package saien.someday.sync.causality.v2

import saien.someday.domain.notes.CausalEditToken
import saien.someday.domain.notes.ConflictDetails
import saien.someday.domain.notes.ConflictHistory
import saien.someday.domain.notes.ConflictResolutionAction
import saien.someday.domain.notes.MemoryMonth
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.notes.NotebookOrderEdit
import saien.someday.domain.notes.NotesRepository
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
) : NotesRepository {
    private fun current(): NotesRepository =
        if (v2AuthorityAvailable()) systemV2 else localNotes

    override fun listNotebooks() = current().listNotebooks()
    override fun createNotebook(title: String) = current().createNotebook(title)
    override fun renameNotebook(notebookId: String, title: String) = current().renameNotebook(notebookId, title)
    override fun renameNotebook(notebookId: String, title: String, causalToken: CausalEditToken) =
        current().renameNotebook(notebookId, title, causalToken)
    override fun deleteNotebook(notebookId: String) = current().deleteNotebook(notebookId)
    override fun deleteNotebook(notebookId: String, causalToken: CausalEditToken) =
        current().deleteNotebook(notebookId, causalToken)
    override fun reorderNotebooks(edits: List<NotebookOrderEdit>) = current().reorderNotebooks(edits)
    override fun getNotebookConflictDetails(notebookId: String) = current().getNotebookConflictDetails(notebookId)
    override fun resolveNotebookConflictBranch(
        conflictId: String,
        selectedVersionId: String,
        expectedHeadVersionIds: List<String>,
    ) = current().resolveNotebookConflictBranch(conflictId, selectedVersionId, expectedHeadVersionIds)
    override fun restoreNotebook(
        notebookId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ) = current().restoreNotebook(notebookId, retainedContentVersionId, causalToken)
    override fun listDeletedWorkspaceItems() = current().listDeletedWorkspaceItems()
    override fun listNotes(notebookId: String) = current().listNotes(notebookId)
    override fun getNoteDetails(noteId: String) = current().getNoteDetails(noteId)
    override fun createNote(input: NoteInput) = current().createNote(input)
    override fun updateNote(noteId: String, input: NoteInput) = current().updateNote(noteId, input)
    override fun listNoteVersions(noteId: String) = current().listNoteVersions(noteId)
    override fun restoreNoteVersion(noteId: String, versionId: String) = current().restoreNoteVersion(noteId, versionId)
    override fun restoreNoteVersion(noteId: String, versionId: String, causalToken: CausalEditToken) =
        current().restoreNoteVersion(noteId, versionId, causalToken)
    override fun getConflictDetails(noteId: String) = current().getConflictDetails(noteId)
    override fun getConflictDetailsForOriginal(originalNoteId: String) =
        current().getConflictDetailsForOriginal(originalNoteId)
    override fun resolveConflict(conflictNoteId: String, action: ConflictResolutionAction) =
        current().resolveConflict(conflictNoteId, action)
    override fun resolveConflictBranch(conflictNoteId: String, versionId: String) =
        current().resolveConflictBranch(conflictNoteId, versionId)
    override fun resolveConflictBranch(
        conflictNoteId: String,
        versionId: String,
        expectedHeadVersionIds: List<String>,
    ) = current().resolveConflictBranch(conflictNoteId, versionId, expectedHeadVersionIds)
    override fun deleteNote(noteId: String) = current().deleteNote(noteId)
    override fun deleteNote(noteId: String, causalToken: CausalEditToken) = current().deleteNote(noteId, causalToken)
    override fun undeleteNote(
        noteId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ) = current().undeleteNote(noteId, retainedContentVersionId, causalToken)
    override fun listMemoryDayCounts(month: MemoryMonth) = current().listMemoryDayCounts(month)
    override fun listActiveNoteDates() = current().listActiveNoteDates()
    override fun listNotesForDate(date: LocalDate) = current().listNotesForDate(date)
    override fun listPriorYearNotesForDate(date: LocalDate) = current().listPriorYearNotesForDate(date)
    override fun searchNotes(query: String) = current().searchNotes(query)
}
