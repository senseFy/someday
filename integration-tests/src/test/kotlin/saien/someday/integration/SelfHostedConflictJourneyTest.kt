@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import saien.someday.domain.notes.NoteDetails
import saien.someday.domain.notes.NoteInput
import saien.someday.integration.testkit.RealSelfHostedFixture
import saien.someday.integration.testkit.adoptWorkspaceFrom
import saien.someday.integration.testkit.assertSuccessfulSync

class SelfHostedConflictJourneyTest {
    @Test
    fun concurrentSameFieldEditsRemainDurableConflictsOnBothDevices() {
        RealSelfHostedFixture.create("same-field-conflict").use { fixture ->
            val leader = fixture.newDevice("android-leader", "android")
            val follower = fixture.newDevice("ios-follower", "ios")
            leader.connect(createAccount = true)
            follower.connect(createAccount = false)

            val notebook = leader.services.notesRepository.createNotebook("Conflict journal")
            val note = leader.services.notesRepository.createNote(
                NoteInput(
                    notebookId = notebook.id,
                    title = "Shared note",
                    markdownBody = "base body",
                    createdAt = CREATED_AT,
                    timeZoneId = "UTC",
                ),
            )
            leader.assertSuccessfulSync()
            follower.adoptWorkspaceFrom(leader)
            follower.assertSuccessfulSync()

            val leaderBase = assertNotNull(leader.services.notesRepository.getNoteDetails(note.id))
            val followerBase = assertNotNull(follower.services.notesRepository.getNoteDetails(note.id))
            leader.services.notesRepository.updateNote(note.id, leaderBase.withBody("android body"))
            follower.services.notesRepository.updateNote(note.id, followerBase.withBody("ios body"))

            leader.assertSuccessfulSync()
            follower.assertSuccessfulSync()
            leader.assertSuccessfulSync()
            follower.assertSuccessfulSync()

            val leaderConflict = assertNotNull(
                leader.services.notesRepository.getConflictDetailsForOriginal(note.id),
                "Leader must expose the unresolved version-DAG conflict.",
            )
            val followerConflict = assertNotNull(
                follower.services.notesRepository.getConflictDetailsForOriginal(note.id),
                "Follower must expose the unresolved version-DAG conflict.",
            )
            assertEquals(2, leaderConflict.versionBranches.size)
            assertEquals(leaderConflict.expectedHeadVersionIds, followerConflict.expectedHeadVersionIds)
            assertEquals(
                setOf("android body", "ios body"),
                leaderConflict.versionBranches
                    .map { branch -> branch.history.versions.last().markdownBody }
                    .toSet(),
            )
        }
    }

    private fun NoteDetails.withBody(body: String): NoteInput = NoteInput(
        notebookId = notebookId,
        title = title,
        markdownBody = body,
        createdAt = createdAt,
        location = location,
        timeZoneId = timeZoneId,
        causalToken = assertNotNull(causalToken),
    )

    private companion object {
        val CREATED_AT = Instant.parse("2026-08-25T03:00:00Z")
    }
}
