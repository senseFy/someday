@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.ui.notes

import saien.someday.domain.notes.DeletedWorkspaceItem
import saien.someday.domain.notes.MemoryDayCount
import saien.someday.domain.notes.MemoryMonth
import saien.someday.domain.notes.ConflictDetails
import saien.someday.domain.notes.ConflictHistory
import saien.someday.domain.notes.ConflictResolutionAction
import saien.someday.domain.notes.NoteDetails
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.notes.NoteSummary
import saien.someday.domain.notes.NoteSyncBadge
import saien.someday.domain.notes.NoteVersionSummary
import saien.someday.domain.notes.NotebookConflictDetails
import saien.someday.domain.notes.NotebookSummary
import saien.someday.domain.notes.NotesLocationInput
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.notes.noteCalendarDate
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

class InMemoryNotesRepository : NotesRepository {
    var failNextSave: Boolean = false
    var listNotebooksCalls: Int = 0
        private set
    var listNotesCalls: Int = 0
        private set
    var listDeletedWorkspaceItemsCalls: Int = 0
        private set
    var getNotebookConflictDetailsCalls: Int = 0
        private set
    var listNoteVersionsCalls: Int = 0
        private set

    private var nextId: Int = 0
    private var logicalTime: Long = 0
    private val notebooks = linkedMapOf<String, NotebookSummary>()
    private val notes = linkedMapOf<String, NoteDetails>()
    private val versions = linkedMapOf<String, MutableList<NoteVersionSummary>>()
    private val conflictSources = linkedMapOf<String, String>()

    override fun listNotebooks(): List<NotebookSummary> {
        listNotebooksCalls += 1
        return notebooks.values.sortedWith(compareBy<NotebookSummary> { it.sortOrder }.thenBy { it.title })
    }

    override fun listDeletedWorkspaceItems(): List<DeletedWorkspaceItem> {
        listDeletedWorkspaceItemsCalls += 1
        return emptyList()
    }

    override fun getNotebookConflictDetails(notebookId: String): NotebookConflictDetails? {
        getNotebookConflictDetailsCalls += 1
        return null
    }

    override fun createNotebook(title: String): NotebookSummary {
        require(title.isNotBlank()) { "Notebook title must not be blank." }
        val notebook = NotebookSummary(
            id = nextId("notebook"),
            title = title.trim(),
            sortOrder = (notebooks.size + 1).toLong(),
            syncBadge = NoteSyncBadge.Pending,
        )
        notebooks[notebook.id] = notebook
        return notebook
    }

    override fun renameNotebook(
        notebookId: String,
        title: String,
    ): NotebookSummary {
        require(title.isNotBlank()) { "Notebook title must not be blank." }
        val existing = requireNotNull(notebooks[notebookId]) {
            "Cannot rename missing notebook: $notebookId"
        }
        val renamed = existing.copy(title = title.trim(), syncBadge = NoteSyncBadge.Pending)
        notebooks[notebookId] = renamed
        return renamed
    }

    override fun deleteNotebook(notebookId: String) {
        requireNotNull(notebooks[notebookId]) {
            "Cannot delete missing notebook: $notebookId"
        }
        require(notes.values.none { it.notebookId == notebookId }) {
            "Cannot delete a notebook that still contains active notes."
        }
        notebooks.remove(notebookId)
    }

    override fun listNotes(notebookId: String): List<NoteSummary> {
        listNotesCalls += 1
        return notes.values
            .filter { it.notebookId == notebookId }
            .map { it.toSummary() }
            .sortedWith(compareByDescending<NoteSummary> { it.createdAt }.thenBy { it.title })
    }

    override fun getNoteDetails(noteId: String): NoteDetails? = notes[noteId]

    override fun createNote(input: NoteInput): NoteDetails {
        failIfRequested()
        require(notebooks.containsKey(input.notebookId)) {
            "Cannot create note in missing notebook: ${input.notebookId}"
        }
        val note = NoteDetails(
            id = nextId("note"),
            notebookId = input.notebookId,
            title = input.title,
            markdownBody = input.markdownBody,
            createdAt = input.createdAt ?: nextInstant(),
            updatedAt = nextInstant(),
            location = input.location,
            syncBadge = NoteSyncBadge.Pending,
            timeZoneId = input.timeZoneId,
        )
        notes[note.id] = note
        appendVersion(
            note = note,
            parentVersionId = null,
            baseVersionId = null,
            deviceId = "in-memory-device",
            mergeMetadata = null,
        )
        return note
    }

    override fun updateNote(
        noteId: String,
        input: NoteInput,
    ): NoteDetails {
        failIfRequested()
        val existing = requireNotNull(notes[noteId]) {
            "Cannot edit missing note: $noteId"
        }
        require(notebooks.containsKey(input.notebookId)) {
            "Cannot move note to missing notebook: ${input.notebookId}"
        }
        if (existing.notebookId == input.notebookId &&
            existing.title == input.title &&
            existing.markdownBody == input.markdownBody &&
            (input.createdAt == null || existing.createdAt == input.createdAt) &&
            existing.timeZoneId == input.timeZoneId &&
            existing.location == input.location
        ) {
            return existing
        }
        val updated = NoteDetails(
            id = noteId,
            notebookId = input.notebookId,
            title = input.title,
            markdownBody = input.markdownBody,
            createdAt = input.createdAt ?: existing.createdAt,
            updatedAt = nextInstant(),
            location = input.location,
            syncBadge = NoteSyncBadge.Pending,
            timeZoneId = input.timeZoneId,
        )
        notes[noteId] = updated
        appendVersion(
            note = updated,
            parentVersionId = versions[noteId]?.lastOrNull()?.versionId,
            baseVersionId = versions[noteId]?.lastOrNull()?.versionId,
            deviceId = "in-memory-device",
            mergeMetadata = null,
        )
        return updated
    }

    override fun listNoteVersions(noteId: String): List<NoteVersionSummary> {
        listNoteVersionsCalls += 1
        return versions[noteId].orEmpty().toList()
    }

    override fun restoreNoteVersion(
        noteId: String,
        versionId: String,
    ): NoteDetails {
        failIfRequested()
        val existing = requireNotNull(notes[noteId]) {
            "Cannot restore missing note: $noteId"
        }
        val version = requireNotNull(versions[noteId]?.firstOrNull { it.versionId == versionId }) {
            "Cannot restore missing version $versionId for note: $noteId"
        }
        val restored = existing.copy(
            title = version.title,
            markdownBody = version.markdownBody,
            updatedAt = nextInstant(),
            syncBadge = NoteSyncBadge.Pending,
        )
        notes[noteId] = restored
        appendVersion(
            note = restored,
            parentVersionId = versions[noteId]?.lastOrNull()?.versionId,
            baseVersionId = version.versionId,
            deviceId = "in-memory-device",
            mergeMetadata = """{"source":"restore","restoredVersionId":"${version.versionId}"}""",
        )
        return restored
    }

    override fun getConflictDetails(noteId: String): ConflictDetails? {
        val originalNoteId = conflictSources[noteId] ?: return null
        val original = notes[originalNoteId] ?: return null
        val conflict = notes[noteId] ?: return null
        return ConflictDetails(
            conflictNoteId = noteId,
            originalNoteId = originalNoteId,
            originalHistory = ConflictHistory(
                noteId = originalNoteId,
                title = original.title,
                versions = versions[originalNoteId].orEmpty(),
            ),
            conflictHistory = ConflictHistory(
                noteId = noteId,
                title = conflict.title,
                versions = versions[noteId].orEmpty(),
            ),
            sourceDeviceId = "in-memory-remote",
            sourceUpdatedAt = versions[noteId]?.lastOrNull()?.createdAt,
        )
    }

    override fun getConflictDetailsForOriginal(originalNoteId: String): ConflictDetails? =
        conflictSources.entries
            .firstOrNull { (_, sourceNoteId) -> sourceNoteId == originalNoteId }
            ?.key
            ?.let(::getConflictDetails)

    override fun resolveConflict(
        conflictNoteId: String,
        action: ConflictResolutionAction,
    ): NoteDetails? {
        val conflict = notes[conflictNoteId] ?: return null
        return when (action) {
            ConflictResolutionAction.KeepConflictCopy -> {
                val kept = conflict.copy(syncBadge = NoteSyncBadge.Pending)
                notes[conflictNoteId] = kept
                conflictSources.remove(conflictNoteId)
                kept
            }
            ConflictResolutionAction.DeleteConflictCopy -> {
                notes.remove(conflictNoteId)
                versions.remove(conflictNoteId)
                conflictSources.remove(conflictNoteId)
                null
            }
            ConflictResolutionAction.RestoreOriginalFromConflict -> {
                val originalNoteId = conflictSources[conflictNoteId] ?: return resolveConflict(
                    conflictNoteId = conflictNoteId,
                    action = ConflictResolutionAction.KeepConflictCopy,
                )
                val original = notes[originalNoteId] ?: return resolveConflict(
                    conflictNoteId = conflictNoteId,
                    action = ConflictResolutionAction.KeepConflictCopy,
                )
                val restored = original.copy(
                    title = conflict.title,
                    markdownBody = conflict.markdownBody,
                    createdAt = conflict.createdAt,
                    updatedAt = nextInstant(),
                    syncBadge = NoteSyncBadge.Pending,
                )
                notes[originalNoteId] = restored
                appendVersion(
                    note = restored,
                    parentVersionId = versions[originalNoteId]?.lastOrNull()?.versionId,
                    baseVersionId = versions[conflictNoteId]?.lastOrNull()?.versionId,
                    deviceId = "in-memory-device",
                    mergeMetadata = """{"source":"conflict-restore","conflictNoteId":"$conflictNoteId"}""",
                )
                notes.remove(conflictNoteId)
                conflictSources.remove(conflictNoteId)
                restored
            }
            ConflictResolutionAction.MergeIntoOriginal -> {
                val originalNoteId = conflictSources[conflictNoteId] ?: return resolveConflict(
                    conflictNoteId = conflictNoteId,
                    action = ConflictResolutionAction.KeepConflictCopy,
                )
                val original = notes[originalNoteId] ?: return resolveConflict(
                    conflictNoteId = conflictNoteId,
                    action = ConflictResolutionAction.KeepConflictCopy,
                )
                val merged = original.copy(
                    markdownBody = "${original.markdownBody}\n\n---\n${conflict.markdownBody}",
                    updatedAt = nextInstant(),
                    syncBadge = NoteSyncBadge.Pending,
                )
                notes[originalNoteId] = merged
                appendVersion(
                    note = merged,
                    parentVersionId = versions[originalNoteId]?.lastOrNull()?.versionId,
                    baseVersionId = versions[conflictNoteId]?.lastOrNull()?.versionId,
                    deviceId = "in-memory-device",
                    mergeMetadata = """{"source":"conflict-manual-merge","conflictNoteId":"$conflictNoteId"}""",
                )
                notes.remove(conflictNoteId)
                conflictSources.remove(conflictNoteId)
                merged
            }
        }
    }

    override fun deleteNote(noteId: String) {
        requireNotNull(notes.remove(noteId)) {
            "Cannot delete missing note: $noteId"
        }
        conflictSources.remove(noteId)
    }

    override fun listMemoryDayCounts(month: MemoryMonth): List<MemoryDayCount> =
        notes.values
            .map { noteCalendarDate(it.createdAt, it.timeZoneId) }
            .filter(month::contains)
            .groupingBy { it }
            .eachCount()
            .map { (date, count) -> MemoryDayCount(date = date, noteCount = count) }
            .sortedBy { it.date.toString() }

    override fun listActiveNoteDates(): List<LocalDate> =
        notes.values.map { note ->
            noteCalendarDate(note.createdAt, note.timeZoneId)
        }

    override fun listNotesForDate(date: LocalDate): List<NoteSummary> =
        notes.values
            .filter { noteCalendarDate(it.createdAt, it.timeZoneId) == date }
            .sortedWith(compareByDescending<NoteDetails> { it.updatedAt }.thenBy { it.title })
            .map { it.toSummary() }

    override fun listPriorYearNotesForDate(date: LocalDate): List<NoteSummary> =
        notes.values
            .filter {
                val noteDate = noteCalendarDate(it.createdAt, it.timeZoneId)
                noteDate < date && noteDate.month == date.month && noteDate.day == date.day
            }
            .sortedWith(
                compareByDescending<NoteDetails> { noteCalendarDate(it.createdAt, it.timeZoneId).toString() }
                    .thenByDescending { it.updatedAt },
            )
            .map { it.toSummary() }

    override fun searchNotes(query: String): List<NoteSummary> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            return emptyList()
        }
        return notes.values
            .filter { note ->
                "${note.title}\n${note.markdownBody}".lowercase().contains(normalizedQuery)
            }
            .sortedWith(compareByDescending<NoteDetails> { it.createdAt }.thenByDescending { it.updatedAt })
            .map { it.toSummary() }
    }

    fun seedNote(
        notebookId: String,
        title: String,
        markdownBody: String,
        createdDate: LocalDate,
        location: NotesLocationInput? = null,
        syncBadge: NoteSyncBadge = NoteSyncBadge.Pending,
        timeZoneId: String? = null,
    ): NoteDetails {
        require(notebooks.containsKey(notebookId)) {
            "Cannot seed note in missing notebook: $notebookId"
        }
        val note = NoteDetails(
            id = nextId("note"),
            notebookId = notebookId,
            title = title,
            markdownBody = markdownBody,
            createdAt = createdDate.toInstantAtStartOfDay(),
            updatedAt = nextInstant(),
            location = location,
            syncBadge = syncBadge,
            timeZoneId = timeZoneId,
        )
        notes[note.id] = note
        appendVersion(
            note = note,
            parentVersionId = null,
            baseVersionId = null,
            deviceId = "in-memory-device",
            mergeMetadata = null,
        )
        return note
    }

    fun seedConflictPair(
        notebookId: String,
        originalTitle: String,
        originalBody: String,
        conflictTitle: String,
        conflictBody: String,
    ): SeededConflictPair {
        val original = seedNote(
            notebookId = notebookId,
            title = originalTitle,
            markdownBody = originalBody,
            createdDate = LocalDate(2026, 5, 22),
            syncBadge = NoteSyncBadge.Pending,
        )
        val originalEdited = original.copy(
            title = "$originalTitle edited locally",
            markdownBody = "$originalBody\nLocal follow-up",
            syncBadge = NoteSyncBadge.Pending,
        )
        notes[original.id] = originalEdited
        appendVersion(
            note = originalEdited,
            parentVersionId = versions[original.id]?.lastOrNull()?.versionId,
            baseVersionId = versions[original.id]?.firstOrNull()?.versionId,
            deviceId = "in-memory-device",
            mergeMetadata = null,
        )

        val conflict = seedNote(
            notebookId = notebookId,
            title = conflictTitle,
            markdownBody = conflictBody,
            createdDate = LocalDate(2026, 5, 22),
            syncBadge = NoteSyncBadge.Conflict("Manual resolution required"),
        )
        val conflictVersion = versions[conflict.id]?.lastOrNull()
        if (conflictVersion != null) {
            versions[conflict.id] = mutableListOf(
                conflictVersion.copy(
                    deviceId = "in-memory-remote",
                    mergeMetadata = """{"source":"remote-vs-dirty-local","remoteNoteId":"${original.id}","remoteDeviceId":"in-memory-remote"}""",
                ),
            )
        }
        conflictSources[conflict.id] = original.id
        return SeededConflictPair(
            originalNoteId = original.id,
            conflictNoteId = conflict.id,
        )
    }

    fun seedConflictForOriginal(
        originalNoteId: String,
        conflictTitle: String,
        conflictBody: String,
    ): SeededConflictPair {
        val original = requireNotNull(notes[originalNoteId]) {
            "Cannot seed conflict for missing original note: $originalNoteId"
        }
        val conflict = seedNote(
            notebookId = original.notebookId,
            title = conflictTitle,
            markdownBody = conflictBody,
            createdDate = noteCalendarDate(original.createdAt, original.timeZoneId),
            syncBadge = NoteSyncBadge.Conflict("Manual resolution required"),
            timeZoneId = original.timeZoneId,
        )
        val conflictVersion = versions[conflict.id]?.lastOrNull()
        if (conflictVersion != null) {
            versions[conflict.id] = mutableListOf(
                conflictVersion.copy(
                    deviceId = "in-memory-remote",
                    mergeMetadata = """{"source":"remote-vs-dirty-local","remoteNoteId":"${original.id}","remoteDeviceId":"in-memory-remote"}""",
                ),
            )
        }
        conflictSources[conflict.id] = original.id
        return SeededConflictPair(
            originalNoteId = original.id,
            conflictNoteId = conflict.id,
        )
    }

    private fun NoteDetails.toSummary(): NoteSummary =
        NoteSummary(
            id = id,
            notebookId = notebookId,
            title = title,
            excerpt = markdownBody.lineSequence().joinToString(" ").trim().take(240),
            createdAt = createdAt,
            updatedAt = updatedAt,
            timeZoneId = timeZoneId,
            syncBadge = syncBadge,
        )

    private fun appendVersion(
        note: NoteDetails,
        parentVersionId: String?,
        baseVersionId: String?,
        deviceId: String,
        mergeMetadata: String?,
    ): NoteVersionSummary {
        val noteVersions = versions.getOrPut(note.id) { mutableListOf() }
        val revision = noteVersions.size.toLong() + 1L
        val version = NoteVersionSummary(
            versionId = nextId("version"),
            noteId = note.id,
            parentVersionId = parentVersionId,
            baseVersionId = baseVersionId,
            revision = revision,
            title = note.title,
            markdownBody = note.markdownBody,
            contentHash = "${note.title}\n${note.markdownBody}\n${note.createdAt}\n${note.timeZoneId.orEmpty()}".hashCode().toString(),
            deviceId = deviceId,
            mergeMetadata = mergeMetadata,
            createdAt = note.updatedAt,
        )
        noteVersions += version
        return version
    }

    private fun failIfRequested() {
        if (failNextSave) {
            failNextSave = false
            error("Injected save failure")
        }
    }

    private fun nextId(prefix: String): String {
        nextId += 1
        return "$prefix-$nextId"
    }

    private fun nextInstant(): Instant {
        logicalTime += 1
        return Instant.fromEpochMilliseconds(logicalTime)
    }

    private fun LocalDate.toInstantAtStartOfDay(): Instant =
        atStartOfDayIn(TimeZone.UTC)
}

data class SeededConflictPair(
    val originalNoteId: String,
    val conflictNoteId: String,
)
