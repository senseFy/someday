package saien.someday.sync.causality.v2

import saien.someday.domain.notes.CausalEditToken
import saien.someday.domain.notes.ConflictDetails
import saien.someday.domain.notes.ConflictHistory
import saien.someday.domain.notes.ConflictResolutionAction
import saien.someday.domain.notes.MemoryMonth
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.notes.NotebookOrderEdit
import saien.someday.domain.notes.NotesRepository
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator
import kotlinx.datetime.LocalDate

/**
 * Routes product notes to the V2 DAG when an epoch is active.
 *
 * Before the first local epoch exists, [localNotes] keeps offline journaling
 * working; the next Sync run genesis-imports that product state into V2.
 */
class ProtocolRoutingNotesRepository(
    private val localNotes: NotesRepository,
    private val systemV2: NotesRepository,
    private val v2AuthorityAvailable: () -> Boolean = { true },
    private val authorityMutationCoordinator: WorkspaceAuthorityMutationCoordinator? = null,
) : NotesRepository {
    private fun current(): NotesRepository =
        if (v2AuthorityAvailable()) systemV2 else localNotes

    private fun <T> routed(block: (NotesRepository) -> T): T =
        if (authorityMutationCoordinator != null) {
            authorityMutationCoordinator.productAccess { block(current()) }
        } else {
            block(current())
        }

    override fun listNotebooks() = routed { it.listNotebooks() }
    override fun createNotebook(title: String) = routed { it.createNotebook(title) }
    override fun renameNotebook(notebookId: String, title: String) = routed { it.renameNotebook(notebookId, title) }
    override fun renameNotebook(notebookId: String, title: String, causalToken: CausalEditToken) =
        routed { it.renameNotebook(notebookId, title, causalToken) }
    override fun deleteNotebook(notebookId: String) = routed { it.deleteNotebook(notebookId) }
    override fun deleteNotebook(notebookId: String, causalToken: CausalEditToken) =
        routed { it.deleteNotebook(notebookId, causalToken) }
    override fun reorderNotebooks(edits: List<NotebookOrderEdit>) = routed { it.reorderNotebooks(edits) }
    override fun getNotebookConflictDetails(notebookId: String) = routed { it.getNotebookConflictDetails(notebookId) }
    override fun resolveNotebookConflictBranch(
        conflictId: String,
        selectedVersionId: String,
        expectedHeadVersionIds: List<String>,
    ) = routed { it.resolveNotebookConflictBranch(conflictId, selectedVersionId, expectedHeadVersionIds) }
    override fun restoreNotebook(
        notebookId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ) = routed { it.restoreNotebook(notebookId, retainedContentVersionId, causalToken) }
    override fun listDeletedWorkspaceItems() = routed { it.listDeletedWorkspaceItems() }
    override fun listNotes(notebookId: String) = routed { it.listNotes(notebookId) }
    override fun getNoteDetails(noteId: String) = routed { it.getNoteDetails(noteId) }
    override fun createNote(input: NoteInput) = routed { it.createNote(input) }
    override fun updateNote(noteId: String, input: NoteInput) = routed { it.updateNote(noteId, input) }
    override fun listNoteVersions(noteId: String) = routed { it.listNoteVersions(noteId) }
    override fun restoreNoteVersion(noteId: String, versionId: String) =
        routed { it.restoreNoteVersion(noteId, versionId) }
    override fun restoreNoteVersion(noteId: String, versionId: String, causalToken: CausalEditToken) =
        routed { it.restoreNoteVersion(noteId, versionId, causalToken) }
    override fun getConflictDetails(noteId: String) = routed { it.getConflictDetails(noteId) }
    override fun getConflictDetailsForOriginal(originalNoteId: String) =
        routed { it.getConflictDetailsForOriginal(originalNoteId) }
    override fun resolveConflict(conflictNoteId: String, action: ConflictResolutionAction) =
        routed { it.resolveConflict(conflictNoteId, action) }
    override fun resolveConflictBranch(conflictNoteId: String, versionId: String) =
        routed { it.resolveConflictBranch(conflictNoteId, versionId) }
    override fun resolveConflictBranch(
        conflictNoteId: String,
        versionId: String,
        expectedHeadVersionIds: List<String>,
    ) = routed { it.resolveConflictBranch(conflictNoteId, versionId, expectedHeadVersionIds) }
    override fun deleteNote(noteId: String) = routed { it.deleteNote(noteId) }
    override fun deleteNote(noteId: String, causalToken: CausalEditToken) =
        routed { it.deleteNote(noteId, causalToken) }
    override fun undeleteNote(
        noteId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ) = routed { it.undeleteNote(noteId, retainedContentVersionId, causalToken) }
    override fun listMemoryDayCounts(month: MemoryMonth) = routed { it.listMemoryDayCounts(month) }
    override fun listActiveNoteDates() = routed { it.listActiveNoteDates() }
    override fun listNotesForDate(date: LocalDate) = routed { it.listNotesForDate(date) }
    override fun listPriorYearNotesForDate(date: LocalDate) = routed { it.listPriorYearNotesForDate(date) }
    override fun searchNotes(query: String) = routed { it.searchNotes(query) }
}
