package saien.someday.server

import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.auth.AccountError
import saien.someday.server.auth.AccountProtocolExpectation
import saien.someday.server.auth.AccountProtocolFailure
import saien.someday.server.auth.AccountRequestContext
import saien.someday.server.media.MediaBlobKey
import saien.someday.server.media.MediaBlobMetadata
import saien.someday.server.media.MediaBlobStore
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountLockMode
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.persistence.DatabaseConnectionPool
import saien.someday.server.persistence.SyncV2Repository
import saien.someday.server.persistence.SystemV3MediaPutResult
import saien.someday.server.persistence.SystemV3MediaRepository
import saien.someday.server.persistence.SystemV3MediaReadResult
import saien.someday.server.persistence.WorkspaceRecoveryEnvelopeInput
import saien.someday.server.persistence.WorkspaceRecoveryEnvelopeRepository
import saien.someday.server.persistence.WorkspaceAdmissionRepository
import saien.someday.server.persistence.WorkspaceAdmissionSnapshot
import saien.someday.server.persistence.SyncV2PointerPublishRepositoryResult
import saien.someday.server.support.ControllableMediaBlobStore
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.SyncV2ContractFixture
import saien.someday.server.support.TestServerIdentity

class AccountContentAdmissionIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var database: PostgresContractFixture
    private lateinit var identity: TestServerIdentity
    private lateinit var entities: SyncV2Repository
    private lateinit var media: SystemV3MediaRepository
    private lateinit var blobs: ControllableMediaBlobStore

    @BeforeTest
    fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("content-admission").toPath(), 1024)
        database.reset()
        identity = database.seedIdentity()
        entities = SyncV2Repository(database.config)
        blobs = ControllableMediaBlobStore()
        media = SystemV3MediaRepository(database.config, blobs)
    }

    @AfterTest
    fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test
    fun revokedSessionCannotCreateEitherWorkspaceRegistryOrPublishBlob() {
        connection().use { connection ->
            connection.prepareStatement("UPDATE someday_sessions SET revoked_at = NOW() WHERE id = ?").use { statement ->
                statement.setObject(1, identity.sessionId)
                statement.executeUpdate()
            }
        }
        val candidate = SyncV2ContractFixture.genesis("denied-registry")
        assertFailure(AccountError.UNAUTHORIZED) {
            entities.putCheckpointChunk(identity.request, OLD_WORKSPACE, candidate.chunk)
        }
        val bytes = ByteArray(64) { 1 }
        assertFailure(AccountError.UNAUTHORIZED) {
            media.putObject(identity.request, NEW_WORKSPACE, MEDIA_ID, sha256(bytes), bytes)
        }
        assertEquals(0L, database.countRows("someday_entity_workspaces", identity.userId, OLD_WORKSPACE))
        assertEquals(0L, database.countRows("someday_entity_workspaces", identity.userId, NEW_WORKSPACE))
        assertNull(blobs.bytes(MediaBlobKey(identity.userId, NEW_WORKSPACE, MEDIA_ID)))
    }

    @Test
    fun admittedMediaReadReleasesItsOnlyConnectionAndAccountLockBeforeBlobIo() {
        val bytes = ByteArray(64) { 3 }
        media.putObject(identity.request, OLD_WORKSPACE, MEDIA_ID, sha256(bytes), bytes)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingStore = object : MediaBlobStore by blobs {
            override fun head(key: MediaBlobKey): MediaBlobMetadata? {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "Blocked media HEAD was not released" }
                return blobs.head(key)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        DatabaseConnectionPool.create(database.config.copy(databaseMaxPoolSize = 1)).use { connections ->
            try {
                val readingRepository = SystemV3MediaRepository(database.config, blockingStore, connections)
                val reading = executor.submit<SystemV3MediaReadResult<*>> {
                    readingRepository.readObject(identity.request, OLD_WORKSPACE, MEDIA_ID)
                }
                assertTrue(entered.await(30, TimeUnit.SECONDS), "Read never reached blob IO")
                // With a pool of one this checkout cannot complete until capture
                // returned its connection; the exclusive transition also proves
                // the read did not retain shared account admission around blob IO.
                connections.connection().use { assertTrue(it.isValid(2)) }
                advanceSyntheticAccount()
                release.countDown()
                assertIs<SystemV3MediaReadResult.Found<*>>(reading.get(30, TimeUnit.SECONDS))
                assertFailure(AccountError.ACCOUNT_SESSION_STALE) {
                    readingRepository.readObject(identity.request, OLD_WORKSPACE, MEDIA_ID)
                }
            } finally {
                release.countDown()
                executor.shutdownNow()
                check(executor.awaitTermination(30, TimeUnit.SECONDS)) { "Media reader did not stop" }
            }
        }
    }

    @Test
    fun newIncarnationCanPublishItsFirstWorkspaceWithoutCountingRetiredAuthorities() {
        SyncV2ContractFixture.initializeWorkspace(entities, identity, OLD_WORKSPACE, "before-reset")
        val recovery = WorkspaceRecoveryEnvelopeRepository(database.config)
        recovery.put(identity.request, WorkspaceRecoveryEnvelopeInput(OLD_WORKSPACE, "a".repeat(32), "opaque", "A".repeat(43), null))
        val current = advanceSyntheticAccount()
        val discovery = WorkspaceAdmissionRepository(database.config)
        assertEquals(WorkspaceAdmissionSnapshot(0, false, false), discovery.discover(current, NEW_WORKSPACE))
        assertFailure(AccountError.WORKSPACE_INCARNATION_RETIRED) { discovery.discover(current, OLD_WORKSPACE) }
        assertFailure(AccountError.ACCOUNT_SESSION_STALE) { discovery.discover(identity.request, NEW_WORKSPACE) }

        // Actual reset removes the account-current recovery pointer. The deliberately
        // retained pointer above proved discovery cannot treat a retired envelope as usable.
        connection().use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT set_config('someday.user_id', ?, true)").use { statement ->
                statement.setString(1, identity.userId.toString())
                statement.executeQuery().close()
            }
            connection.createStatement().use { it.executeQuery("SELECT set_config('someday.workspace_id', '*', true)").close() }
            connection.prepareStatement("DELETE FROM workspace_recovery_envelopes WHERE user_id = ?").use { statement ->
                statement.setObject(1, identity.userId)
                statement.executeUpdate()
            }
            connection.commit()
        }
        val candidate = SyncV2ContractFixture.genesis("after-reset")
        entities.putCheckpointChunk(current, NEW_WORKSPACE, candidate.chunk)
        entities.putCheckpointManifest(current, NEW_WORKSPACE, candidate.manifest)
        assertIs<SyncV2PointerPublishRepositoryResult.Published>(
            entities.compareAndSetEpoch(current, NEW_WORKSPACE, null, candidate.metadata, candidate.pointerObjectJson),
        )
        assertEquals(WorkspaceAdmissionSnapshot(1, true, false), discovery.discover(current, NEW_WORKSPACE))
    }

    @Test
    fun retiredAuthorityCannotReplayWhileCurrentQuotaAndBlobLayoutExcludeRetiredWorkspace() {
        SyncV2ContractFixture.initializeWorkspace(entities, identity, OLD_WORKSPACE, "retired-content")
        val bytes = ByteArray(700) { 2 }
        assertIs<SystemV3MediaPutResult.Stored>(media.putObject(identity.request, OLD_WORKSPACE, MEDIA_ID, sha256(bytes), bytes))
        val recovery = WorkspaceRecoveryEnvelopeRepository(database.config)
        val envelope = WorkspaceRecoveryEnvelopeInput(OLD_WORKSPACE, "a".repeat(32), "opaque", "A".repeat(43), null)
        recovery.put(identity.request, envelope)

        val current = advanceSyntheticAccount()
        // Even a forged current header cannot retag the historical session.
        val retagged = identity.request.copy(protocol = current.protocol)
        assertFailure(AccountError.ACCOUNT_SESSION_STALE) { entities.loadEpoch(retagged, OLD_WORKSPACE) }
        assertFailure(AccountError.ACCOUNT_SESSION_STALE) {
            media.putObject(retagged, OLD_WORKSPACE, MEDIA_ID, sha256(bytes), bytes)
        }
        assertFailure(AccountError.ACCOUNT_SESSION_STALE) { recovery.put(retagged, envelope) }
        assertFailure(AccountError.WORKSPACE_INCARNATION_RETIRED) { entities.loadEpoch(current, OLD_WORKSPACE) }
        assertFailure(AccountError.WORKSPACE_INCARNATION_RETIRED) { media.headObject(current, OLD_WORKSPACE, MEDIA_ID) }
        assertFailure(AccountError.WORKSPACE_INCARNATION_RETIRED) { recovery.load(current) }

        assertIs<SystemV3MediaPutResult.Stored>(media.putObject(current, NEW_WORKSPACE, MEDIA_ID, sha256(bytes), bytes))
        val incarnation = (current.protocol as AccountProtocolExpectation.V1).incarnation
        assertContentEquals(bytes, blobs.bytes(MediaBlobKey(identity.userId, NEW_WORKSPACE, MEDIA_ID, incarnation)))
        assertNull(blobs.bytes(MediaBlobKey(identity.userId, NEW_WORKSPACE, MEDIA_ID)))
        assertEquals(1400L, database.mediaBytes(identity.userId))
    }

    /** Test-only state transition: deliberately retain old rows to exercise every admission fence. */
    private fun advanceSyntheticAccount(): AccountRequestContext {
        val incarnation = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        AccountAdmission.transaction(DatabaseConnectionProvider(::connection), listOf(identity.userId), AccountLockMode.EXCLUSIVE) { connection ->
            connection.prepareStatement(
                "UPDATE someday_account_data_incarnations SET state = 'retired', retired_at = clock_timestamp() WHERE user_id = ? AND state = 'active'",
            ).use { statement ->
                statement.setObject(1, identity.userId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) VALUES (?, ?, 'active', 'incarnation-v1', NOW())",
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.setObject(2, incarnation)
                statement.executeUpdate()
            }
            connection.prepareStatement("UPDATE someday_devices SET data_incarnation = ? WHERE user_id = ? AND id = ?").use { statement ->
                statement.setObject(1, incarnation)
                statement.setObject(2, identity.userId)
                statement.setObject(3, identity.deviceId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO someday_sessions(id, user_id, device_id, expires_at, data_incarnation) VALUES (?, ?, ?, NOW() + INTERVAL '1 hour', ?)",
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, identity.userId)
                statement.setObject(3, identity.deviceId)
                statement.setObject(4, incarnation)
                statement.executeUpdate()
            }
        }
        return identity.request.copy(sessionId = sessionId, protocol = AccountProtocolExpectation.V1(incarnation))
    }

    private fun assertFailure(error: AccountError, block: () -> Unit) {
        assertEquals(error, assertFailsWith<AccountProtocolFailure>(block = block).error)
    }

    private fun connection(): Connection = DriverManager.getConnection(
        database.config.databaseConnectionUrl, database.config.databaseUser, database.config.databasePassword,
    )

    private fun sha256(bytes: ByteArray): String =
        "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"

    private companion object {
        const val OLD_WORKSPACE = "workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val NEW_WORKSPACE = "workspace-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val MEDIA_ID = "11".repeat(32)
    }
}
