package saien.someday.app.ios

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Buffer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.impl.use
import saien.someday.data.media.MediaImageNormalizationRequest
import saien.someday.data.media.StaticImageMediaAssetInspector
import saien.someday.domain.media.MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT
import saien.someday.domain.media.MAX_MEDIA_ASSET_PIXEL_COUNT

class IosMediaImageNormalizerTest {
    @Test
    fun oversizedPngIsScaledWithinFinalProtocolBounds() {
        val sourceWidth = 4_000
        val sourceHeight = 3_100
        val encoded = createPng(sourceWidth, sourceHeight)
        val sourceInspection = StaticImageMediaAssetInspector.inspect(
            source = Buffer().write(encoded),
            encodedByteSize = encoded.size.toLong(),
            declaredMediaType = null,
            maxDecodedPixelCount = sourceWidth.toLong() * sourceHeight,
        )

        val normalized = IosMediaImageNormalizer.normalize(
            source = Buffer().write(encoded),
            request = MediaImageNormalizationRequest(sourceInspection),
        )
        val finalInspection = StaticImageMediaAssetInspector.inspect(
            source = Buffer().write(normalized),
            encodedByteSize = normalized.size.toLong(),
            declaredMediaType = "image/png",
            maxDecodedPixelCount = MAX_MEDIA_ASSET_PIXEL_COUNT,
        )

        assertTrue(normalized.size <= MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT)
        assertTrue(finalInspection.decodedPixelCount <= MAX_MEDIA_ASSET_PIXEL_COUNT)
        assertEquals("image/png", finalInspection.mediaType)
        val sourceAspect = sourceWidth.toDouble() / sourceHeight
        val finalAspect = finalInspection.pixelWidth.toDouble() / finalInspection.pixelHeight
        assertTrue(abs(sourceAspect - finalAspect) < 0.001)
        withDecodedPixels(normalized) { bitmap ->
            assertEquals(128, Color.getA(bitmap.getColor(bitmap.width / 2, bitmap.height / 2)))
        }
    }

    @Test
    fun oversizedJpegRemainsJpegAfterScaling() {
        val sourceWidth = 4_000
        val sourceHeight = 3_100
        val encoded = createImage(sourceWidth, sourceHeight, EncodedImageFormat.JPEG, 0xff336699.toInt())

        val normalized = normalize(encoded, sourceWidth.toLong() * sourceHeight)
        val inspection = inspect(normalized, MAX_MEDIA_ASSET_PIXEL_COUNT)

        assertEquals("image/jpeg", inspection.mediaType)
        assertTrue(normalized.size <= MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT)
        assertTrue(inspection.decodedPixelCount <= MAX_MEDIA_ASSET_PIXEL_COUNT)
        assertTrue(maxOf(inspection.pixelWidth, inspection.pixelHeight) >= 2_048)
        assertTrue(abs(sourceWidth.toDouble() / sourceHeight - inspection.pixelWidth.toDouble() / inspection.pixelHeight) < 0.001)
    }

    @Test
    fun transparentWebpBecomesPngWithoutLosingAlpha() {
        val encoded = createImage(320, 240, EncodedImageFormat.WEBP, 0x80336699.toInt())

        val normalized = normalize(encoded, MAX_MEDIA_ASSET_PIXEL_COUNT)

        assertEquals("image/png", inspect(normalized, MAX_MEDIA_ASSET_PIXEL_COUNT).mediaType)
        withDecodedPixels(normalized) { bitmap ->
            assertEquals(128, Color.getA(bitmap.getColor(160, 120)))
        }
    }

    @Test
    fun rotatedJpegKeepsJpegEncodingAndAppliesExifOrientation() {
        val encoded = withExifOrientation(createTwoColorJpeg(), 6)

        val normalized = normalize(encoded, MAX_MEDIA_ASSET_PIXEL_COUNT)

        assertEquals("image/jpeg", inspect(normalized, MAX_MEDIA_ASSET_PIXEL_COUNT).mediaType)
        withDecodedPixels(normalized) { bitmap ->
            assertEquals(200, bitmap.width)
            assertEquals(300, bitmap.height)
            assertRed(bitmap.getColor(100, 75))
            assertBlue(bitmap.getColor(100, 225))
        }
    }

    @Test
    fun mirroredJpegKeepsJpegEncodingAndAppliesExifOrientation() {
        listOf(300 to 200, 4_000 to 3_100).forEach { (width, height) ->
            val encoded = withExifOrientation(createTwoColorJpeg(width, height), 2)

            val normalized = normalize(encoded, width.toLong() * height)
            val inspection = inspect(normalized, MAX_MEDIA_ASSET_PIXEL_COUNT)

            assertEquals("image/jpeg", inspection.mediaType)
            assertTrue(normalized.size <= MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT)
            assertTrue(abs(width.toDouble() / height - inspection.pixelWidth.toDouble() / inspection.pixelHeight) < 0.001)
            withDecodedPixels(normalized) { bitmap ->
                if (width.toLong() * height <= MAX_MEDIA_ASSET_PIXEL_COUNT) {
                    assertEquals(width, bitmap.width)
                    assertEquals(height, bitmap.height)
                }
                assertBlue(bitmap.getColor(bitmap.width / 4, bitmap.height / 2))
                assertRed(bitmap.getColor(bitmap.width * 3 / 4, bitmap.height / 2))
            }
        }
    }

    @Test
    fun opaqueWebpUsesJpeg() {
        val encoded = createImage(320, 240, EncodedImageFormat.WEBP, 0xff336699.toInt())
        val normalized = normalize(encoded, MAX_MEDIA_ASSET_PIXEL_COUNT)
        assertEquals("image/jpeg", inspect(normalized, MAX_MEDIA_ASSET_PIXEL_COUNT).mediaType)
    }

    @Test
    fun opaquePngRemainsPng() {
        val encoded = createImage(320, 240, EncodedImageFormat.PNG, 0xff336699.toInt())
        val normalized = normalize(encoded, MAX_MEDIA_ASSET_PIXEL_COUNT)
        assertEquals("image/png", inspect(normalized, MAX_MEDIA_ASSET_PIXEL_COUNT).mediaType)
    }

    @Test
    fun oversizedRotatedJpegKeepsJpegEncodingAndAppliesExifOrientation() {
        val sourceWidth = 4_000
        val sourceHeight = 3_100
        val encoded = withExifOrientation(createTwoColorJpeg(sourceWidth, sourceHeight), 6)

        val normalized = normalize(encoded, sourceWidth.toLong() * sourceHeight)
        val inspection = inspect(normalized, MAX_MEDIA_ASSET_PIXEL_COUNT)

        assertEquals("image/jpeg", inspection.mediaType)
        assertTrue(normalized.size <= MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT)
        assertTrue(inspection.decodedPixelCount <= MAX_MEDIA_ASSET_PIXEL_COUNT)
        assertTrue(abs(sourceHeight.toDouble() / sourceWidth - inspection.pixelWidth.toDouble() / inspection.pixelHeight) < 0.001)
        withDecodedPixels(normalized) { bitmap ->
            assertRed(bitmap.getColor(bitmap.width / 2, bitmap.height / 4))
            assertBlue(bitmap.getColor(bitmap.width / 2, bitmap.height * 3 / 4))
        }
    }

    private fun normalize(encoded: ByteArray, sourcePixelLimit: Long): ByteArray =
        IosMediaImageNormalizer.normalize(
            source = Buffer().write(encoded),
            request = MediaImageNormalizationRequest(inspect(encoded, sourcePixelLimit)),
        )

    private fun inspect(encoded: ByteArray, pixelLimit: Long) =
        StaticImageMediaAssetInspector.inspect(
            source = Buffer().write(encoded),
            encodedByteSize = encoded.size.toLong(),
            declaredMediaType = null,
            maxDecodedPixelCount = pixelLimit,
        )

    private fun withDecodedPixels(encoded: ByteArray, assertion: (Bitmap) -> Unit) {
        Data.makeFromBytes(encoded).use { data ->
            Codec.makeFromData(data).use { codec ->
                assertEquals(EncodedOrigin.TOP_LEFT, codec.encodedOrigin)
                codec.readPixels().use(assertion)
            }
        }
    }

    private fun createPng(width: Int, height: Int): ByteArray =
        createImage(width, height, EncodedImageFormat.PNG, 0x80336699.toInt())

    private fun createImage(width: Int, height: Int, format: EncodedImageFormat, color: Int): ByteArray =
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(color)
            surface.makeImageSnapshot().use { image ->
                image.encodeToData(format)?.use { it.bytes }
                    ?: error("Skia could not encode the test image.")
            }
        }

    private fun createTwoColorJpeg(width: Int = 300, height: Int = 200): ByteArray =
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(0xffff0000.toInt())
            Paint().use { paint ->
                paint.color = 0xff0000ff.toInt()
                surface.canvas.drawRect(Rect.makeXYWH(width / 2f, 0f, width / 2f, height.toFloat()), paint)
            }
            surface.makeImageSnapshot().use { image ->
                image.encodeToData(EncodedImageFormat.JPEG)?.use { it.bytes }
                    ?: error("Skia could not encode the test JPEG.")
            }
        }

    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        // APP1 with one little-endian TIFF IFD entry: Orientation (SHORT).
        val exif = byteArrayOf(
            0xff.toByte(), 0xe1.toByte(), 0, 34,
            0x45, 0x78, 0x69, 0x66, 0, 0,
            0x49, 0x49, 0x2a, 0, 8, 0, 0, 0,
            1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0,
            orientation.toByte(), 0, 0, 0, 0, 0, 0, 0,
        )
        return jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun assertRed(color: Int) {
        assertTrue(
            Color.getR(color) > 240 && Color.getG(color) < 15 && Color.getB(color) < 15,
            "Expected red, got ARGB ${color.toUInt().toString(16)}.",
        )
    }

    private fun assertBlue(color: Int) {
        assertTrue(
            Color.getB(color) > 240 && Color.getR(color) < 15 && Color.getG(color) < 15,
            "Expected blue, got ARGB ${color.toUInt().toString(16)}.",
        )
    }
}
