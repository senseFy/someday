package saien.someday.data.importing.dayone

import okio.Buffer
import okio.ByteString.Companion.toByteString
import saien.someday.data.media.MAX_SELECTED_IMAGE_BYTE_COUNT
import saien.someday.data.media.MAX_SELECTED_IMAGE_PIXEL_COUNT
import saien.someday.data.media.StaticImageMediaAssetInspector
import saien.someday.domain.media.SomedayAssetUri

/** Resolves only the identifier -> metadata -> content hash chain. Never guesses by order or filename. */
internal class DayOneMediaResolver(
    private val archive: DayOneArchive?,
    private val importPhoto: ((ByteArray, String) -> SomedayAssetUri?)?,
) {
    var imported = 0
        private set
    var missing = 0
        private set
    var unresolved = 0
        private set
    var rejected = 0
        private set

    private enum class Status { Imported, Missing, Unresolved, Rejected }
    private data class Photo(val status: Status, val uri: SomedayAssetUri? = null)
    private val photosByPath = mutableMapOf<String, Photo>()

    fun forEntry(entry: DayOneEntry, directory: String): EntryMedia = EntryMedia(entry, directory)

    inner class EntryMedia(entry: DayOneEntry, private val directory: String) {
        private val records = listOf(
            "photo" to entry.photos, "audio" to entry.audios, "video" to entry.videos, "pdf" to entry.pdfs,
        ).flatMap { (kind, media) -> media.map { kind to it } }
        private val rendered = linkedMapOf<Pair<String, String?>, String>()

        fun count(kind: String): Int = rendered.keys.count { it.first == kind }

        fun render(kind: String, identifier: String?): String = rendered.getOrPut(kind to identifier) {
            val label = when (kind) {
                "photo" -> "Photo"
                "audio" -> "Audio"
                "video" -> "Video"
                else -> "PDF"
            }
            val safeId = identifier?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,128}")) } ?: "missing identifier"
            if (kind != "photo") return@getOrPut "[$label: $safeId — unsupported attachment]"
            val candidates = records.filter { it.second.identifier == identifier }
            val photo = if (identifier == null || candidates.size != 1 || candidates.single().first != kind) {
                Photo(Status.Unresolved)
            } else {
                resolvePhoto(candidates.single().second, directory)
            }
            when (photo.status) {
                Status.Imported -> {
                    imported++
                    "![Photo](${photo.uri})"
                }
                Status.Missing -> {
                    missing++
                    "[Photo: $safeId — file not included]"
                }
                Status.Unresolved -> {
                    unresolved++
                    "[Photo: $safeId — unresolved reference]"
                }
                Status.Rejected -> {
                    rejected++
                    "[Photo: $safeId — unsupported or invalid image]"
                }
            }
        }

        fun finish(markdown: String): String {
            val body = rewriteDayOneLinks(markdown) { identifier ->
                val kinds = records.filter { it.second.identifier == identifier }.map { it.first }.distinct()
                render(kinds.singleOrNull() ?: "photo", identifier)
            }
            val remaining = records.sortedBy { it.second.orderInEntry }
                .filter { (kind, media) -> kind to media.identifier !in rendered }
                .map { (kind, media) -> render(kind, media.identifier) }
            return (listOf(body) + remaining).filter { it.isNotBlank() }.joinToString("\n\n")
        }
    }

    private fun resolvePhoto(media: DayOneMedia, directory: String): Photo {
        val md5 = media.md5?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
            ?: return Photo(Status.Unresolved)
        val type = media.type?.lowercase() ?: return Photo(Status.Unresolved)
        if (type !in setOf("jpeg", "jpg", "png", "webp")) return Photo(Status.Rejected)
        val path = listOf(directory, "photos", "$md5.$type").filter(String::isNotEmpty).joinToString("/")
        return photosByPath.getOrPut(path.lowercase()) {
            if (archive == null || !archive.contains(path)) return@getOrPut Photo(Status.Missing)
            val bytes = try {
                archive.read(path, MAX_SELECTED_IMAGE_BYTE_COUNT).also { bytes ->
                    require(bytes.toByteString().md5().hex() == md5)
                    StaticImageMediaAssetInspector.inspect(
                        Buffer().write(bytes), bytes.size.toLong(),
                        "image/${if (type == "jpg") "jpeg" else type}", MAX_SELECTED_IMAGE_PIXEL_COUNT,
                    )
                }
            } catch (_: Exception) {
                return@getOrPut Photo(Status.Rejected)
            }
            // Storage/authority failures must propagate, not be disguised as missing source media.
            val uri = importPhoto?.invoke(bytes, "$md5.$type")
            if (uri == null) Photo(Status.Rejected) else Photo(Status.Imported, uri)
        }
    }
}

/** Day One inline attachment links only; code spans/fences and unrelated links remain literal. */
private fun rewriteDayOneLinks(markdown: String, render: (String) -> String): String {
    val tokens = Regex("`+|\\\\.|!?\\[(?:\\\\.|[^\\]\\\\])*]\\(dayone-moment://([A-Za-z0-9-]{1,128})\\)")
    val fenceStart = Regex("(?m)^ {0,3}(`{3,}|~{3,})[^\\n]*$")
    val fences = mutableListOf<IntRange>()
    var cursor = 0
    while (cursor < markdown.length) {
        val opening = fenceStart.find(markdown, cursor) ?: break
        val fence = opening.groupValues[1]
        val closing = Regex("(?m)^ {0,3}${fence.first()}{${fence.length},}[ \\t\\r]*$")
            .find(markdown, opening.range.last + 1)
        val end = closing?.range?.last ?: markdown.lastIndex
        fences += opening.range.first..end
        cursor = end + 1
    }
    val result = StringBuilder()
    var literalUntil = 0
    cursor = 0
    for (token in tokens.findAll(markdown)) {
        val start = token.range.first
        val end = token.range.last + 1
        if (start < literalUntil || fences.any { start in it }) continue
        if (token.value.startsWith('`')) {
            val closing = Regex("`+").findAll(markdown, end).firstOrNull { it.value.length == token.value.length }
            if (closing != null) literalUntil = closing.range.last + 1
            continue
        }
        val identifier = token.groups[1]?.value ?: continue
        val replacement = render(identifier)
        result.append(markdown.substring(cursor, start))
        // Someday's Markdown preview recognizes standalone image lines, not inline images.
        if (replacement.startsWith("![") && start > 0 && markdown[start - 1] != '\n') result.append('\n')
        result.append(replacement)
        if (replacement.startsWith("![") && end < markdown.length && markdown[end] != '\n') result.append('\n')
        cursor = end
    }
    return result.append(markdown.substring(cursor)).toString()
}
