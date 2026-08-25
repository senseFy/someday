package saien.someday.server

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
import saien.someday.server.persistence.SyncV2PointerPublishRepositoryResult
import saien.someday.server.persistence.SyncV2Repository
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

    private companion object {
        const val WORKSPACE_ID = "workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
