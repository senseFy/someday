@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.domain.notes

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class NotebookSummary(
    val id: String,
    val title: String,
    val sortOrder: Long,
    val syncBadge: NoteSyncBadge = NoteSyncBadge.Synced,
    val causalToken: CausalEditToken? = null,
)

data class NotebookOrderEdit(
    val notebookId: String,
    val sortOrder: Long,
    val causalToken: CausalEditToken,
)

data class NotebookConflictBranch(
    val versionId: String,
    val title: String?,
    val sortOrder: Long?,
    val deleted: Boolean,
    val updatedAt: Instant,
)

data class NotebookConflictDetails(
    val conflictId: String,
    val notebookId: String,
    val expectedHeadVersionIds: List<String>,
    val branches: List<NotebookConflictBranch>,
    val conflictingFields: Set<String>,
)

enum class DeletedWorkspaceItemType {
    Note,
    Notebook,
}

data class DeletedWorkspaceItem(
    val entityId: String,
    val type: DeletedWorkspaceItemType,
    val displayTitle: String,
    val deletedAt: Instant,
    val retainedContentVersionId: String?,
    val causalToken: CausalEditToken,
) {
    val canRestore: Boolean get() = retainedContentVersionId != null
}

/** Exact immutable base represented by an editable product view. */
data class CausalEditToken(
    val syncEpochId: String,
    val entityType: String,
    val entityId: String,
    val expectedBaseVersionId: String,
    val activeConflictId: String? = null,
)

data class NoteSummary(
    val id: String,
    val notebookId: String,
    val title: String,
    val excerpt: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val syncBadge: NoteSyncBadge,
    val timeZoneId: String? = null,
)

data class NoteDetails(
    val id: String,
    val notebookId: String,
    val title: String,
    val markdownBody: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val location: NotesLocationInput?,
    val syncBadge: NoteSyncBadge,
    val timeZoneId: String? = null,
    val causalToken: CausalEditToken? = null,
)

data class NotesLocationInput(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val placeText: String? = null,
    val accuracyMeters: Double? = null,
    val altitudeMeters: Double? = null,
    val capturedAt: Instant? = null,
) {
    val hasValue: Boolean =
        latitude != null || longitude != null || !placeText.isNullOrBlank()
}

data class MemoryMonth(
    val year: Int,
    val monthNumber: Int,
) {
    init {
        require(monthNumber in 1..12) { "Month number must be between 1 and 12." }
    }

    val label: String = "$year-${monthNumber.toString().padStart(2, '0')}"
    val daysInMonth: Int = daysInMonth(year, monthNumber)
    val startDate: LocalDate = LocalDate(year, monthNumber, 1)
    val endDate: LocalDate = LocalDate(year, monthNumber, daysInMonth)

    fun previous(): MemoryMonth =
        if (monthNumber == 1) {
            MemoryMonth(year - 1, 12)
        } else {
            MemoryMonth(year, monthNumber - 1)
        }

    fun next(): MemoryMonth =
        if (monthNumber == 12) {
            MemoryMonth(year + 1, 1)
        } else {
            MemoryMonth(year, monthNumber + 1)
        }

    fun shiftedBy(months: Int): MemoryMonth {
        val total = year * 12 + (monthNumber - 1) + months
        val y = total / 12
        val r = total % 12
        return if (r < 0) MemoryMonth(y - 1, r + 13) else MemoryMonth(y, r + 1)
    }

    fun dateForDay(dayOfMonth: Int): LocalDate {
        require(dayOfMonth in 1..daysInMonth) {
            "Day $dayOfMonth is outside $label."
        }
        return LocalDate(year, monthNumber, dayOfMonth)
    }

    fun clampDay(dayOfMonth: Int): LocalDate =
        LocalDate(year, monthNumber, dayOfMonth.coerceIn(1, daysInMonth))

    fun contains(date: LocalDate): Boolean =
        date.year == year && date.month.ordinal == monthNumber - 1
}

data class MemoryDayCount(
    val date: LocalDate,
    val noteCount: Int,
)

data class NoteInput(
    val notebookId: String,
    val title: String,
    val markdownBody: String,
    val createdAt: Instant? = null,
    val location: NotesLocationInput? = null,
    val timeZoneId: String? = null,
    val causalToken: CausalEditToken? = null,
)

data class NoteBatchUpdate(
    val noteId: String,
    val input: NoteInput,
)

data class NoteBatchDeletion(
    val noteId: String,
    val causalToken: CausalEditToken? = null,
)

data class NoteBatchUndelete(
    val noteId: String,
    val retainedContentVersionId: String,
    val causalToken: CausalEditToken,
)

data class NoteVersionSummary(
    val versionId: String,
    val noteId: String,
    val parentVersionId: String?,
    val baseVersionId: String?,
    val revision: Long,
    val title: String,
    val markdownBody: String,
    val contentHash: String,
    val deviceId: String,
    val mergeMetadata: String?,
    val createdAt: Instant,
)

enum class ConflictResolutionAction(
    val label: String,
) {
    MergeIntoOriginal("Merge into original"),
    KeepConflictCopy("Keep conflict copy"),
    RestoreOriginalFromConflict("Restore original from conflict"),
    DeleteConflictCopy("Delete conflict copy"),
}

fun noteCalendarDate(
    createdAt: Instant,
    timeZoneId: String?,
): LocalDate {
    val timeZone = timeZoneId
        ?.let { runCatching { TimeZone.of(it) }.getOrNull() }
        ?: TimeZone.UTC
    return createdAt.toLocalDateTime(timeZone).date
}

data class ConflictHistory(
    val noteId: String,
    val title: String,
    val versions: List<NoteVersionSummary>,
)

/** A concrete immutable head participating in a version-DAG conflict. */
data class VersionConflictBranch(
    val versionId: String,
    val history: ConflictHistory,
    val deleted: Boolean,
    val authorDeviceId: String?,
    val updatedAt: Instant,
)

data class ConflictDetails(
    val conflictNoteId: String,
    val originalNoteId: String,
    val originalHistory: ConflictHistory,
    val conflictHistory: ConflictHistory,
    val sourceDeviceId: String?,
    val sourceUpdatedAt: Instant?,
    val availableActions: List<ConflictResolutionAction> = ConflictResolutionAction.entries,
    val versionBranches: List<VersionConflictBranch> = emptyList(),
    val expectedHeadVersionIds: List<String> = emptyList(),
)

sealed interface NoteSyncBadge {
    val label: String
    val details: String?

    data object Synced : NoteSyncBadge {
        override val label: String = "Synced"
        override val details: String? = null
    }

    data object Pending : NoteSyncBadge {
        override val label: String = "Pending sync"
        override val details: String = "Local changes are waiting to sync."
    }

    data class Error(
        override val details: String,
    ) : NoteSyncBadge {
        override val label: String = "Sync error"
    }

    data class Conflict(
        override val details: String,
    ) : NoteSyncBadge {
        override val label: String = "Conflict"
    }
}

interface NotesRepository {
    fun listNotebooks(): List<NotebookSummary>

    fun createNotebook(title: String): NotebookSummary

    fun renameNotebook(
        notebookId: String,
        title: String,
    ): NotebookSummary

    fun renameNotebook(
        notebookId: String,
        title: String,
        causalToken: CausalEditToken,
    ): NotebookSummary = renameNotebook(notebookId, title)

    fun deleteNotebook(notebookId: String)

    fun deleteNotebook(notebookId: String, causalToken: CausalEditToken) = deleteNotebook(notebookId)

    fun reorderNotebooks(edits: List<NotebookOrderEdit>): List<NotebookSummary> =
        error("Notebook reordering is unavailable in this repository.")

    fun getNotebookConflictDetails(notebookId: String): NotebookConflictDetails? = null

    fun resolveNotebookConflictBranch(
        conflictId: String,
        selectedVersionId: String,
        expectedHeadVersionIds: List<String>,
    ): NotebookSummary? = null

    fun restoreNotebook(
        notebookId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ): NotebookSummary = error("Notebook restore is unavailable in this repository.")

    /** Explicit deletion/undelete surface; ordinary history restore is separate. */
    fun listDeletedWorkspaceItems(): List<DeletedWorkspaceItem> = emptyList()

    fun listNotes(notebookId: String): List<NoteSummary>

    fun getNoteDetails(noteId: String): NoteDetails?

    fun createNote(input: NoteInput): NoteDetails

    fun updateNote(
        noteId: String,
        input: NoteInput,
    ): NoteDetails

    /** Applies every note edit in one local transaction or applies none of them. */
    fun updateNotes(edits: List<NoteBatchUpdate>): List<NoteDetails> =
        error("Batch note updates are unavailable in this repository.")

    fun listNoteVersions(noteId: String): List<NoteVersionSummary>

    fun restoreNoteVersion(
        noteId: String,
        versionId: String,
    ): NoteDetails

    fun restoreNoteVersion(
        noteId: String,
        versionId: String,
        causalToken: CausalEditToken,
    ): NoteDetails = restoreNoteVersion(noteId, versionId)

    fun getConflictDetails(noteId: String): ConflictDetails? = null

    fun getConflictDetailsForOriginal(originalNoteId: String): ConflictDetails? = null

    fun resolveConflict(
        conflictNoteId: String,
        action: ConflictResolutionAction,
    ): NoteDetails? = null

    /**
     * Resolves a version-DAG conflict by preserving the exact payload of the
     * selected immutable head while joining every currently active head.
     */
    fun resolveConflictBranch(
        conflictNoteId: String,
        versionId: String,
    ): NoteDetails? = null

    fun resolveConflictBranch(
        conflictNoteId: String,
        versionId: String,
        expectedHeadVersionIds: List<String>,
    ): NoteDetails? = resolveConflictBranch(conflictNoteId, versionId)

    fun deleteNote(noteId: String)

    fun deleteNote(noteId: String, causalToken: CausalEditToken) = deleteNote(noteId)

    /** Applies every note deletion in one local transaction or applies none of them. */
    fun deleteNotes(deletions: List<NoteBatchDeletion>): Unit =
        error("Batch note deletion is unavailable in this repository.")

    fun undeleteNote(
        noteId: String,
        retainedContentVersionId: String,
        causalToken: CausalEditToken,
    ): NoteDetails = error("Note undelete is unavailable in this repository.")

    /** Applies every note restore in one local transaction or applies none of them. */
    fun undeleteNotes(restores: List<NoteBatchUndelete>): List<NoteDetails> =
        error("Batch note restore is unavailable in this repository.")

    fun listMemoryDayCounts(month: MemoryMonth): List<MemoryDayCount>

    fun listActiveNoteDates(): List<LocalDate>

    fun listNotesForDate(date: LocalDate): List<NoteSummary>

    fun listPriorYearNotesForDate(date: LocalDate): List<NoteSummary>

    fun searchNotes(query: String): List<NoteSummary>
}

private fun daysInMonth(
    year: Int,
    monthNumber: Int,
): Int =
    when (monthNumber) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> error("Invalid month: $monthNumber")
    }

private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
