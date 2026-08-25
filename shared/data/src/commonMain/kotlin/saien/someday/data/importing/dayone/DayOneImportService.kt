@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.importing.dayone

import saien.someday.data.export.ExportedLocation
import saien.someday.data.export.ExportedNote
import saien.someday.data.export.ExportedNotebook
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataImportSummary
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
    /** Parsed Day One content is written only through the System V3 workspace DAG. */
    private val authoritativeImporter: (LocalDataExportDocument) -> LocalDataImportSummary,
) {
    fun importArchive(
        archiveBytes: ByteArray,
        fallbackJournalTitle: String = "Day One",
    ): DayOneImportSummary =
        importDocuments(DayOneArchiveReader.readJsonDocuments(archiveBytes, fallbackJournalTitle))

    fun importDocuments(documents: List<DayOneJsonDocument>): DayOneImportSummary {
        require(documents.isNotEmpty()) { "Day One import requires at least one JSON document." }
        return importThroughWorkspaceDag(documents)
    }

    private fun importThroughWorkspaceDag(documents: List<DayOneJsonDocument>): DayOneImportSummary {
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
                val location = entry.location?.toExportedLocation(createdAt)
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
                    location = location,
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
        val imported = authoritativeImporter(
            LocalDataExportDocument(
                exportedAt = exportedAt,
                notebooks = notebooksByTitle.values.toList(),
                notes = notes.distinctBy { it.id },
            ),
        )
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

    private fun parseInstantOrNow(value: String?): Instant =
        value?.let(::parseInstantOrNull) ?: kotlin.time.Clock.System.now()

    private fun parseInstantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    private fun DayOneLocation.toExportedLocation(createdAt: Instant): ExportedLocation? {
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
        return ExportedLocation(
            latitude = latitude,
            longitude = longitude,
            placeText = place,
            capturedAt = createdAt.toString(),
        )
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

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
