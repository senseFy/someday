package saien.someday.ui.settings

/** Completes the platform's user-selected export delivery, including cancellation. */
fun interface LocalExportRunner {
    suspend fun export(): LocalExportResult
}

sealed interface LocalExportResult {
    /** The user-selected destination has received the export, not merely a staging file. */
    data class Saved(val summary: SettingsExportSummary) : LocalExportResult

    data object Cancelled : LocalExportResult

    data object Failed : LocalExportResult
}
