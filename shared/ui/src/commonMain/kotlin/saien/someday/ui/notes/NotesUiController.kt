@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.ui.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import saien.someday.domain.location.LocationCaptureAdapter
import saien.someday.domain.location.LocationCaptureResult
import saien.someday.domain.location.UnavailableLocationCaptureAdapter
import saien.someday.domain.notes.ConflictDetails
import saien.someday.domain.notes.ConflictResolutionAction
import saien.someday.domain.notes.CausalEditToken
import saien.someday.domain.notes.DeletedWorkspaceItem
import saien.someday.domain.notes.DeletedWorkspaceItemType
import saien.someday.domain.notes.NoteDetails
import saien.someday.domain.notes.NoteBatchDeletion
import saien.someday.domain.notes.NoteBatchUndelete
import saien.someday.domain.notes.NoteBatchUpdate
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.notes.NoteSummary
import saien.someday.domain.notes.NoteSyncBadge
import saien.someday.domain.notes.NoteVersionSummary
import saien.someday.domain.notes.NotebookSummary
import saien.someday.domain.notes.NotebookConflictDetails
import saien.someday.domain.notes.NotesLocationInput
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.notes.noteCalendarDate
import saien.someday.domain.settings.EditorPreferences
import saien.someday.ui.i18n.NotesUiStrings
import saien.someday.ui.i18n.formatUiString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

class NotesUiController(
    private val repository: NotesRepository,
    private val strings: NotesUiStrings = NotesUiStrings(),
    private val locationCaptureAdapter: LocationCaptureAdapter = UnavailableLocationCaptureAdapter,
    initialNotebookId: String? = null,
    private var editorPreferences: EditorPreferences = EditorPreferences(),
    private val currentDateProvider: () -> LocalDate = { currentLocalDate() },
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    var state: NotesUiState by mutableStateOf(NotesUiState(selectedNotebookId = initialNotebookId))
        private set
    private var nextEditorSessionIdSeed: Long = 1L

    fun updateEditorPreferences(preferences: EditorPreferences) {
        editorPreferences = preferences
    }

    suspend fun refresh(preferredNotebookId: String? = state.selectedNotebookId) {
        applyRepositoryData(
            loadRepositoryData(
                preferredNotebookId = preferredNotebookId,
                searchQuery = state.searchQuery,
            ),
        )
    }

    suspend fun refreshAfterSync(preferredNotebookId: String? = state.selectedNotebookId) {
        val editorBeforeRefresh = state.editor
        val currentVersionHistory = state.versionHistory
        val activeConflictNoteId = state.conflictDetails?.conflictNoteId
        val searchQuery = state.searchQuery
        val refreshed = withContext(backgroundDispatcher) {
            val repositoryData = loadRepositoryDataBlocking(
                preferredNotebookId = preferredNotebookId,
                searchQuery = searchQuery,
            )
            val editorRefresh = editorBeforeRefresh
                ?.noteId
                ?.let { noteId ->
                    val details = repository.getNoteDetails(noteId)
                    if (details == null) {
                        EditorRefreshResult.Missing(noteId)
                    } else {
                        EditorRefreshResult.Found(
                            details = details,
                            versions = repository.listNoteVersions(details.id),
                            conflictDetails = conflictDetailsForNote(details),
                        )
                    }
                }
            SyncRefreshData(
                repositoryData = repositoryData,
                editorRefresh = editorRefresh,
                conflictDetails = if (editorRefresh == null) {
                    activeConflictNoteId?.let(repository::getConflictDetails)
                } else {
                    null
                },
            )
        }
        applyRepositoryData(refreshed.repositoryData)
        when (val editorRefresh = refreshed.editorRefresh) {
            is EditorRefreshResult.Found -> {
                val currentEditor = state.editor
                val refreshedEditor = if (currentEditor?.noteId == editorRefresh.details.id) {
                    if (currentEditor.hasUnsavedChanges) {
                        currentEditor.copy(syncBadge = editorRefresh.details.syncBadge)
                    } else {
                        NoteEditorState.fromDetails(
                            details = editorRefresh.details,
                            markdownPreviewVisible = currentEditor.markdownPreviewVisible,
                            sessionId = currentEditor.sessionId,
                        )
                    }
                } else {
                    state.editor
                }
                state = state.copy(
                    editor = refreshedEditor,
                    versionHistory = if (currentVersionHistory?.noteId == editorRefresh.details.id) {
                        currentVersionHistory.copy(versions = editorRefresh.versions)
                    } else {
                        state.versionHistory
                    },
                    conflictDetails = editorRefresh.conflictDetails,
                    feedbackMessage = if (editorRefresh.conflictDetails != null && state.conflictDetails == null) {
                        strings.conflictWaiting
                    } else {
                        state.feedbackMessage
                    },
                )
            }
            is EditorRefreshResult.Missing -> {
                state = state.copy(
                    editor = null,
                    versionHistory = null,
                    conflictDetails = state.conflictDetails?.takeUnless { it.referencesNote(editorRefresh.noteId) },
                    feedbackMessage = strings.noteNoLongerExists,
                )
            }
            null -> {
                if (refreshed.conflictDetails != null || state.conflictDetails != null) {
                    state = state.copy(conflictDetails = refreshed.conflictDetails)
                }
            }
        }
    }

    suspend fun selectNotebook(notebookId: String): Boolean {
        if (state.editor?.hasUnsavedChanges == true) {
            state = state.copy(
                unsavedChangesDialogVisible = true,
                feedbackMessage = strings.resolveBeforeSwitchNotebook,
            )
            return false
        }
        clearNoteSelection()
        refresh(preferredNotebookId = notebookId)
        closeEditor()
        return true
    }

    suspend fun updateSearchQuery(query: String) {
        state = state.copy(
            searchQuery = query,
            searchResults = if (query.isBlank()) emptyList() else state.searchResults,
            selectedNoteIds = emptySet(),
            noteSelectionAnchorId = null,
            feedbackMessage = null,
        )
        val results = withContext(backgroundDispatcher) {
            query
                .takeIf { it.isNotBlank() }
                ?.let(repository::searchNotes)
                .orEmpty()
        }
        if (state.searchQuery != query) {
            return
        }
        state = state.copy(
            searchResults = results,
            feedbackMessage = if (query.isBlank()) null else formatUiString(strings.foundNotes, results.size),
        )
    }

    fun clearSearch() {
        state = state.copy(
            searchQuery = "",
            searchResults = emptyList(),
            selectedNoteIds = emptySet(),
            noteSelectionAnchorId = null,
            feedbackMessage = null,
        )
    }

    fun toggleNoteSelection(
        noteId: String,
        extendRange: Boolean = false,
    ) {
        if (state.batchOperationInProgress) return
        val visibleIds = state.visibleNotes.map { it.id }
        if (noteId !in visibleIds) return
        val anchorId = state.noteSelectionAnchorId
        val nextSelection = if (extendRange && anchorId in visibleIds) {
            val anchorIndex = visibleIds.indexOf(anchorId)
            val noteIndex = visibleIds.indexOf(noteId)
            val range = visibleIds.subList(
                fromIndex = minOf(anchorIndex, noteIndex),
                toIndex = maxOf(anchorIndex, noteIndex) + 1,
            )
            state.selectedNoteIds + range
        } else if (noteId in state.selectedNoteIds) {
            state.selectedNoteIds - noteId
        } else {
            state.selectedNoteIds + noteId
        }
        state = state.copy(
            selectedNoteIds = nextSelection,
            noteSelectionAnchorId = when {
                nextSelection.isEmpty() -> null
                extendRange && anchorId != null -> anchorId
                else -> noteId
            },
        )
    }

    fun selectAllVisibleNotes() {
        if (state.batchOperationInProgress) return
        val visibleIds = state.visibleNotes.map { it.id }
        state = state.copy(
            selectedNoteIds = visibleIds.toSet(),
            noteSelectionAnchorId = visibleIds.firstOrNull(),
        )
    }

    fun clearNoteSelection() {
        if (state.batchOperationInProgress) return
        state = state.copy(
            selectedNoteIds = emptySet(),
            noteSelectionAnchorId = null,
        )
    }

    suspend fun moveSelectedNotes(notebookId: String): Boolean {
        if (state.notebooks.none { it.id == notebookId && it.syncBadge !is NoteSyncBadge.Error }) {
            state = state.copy(feedbackMessage = strings.batchNotebookUnavailable)
            return false
        }
        return updateSelectedNotes(BatchUpdateKind.Moved) { details ->
            details.toBatchInput(notebookId = notebookId)
        }
    }

    suspend fun changeSelectedNotesCreatedDate(date: LocalDate): Boolean =
        updateSelectedNotes(BatchUpdateKind.Updated) { details ->
            details.toBatchInput(createdAt = date.toInstantAtStartOfDay(details.timeZoneId))
        }

    suspend fun changeSelectedNotesTimeZone(timeZoneId: String?): Boolean {
        val normalized = timeZoneId?.trim()?.ifBlank { null }
        if (normalized != null && runCatching { TimeZone.of(normalized) }.isFailure) {
            state = state.copy(feedbackMessage = strings.invalidTimeZone)
            return false
        }
        return updateSelectedNotes(BatchUpdateKind.Updated) { details ->
            val currentDate = noteCalendarDate(details.createdAt, details.timeZoneId)
            val createdAt = if (noteCalendarDate(details.createdAt, normalized) == currentDate) {
                details.createdAt
            } else {
                currentDate.toInstantAtStartOfDay(normalized)
            }
            details.toBatchInput(createdAt = createdAt, timeZoneId = normalized)
        }
    }

    suspend fun clearSelectedNotesLocation(): Boolean =
        updateSelectedNotes(BatchUpdateKind.Updated) { details ->
            details.toBatchInput(location = null)
        }

    suspend fun deleteSelectedNotes(): Boolean {
        val noteIds = selectedNoteIdsInVisibleOrder()
        if (noteIds.isEmpty() || !beginBatchOperation()) return false
        val selectedNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        return runCatching {
            withContext(backgroundDispatcher) {
                val details = noteIds.map { noteId ->
                    requireNotNull(repository.getNoteDetails(noteId)) { "Note no longer exists: $noteId" }
                }
                repository.deleteNotes(details.map { detail ->
                    NoteBatchDeletion(noteId = detail.id, causalToken = detail.causalToken)
                })
                val repositoryData = loadRepositoryDataBlocking(selectedNotebookId, searchQuery)
                val undoItems = repositoryData.deletedWorkspaceItems
                    .filter { it.type == DeletedWorkspaceItemType.Note && it.entityId in noteIds && it.canRestore }
                BatchDeleteResult(repositoryData, undoItems)
            }
        }.fold(
            onSuccess = { result ->
                applyRepositoryData(result.repositoryData)
                state = state.copy(
                    editor = state.editor?.takeUnless { it.noteId in noteIds },
                    versionHistory = state.versionHistory?.takeUnless { it.noteId in noteIds },
                    conflictDetails = state.conflictDetails?.takeUnless { details ->
                        noteIds.any(details::referencesNote)
                    },
                    selectedNoteIds = emptySet(),
                    noteSelectionAnchorId = null,
                    batchOperationInProgress = false,
                    batchDeleteUndoItems = result.undoItems,
                    feedbackMessage = formatUiString(strings.notesDeleted, noteIds.size),
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                state = state.copy(
                    batchOperationInProgress = false,
                    feedbackMessage = formatUiString(
                        strings.cannotEditNotes,
                        failure.message ?: strings.unknownError,
                    ),
                )
                false
            },
        )
    }

    suspend fun undoLastBatchDelete(): Boolean {
        val items = state.batchDeleteUndoItems
        if (items.isEmpty() || state.batchOperationInProgress) return false
        state = state.copy(batchOperationInProgress = true)
        val selectedNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        return runCatching {
            withContext(backgroundDispatcher) {
                repository.undeleteNotes(items.map { item ->
                    NoteBatchUndelete(
                        noteId = item.entityId,
                        retainedContentVersionId = requireNotNull(item.retainedContentVersionId),
                        causalToken = item.causalToken,
                    )
                })
                loadRepositoryDataBlocking(selectedNotebookId, searchQuery)
            }
        }.fold(
            onSuccess = { repositoryData ->
                applyRepositoryData(repositoryData)
                state = state.copy(
                    batchOperationInProgress = false,
                    batchDeleteUndoItems = emptyList(),
                    feedbackMessage = formatUiString(strings.notesRestored, items.size),
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                state = state.copy(
                    batchOperationInProgress = false,
                    feedbackMessage = formatUiString(
                        strings.cannotEditNotes,
                        failure.message ?: strings.unknownError,
                    ),
                )
                false
            },
        )
    }

    fun dismissBatchDeleteUndo() {
        state = state.copy(batchDeleteUndoItems = emptyList())
    }

    private suspend fun updateSelectedNotes(
        kind: BatchUpdateKind,
        transform: (NoteDetails) -> NoteInput,
    ): Boolean {
        val noteIds = selectedNoteIdsInVisibleOrder()
        if (noteIds.isEmpty() || !beginBatchOperation()) return false
        val selectedNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        return runCatching {
            withContext(backgroundDispatcher) {
                val edits = noteIds.map { noteId ->
                    val details = requireNotNull(repository.getNoteDetails(noteId)) {
                        "Note no longer exists: $noteId"
                    }
                    NoteBatchUpdate(noteId, transform(details))
                }
                val updated = repository.updateNotes(edits)
                BatchUpdateResult(
                    repositoryData = loadRepositoryDataBlocking(selectedNotebookId, searchQuery),
                    updated = updated.associateBy { it.id },
                )
            }
        }.fold(
            onSuccess = { result ->
                val previousEditor = state.editor
                applyRepositoryData(result.repositoryData)
                val refreshedEditor = previousEditor
                    ?.noteId
                    ?.let(result.updated::get)
                    ?.let { details ->
                        NoteEditorState.fromDetails(
                            details = details,
                            markdownPreviewVisible = previousEditor.markdownPreviewVisible,
                            sessionId = previousEditor.sessionId,
                        )
                    }
                    ?: previousEditor
                state = state.copy(
                    editor = refreshedEditor,
                    selectedNoteIds = emptySet(),
                    noteSelectionAnchorId = null,
                    batchOperationInProgress = false,
                    feedbackMessage = formatUiString(
                        if (kind == BatchUpdateKind.Moved) strings.notesMoved else strings.notesUpdated,
                        noteIds.size,
                    ),
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                state = state.copy(
                    batchOperationInProgress = false,
                    feedbackMessage = formatUiString(
                        strings.cannotEditNotes,
                        failure.message ?: strings.unknownError,
                    ),
                )
                false
            },
        )
    }

    private fun selectedNoteIdsInVisibleOrder(): List<String> =
        state.visibleNotes.map { it.id }.filter { it in state.selectedNoteIds }

    private fun beginBatchOperation(): Boolean {
        val editor = state.editor
        if (editor?.hasUnsavedChanges == true && editor.noteId in state.selectedNoteIds) {
            state = state.copy(feedbackMessage = strings.resolveBeforeBatchUpdate)
            return false
        }
        state = state.copy(
            editor = editor?.takeUnless { it.noteId in state.selectedNoteIds },
            versionHistory = state.versionHistory?.takeUnless { it.noteId in state.selectedNoteIds },
            conflictDetails = state.conflictDetails?.takeUnless { details ->
                state.selectedNoteIds.any(details::referencesNote)
            },
            unsavedChangesDialogVisible = false,
            batchOperationInProgress = true,
            batchDeleteUndoItems = emptyList(),
            feedbackMessage = null,
        )
        return true
    }

    fun canNavigateToNewNote(notebookId: String? = state.selectedNotebookId): Boolean {
        if (state.editor?.hasUnsavedChanges == true) {
            state = state.copy(
                unsavedChangesDialogVisible = true,
                feedbackMessage = strings.resolveBeforeOpen,
            )
            return false
        }
        val targetNotebookId = notebookId
            ?.takeIf { candidate -> state.notebooks.any { it.id == candidate } }
            ?: state.selectedNotebookId
        if (targetNotebookId == null) {
            state = state.copy(feedbackMessage = strings.createNotebookFirst)
            return false
        }
        return true
    }

    fun canNavigateToExistingNote(noteId: String): Boolean {
        if (state.editor?.hasUnsavedChanges == true && state.editor?.noteId != noteId) {
            state = state.copy(
                unsavedChangesDialogVisible = true,
                feedbackMessage = strings.resolveBeforeOpen,
            )
            return false
        }
        return true
    }

    suspend fun createNotebook(title: String): NotebookSummary {
        val searchQuery = state.searchQuery
        val created = withContext(backgroundDispatcher) {
            repository.createNotebook(title.trim())
        }
        applyRepositoryData(
            loadRepositoryData(
                preferredNotebookId = created.id,
                searchQuery = searchQuery,
            ),
        )
        state = state.copy(
            feedbackMessage = formatUiString(strings.notebookCreated, created.title),
            localChangeEventId = state.localChangeEventId + 1,
        )
        return created
    }

    suspend fun renameNotebook(
        notebookId: String,
        title: String,
    ): NotebookSummary {
        val preferredNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        val renamed = withContext(backgroundDispatcher) {
            state.notebooks.firstOrNull { it.id == notebookId }?.causalToken?.let { token ->
                repository.renameNotebook(notebookId, title.trim(), token)
            } ?: repository.renameNotebook(notebookId, title.trim())
        }
        applyRepositoryData(
            loadRepositoryData(
                preferredNotebookId = preferredNotebookId,
                searchQuery = searchQuery,
            ),
        )
        state = state.copy(
            feedbackMessage = formatUiString(strings.notebookRenamed, renamed.title),
            localChangeEventId = state.localChangeEventId + 1,
        )
        return renamed
    }

    suspend fun deleteNotebook(notebookId: String): Boolean {
        val preferredNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        return runCatching {
            withContext(backgroundDispatcher) {
                state.notebooks.firstOrNull { it.id == notebookId }?.causalToken?.let { token ->
                    repository.deleteNotebook(notebookId, token)
                } ?: repository.deleteNotebook(notebookId)
            }
        }.fold(
            onSuccess = {
                applyRepositoryData(
                    loadRepositoryData(
                        preferredNotebookId = preferredNotebookId?.takeUnless { it == notebookId },
                        searchQuery = searchQuery,
                    ),
                )
                state = state.copy(
                    feedbackMessage = strings.notebookDeleted,
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                applyRepositoryData(
                    loadRepositoryData(
                        preferredNotebookId = preferredNotebookId,
                        searchQuery = searchQuery,
                    ),
                )
                state = state.copy(
                    feedbackMessage = formatUiString(strings.cannotDeleteNotebook, failure.message ?: strings.unknownError),
                )
                false
            },
        )
    }

    suspend fun resolveNotebookConflictBranch(notebookId: String, versionId: String): Boolean {
        val conflict = state.notebookConflicts[notebookId] ?: return false
        val preferredNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        return runCatching {
            withContext(backgroundDispatcher) {
                repository.resolveNotebookConflictBranch(
                    conflict.conflictId,
                    versionId,
                    conflict.expectedHeadVersionIds,
                )
                loadRepositoryDataBlocking(preferredNotebookId, searchQuery)
            }
        }.fold(
            onSuccess = { data ->
                applyRepositoryData(data)
                state = state.copy(
                    feedbackMessage = strings.notebookConflictResolved,
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                state = state.copy(feedbackMessage = formatUiString(strings.cannotResolveNotebookConflict, failure.message ?: strings.unknownError))
                false
            },
        )
    }

    suspend fun restoreDeletedWorkspaceItem(entityId: String): Boolean {
        val item = state.deletedWorkspaceItems.firstOrNull { it.entityId == entityId } ?: return false
        val retainedVersion = item.retainedContentVersionId
        if (retainedVersion == null) {
            state = state.copy(
                feedbackMessage = strings.snapshotExpiredDeletion,
            )
            return false
        }
        return runCatching {
            withContext(backgroundDispatcher) {
                when (item.type) {
                    DeletedWorkspaceItemType.Note -> repository.undeleteNote(
                        item.entityId,
                        retainedVersion,
                        item.causalToken,
                    )
                    DeletedWorkspaceItemType.Notebook -> repository.restoreNotebook(
                        item.entityId,
                        retainedVersion,
                        item.causalToken,
                    )
                }
                loadRepositoryDataBlocking(state.selectedNotebookId, state.searchQuery)
            }
        }.fold(
            onSuccess = { data ->
                applyRepositoryData(data)
                state = state.copy(
                    feedbackMessage = if (item.type == DeletedWorkspaceItemType.Note) {
                        strings.noteUndeleted
                    } else {
                        strings.notebookRestored
                    },
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                state = state.copy(feedbackMessage = formatUiString(strings.cannotRestoreDeleted, failure.message ?: strings.unknownError))
                false
            },
        )
    }

    fun openNewNote(notebookId: String? = state.selectedNotebookId): Boolean {
        if (state.editor?.hasUnsavedChanges == true) {
            state = state.copy(
                unsavedChangesDialogVisible = true,
                feedbackMessage = strings.resolveBeforeOpen,
            )
            return false
        }
        val targetNotebookId = notebookId
            ?.takeIf { candidate -> state.notebooks.any { it.id == candidate } }
            ?: state.selectedNotebookId
        if (targetNotebookId == null) {
            state = state.copy(feedbackMessage = strings.createNotebookFirst)
            return false
        }

        state = state.copy(
            editor = NoteEditorState.newDraft(
                sessionId = nextEditorSessionId(),
                notebookId = targetNotebookId,
                createdDateText = currentDateProvider().toString(),
                markdownPreviewVisible = editorPreferences.previewByDefault,
            ),
            versionHistory = null,
            conflictDetails = null,
            unsavedChangesDialogVisible = false,
            feedbackMessage = null,
        )
        return true
    }

    suspend fun openExistingNote(noteId: String): Boolean {
        if (state.editor?.hasUnsavedChanges == true && state.editor?.noteId != noteId) {
            state = state.copy(
                unsavedChangesDialogVisible = true,
                feedbackMessage = strings.resolveBeforeOpen,
            )
            return false
        }
        val fallbackNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        val loaded = withContext(backgroundDispatcher) {
            val details = repository.getNoteDetails(noteId)
            if (details == null) {
                ExistingNoteLoadResult.Missing(
                    repositoryData = loadRepositoryDataBlocking(
                        preferredNotebookId = fallbackNotebookId,
                        searchQuery = searchQuery,
                    ),
                )
            } else {
                ExistingNoteLoadResult.Found(
                    details = details,
                    notes = repository.listNotes(details.notebookId),
                    versions = repository.listNoteVersions(details.id),
                    conflictDetails = conflictDetailsForNote(details),
                )
            }
        }
        when (loaded) {
            is ExistingNoteLoadResult.Missing -> {
                applyRepositoryData(loaded.repositoryData)
                state = state.copy(feedbackMessage = strings.noteNoLongerExists)
                return false
            }
            is ExistingNoteLoadResult.Found -> Unit
        }
        val details = loaded.details
        state = state.copy(
            editor = NoteEditorState.fromDetails(
                details = details,
                markdownPreviewVisible = editorPreferences.previewByDefault,
                sessionId = nextEditorSessionId(),
            ),
            selectedNotebookId = details.notebookId,
            notes = loaded.notes,
            versionHistory = NoteVersionHistoryState(
                noteId = details.id,
                versions = loaded.versions,
                visible = false,
            ),
            conflictDetails = loaded.conflictDetails,
            unsavedChangesDialogVisible = false,
            feedbackMessage = when {
                details.syncBadge is NoteSyncBadge.Conflict ->
                    strings.conflictCopyExposes
                loaded.conflictDetails != null ->
                    strings.conflictWaiting
                else -> null
            },
        )
        return true
    }

    suspend fun openConflictResolution(conflictNoteId: String): Boolean {
        val preferredNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        val loaded = withContext(backgroundDispatcher) {
            val details = repository.getConflictDetails(conflictNoteId)
            if (details == null) {
                ConflictResolutionLoadResult.Missing(
                    repositoryData = loadRepositoryDataBlocking(
                        preferredNotebookId = preferredNotebookId,
                        searchQuery = searchQuery,
                    ),
                )
            } else {
                ConflictResolutionLoadResult.Found(
                    details = details,
                    repositoryData = loadRepositoryDataBlocking(
                        preferredNotebookId = repository.getNoteDetails(details.originalNoteId)?.notebookId
                            ?: preferredNotebookId,
                        searchQuery = searchQuery,
                    ),
                )
            }
        }
        return when (loaded) {
            is ConflictResolutionLoadResult.Found -> {
                applyRepositoryData(loaded.repositoryData)
                state = state.copy(
                    conflictDetails = loaded.details,
                    feedbackMessage = null,
                )
                true
            }
            is ConflictResolutionLoadResult.Missing -> {
                applyRepositoryData(loaded.repositoryData)
                state = state.copy(
                    conflictDetails = null,
                    feedbackMessage = strings.conflictGone,
                )
                false
            }
        }
    }

    fun updateDraft(
        notebookId: String? = null,
        title: String? = null,
        markdownBody: String? = null,
        createdDateText: String? = null,
        placeText: String? = null,
        latitudeText: String? = null,
        longitudeText: String? = null,
        accuracyMetersText: String? = null,
        altitudeMetersText: String? = null,
        capturedAtText: String? = null,
    ) {
        val editor = state.editor ?: return
        val updatedMarkdownBody = markdownBody ?: editor.markdownBody
        val updated = editor.copy(
            notebookId = notebookId ?: editor.notebookId,
            title = title ?: editor.title,
            markdownBody = updatedMarkdownBody,
            createdDateText = createdDateText ?: editor.createdDateText,
            placeText = placeText ?: editor.placeText,
            latitudeText = latitudeText ?: editor.latitudeText,
            longitudeText = longitudeText ?: editor.longitudeText,
            accuracyMetersText = accuracyMetersText ?: editor.accuracyMetersText,
            altitudeMetersText = altitudeMetersText ?: editor.altitudeMetersText,
            capturedAtText = capturedAtText ?: editor.capturedAtText,
            markdownSelectionStart = editor.markdownSelectionStart.coerceIn(0, updatedMarkdownBody.length),
            markdownSelectionEnd = editor.markdownSelectionEnd.coerceIn(0, updatedMarkdownBody.length),
            validationMessage = null,
        )
        state = state.copy(editor = updated)
    }

    fun captureCurrentLocation(): Boolean {
        val editor = state.editor ?: return false
        return when (val result = locationCaptureAdapter.captureCurrentLocation()) {
            is LocationCaptureResult.Captured -> {
                val location = result.location
                state = state.copy(
                    editor = editor.copy(
                        latitudeText = location.latitude.toString(),
                        longitudeText = location.longitude.toString(),
                        accuracyMetersText = location.accuracyMeters?.toString().orEmpty(),
                        altitudeMetersText = location.altitudeMeters?.toString().orEmpty(),
                        capturedAtText = location.capturedAt.toString(),
                        validationMessage = null,
                    ),
                    feedbackMessage = strings.locationAdded,
                )
                true
            }

            is LocationCaptureResult.Denied -> {
                state = state.copy(
                    editor = editor.copy(validationMessage = null),
                    feedbackMessage = strings.locationPermissionDenied,
                )
                false
            }

            is LocationCaptureResult.Unavailable -> {
                state = state.copy(
                    editor = editor.copy(validationMessage = null),
                    feedbackMessage = strings.locationUnavailable,
                )
                false
            }
        }
    }

    fun updateMarkdownSelection(
        start: Int,
        end: Int,
    ) {
        val editor = state.editor ?: return
        val coercedStart = start.coerceIn(0, editor.markdownBody.length)
        val coercedEnd = end.coerceIn(0, editor.markdownBody.length)
        state = state.copy(
            editor = if (coercedStart <= coercedEnd) {
                editor.copy(
                    markdownSelectionStart = coercedStart,
                    markdownSelectionEnd = coercedEnd,
                )
            } else {
                editor.copy(
                    markdownSelectionStart = coercedEnd,
                    markdownSelectionEnd = coercedStart,
                )
            },
        )
    }

    fun toggleMarkdownPreview() {
        val editor = state.editor ?: return
        state = state.copy(
            editor = editor.copy(
                markdownPreviewVisible = !editor.markdownPreviewVisible,
                validationMessage = null,
            ),
        )
    }

    fun applyMarkdownToolbarAction(action: MarkdownToolbarAction) {
        val editor = state.editor ?: return
        val edit = applyMarkdownToolbarAction(
            source = editor.markdownBody,
            selectionStart = editor.markdownSelectionStart,
            selectionEnd = editor.markdownSelectionEnd,
            action = action,
        )
        state = state.copy(
            editor = editor.copy(
                markdownBody = edit.text,
                markdownSelectionStart = edit.selectionStart,
                markdownSelectionEnd = edit.selectionEnd,
                markdownPreviewVisible = false,
                validationMessage = null,
            ),
        )
    }

    suspend fun saveEditor(): Boolean =
        saveEditor(clearEditor = true)

    suspend fun saveEditorForRouteExit(): Boolean =
        saveEditor(clearEditor = false)

    private suspend fun saveEditor(clearEditor: Boolean): Boolean {
        val editor = state.editor ?: return false
        val input = validate(editor) ?: return false
        val selectedNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        val currentNotes = state.notes
        val currentSearchResults = state.searchResults
        val currentVersionHistory = state.versionHistory
        val currentConflictDetails = state.conflictDetails

        return runCatching {
            withContext(backgroundDispatcher) {
                val saved = if (editor.noteId == null) {
                    repository.createNote(input)
                } else {
                    repository.updateNote(editor.noteId, input)
                }
                SavedEditorData(
                    saved = saved,
                    listedNotes = if (selectedNotebookId == saved.notebookId) {
                        null
                    } else {
                        repository.listNotes(saved.notebookId)
                    },
                )
            }
        }.fold(
            onSuccess = { savedData ->
                val saved = savedData.saved
                val summary = saved.toListSummary()
                state = state.copy(
                    selectedNotebookId = saved.notebookId,
                    notes = savedData.listedNotes ?: currentNotes.upsertNote(summary),
                    searchResults = currentSearchResults.withSavedNote(searchQuery, saved),
                    editor = if (clearEditor) {
                        null
                    } else {
                        NoteEditorState.fromDetails(
                            details = saved,
                            markdownPreviewVisible = editor.markdownPreviewVisible,
                            sessionId = editor.sessionId,
                        )
                    },
                    versionHistory = if (clearEditor) {
                        null
                    } else {
                        currentVersionHistory?.takeIf { it.noteId == saved.id }
                    },
                    conflictDetails = if (clearEditor) {
                        null
                    } else {
                        currentConflictDetails
                    },
                    unsavedChangesDialogVisible = false,
                    feedbackMessage = formatUiString(strings.noteSaved, saved.title),
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                state = state.copy(
                    editor = editor.copy(validationMessage = formatUiString(strings.saveFailed, failure.message ?: strings.unknownError)),
                    unsavedChangesDialogVisible = false,
                )
                false
            },
        )
    }

    fun requestCloseEditor(): Boolean {
        val editor = state.editor ?: return true
        return if (editor.hasUnsavedChanges) {
            state = state.copy(unsavedChangesDialogVisible = true)
            false
        } else {
            state = state.copy(unsavedChangesDialogVisible = false)
            true
        }
    }

    fun keepEditing() {
        state = state.copy(unsavedChangesDialogVisible = false)
    }

    fun discardEditorChanges() {
        state = state.copy(
            editor = null,
            versionHistory = null,
            unsavedChangesDialogVisible = false,
            feedbackMessage = strings.discarded,
        )
    }

    fun confirmDiscardEditorForRouteExit() {
        state = state.copy(unsavedChangesDialogVisible = false)
    }

    fun currentEditorSessionId(): Long? = state.editor?.sessionId

    fun closeEditorSession(
        sessionId: Long?,
        discarded: Boolean = false,
    ) {
        if (sessionId == null) {
            return
        }
        val editor = state.editor ?: return
        if (editor.sessionId != sessionId) {
            return
        }
        state = state.copy(
            editor = null,
            versionHistory = null,
            unsavedChangesDialogVisible = false,
            feedbackMessage = if (discarded) {
                strings.discarded
            } else {
                state.feedbackMessage
            },
        )
    }

    suspend fun deleteNote(noteId: String): Boolean {
        val selectedNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        return runCatching {
            withContext(backgroundDispatcher) {
                val token = state.editor?.takeIf { it.noteId == noteId }?.causalToken
                    ?: repository.getNoteDetails(noteId)?.causalToken
                if (token != null) repository.deleteNote(noteId, token) else repository.deleteNote(noteId)
                loadRepositoryDataBlocking(
                    preferredNotebookId = selectedNotebookId,
                    searchQuery = searchQuery,
                )
            }
        }.fold(
            onSuccess = { repositoryData ->
                applyRepositoryData(repositoryData)
                state = state.copy(
                    editor = state.editor?.takeUnless { it.noteId == noteId },
                    versionHistory = state.versionHistory?.takeUnless { it.noteId == noteId },
                    conflictDetails = state.conflictDetails?.takeUnless { it.referencesNote(noteId) },
                    feedbackMessage = strings.noteDeleted,
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                state = state.copy(feedbackMessage = formatUiString(strings.cannotDeleteNote, failure.message ?: strings.unknownError))
                false
            },
        )
    }

    suspend fun deleteNoteForRouteExit(noteId: String): Boolean {
        val editorBeforeDelete = state.editor
        val selectedNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        return runCatching {
            withContext(backgroundDispatcher) {
                val token = editorBeforeDelete?.takeIf { it.noteId == noteId }?.causalToken
                    ?: repository.getNoteDetails(noteId)?.causalToken
                if (token != null) repository.deleteNote(noteId, token) else repository.deleteNote(noteId)
                loadRepositoryDataBlocking(
                    preferredNotebookId = selectedNotebookId,
                    searchQuery = searchQuery,
                )
            }
        }.fold(
            onSuccess = { repositoryData ->
                applyRepositoryData(repositoryData)
                state = state.copy(
                    editor = if (editorBeforeDelete?.noteId == noteId) {
                        editorBeforeDelete
                    } else {
                        state.editor?.takeUnless { it.noteId == noteId }
                    },
                    versionHistory = if (editorBeforeDelete?.noteId == noteId) {
                        state.versionHistory
                    } else {
                        state.versionHistory?.takeUnless { it.noteId == noteId }
                    },
                    conflictDetails = state.conflictDetails?.takeUnless { it.referencesNote(noteId) },
                    feedbackMessage = strings.noteDeleted,
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                state = state.copy(feedbackMessage = formatUiString(strings.cannotDeleteNote, failure.message ?: strings.unknownError))
                false
            },
        )
    }

    suspend fun showVersionHistory() {
        val editor = state.editor ?: return
        val noteId = editor.noteId
        if (noteId == null) {
            state = state.copy(feedbackMessage = strings.saveBeforeHistory)
            return
        }
        val versions = withContext(backgroundDispatcher) { repository.listNoteVersions(noteId) }
        state = state.copy(
            versionHistory = NoteVersionHistoryState(
                noteId = noteId,
                versions = versions,
                visible = true,
            ),
            feedbackMessage = null,
        )
    }

    fun hideVersionHistory() {
        val history = state.versionHistory ?: return
        state = state.copy(versionHistory = history.copy(visible = false))
    }


    private fun conflictActionLabel(action: ConflictResolutionAction): String =
        when (action) {
            ConflictResolutionAction.MergeIntoOriginal -> strings.conflictActionMerge
            ConflictResolutionAction.KeepConflictCopy -> strings.conflictActionKeepCopy
            ConflictResolutionAction.RestoreOriginalFromConflict -> strings.conflictActionRestoreOriginal
            ConflictResolutionAction.DeleteConflictCopy -> strings.conflictActionDeleteCopy
        }

    suspend fun resolveConflict(action: ConflictResolutionAction): Boolean {
        return resolveConflictWithFeedback(formatUiString(strings.conflictResolvedAction, conflictActionLabel(action))) { conflictNoteId ->
            repository.resolveConflict(conflictNoteId, action)
        }
    }

    suspend fun resolveConflictBranch(versionId: String): Boolean {
        val expectedHeads = state.conflictDetails?.expectedHeadVersionIds.orEmpty()
        return resolveConflictWithFeedback(strings.conflictResolvedBranch) { conflictNoteId ->
            repository.resolveConflictBranch(conflictNoteId, versionId, expectedHeads)
        }
    }

    private suspend fun resolveConflictWithFeedback(
        successMessage: String,
        resolver: (String) -> NoteDetails?,
    ): Boolean {
        val conflictNoteId = state.conflictDetails?.conflictNoteId ?: state.editor?.noteId ?: return false
        val selectedNotebookId = state.selectedNotebookId
        val searchQuery = state.searchQuery
        return runCatching {
            withContext(backgroundDispatcher) {
                val resolved = resolver(conflictNoteId)
                ResolvedConflictData(
                    resolved = resolved,
                    repositoryData = loadRepositoryDataBlocking(
                        preferredNotebookId = resolved?.notebookId ?: selectedNotebookId,
                        searchQuery = searchQuery,
                    ),
                    versions = resolved?.let { repository.listNoteVersions(it.id) }.orEmpty(),
                )
            }
        }.fold(
            onSuccess = { resolvedData ->
                applyRepositoryData(resolvedData.repositoryData)
                val resolved = resolvedData.resolved
                state = state.copy(
                    editor = resolved?.let { details ->
                        NoteEditorState.fromDetails(
                            details = details,
                            markdownPreviewVisible = editorPreferences.previewByDefault,
                            sessionId = state.editor?.sessionId ?: nextEditorSessionId(),
                        )
                    },
                    versionHistory = resolved?.let { details ->
                        NoteVersionHistoryState(
                            noteId = details.id,
                            versions = resolvedData.versions,
                            visible = false,
                        )
                    },
                    conflictDetails = null,
                    unsavedChangesDialogVisible = false,
                    feedbackMessage = successMessage,
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                state = state.copy(feedbackMessage = formatUiString(strings.cannotResolveConflict, failure.message ?: strings.unknownError))
                false
            },
        )
    }

    suspend fun restoreVersion(versionId: String): Boolean {
        val editor = state.editor ?: return false
        val noteId = editor.noteId ?: run {
            state = state.copy(feedbackMessage = strings.saveBeforeRestore)
            return false
        }
        if (editor.hasUnsavedChanges) {
            state = state.copy(
                unsavedChangesDialogVisible = true,
                feedbackMessage = strings.resolveBeforeRestore,
            )
            return false
        }
        val searchQuery = state.searchQuery

        return runCatching {
            withContext(backgroundDispatcher) {
                val restored = editor.causalToken?.let { token ->
                    repository.restoreNoteVersion(noteId, versionId, token)
                } ?: repository.restoreNoteVersion(noteId, versionId)
                RestoredVersionData(
                    restored = restored,
                    repositoryData = loadRepositoryDataBlocking(
                        preferredNotebookId = restored.notebookId,
                        searchQuery = searchQuery,
                    ),
                    versions = repository.listNoteVersions(restored.id),
                )
            }
        }.fold(
            onSuccess = { restoredData ->
                applyRepositoryData(restoredData.repositoryData)
                val restored = restoredData.restored
                state = state.copy(
                    editor = NoteEditorState.fromDetails(
                        details = restored,
                        markdownPreviewVisible = editorPreferences.previewByDefault,
                        sessionId = editor.sessionId,
                    ),
                    versionHistory = NoteVersionHistoryState(
                        noteId = restored.id,
                        versions = restoredData.versions,
                        visible = true,
                    ),
                    conflictDetails = null,
                    unsavedChangesDialogVisible = false,
                    feedbackMessage = strings.restoredVersion,
                    localChangeEventId = state.localChangeEventId + 1,
                )
                true
            },
            onFailure = { failure ->
                state = state.copy(feedbackMessage = formatUiString(strings.cannotRestoreVersion, failure.message ?: strings.unknownError))
                false
            },
        )
    }

    suspend fun createMockContent(): MockContentResult =
        runCatching {
            val searchQuery = state.searchQuery
            withContext(backgroundDispatcher) {
                clearMockContentBlocking()
                val today = currentDateProvider()
                var noteCount = 0
                val createdNotebooks = mockNotebookTitles.map { repository.createNotebook(it) }
                createdNotebooks.zip(demoNotebookSpecs).forEach { (notebook, spec) ->
                    demoNotes(today, notebook.id, spec).forEach { input ->
                        repository.createNote(input)
                        noteCount++
                    }
                }
                MockContentOperation(
                    result = MockContentResult(
                        createdNotebooks = createdNotebooks.size,
                        createdNotes = noteCount,
                    ),
                    repositoryData = loadRepositoryDataBlocking(
                        preferredNotebookId = createdNotebooks.firstOrNull()?.id,
                        searchQuery = searchQuery,
                    ),
                )
            }
        }.fold(
            onSuccess = { operation ->
                applyRepositoryData(operation.repositoryData)
                state = state.copy(
                    editor = null,
                    versionHistory = null,
                    conflictDetails = null,
                    feedbackMessage = formatUiString(strings.demoCreated, operation.result.createdNotebooks, operation.result.createdNotes),
                    localChangeEventId = state.localChangeEventId + 1,
                )
                operation.result
            },
            onFailure = { failure ->
                refresh()
                state = state.copy(feedbackMessage = formatUiString(strings.cannotCreateDemo, failure.message ?: strings.unknownError))
                MockContentResult(errorMessage = failure.message ?: "unknown error")
            },
        )

    suspend fun clearMockContent(): MockContentResult =
        runCatching {
            val selectedNotebookId = state.selectedNotebookId
            val searchQuery = state.searchQuery
            withContext(backgroundDispatcher) {
                val result = clearMockContentBlocking()
                MockContentOperation(
                    result = result,
                    repositoryData = loadRepositoryDataBlocking(
                        preferredNotebookId = selectedNotebookId,
                        searchQuery = searchQuery,
                    ),
                )
            }
        }.fold(
            onSuccess = { operation ->
                applyRepositoryData(operation.repositoryData)
                state = state.copy(
                    editor = null,
                    versionHistory = null,
                    conflictDetails = null,
                    feedbackMessage = formatUiString(strings.demoCleared, operation.result.deletedNotes, operation.result.deletedNotebooks),
                    localChangeEventId = if (operation.result.deletedNotes > 0 || operation.result.deletedNotebooks > 0) {
                        state.localChangeEventId + 1
                    } else {
                        state.localChangeEventId
                    },
                )
                operation.result
            },
            onFailure = { failure ->
                refresh()
                state = state.copy(feedbackMessage = formatUiString(strings.cannotClearDemo, failure.message ?: strings.unknownError))
                MockContentResult(errorMessage = failure.message ?: "unknown error")
            },
        )

    private fun loadRepositoryDataBlocking(
        preferredNotebookId: String?,
        searchQuery: String,
    ): NotesRepositoryData {
        val notebooks = repository.listNotebooks()
        val selectedNotebookId = preferredNotebookId
            ?.takeIf { candidate -> notebooks.any { it.id == candidate } }
            ?: notebooks.firstOrNull()?.id
        val notes = selectedNotebookId?.let(repository::listNotes).orEmpty()
        val searchResults = searchQuery
            .takeIf { it.isNotBlank() }
            ?.let(repository::searchNotes)
            .orEmpty()
        return NotesRepositoryData(
            notebooks = notebooks,
            notebookConflicts = notebooks.mapNotNull { notebook ->
                repository.getNotebookConflictDetails(notebook.id)?.let { notebook.id to it }
            }.toMap(),
            selectedNotebookId = selectedNotebookId,
            notes = notes,
            searchQuery = searchQuery,
            searchResults = searchResults,
            deletedWorkspaceItems = repository.listDeletedWorkspaceItems(),
        )
    }

    private suspend fun loadRepositoryData(
        preferredNotebookId: String?,
        searchQuery: String,
    ): NotesRepositoryData =
        withContext(backgroundDispatcher) {
            loadRepositoryDataBlocking(preferredNotebookId, searchQuery)
        }

    private fun applyRepositoryData(data: NotesRepositoryData) {
        state = state.withRepositoryData(data)
    }

    private fun conflictDetailsForNote(details: NoteDetails): ConflictDetails? {
        val conflictCopyDetails = if (details.syncBadge is NoteSyncBadge.Conflict) {
            repository.getConflictDetails(details.id)
        } else {
            null
        }
        return conflictCopyDetails ?: repository.getConflictDetailsForOriginal(details.id)
    }

    private fun NotesUiState.withRepositoryData(data: NotesRepositoryData): NotesUiState =
        copy(
            notebooks = data.notebooks,
            notebookConflicts = data.notebookConflicts,
            deletedWorkspaceItems = data.deletedWorkspaceItems,
            selectedNotebookId = data.selectedNotebookId,
            notes = data.notes,
            searchResults = if (searchQuery == data.searchQuery) data.searchResults else searchResults,
        ).let { updated ->
            val visibleIds = updated.visibleNotes.mapTo(mutableSetOf()) { it.id }
            val retainedSelection = updated.selectedNoteIds.intersect(visibleIds)
            updated.copy(
                selectedNoteIds = retainedSelection,
                noteSelectionAnchorId = updated.noteSelectionAnchorId?.takeIf { it in retainedSelection },
            )
        }

    private fun clearMockContentBlocking(): MockContentResult {
        var deletedNotes = 0
        val notebooks = repository.listNotebooks()
        notebooks.forEach { notebook ->
            repository.listNotes(notebook.id).forEach { summary ->
                val details = repository.getNoteDetails(summary.id)
                if (details?.markdownBody?.hasGeneratedContentMarker() == true) {
                    details.causalToken?.let { repository.deleteNote(summary.id, it) }
                        ?: repository.deleteNote(summary.id)
                    deletedNotes++
                }
            }
        }

        var deletedNotebooks = 0
        repository.listNotebooks()
            .filter { it.title.isGeneratedNotebookTitle() && repository.listNotes(it.id).isEmpty() }
            .forEach { notebook ->
                runCatching {
                    notebook.causalToken?.let { repository.deleteNotebook(notebook.id, it) }
                        ?: repository.deleteNotebook(notebook.id)
                    deletedNotebooks++
                }
            }

        return MockContentResult(deletedNotebooks = deletedNotebooks, deletedNotes = deletedNotes)
    }

    private data class NotesRepositoryData(
        val notebooks: List<NotebookSummary>,
        val notebookConflicts: Map<String, NotebookConflictDetails>,
        val selectedNotebookId: String?,
        val notes: List<NoteSummary>,
        val searchQuery: String,
        val searchResults: List<NoteSummary>,
        val deletedWorkspaceItems: List<DeletedWorkspaceItem>,
    )

    private data class BatchUpdateResult(
        val repositoryData: NotesRepositoryData,
        val updated: Map<String, NoteDetails>,
    )

    private data class BatchDeleteResult(
        val repositoryData: NotesRepositoryData,
        val undoItems: List<DeletedWorkspaceItem>,
    )

    private enum class BatchUpdateKind {
        Moved,
        Updated,
    }

    private data class SyncRefreshData(
        val repositoryData: NotesRepositoryData,
        val editorRefresh: EditorRefreshResult?,
        val conflictDetails: ConflictDetails?,
    )

    private sealed interface EditorRefreshResult {
        data class Found(
            val details: NoteDetails,
            val versions: List<NoteVersionSummary>,
            val conflictDetails: ConflictDetails?,
        ) : EditorRefreshResult

        data class Missing(
            val noteId: String,
        ) : EditorRefreshResult
    }

    private sealed interface ExistingNoteLoadResult {
        data class Found(
            val details: NoteDetails,
            val notes: List<NoteSummary>,
            val versions: List<NoteVersionSummary>,
            val conflictDetails: ConflictDetails?,
        ) : ExistingNoteLoadResult

        data class Missing(
            val repositoryData: NotesRepositoryData,
        ) : ExistingNoteLoadResult
    }

    private sealed interface ConflictResolutionLoadResult {
        data class Found(
            val details: ConflictDetails,
            val repositoryData: NotesRepositoryData,
        ) : ConflictResolutionLoadResult

        data class Missing(
            val repositoryData: NotesRepositoryData,
        ) : ConflictResolutionLoadResult
    }

    private data class SavedEditorData(
        val saved: NoteDetails,
        val listedNotes: List<NoteSummary>?,
    )

    private data class ResolvedConflictData(
        val resolved: NoteDetails?,
        val repositoryData: NotesRepositoryData,
        val versions: List<NoteVersionSummary>,
    )

    private data class RestoredVersionData(
        val restored: NoteDetails,
        val repositoryData: NotesRepositoryData,
        val versions: List<NoteVersionSummary>,
    )

    private data class MockContentOperation(
        val result: MockContentResult,
        val repositoryData: NotesRepositoryData,
    )

    private fun validate(editor: NoteEditorState): NoteInput? {
        fun invalid(message: String): NoteInput? {
            state = state.copy(editor = editor.copy(validationMessage = message))
            return null
        }

        if (editor.title.isBlank()) {
            return invalid(strings.titleRequired)
        }
        val notebookId = editor.notebookId.takeIf { candidate ->
            state.notebooks.any { it.id == candidate }
        } ?: return invalid(strings.chooseNotebook)
        val createdDate = try {
            LocalDate.parse(editor.createdDateText)
        } catch (_: IllegalArgumentException) {
            return invalid(strings.dateFormat)
        }
        val latitude = if (editor.latitudeText.isBlank()) {
            null
        } else {
            editor.latitudeText.toDoubleOrNull()
                ?: return invalid(strings.latNumber)
        }
        val longitude = if (editor.longitudeText.isBlank()) {
            null
        } else {
            editor.longitudeText.toDoubleOrNull()
                ?: return invalid(strings.lonNumber)
        }
        if ((latitude == null) != (longitude == null)) {
            return invalid(strings.latLonTogether)
        }
        if (latitude != null && latitude !in -90.0..90.0) {
            return invalid(strings.latRange)
        }
        if (longitude != null && longitude !in -180.0..180.0) {
            return invalid(strings.lonRange)
        }
        val accuracyMeters = if (editor.accuracyMetersText.isBlank()) {
            null
        } else {
            editor.accuracyMetersText.toDoubleOrNull()
                ?: return invalid(strings.accuracyNumber)
        }
        val altitudeMeters = if (editor.altitudeMetersText.isBlank()) {
            null
        } else {
            editor.altitudeMetersText.toDoubleOrNull()
                ?: return invalid(strings.altitudeNumber)
        }
        val capturedAt = if (editor.capturedAtText.isBlank()) {
            null
        } else {
            try {
                Instant.parse(editor.capturedAtText)
            } catch (_: IllegalArgumentException) {
                return invalid(strings.capturedIso)
            }
        }
        if (latitude == null && (accuracyMeters != null || altitudeMeters != null || capturedAt != null)) {
            return invalid(strings.metaRequiresCoords)
        }

        val location = NotesLocationInput(
            latitude = latitude,
            longitude = longitude,
            placeText = editor.placeText.trim().takeIf { it.isNotBlank() },
            accuracyMeters = accuracyMeters,
            altitudeMeters = altitudeMeters,
            capturedAt = capturedAt,
        ).takeIf { it.hasValue }

        return NoteInput(
            notebookId = notebookId,
            title = editor.title.trim(),
            markdownBody = editor.markdownBody,
            createdAt = editor.resolveCreatedAt(createdDate, currentDateProvider()),
            location = location,
            timeZoneId = editor.timeZoneId,
            causalToken = editor.causalToken,
        )
    }

    private fun closeEditor() {
        state = state.copy(editor = null, versionHistory = null, conflictDetails = null, unsavedChangesDialogVisible = false)
    }

    private companion object {
        const val demoNotesPerNotebook = 100
        const val demoLeadNotesPerNotebook = 10
        const val mockContentMarker = "\u200B\u200C\u200D\u2060\u2063\u2062\u2060\u200D\u200C\u200B"

        val mockContentMarkers = listOf(mockContentMarker)

        data class DemoNotebookSpec(
            val title: String,
            val subject: String,
            val leadTitles: List<String>,
            val leadDetails: List<String>,
            val checklist: List<String>,
            val fillerTopics: List<String>,
            val places: List<String> = emptyList(),
        )

        fun generatedNotebookTitle(title: String): String =
            "$title$mockContentMarker"

        val demoNotebookSpecs = listOf(
            DemoNotebookSpec(
                title = "Journal",
                subject = "daily life",
                leadTitles = listOf(
                    "Morning walk and a quieter inbox",
                    "Dinner notes: small table, good light",
                    "Bookshelf cleanup",
                    "Rain before the commute",
                    "Sunday reset list",
                    "Call with Mom",
                    "Coffee shop table by the window",
                    "Evening without notifications",
                    "First swim after a long break",
                    "Things that made today lighter",
                ),
                leadDetails = listOf(
                    "The air was still cool after last night's rain, so I took the long way past the bakery before opening my laptop.",
                    "We sat by the window again. The room was loud, but the light was soft enough for reading between courses.",
                    "The lower shelf had three notebooks from 2022, mostly grocery lists and half-written routes that still felt useful.",
                    "I left early and still arrived damp. The extra ten minutes made the train platform feel less rushed.",
                    "Laundry, calendar, and groceries all fit into the morning once I stopped trying to do them in parallel.",
                    "She sounded brighter than last week and wanted to know whether the new desk was comfortable.",
                    "The corner table had a loose socket, but it was quiet enough to finish the outline without headphones.",
                    "Putting the phone in the kitchen changed the whole pace of the room after dinner.",
                    "The pool was busier than expected, but ten slow laps were enough to feel like I had restarted.",
                    "A short list, but it worked: fresh sheets, a clean sink, and twenty minutes with a book.",
                ),
                checklist = listOf(
                    "Keep tomorrow morning free from meetings until 10:30.",
                    "Buy coffee beans before Friday.",
                    "Move the blue notebook to the top shelf.",
                    "Reply to the long email after lunch.",
                    "Take the small umbrella instead of the heavy one.",
                ),
                fillerTopics = listOf("Morning", "Errands", "Home", "Weekend", "Calls", "Reading"),
                places = listOf(
                    "Fuxing Park, Shanghai",
                    "Xintiandi, Shanghai",
                    "Home office",
                    "People's Square Station",
                    "Jing'an, Shanghai",
                    "Phone call",
                    "Anfu Road, Shanghai",
                    "Kitchen table",
                    "Local pool",
                    "Living room",
                ),
            ),
            DemoNotebookSpec(
                title = "Work",
                subject = "product work",
                leadTitles = listOf(
                    "Design review: adaptive shell",
                    "Sync QA checklist",
                    "Command palette ideas",
                    "Release notes for Friday",
                    "Customer interview synthesis",
                    "Metrics review with support",
                    "Onboarding copy pass",
                    "WebDAV restore edge cases",
                    "Sprint planning constraints",
                    "Desktop sidebar polish",
                ),
                leadDetails = listOf(
                    "Desktop should feel like a workspace, not a stretched phone screen; the sidebar carries navigation better than a bottom bar.",
                    "The manual pass needs one clean restore into an empty profile before we call the backup flow stable.",
                    "Search should include notebooks, notes, and settings pages, but the first version can stay narrow.",
                    "The changelog needs to explain the layout changes without turning into a design essay.",
                    "Three interviews pointed at the same problem: people trust local notes only when recovery feels obvious.",
                    "Support tickets clustered around sync setup, not editing; the settings screen should make that visible.",
                    "Shorter headings worked better, especially on mobile where every extra line pushed the form down.",
                    "Restore needs to handle missing folders, stale credentials, and a user switching devices mid-flow.",
                    "The release window is tight because QA also needs Android install time and WebDAV account setup.",
                    "The drag target feels right at 8 dp, but the divider needs to stay quiet until hover or drag.",
                ),
                checklist = listOf(
                    "Capture one desktop screenshot at 1220 x 820.",
                    "Run the smoke task before packaging.",
                    "Check Android debug after shared UI edits.",
                    "Keep token and recovery material out of logs.",
                    "Update the release note after visual QA.",
                ),
                fillerTopics = listOf("Review", "QA", "Sync", "Editor", "Settings", "Release"),
            ),
            DemoNotebookSpec(
                title = "Travel",
                subject = "travel planning",
                leadTitles = listOf(
                    "Kyoto day plan",
                    "Packing list for a light week",
                    "Train window notes",
                    "Taipei neighborhood shortlist",
                    "Hotel check-in details",
                    "Lisbon tram route",
                    "Things to buy before departure",
                    "Museum afternoon plan",
                    "Long flight recovery plan",
                    "Receipts to reconcile",
                ),
                leadDetails = listOf(
                    "Start early, avoid moving hotels mid-day, and leave room for the stationery shop after the temple visit.",
                    "Carry-on only still works if the charger pouch and linen shirts share the same packing cube.",
                    "The fields outside the window changed color every few minutes; writing without checking the map helped.",
                    "Da'an looks best for walking, Songshan for late food, and Zhongshan for a quiet first morning.",
                    "The confirmation email says room access starts at 15:00, but luggage storage is available after 10:00.",
                    "The route is slower than the metro, but it keeps the river and the tiled streets in view.",
                    "Refillable bottle, small pouch for receipts, and a spare cable for the camera battery.",
                    "Book the early slot, then keep the cafe afterward unplanned so the day does not feel packed.",
                    "Hydrate before landing, skip the heavy meal, and walk outside for at least twenty minutes.",
                    "Separate meals, transit, books, and gifts before they disappear into one card statement.",
                ),
                checklist = listOf(
                    "Send the passport photo to the shared folder.",
                    "Leave room for books on the return trip.",
                    "Pack the rain shell in the outer pocket.",
                    "Check the train platform before breakfast.",
                    "Keep one printed reservation in the notebook.",
                ),
                fillerTopics = listOf("Flights", "Hotels", "Transit", "Packing", "Food", "Receipts"),
                places = listOf(
                    "Kyoto, Japan",
                    "Bedroom floor",
                    "Tokaido Shinkansen",
                    "Taipei, Taiwan",
                    "Hotel desk",
                    "Lisbon, Portugal",
                    "Packing list",
                    "Museum cafe",
                    "Airport arrival hall",
                    "Expense folder",
                ),
            ),
            DemoNotebookSpec(
                title = "Reading",
                subject = "books and articles",
                leadTitles = listOf(
                    "Notes from The Creative Act",
                    "Local-first software essay",
                    "Chapter six margin notes",
                    "Bookstore stack from Saturday",
                    "Quotes to revisit",
                    "Long article on attention",
                    "Reading queue cleanup",
                    "Library renewal reminder",
                    "Paperback for the train",
                    "What to recommend next",
                ),
                leadDetails = listOf(
                    "The strongest part was the reminder that taste improves through repeated noticing, not through one big plan.",
                    "The essay made offline-first feel less like an architecture choice and more like a trust promise.",
                    "The argument finally clicked once the author moved from theory into the small case study.",
                    "Three books came home, but only one should stay on the desk this week.",
                    "Most saved quotes are too polished; the useful ones still feel slightly unfinished.",
                    "The best line was about protecting attention by reducing decisions, not by adding discipline.",
                    "The queue had become a guilt list, so I moved anything older than six months into archive.",
                    "Two books can renew online, one needs to go back because someone else has it on hold.",
                    "Short chapters matter more than page count when the ride is only thirty minutes.",
                    "Suggest the essay first, then the book, because the essay gives faster context.",
                ),
                checklist = listOf(
                    "Copy the best quote into the commonplace section.",
                    "Return the library book by Thursday.",
                    "Keep only one book on the nightstand.",
                    "Send the article to the product channel.",
                    "Add page numbers before closing the note.",
                ),
                fillerTopics = listOf("Books", "Articles", "Quotes", "Library", "Essays", "Notes"),
            ),
            DemoNotebookSpec(
                title = "Home",
                subject = "home maintenance",
                leadTitles = listOf(
                    "Entryway reset",
                    "Plants that need attention",
                    "Desk cable cleanup",
                    "Kitchen drawer inventory",
                    "Weekend repair list",
                    "Bedroom lighting notes",
                    "Laundry rhythm",
                    "Winter storage box",
                    "Window seal check",
                    "Small things to donate",
                ),
                leadDetails = listOf(
                    "The entryway works better with one tray for keys and one hook kept empty for guests.",
                    "The basil is done, the snake plant is fine, and the fern needs a better watering rhythm.",
                    "The extra HDMI cable was the real problem; everything else fit once it was removed.",
                    "Three duplicate peelers, no measuring spoons, and too many loose rubber bands.",
                    "The loose chair screw and bathroom shelf bracket both need the same screwdriver.",
                    "Warm bulbs make the room calmer, but the desk still needs a cooler task light.",
                    "Doing towels midweek keeps Sunday from turning into a laundry day.",
                    "Scarves and gloves fit into the clear box if the old tote bags move out.",
                    "The living room window has the draft; the bedroom is fine after last year's repair.",
                    "Two mugs, one lamp, and the spare keyboard can leave this week.",
                ),
                checklist = listOf(
                    "Buy felt pads for the chair legs.",
                    "Label the storage box before moving it.",
                    "Water plants on Wednesday and Sunday.",
                    "Donate the extra mugs this weekend.",
                    "Measure the shelf before ordering brackets.",
                ),
                fillerTopics = listOf("Cleaning", "Repairs", "Storage", "Plants", "Lighting", "Kitchen"),
                places = listOf(
                    "Entryway",
                    "Balcony",
                    "Desk",
                    "Kitchen",
                    "Hallway",
                    "Bedroom",
                    "Laundry room",
                    "Storage closet",
                    "Living room",
                    "Donation bag",
                ),
            ),
            DemoNotebookSpec(
                title = "Health",
                subject = "health routines",
                leadTitles = listOf(
                    "Sleep log after the late meeting",
                    "First run in cooler weather",
                    "Stretching routine notes",
                    "Grocery choices for the week",
                    "Hydration reminder",
                    "Doctor appointment questions",
                    "Lunch that did not crash energy",
                    "Evening walk route",
                    "Screen break experiment",
                    "Weekend recovery plan",
                ),
                leadDetails = listOf(
                    "The late meeting pushed bedtime back, but reading on paper helped avoid another hour on the phone.",
                    "Cooler air made the first kilometer easier; the hill still needs a slower pace.",
                    "Hips and shoulders took most of the time, which probably says enough about the desk setup.",
                    "The week looks better with soup, eggs, greens, and two easy snacks already planned.",
                    "The bottle on the desk works only if it is filled before the first call.",
                    "Ask about sleep quality, vitamin D, and whether the afternoon headaches are posture-related.",
                    "Rice, fish, and greens held up better than the sandwich-heavy default.",
                    "The river route is calmer, but the park route has better light after 18:30.",
                    "A five-minute break every hour felt annoying until the third hour, then it helped.",
                    "No intense workout needed; just sleep, walking, and a proper grocery run.",
                ),
                checklist = listOf(
                    "Put the water bottle on the desk before standup.",
                    "Schedule the appointment before Friday.",
                    "Keep dinner simple after running.",
                    "Do the shoulder stretch after lunch.",
                    "Stop screens thirty minutes before bed.",
                ),
                fillerTopics = listOf("Sleep", "Runs", "Meals", "Appointments", "Walks", "Breaks"),
                places = listOf(
                    "Bedroom",
                    "Riverside path",
                    "Living room mat",
                    "Grocery store",
                    "Desk",
                    "Clinic",
                    "Lunch spot",
                    "Park loop",
                    "Office",
                    "Weekend plan",
                ),
            ),
            DemoNotebookSpec(
                title = "Finance",
                subject = "personal finance",
                leadTitles = listOf(
                    "May budget review",
                    "Subscription audit",
                    "Travel card charges",
                    "Quarterly tax folder",
                    "Emergency fund check",
                    "Grocery spend pattern",
                    "Invoice follow-up",
                    "Insurance renewal notes",
                    "Gift budget for June",
                    "Receipts from the weekend",
                ),
                leadDetails = listOf(
                    "Dining out was higher than expected, but transit and subscriptions were lower after the cleanup.",
                    "Three renewals were still active from trial periods; only one is worth keeping.",
                    "The hotel deposit and train tickets posted on different days, so reconcile by reservation number.",
                    "Receipts, bank statements, and freelance invoices should all live in the same folder before July.",
                    "The target is still fine, but the transfer should happen right after salary instead of month-end.",
                    "Weekend groceries are cheaper when the list is written after checking the freezer.",
                    "Send one polite reminder with the original scope attached and the payment terms in the first line.",
                    "Coverage looks unchanged, but the premium increased enough to compare two alternatives.",
                    "Put birthdays, wedding gift, and Father's Day into one number instead of treating them as surprises.",
                    "Separate meals from household items before entering totals into the monthly note.",
                ),
                checklist = listOf(
                    "Cancel the unused subscription before renewal.",
                    "Move receipts into the tax folder.",
                    "Reconcile travel charges by Sunday.",
                    "Schedule the savings transfer.",
                    "Compare renewal quotes before accepting.",
                ),
                fillerTopics = listOf("Budget", "Receipts", "Subscriptions", "Invoices", "Taxes", "Savings"),
            ),
            DemoNotebookSpec(
                title = "Recipes",
                subject = "cooking",
                leadTitles = listOf(
                    "Ginger scallion noodles",
                    "Tomato egg rice notes",
                    "Sunday soup base",
                    "Pantry dinner formula",
                    "Breakfast oats that held up",
                    "Tea eggs timing",
                    "Roasted vegetables for lunch",
                    "Mapo tofu adjustment",
                    "Dinner for four plan",
                    "Freezer inventory",
                ),
                leadDetails = listOf(
                    "The sauce was better after warming the oil first and adding the scallions off heat.",
                    "More tomato, less sugar, and a splash of water made it softer without becoming soup.",
                    "Chicken bones, ginger, and one dried mushroom were enough for a clean base.",
                    "Grain, greens, egg, and one strong sauce solved dinner without another grocery run.",
                    "The oats stayed better with chia added at night and nuts kept separate until morning.",
                    "Eight minutes simmering, then a long soak, gave the yolks the texture I wanted.",
                    "Carrots need a head start; zucchini should go in later or it disappears.",
                    "Less chili oil for a weeknight version, but keep the peppercorns bright.",
                    "Two mains, one green side, rice, and fruit is enough; do not add a complicated dessert.",
                    "There are dumplings, stock, and two portions of soup, but no frozen vegetables left.",
                ),
                checklist = listOf(
                    "Buy scallions and ginger before noodles.",
                    "Label freezer containers with dates.",
                    "Keep breakfast toppings separate.",
                    "Start rice before chopping vegetables.",
                    "Use the small pan for tea eggs.",
                ),
                fillerTopics = listOf("Dinner", "Breakfast", "Pantry", "Soup", "Freezer", "Prep"),
                places = listOf(
                    "Kitchen",
                    "Kitchen",
                    "Stove",
                    "Pantry",
                    "Breakfast shelf",
                    "Small pot",
                    "Sheet pan",
                    "Wok",
                    "Dining table",
                    "Freezer drawer",
                ),
            ),
            DemoNotebookSpec(
                title = "Ideas",
                subject = "ideas and drafts",
                leadTitles = listOf(
                    "Local notes as a memory layer",
                    "Tiny command palette",
                    "Calendar view improvements",
                    "A better empty state",
                    "Portable writing desk",
                    "Mapless location capture",
                    "Search result grouping",
                    "One-button export story",
                    "Soft delete explanation",
                    "Daily review ritual",
                ),
                leadDetails = listOf(
                    "The app should feel less like storage and more like a layer that remembers context without demanding tags.",
                    "A palette can start with only three actions if it is fast and predictable.",
                    "The month grid should help people find memory clusters, not become another task calendar.",
                    "Empty states should invite the next action without explaining the entire product.",
                    "A small board, cable pouch, and notebook could make hotel writing less awkward.",
                    "Text place names are still useful even without a map SDK, especially for memory and search.",
                    "Group by notebook first, then date, so the result list feels less like a flat dump.",
                    "Export should make people feel ownership immediately, even if sync is never configured.",
                    "Deletion copy should be honest: hidden from active lists, preserved enough for sync safety.",
                    "Ten minutes at the end of the day might be enough if the questions stay the same.",
                ),
                checklist = listOf(
                    "Sketch the first-state screen before adding copy.",
                    "Test the idea on desktop and phone widths.",
                    "Write the export story in one paragraph.",
                    "Keep the first version intentionally small.",
                    "Turn the daily review into three prompts.",
                ),
                fillerTopics = listOf("Product", "Writing", "Search", "Memory", "Export", "Rituals"),
            ),
            DemoNotebookSpec(
                title = "People",
                subject = "relationships",
                leadTitles = listOf(
                    "Birthday gift shortlist",
                    "Dinner with Alex",
                    "Questions for the mentor call",
                    "Team offsite preferences",
                    "Follow-up after the workshop",
                    "Dad's camera notes",
                    "Neighbor package pickup",
                    "Book recommendation for Li",
                    "Thank-you note draft",
                    "Names from the meetup",
                ),
                leadDetails = listOf(
                    "Keep it practical this year: coffee beans, a small print, or the notebook she liked last time.",
                    "The conversation was better once work was off the table and we talked about the bike trip instead.",
                    "Ask about prioritization, hiring too early, and how to protect design quality during release pressure.",
                    "Most people preferred a half-day format with food nearby and no forced evening activity.",
                    "Send slides, but also send the checklist because that is what people asked about afterward.",
                    "He wants something light, with a real viewfinder, and no complicated lens decisions.",
                    "The package is under the bench by the mail room; send a message before 20:00.",
                    "Start with the essay, not the full book, because the idea lands faster that way.",
                    "Mention the specific line that helped; otherwise it will sound too generic.",
                    "Add company names immediately because faces alone will not be enough by Monday.",
                ),
                checklist = listOf(
                    "Send the follow-up within twenty-four hours.",
                    "Add the birthday reminder to the calendar.",
                    "Bring the camera list to the weekend call.",
                    "Share the book link after dinner.",
                    "Write names down before leaving the venue.",
                ),
                fillerTopics = listOf("Calls", "Gifts", "Follow-ups", "Meetups", "Family", "Team"),
                places = listOf(
                    "Gift list",
                    "Dinner table",
                    "Mentor call",
                    "Offsite notes",
                    "Workshop room",
                    "Weekend call",
                    "Mail room",
                    "Reading list",
                    "Draft",
                    "Meetup venue",
                ),
            ),
        )

        val mockNotebookTitles = demoNotebookSpecs.map { spec -> generatedNotebookTitle(spec.title) }

        fun String.hasGeneratedContentMarker(): Boolean =
            mockContentMarkers.any { marker -> contains(marker) }

        fun String.isGeneratedNotebookTitle(): Boolean =
            hasGeneratedContentMarker()

        fun demoNotes(
            today: LocalDate,
            notebookId: String,
            spec: DemoNotebookSpec,
        ): List<NoteInput> {
            require(spec.leadTitles.size >= demoLeadNotesPerNotebook) {
                "Demo notebook ${spec.title} needs at least $demoLeadNotesPerNotebook lead notes."
            }
            val leadNotes = spec.leadTitles.take(demoLeadNotesPerNotebook).mapIndexed { index, title ->
                NoteInput(
                    notebookId = notebookId,
                    title = title,
                    markdownBody = mockBody(leadBody(spec, title, index)),
                    createdAt = today.minus(DatePeriod(days = index)).toInstantAtStartOfDay(null),
                    location = locationFor(spec, index),
                )
            }
            val fillerNotes = (0 until demoNotesPerNotebook - demoLeadNotesPerNotebook).map { index ->
                val topic = spec.fillerTopics[index % spec.fillerTopics.size]
                val title = "$topic note ${index + 1}"
                NoteInput(
                    notebookId = notebookId,
                    title = title,
                    markdownBody = mockBody(fillerBody(spec, title, index)),
                    createdAt = today.minus(DatePeriod(days = demoLeadNotesPerNotebook + index)).toInstantAtStartOfDay(null),
                    location = locationFor(spec, index),
                )
            }
            return fillerNotes + leadNotes.asReversed()
        }

        fun locationFor(
            spec: DemoNotebookSpec,
            index: Int,
        ): NotesLocationInput? =
            spec.places.takeIf { places -> places.isNotEmpty() }
                ?.let { places -> NotesLocationInput(placeText = places[index % places.size]) }

        fun leadBody(
            spec: DemoNotebookSpec,
            title: String,
            index: Int,
        ): String {
            val detail = spec.leadDetails[index % spec.leadDetails.size]
            val firstAction = spec.checklist[index % spec.checklist.size]
            val secondAction = spec.checklist[(index + 2) % spec.checklist.size]
            val thirdAction = spec.checklist[(index + 4) % spec.checklist.size]
            return """
                $detail

                Notes:

                - $firstAction
                - $secondAction
                - $thirdAction

                Keep the note short enough to scan, but specific enough to recover the context later.
            """.trimIndent()
        }

        fun fillerBody(
            spec: DemoNotebookSpec,
            title: String,
            index: Int,
        ): String {
            val detail = spec.leadDetails[index % spec.leadDetails.size]
            val firstAction = spec.checklist[index % spec.checklist.size]
            val secondAction = spec.checklist[(index + 1) % spec.checklist.size]
            return """
                Short update for ${spec.subject}. $detail

                - Current focus: ${spec.fillerTopics[index % spec.fillerTopics.size]}.
                - Decision: keep the next step small and visible.
                - Follow-up: $firstAction
                - Reminder: $secondAction
            """.trimIndent()
        }

        fun mockBody(body: String): String =
            "$body\n\n$mockContentMarker"

        fun currentLocalDate(): LocalDate =
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    }

    private fun nextEditorSessionId(): Long =
        nextEditorSessionIdSeed++
}

private fun LocalDate.toInstantAtStartOfDay(timeZoneId: String?): Instant {
    val timeZone = timeZoneId
        ?.let { runCatching { TimeZone.of(it) }.getOrNull() }
        ?: TimeZone.UTC
    return atStartOfDayIn(timeZone)
}


private fun NoteEditorState.resolveCreatedAt(
    createdDate: LocalDate,
    defaultDate: LocalDate,
): Instant? {
    val existingCreatedAt = createdAt
    if (existingCreatedAt != null) {
        val existingCreatedDate = noteCalendarDate(existingCreatedAt, timeZoneId)
        return if (createdDate == existingCreatedDate) {
            existingCreatedAt
        } else {
            createdDate.toInstantAtStartOfDay(timeZoneId)
        }
    }
    return if (createdDate == defaultDate) {
        null
    } else {
        createdDate.toInstantAtStartOfDay(timeZoneId)
    }
}

data class NotesUiState(
    val notebooks: List<NotebookSummary> = emptyList(),
    val notebookConflicts: Map<String, NotebookConflictDetails> = emptyMap(),
    val deletedWorkspaceItems: List<DeletedWorkspaceItem> = emptyList(),
    val selectedNotebookId: String? = null,
    val notes: List<NoteSummary> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<NoteSummary> = emptyList(),
    val selectedNoteIds: Set<String> = emptySet(),
    val noteSelectionAnchorId: String? = null,
    val batchOperationInProgress: Boolean = false,
    val batchDeleteUndoItems: List<DeletedWorkspaceItem> = emptyList(),
    val editor: NoteEditorState? = null,
    val versionHistory: NoteVersionHistoryState? = null,
    val conflictDetails: ConflictDetails? = null,
    val unsavedChangesDialogVisible: Boolean = false,
    val feedbackMessage: String? = null,
    val localChangeEventId: Long = 0L,
) {
    val selectedNotebook: NotebookSummary? =
        notebooks.firstOrNull { it.id == selectedNotebookId }

    val visibleNotes: List<NoteSummary> =
        if (searchQuery.isBlank()) notes else searchResults

    val noteSelectionActive: Boolean get() = selectedNoteIds.isNotEmpty()
}

private fun NoteDetails.toBatchInput(
    notebookId: String = this.notebookId,
    createdAt: Instant = this.createdAt,
    location: NotesLocationInput? = this.location,
    timeZoneId: String? = this.timeZoneId,
): NoteInput =
    NoteInput(
        notebookId = notebookId,
        title = title,
        markdownBody = markdownBody,
        createdAt = createdAt,
        location = location,
        timeZoneId = timeZoneId,
        causalToken = causalToken,
    )

private fun ConflictDetails.referencesNote(noteId: String): Boolean =
    conflictNoteId == noteId || originalNoteId == noteId

private fun NoteDetails.toListSummary(): NoteSummary =
    NoteSummary(
        id = id,
        notebookId = notebookId,
        title = title,
        excerpt = markdownBody.lineSequence().joinToString(" ").trim().take(180),
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncBadge = syncBadge,
        timeZoneId = timeZoneId,
    )

private fun List<NoteSummary>.upsertNote(summary: NoteSummary): List<NoteSummary> =
    (filterNot { it.id == summary.id } + summary)
        .sortedWith(compareByDescending<NoteSummary> { it.createdAt }.thenBy { it.id })

private fun List<NoteSummary>.withSavedNote(
    searchQuery: String,
    saved: NoteDetails,
): List<NoteSummary> {
    if (searchQuery.isBlank()) {
        return this
    }
    val summary = saved.toListSummary()
    return if (saved.matchesSearch(searchQuery)) {
        upsertNote(summary)
    } else {
        filterNot { it.id == saved.id }
    }
}

private fun NoteDetails.matchesSearch(query: String): Boolean {
    val normalized = query.trim().lowercase()
    if (normalized.isBlank()) {
        return false
    }
    return title.lowercase().contains(normalized) ||
        markdownBody.lowercase().contains(normalized) ||
        location?.placeText?.lowercase()?.contains(normalized) == true
}

data class MockContentResult(
    val createdNotebooks: Int = 0,
    val createdNotes: Int = 0,
    val deletedNotebooks: Int = 0,
    val deletedNotes: Int = 0,
    val errorMessage: String? = null,
) {
    val success: Boolean = errorMessage == null

    val summary: String =
        errorMessage ?: when {
            createdNotebooks > 0 || createdNotes > 0 ->
                "Created $createdNotebooks notebooks and $createdNotes notes."
            deletedNotebooks > 0 || deletedNotes > 0 ->
                "Deleted $deletedNotes notes and $deletedNotebooks notebooks."
            else -> "No generated content changed."
        }
    }

data class NoteEditorState(
    val sessionId: Long,
    val noteId: String?,
    val causalToken: CausalEditToken?,
    val notebookId: String,
    val title: String,
    val markdownBody: String,
    val markdownPreviewVisible: Boolean,
    val markdownSelectionStart: Int,
    val markdownSelectionEnd: Int,
    val createdDateText: String,
    val createdAt: Instant?,
    val timeZoneId: String?,
    val placeText: String,
    val latitudeText: String,
    val longitudeText: String,
    val accuracyMetersText: String,
    val altitudeMetersText: String,
    val capturedAtText: String,
    val syncBadge: NoteSyncBadge,
    val originalSnapshot: NoteEditorSnapshot,
    val validationMessage: String? = null,
) {
    val hasUnsavedChanges: Boolean
        get() = snapshot() != originalSnapshot

    fun snapshot(): NoteEditorSnapshot =
        NoteEditorSnapshot(
            notebookId = notebookId,
            title = title,
            markdownBody = markdownBody,
            createdDateText = createdDateText,
            timeZoneId = timeZoneId,
            placeText = placeText,
            latitudeText = latitudeText,
            longitudeText = longitudeText,
            accuracyMetersText = accuracyMetersText,
            altitudeMetersText = altitudeMetersText,
            capturedAtText = capturedAtText,
        )

    companion object {
        fun newDraft(
            sessionId: Long = 0L,
            notebookId: String,
            createdDateText: String,
            markdownPreviewVisible: Boolean = false,
        ): NoteEditorState {
            val snapshot = NoteEditorSnapshot(
                notebookId = notebookId,
                title = "",
                markdownBody = "",
                createdDateText = createdDateText,
                timeZoneId = null,
                placeText = "",
                latitudeText = "",
                longitudeText = "",
                accuracyMetersText = "",
                altitudeMetersText = "",
                capturedAtText = "",
            )
            return NoteEditorState(
                sessionId = sessionId,
                noteId = null,
                causalToken = null,
                notebookId = notebookId,
                title = "",
                markdownBody = "",
                markdownPreviewVisible = markdownPreviewVisible,
                markdownSelectionStart = 0,
                markdownSelectionEnd = 0,
                createdDateText = createdDateText,
                createdAt = null,
                timeZoneId = null,
                placeText = "",
                latitudeText = "",
                longitudeText = "",
                accuracyMetersText = "",
                altitudeMetersText = "",
                capturedAtText = "",
                syncBadge = NoteSyncBadge.Pending,
                originalSnapshot = snapshot,
            )
        }

        fun fromDetails(
            details: NoteDetails,
            markdownPreviewVisible: Boolean = false,
            sessionId: Long = 0L,
        ): NoteEditorState {
            val snapshot = NoteEditorSnapshot(
                notebookId = details.notebookId,
                title = details.title,
                markdownBody = details.markdownBody,
                createdDateText = noteCalendarDate(details.createdAt, details.timeZoneId).toString(),
                timeZoneId = details.timeZoneId,
                placeText = details.location?.placeText.orEmpty(),
                latitudeText = details.location?.latitude?.toString().orEmpty(),
                longitudeText = details.location?.longitude?.toString().orEmpty(),
                accuracyMetersText = details.location?.accuracyMeters?.toString().orEmpty(),
                altitudeMetersText = details.location?.altitudeMeters?.toString().orEmpty(),
                capturedAtText = details.location?.capturedAt?.toString().orEmpty(),
            )
            return NoteEditorState(
                sessionId = sessionId,
                noteId = details.id,
                causalToken = details.causalToken,
                notebookId = details.notebookId,
                title = details.title,
                markdownBody = details.markdownBody,
                markdownPreviewVisible = markdownPreviewVisible,
                markdownSelectionStart = details.markdownBody.length,
                markdownSelectionEnd = details.markdownBody.length,
                createdDateText = noteCalendarDate(details.createdAt, details.timeZoneId).toString(),
                createdAt = details.createdAt,
                timeZoneId = details.timeZoneId,
                placeText = details.location?.placeText.orEmpty(),
                latitudeText = details.location?.latitude?.toString().orEmpty(),
                longitudeText = details.location?.longitude?.toString().orEmpty(),
                accuracyMetersText = details.location?.accuracyMeters?.toString().orEmpty(),
                altitudeMetersText = details.location?.altitudeMeters?.toString().orEmpty(),
                capturedAtText = details.location?.capturedAt?.toString().orEmpty(),
                syncBadge = details.syncBadge,
                originalSnapshot = snapshot,
            )
        }
    }
}

data class NoteVersionHistoryState(
    val noteId: String,
    val versions: List<NoteVersionSummary>,
    val visible: Boolean,
)

data class NoteEditorSnapshot(
    val notebookId: String,
    val title: String,
    val markdownBody: String,
    val createdDateText: String,
    val timeZoneId: String?,
    val placeText: String,
    val latitudeText: String,
    val longitudeText: String,
    val accuracyMetersText: String,
    val altitudeMetersText: String,
    val capturedAtText: String,
)
