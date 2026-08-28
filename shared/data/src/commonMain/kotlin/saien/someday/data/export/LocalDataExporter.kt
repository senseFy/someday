@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

const val LOCAL_DATA_EXPORT_FORMAT_V3: String = "someday.local-export.v3"
const val LOCAL_DATA_EXPORT_FORMAT_V2: String = "someday.local-export.v2"

fun isSupportedLocalDataExportFormat(format: String): Boolean =
    format == LOCAL_DATA_EXPORT_FORMAT_V3 || format == LOCAL_DATA_EXPORT_FORMAT_V2

class LocalDataExporter(
    /** System V3's workspace DAG is the sole production source for portable exports. */
    private val authoritativeDocumentProvider: (Instant) -> LocalDataExportDocument,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    fun exportDocument(): LocalDataExportDocument =
        authoritativeDocumentProvider(clock())

    fun exportJson(): String =
        encodeDocument(exportDocument())

    fun encodeDocument(document: LocalDataExportDocument): String =
        jsonFormatter.encodeToString(document)

    companion object {
        private val jsonFormatter = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

@Serializable
data class LocalDataExportDocument(
    val format: String = LOCAL_DATA_EXPORT_FORMAT_V3,
    val formatDescription: String =
        "JSON export of workspace notebooks and note Markdown only. " +
            "Image bytes are not included in this release; asset references are preserved and may remain " +
            "unresolved after restore. Settings, devices, keys, tokens, passwords, and recovery material " +
            "are intentionally excluded.",
    val includesMediaBytes: Boolean = false,
    val assetReferencesMayBeUnresolved: Boolean = true,
    val exportedAt: String,
    val notebooks: List<ExportedNotebook>,
    val notes: List<ExportedNote>,
    val excludedSensitiveFields: List<String> = defaultExcludedSensitiveFields,
) {
    companion object {
        val defaultExcludedSensitiveFields: List<String> = listOf(
            "raw workspace keys",
            "refresh tokens",
            "passwords",
            "recovery material",
            "secure storage aliases",
            "credential secrets",
            "device workspace key metadata",
            "sync account sessions",
        )
    }
}

@Serializable
data class ExportedNotebook(
    val id: String,
    val title: String,
    val sortOrder: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ExportedNote(
    val id: String,
    val notebookId: String,
    val title: String,
    val markdownBody: String,
    val excerpt: String,
    val timeZoneId: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val revision: Long,
    val location: ExportedLocation? = null,
    val currentVersionId: String? = null,
    val parentVersionId: String? = null,
    val baseVersionId: String? = null,
    val versionDeviceId: String? = null,
    val mergeMetadataJson: String? = null,
)

@Serializable
data class ExportedLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Double? = null,
    val altitudeMeters: Double? = null,
    val placeText: String? = null,
    val capturedAt: String,
)
