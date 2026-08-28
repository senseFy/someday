package saien.someday.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import saien.someday.server.api.SyncV2PullRequest
import saien.someday.server.api.SyncV2PullResponse
import saien.someday.server.api.SyncV2PushRequest
import saien.someday.server.support.ConcurrentStartGate
import saien.someday.server.support.SYNC_V2_HTTP_JSON
import saien.someday.server.support.WORKSPACE_ID
import saien.someday.server.support.checkpoint
import saien.someday.server.support.clearServerTables
import saien.someday.server.support.entityObject
import saien.someday.server.support.postJson
import saien.someday.server.support.publishEpoch
import saien.someday.server.support.registerAccountAndDevice

class SyncV2CursorApiContractIntegrationTest {
    @AfterTest
    fun tearDown() = clearServerTables()

    @Test
    fun concurrentPushPullPagesExposeOneContinuousAuthenticatedCursorChain() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccountAndDevice()
        val prepared = checkpoint(account.device.id, previous = null)
        publishEpoch(account.accessToken, prepared)
        val objects = (0 until 8).map { index ->
            entityObject(
                prepared,
                account.device.id,
                "00000000-0000-4000-8000-${(index + 1_000).toString().padStart(12, '0')}",
                "Concurrent $index",
            )
        }
        val startGate = ConcurrentStartGate(objects.size)

        val pushes = coroutineScope {
            objects.map { value ->
                async(Dispatchers.IO) {
                    startGate.awaitRelease()
                    postJson(
                        "/sync/v3/workspaces/$WORKSPACE_ID/entities/push",
                        account.accessToken,
                        SyncV2PushRequest(prepared.descriptor.syncEpochId, 2, listOf(value)),
                    )
                }
            }.awaitAll()
        }
        assertTrue(pushes.all { it.status == HttpStatusCode.OK }, pushes.joinToString { it.body })

        val seenObjectIds = mutableListOf<String>()
        var afterCursor: Long? = null
        var previousNumericCursor: Long? = null
        while (true) {
            val response = postJson(
                "/sync/v3/workspaces/$WORKSPACE_ID/entities/pull",
                account.accessToken,
                SyncV2PullRequest(prepared.descriptor.syncEpochId, afterCursor, limit = 3),
            )
            assertEquals(HttpStatusCode.OK, response.status, response.body)
            val page = SYNC_V2_HTTP_JSON.decodeFromString<SyncV2PullResponse>(response.body)
            assertNull(page.error)

            var expectedCursor = afterCursor?.toString()
            page.units.forEach { unit ->
                assertEquals(expectedCursor, unit.expectedCursorValue)
                val next = unit.nextCursorValue.toLong()
                previousNumericCursor?.let { previous -> assertTrue(next > previous) }
                previousNumericCursor = next
                expectedCursor = unit.nextCursorValue
                seenObjectIds += unit.objects.single().objectId
            }
            if (page.complete) break
            assertTrue(page.units.isNotEmpty())
            afterCursor = page.units.last().nextCursorValue.toLong()
        }

        assertEquals(objects.map { it.objectId }.toSet(), seenObjectIds.toSet())
        assertEquals(objects.size, seenObjectIds.size)
    }
}
