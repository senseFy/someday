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
import saien.someday.domain.workspace.UnrestrictedWorkspaceProductAccess
import saien.someday.domain.workspace.WorkspaceProductAccess

/** Holds the product-access barrier for the complete repository operation, including context resolution. */
internal class AuthorityCoordinatedNotesRepository(
    private val delegate: NotesRepository,
    private val coordinator: WorkspaceLifecycleCoordinator,
    private val workspaceProductAccess: WorkspaceProductAccess = UnrestrictedWorkspaceProductAccess,
) : NotesRepository {
    private fun <T> access(block: NotesRepository.() -> T): T = coordinator.productAccess { delegate.block() }

    private fun <T> mutate(block: NotesRepository.() -> T): T = coordinator.productAccess {
        workspaceProductAccess.mutate { delegate.block() }
    }

    override fun listNotebooks() = access { listNotebooks() }
    override fun createNotebook(title: String) = mutate { createNotebook(title) }
    override fun renameNotebook(notebookId: String, title: String) = mutate { renameNotebook(notebookId, title) }
    override fun renameNotebook(
        notebookId: String,
        title: String,
        causalToken: CausalEditToken,
    ) = mutate { renameNotebook(notebookId, title, causalToken) }
    override fun reorderNotebooks(edits: List<NotebookOrderEdit>) = mutate { reorderNotebooks(edits) }
    override fun deleteNotebook(notebookId: String) = mutate { deleteNotebook(notebookId) }
    override fun deleteNotebook(
        notebookId: String,
        causalToken: CausalEditToken,
    ) = mutate { deleteNotebook(notebookId, causalToken) }

    override fun restoreNotebook(
        notebookId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ) = mutate { restoreNotebook(notebookId, retainedContentVersionId, causalToken) }

    override fun listDeletedWorkspaceItems() = access { listDeletedWorkspaceItems() }
    override fun getNotebookConflictDetails(notebookId: String) = access { getNotebookConflictDetails(notebookId) }

    override fun resolveNotebookConflictBranch(
        conflictId: String,
        selectedVersionId: String,
        expectedHeadVersionIds: List<String>,
    ) = mutate { resolveNotebookConflictBranch(conflictId, selectedVersionId, expectedHeadVersionIds) }

    override fun listNotes(notebookId: String) = access { listNotes(notebookId) }
    override fun getNoteDetails(noteId: String) = access { getNoteDetails(noteId) }
    override fun createNote(input: NoteInput) = mutate { createNote(input) }
    override fun updateNote(noteId: String, input: NoteInput) = mutate { updateNote(noteId, input) }
    override fun updateNotes(edits: List<NoteBatchUpdate>) = mutate { updateNotes(edits) }
    override fun deleteNote(noteId: String) = mutate { deleteNote(noteId) }
    override fun deleteNote(
        noteId: String,
        causalToken: CausalEditToken,
    ) = mutate { deleteNote(noteId, causalToken) }

    override fun deleteNotes(deletions: List<NoteBatchDeletion>) = mutate { deleteNotes(deletions) }

    override fun undeleteNote(
        noteId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ) = mutate { undeleteNote(noteId, retainedContentVersionId, causalToken) }

    override fun undeleteNotes(restores: List<NoteBatchUndelete>) = mutate { undeleteNotes(restores) }
    override fun listNoteVersions(noteId: String) = access { listNoteVersions(noteId) }
    override fun restoreNoteVersion(noteId: String, versionId: String) = mutate { restoreNoteVersion(noteId, versionId) }
    override fun restoreNoteVersion(
        noteId: String,
        versionId: String,
        causalToken: CausalEditToken,
    ) = mutate { restoreNoteVersion(noteId, versionId, causalToken) }

    override fun getConflictDetails(noteId: String) = access { getConflictDetails(noteId) }
    override fun getConflictDetailsForOriginal(originalNoteId: String) = access { getConflictDetailsForOriginal(originalNoteId) }
    override fun resolveConflictBranch(
        conflictNoteId: String,
        versionId: String,
        expectedHeadVersionIds: List<String>,
    ) = mutate { resolveConflictBranch(conflictNoteId, versionId, expectedHeadVersionIds) }

    override fun listMemoryDayCounts(month: MemoryMonth) = access { listMemoryDayCounts(month) }
    override fun listActiveNoteDates() = access { listActiveNoteDates() }
    override fun listNotesForDate(date: LocalDate) = access { listNotesForDate(date) }
    override fun listPriorYearNotesForDate(date: LocalDate) = access { listPriorYearNotesForDate(date) }
    override fun searchNotes(query: String) = access { searchNotes(query) }
}
