@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.integration

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import okio.Buffer
import okio.buffer
import saien.someday.data.media.MediaAssetImportRequest
import saien.someday.domain.notes.NoteInput
import saien.someday.integration.testkit.RealSelfHostedFixture
import saien.someday.integration.testkit.adoptWorkspaceFrom
import saien.someday.integration.testkit.assertSuccessfulSync

class SelfHostedMediaJourneyTest {
    @Test
    fun imagePublishesBeforeItsNoteAndFollowerMaterializesTheOriginalLazily() {
        RealSelfHostedFixture.create("media-journey").use { fixture ->
            val leader = fixture.newDevice("desktop-leader", "desktop")
            val follower = fixture.newDevice("android-follower", "android")
            leader.connect(createAccount = true)
            follower.connect(createAccount = false)

            val imported = leader.services.localMediaAssetStore.importAsset(
                source = Buffer().write(PNG_1X1),
                request = MediaAssetImportRequest(
                    mediaType = "image/png",
                    originalFileName = "tiny-private-image.png",
                ),
            )
            val assetId = imported.asset.metadata.id
            val notebook = leader.services.notesRepository.createNotebook("Image journal")
            val note = leader.services.notesRepository.createNote(
                NoteInput(
                    notebookId = notebook.id,
                    title = "Image note",
                    markdownBody = "before\n\n![tiny](someday-asset://${assetId.value})\n\nafter",
                    createdAt = CREATED_AT,
                    timeZoneId = "UTC",
                ),
            )

            var entityPublicationBoundariesObserved = 0
            fixture.transport.beforeEntityPublication = { endpoint, accessToken, workspaceId ->
                entityPublicationBoundariesObserved += 1
                assertEquals(leader.workspaceKeys.workspaceIdOrNull(), workspaceId)
                assertNotNull(
                    fixture.transport.headMediaObject(endpoint, accessToken, workspaceId, assetId.value),
                    "The immutable image must already be durable before entity publication starts.",
                )
            }
            try {
                leader.assertSuccessfulSync()
            } finally {
                fixture.transport.beforeEntityPublication = null
            }
            assertTrue(entityPublicationBoundariesObserved > 0, "The journey must cross a real entity publication boundary.")

            follower.adoptWorkspaceFrom(leader)
            follower.assertSuccessfulSync()
            val received = assertNotNull(follower.services.notesRepository.getNoteDetails(note.id))
            assertTrue(received.markdownBody.contains("someday-asset://${assetId.value}"))
            assertNull(
                follower.services.localMediaAssetStore.getAsset(assetId),
                "Entity bootstrap must not eagerly download image bytes.",
            )

            val materialized = follower.services.mediaCoordinator.materialize(assetId)
            assertTrue(materialized.downloaded)
            assertEquals(assetId, materialized.asset.metadata.id)
            val restoredBytes = follower.services.localMediaAssetStore.openSource(assetId).buffer().use { source ->
                source.readByteArray()
            }
            assertContentEquals(PNG_1X1, restoredBytes)
        }
    }

    private companion object {
        val CREATED_AT = Instant.parse("2026-08-25T04:00:00Z")
        val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
