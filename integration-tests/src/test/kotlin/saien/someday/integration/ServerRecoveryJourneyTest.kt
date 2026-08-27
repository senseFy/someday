@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.integration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import okio.Buffer
import okio.buffer
import saien.someday.data.media.MediaAssetImportRequest
import saien.someday.domain.notes.NoteInput
import saien.someday.integration.testkit.RealSelfHostedFixture
import saien.someday.integration.testkit.assertSuccessfulSync

/** Holds an intact paired device in memory while the server recovery unit is replaced. */
class ServerRecoveryJourneyTest {
    @Test
    fun contentEmptyPairedDeviceReadsTextAndImageBeforeWritesAreEnabled() {
        RealSelfHostedFixture.create("recovery-journey").use { fixture ->
            val source = fixture.newDevice("recovery-source", "desktop")
            val verifier = fixture.newDevice("recovery-verifier", "desktop")
            source.connect(createAccount = true)
            verifier.connect(createAccount = false)

            val notebook = source.services.notesRepository.createNotebook("Recovery journal")
            val imported = source.services.localMediaAssetStore.importAsset(
                source = Buffer().write(PNG_1X1),
                request = MediaAssetImportRequest(
                    mediaType = "image/png",
                    originalFileName = "recovery-proof.png",
                ),
            )
            val mediaId = imported.asset.metadata.id
            val note = source.services.notesRepository.createNote(
                NoteInput(
                    notebookId = notebook.id,
                    title = "Recovered text",
                    markdownBody = "non-empty recovery proof\n\n![proof](someday-asset://${mediaId.value})",
                    createdAt = CREATED_AT,
                    timeZoneId = "UTC",
                ),
            )
            source.assertSuccessfulSync()

            val invitation = source.pairing.createInvitation()
            assertTrue(invitation.success, invitation.diagnosticMessage ?: invitation.reason.name)
            val joined = verifier.pairing.joinWithToken(assertNotNull(invitation.invitation).revealManualToken())
            assertTrue(joined.success, joined.diagnosticMessage ?: joined.reason.name)
            assertNull(verifier.services.notesRepository.getNoteDetails(note.id))
            assertTrue(verifier.database.somedayQueries.selectAllMediaAssets().executeAsList().isEmpty())

            awaitIsolatedRestore()

            verifier.assertSuccessfulSync()
            val restored = assertNotNull(verifier.services.notesRepository.getNoteDetails(note.id))
            assertEquals("Recovered text", restored.title)
            assertTrue(restored.markdownBody.contains("someday-asset://${mediaId.value}"))
            val materialized = verifier.services.mediaCoordinator.materialize(mediaId)
            assertTrue(materialized.downloaded)
            val restoredBytes = verifier.services.localMediaAssetStore.openSource(mediaId).buffer().use {
                it.readByteArray()
            }
            assertContentEquals(PNG_1X1, restoredBytes)

            verifier.services.notesRepository.createNote(
                NoteInput(
                    notebookId = notebook.id,
                    title = "must not upload",
                    markdownBody = "recovery verification remains read-only",
                    createdAt = CREATED_AT,
                    timeZoneId = "UTC",
                ),
            )
            val rejectedEntityWrite = verifier.services.manualSyncRunner.run()
            assertFalse(rejectedEntityWrite.success, "The recovery endpoint accepted an entity write.")

            val pendingImage = verifier.services.localMediaAssetStore.importAsset(
                source = Buffer().write(PNG_RED_1X1),
                request = MediaAssetImportRequest(mediaType = "image/png", originalFileName = "must-not-upload.png"),
            )
            val rejectedMediaWrite = runCatching {
                verifier.services.mediaCoordinator.ensurePublished(setOf(pendingImage.asset.metadata.id))
            }.exceptionOrNull()
            assertNotNull(rejectedMediaWrite, "The recovery endpoint accepted a media write.")
        }
    }

    private fun awaitIsolatedRestore() {
        val gate = Path.of(requiredEnvironment("SOMEDAY_RECOVERY_GATE_DIR"))
        Files.createDirectories(gate)
        val temporary = gate.resolve("ready.tmp")
        Files.writeString(temporary, "paired-content-empty\n")
        Files.move(temporary, gate.resolve("ready"), StandardCopyOption.ATOMIC_MOVE)
        val deadline = System.nanoTime() + isolatedRestoreTimeout().inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            val abort = gate.resolve("abort")
            check(!Files.exists(abort)) {
                "Recovery orchestration failed: ${Files.readString(abort).trim()}"
            }
            if (Files.exists(gate.resolve("continue"))) return
            Thread.sleep(100)
        }
        error("Timed out waiting for the isolated recovery server.")
    }

    private fun isolatedRestoreTimeout(): Duration {
        val raw = System.getenv("SOMEDAY_RECOVERY_GATE_TIMEOUT_SECONDS") ?: return 2.minutes
        val value = raw.toLongOrNull()
        require(value != null && value > 0) {
            "SOMEDAY_RECOVERY_GATE_TIMEOUT_SECONDS must be a positive integer."
        }
        return value.seconds
    }

    private fun requiredEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank) ?: error("$name is required.")

    private companion object {
        val CREATED_AT = Instant.parse("2026-08-27T00:00:00Z")
        val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val PNG_RED_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4XmP4z8DwHwAFAAH/NQZ7kgAAAABJRU5ErkJggg==",
        )
    }
}
