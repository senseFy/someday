@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import saien.someday.domain.notes.NoteDetails
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.notes.NotebookOrderEdit
import saien.someday.domain.notes.NotesLocationInput
import saien.someday.domain.settings.ClientTheme
import saien.someday.integration.testkit.RealSelfHostedFixture
import saien.someday.integration.testkit.replaceWorkspaceFrom
import saien.someday.integration.testkit.assertSuccessfulSync

class SelfHostedTextConvergenceJourneyTest {
    @Test
    fun twoDevicesBootstrapAndMergeNonConflictingProductChangesWithoutPlaintextLeak() {
        RealSelfHostedFixture.create("text-convergence").use { fixture ->
            val leader = fixture.newDevice("desktop-leader", "desktop")
            val follower = fixture.newDevice("ios-follower", "ios")
            leader.connect(createAccount = true)
            follower.connect(createAccount = false)

            val notebook = leader.services.notesRepository.createNotebook(NOTEBOOK_SENTINEL)
            val note = leader.services.notesRepository.createNote(
                NoteInput(
                    notebookId = notebook.id,
                    title = NOTE_TITLE_SENTINEL,
                    markdownBody = NOTE_BODY_SENTINEL,
                    createdAt = CREATED_AT,
                    location = NotesLocationInput(placeText = LOCATION_SENTINEL, capturedAt = CREATED_AT),
                    timeZoneId = "Asia/Shanghai",
                ),
            )
            leader.assertSuccessfulSync()

            follower.replaceWorkspaceFrom(leader)
            follower.assertSuccessfulSync()
            assertEquals(NOTE_BODY_SENTINEL, follower.services.notesRepository.getNoteDetails(note.id)?.markdownBody)

            val leaderNote = assertNotNull(leader.services.notesRepository.getNoteDetails(note.id))
            val followerNote = assertNotNull(follower.services.notesRepository.getNoteDetails(note.id))
            leader.services.notesRepository.updateNote(
                note.id,
                leaderNote.edit(markdownBody = "leader body"),
            )
            follower.services.notesRepository.updateNote(
                note.id,
                followerNote.edit(
                    location = NotesLocationInput(
                        latitude = 31.2304,
                        longitude = 121.4737,
                        placeText = "follower place",
                        accuracyMeters = 4.0,
                        altitudeMeters = 10.0,
                        capturedAt = FOLLOWER_EDIT_AT,
                    ),
                ),
            )

            val leaderNotebook = leader.services.notesRepository.listNotebooks().single { it.id == notebook.id }
            val followerNotebook = follower.services.notesRepository.listNotebooks().single { it.id == notebook.id }
            leader.services.notesRepository.renameNotebook(
                notebook.id,
                "Leader journal",
                assertNotNull(leaderNotebook.causalToken),
            )
            follower.services.notesRepository.reorderNotebooks(
                listOf(NotebookOrderEdit(notebook.id, 99L, assertNotNull(followerNotebook.causalToken))),
            )

            val leaderSettings = leader.services.settingsRepository.load()
            val followerSettings = follower.services.settingsRepository.load()
            leader.services.settingsRepository.save(leaderSettings.copy(theme = ClientTheme.Dark))
            follower.services.settingsRepository.save(
                followerSettings.copy(
                    editorPreferences = followerSettings.editorPreferences.copy(previewByDefault = true),
                ),
            )

            leader.assertSuccessfulSync()
            follower.assertSuccessfulSync()
            leader.assertSuccessfulSync()
            follower.assertSuccessfulSync()

            val convergedLeader = assertNotNull(leader.services.notesRepository.getNoteDetails(note.id))
            val convergedFollower = assertNotNull(follower.services.notesRepository.getNoteDetails(note.id))
            assertEquals("leader body", convergedLeader.markdownBody)
            assertEquals("follower place", convergedLeader.location?.placeText)
            assertEquals(convergedLeader, convergedFollower)

            val finalLeaderNotebook = leader.services.notesRepository.listNotebooks().single { it.id == notebook.id }
            val finalFollowerNotebook = follower.services.notesRepository.listNotebooks().single { it.id == notebook.id }
            assertEquals("Leader journal", finalLeaderNotebook.title)
            assertEquals(99L, finalLeaderNotebook.sortOrder)
            assertEquals(finalLeaderNotebook, finalFollowerNotebook)

            val finalLeaderSettings = leader.services.settingsRepository.load()
            val finalFollowerSettings = follower.services.settingsRepository.load()
            assertEquals(ClientTheme.Dark, finalLeaderSettings.theme)
            assertTrue(finalLeaderSettings.editorPreferences.previewByDefault)
            assertEquals(finalLeaderSettings.theme, finalFollowerSettings.theme)
            assertEquals(
                finalLeaderSettings.editorPreferences.previewByDefault,
                finalFollowerSettings.editorPreferences.previewByDefault,
            )
            assertTrue(leader.services.notesRepository.getConflictDetailsForOriginal(note.id) == null)
            assertTrue(follower.services.notesRepository.getConflictDetailsForOriginal(note.id) == null)

            val opaqueRows = fixture.readOpaqueRowsForAccount()
            mapOf(
                "note title" to NOTE_TITLE_SENTINEL,
                "note body" to NOTE_BODY_SENTINEL,
                "location" to LOCATION_SENTINEL,
                "notebook title" to NOTEBOOK_SENTINEL,
                "account credential" to fixture.account.password,
                "workspace recovery material" to leader.initialRecoveryCode,
            ).forEach { (label, sentinel) ->
                assertFalse(opaqueRows.contains(sentinel), "Self-hosted rows leaked $label.")
            }
        }
    }

    private fun NoteDetails.edit(
        markdownBody: String = this.markdownBody,
        location: NotesLocationInput? = this.location,
    ): NoteInput = NoteInput(
        notebookId = notebookId,
        title = title,
        markdownBody = markdownBody,
        createdAt = createdAt,
        location = location,
        timeZoneId = timeZoneId,
        causalToken = assertNotNull(causalToken),
    )

    private companion object {
        const val NOTE_TITLE_SENTINEL = "private-note-title-7cb74d"
        const val NOTE_BODY_SENTINEL = "private-note-body-9702ee"
        const val LOCATION_SENTINEL = "private-location-1e381c"
        const val NOTEBOOK_SENTINEL = "private-notebook-9ce5aa"
        val CREATED_AT = Instant.parse("2026-08-25T01:00:00Z")
        val FOLLOWER_EDIT_AT = Instant.parse("2026-08-25T02:00:00Z")
    }
}
