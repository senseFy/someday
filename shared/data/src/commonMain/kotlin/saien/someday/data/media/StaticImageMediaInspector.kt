package saien.someday.data.media

import okio.BufferedSource
import saien.someday.domain.media.MAX_MEDIA_ASSET_PIXEL_COUNT

const val DEFAULT_MAX_DECODED_PIXEL_COUNT: Long = 12_000_000L
const val MAX_DECODED_PIXEL_COUNT_BOUND: Long = MAX_MEDIA_ASSET_PIXEL_COUNT

private const val IMAGE_JPEG = "image/jpeg"
private const val IMAGE_PNG = "image/png"
private const val IMAGE_WEBP = "image/webp"
private const val MAX_CONTAINER_CHUNKS = 10_000
private const val STATIC_IMAGE_SIGNATURE_BYTES = 12L

data class MediaAssetInspection(
    val mediaType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
) {
    val decodedPixelCount: Long = pixelWidth.toLong() * pixelHeight

    init {
        require(pixelWidth > 0 && pixelHeight > 0) { "Image dimensions must be positive." }
    }
}

/**
 * Inspects encoded media before it is promoted into immutable storage.
 * Implementations must be bounded and must not decode untrusted pixels.
 */
fun interface MediaAssetInspector {
    fun inspect(
        source: BufferedSource,
        encodedByteSize: Long,
        declaredMediaType: String?,
        maxDecodedPixelCount: Long,
    ): MediaAssetInspection
}

enum class MediaAssetInspectionFailureReason {
    UnsupportedFormat,
    AnimatedImage,
    PixelLimitExceeded,
    InvalidEncoding,
}

class MediaAssetInspectionException(
    message: String,
    val reason: MediaAssetInspectionFailureReason = MediaAssetInspectionFailureReason.InvalidEncoding,
) : LocalMediaAssetStoreException(message)

/** Fail-closed metadata inspector for the supported static raster formats. */
object StaticImageMediaAssetInspector : MediaAssetInspector {
    override fun inspect(
        source: BufferedSource,
        encodedByteSize: Long,
        declaredMediaType: String?,
        maxDecodedPixelCount: Long,
    ): MediaAssetInspection {
        require(encodedByteSize > 0L) { "Encoded byte size must be positive." }
        require(maxDecodedPixelCount in 1L..MAX_SELECTED_IMAGE_PIXEL_COUNT) {
            "Decoded pixel bound is outside the supported range."
        }
        val detectedMediaType = detectStaticImageMediaType(
            source.peek().readByteArray(minOf(encodedByteSize, STATIC_IMAGE_SIGNATURE_BYTES)),
        ) ?: throw MediaAssetInspectionException(
            reason = MediaAssetInspectionFailureReason.UnsupportedFormat,
            message = "Unsupported media type. Only static JPEG, PNG, and WebP images are accepted.",
        )
        if (declaredMediaType != null && declaredMediaType != detectedMediaType) {
            throw MediaAssetInspectionException("Declared media type does not match the encoded image signature.")
        }
        val dimensions = when (detectedMediaType) {
            IMAGE_JPEG -> inspectJpeg(source, encodedByteSize)
            IMAGE_PNG -> inspectPng(source, encodedByteSize)
            IMAGE_WEBP -> inspectWebP(source, encodedByteSize)
            else -> error("Detected unsupported static image type.")
        }
        val pixelCount = dimensions.width.toLong() * dimensions.height
        if (pixelCount > maxDecodedPixelCount) {
            throw MediaAssetInspectionException(
                reason = MediaAssetInspectionFailureReason.PixelLimitExceeded,
                message = "Decoded image size $pixelCount exceeds the configured $maxDecodedPixelCount-pixel limit.",
            )
        }
        return MediaAssetInspection(
            mediaType = detectedMediaType,
            pixelWidth = dimensions.width,
            pixelHeight = dimensions.height,
        )
    }

    private fun inspectPng(source: BufferedSource, encodedByteSize: Long): Dimensions {
        if (encodedByteSize < 45L || !source.readByteArray(8L).contentEquals(PNG_SIGNATURE)) {
            throw MediaAssetInspectionException("Declared PNG does not have a valid PNG signature.")
        }
        var consumed = 8L
        var dimensions: Dimensions? = null
        var imageDataBytes = 0L
        var foundEnd = false
        repeat(MAX_CONTAINER_CHUNKS) {
            if (foundEnd) return@repeat
            if (encodedByteSize - consumed < 12L) {
                throw MediaAssetInspectionException("PNG container is truncated.")
            }
            val length = source.readUnsignedIntBigEndian()
            val type = source.readInt()
            consumed += 8L
            if (length > encodedByteSize - consumed - 4L) {
                throw MediaAssetInspectionException("PNG chunk length exceeds the encoded file size.")
            }
            when (type) {
                PNG_IHDR -> {
                    if (dimensions != null || consumed != 16L || length != 13L) {
                        throw MediaAssetInspectionException("PNG must contain one leading IHDR chunk.")
                    }
                    val width = source.readUnsignedIntBigEndian().toPositiveDimension("PNG width")
                    val height = source.readUnsignedIntBigEndian().toPositiveDimension("PNG height")
                    val bitDepth = source.readUnsignedByte()
                    val colorType = source.readUnsignedByte()
                    val compression = source.readUnsignedByte()
                    val filter = source.readUnsignedByte()
                    val interlace = source.readUnsignedByte()
                    if (!validPngColorDepth(bitDepth, colorType) || compression != 0 || filter != 0 || interlace !in 0..1) {
                        throw MediaAssetInspectionException("PNG IHDR uses unsupported or invalid encoding parameters.")
                    }
                    dimensions = Dimensions(width, height)
                }
                PNG_ACTL -> throw MediaAssetInspectionException(
                    message = "Animated PNG images are not supported.",
                    reason = MediaAssetInspectionFailureReason.AnimatedImage,
                )
                PNG_IDAT -> {
                    if (dimensions == null) throw MediaAssetInspectionException("PNG image data precedes IHDR.")
                    imageDataBytes += length
                    source.skip(length)
                }
                PNG_IEND -> {
                    if (length != 0L) throw MediaAssetInspectionException("PNG IEND chunk is invalid.")
                    foundEnd = true
                }
                else -> source.skip(length)
            }
            if (type == PNG_IHDR) {
                // IHDR's 13-byte payload was consumed while validating it.
            } else if (type == PNG_IEND) {
                // IEND has no payload.
            }
            source.skip(4L) // CRC; format decoders perform full CRC validation.
            consumed += length + 4L
        }
        if (!foundEnd) throw MediaAssetInspectionException("PNG has too many chunks or no IEND marker.")
        if (consumed != encodedByteSize) {
            throw MediaAssetInspectionException("PNG contains bytes after its IEND marker.")
        }
        if (imageDataBytes == 0L) throw MediaAssetInspectionException("PNG contains no image data.")
        return dimensions ?: throw MediaAssetInspectionException("PNG is missing image dimensions.")
    }

    private fun inspectJpeg(source: BufferedSource, encodedByteSize: Long): Dimensions {
        if (encodedByteSize < 4L) throw MediaAssetInspectionException("JPEG container is truncated.")
        val endsWithEoi = source.peek().let { tail ->
            try {
                tail.skip(encodedByteSize - 2L)
                tail.readUnsignedByte() == 0xff && tail.readUnsignedByte() == JPEG_EOI
            } finally {
                tail.close()
            }
        }
        if (!endsWithEoi) {
            throw MediaAssetInspectionException("JPEG is truncated or contains bytes after its EOI marker.")
        }
        if (source.readUnsignedByte() != 0xff || source.readUnsignedByte() != JPEG_SOI) {
            throw MediaAssetInspectionException("Declared JPEG does not have a valid JPEG signature.")
        }
        repeat(MAX_CONTAINER_CHUNKS) {
            var prefix = source.readUnsignedByte()
            while (prefix != 0xff) prefix = source.readUnsignedByte()
            var marker = source.readUnsignedByte()
            while (marker == 0xff) marker = source.readUnsignedByte()
            when {
                marker == 0x00 -> throw MediaAssetInspectionException("JPEG contains an invalid escaped marker before scan data.")
                marker == JPEG_EOI || marker == JPEG_SOS ->
                    throw MediaAssetInspectionException("JPEG scan begins before a supported frame header.")
                marker == JPEG_SOI || marker == JPEG_TEM || marker in JPEG_RST0..JPEG_RST7 -> Unit
                else -> {
                    val segmentLength = source.readUnsignedShortBigEndian()
                    if (segmentLength < 2) throw MediaAssetInspectionException("JPEG segment length is invalid.")
                    var payloadLength = segmentLength - 2L
                    if (marker in SUPPORTED_JPEG_SOF_MARKERS) {
                        if (payloadLength < 6L) throw MediaAssetInspectionException("JPEG frame header is truncated.")
                        val precision = source.readUnsignedByte()
                        val height = source.readUnsignedShortBigEndian().toPositiveDimension("JPEG height")
                        val width = source.readUnsignedShortBigEndian().toPositiveDimension("JPEG width")
                        val componentCount = source.readUnsignedByte()
                        payloadLength -= 6L
                        if (precision !in 8..12 || componentCount !in 1..4 || payloadLength != componentCount * 3L) {
                            throw MediaAssetInspectionException("JPEG frame header is unsupported or malformed.")
                        }
                        return Dimensions(width, height)
                    }
                    source.skip(payloadLength)
                }
            }
        }
        throw MediaAssetInspectionException("JPEG has too many segments or no supported frame header.")
    }

    private fun inspectWebP(source: BufferedSource, encodedByteSize: Long): Dimensions {
        if (encodedByteSize < 20L || source.readInt() != WEBP_RIFF) {
            throw MediaAssetInspectionException("Declared WebP does not have a RIFF signature.")
        }
        val riffSize = source.readUnsignedIntLittleEndian()
        if (source.readInt() != WEBP_SIGNATURE || riffSize + 8L != encodedByteSize) {
            throw MediaAssetInspectionException("WebP RIFF size or signature is invalid.")
        }
        var consumed = 12L
        var canvas: Dimensions? = null
        var payload: Dimensions? = null
        var payloadCount = 0
        var chunkCount = 0
        while (consumed < encodedByteSize) {
            if (++chunkCount > MAX_CONTAINER_CHUNKS || encodedByteSize - consumed < 8L) {
                throw MediaAssetInspectionException("WebP has too many chunks or a truncated chunk header.")
            }
            val type = source.readInt()
            val length = source.readUnsignedIntLittleEndian()
            consumed += 8L
            val paddedLength = length + (length and 1L)
            if (paddedLength > encodedByteSize - consumed) {
                throw MediaAssetInspectionException("WebP chunk length exceeds the RIFF container.")
            }
            when (type) {
                WEBP_VP8X -> {
                    if (canvas != null || length != 10L) {
                        throw MediaAssetInspectionException("WebP extended header is invalid.")
                    }
                    val bytes = source.readByteArray(length)
                    if ((bytes[0].toInt() and WEBP_ANIMATION_FLAG) != 0) {
                        throw MediaAssetInspectionException(
                            message = "Animated WebP images are not supported.",
                            reason = MediaAssetInspectionFailureReason.AnimatedImage,
                        )
                    }
                    canvas = Dimensions(
                        width = bytes.readUnsigned24LittleEndian(4) + 1,
                        height = bytes.readUnsigned24LittleEndian(7) + 1,
                    )
                }
                WEBP_VP8 -> {
                    if (++payloadCount > 1 || length <= 10L) {
                        throw MediaAssetInspectionException("WebP contains an invalid lossy image chunk.")
                    }
                    val bytes = source.readByteArray(10L)
                    if ((bytes[0].toInt() and 1) != 0 ||
                        bytes[3] != 0x9d.toByte() || bytes[4] != 0x01.toByte() || bytes[5] != 0x2a.toByte()
                    ) {
                        throw MediaAssetInspectionException("WebP lossy frame header is invalid.")
                    }
                    payload = Dimensions(
                        width = bytes.readUnsigned16LittleEndian(6) and 0x3fff,
                        height = bytes.readUnsigned16LittleEndian(8) and 0x3fff,
                    ).requirePositive("WebP")
                    source.skip(length - 10L)
                }
                WEBP_VP8L -> {
                    if (++payloadCount > 1 || length <= 5L) {
                        throw MediaAssetInspectionException("WebP contains an invalid lossless image chunk.")
                    }
                    val bytes = source.readByteArray(5L)
                    if (bytes[0] != 0x2f.toByte()) {
                        throw MediaAssetInspectionException("WebP lossless frame header is invalid.")
                    }
                    val bits = bytes.readUnsigned32LittleEndian(1)
                    if ((bits ushr 29) != 0L) {
                        throw MediaAssetInspectionException("WebP lossless version bits are unsupported.")
                    }
                    payload = Dimensions(
                        width = ((bits and 0x3fffL) + 1L).toInt(),
                        height = (((bits ushr 14) and 0x3fffL) + 1L).toInt(),
                    )
                    source.skip(length - 5L)
                }
                WEBP_ANIM, WEBP_ANMF ->
                    throw MediaAssetInspectionException(
                        message = "Animated WebP images are not supported.",
                        reason = MediaAssetInspectionFailureReason.AnimatedImage,
                    )
                else -> source.skip(length)
            }
            if (length and 1L != 0L) source.skip(1L)
            consumed += paddedLength
        }
        val image = payload ?: throw MediaAssetInspectionException("WebP contains no supported image payload.")
        val result = canvas ?: image
        if (image.width > result.width || image.height > result.height) {
            throw MediaAssetInspectionException("WebP frame exceeds its declared canvas.")
        }
        return result.requirePositive("WebP")
    }
}

/** Detects only the bounded static raster formats accepted by the importer. */
fun detectStaticImageMediaType(header: ByteArray): String? = when {
    header.size >= PNG_SIGNATURE.size &&
        header.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) -> IMAGE_PNG
    header.size >= 3 &&
        header[0] == 0xff.toByte() && header[1] == JPEG_SOI.toByte() && header[2] == 0xff.toByte() -> IMAGE_JPEG
    header.size >= 12 &&
        header.readIntBigEndian(0) == WEBP_RIFF && header.readIntBigEndian(8) == WEBP_SIGNATURE -> IMAGE_WEBP
    else -> null
}

private data class Dimensions(
    val width: Int,
    val height: Int,
) {
    fun requirePositive(format: String): Dimensions {
        if (width <= 0 || height <= 0) {
            throw MediaAssetInspectionException("$format dimensions must be positive.")
        }
        return this
    }
}

private fun BufferedSource.readUnsignedByte(): Int = readByte().toInt() and 0xff

private fun BufferedSource.readUnsignedShortBigEndian(): Long = readShort().toLong() and 0xffffL

private fun BufferedSource.readUnsignedIntBigEndian(): Long = readInt().toLong() and 0xffff_ffffL

private fun BufferedSource.readUnsignedIntLittleEndian(): Long = readIntLe().toLong() and 0xffff_ffffL

private fun Long.toPositiveDimension(name: String): Int {
    if (this !in 1L..Int.MAX_VALUE.toLong()) {
        throw MediaAssetInspectionException("$name is outside the supported range.")
    }
    return toInt()
}

private fun ByteArray.readUnsigned16LittleEndian(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.readUnsigned24LittleEndian(offset: Int): Int =
    readUnsigned16LittleEndian(offset) or ((this[offset + 2].toInt() and 0xff) shl 16)

private fun ByteArray.readUnsigned32LittleEndian(offset: Int): Long =
    (this[offset].toLong() and 0xffL) or
        ((this[offset + 1].toLong() and 0xffL) shl 8) or
        ((this[offset + 2].toLong() and 0xffL) shl 16) or
        ((this[offset + 3].toLong() and 0xffL) shl 24)

private fun ByteArray.readIntBigEndian(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)

private fun validPngColorDepth(bitDepth: Int, colorType: Int): Boolean = when (colorType) {
    0 -> bitDepth in setOf(1, 2, 4, 8, 16)
    2, 4, 6 -> bitDepth in setOf(8, 16)
    3 -> bitDepth in setOf(1, 2, 4, 8)
    else -> false
}

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
)
private const val PNG_IHDR = 0x49484452
private const val PNG_ACTL = 0x6163544c
private const val PNG_IDAT = 0x49444154
private const val PNG_IEND = 0x49454e44

private const val JPEG_SOI = 0xd8
private const val JPEG_EOI = 0xd9
private const val JPEG_SOS = 0xda
private const val JPEG_TEM = 0x01
private const val JPEG_RST0 = 0xd0
private const val JPEG_RST7 = 0xd7
private val SUPPORTED_JPEG_SOF_MARKERS = setOf(0xc0, 0xc1, 0xc2)

private const val WEBP_RIFF = 0x52494646
private const val WEBP_SIGNATURE = 0x57454250
private const val WEBP_VP8X = 0x56503858
private const val WEBP_VP8 = 0x56503820
private const val WEBP_VP8L = 0x5650384c
private const val WEBP_ANIM = 0x414e494d
private const val WEBP_ANMF = 0x414e4d46
private const val WEBP_ANIMATION_FLAG = 0x02
