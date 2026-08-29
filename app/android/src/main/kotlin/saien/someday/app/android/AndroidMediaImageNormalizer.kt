package saien.someday.app.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt
import okio.BufferedSource
import saien.someday.data.media.MediaImageNormalizationException
import saien.someday.data.media.MediaImageNormalizationRequest
import saien.someday.data.media.MediaImageNormalizer

internal object AndroidMediaImageNormalizer : MediaImageNormalizer {
    override fun normalize(
        source: BufferedSource,
        request: MediaImageNormalizationRequest,
    ): ByteArray {
        val encoded = source.readByteArray()
        val orientation = encoded.androidImageOrientation()
        val orientedSourceWidth = if (orientation.swapsDimensions) {
            request.sourceInspection.pixelHeight
        } else {
            request.sourceInspection.pixelWidth
        }
        val orientedSourceHeight = if (orientation.swapsDimensions) {
            request.sourceInspection.pixelWidth
        } else {
            request.sourceInspection.pixelHeight
        }
        val target = dimensionsWithinPixelLimit(
            orientedSourceWidth,
            orientedSourceHeight,
            request.maxOutputPixelCount,
        )
        val minimumLongEdge = minOf(
            maxOf(orientedSourceWidth, orientedSourceHeight),
            request.minimumLongEdgePixels,
        )
        var bitmap = decodeBounded(
            encoded = encoded,
            target = target,
            orientation = orientation,
            pixelLimit = request.maxOutputPixelCount,
            minimumLongEdge = minimumLongEdge,
        )
        try {
            val decodedTarget = dimensionsWithinPixelLimit(
                bitmap.width,
                bitmap.height,
                request.maxOutputPixelCount,
            )
            if (bitmap.width != decodedTarget.width || bitmap.height != decodedTarget.height) {
                bitmap = bitmap.replaceWithScaled(decodedTarget.width, decodedTarget.height)
            }
            val outputFormat = if (
                request.sourceInspection.mediaType == "image/png" || bitmap.hasAlpha()
            ) {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            while (true) {
                encodeWithinLimit(bitmap, outputFormat, request)?.let { return it }
                val currentLongEdge = maxOf(bitmap.width, bitmap.height)
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
                bitmap = bitmap.replaceWithScaled(
                    width = (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    height = (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBounded(
        encoded: ByteArray,
        target: PixelDimensions,
        orientation: AndroidImageOrientation,
        pixelLimit: Long,
        minimumLongEdge: Int,
    ): Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSize(target.width, target.height)
        }
    } else {
        val options = BitmapFactory.Options().apply {
            inSampleSize = boundedSampleSize(
                encoded,
                pixelLimit,
                minimumLongEdge,
            )
        }
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)) {
            "Android image decoder rejected the selected image."
        }
        decoded.applyImageOrientation(orientation)
    }

    private fun boundedSampleSize(
        encoded: ByteArray,
        pixelLimit: Long,
        minimumLongEdge: Int,
    ): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw MediaImageNormalizationException(message = "Android could not inspect the selected image.")
        }
        if (bounds.outWidth.toLong() * bounds.outHeight <= pixelLimit) return 1
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2) {
            val nextSample = sample * 2
            val nextWidth = ((bounds.outWidth.toLong() + nextSample - 1L) / nextSample).toInt()
            val nextHeight = ((bounds.outHeight.toLong() + nextSample - 1L) / nextSample).toInt()
            if (nextWidth.toLong() * nextHeight > pixelLimit) {
                sample = nextSample
                continue
            }
            if (maxOf(nextWidth, nextHeight) >= minimumLongEdge) sample = nextSample
            break
        }
        return sample
    }

    private fun Bitmap.replaceWithScaled(width: Int, height: Int): Bitmap {
        if (this.width == width && this.height == height) return this
        val scaled = Bitmap.createScaledBitmap(this, width, height, true)
        if (scaled !== this) recycle()
        return scaled
    }

    private fun encodeWithinLimit(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        request: MediaImageNormalizationRequest,
    ): ByteArray? {
        val output = BoundedByteArrayOutputStream(request.maxOutputBytes.toInt())
        return try {
            if (!bitmap.compress(format, request.jpegQuality, output)) null else output.toByteArray()
        } catch (_: EncodedImageTooLargeException) {
            null
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

    private class BoundedByteArrayOutputStream(
        private val byteLimit: Int,
    ) : OutputStream() {
        private val delegate = ByteArrayOutputStream(minOf(byteLimit, INITIAL_OUTPUT_CAPACITY))
        private var byteCount = 0

        override fun write(value: Int) {
            ensureCapacity(1)
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length)
            delegate.write(bytes, offset, length)
        }

        fun toByteArray(): ByteArray = delegate.toByteArray()

        private fun ensureCapacity(additionalBytes: Int) {
            if (additionalBytes > byteLimit - byteCount) throw EncodedImageTooLargeException()
            byteCount += additionalBytes
        }
    }

    private class EncodedImageTooLargeException : IOException()

    private const val DIMENSION_RETRY_SCALE = 0.8
    private const val INITIAL_OUTPUT_CAPACITY = 256 * 1_024
}
