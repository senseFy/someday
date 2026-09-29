package saien.someday.ui.notes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.workspace.WorkspaceProductAccess
import saien.someday.domain.workspace.WorkspaceProductChangedException
import saien.someday.domain.workspace.WorkspaceProductReadOnlyException
import saien.someday.domain.workspace.WorkspaceProductSnapshot
import saien.someday.ui.media.MediaImportUiResult
import saien.someday.ui.memories.MemoriesUiController

class WorkspaceProductUiGuardTest {
    @Test
    fun resetFreezePreservesDraftAndOfflineChoiceAllowsLocalSave() = runBlocking {
        val repository = InMemoryNotesRepository()
        val access = TestProductAccess()
        val controller = NotesUiController(repository, backgroundDispatcher = Dispatchers.Unconfined, workspaceProductAccess = access)
        val notebook = controller.createNotebook("Retained")
        controller.openNewNote(notebook.id)
        controller.updateDraft(title = "Draft", markdownBody = "Before reset", createdDateText = "2026-05-23")
        val editorSessionId = checkNotNull(controller.currentEditorSessionId())
        access.readOnly = true
        controller.updateProductReadOnly(true)

        controller.updateDraft(markdownBody = "Must not replace the frozen draft")
        assertFalse(controller.applyMediaImportResult(editorSessionId, importedImage()))
        assertFalse(controller.saveEditor())
        assertEquals("Before reset", controller.state.editor?.markdownBody)
        assertTrue(repository.listNotes(notebook.id).isEmpty())

        access.readOnly = false
        controller.updateProductReadOnly(false)
        controller.updateDraft(markdownBody = "Kept and edited offline")
        assertTrue(controller.saveEditor())
        assertEquals("Kept and edited offline", repository.getNoteDetails(repository.listNotes(notebook.id).single().id)?.markdownBody)
    }

    @Test
    fun durableFreezeRacingANotebookSheetClickCancelsWithoutCreatingOrRenaming() = runBlocking {
        val repository = InMemoryNotesRepository()
        val access = TestProductAccess()
        val controller = NotesUiController(repository, backgroundDispatcher = Dispatchers.Unconfined, workspaceProductAccess = access)
        val notebook = controller.createNotebook("Before freeze")
        // The DB gate can arrive before the UI has received its read-only snapshot.
        access.readOnly = true
        assertFailsWith<CancellationException> { controller.createNotebook("Late creation") }
        assertFailsWith<CancellationException> { controller.renameNotebook(notebook.id, "Late rename") }
        assertEquals(listOf("Before freeze"), repository.listNotebooks().map { it.title })
    }

    @Test
    fun editorAndPickerFromOldWorkspaceCannotWriteIntoReplacement() = runBlocking {
        val repository = InMemoryNotesRepository()
        val access = TestProductAccess()
        val controller = NotesUiController(repository, backgroundDispatcher = Dispatchers.Unconfined, workspaceProductAccess = access)
        val notebook = controller.createNotebook("Old workspace")
        controller.openNewNote(notebook.id)
        controller.updateDraft(title = "Old draft", markdownBody = "Retain locally", createdDateText = "2026-05-23")
        val editorSessionId = checkNotNull(controller.currentEditorSessionId())
        access.replaceWorkspace()

        assertFalse(controller.applyMediaImportResult(editorSessionId, importedImage()))
        controller.updateDraft(markdownBody = "Late edit")
        assertEquals("Retain locally", controller.state.editor?.markdownBody)
        assertFailsWith<CancellationException> { controller.saveEditor() }
        assertTrue(repository.listNotes(notebook.id).isEmpty())
        controller.refresh()
        assertNull(controller.state.editor, "Refreshing the replacement must not retain the old editor identity.")
    }

    @Test
    fun completedReadFromOldWorkspaceIsDiscardedBeforePublishingUiState() = runBlocking {
        val repository = InMemoryNotesRepository()
        val access = TestProductAccess()
        val controller = NotesUiController(repository, backgroundDispatcher = Dispatchers.Unconfined, workspaceProductAccess = access)
        controller.createNotebook("Already displayed")
        repository.createNotebook("Old result that must not appear")
        access.afterRead = { access.replaceWorkspace() }

        assertFailsWith<CancellationException> { controller.refreshAfterSync() }
        assertEquals(listOf("Already displayed"), controller.state.notebooks.map { it.title })
    }

    @Test
    fun firstPublicationOfTheSameLocalWorkspacePreservesUnsavedDraft() = runBlocking {
        val repository = InMemoryNotesRepository()
        val access = TestProductAccess(initialAuthority = null)
        val controller = NotesUiController(repository, backgroundDispatcher = Dispatchers.Unconfined, workspaceProductAccess = access)
        val notebook = controller.createNotebook("Local workspace")
        controller.openNewNote(notebook.id)
        controller.updateDraft(title = "Unsaved", markdownBody = "Preserve through first publication", createdDateText = "2026-05-23")
        val session = controller.currentEditorSessionId()
        access.bindFirstAuthority()
        controller.refreshAfterSync()
        assertEquals(session, controller.currentEditorSessionId())
        assertEquals("Preserve through first publication", controller.state.editor?.markdownBody)
        assertTrue(controller.saveEditor())
    }

    @Test
    fun firstBindingCannotPreserveAnEditorAcrossAnIncarnationChange() = runBlocking {
        val repository = InMemoryNotesRepository()
        val access = TestProductAccess(initialAuthority = null)
        val controller = NotesUiController(repository, backgroundDispatcher = Dispatchers.Unconfined, workspaceProductAccess = access)
        val notebook = controller.createNotebook("Local workspace")
        controller.openNewNote(notebook.id)
        controller.updateDraft(title = "Old", markdownBody = "Do not retag", createdDateText = "2026-05-23")
        access.bindFirstAuthority(newIncarnation = "different-incarnation")
        controller.refreshAfterSync()
        assertNull(controller.state.editor)
        assertTrue(repository.listNotes(notebook.id).isEmpty())
    }

    @Test
    fun firstBindingAfterAnotherLocalReplacementDoesNotReviveDiscardedDraft() = runBlocking {
        val repository = InMemoryNotesRepository()
        val access = TestProductAccess(initialAuthority = null)
        val controller = NotesUiController(repository, backgroundDispatcher = Dispatchers.Unconfined, workspaceProductAccess = access)
        val notebook = controller.createNotebook("Local workspace")
        controller.openNewNote(notebook.id)
        controller.updateDraft(title = "Discarded", markdownBody = "Must not survive replacement", createdDateText = "2026-05-23")

        access.invalidateLocalCopy()
        access.bindFirstAuthority()
        controller.refreshAfterSync()

        assertNull(controller.state.editor)
        assertTrue(repository.listNotes(notebook.id).isEmpty())
    }

    @Test
    fun queuedBatchCancelledByFirstPublicationReleasesBusyStateAndCanBeRetried() = runBlocking {
        for (delete in listOf(false, true)) {
            val repository = InMemoryNotesRepository()
            val notebook = repository.createNotebook("Local workspace")
            val note = repository.seedNote(notebook.id, "Retained", "Before publication", LocalDate(2026, 5, 23))
            val access = TestProductAccess(initialAuthority = null)
            val dispatcher = QueuedDispatcher()
            val controller = NotesUiController(repository, backgroundDispatcher = dispatcher, workspaceProductAccess = access)
            controller.refresh()
            controller.selectAllVisibleNotes()
            dispatcher.queueing = true
            val pending = launch(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<CancellationException> {
                    if (delete) controller.deleteSelectedNotes() else controller.changeSelectedNotesCreatedDate(LocalDate(2024, 2, 3))
                }
            }
            assertTrue(controller.state.batchOperationInProgress)
            access.bindFirstAuthority()
            dispatcher.queueing = false
            dispatcher.runNext()
            pending.join()

            assertFalse(controller.state.batchOperationInProgress)
            assertEquals(listOf(note.id), repository.listNotes(notebook.id).map { it.id })
            controller.refreshAfterSync()
            controller.selectAllVisibleNotes()
            assertTrue(if (delete) controller.deleteSelectedNotes() else controller.changeSelectedNotesCreatedDate(LocalDate(2024, 2, 3)))
            assertFalse(controller.state.batchOperationInProgress)
        }
    }

    @Test
    fun oldBatchCancellationCannotClearTheReplacementsRunningBatch() = runBlocking {
        val repository = InMemoryNotesRepository()
        val notebook = repository.createNotebook("Workspace")
        val note = repository.seedNote(notebook.id, "Retained", "Content", LocalDate(2026, 5, 23))
        val access = TestProductAccess()
        val dispatcher = QueuedDispatcher()
        val controller = NotesUiController(repository, backgroundDispatcher = dispatcher, workspaceProductAccess = access)
        controller.refresh()
        controller.selectAllVisibleNotes()
        dispatcher.queueing = true
        val oldBatch = launch(start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<CancellationException> { controller.deleteSelectedNotes() }
        }
        access.replaceWorkspace()
        dispatcher.queueing = false
        controller.refresh()
        controller.selectAllVisibleNotes()
        dispatcher.queueing = true
        val newBatch = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(controller.changeSelectedNotesCreatedDate(LocalDate(2024, 2, 3)))
        }
        dispatcher.queueing = false
        dispatcher.runNext()
        oldBatch.join()

        assertTrue(controller.state.batchOperationInProgress, "Only the new batch owns this busy state.")
        dispatcher.runNext()
        newBatch.join()
        assertFalse(controller.state.batchOperationInProgress)
        assertEquals(listOf(note.id), repository.listNotes(notebook.id).map { it.id })
    }

    @Test
    fun frozenLocalCopyRemainsSearchableWhileWritesAreRejected() = runBlocking {
        val repository = InMemoryNotesRepository()
        val notebook = repository.createNotebook("Retained")
        val note = repository.seedNote(notebook.id, "Find this memory", "Retained content", LocalDate(2026, 5, 23))
        val access = TestProductAccess()
        val controller = NotesUiController(repository, backgroundDispatcher = Dispatchers.Unconfined, workspaceProductAccess = access)
        controller.refresh()
        access.readOnly = true
        controller.updateProductReadOnly(true)

        controller.updateSearchQuery("memory")

        assertEquals(listOf(note.id), controller.state.searchResults.map { it.id })
        assertFailsWith<CancellationException> { controller.createNotebook("Blocked") }
        assertEquals(listOf("Retained"), repository.listNotebooks().map { it.title })
    }

    @Test
    fun memoriesDiscardCapturedDataAfterWorkspaceReplacement() = runBlocking {
        val repository = InMemoryNotesRepository()
        val notebook = repository.createNotebook("Memories")
        repository.seedNote(notebook.id, "Last year", "Private prior workspace", LocalDate(2025, 5, 23))
        val access = TestProductAccess()
        val controller = MemoriesUiController(
            repository, initialSelectedDate = LocalDate(2026, 5, 23),
            backgroundDispatcher = Dispatchers.Unconfined, workspaceProductAccess = access,
        )
        val completed = controller.loadRepositoryData()
        assertEquals(1, completed.priorYearNotes.size)
        access.replaceWorkspace()
        controller.applyRepositoryData(completed)
        assertTrue(controller.state.priorYearNotes.isEmpty())
    }

    private fun importedImage() = MediaImportUiResult.Imported(
        assetId = MediaAssetId.fromCanonicalValue("ab".repeat(32)),
        suggestedAltText = "Late image",
    )

    private class TestProductAccess(initialAuthority: String? = "authority") : WorkspaceProductAccess {
        var readOnly = false
        var afterRead: (() -> Unit)? = null
        private var identity = WorkspaceProductSnapshot("workspace", "incarnation", initialAuthority, "writer")
        override fun capture() = identity
        override fun isCurrent(snapshot: WorkspaceProductSnapshot) = snapshot == identity
        override fun <T> read(snapshot: WorkspaceProductSnapshot, block: () -> T): T {
            if (!isCurrent(snapshot)) throw WorkspaceProductChangedException()
            return block().also { afterRead?.also { afterRead = null }?.invoke() }
        }
        override fun <T> mutate(snapshot: WorkspaceProductSnapshot?, block: () -> T): T {
            if (snapshot != null && !isCurrent(snapshot)) throw WorkspaceProductChangedException()
            if (readOnly) throw WorkspaceProductReadOnlyException()
            return block()
        }
        fun bindFirstAuthority(newIncarnation: String = identity.accountIncarnation) {
            identity = identity.copy(authorityBindingId = "authority", accountIncarnation = newIncarnation, localRevision = identity.localRevision + 1)
        }
        fun replaceWorkspace() {
            identity = identity.copy(workspaceId = "replacement", localRevision = identity.localRevision + 1)
        }
        fun invalidateLocalCopy() {
            identity = identity.copy(localRevision = identity.localRevision + 1)
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        var queueing = false
        private val tasks = ArrayDeque<Runnable>()
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = queueing
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() { tasks.removeFirst().run() }
    }
}
