@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.settings.authorityBindingId
import saien.someday.integration.testkit.RealSelfHostedFixture
import saien.someday.integration.testkit.assertSuccessfulSync

class SelfHostedPairingJourneyTest {
    @Test
    fun invitationClaimAtomicallyAdoptsAuthorityThenBootstrapsVisibleNotes() {
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

            val previousWorkspaceId = assertNotNull(joiner.workspaceKeys.workspaceIdOrNull())
            val previousFingerprint = assertNotNull(joiner.workspaceKeys.unlockedKeyOrNull()).fingerprint
            val invitationResult = inviter.pairing.createInvitation()
            assertTrue(
                invitationResult.success,
                invitationResult.diagnosticMessage ?: invitationResult.reason.name,
            )
            val invitation = assertNotNull(invitationResult.invitation)

            val joinResult = joiner.pairing.joinWithToken(invitation.revealManualToken())
            assertTrue(joinResult.success, joinResult.diagnosticMessage ?: joinResult.reason.name)
            val adoptedWorkspaceId = assertNotNull(joiner.workspaceKeys.workspaceIdOrNull())
            val adoptedFingerprint = assertNotNull(joiner.workspaceKeys.unlockedKeyOrNull()).fingerprint
            assertEquals(inviter.workspaceKeys.workspaceIdOrNull(), adoptedWorkspaceId)
            assertEquals(assertNotNull(inviter.workspaceKeys.unlockedKeyOrNull()).fingerprint, adoptedFingerprint)
            assertNotEquals(previousWorkspaceId, adoptedWorkspaceId)
            assertNotEquals(previousFingerprint, adoptedFingerprint)

            val credentials = assertNotNull(joiner.sessionStore.load())
            val requirement = assertNotNull(joiner.services.activeWorkspaceSessionGuard.currentRequirement())
            assertEquals(credentials.authorityBindingId, requirement.authorityBindingId)
            assertEquals(joiner.deviceId, requirement.localWriterDeviceId)
            assertEquals(adoptedWorkspaceId, requirement.workspaceId)

            joiner.assertSuccessfulSync()
            val received = assertNotNull(joiner.services.notesRepository.getNoteDetails(note.id))
            assertEquals("Visible after pairing", received.title)
            assertEquals("Pairing bootstrap body", received.markdownBody)
            assertEquals(notebook.id, received.notebookId)
        }
    }

    private companion object {
        val CREATED_AT = Instant.parse("2026-08-25T05:00:00Z")
    }
}
