package saien.someday.data.media

import okio.BufferedSource
import saien.someday.domain.media.MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT
import saien.someday.domain.media.MAX_MEDIA_ASSET_PIXEL_COUNT
import saien.someday.domain.media.isSafeOriginalFileName

const val MAX_SELECTED_IMAGE_BYTE_COUNT: Long = 32L * 1_024L * 1_024L
const val MAX_SELECTED_IMAGE_PIXEL_COUNT: Long = 200_000_000L
const val NORMALIZED_JPEG_QUALITY: Int = 88
const val MIN_NORMALIZED_LONG_EDGE_PIXELS: Int = 2_048

data class SelectedImageImportRequest(
    val originalFileName: String? = null,
    val maxSourceBytes: Long = MAX_SELECTED_IMAGE_BYTE_COUNT,
    val maxSourcePixelCount: Long = MAX_SELECTED_IMAGE_PIXEL_COUNT,
) {
    init {
        require(originalFileName == null || isSafeOriginalFileName(originalFileName)) {
            "Original file name must be a safe basename of at most 255 characters."
        }
        require(maxSourceBytes in 1L..MAX_SELECTED_IMAGE_BYTE_COUNT) {
            "Selected image byte limit is outside the supported range."
        }
        require(maxSourcePixelCount in 1L..MAX_SELECTED_IMAGE_PIXEL_COUNT) {
            "Selected image pixel limit is outside the supported range."
        }
    }
}

data class MediaImageNormalizationRequest(
    val sourceInspection: MediaAssetInspection,
    val maxOutputBytes: Long = MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT,
    val maxOutputPixelCount: Long = MAX_MEDIA_ASSET_PIXEL_COUNT,
    val minimumLongEdgePixels: Int = MIN_NORMALIZED_LONG_EDGE_PIXELS,
    val jpegQuality: Int = NORMALIZED_JPEG_QUALITY,
) {
    init {
        require(maxOutputBytes == MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT)
        require(maxOutputPixelCount == MAX_MEDIA_ASSET_PIXEL_COUNT)
        require(minimumLongEdgePixels > 0)
        require(jpegQuality in 1..100)
    }
}

fun interface MediaImageNormalizer {
    /** Returns one complete final JPEG or PNG candidate. */
    fun normalize(
        source: BufferedSource,
        request: MediaImageNormalizationRequest,
    ): ByteArray
}

enum class SelectedImageImportFailureReason {
    SourceTooLarge,
    SourcePixelLimitExceeded,
    UnsupportedFormat,
    AnimatedImage,
    InvalidEncoding,
    NormalizationFailed,
    NormalizationWouldViolateQualityBounds,
}

class SelectedImageImportException(
    val reason: SelectedImageImportFailureReason,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class MediaImageNormalizationException(
    val violatesQualityBounds: Boolean = false,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
