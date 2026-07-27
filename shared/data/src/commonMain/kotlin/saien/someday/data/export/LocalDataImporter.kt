@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.export

import saien.someday.data.local.LocationInput
import saien.someday.data.local.Note
import saien.someday.data.local.RemoteApplyStatus
import saien.someday.data.local.RemoteNoteSnapshot
import saien.someday.data.local.SqlDelightLocalDataRepository
import kotlin.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LocalDataImporter(
    private val localRepository: SqlDelightLocalDataRepository,
    /** When present, owns authority routing; null delegates to pre-authority local product rows. */
    private val authoritativeImporter: ((LocalDataExportDocument) -> LocalDataImportSummary?)? = null,
) {
    fun importJson(json: String): LocalDataImportSummary =
        importDocument(jsonFormatter.decodeFromString(json))

    fun importDocument(document: LocalDataExportDocument): LocalDataImportSummary {
        require(document.format == "someday.local-export.v2") {
            "Unsupported backup format: ${document.format}"
        }
        authoritativeImporter?.invoke(document)?.let { return it }

        var notebooksCreated = 0
        var notebooksReused = 0
        var notesCreated = 0
        var notesUpdated = 0
        var notesMerged = 0
        var noteConflictsCreated = 0
        var notesSkipped = 0
        val notebookIdMap = mutableMapOf<String, String>()
        val activeNotebookIdsByTitle = mutableMapOf<String, String>()

        localRepository.listActiveNotebooks().forEach { notebook ->
            val titleKey = notebookTitleKey(notebook.title)
            if (titleKey.isNotBlank() && titleKey !in activeNotebookIdsByTitle) {
                activeNotebookIdsByTitle[titleKey] = notebook.id
            }
        }

        document.notebooks.forEach { notebook ->
            val restoredTitle = restoredNotebookTitle(notebook.title)
            val titleKey = notebookTitleKey(restoredTitle)
            val existing = localRepository.getNotebook(notebook.id, includeDeleted = true)
            val reusableNotebookId = existing
                ?.takeIf { it.deletedAt == null }
                ?.id
                ?: titleKey
                    .takeIf { it.isNotBlank() }
                    ?.let { activeNotebookIdsByTitle[it] }

            if (reusableNotebookId != null) {
                notebookIdMap[notebook.id] = reusableNotebookId
                notebooksReused += 1
            } else {
                val created = if (existing == null) {
                    localRepository.createNotebook(
                        id = notebook.id,
                        title = restoredTitle,
                        sortOrder = notebook.sortOrder,
                    )
                } else {
                    localRepository.createNotebook(
                        title = restoredTitle,
                        sortOrder = notebook.sortOrder,
                    )
                }
                notebookIdMap[notebook.id] = created.id
                if (titleKey.isNotBlank()) {
                    activeNotebookIdsByTitle[titleKey] = created.id
                }
                notebooksCreated += 1
            }
        }

        document.notes.forEach { note ->
            val notebookId = notebookIdMap[note.notebookId] ?: createFallbackNotebook(note.notebookId, notebookIdMap).also {
                notebooksCreated += 1
            }
            val title = note.title.ifBlank { "Untitled" }
            val timeZoneId = note.timeZoneId?.takeIf { it.isNotBlank() }
            val location = note.location?.toLocationInput()
            val createdAt = Instant.parse(note.createdAt)
            val existing = localRepository.getNote(note.id)
            val existingIncludingDeleted = localRepository.getNote(note.id, includeDeleted = true)

            if (existing?.matchesImportedNote(note, notebookId, title, createdAt, timeZoneId) == true) {
                notesSkipped += 1
                return@forEach
            }

            if (existingIncludingDeleted != null) {
                val result = localRepository.applyRemoteNoteSnapshot(
                    note.toRemoteNoteSnapshot(
                        notebookId = notebookId,
                        title = title,
                        createdAt = createdAt,
                        timeZoneId = timeZoneId,
                    ),
                )
                when (result.status) {
                    RemoteApplyStatus.APPLIED -> notesUpdated += 1
                    RemoteApplyStatus.MERGED_COMMON_BASE -> notesMerged += 1
                    RemoteApplyStatus.CONFLICT_COPY_CREATED -> noteConflictsCreated += 1
                    RemoteApplyStatus.IGNORED_BY_TOMBSTONE,
                    RemoteApplyStatus.IGNORED_OLDER_REVISION,
                    RemoteApplyStatus.CONFLICT_REQUIRES_LOCAL_RESOLUTION,
                    -> notesSkipped += 1
                }
            } else {
                localRepository.importNoteSnapshot(
                    id = note.id,
                    notebookId = notebookId,
                    title = title,
                    markdownBody = note.markdownBody,
                    timeZoneId = timeZoneId,
                    createdAt = createdAt,
                    updatedAt = Instant.parse(note.updatedAt),
                    revision = note.revision,
                    location = location,
                    currentVersionId = note.currentVersionId,
                    parentVersionId = note.parentVersionId,
                    baseVersionId = note.baseVersionId,
                    versionDeviceId = note.versionDeviceId?.takeIf { it.isNotBlank() } ?: "backup",
                    mergeMetadataJson = note.mergeMetadataJson,
                )
                notesCreated += 1
            }
        }

        return LocalDataImportSummary(
            notebooksCreated = notebooksCreated,
            notebooksReused = notebooksReused,
            notesCreated = notesCreated,
            notesUpdated = notesUpdated,
            notesMerged = notesMerged,
            noteConflictsCreated = noteConflictsCreated,
            notesSkipped = notesSkipped,
        )
    }

    private fun createFallbackNotebook(
        originalNotebookId: String,
        notebookIdMap: MutableMap<String, String>,
    ): String {
        val created = localRepository.createNotebook("Restored notes")
        notebookIdMap[originalNotebookId] = created.id
        return created.id
    }

    private fun restoredNotebookTitle(title: String): String =
        title.trim().ifBlank { "Restored notebook" }

    private fun notebookTitleKey(title: String): String =
        title.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun Note.matchesImportedNote(
        note: ExportedNote,
        notebookId: String,
        title: String,
        createdAt: Instant,
        timeZoneId: String?,
    ): Boolean =
        this.notebookId == notebookId &&
            this.title == title &&
            markdownBody == note.markdownBody &&
            this.createdAt == createdAt &&
            this.timeZoneId == timeZoneId

    private fun ExportedNote.toRemoteNoteSnapshot(
        notebookId: String,
        title: String,
        createdAt: Instant,
        timeZoneId: String?,
    ): RemoteNoteSnapshot =
        RemoteNoteSnapshot(
            id = id,
            notebookId = notebookId,
            title = title,
            markdownBody = markdownBody,
            timeZoneId = timeZoneId,
            createdAt = createdAt,
            revision = revision,
            updatedAt = Instant.parse(updatedAt),
            deviceId = versionDeviceId?.takeIf { it.isNotBlank() } ?: "backup",
            currentVersionId = currentVersionId,
            parentVersionId = parentVersionId,
            baseVersionId = baseVersionId,
            mergeMetadataJson = mergeMetadataJson,
        )

    private fun ExportedLocation.toLocationInput(): LocationInput =
        LocationInput(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            altitudeMeters = altitudeMeters,
            placeText = placeText?.takeIf { it.isNotBlank() },
            capturedAt = Instant.parse(capturedAt),
        )

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
