package saien.someday.app.ios

import kotlin.math.roundToInt
import kotlin.math.sqrt
import okio.BufferedSource
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import saien.someday.data.media.MediaImageNormalizationException
import saien.someday.data.media.MediaImageNormalizationRequest
import saien.someday.data.media.MediaImageNormalizer

internal object IosMediaImageNormalizer : MediaImageNormalizer {
    override fun normalize(
        source: BufferedSource,
        request: MediaImageNormalizationRequest,
    ): ByteArray {
        val encoded = source.readByteArray()
        var image = decodeOrientedBounded(encoded, request)
        try {
            val outputFormat = if (
                request.sourceInspection.mediaType == "image/png" || !image.image.isOpaque
            ) {
                EncodedImageFormat.PNG
            } else {
                EncodedImageFormat.JPEG
            }
            val sourceLongEdge = maxOf(
                request.sourceInspection.pixelWidth,
                request.sourceInspection.pixelHeight,
            )
            val minimumLongEdge = minOf(sourceLongEdge, request.minimumLongEdgePixels)
            while (true) {
                image.encode(outputFormat, request.jpegQuality)?.let { bytes ->
                    if (bytes.size <= request.maxOutputBytes) return bytes
                }
                val currentLongEdge = maxOf(image.image.width, image.image.height)
                if (currentLongEdge <= minimumLongEdge) {
                    throw MediaImageNormalizationException(
                        violatesQualityBounds = true,
                        message = "Image cannot fit the media byte limit without excessive downscaling.",
                    )
                }
                val nextLongEdge = maxOf(
                    minimumLongEdge,
                    (currentLongEdge * DIMENSION_RETRY_SCALE).roundToInt(),
                )
                val scale = nextLongEdge.toDouble() / currentLongEdge
                image = image.replaceWithScaled(
                    width = (image.image.width * scale).roundToInt().coerceAtLeast(1),
                    height = (image.image.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        } finally {
            image.close()
        }
    }

    private fun decodeOrientedBounded(
        encoded: ByteArray,
        request: MediaImageNormalizationRequest,
    ): ManagedImage {
        val data = Data.makeFromBytes(encoded)
        try {
            val codec = Codec.makeFromData(data)
            try {
                val origin = codec.encodedOrigin
                val orientedWidth = if (origin.swapsWidthHeight()) codec.size.y else codec.size.x
                val orientedHeight = if (origin.swapsWidthHeight()) codec.size.x else codec.size.y
                val target = dimensionsWithinPixelLimit(
                    orientedWidth,
                    orientedHeight,
                    request.maxOutputPixelCount,
                )
                val requestedRawWidth = if (origin.swapsWidthHeight()) target.height else target.width
                val requestedRawHeight = if (origin.swapsWidthHeight()) target.width else target.height
                val bitmap = Bitmap()
                try {
                    try {
                        bitmap.allocPixels(
                            ImageInfo.makeN32(requestedRawWidth, requestedRawHeight, ColorAlphaType.PREMUL),
                        )
                        codec.readPixels(bitmap)
                    } catch (failure: IllegalArgumentException) {
                        if (!failure.isUnsupportedCodecScale()) throw failure
                        return renderEncodedAtTarget(
                            encoded = encoded,
                            origin = origin,
                            target = target,
                            rawTargetWidth = requestedRawWidth,
                            rawTargetHeight = requestedRawHeight,
                        )
                    }
                    val rawImage = Image.makeFromBitmap(bitmap)
                    if (origin == EncodedOrigin.TOP_LEFT) return ManagedImage(rawImage)
                    try {
                        val surface = Surface.makeRasterN32Premul(target.width, target.height)
                        try {
                            surface.canvas.concat(origin.toMatrix(requestedRawWidth, requestedRawHeight))
                            surface.canvas.drawImage(rawImage, 0f, 0f)
                            return ManagedImage(surface.makeImageSnapshot())
                        } finally {
                            surface.close()
                        }
                    } finally {
                        rawImage.close()
                    }
                } finally {
                    bitmap.close()
                }
            } finally {
                codec.close()
            }
        } finally {
            data.close()
        }
    }

    private fun renderEncodedAtTarget(
        encoded: ByteArray,
        origin: EncodedOrigin,
        target: PixelDimensions,
        rawTargetWidth: Int,
        rawTargetHeight: Int,
    ): ManagedImage {
        val image = Image.makeFromEncoded(encoded)
        try {
            val surface = Surface.makeRasterN32Premul(target.width, target.height)
            try {
                if (origin != EncodedOrigin.TOP_LEFT) {
                    surface.canvas.concat(origin.toMatrix(rawTargetWidth, rawTargetHeight))
                }
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeWH(rawTargetWidth.toFloat(), rawTargetHeight.toFloat()),
                )
                return ManagedImage(surface.makeImageSnapshot())
            } finally {
                surface.close()
            }
        } finally {
            image.close()
        }
    }

    private fun Throwable.isUnsupportedCodecScale(): Boolean =
        this is IllegalArgumentException && message?.startsWith("Invalid scale:") == true

    private fun ManagedImage.replaceWithScaled(width: Int, height: Int): ManagedImage {
        if (image.width == width && image.height == height) return this
        val surface = Surface.makeRasterN32Premul(width, height)
        val scaled = try {
            surface.canvas.drawImageRect(image, Rect.makeWH(width.toFloat(), height.toFloat()))
            surface.makeImageSnapshot()
        } finally {
            surface.close()
        }
        close()
        return ManagedImage(scaled)
    }

    private fun ManagedImage.encode(format: EncodedImageFormat, jpegQuality: Int): ByteArray? =
        image.encodeToData(format, jpegQuality)?.let { data ->
            try {
                data.bytes
            } finally {
                data.close()
            }
        }

    private fun dimensionsWithinPixelLimit(
        width: Int,
        height: Int,
        pixelLimit: Long,
    ): PixelDimensions {
        val pixels = width.toLong() * height
        if (pixels <= pixelLimit) return PixelDimensions(width, height)
        val scale = sqrt(pixelLimit.toDouble() / pixels)
        var targetWidth = (width * scale).toInt().coerceAtLeast(1)
        var targetHeight = (height * scale).toInt().coerceAtLeast(1)
        while (targetWidth.toLong() * targetHeight > pixelLimit) {
            if (targetWidth >= targetHeight) targetWidth-- else targetHeight--
        }
        return PixelDimensions(targetWidth, targetHeight)
    }

    private data class PixelDimensions(val width: Int, val height: Int)

    private class ManagedImage(
        val image: Image,
    ) : AutoCloseable {
        override fun close() = image.close()
    }

    private const val DIMENSION_RETRY_SCALE = 0.8
}
