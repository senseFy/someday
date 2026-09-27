@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.importing.dayone

import saien.someday.data.export.ExportedLocation
import saien.someday.data.export.ExportedNote
import saien.someday.data.export.ExportedNotebook
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataImportException
import saien.someday.data.export.LocalDataImportSummary
import saien.someday.domain.media.SomedayAssetUri
import saien.someday.domain.notes.noteCalendarDate
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class DayOneImportService(
    /** Null means text-only conversion. Production supplies the coordinated media store. */
    private val importPhoto: ((ByteArray, String) -> SomedayAssetUri?)? = null,
    /** Parsed Day One content is written only through the System V3 workspace DAG. */
    private val authoritativeImporter: (LocalDataExportDocument) -> LocalDataImportSummary,
) {
    fun importArchive(
        archiveBytes: ByteArray,
        fallbackJournalTitle: String = "Day One",
    ): DayOneImportSummary {
        val archive = DayOneArchiveReader.open(archiveBytes)
        return importThroughWorkspaceDag(archive.documents(fallbackJournalTitle), DayOneMediaResolver(archive, importPhoto))
    }

    fun importDocuments(documents: List<DayOneJsonDocument>): DayOneImportSummary {
        return importThroughWorkspaceDag(documents, DayOneMediaResolver(null, null))
    }

    private fun importThroughWorkspaceDag(
        documents: List<DayOneJsonDocument>,
        mediaResolver: DayOneMediaResolver,
    ): DayOneImportSummary {
        require(documents.isNotEmpty()) { "Day One import requires at least one JSON document." }
        // Validate every journal before any media or note writes. Never leak source text in errors.
        val decodedDocuments = documents.map { document ->
            val decoded = try {
                json.decodeFromString(DayOneExportDocument.serializer(), document.json)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid Day One journal JSON.")
            }
            require(decoded.metadata?.version == null || decoded.metadata.version == "1.0") {
                "Unsupported Day One export version."
            }
            decoded.entries.forEach { entry ->
                require(entry.uuid.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "Invalid Day One entry identifier." }
                require(parseInstantOrNull(entry.creationDate.orEmpty()) != null) { "Invalid Day One creation date." }
                require(entry.modifiedDate == null || parseInstantOrNull(entry.modifiedDate) != null) {
                    "Invalid Day One modification date."
                }
                require(entry.timeZone.isNullOrBlank() || runCatching { TimeZone.of(entry.timeZone) }.isSuccess) {
                    "Invalid Day One time zone."
                }
            }
            decoded
        }
        val identifiers = decodedDocuments.flatMap { it.entries }.map { it.uuid }
        require(identifiers.size <= 100_000 && identifiers.distinct().size == identifiers.size) {
            "Day One export contains duplicate entry identifiers or more than 100000 entries."
        }
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
            val decoded = decodedDocuments[index]
            val entryTimes = decoded.entries.map { Instant.parse(it.creationDate!!) }
            val notebook = notebooksByTitle.getOrPut(titleKey) {
                val created = entryTimes.minOrNull() ?: Instant.fromEpochMilliseconds(0)
                ExportedNotebook(
                    id = "dayone-journal-${titleKey.hashCode().toUInt().toString(16)}",
                    title = title,
                    sortOrder = index.toLong() + 1L,
                    createdAt = created.toString(),
                    updatedAt = created.toString(),
                )
            }
            decoded.entries.forEach { entry ->
                val createdAt = Instant.parse(entry.creationDate!!)
                val updatedAt = entry.modifiedDate?.let(Instant::parse) ?: createdAt
                val media = mediaResolver.forEntry(entry, document.archiveDirectory)
                val conversion = DayOneRichTextMarkdownConverter.convert(entry.richText, entry.text.orEmpty(), media::render)
                val markdown = media.finish(conversion.markdown)
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
                photosReferenced += media.count("photo")
                audiosReferenced += media.count("audio")
                videosReferenced += media.count("video")
                pdfsReferenced += media.count("pdf")
                if (entry.weather != null) weatherFound++
                unsupportedEmbeddedObjects += conversion.unsupportedEmbeddedObjects
            }
        }
        val exportedAt = notes.maxOfOrNull { it.updatedAt }
            ?: Instant.fromEpochMilliseconds(0).toString()
        var completed = true
        val imported = try {
            authoritativeImporter(
                LocalDataExportDocument(
                    exportedAt = exportedAt,
                    notebooks = notebooksByTitle.values.toList(),
                    notes = notes.distinctBy { it.id },
                ),
            )
        } catch (failure: LocalDataImportException) {
            completed = false
            failure.completed
        }
        return DayOneImportSummary(
            completed = completed,
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
            photosImported = mediaResolver.imported,
            photosMissing = mediaResolver.missing,
            photosUnresolved = mediaResolver.unresolved,
            photosRejected = mediaResolver.rejected,
        )
    }

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
    val archiveDirectory: String = "",
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
    val photosImported: Int = 0,
    val photosMissing: Int = 0,
    val photosUnresolved: Int = 0,
    val photosRejected: Int = 0,
    val completed: Boolean = true,
) {
    val importedNotes: Int = notesCreated + notesUpdated
}

@Serializable
private data class DayOneExportDocument(
    val entries: List<DayOneEntry>,
    val metadata: DayOneExportMetadata? = null,
)

@Serializable
private data class DayOneExportMetadata(val version: String? = null)

@Serializable
internal data class DayOneEntry(
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
internal data class DayOneLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val administrativeArea: String? = null,
    val country: String? = null,
    val localityName: String? = null,
    val placeName: String? = null,
)

@Serializable
internal data class DayOneMedia(
    val identifier: String? = null,
    val type: String? = null,
    val md5: String? = null,
    val orderInEntry: Int = 0,
)

private object DayOneRichTextMarkdownConverter {
    fun convert(
        richText: String?,
        fallbackText: String,
        renderMedia: (String, String?) -> String,
    ): DayOneRichTextConversion {
        fun fallback(): DayOneRichTextConversion {
            require(fallbackText.isNotBlank()) { "Invalid or unsupported Day One rich text without a text fallback." }
            return DayOneRichTextConversion(fallbackText.trim(), converted = false, fallbackUsed = true)
        }
        if (richText.isNullOrBlank()) {
            return DayOneRichTextConversion(
                markdown = fallbackText.trim(),
                converted = false,
                fallbackUsed = false,
            )
        }
        val root = runCatching { json.parseToJsonElement(richText).jsonObject }.getOrNull()
            ?: return fallback()
        val meta = root["meta"]
        if (meta != null && meta !is JsonObject) return fallback()
        val version = meta?.get("version")
        if (version != null && (version !is JsonPrimitive || version.isString || version.intOrNull != 1)) return fallback()
        val contents = root["contents"] as? JsonArray ?: return fallback()
        if (contents.any { node ->
                node !is JsonObject || (node["text"] != null && node["text"]?.asStringOrNull() == null) ||
                    (node["embeddedObjects"] != null &&
                        (node["embeddedObjects"] !is JsonArray || (node["embeddedObjects"] as JsonArray).any { it !is JsonObject }))
            }) return fallback()

        val builder = StringBuilder()
        var unsupportedEmbeddedObjects = 0

        contents.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val text = item["text"]?.asStringOrNull()
            if (text != null) {
                appendText(builder, text, item["attributes"] as? JsonObject)
            }
            val embeddedObjects = item["embeddedObjects"] as? JsonArray
            embeddedObjects?.forEach { embedded ->
                val embeddedObject = embedded as? JsonObject ?: return@forEach
                when (val type = embeddedObject["type"]?.asStringOrNull().orEmpty()) {
                    "photo", "audio", "video", "pdf" ->
                        appendBlock(builder, renderMedia(type, embeddedObject["identifier"]?.asStringOrNull()))
                    "markdown" -> appendMarkdownObject(builder, embeddedObject)
                    "horizontalRuleLine" -> appendBlock(builder, "---")
                    else -> {
                        unsupportedEmbeddedObjects += 1
                        val safeType = type.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,64}")) } ?: "unknown"
                        appendBlock(builder, "[Unsupported Day One object: $safeType]")
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
                unsupportedEmbeddedObjects = unsupportedEmbeddedObjects,
            )
        } else {
            DayOneRichTextConversion(
                markdown = converted,
                converted = true,
                fallbackUsed = false,
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
        text.split('\n').forEachIndexed { index, part ->
            if (index > 0) {
                builder.append('\n')
            }
            if (prefix != null && part.isNotBlank() && (builder.isEmpty() || builder.endsWithLineBreak())) {
                builder.append(prefix)
            }
            builder.append(applyInlineFormatting(part, attributes))
        }
    }

    private fun linePrefix(line: JsonObject?): String? {
        if (line == null) {
            return null
        }
        (line["header"] as? JsonPrimitive)?.intOrNull?.let { level ->
            if (level in 1..6) return "#".repeat(level) + " "
        }
        val indent = "  ".repeat(((line["indentLevel"] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(1, 17) - 1)
        val checked = (line["checked"] as? JsonPrimitive)?.booleanOrNull
        if (checked != null) {
            return "$indent- [${if (checked) "x" else " "}] "
        }
        return when (line["listStyle"]?.asStringOrNull()) {
            "bulleted" -> "$indent- "
            "numbered" -> "${indent}1. "
            else -> null
        }
    }

    private fun applyInlineFormatting(
        text: String,
        attributes: JsonObject?,
    ): String {
        if (attributes == null || text.isBlank()) {
            return text
        }
        val trimmed = text.trim()
        var result = trimmed
        if ((attributes["autolink"] as? JsonPrimitive)?.booleanOrNull == true &&
            (trimmed.startsWith("https://") || trimmed.startsWith("http://"))
        ) {
            result = "[$trimmed](${trimmed.replace("(", "%28").replace(")", "%29")})"
        }
        if ((attributes["bold"] as? JsonPrimitive)?.booleanOrNull == true) {
            result = "**$result**"
        }
        if ((attributes["italic"] as? JsonPrimitive)?.booleanOrNull == true) {
            result = "*$result*"
        }
        if ((attributes["underline"] as? JsonPrimitive)?.booleanOrNull == true) {
            result = "<u>$result</u>"
        }
        return text.takeWhile(Char::isWhitespace) + result + text.takeLastWhile(Char::isWhitespace)
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
    val unsupportedEmbeddedObjects: Int = 0,
)

private fun JsonElement.asStringOrNull(): String? =
    when (this) {
        is JsonPrimitive -> takeIf { isString }?.contentOrNull
        else -> null
    }

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
