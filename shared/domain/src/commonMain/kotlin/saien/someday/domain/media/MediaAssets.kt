package saien.someday.domain.media

import kotlin.jvm.JvmInline
import kotlin.time.Instant

const val SOMEDAY_ASSET_URI_SCHEME: String = "someday-asset"
const val MAX_MEDIA_ASSET_PIXEL_COUNT: Long = 12_000_000L

private const val MEDIA_ASSET_ID_HEX_LENGTH: Int = 64
private const val MAX_MEDIA_TYPE_LENGTH: Int = 127
private const val MAX_ORIGINAL_FILE_NAME_LENGTH: Int = 255
private val canonicalMediaTypePattern = Regex(
    "^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$",
)

/** A canonical opaque 256-bit asset identity, stable across devices and storage locations. */
@JvmInline
value class MediaAssetId private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        fun fromCanonicalValue(value: String): MediaAssetId {
            require(isCanonicalMediaAssetId(value)) {
                "A media asset id must be 64 lowercase hexadecimal characters."
            }
            return MediaAssetId(value)
        }

        fun parseOrNull(value: String): MediaAssetId? =
            value.takeIf(::isCanonicalMediaAssetId)?.let(::fromCanonicalValue)
    }
}

/** Canonical Markdown-safe reference to an app-owned media asset. */
data class SomedayAssetUri(
    val assetId: MediaAssetId,
) {
    override fun toString(): String = "$SOMEDAY_ASSET_URI_SCHEME://${assetId.value}"

    companion object {
        fun parseOrNull(value: String): SomedayAssetUri? {
            val prefix = "$SOMEDAY_ASSET_URI_SCHEME://"
            if (!value.startsWith(prefix)) return null
            return MediaAssetId.parseOrNull(value.removePrefix(prefix))?.let(::SomedayAssetUri)
        }
    }
}

/** Immutable descriptive metadata for bytes identified by [id]. */
data class MediaAssetMetadata(
    val id: MediaAssetId,
    val byteSize: Long,
    val mediaType: String,
    val originalFileName: String?,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val createdAt: Instant,
) {
    val uri: SomedayAssetUri = SomedayAssetUri(id)
    val decodedPixelCount: Long = pixelWidth.toLong() * pixelHeight

    init {
        require(byteSize > 0L) { "A media asset must contain at least one byte." }
        require(canonicalMediaTypeOrNull(mediaType) == mediaType) {
            "A media asset type must be a canonical lowercase type/subtype without parameters."
        }
        require(originalFileName == null || isSafeOriginalFileName(originalFileName)) {
            "An original media file name must be a safe basename of at most 255 characters."
        }
        require(pixelWidth > 0 && pixelHeight > 0) { "Image dimensions must be positive." }
        require(decodedPixelCount <= MAX_MEDIA_ASSET_PIXEL_COUNT) {
            "Decoded image dimensions exceed the supported pixel count."
        }
    }
}

fun canonicalMediaTypeOrNull(value: String): String? =
    value
        .takeIf { it.length in 3..MAX_MEDIA_TYPE_LENGTH }
        ?.takeIf { it == it.lowercase() }
        ?.takeIf(canonicalMediaTypePattern::matches)

fun isSafeOriginalFileName(value: String): Boolean =
    value.isNotBlank() &&
        value == value.trim() &&
        value.length <= MAX_ORIGINAL_FILE_NAME_LENGTH &&
        value.none { it == '/' || it == '\\' || it.isISOControl() }

/**
 * Finds opaque asset references without interpreting Markdown presentation.
 * System V3 calls this only for the exact immutable entity versions selected by
 * an outbox batch or checkpoint. A current projection alone is not sufficient
 * to infer the reachability requirements of pending historical versions.
 */
fun findSomedayAssetIds(text: String): Set<MediaAssetId> {
    val prefix = "$SOMEDAY_ASSET_URI_SCHEME://"
    val result = linkedSetOf<MediaAssetId>()
    var searchFrom = 0
    while (searchFrom < text.length) {
        val prefixStart = text.indexOf(prefix, searchFrom)
        if (prefixStart < 0) break
        val idStart = prefixStart + prefix.length
        val idEnd = idStart + MEDIA_ASSET_ID_HEX_LENGTH
        if (idEnd <= text.length) {
            val candidate = text.substring(idStart, idEnd)
            val hasHexContinuation = text.getOrNull(idEnd)?.let(::isLowerHexDigit) == true
            if (!hasHexContinuation) {
                MediaAssetId.parseOrNull(candidate)?.let(result::add)
            }
        }
        searchFrom = idStart.coerceAtMost(text.length)
    }
    return result
}

private fun isCanonicalMediaAssetId(value: String): Boolean =
    value.length == MEDIA_ASSET_ID_HEX_LENGTH && value.all(::isLowerHexDigit)

private fun isLowerHexDigit(value: Char): Boolean = value in '0'..'9' || value in 'a'..'f'
