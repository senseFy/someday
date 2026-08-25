@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.export

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LocalDataImporter(
    /** System V3's workspace DAG is the sole production destination for portable restores. */
    private val authoritativeImporter: (LocalDataExportDocument) -> LocalDataImportSummary,
) {
    fun importJson(json: String): LocalDataImportSummary =
        importDocument(jsonFormatter.decodeFromString(json))

    fun importDocument(document: LocalDataExportDocument): LocalDataImportSummary {
        require(isSupportedLocalDataExportFormat(document.format)) {
            "Unsupported backup format: ${document.format}"
        }
        return authoritativeImporter(document)
    }

    private companion object {
        val jsonFormatter = Json {
            ignoreUnknownKeys = true
        }
    }
}

data class LocalDataImportSummary(
    val notebooksCreated: Int,
    val notebooksReused: Int,
    val notesCreated: Int,
    val notesUpdated: Int = 0,
    val notesMerged: Int = 0,
    val noteConflictsCreated: Int = 0,
    val notesSkipped: Int,
)
