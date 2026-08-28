package saien.someday.sync

import kotlinx.datetime.LocalDate
import saien.someday.domain.notes.CausalEditToken
import saien.someday.domain.notes.MemoryMonth
import saien.someday.domain.notes.NoteBatchDeletion
import saien.someday.domain.notes.NoteBatchUndelete
import saien.someday.domain.notes.NoteBatchUpdate
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.notes.NotebookOrderEdit
import saien.someday.domain.notes.NotesRepository

/** Holds the product-access barrier for the complete repository operation, including context resolution. */
internal class AuthorityCoordinatedNotesRepository(
    private val delegate: NotesRepository,
    private val coordinator: WorkspaceAuthorityMutationCoordinator,
) : NotesRepository {
    private fun <T> access(block: NotesRepository.() -> T): T = coordinator.productAccess { delegate.block() }

    override fun listNotebooks() = access { listNotebooks() }
    override fun createNotebook(title: String) = access { createNotebook(title) }
    override fun renameNotebook(notebookId: String, title: String) = access { renameNotebook(notebookId, title) }
    override fun renameNotebook(
        notebookId: String,
        title: String,
        causalToken: CausalEditToken,
    ) = access { renameNotebook(notebookId, title, causalToken) }
    override fun reorderNotebooks(edits: List<NotebookOrderEdit>) = access { reorderNotebooks(edits) }
    override fun deleteNotebook(notebookId: String) = access { deleteNotebook(notebookId) }
    override fun deleteNotebook(
        notebookId: String,
        causalToken: CausalEditToken,
    ) = access { deleteNotebook(notebookId, causalToken) }

    override fun restoreNotebook(
        notebookId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ) = access { restoreNotebook(notebookId, retainedContentVersionId, causalToken) }

    override fun listDeletedWorkspaceItems() = access { listDeletedWorkspaceItems() }
    override fun getNotebookConflictDetails(notebookId: String) = access { getNotebookConflictDetails(notebookId) }

    override fun resolveNotebookConflictBranch(
        conflictId: String,
        selectedVersionId: String,
        expectedHeadVersionIds: List<String>,
    ) = access { resolveNotebookConflictBranch(conflictId, selectedVersionId, expectedHeadVersionIds) }

    override fun listNotes(notebookId: String) = access { listNotes(notebookId) }
    override fun getNoteDetails(noteId: String) = access { getNoteDetails(noteId) }
    override fun createNote(input: NoteInput) = access { createNote(input) }
    override fun updateNote(noteId: String, input: NoteInput) = access { updateNote(noteId, input) }
    override fun updateNotes(edits: List<NoteBatchUpdate>) = access { updateNotes(edits) }
    override fun deleteNote(noteId: String) = access { deleteNote(noteId) }
    override fun deleteNote(
        noteId: String,
        causalToken: CausalEditToken,
    ) = access { deleteNote(noteId, causalToken) }

    override fun deleteNotes(deletions: List<NoteBatchDeletion>) = access { deleteNotes(deletions) }

    override fun undeleteNote(
        noteId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ) = access { undeleteNote(noteId, retainedContentVersionId, causalToken) }

    override fun undeleteNotes(restores: List<NoteBatchUndelete>) = access { undeleteNotes(restores) }
    override fun listNoteVersions(noteId: String) = access { listNoteVersions(noteId) }
    override fun restoreNoteVersion(noteId: String, versionId: String) = access { restoreNoteVersion(noteId, versionId) }
    override fun restoreNoteVersion(
        noteId: String,
        versionId: String,
        causalToken: CausalEditToken,
    ) = access { restoreNoteVersion(noteId, versionId, causalToken) }

    override fun getConflictDetails(noteId: String) = access { getConflictDetails(noteId) }
    override fun getConflictDetailsForOriginal(originalNoteId: String) = access { getConflictDetailsForOriginal(originalNoteId) }
    override fun resolveConflictBranch(
        conflictNoteId: String,
        versionId: String,
        expectedHeadVersionIds: List<String>,
    ) = access { resolveConflictBranch(conflictNoteId, versionId, expectedHeadVersionIds) }

    override fun listMemoryDayCounts(month: MemoryMonth) = access { listMemoryDayCounts(month) }
    override fun listActiveNoteDates() = access { listActiveNoteDates() }
    override fun listNotesForDate(date: LocalDate) = access { listNotesForDate(date) }
    override fun listPriorYearNotesForDate(date: LocalDate) = access { listPriorYearNotesForDate(date) }
    override fun searchNotes(query: String) = access { searchNotes(query) }
}
