package saien.someday.ui.i18n

/**
 * User-visible feedback strings for NotesUiController.
 * Defaults are English so unit tests remain stable without Compose resources.
 * Production injects localized values from composeResources.
 */
data class NotesUiStrings(
    val conflictWaiting: String = "This note has a sync conflict waiting for review.",
    val noteNoLongerExists: String = "Note no longer exists.",
    val resolveBeforeSwitchNotebook: String = "Resolve unsaved note changes before switching notebooks.",
    val foundNotes: String = "Found %1\$d notes.",
    val resolveBeforeOpen: String = "Resolve unsaved note changes before opening another note.",
    val createNotebookFirst: String = "Create a notebook before adding a note.",
    val notebookCreated: String = "Notebook created: %1\$s",
    val notebookRenamed: String = "Notebook renamed: %1\$s",
    val notebookDeleted: String = "Notebook deleted. Empty notebooks can be removed safely.",
    val cannotDeleteNotebook: String = "Cannot delete notebook: %1\$s. No notes were removed.",
    val notebookConflictResolved: String = "Notebook conflict resolved.",
    val cannotResolveNotebookConflict: String = "Cannot resolve notebook conflict: %1\$s",
    val snapshotExpiredDeletion: String = "The retained content snapshot has expired; this deletion still prevents resurrection.",
    val noteUndeleted: String = "Note undeleted from its retained complete snapshot.",
    val notebookRestored: String = "Notebook restored from its retained complete snapshot.",
    val cannotRestoreDeleted: String = "Cannot restore deleted item: %1\$s",
    val conflictCopyExposes: String = "Conflict copy exposes both histories for manual resolution.",
    val conflictGone: String = "Conflict no longer exists.",
    val locationAdded: String = "Location added.",
    val locationPermissionDenied: String = "Location permission was denied. You can still type a place.",
    val locationUnavailable: String = "Location is unavailable. You can still type a place.",
    val imageAdded: String = "Image added.",
    val imageImportCancelled: String = "Image import cancelled.",
    val imageImportFailed: String = "Unable to import the selected image.",
    val noteSaved: String = "Note saved: %1\$s",
    val saveFailed: String = "Save failed: %1\$s",
    val discarded: String = "Discarded unsaved note changes.",
    val noteDeleted: String = "Note deleted.",
    val cannotDeleteNote: String = "Cannot delete note: %1\$s",
    val resolveBeforeBatchUpdate: String = "Save or discard changes to the open note before editing it in a batch.",
    val notesMoved: String = "Notes moved: %1\$d.",
    val notesUpdated: String = "Notes updated: %1\$d.",
    val notesDeleted: String = "Notes deleted: %1\$d.",
    val notesRestored: String = "Notes restored: %1\$d.",
    val cannotEditNotes: String = "Cannot complete batch operation: %1\$s",
    val batchNotebookUnavailable: String = "The destination notebook is no longer available.",
    val invalidTimeZone: String = "Enter a valid IANA time zone.",
    val saveBeforeHistory: String = "Save the note before opening version history.",
    val conflictResolvedAction: String = "Conflict resolved: %1\$s.",
    val conflictResolvedBranch: String = "Conflict resolved using the selected branch.",
    val cannotResolveConflict: String = "Cannot resolve conflict: %1\$s",
    val saveBeforeRestore: String = "Save the note before restoring a version.",
    val resolveBeforeRestore: String = "Resolve unsaved note changes before restoring a historical version.",
    val restoredVersion: String = "Restored version as a new current version.",
    val cannotRestoreVersion: String = "Cannot restore version: %1\$s",
    val demoCreated: String = "Demo workspace created: %1\$d notebooks, %2\$d notes.",
    val cannotCreateDemo: String = "Cannot create demo content: %1\$s",
    val demoCleared: String = "Demo content cleared: %1\$d notes, %2\$d empty notebooks.",
    val cannotClearDemo: String = "Cannot clear demo content: %1\$s",
    val titleRequired: String = "Title is required before saving.",
    val chooseNotebook: String = "Choose a notebook before saving.",
    val dateFormat: String = "Created date must use YYYY-MM-DD format.",
    val latNumber: String = "Latitude must be a valid number when provided.",
    val lonNumber: String = "Longitude must be a valid number when provided.",
    val latLonTogether: String = "Latitude and longitude must be provided together.",
    val latRange: String = "Latitude must be between -90 and 90.",
    val lonRange: String = "Longitude must be between -180 and 180.",
    val accuracyNumber: String = "Accuracy must be a valid number when provided.",
    val altitudeNumber: String = "Altitude must be a valid number when provided.",
    val capturedIso: String = "Captured timestamp must be an ISO-8601 instant.",
    val metaRequiresCoords: String = "Accuracy, altitude, and captured timestamp require captured coordinates.",
    val unknownError: String = "unknown error",
    val conflictActionMerge: String = "Merge into original",
    val conflictActionKeepCopy: String = "Keep conflict copy",
    val conflictActionRestoreOriginal: String = "Restore original from conflict",
    val conflictActionDeleteCopy: String = "Delete conflict copy",
)

data class MemoriesUiStrings(
    val showingMonth: String = "Showing %1\$s.",
    val chooseDateInMonth: String = "Choose a date in %1\$s.",
    val selectedDate: String = "Selected %1\$s.",
)

/**
 * Format Android/CMP-style placeholders (`%1\$s`, `%2\$d`, …) plus single `%s`/`%d`.
 */
fun formatUiString(template: String, vararg args: Any): String {
    var result = template
    args.forEachIndexed { index, arg ->
        val n = index + 1
        val value = arg.toString()
        result = result
            .replace("%" + n + "\$s", value)
            .replace("%" + n + "\$d", value)
    }
    if (args.size == 1) {
        val value = args[0].toString()
        result = result.replace("%d", value).replace("%s", value)
    }
    return result
}
