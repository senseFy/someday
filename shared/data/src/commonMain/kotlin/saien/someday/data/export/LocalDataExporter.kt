@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.export

import saien.someday.data.local.Note
import saien.someday.data.local.NoteLocation
import saien.someday.data.local.Notebook
import saien.someday.data.local.SqlDelightLocalDataRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.time.Clock

class LocalDataExporter(
    private val localRepository: SqlDelightLocalDataRepository,
    private val clock: () -> Instant = { Clock.System.now() },
    /** When present, owns authority routing; null delegates to pre-authority local product rows. */
    private val authoritativeDocumentProvider: ((Instant) -> LocalDataExportDocument?)? = null,
) {
    fun exportDocument(): LocalDataExportDocument {
        val exportedAt = clock()
        authoritativeDocumentProvider?.invoke(exportedAt)?.let { return it }
        val notebooks = localRepository.listActiveNotebooks()
        val notes = notebooks.flatMap { notebook ->
            localRepository.listActiveNotes(notebook.id).map { note ->
                note.toExportedNote(localRepository.getLocation(note.id))
            }
        }

        return LocalDataExportDocument(
            exportedAt = exportedAt.toString(),
            notebooks = notebooks.map { it.toExportedNotebook() },
            notes = notes,
        )
    }

    fun exportJson(): String =
        encodeDocument(exportDocument())

    fun encodeDocument(document: LocalDataExportDocument): String =
        jsonFormatter.encodeToString(document)

    private fun Notebook.toExportedNotebook(): ExportedNotebook =
        ExportedNotebook(
            id = id,
            title = title,
            sortOrder = sortOrder,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    private fun Note.toExportedNote(location: NoteLocation?): ExportedNote =
        currentVersionId?.let(localRepository::getNoteVersion).let { currentVersion ->
            ExportedNote(
                id = id,
                notebookId = notebookId,
                title = title,
                markdownBody = markdownBody,
                excerpt = excerpt,
                timeZoneId = timeZoneId,
                createdAt = createdAt.toString(),
                updatedAt = updatedAt.toString(),
                revision = revision,
                location = location?.toExportedLocation(),
                currentVersionId = currentVersion?.versionId ?: currentVersionId,
                parentVersionId = currentVersion?.parentVersionId,
                baseVersionId = currentVersion?.baseVersionId,
                versionDeviceId = currentVersion?.deviceId,
                mergeMetadataJson = currentVersion?.mergeMetadataJson,
            )
        }

    private fun NoteLocation.toExportedLocation(): ExportedLocation =
        ExportedLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            altitudeMeters = altitudeMeters,
            placeText = placeText,
            capturedAt = capturedAt.toString(),
        )

    companion object {
        private val jsonFormatter = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

@Serializable
data class LocalDataExportDocument(
    val format: String = "someday.local-export.v2",
    val formatDescription: String = "JSON export of local active notebooks and notes only; settings, devices, keys, tokens, passwords, and recovery material are intentionally excluded.",
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
