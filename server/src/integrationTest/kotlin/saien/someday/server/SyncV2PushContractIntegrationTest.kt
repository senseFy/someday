package saien.someday.server

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.persistence.SyncV2PushRepositoryResult
import saien.someday.server.persistence.SyncV2Repository
import saien.someday.server.support.ConcurrentStartGate
import saien.someday.server.support.GenesisCandidate
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.SyncV2ContractFixture
import saien.someday.server.support.TestServerIdentity

class SyncV2PushContractIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: PostgresContractFixture
    private lateinit var repository: SyncV2Repository
    private lateinit var identity: TestServerIdentity
    private lateinit var genesis: GenesisCandidate

    @BeforeTest
    fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("push-media").toPath())
        database.reset()
        repository = SyncV2Repository(database.config)
        identity = database.seedIdentity("push-${System.nanoTime()}")
        genesis = SyncV2ContractFixture.initializeWorkspace(
            repository,
            identity,
            WORKSPACE_ID,
        )
    }

    @AfterTest
    fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test
    fun secondObjectConflictRollsBackTheWholePushBatch() {
        val stored = SyncV2ContractFixture.entity(genesis, identity.deviceId, "stored")
        assertIs<SyncV2PushRepositoryResult.Accepted>(push(listOf(stored)))

        val fresh = SyncV2ContractFixture.entity(genesis, identity.deviceId, "must-not-commit")
        val conflictingSecond = stored.copy(
            objectDigest = "od2:hmac-sha256:${"f".repeat(64)}",
            ciphertextDigest = "ct2:sha256:${"e".repeat(64)}",
            encodedObjectJson = """{"encryptedEntity":"conflict"}""",
        )
        val rejected = assertIs<SyncV2PushRepositoryResult.Rejected>(
            push(listOf(fresh, conflictingSecond)),
        )
        assertEquals("mutation_reuse_mismatch", rejected.error)

        val pulled = repository.pull(
            identity.userId,
            WORKSPACE_ID,
            genesis.metadata.epochId,
            afterCursor = 0,
            limit = 10,
        )
        assertEquals(listOf(stored.encodedObjectJson), pulled.changes.map { it.encodedObjectJson })
        assertEquals(1L, database.countRows("someday_sync_v2_objects", identity.userId, WORKSPACE_ID))
        assertEquals(1L, database.countRows("someday_sync_v2_changes", identity.userId, WORKSPACE_ID))
        assertEquals(1L, database.countRows("someday_sync_v2_mutations", identity.userId, WORKSPACE_ID))
    }

    @Test
    fun concurrentPushCreatesOneOrderedCursorPerObjectAndMutationReplayStaysImmutable() = runBlocking {
        val objects = (0 until OBJECT_COUNT).map { index ->
            SyncV2ContractFixture.entity(genesis, identity.deviceId, "concurrent-$index")
        }
        val startGate = ConcurrentStartGate(objects.size)

        val pushResults = objects.map { value ->
            async(Dispatchers.IO) {
                startGate.awaitRelease()
                push(listOf(value))
            }
        }.awaitAll()
        assertEquals(OBJECT_COUNT, pushResults.filterIsInstance<SyncV2PushRepositoryResult.Accepted>().size)
        assertTrue(
            pushResults.filterIsInstance<SyncV2PushRepositoryResult.Accepted>()
                .flatMap { it.acknowledgements }
                .none { it.idempotentReplay },
        )

        val pulled = repository.pull(
            identity.userId,
            WORKSPACE_ID,
            genesis.metadata.epochId,
            afterCursor = 0,
            limit = OBJECT_COUNT + 1,
        )
        val cursors = pulled.changes.map { it.cursor }
        assertEquals(OBJECT_COUNT, cursors.distinct().size)
        assertTrue(cursors.zipWithNext().all { (first, second) -> first < second })
        assertEquals(
            objects.map { it.encodedObjectJson }.toSet(),
            pulled.changes.map { it.encodedObjectJson }.toSet(),
        )
        assertEquals(OBJECT_COUNT, pulled.changes.size)
        assertTrue(pulled.complete)

        val replay = assertIs<SyncV2PushRepositoryResult.Accepted>(push(listOf(objects.first())))
        assertTrue(replay.acknowledgements.single().idempotentReplay)

        val reusedMutation = SyncV2ContractFixture.entity(
            genesis,
            identity.deviceId,
            label = "mutation-collision",
            mutationIdentity = "concurrent-0",
        )
        val collision = assertIs<SyncV2PushRepositoryResult.Rejected>(push(listOf(reusedMutation)))
        assertEquals("mutation_reuse_mismatch", collision.error)

        val afterReplay = repository.pull(
            identity.userId,
            WORKSPACE_ID,
            genesis.metadata.epochId,
            afterCursor = 0,
            limit = OBJECT_COUNT + 1,
        )
        assertEquals(cursors, afterReplay.changes.map { it.cursor })
        assertEquals(OBJECT_COUNT.toLong(), database.countRows("someday_sync_v2_objects", identity.userId, WORKSPACE_ID))
        assertEquals(OBJECT_COUNT.toLong(), database.countRows("someday_sync_v2_changes", identity.userId, WORKSPACE_ID))
        assertEquals(OBJECT_COUNT.toLong(), database.countRows("someday_sync_v2_mutations", identity.userId, WORKSPACE_ID))
    }

    private fun push(objects: List<saien.someday.server.persistence.SyncV2ObjectInput>) =
        repository.push(
            identity.userId,
            WORKSPACE_ID,
            identity.deviceId,
            genesis.metadata.epochId,
            writerProtocolVersion = 2,
            objects = objects,
        )

    private companion object {
        const val WORKSPACE_ID = "workspace-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val OBJECT_COUNT = 24
    }
}
