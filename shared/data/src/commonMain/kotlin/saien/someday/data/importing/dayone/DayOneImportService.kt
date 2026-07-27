@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.importing.dayone

import saien.someday.data.export.ExportedLocation
import saien.someday.data.export.ExportedNote
import saien.someday.data.export.ExportedNotebook
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataImportSummary
import saien.someday.data.local.LocationInput
import saien.someday.data.local.NoteLocation
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.domain.notes.noteCalendarDate
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DayOneImportService(
    private val localRepository: SqlDelightLocalDataRepository,
    private val authoritativeImporter: ((LocalDataExportDocument) -> LocalDataImportSummary?)? = null,
) {
    fun importArchive(
        archiveBytes: ByteArray,
        fallbackJournalTitle: String = "Day One",
    ): DayOneImportSummary =
        importDocuments(DayOneArchiveReader.readJsonDocuments(archiveBytes, fallbackJournalTitle))

    fun importDocuments(documents: List<DayOneJsonDocument>): DayOneImportSummary {
        require(documents.isNotEmpty()) { "Day One import requires at least one JSON document." }
        tryAuthoritativeImport(documents)?.let { return it }

        var notebooksCreated = 0
        var notebooksReused = 0
        var notesCreated = 0
        var notesUpdated = 0
        var notesSkipped = 0
        var richTextConverted = 0
        var richTextFallbacks = 0
        var locationsImported = 0
        var tagsFound = 0
        var starredFound = 0
        var pinnedFound = 0
        var photosReferenced = 0
        var audiosReferenced = 0
        var videosReferenced = 0
        var pdfsReferenced = 0
        var weatherFound = 0
        var unsupportedEmbeddedObjects = 0

        val notebookIdsByTitle = localRepository.listActiveNotebooks()
            .associateBy { notebookTitleKey(it.title) }
            .toMutableMap()

        documents.forEach { document ->
            val notebookTitle = document.journalTitle.trim().ifBlank { "Day One" }
            val notebookKey = notebookTitleKey(notebookTitle)
            val notebook = notebookIdsByTitle[notebookKey]
            val notebookId = if (notebook != null) {
                notebooksReused += 1
                notebook.id
            } else {
                val created = localRepository.createNotebook(
                    title = notebookTitle,
                    sortOrder = (localRepository.listActiveNotebooks().maxOfOrNull { it.sortOrder } ?: 0L) + 1L,
                )
                notebookIdsByTitle[notebookKey] = created
                notebooksCreated += 1
                created.id
            }

            val decoded = json.decodeFromString(DayOneExportDocument.serializer(), document.json)
            decoded.entries.forEach { entry ->
                val entryResult = importEntry(entry, notebookId)
                when (entryResult.status) {
                    DayOneEntryImportStatus.Created -> notesCreated += 1
                    DayOneEntryImportStatus.Updated -> notesUpdated += 1
                    DayOneEntryImportStatus.Skipped -> notesSkipped += 1
                }
                if (entryResult.richTextConverted) {
                    richTextConverted += 1
                }
                if (entryResult.richTextFallback) {
                    richTextFallbacks += 1
                }
                if (entryResult.locationImported) {
                    locationsImported += 1
                }
                tagsFound += entryResult.tagsFound
                starredFound += entryResult.starredFound
                pinnedFound += entryResult.pinnedFound
                photosReferenced += entryResult.photosReferenced
                audiosReferenced += entryResult.audiosReferenced
                videosReferenced += entryResult.videosReferenced
                pdfsReferenced += entryResult.pdfsReferenced
                weatherFound += entryResult.weatherFound
                unsupportedEmbeddedObjects += entryResult.unsupportedEmbeddedObjects
            }
        }

        return DayOneImportSummary(
            journalsImported = documents.size,
            notebooksCreated = notebooksCreated,
            notebooksReused = notebooksReused,
            notesCreated = notesCreated,
            notesUpdated = notesUpdated,
            notesSkipped = notesSkipped,
            richTextConverted = richTextConverted,
            richTextFallbacks = richTextFallbacks,
            locationsImported = locationsImported,
            tagsFound = tagsFound,
            starredFound = starredFound,
            pinnedFound = pinnedFound,
            photosReferenced = photosReferenced,
            audiosReferenced = audiosReferenced,
            videosReferenced = videosReferenced,
            pdfsReferenced = pdfsReferenced,
            weatherFound = weatherFound,
            unsupportedEmbeddedObjects = unsupportedEmbeddedObjects,
        )
    }

    private fun tryAuthoritativeImport(documents: List<DayOneJsonDocument>): DayOneImportSummary? {
        val importer = authoritativeImporter ?: return null
        val notebooksByTitle = linkedMapOf<String, ExportedNotebook>()
        val notes = mutableListOf<ExportedNote>()
        var richTextConverted = 0
        var richTextFallbacks = 0
        var locationsImported = 0
        var tagsFound = 0
        var starredFound = 0
        var pinnedFound = 0
        var photosReferenced = 0
        var audiosReferenced = 0
        var videosReferenced = 0
        var pdfsReferenced = 0
        var weatherFound = 0
        var unsupportedEmbeddedObjects = 0

        documents.forEachIndexed { index, document ->
            val title = document.journalTitle.trim().ifBlank { "Day One" }
            val titleKey = notebookTitleKey(title)
            val decoded = json.decodeFromString(DayOneExportDocument.serializer(), document.json)
            val entryTimes = decoded.entries.map { parseInstantOrNow(it.creationDate) }
            val notebook = notebooksByTitle.getOrPut(titleKey) {
                val created = entryTimes.minOrNull() ?: kotlin.time.Clock.System.now()
                ExportedNotebook(
                    id = "dayone-journal-${titleKey.hashCode().toUInt().toString(16)}",
                    title = title,
                    sortOrder = index.toLong() + 1L,
                    createdAt = created.toString(),
                    updatedAt = created.toString(),
                )
            }
            decoded.entries.forEach { entry ->
                val createdAt = parseInstantOrNow(entry.creationDate)
                val updatedAt = entry.modifiedDate?.let(::parseInstantOrNull) ?: createdAt
                val conversion = DayOneRichTextMarkdownConverter.convert(entry.richText, entry.text.orEmpty())
                val markdown = conversion.markdown
                val timeZoneId = entry.timeZone?.takeIf { it.isNotBlank() }
                val titleValue = deriveTitle(markdown, noteCalendarDate(createdAt, timeZoneId))
                val location = entry.location?.toLocationInput(createdAt)
                val noteId = "dayone-${entry.uuid.trim()}"
                notes += ExportedNote(
                    id = noteId,
                    notebookId = notebook.id,
                    title = titleValue,
                    markdownBody = markdown,
                    excerpt = markdown.lineSequence().joinToString(" ").trim().take(180),
                    timeZoneId = timeZoneId,
                    createdAt = createdAt.toString(),
                    updatedAt = updatedAt.toString(),
                    revision = 1L,
                    location = location?.let {
                        ExportedLocation(
                            it.latitude,
                            it.longitude,
                            it.accuracyMeters,
                            it.altitudeMeters,
                            it.placeText,
                            (it.capturedAt ?: createdAt).toString(),
                        )
                    },
                    currentVersionId = "dayone-version-${entry.uuid.trim()}",
                    versionDeviceId = "day-one-import",
                    mergeMetadataJson = "day-one",
                )
                if (conversion.converted) richTextConverted++
                if (conversion.fallbackUsed) richTextFallbacks++
                if (location != null) locationsImported++
                tagsFound += entry.tags.size
                if (entry.starred) starredFound++
                if (entry.isPinned) pinnedFound++
                val media = entry.mediaCounts()
                photosReferenced += media.photos.takeIf { it > 0 } ?: conversion.photosReferenced
                audiosReferenced += media.audios.takeIf { it > 0 } ?: conversion.audiosReferenced
                videosReferenced += media.videos.takeIf { it > 0 } ?: conversion.videosReferenced
                pdfsReferenced += media.pdfs.takeIf { it > 0 } ?: conversion.pdfsReferenced
                if (entry.weather != null) weatherFound++
                unsupportedEmbeddedObjects += conversion.unsupportedEmbeddedObjects
            }
        }
        val exportedAt = notes.maxOfOrNull { it.updatedAt }
            ?: kotlin.time.Clock.System.now().toString()
        val imported = importer(
            LocalDataExportDocument(
                exportedAt = exportedAt,
                notebooks = notebooksByTitle.values.toList(),
                notes = notes.distinctBy { it.id },
            ),
        ) ?: return null
        return DayOneImportSummary(
            journalsImported = documents.size,
            notebooksCreated = imported.notebooksCreated,
            notebooksReused = imported.notebooksReused,
            notesCreated = imported.notesCreated,
            notesUpdated = imported.notesUpdated + imported.notesMerged + imported.noteConflictsCreated,
            notesSkipped = imported.notesSkipped,
            richTextConverted = richTextConverted,
            richTextFallbacks = richTextFallbacks,
            locationsImported = locationsImported,
            tagsFound = tagsFound,
            starredFound = starredFound,
            pinnedFound = pinnedFound,
            photosReferenced = photosReferenced,
            audiosReferenced = audiosReferenced,
            videosReferenced = videosReferenced,
            pdfsReferenced = pdfsReferenced,
            weatherFound = weatherFound,
            unsupportedEmbeddedObjects = unsupportedEmbeddedObjects,
        )
    }

    private fun importEntry(
        entry: DayOneEntry,
        notebookId: String,
    ): DayOneEntryImportResult {
        val noteId = "dayone-${entry.uuid.trim()}"
        val createdAt = parseInstantOrNow(entry.creationDate)
        val updatedAt = entry.modifiedDate?.let(::parseInstantOrNull) ?: createdAt
        val timeZoneId = entry.timeZone?.takeIf { it.isNotBlank() }
        val createdDate = noteCalendarDate(createdAt, timeZoneId)
        val conversion = DayOneRichTextMarkdownConverter.convert(
            richText = entry.richText,
            fallbackText = entry.text.orEmpty(),
        )
        val markdownBody = conversion.markdown
        val title = deriveTitle(markdownBody, createdDate)
        val location = entry.location?.toLocationInput(createdAt)
        val current = localRepository.getNote(noteId)
        val currentLocation = current?.let { localRepository.getLocation(it.id) }
        val contentMatches = current != null &&
            current.notebookId == notebookId &&
            current.title == title &&
            current.markdownBody == markdownBody &&
            current.createdAt == createdAt &&
            current.timeZoneId == timeZoneId &&
            currentLocation.matches(location)

        val status = when {
            contentMatches -> DayOneEntryImportStatus.Skipped
            current == null && localRepository.getNote(noteId, includeDeleted = true) == null -> {
                localRepository.importNoteSnapshot(
                    id = noteId,
                    notebookId = notebookId,
                    title = title,
                    markdownBody = markdownBody,
                    timeZoneId = timeZoneId,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    revision = 1L,
                    location = location,
                    currentVersionId = "dayone-version-${entry.uuid.trim()}",
                    versionDeviceId = "day-one-import",
                    mergeMetadataJson = """{"source":"day-one","dayOneUuid":"${jsonEscape(entry.uuid.trim())}"}""",
                )
                DayOneEntryImportStatus.Created
            }
            current != null -> {
                localRepository.updateNote(
                    noteId = current.id,
                    notebookId = notebookId,
                    title = title,
                    markdownBody = markdownBody,
                    createdAt = createdAt,
                    timeZoneId = timeZoneId,
                    clearTimeZone = timeZoneId == null,
                    location = location,
                    clearLocation = location == null,
                )
                DayOneEntryImportStatus.Updated
            }
            else -> DayOneEntryImportStatus.Skipped
        }

        val mediaCounts = entry.mediaCounts()
        return DayOneEntryImportResult(
            status = status,
            richTextConverted = conversion.converted,
            richTextFallback = conversion.fallbackUsed,
            locationImported = location != null,
            tagsFound = entry.tags.size,
            starredFound = if (entry.starred) 1 else 0,
            pinnedFound = if (entry.isPinned) 1 else 0,
            photosReferenced = mediaCounts.photos.takeIf { it > 0 } ?: conversion.photosReferenced,
            audiosReferenced = mediaCounts.audios.takeIf { it > 0 } ?: conversion.audiosReferenced,
            videosReferenced = mediaCounts.videos.takeIf { it > 0 } ?: conversion.videosReferenced,
            pdfsReferenced = mediaCounts.pdfs.takeIf { it > 0 } ?: conversion.pdfsReferenced,
            weatherFound = if (entry.weather == null) 0 else 1,
            unsupportedEmbeddedObjects = conversion.unsupportedEmbeddedObjects,
        )
    }

    private fun parseInstantOrNow(value: String?): Instant =
        value?.let(::parseInstantOrNull) ?: kotlin.time.Clock.System.now()

    private fun parseInstantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    private fun DayOneLocation.toLocationInput(createdAt: Instant): LocationInput? {
        val place = listOfNotNull(
            placeName?.takeIf { it.isNotBlank() },
            address?.takeIf { it.isNotBlank() },
            localityName?.takeIf { it.isNotBlank() },
            administrativeArea?.takeIf { it.isNotBlank() },
            country?.takeIf { it.isNotBlank() },
        ).distinct().joinToString(separator = ", ").takeIf { it.isNotBlank() }
        val hasCoordinates = latitude != null || longitude != null
        if (!hasCoordinates && place == null) {
            return null
        }
        return LocationInput(
            latitude = latitude,
            longitude = longitude,
            placeText = place,
            capturedAt = createdAt,
        )
    }

    private fun NoteLocation?.matches(input: LocationInput?): Boolean =
        when {
            this == null && input == null -> true
            this == null || input == null -> false
            else ->
                latitude == input.latitude &&
                    longitude == input.longitude &&
                    accuracyMeters == input.accuracyMeters &&
                    altitudeMeters == input.altitudeMeters &&
                    placeText == input.placeText?.takeIf { it.isNotBlank() } &&
                    capturedAt == (input.capturedAt ?: capturedAt)
        }

    private fun DayOneEntry.mediaCounts(): DayOneMediaCounts =
        DayOneMediaCounts(
            photos = photos.size,
            audios = audios.size,
            videos = videos.size,
            pdfs = pdfs.size,
        )

    private fun deriveTitle(
        markdownBody: String,
        createdDate: LocalDate,
    ): String =
        markdownBody
            .lineSequence()
            .map { line -> line.trim().trimStart('#').trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(80)
            ?: "Day One ${createdDate}"

    private fun notebookTitleKey(title: String): String =
        title.trim().replace(Regex("\\s+"), " ").lowercase()
}

data class DayOneJsonDocument(
    val journalTitle: String,
    val json: String,
)

data class DayOneImportSummary(
    val journalsImported: Int,
    val notebooksCreated: Int,
    val notebooksReused: Int,
    val notesCreated: Int,
    val notesUpdated: Int,
    val notesSkipped: Int,
    val richTextConverted: Int,
    val richTextFallbacks: Int,
    val locationsImported: Int,
    val tagsFound: Int,
    val starredFound: Int,
    val pinnedFound: Int,
    val photosReferenced: Int,
    val audiosReferenced: Int,
    val videosReferenced: Int,
    val pdfsReferenced: Int,
    val weatherFound: Int,
    val unsupportedEmbeddedObjects: Int,
) {
    val importedNotes: Int = notesCreated + notesUpdated

    fun toUserMessage(): String =
        "Imported $importedNotes Day One notes across $journalsImported journals. " +
            "Created $notebooksCreated notebooks, reused $notebooksReused, skipped $notesSkipped duplicates."
}

@Serializable
private data class DayOneExportDocument(
    val entries: List<DayOneEntry> = emptyList(),
)

@Serializable
private data class DayOneEntry(
    val uuid: String,
    val text: String? = null,
    val richText: String? = null,
    val creationDate: String? = null,
    val modifiedDate: String? = null,
    val timeZone: String? = null,
    val location: DayOneLocation? = null,
    val tags: List<String> = emptyList(),
    val photos: List<DayOneMedia> = emptyList(),
    val audios: List<DayOneMedia> = emptyList(),
    val videos: List<DayOneMedia> = emptyList(),
    val pdfs: List<DayOneMedia> = emptyList(),
    val weather: JsonObject? = null,
    val starred: Boolean = false,
    val isPinned: Boolean = false,
)

@Serializable
private data class DayOneLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val administrativeArea: String? = null,
    val country: String? = null,
    val localityName: String? = null,
    val placeName: String? = null,
)

@Serializable
private data class DayOneMedia(
    val identifier: String? = null,
    val type: String? = null,
)

private data class DayOneMediaCounts(
    val photos: Int,
    val audios: Int,
    val videos: Int,
    val pdfs: Int,
)

private data class DayOneEntryImportResult(
    val status: DayOneEntryImportStatus,
    val richTextConverted: Boolean,
    val richTextFallback: Boolean,
    val locationImported: Boolean,
    val tagsFound: Int,
    val starredFound: Int,
    val pinnedFound: Int,
    val photosReferenced: Int,
    val audiosReferenced: Int,
    val videosReferenced: Int,
    val pdfsReferenced: Int,
    val weatherFound: Int,
    val unsupportedEmbeddedObjects: Int,
)

private enum class DayOneEntryImportStatus {
    Created,
    Updated,
    Skipped,
}

private object DayOneRichTextMarkdownConverter {
    fun convert(
        richText: String?,
        fallbackText: String,
    ): DayOneRichTextConversion {
        if (richText.isNullOrBlank()) {
            return DayOneRichTextConversion(
                markdown = fallbackText.trim(),
                converted = false,
                fallbackUsed = false,
            )
        }
        val root = runCatching { json.parseToJsonElement(richText).jsonObject }.getOrNull()
            ?: return DayOneRichTextConversion(
                markdown = fallbackText.trim(),
                converted = false,
                fallbackUsed = true,
            )
        val contents = root["contents"] as? JsonArray
            ?: return DayOneRichTextConversion(
                markdown = fallbackText.trim(),
                converted = false,
                fallbackUsed = true,
            )

        val builder = StringBuilder()
        var photosReferenced = 0
        var audiosReferenced = 0
        var videosReferenced = 0
        var pdfsReferenced = 0
        var unsupportedEmbeddedObjects = 0

        contents.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val text = item["text"]?.jsonPrimitive?.contentOrNull
            if (text != null) {
                appendText(builder, text, item["attributes"] as? JsonObject)
            }
            val embeddedObjects = item["embeddedObjects"] as? JsonArray
            embeddedObjects?.forEach { embedded ->
                val embeddedObject = embedded as? JsonObject ?: return@forEach
                when (val type = embeddedObject["type"]?.jsonPrimitive?.contentOrNull.orEmpty()) {
                    "photo" -> {
                        photosReferenced += 1
                        appendEmbeddedPlaceholder(builder, "Photo", embeddedObject)
                    }
                    "audio" -> {
                        audiosReferenced += 1
                        appendEmbeddedPlaceholder(builder, "Audio", embeddedObject)
                    }
                    "video" -> {
                        videosReferenced += 1
                        appendEmbeddedPlaceholder(builder, "Video", embeddedObject)
                    }
                    "pdf" -> {
                        pdfsReferenced += 1
                        appendEmbeddedPlaceholder(builder, "PDF", embeddedObject)
                    }
                    "markdown" -> appendMarkdownObject(builder, embeddedObject)
                    "horizontalRuleLine" -> appendBlock(builder, "---")
                    else -> {
                        unsupportedEmbeddedObjects += 1
                        appendBlock(builder, "[Unsupported Day One object: ${type.ifBlank { "unknown" }}]")
                    }
                }
            }
        }

        val converted = builder.toString().trim()
        return if (converted.isBlank() && fallbackText.isNotBlank()) {
            DayOneRichTextConversion(
                markdown = fallbackText.trim(),
                converted = false,
                fallbackUsed = true,
                photosReferenced = photosReferenced,
                audiosReferenced = audiosReferenced,
                videosReferenced = videosReferenced,
                pdfsReferenced = pdfsReferenced,
                unsupportedEmbeddedObjects = unsupportedEmbeddedObjects,
            )
        } else {
            DayOneRichTextConversion(
                markdown = converted,
                converted = true,
                fallbackUsed = false,
                photosReferenced = photosReferenced,
                audiosReferenced = audiosReferenced,
                videosReferenced = videosReferenced,
                pdfsReferenced = pdfsReferenced,
                unsupportedEmbeddedObjects = unsupportedEmbeddedObjects,
            )
        }
    }

    private fun appendText(
        builder: StringBuilder,
        text: String,
        attributes: JsonObject?,
    ) {
        val line = attributes?.get("line") as? JsonObject
        val prefix = linePrefix(line)
        val formatted = applyInlineFormatting(text, attributes)
        if (prefix == null) {
            builder.append(formatted)
            return
        }

        if (builder.isNotEmpty() && !builder.endsWithLineBreak()) {
            builder.append('\n')
        }
        formatted.split('\n').forEachIndexed { index, part ->
            if (index > 0) {
                builder.append('\n')
            }
            if (part.isNotBlank()) {
                builder.append(prefix)
            }
            builder.append(part)
        }
    }

    private fun linePrefix(line: JsonObject?): String? {
        if (line == null) {
            return null
        }
        line["header"]?.jsonPrimitive?.intOrNull?.let { level ->
            return "#".repeat(level.coerceIn(1, 6)) + " "
        }
        val indent = "  ".repeat((line["indentLevel"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0))
        val checked = line["checked"]?.jsonPrimitive?.booleanOrNull
        if (checked != null) {
            return "$indent- [${if (checked) "x" else " "}] "
        }
        return when (line["listStyle"]?.jsonPrimitive?.contentOrNull) {
            "bulleted" -> "$indent- "
            "numbered" -> "${indent}1. "
            else -> null
        }
    }

    private fun applyInlineFormatting(
        text: String,
        attributes: JsonObject?,
    ): String {
        if (attributes == null) {
            return text
        }
        var result = text
        if (attributes["autolink"]?.jsonPrimitive?.booleanOrNull == true && text.startsWith("http")) {
            result = "[$text]($text)"
        }
        if (attributes["bold"]?.jsonPrimitive?.booleanOrNull == true) {
            result = "**$result**"
        }
        if (attributes["italic"]?.jsonPrimitive?.booleanOrNull == true) {
            result = "*$result*"
        }
        if (attributes["underline"]?.jsonPrimitive?.booleanOrNull == true) {
            result = "<u>$result</u>"
        }
        return result
    }

    private fun appendEmbeddedPlaceholder(
        builder: StringBuilder,
        label: String,
        embeddedObject: JsonObject,
    ) {
        val identifier = embeddedObject["identifier"]?.jsonPrimitive?.contentOrNull
        appendBlock(builder, "[$label: ${identifier ?: "missing identifier"}]")
    }

    private fun appendMarkdownObject(
        builder: StringBuilder,
        embeddedObject: JsonObject,
    ) {
        val markdown = sequenceOf("markdown", "text", "contents")
            .mapNotNull { key -> embeddedObject[key]?.asStringOrNull() }
            .firstOrNull()
        appendBlock(builder, markdown ?: "[Markdown object]")
    }

    private fun appendBlock(
        builder: StringBuilder,
        text: String,
    ) {
        if (builder.isNotEmpty() && !builder.endsWithLineBreak()) {
            builder.append('\n')
        }
        builder.append(text)
        if (!builder.endsWithLineBreak()) {
            builder.append('\n')
        }
    }

    private fun StringBuilder.endsWithLineBreak(): Boolean =
        isNotEmpty() && last() == '\n'
}

private data class DayOneRichTextConversion(
    val markdown: String,
    val converted: Boolean,
    val fallbackUsed: Boolean,
    val photosReferenced: Int = 0,
    val audiosReferenced: Int = 0,
    val videosReferenced: Int = 0,
    val pdfsReferenced: Int = 0,
    val unsupportedEmbeddedObjects: Int = 0,
)

private fun JsonElement.asStringOrNull(): String? =
    when (this) {
        is JsonPrimitive -> contentOrNull
        else -> null
    }

private fun jsonEscape(value: String): String =
    buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
