@file:OptIn(kotlin.time.ExperimentalTime::class)
package saien.someday.data.local

import saien.someday.domain.notes.MemoryDayCount
import saien.someday.domain.notes.MemoryMonth
import saien.someday.domain.notes.ConflictDetails
import saien.someday.domain.notes.ConflictResolutionAction
import saien.someday.domain.notes.NoteDetails
import saien.someday.domain.notes.NoteBatchDeletion
import saien.someday.domain.notes.NoteBatchUpdate
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.notes.NoteSummary
import saien.someday.domain.notes.NoteSyncBadge
import saien.someday.domain.notes.NoteVersionSummary
import saien.someday.domain.notes.NotebookSummary
import saien.someday.domain.notes.NotesLocationInput
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.notes.noteCalendarDate
import kotlinx.datetime.LocalDate

class SqlDelightNotesRepository(
    private val localRepository: SqlDelightLocalDataRepository,
) : NotesRepository {
    override fun listNotebooks(): List<NotebookSummary> =
        localRepository.listActiveNotebooks().map { notebook ->
            notebook.toSummary(
                syncMetadata = localRepository.getSyncMetadata(notebook.id, EntityType.NOTEBOOK),
            )
        }

    override fun createNotebook(title: String): NotebookSummary {
        val notebook = localRepository.createNotebook(title)
        return notebook.toSummary(localRepository.getSyncMetadata(notebook.id, EntityType.NOTEBOOK))
    }

    override fun renameNotebook(
        notebookId: String,
        title: String,
    ): NotebookSummary {
        val notebook = localRepository.renameNotebook(notebookId, title)
        return notebook.toSummary(localRepository.getSyncMetadata(notebook.id, EntityType.NOTEBOOK))
    }

    override fun deleteNotebook(notebookId: String) {
        localRepository.deleteNotebook(notebookId)
    }

    override fun listNotes(notebookId: String): List<NoteSummary> =
        localRepository.listActiveNotes(notebookId).map { note ->
            note.toSummary(localRepository.getSyncMetadata(note.id, EntityType.NOTE))
        }

    override fun getNoteDetails(noteId: String): NoteDetails? =
        localRepository.getNote(noteId)?.let { note ->
            note.toDetails(
                location = localRepository.getLocation(note.id),
                syncMetadata = localRepository.getSyncMetadata(note.id, EntityType.NOTE),
            )
        }

    override fun createNote(input: NoteInput): NoteDetails {
        val note = localRepository.createNote(
            notebookId = input.notebookId,
            title = input.title,
            markdownBody = input.markdownBody,
            createdAt = input.createdAt,
            timeZoneId = input.timeZoneId,
            location = input.location?.toLocalInput(),
        )
        return checkNotNull(getNoteDetails(note.id))
    }

    override fun updateNote(
        noteId: String,
        input: NoteInput,
    ): NoteDetails {
        val note = localRepository.updateNote(
            noteId = noteId,
            notebookId = input.notebookId,
            title = input.title,
            markdownBody = input.markdownBody,
            createdAt = input.createdAt,
            timeZoneId = input.timeZoneId,
            location = input.location?.toLocalInput(),
            clearLocation = input.location == null,
        )
        return checkNotNull(getNoteDetails(note.id))
    }

    override fun updateNotes(edits: List<NoteBatchUpdate>): List<NoteDetails> {
        require(edits.map { it.noteId }.distinct().size == edits.size) {
            "A note can only appear once in a batch update."
        }
        var updated = emptyList<NoteDetails>()
        localRepository.database.transaction {
            updated = edits.map { edit -> updateNote(edit.noteId, edit.input) }
        }
        return updated
    }

    override fun listNoteVersions(noteId: String): List<NoteVersionSummary> =
        localRepository.listNoteVersions(noteId).map { it.toSummary() }

    override fun restoreNoteVersion(
        noteId: String,
        versionId: String,
    ): NoteDetails {
        val note = localRepository.restoreNoteVersion(noteId, versionId)
        return checkNotNull(getNoteDetails(note.id))
    }

    override fun getConflictDetails(noteId: String): ConflictDetails? =
        localRepository.getConflictDetails(noteId)

    override fun getConflictDetailsForOriginal(originalNoteId: String): ConflictDetails? =
        localRepository.getConflictDetailsForOriginal(originalNoteId)

    override fun resolveConflict(
        conflictNoteId: String,
        action: ConflictResolutionAction,
    ): NoteDetails? {
        val note = localRepository.resolveConflictCopy(conflictNoteId, action) ?: return null
        return getNoteDetails(note.id)
    }

    override fun deleteNote(noteId: String) {
        localRepository.deleteNote(noteId)
    }

    override fun deleteNotes(deletions: List<NoteBatchDeletion>) {
        require(deletions.map { it.noteId }.distinct().size == deletions.size) {
            "A note can only appear once in a batch deletion."
        }
        localRepository.database.transaction {
            deletions.forEach { localRepository.deleteNote(it.noteId) }
        }
    }

    override fun listMemoryDayCounts(month: MemoryMonth): List<MemoryDayCount> =
        localRepository.listMemoryDayCounts(month)

    override fun listActiveNoteDates(): List<LocalDate> =
        localRepository.listAllActiveNotes().map { note ->
            noteCalendarDate(note.createdAt, note.timeZoneId)
        }

    override fun listNotesForDate(date: LocalDate): List<NoteSummary> =
        localRepository.listActiveNotesForDate(date).map { note ->
            note.toSummary(localRepository.getSyncMetadata(note.id, EntityType.NOTE))
        }

    override fun listPriorYearNotesForDate(date: LocalDate): List<NoteSummary> =
        localRepository.listPriorYearSameDayNotes(date).map { note ->
            note.toSummary(localRepository.getSyncMetadata(note.id, EntityType.NOTE))
        }

    override fun searchNotes(query: String): List<NoteSummary> =
        localRepository.searchActiveNotes(query).map { note ->
            note.toSummary(localRepository.getSyncMetadata(note.id, EntityType.NOTE))
        }

    private fun Notebook.toSummary(syncMetadata: SyncMetadata?): NotebookSummary =
        NotebookSummary(
            id = id,
            title = title,
            sortOrder = sortOrder,
            syncBadge = syncBadgeFor(syncState, syncMetadata),
        )

    private fun Note.toDetails(
        location: NoteLocation?,
        syncMetadata: SyncMetadata?,
    ): NoteDetails =
        NoteDetails(
            id = id,
            notebookId = notebookId,
            title = title,
            markdownBody = markdownBody,
            createdAt = createdAt,
            updatedAt = updatedAt,
            location = location?.toDomainInput(),
            syncBadge = syncBadgeFor(syncState, syncMetadata),
            timeZoneId = timeZoneId,
        )

    private fun Note.toSummary(syncMetadata: SyncMetadata?): NoteSummary =
        NoteSummary(
            id = id,
            notebookId = notebookId,
            title = title,
            excerpt = excerpt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            timeZoneId = timeZoneId,
            syncBadge = syncBadgeFor(syncState, syncMetadata),
        )

    private fun NoteVersion.toSummary(): NoteVersionSummary =
        NoteVersionSummary(
            versionId = versionId,
            noteId = noteId,
            parentVersionId = parentVersionId,
            baseVersionId = baseVersionId,
            revision = revision,
            title = title,
            markdownBody = markdownBody,
            contentHash = contentHash,
            deviceId = deviceId,
            mergeMetadata = mergeMetadataJson,
            createdAt = createdAt,
        )

    private fun NotesLocationInput.toLocalInput(): LocationInput =
        LocationInput(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            altitudeMeters = altitudeMeters,
            placeText = placeText?.takeIf { it.isNotBlank() },
            capturedAt = capturedAt,
        )

    private fun NoteLocation.toDomainInput(): NotesLocationInput =
        NotesLocationInput(
            latitude = latitude,
            longitude = longitude,
            placeText = placeText,
            accuracyMeters = accuracyMeters,
            altitudeMeters = altitudeMeters,
            capturedAt = capturedAt,
        )

    private fun syncBadgeFor(
        syncState: SyncState,
        syncMetadata: SyncMetadata?,
    ): NoteSyncBadge =
        when {
            syncState == SyncState.CONFLICT ||
                syncMetadata?.conflictState == ConflictState.MANUAL_RESOLUTION_REQUIRED ||
                syncMetadata?.conflictState == ConflictState.DELETE_VS_EDIT ->
                NoteSyncBadge.Conflict(
                    details = syncMetadata?.conflictState?.storageValue
                        ?.replace('_', ' ')
                        ?.replaceFirstChar { it.uppercase() }
                        ?: "Manual resolution required",
                )

            !syncMetadata?.lastError.isNullOrBlank() ->
                NoteSyncBadge.Error(checkNotNull(syncMetadata?.lastError))

            syncState == SyncState.DIRTY || syncState == SyncState.DELETED || syncMetadata?.dirty == true ->
                NoteSyncBadge.Pending

            else -> NoteSyncBadge.Synced
        }
}
