package saien.someday.server

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.persistence.SyncV2PushRepositoryResult
import saien.someday.server.persistence.SyncV2Repository
import saien.someday.server.support.GenesisCandidate
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.SyncV2ContractFixture
import saien.someday.server.support.TestServerIdentity

class SyncV2PullContractIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: PostgresContractFixture
    private lateinit var repository: SyncV2Repository
    private lateinit var identity: TestServerIdentity
    private lateinit var genesis: GenesisCandidate

    @BeforeTest
    fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("pull-media").toPath())
        database.reset()
        repository = SyncV2Repository(database.config)
        identity = database.seedIdentity("pull-${System.nanoTime()}")
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
    fun paginationMarksOnlyTheLastPageCompleteAndAheadCursorDetectsRollback() {
        val objects = (0 until 3).map { index ->
            SyncV2ContractFixture.entity(genesis, identity.deviceId, "page-$index")
        }
        assertIs<SyncV2PushRepositoryResult.Accepted>(
            repository.push(
                identity.userId,
                WORKSPACE_ID,
                identity.deviceId,
                genesis.metadata.epochId,
                writerProtocolVersion = 2,
                objects = objects,
            ),
        )

        val first = pull(after = 0, limit = 2)
        assertEquals(2, first.changes.size)
        assertTrue(!first.complete)
        assertNull(first.error)

        val second = pull(after = first.changes.last().cursor, limit = 2)
        assertEquals(1, second.changes.size)
        assertTrue(second.complete)
        assertNull(second.error)

        val stitched = first.changes + second.changes
        assertEquals(3, stitched.map { it.cursor }.distinct().size)
        assertTrue(stitched.map { it.cursor }.zipWithNext().all { (left, right) -> left < right })
        assertEquals(objects.map { it.encodedObjectJson }, stitched.map { it.encodedObjectJson })

        val exactBoundary = pull(after = 0, limit = 3)
        assertEquals(stitched, exactBoundary.changes)
        assertTrue(exactBoundary.complete)

        val exhausted = pull(after = stitched.last().cursor, limit = 2)
        assertTrue(exhausted.changes.isEmpty())
        assertTrue(exhausted.complete)
        assertNull(exhausted.error)

        val ahead = pull(after = stitched.last().cursor + 1, limit = 2)
        assertTrue(ahead.changes.isEmpty())
        assertTrue(ahead.complete)
        assertEquals("remote_rollback_detected", ahead.error)
    }

    private fun pull(after: Long, limit: Int) = repository.pull(
        identity.userId,
        WORKSPACE_ID,
        genesis.metadata.epochId,
        afterCursor = after,
        limit = limit,
    )

    private companion object {
        const val WORKSPACE_ID = "workspace-cccccccccccccccccccccccccccccccc"
    }
}
