@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.integration

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import okio.Buffer
import saien.someday.data.media.MediaAssetImportRequest
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.settings.authorityBindingId
import saien.someday.integration.testkit.RealSelfHostedFixture
import saien.someday.integration.testkit.assertSuccessfulSync

class SelfHostedPairingJourneyTest {
    @Test
    fun confirmedReplacementDiscardsOldContentAndBootstrapsTargetWorkspace() {
        RealSelfHostedFixture.create("pairing-journey").use { fixture ->
            val inviter = fixture.newDevice("desktop-inviter", "desktop")
            val joiner = fixture.newDevice("ios-joiner", "ios")
            inviter.connect(createAccount = true)
            joiner.connect(createAccount = false)

            val notebook = inviter.services.notesRepository.createNotebook("Paired journal")
            val note = inviter.services.notesRepository.createNote(
                NoteInput(
                    notebookId = notebook.id,
                    title = "Visible after pairing",
                    markdownBody = "Pairing bootstrap body",
                    createdAt = CREATED_AT,
                    timeZoneId = "UTC",
                ),
            )
            inviter.assertSuccessfulSync()

            val discardedNotebook = joiner.services.notesRepository.createNotebook("Discarded journal")
            val discardedImage = joiner.services.localMediaAssetStore.importAsset(
                source = Buffer().write(PNG_1X1),
                request = MediaAssetImportRequest(
                    mediaType = "image/png",
                    originalFileName = "discarded.png",
                ),
            ).asset
            val discardedPublishedNote = joiner.services.notesRepository.createNote(
                NoteInput(
                    notebookId = discardedNotebook.id,
                    title = "Discarded published note",
                    markdownBody = "![discarded](someday-asset://${discardedImage.metadata.id.value})",
                    createdAt = CREATED_AT,
                    timeZoneId = "UTC",
                ),
            )
            joiner.assertSuccessfulSync()
            val oldWorkspacePackage = assertNotNull(
                joiner.workspaceJoinPackageProvider.createPackage().packageData,
            )
            val discardedPendingNote = joiner.services.notesRepository.createNote(
                NoteInput(
                    notebookId = discardedNotebook.id,
                    title = "Discarded pending note",
                    markdownBody = "Never merge this local change",
                    createdAt = CREATED_AT,
                    timeZoneId = "UTC",
                ),
            )

            val previousWorkspaceId = assertNotNull(joiner.workspaceKeys.workspaceIdOrNull())
            val previousFingerprint = assertNotNull(joiner.workspaceKeys.unlockedKeyOrNull()).fingerprint
            val invitationResult = inviter.pairing.createInvitation()
            assertTrue(
                invitationResult.success,
                invitationResult.diagnosticMessage ?: invitationResult.reason.name,
            )
            val invitation = assertNotNull(invitationResult.invitation)

            val joinResult = joiner.pairing.joinWithToken(
                invitation.revealManualToken(),
                replaceExistingWorkspace = true,
            )
            assertTrue(joinResult.success, joinResult.diagnosticMessage ?: joinResult.reason.name)
            val joinedWorkspaceId = assertNotNull(joiner.workspaceKeys.workspaceIdOrNull())
            val joinedFingerprint = assertNotNull(joiner.workspaceKeys.unlockedKeyOrNull()).fingerprint
            assertEquals(inviter.workspaceKeys.workspaceIdOrNull(), joinedWorkspaceId)
            assertEquals(assertNotNull(inviter.workspaceKeys.unlockedKeyOrNull()).fingerprint, joinedFingerprint)
            assertNotEquals(previousWorkspaceId, joinedWorkspaceId)
            assertNotEquals(previousFingerprint, joinedFingerprint)
            assertNull(joiner.services.notesRepository.getNoteDetails(discardedPublishedNote.id))
            assertNull(joiner.services.notesRepository.getNoteDetails(discardedPendingNote.id))
            assertNull(joiner.services.localMediaAssetStore.getAsset(discardedImage.metadata.id))

            val credentials = assertNotNull(joiner.sessionStore.load())
            val requirement = assertNotNull(joiner.services.activeWorkspaceSessionGuard.currentRequirement())
            assertEquals(credentials.authorityBindingId, requirement.authorityBindingId)
            assertEquals(joiner.deviceId, requirement.localWriterDeviceId)
            assertEquals(joinedWorkspaceId, requirement.workspaceId)

            joiner.assertSuccessfulSync()
            val received = assertNotNull(joiner.services.notesRepository.getNoteDetails(note.id))
            assertEquals("Visible after pairing", received.title)
            assertEquals("Pairing bootstrap body", received.markdownBody)
            assertEquals(notebook.id, received.notebookId)

            val oldWorkspaceReader = fixture.newDevice("desktop-old-workspace", "desktop")
            oldWorkspaceReader.connect(createAccount = false)
            val oldWorkspaceJoin = oldWorkspaceReader.workspaceJoiner.join(
                packageData = oldWorkspacePackage,
                replaceExistingWorkspace = true,
            )
            assertTrue(
                oldWorkspaceJoin.success,
                oldWorkspaceJoin.diagnosticMessage ?: oldWorkspaceJoin.reason.name,
            )
            oldWorkspaceReader.assertSuccessfulSync()
            assertNotNull(
                oldWorkspaceReader.services.notesRepository.getNoteDetails(discardedPublishedNote.id),
            )
            assertNull(
                oldWorkspaceReader.services.notesRepository.getNoteDetails(discardedPendingNote.id),
            )
            val oldImage = oldWorkspaceReader.services.mediaCoordinator.materialize(
                discardedImage.metadata.id,
            )
            assertTrue(oldImage.downloaded)
        }
    }

    private companion object {
        val CREATED_AT = Instant.parse("2026-08-25T05:00:00Z")
        val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
