package saien.someday.server

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.persistence.SyncV2EpochMetadataRecord
import saien.someday.server.persistence.SyncV2PointerPublishRepositoryResult
import saien.someday.server.persistence.SyncV2Repository
import saien.someday.server.persistence.WorkspaceRecoveryEnvelopeInput
import saien.someday.server.persistence.WorkspaceRecoveryEnvelopePutResult
import saien.someday.server.persistence.WorkspaceRecoveryEnvelopeRepository
import saien.someday.server.support.ConcurrentStartGate
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.SyncV2ContractFixture
import saien.someday.server.support.TestServerIdentity

class SyncV2EpochContractIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: PostgresContractFixture
    private lateinit var repository: SyncV2Repository
    private lateinit var identity: TestServerIdentity

    @BeforeTest
    fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("epoch-media").toPath())
        database.reset()
        repository = SyncV2Repository(database.config)
        identity = database.seedIdentity("epoch-${System.nanoTime()}")
    }

    @AfterTest
    fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test
    fun concurrentGenesisCasHasExactlyOneWinnerAndWinnerReplayIsIdempotent() = runBlocking {
        val candidates = listOf(
            SyncV2ContractFixture.genesis("candidate-a"),
            SyncV2ContractFixture.genesis("candidate-b"),
        )
        candidates.forEach {
            SyncV2ContractFixture.prepareGenesis(repository, identity, WORKSPACE_ID, it)
        }
        val startGate = ConcurrentStartGate(candidates.size)

        val results = candidates.map { candidate ->
            async(Dispatchers.IO) {
                startGate.awaitRelease()
                repository.compareAndSetEpoch(
                    identity.userId,
                    WORKSPACE_ID,
                    expectedCurrentDigest = null,
                    metadata = candidate.metadata,
                    pointerObjectJson = candidate.pointerObjectJson,
                )
            }
        }.awaitAll()

        val winnerIndex = results.indexOfFirst {
            it is SyncV2PointerPublishRepositoryResult.Published && !it.idempotentReplay
        }
        assertTrue(winnerIndex >= 0)
        assertEquals(
            1,
            results.count {
                it is SyncV2PointerPublishRepositoryResult.Published && !it.idempotentReplay
            },
        )
        assertEquals(1, results.count { it is SyncV2PointerPublishRepositoryResult.CompareAndSetFailed })

        val winner = candidates[winnerIndex]
        val replay = assertIs<SyncV2PointerPublishRepositoryResult.Published>(
            repository.compareAndSetEpoch(
                identity.userId,
                WORKSPACE_ID,
                expectedCurrentDigest = null,
                metadata = winner.metadata,
                pointerObjectJson = winner.pointerObjectJson,
            ),
        )
        assertTrue(replay.idempotentReplay)
        assertEquals(winner.metadata, repository.loadEpoch(identity.userId, WORKSPACE_ID)?.metadata)
        assertFalse(
            results.filterIsInstance<SyncV2PointerPublishRepositoryResult.Published>()
                .any { it.idempotentReplay },
        )
    }

    @Test
    fun genesisCasObservablyWaitsForTheWorkspaceAdvisoryLock() {
        val candidate = SyncV2ContractFixture.genesis("observable-workspace-lock")
        SyncV2ContractFixture.prepareGenesis(repository, identity, WORKSPACE_ID, candidate)
        val applicationName = "workspace_lock_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val waitingRepository = SyncV2Repository(database.configWithApplicationName(applicationName))
        val executor = Executors.newSingleThreadExecutor()

        try {
            val future = database.holdWorkspaceAdvisoryLock(identity.userId, WORKSPACE_ID).use {
                val pending = executor.submit<SyncV2PointerPublishRepositoryResult> {
                    waitingRepository.compareAndSetEpoch(
                        identity.userId,
                        WORKSPACE_ID,
                        expectedCurrentDigest = null,
                        metadata = candidate.metadata,
                        pointerObjectJson = candidate.pointerObjectJson,
                    )
                }
                database.awaitAdvisoryLockWait(applicationName, pending::isDone)
                pending
            }
            val published = assertIs<SyncV2PointerPublishRepositoryResult.Published>(
                future.get(30, TimeUnit.SECONDS),
            )
            assertTrue(!published.idempotentReplay)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun accountRecoveryPointerRejectsOnlyCompetingGenesisAndPreservesExistingWorkspaces() {
        val recoveryRepository = WorkspaceRecoveryEnvelopeRepository(database.config)
        val existingA = SyncV2ContractFixture.initializeWorkspace(
            repository,
            identity,
            WORKSPACE_ID,
            "existing-a",
        )
        SyncV2ContractFixture.initializeWorkspace(
            repository,
            identity,
            RECOVERY_WORKSPACE_ID,
            "recovery-b",
        )
        val stored = assertIs<WorkspaceRecoveryEnvelopePutResult.Stored>(
            recoveryRepository.put(identity.userId, identity.deviceId, recoveryInput()),
        )
        assertTrue(stored.created)
        val competing = SyncV2ContractFixture.genesis("competing-c")
        SyncV2ContractFixture.prepareGenesis(repository, identity, COMPETING_WORKSPACE_ID, competing)

        val rejected = assertIs<SyncV2PointerPublishRepositoryResult.Rejected>(
            repository.compareAndSetEpoch(
                identity.userId,
                COMPETING_WORKSPACE_ID,
                expectedCurrentDigest = null,
                metadata = competing.metadata,
                pointerObjectJson = competing.pointerObjectJson,
            ),
        )
        val existingReplay = assertIs<SyncV2PointerPublishRepositoryResult.Published>(
            repository.compareAndSetEpoch(
                identity.userId,
                WORKSPACE_ID,
                expectedCurrentDigest = null,
                metadata = existingA.metadata,
                pointerObjectJson = existingA.pointerObjectJson,
            ),
        )

        assertEquals("workspace_recovery_required", rejected.error)
        assertEquals(null, repository.loadEpoch(identity.userId, COMPETING_WORKSPACE_ID))
        assertTrue(existingReplay.idempotentReplay)
    }

    @Test
    fun genesisAndRecoveryPublicationShareTheObservableAccountLock() {
        val candidate = SyncV2ContractFixture.genesis("account-lock-genesis")
        SyncV2ContractFixture.prepareGenesis(repository, identity, WORKSPACE_ID, candidate)
        val casApplicationName = "recovery_cas_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val waitingCasRepository = SyncV2Repository(database.configWithApplicationName(casApplicationName))
        val executor = Executors.newSingleThreadExecutor()

        try {
            val casFuture = database.holdWorkspaceRecoveryAccountAdvisoryLock(identity.userId).use {
                val pending = executor.submit<SyncV2PointerPublishRepositoryResult> {
                    waitingCasRepository.compareAndSetEpoch(
                        identity.userId,
                        WORKSPACE_ID,
                        expectedCurrentDigest = null,
                        metadata = candidate.metadata,
                        pointerObjectJson = candidate.pointerObjectJson,
                    )
                }
                database.awaitAdvisoryLockWait(casApplicationName, pending::isDone)
                pending
            }
            assertIs<SyncV2PointerPublishRepositoryResult.Published>(
                casFuture.get(30, TimeUnit.SECONDS),
            )

            val recoveryApplicationName =
                "recovery_put_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val waitingRecoveryRepository = WorkspaceRecoveryEnvelopeRepository(
                database.configWithApplicationName(recoveryApplicationName),
            )
            val recoveryFuture = database.holdWorkspaceRecoveryAccountAdvisoryLock(identity.userId).use {
                val pending = executor.submit<WorkspaceRecoveryEnvelopePutResult> {
                    waitingRecoveryRepository.put(
                        identity.userId,
                        identity.deviceId,
                        recoveryInput(workspaceId = WORKSPACE_ID),
                    )
                }
                database.awaitAdvisoryLockWait(recoveryApplicationName, pending::isDone)
                pending
            }
            assertIs<WorkspaceRecoveryEnvelopePutResult.Stored>(
                recoveryFuture.get(30, TimeUnit.SECONDS),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun accountLockWaitersRefreshSnapshotsDespiteRepeatableReadConnectionDefaults() {
        SyncV2ContractFixture.initializeWorkspace(
            repository,
            identity,
            RECOVERY_WORKSPACE_ID,
            "repeatable-read-recovery",
        )
        val competing = SyncV2ContractFixture.genesis("repeatable-read-competing")
        SyncV2ContractFixture.prepareGenesis(repository, identity, COMPETING_WORKSPACE_ID, competing)
        val casApplicationName = "rr_recovery_cas_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val casConfig = database.configWithApplicationName(casApplicationName)
        val waitingCasRepository = SyncV2Repository(
            casConfig,
            RepeatableReadConnectionProvider(casConfig),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val casFuture = database.holdWorkspaceRecoveryAccountAdvisoryLock(identity.userId).use { held ->
                val pending = executor.submit<SyncV2PointerPublishRepositoryResult> {
                    waitingCasRepository.compareAndSetEpoch(
                        identity.userId,
                        COMPETING_WORKSPACE_ID,
                        expectedCurrentDigest = null,
                        metadata = competing.metadata,
                        pointerObjectJson = competing.pointerObjectJson,
                    )
                }
                database.awaitAdvisoryLockWait(casApplicationName, pending::isDone)
                held.commit { connection ->
                    insertRecoveryEnvelope(connection, identity, recoveryInput())
                }
                pending
            }
            val rejected = assertIs<SyncV2PointerPublishRepositoryResult.Rejected>(
                casFuture.get(30, TimeUnit.SECONDS),
            )
            assertEquals("workspace_recovery_required", rejected.error)
            assertEquals(null, repository.loadEpoch(identity.userId, COMPETING_WORKSPACE_ID))

            val secondIdentity = database.seedIdentity("repeatable-read-epoch")
            val initialized = SyncV2ContractFixture.genesis("repeatable-read-epoch")
            SyncV2ContractFixture.prepareGenesis(repository, secondIdentity, WORKSPACE_ID, initialized)
            val recoveryApplicationName =
                "rr_recovery_put_${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val recoveryConfig = database.configWithApplicationName(recoveryApplicationName)
            val waitingRecoveryRepository = WorkspaceRecoveryEnvelopeRepository(
                recoveryConfig,
                RepeatableReadConnectionProvider(recoveryConfig),
            )
            val recoveryFuture = database.holdWorkspaceRecoveryAccountAdvisoryLock(secondIdentity.userId).use { held ->
                val pending = executor.submit<WorkspaceRecoveryEnvelopePutResult> {
                    waitingRecoveryRepository.put(
                        secondIdentity.userId,
                        secondIdentity.deviceId,
                        recoveryInput(workspaceId = WORKSPACE_ID),
                    )
                }
                database.awaitAdvisoryLockWait(recoveryApplicationName, pending::isDone)
                held.commit { connection ->
                    insertEpoch(
                        connection,
                        secondIdentity,
                        WORKSPACE_ID,
                        initialized.metadata,
                        initialized.pointerObjectJson,
                    )
                }
                pending
            }
            assertIs<WorkspaceRecoveryEnvelopePutResult.Stored>(
                recoveryFuture.get(30, TimeUnit.SECONDS),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun recoveryInput(
        workspaceId: String = RECOVERY_WORKSPACE_ID,
    ): WorkspaceRecoveryEnvelopeInput = WorkspaceRecoveryEnvelopeInput(
        workspaceId = workspaceId,
        keyFingerprint = "0123456789abcdef0123456789abcdef",
        envelopeJson = "{\"opaque\":\"recovery\"}",
        envelopeDigest = "A".repeat(43),
        expectedRevision = null,
    )

    private fun insertRecoveryEnvelope(
        connection: Connection,
        identity: TestServerIdentity,
        input: WorkspaceRecoveryEnvelopeInput,
    ) {
        selectScope(connection, identity.userId, "*")
        connection.prepareStatement(
            """
            INSERT INTO workspace_recovery_envelopes (
                user_id, workspace_id, key_fingerprint, envelope_json, envelope_digest,
                revision, created_by_device_id, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, 1, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, identity.userId)
            statement.setString(2, input.workspaceId)
            statement.setString(3, input.keyFingerprint)
            statement.setString(4, input.envelopeJson)
            statement.setString(5, input.envelopeDigest)
            statement.setObject(6, identity.deviceId)
            statement.setObject(7, identity.deviceId)
            statement.executeUpdate()
        }
    }

    private fun insertEpoch(
        connection: Connection,
        identity: TestServerIdentity,
        workspaceId: String,
        metadata: SyncV2EpochMetadataRecord,
        pointerObjectJson: String,
    ) {
        selectScope(connection, identity.userId, workspaceId)
        connection.prepareStatement(
            """
            INSERT INTO someday_sync_v2_epochs (
                user_id, workspace_id, epoch_id, pointer_digest, pointer_object_json,
                contract_id, schema_set_version, semantic_protocol_version,
                minimum_writer_protocol_version, key_set_version, remote_profile,
                metadata_privacy_mode, supported_offline_window_seconds,
                checkpoint_id, checkpoint_digest
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, identity.userId)
            statement.setString(2, workspaceId)
            statement.setString(3, metadata.epochId)
            statement.setString(4, metadata.pointerDigest)
            statement.setString(5, pointerObjectJson)
            statement.setString(6, metadata.contractId)
            statement.setString(7, metadata.schemaSetVersion)
            statement.setInt(8, metadata.semanticProtocolVersion)
            statement.setInt(9, metadata.minimumWriterProtocolVersion)
            statement.setString(10, metadata.keySetVersion)
            statement.setString(11, metadata.remoteProfile)
            statement.setString(12, metadata.metadataPrivacyMode)
            statement.setLong(13, metadata.supportedOfflineWindowSeconds)
            statement.setString(14, metadata.checkpointId)
            statement.setString(15, metadata.checkpointDigest)
            statement.executeUpdate()
        }
    }

    private fun selectScope(connection: Connection, userId: UUID, workspaceId: String) {
        connection.prepareStatement("SELECT set_config('someday.user_id', ?, true)").use { statement ->
            statement.setString(1, userId.toString())
            statement.executeQuery().close()
        }
        connection.prepareStatement("SELECT set_config('someday.workspace_id', ?, true)").use { statement ->
            statement.setString(1, workspaceId)
            statement.executeQuery().close()
        }
    }

    private companion object {
        const val WORKSPACE_ID = "workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val RECOVERY_WORKSPACE_ID = "workspace-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val COMPETING_WORKSPACE_ID = "workspace-cccccccccccccccccccccccccccccccc"
    }
}

private class RepeatableReadConnectionProvider(
    private val config: ServerConfig,
) : DatabaseConnectionProvider {
    override fun connection(): Connection = DriverManager.getConnection(
        config.databaseConnectionUrl,
        config.databaseUser,
        config.databasePassword,
    ).also { connection ->
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
    }
}
