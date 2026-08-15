@file:OptIn(kotlin.time.ExperimentalTime::class)
package saien.someday.ui.notes

import saien.someday.domain.notes.NoteSyncBadge
import saien.someday.domain.notes.NotesLocationInput
import saien.someday.domain.notes.noteCalendarDate
import saien.someday.domain.settings.EditorPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesUiControllerTest {
    @Test
    fun newNoteDefaultsToCurrentLocalDateProvider() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(
            repository = repository,
            currentDateProvider = { LocalDate(2026, 5, 23) },
        )
        val diary = controller.createNotebook("Diary")

        controller.openNewNote(diary.id)

        assertEquals("2026-05-23", controller.state.editor?.createdDateText)
    }

    @Test
    fun successfulLocalMutationsIncrementChangeEventForAutoSync() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)

        val startEventId = controller.state.localChangeEventId
        val diary = controller.createNotebook("Diary")
        assertEquals(startEventId + 1, controller.state.localChangeEventId)

        controller.openNewNote(diary.id)
        controller.updateDraft(title = "Daily note", markdownBody = "Saved body", createdDateText = "2026-05-22")
        assertTrue(controller.saveEditor())
        val savedEventId = controller.state.localChangeEventId
        assertEquals(startEventId + 2, savedEventId)

        controller.openNewNote(diary.id)
        controller.updateDraft(title = "", markdownBody = "Invalid body", createdDateText = "2026-05-22")
        assertFalse(controller.saveEditor())
        assertEquals(savedEventId, controller.state.localChangeEventId)

        val noteId = repository.listNotes(diary.id).single().id
        assertTrue(controller.deleteNote(noteId))
        assertEquals(savedEventId + 1, controller.state.localChangeEventId)
    }

    @Test
    fun notebookSheetCrudBlocksNonEmptyDeletionAndFiltersNotes() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)

        val diary = controller.createNotebook("Diary")
        val work = controller.createNotebook("Work")
        controller.renameNotebook(diary.id, "Daily diary")

        controller.selectNotebook(work.id)
        controller.openNewNote()
        controller.updateDraft(
            title = "Work note",
            markdownBody = "Only visible in Work",
            createdDateText = "2026-05-22",
        )
        assertTrue(controller.saveEditor())

        controller.selectNotebook(diary.id)
        assertEquals("Daily diary", controller.state.selectedNotebook?.title)
        assertTrue(controller.state.notes.isEmpty(), "Selecting Diary must hide Work notes")

        controller.deleteNotebook(work.id)
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("still contains active notes"))
        assertTrue(
            repository.listNotebooks().any { it.id == work.id },
            "Non-empty notebook deletion must be blocked without deleting the notebook",
        )
        assertTrue(
            repository.listNotes(work.id).any { it.title == "Work note" },
            "Non-empty notebook deletion must never silently delete or orphan notes",
        )

        controller.selectNotebook(work.id)
        controller.deleteNote(controller.state.notes.single().id)
        controller.deleteNotebook(work.id)

        assertFalse(repository.listNotebooks().any { it.id == work.id })
        assertEquals(diary.id, controller.state.selectedNotebookId)
    }

    @Test
    fun editorValidationSaveFailureAndUnsavedChangePromptAreNonDestructive() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val travel = controller.createNotebook("Travel")

        controller.selectNotebook(diary.id)
        controller.openNewNote()
        controller.updateDraft(title = "", markdownBody = "Body survives", createdDateText = "2026-05-22")

        assertFalse(controller.saveEditor())
        assertTrue(controller.state.editor?.validationMessage.orEmpty().contains("Title is required"))
        assertEquals("Body survives", controller.state.editor?.markdownBody)

        controller.updateDraft(title = "Draft title")
        controller.requestCloseEditor()
        assertTrue(controller.state.unsavedChangesDialogVisible)
        controller.keepEditing()
        assertEquals("Draft title", controller.state.editor?.title)

        assertFalse(controller.selectNotebook(travel.id))
        assertTrue(controller.state.unsavedChangesDialogVisible)
        assertEquals("Draft title", controller.state.editor?.title)
        controller.keepEditing()

        repository.failNextSave = true
        assertFalse(controller.saveEditor())
        assertTrue(controller.state.editor?.validationMessage.orEmpty().contains("Save failed"))
        assertEquals("Draft title", controller.state.editor?.title)
        assertTrue(repository.listNotes(diary.id).isEmpty())

        controller.updateDraft(
            notebookId = travel.id,
            title = "Trip day",
            markdownBody = "Arrived by train",
            createdDateText = "2026-05-23",
            placeText = "Kyoto Station",
            latitudeText = "35.985",
            longitudeText = "135.758",
        )
        assertTrue(controller.saveEditor())

        assertTrue(repository.listNotes(diary.id).isEmpty())
        val saved = repository.listNotes(travel.id).single()
        assertEquals("Trip day", saved.title)
        assertEquals(LocalDate(2026, 5, 23), noteCalendarDate(saved.createdAt, saved.timeZoneId))
        assertEquals(NotesLocationInput(35.985, 135.758, "Kyoto Station"), repository.getNoteDetails(saved.id)?.location)

        controller.openExistingNote(saved.id)
        controller.updateDraft(title = "Changed but not discarded")
        controller.requestCloseEditor()
        controller.discardEditorChanges()

        assertNull(controller.state.editor)
        assertEquals("Trip day", repository.getNoteDetails(saved.id)?.title)
    }

    @Test
    fun routeExitKeepsEditorStableUntilMatchingSessionIsDisposed() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val first = repository.seedNote(
            notebookId = diary.id,
            title = "First",
            markdownBody = "First body",
            createdDate = LocalDate(2026, 5, 22),
        )
        val second = repository.seedNote(
            notebookId = diary.id,
            title = "Second",
            markdownBody = "Second body",
            createdDate = LocalDate(2026, 5, 23),
        )

        assertTrue(controller.openExistingNote(first.id))
        val firstSessionId = checkNotNull(controller.currentEditorSessionId())
        assertTrue(controller.requestCloseEditor())
        assertEquals("First", controller.state.editor?.title, "Clean close must keep outgoing editor until route exit.")

        assertTrue(controller.openExistingNote(second.id))
        val secondSessionId = checkNotNull(controller.currentEditorSessionId())
        controller.closeEditorSession(firstSessionId)
        assertEquals("Second", controller.state.editor?.title, "Old route exit must not close a newer editor session.")

        controller.closeEditorSession(secondSessionId)
        assertNull(controller.state.editor)
    }

    @Test
    fun openingSearchResultKeepsSearchStateAfterEditorRouteCloses() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val match = repository.seedNote(
            notebookId = diary.id,
            title = "Kyoto itinerary",
            markdownBody = "Temple notes",
            createdDate = LocalDate(2026, 5, 22),
        )
        repository.seedNote(
            notebookId = diary.id,
            title = "Grocery list",
            markdownBody = "Milk",
            createdDate = LocalDate(2026, 5, 23),
        )

        controller.updateSearchQuery("kyoto")
        assertEquals(listOf(match.id), controller.state.searchResults.map { it.id })

        assertTrue(controller.openExistingNote(match.id))
        val sessionId = checkNotNull(controller.currentEditorSessionId())
        assertEquals("kyoto", controller.state.searchQuery)
        assertEquals(listOf(match.id), controller.state.searchResults.map { it.id })

        assertTrue(controller.requestCloseEditor())
        controller.closeEditorSession(sessionId)

        assertNull(controller.state.editor)
        assertEquals("kyoto", controller.state.searchQuery)
        assertEquals(listOf(match.id), controller.state.searchResults.map { it.id })

        controller.clearSearch()
        assertEquals("", controller.state.searchQuery)
        assertTrue(controller.state.searchResults.isEmpty())
    }

    @Test
    fun routeExitSaveKeepsNewNoteVisibleUntilSessionIsDisposed() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")

        assertTrue(controller.openNewNote(diary.id))
        val sessionId = checkNotNull(controller.currentEditorSessionId())
        controller.updateDraft(title = "Draft", markdownBody = "Saved on exit", createdDateText = "2026-05-22")

        assertTrue(controller.saveEditorForRouteExit())
        assertEquals("Draft", controller.state.editor?.title)
        assertNotNull(controller.state.editor?.noteId)

        controller.closeEditorSession(sessionId)
        assertNull(controller.state.editor)
        assertEquals("Draft", repository.listNotes(diary.id).single().title)
    }

    @Test
    fun saveUpdatesVisibleNotesWithoutReloadingWorkspace() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val older = repository.seedNote(
            notebookId = diary.id,
            title = "Older",
            markdownBody = "Older body",
            createdDate = LocalDate(2026, 5, 20),
        )
        controller.selectNotebook(diary.id)

        val notebooksBeforeSave = repository.listNotebooksCalls
        val notesBeforeSave = repository.listNotesCalls
        val deletedBeforeSave = repository.listDeletedWorkspaceItemsCalls
        val conflictsBeforeSave = repository.getNotebookConflictDetailsCalls
        val versionsBeforeSave = repository.listNoteVersionsCalls

        controller.openNewNote(diary.id)
        controller.updateDraft(
            title = "Newest",
            markdownBody = "Newest body",
            createdDateText = "2026-05-24",
        )
        assertTrue(controller.saveEditor())

        assertEquals(listOf("Newest", "Older"), controller.state.notes.map { it.title })
        assertEquals(older.id, controller.state.notes.last().id)
        assertEquals(notebooksBeforeSave, repository.listNotebooksCalls)
        assertEquals(notesBeforeSave, repository.listNotesCalls)
        assertEquals(deletedBeforeSave, repository.listDeletedWorkspaceItemsCalls)
        assertEquals(conflictsBeforeSave, repository.getNotebookConflictDetailsCalls)
        assertEquals(versionsBeforeSave, repository.listNoteVersionsCalls)
    }

    @Test
    fun routeExitSaveDoesNotLoadVersionsOrConflicts() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        controller.selectNotebook(diary.id)

        val notebooksBeforeSave = repository.listNotebooksCalls
        val notesBeforeSave = repository.listNotesCalls
        val deletedBeforeSave = repository.listDeletedWorkspaceItemsCalls
        val conflictsBeforeSave = repository.getNotebookConflictDetailsCalls
        val versionsBeforeSave = repository.listNoteVersionsCalls

        assertTrue(controller.openNewNote(diary.id))
        controller.updateDraft(title = "Draft", markdownBody = "Saved on exit", createdDateText = "2026-05-22")
        assertTrue(controller.saveEditorForRouteExit())

        assertEquals("Draft", controller.state.notes.single().title)
        assertEquals(notebooksBeforeSave, repository.listNotebooksCalls)
        assertEquals(notesBeforeSave, repository.listNotesCalls)
        assertEquals(deletedBeforeSave, repository.listDeletedWorkspaceItemsCalls)
        assertEquals(conflictsBeforeSave, repository.getNotebookConflictDetailsCalls)
        assertEquals(versionsBeforeSave, repository.listNoteVersionsCalls)
    }

    @Test
    fun saveIntoAnotherNotebookReloadsOnlyThatNotebookList() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val travel = controller.createNotebook("Travel")
        controller.selectNotebook(diary.id)

        val notebooksBeforeSave = repository.listNotebooksCalls
        val notesBeforeSave = repository.listNotesCalls
        val deletedBeforeSave = repository.listDeletedWorkspaceItemsCalls

        controller.openNewNote(diary.id)
        controller.updateDraft(
            notebookId = travel.id,
            title = "Trip day",
            markdownBody = "Arrived by train",
            createdDateText = "2026-05-23",
        )
        assertTrue(controller.saveEditor())

        assertEquals(travel.id, controller.state.selectedNotebookId)
        assertEquals(listOf("Trip day"), controller.state.notes.map { it.title })
        assertEquals(notebooksBeforeSave, repository.listNotebooksCalls)
        assertEquals(notesBeforeSave + 1, repository.listNotesCalls)
        assertEquals(deletedBeforeSave, repository.listDeletedWorkspaceItemsCalls)
    }

    @Test
    fun syncErrorPendingAndConflictBadgesAreVisibleInListAndEditor() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")

        repository.seedNote(
            notebookId = diary.id,
            title = "Pending note",
            markdownBody = "Awaiting upload",
            createdDate = LocalDate(2026, 5, 22),
            syncBadge = NoteSyncBadge.Pending,
        )
        repository.seedNote(
            notebookId = diary.id,
            title = "Sync error note",
            markdownBody = "Retry later",
            createdDate = LocalDate(2026, 5, 22),
            syncBadge = NoteSyncBadge.Error("Network unavailable"),
        )
        val conflict = repository.seedNote(
            notebookId = diary.id,
            title = "Conflict copy from laptop",
            markdownBody = "Preserved remote edit",
            createdDate = LocalDate(2026, 5, 22),
            syncBadge = NoteSyncBadge.Conflict("Manual resolution required"),
        )

        controller.selectNotebook(diary.id)

        assertEquals(
            setOf("Pending sync", "Sync error", "Conflict"),
            controller.state.notes.map { it.syncBadge.label }.toSet(),
        )

        controller.openExistingNote(conflict.id)

        assertNotNull(controller.state.editor)
        assertEquals("Conflict", controller.state.editor?.syncBadge?.label)
        assertTrue(controller.state.editor?.syncBadge?.details.orEmpty().contains("Manual resolution"))
    }

    @Test
    fun conflictEditorExposesOriginalAndConflictHistories() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val conflictPair = repository.seedConflictPair(
            notebookId = diary.id,
            originalTitle = "Local original",
            originalBody = "Local version history",
            conflictTitle = "Conflict copy from phone",
            conflictBody = "Remote version history",
        )

        controller.selectNotebook(diary.id)
        controller.openExistingNote(conflictPair.conflictNoteId)

        val details = checkNotNull(controller.state.conflictDetails)
        assertEquals(conflictPair.originalNoteId, details.originalHistory.noteId)
        assertEquals(conflictPair.conflictNoteId, details.conflictHistory.noteId)
        assertTrue(details.originalHistory.versions.any { it.markdownBody.contains("Local version") })
        assertTrue(details.conflictHistory.versions.any { it.markdownBody.contains("Remote version") })
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("Conflict copy exposes both histories"))
    }

    @Test
    fun originalNoteWithConflictExposesResolutionEntryState() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val conflictPair = repository.seedConflictPair(
            notebookId = diary.id,
            originalTitle = "Local original",
            originalBody = "Local version history",
            conflictTitle = "Conflict copy from phone",
            conflictBody = "Remote version history",
        )

        controller.selectNotebook(diary.id)
        controller.openExistingNote(conflictPair.originalNoteId)

        val details = checkNotNull(controller.state.conflictDetails)
        assertEquals(conflictPair.originalNoteId, details.originalNoteId)
        assertEquals(conflictPair.conflictNoteId, details.conflictNoteId)
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("sync conflict"))

        assertTrue(controller.openConflictResolution(conflictPair.conflictNoteId))
        assertEquals(conflictPair.conflictNoteId, controller.state.conflictDetails?.conflictNoteId)
    }

    @Test
    fun syncRefreshShowsConflictBannerForAlreadyOpenNote() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val original = repository.seedNote(
            notebookId = diary.id,
            title = "Local original",
            markdownBody = "Local body",
            createdDate = LocalDate(2026, 5, 22),
        )

        controller.selectNotebook(diary.id)
        assertTrue(controller.openExistingNote(original.id))
        assertNull(controller.state.conflictDetails)

        val conflictPair = repository.seedConflictForOriginal(
            originalNoteId = original.id,
            conflictTitle = "Conflict copy from phone",
            conflictBody = "Remote body",
        )
        controller.refreshAfterSync()

        val details = checkNotNull(controller.state.conflictDetails)
        assertEquals(conflictPair.conflictNoteId, details.conflictNoteId)
        assertEquals(original.id, details.originalNoteId)
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("sync conflict"))
    }

    @Test
    fun syncRefreshDoesNotOverwriteUnsavedEditorWhileShowingConflict() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val original = repository.seedNote(
            notebookId = diary.id,
            title = "Local original",
            markdownBody = "Local body",
            createdDate = LocalDate(2026, 5, 22),
        )

        controller.selectNotebook(diary.id)
        assertTrue(controller.openExistingNote(original.id))
        controller.updateDraft(
            title = "Unsaved local title",
            markdownBody = "Unsaved local body",
        )

        repository.seedConflictForOriginal(
            originalNoteId = original.id,
            conflictTitle = "Conflict copy from phone",
            conflictBody = "Remote body",
        )
        controller.refreshAfterSync()

        assertNotNull(controller.state.conflictDetails)
        assertEquals("Unsaved local title", controller.state.editor?.title)
        assertEquals("Unsaved local body", controller.state.editor?.markdownBody)
    }

    @Test
    fun markdownPreviewTogglePreservesExactSourceAndToolbarFormatsSelection() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")
        val exactSource = """
            |# Morning
            |
            |Before code:
            |```
            |  keep indentation
            |```
            |
            |Trailing spaces stay here:
        """.trimMargin() + "   "

        controller.selectNotebook(diary.id)
        controller.openNewNote()
        controller.updateDraft(
            title = "Markdown source",
            markdownBody = exactSource,
            createdDateText = "2026-05-22",
        )

        controller.toggleMarkdownPreview()
        assertTrue(controller.state.editor?.markdownPreviewVisible == true)
        assertEquals(exactSource, controller.state.editor?.markdownBody)

        controller.toggleMarkdownPreview()
        assertFalse(controller.state.editor?.markdownPreviewVisible == true)
        assertEquals(exactSource, controller.state.editor?.markdownBody)

        val selectionStart = exactSource.indexOf("Morning")
        controller.updateMarkdownSelection(selectionStart, selectionStart + "Morning".length)
        controller.applyMarkdownToolbarAction(MarkdownToolbarAction.Bold)

        val formattedSource = exactSource.replace("Morning", "**Morning**")
        assertEquals(formattedSource, controller.state.editor?.markdownBody)
        assertTrue(controller.saveEditor())

        val saved = repository.listNotes(diary.id).single()
        assertEquals(formattedSource, repository.getNoteDetails(saved.id)?.markdownBody)
    }

    @Test
    fun editorPreviewPreferenceControlsNewAndOpenedNotes() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(
            repository = repository,
            editorPreferences = EditorPreferences(previewByDefault = true),
        )
        val diary = controller.createNotebook("Diary")

        controller.openNewNote(diary.id)
        assertTrue(controller.state.editor?.markdownPreviewVisible == true)
        controller.updateDraft(
            title = "Preview first",
            markdownBody = "# Visible",
            createdDateText = "2026-05-22",
        )
        assertTrue(controller.saveEditor())

        val saved = repository.listNotes(diary.id).single()

        controller.updateEditorPreferences(EditorPreferences(previewByDefault = false))
        controller.openExistingNote(saved.id)
        assertFalse(controller.state.editor?.markdownPreviewVisible == true)

        controller.updateEditorPreferences(EditorPreferences(previewByDefault = true))
        controller.openExistingNote(saved.id)
        assertTrue(controller.state.editor?.markdownPreviewVisible == true)
    }

    @Test
    fun versionHistoryIsVisibleAndRestoresOlderVersionAsNewHead() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(repository)
        val diary = controller.createNotebook("Diary")

        controller.selectNotebook(diary.id)
        controller.openNewNote()
        controller.updateDraft(
            title = "First title",
            markdownBody = "First body",
            createdDateText = "2026-05-22",
        )
        assertTrue(controller.saveEditor())
        val noteId = repository.listNotes(diary.id).single().id

        controller.openExistingNote(noteId)
        controller.updateDraft(
            title = "Second title",
            markdownBody = "Second body",
        )
        assertTrue(controller.saveEditor())

        controller.openExistingNote(noteId)
        controller.showVersionHistory()

        val historyBeforeRestore = checkNotNull(controller.state.versionHistory)
        val firstVersion = historyBeforeRestore.versions.first()
        val currentVersion = historyBeforeRestore.versions.last()

        assertTrue(historyBeforeRestore.visible)
        assertEquals(2, historyBeforeRestore.versions.size)
        assertEquals("First body", firstVersion.markdownBody)

        assertTrue(controller.restoreVersion(firstVersion.versionId))

        val restoredEditor = checkNotNull(controller.state.editor)
        val historyAfterRestore = checkNotNull(controller.state.versionHistory)
        val restoreVersion = historyAfterRestore.versions.last()

        assertEquals("First title", restoredEditor.title)
        assertEquals("First body", restoredEditor.markdownBody)
        assertTrue(historyAfterRestore.visible)
        assertEquals(3, historyAfterRestore.versions.size)
        assertEquals(currentVersion.versionId, restoreVersion.parentVersionId)
        assertEquals(firstVersion.versionId, restoreVersion.baseVersionId)
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("Restored version"))
    }

    @Test
    fun generatedDemoContentHasNoVisibleMockLabelsAndCanBeCleared() = runBlocking {
        val repository = InMemoryNotesRepository()
        val controller = NotesUiController(
            repository = repository,
            currentDateProvider = { LocalDate(2026, 5, 23) },
        )

        val result = controller.createMockContent()

        assertEquals(10, result.createdNotebooks)
        assertEquals(1_000, result.createdNotes)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("mock", ignoreCase = true))
        assertEquals(10, repository.listNotebooks().size)
        repository.listNotebooks().forEach { notebook ->
            assertEquals(100, repository.listNotes(notebook.id).size)
        }

        val generatedText = buildString {
            repository.listNotebooks().forEach { notebook ->
                appendLine(notebook.title)
                repository.listNotes(notebook.id).forEach { note ->
                    val body = repository.getNoteDetails(note.id)?.markdownBody.orEmpty()
                    assertFalse(
                        body.lineSequence().firstOrNull()?.trim() == "# ${note.title}",
                        "Generated demo body must not duplicate the note title as its first heading.",
                    )
                    appendLine(note.title)
                    appendLine(body)
                }
            }
        }
        assertFalse(generatedText.contains("mock", ignoreCase = true))
        assertFalse(generatedText.contains("someday-dev", ignoreCase = true))

        val cleared = controller.clearMockContent()

        assertEquals(10, cleared.deletedNotebooks)
        assertEquals(1_000, cleared.deletedNotes)
        assertTrue(repository.listNotebooks().isEmpty())
    }
}
