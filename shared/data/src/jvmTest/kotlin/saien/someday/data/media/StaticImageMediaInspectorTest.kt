package saien.someday.data.media

import java.util.Base64
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class StaticImageMediaInspectorTest {
    @Test
    fun inspectsCompleteSupportedStaticRasterImages() {
        assertInspection(PNG_1X1, "image/png", 1, 1)
        assertInspection(JPEG_3X2, "image/jpeg", 3, 2)
        assertInspection(WEBP_7X5, "image/webp", 7, 5)
    }

    @Test
    fun rejectsEmptyPngAndHeaderOnlyWebpPayloads() {
        assertFailsWith<MediaAssetInspectionException> {
            inspect(pngHeader(width = 1, height = 1, imageDataBytes = 0), "image/png")
        }
        assertFailsWith<MediaAssetInspectionException> {
            inspect(webpHeaderOnly(width = 7, height = 5), "image/webp")
        }
    }

    @Test
    fun rejectsDeclaredTypeMismatchAndUnsupportedSvg() {
        assertFailsWith<MediaAssetInspectionException> {
            inspect(PNG_1X1, "image/jpeg")
        }
        assertFailsWith<MediaAssetInspectionException> {
            inspect("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray(), "image/svg+xml")
        }
    }

    @Test
    fun rejectsEmptyAndTruncatedEncodedPayloads() {
        assertFails { inspect(ByteArray(0), "image/png") }
        listOf(
            "image/png" to PNG_1X1,
            "image/jpeg" to JPEG_3X2,
            "image/webp" to WEBP_7X5,
        ).forEach { (mediaType, bytes) ->
            assertFailsWith<MediaAssetInspectionException> {
                inspect(bytes.copyOf(bytes.size - 1), mediaType)
            }
        }
    }

    @Test
    fun rejectsAnimatedPngAndWebP() {
        assertFailsWith<MediaAssetInspectionException> {
            inspect(pngHeader(width = 1, height = 1, animated = true), "image/png")
        }
        assertFailsWith<MediaAssetInspectionException> {
            inspect(animatedWebpHeader(), "image/webp")
        }
    }

    @Test
    fun rejectsDecodedPixelBombBeforePromotion() {
        assertFailsWith<MediaAssetInspectionException> {
            inspect(
                bytes = pngHeader(width = 10_000, height = 10_000),
                mediaType = "image/png",
                maxDecodedPixelCount = 12_000_000L,
            )
        }
    }

    private fun assertInspection(
        bytes: ByteArray,
        mediaType: String,
        expectedWidth: Int,
        expectedHeight: Int,
    ) {
        val inspection = inspect(bytes, mediaType)
        assertEquals(mediaType, inspection.mediaType)
        assertEquals(expectedWidth, inspection.pixelWidth)
        assertEquals(expectedHeight, inspection.pixelHeight)
    }

    private fun inspect(
        bytes: ByteArray,
        mediaType: String,
        maxDecodedPixelCount: Long = DEFAULT_MAX_DECODED_PIXEL_COUNT,
    ): MediaAssetInspection =
        StaticImageMediaAssetInspector.inspect(
            source = Buffer().write(bytes),
            encodedByteSize = bytes.size.toLong(),
            declaredMediaType = mediaType,
            maxDecodedPixelCount = maxDecodedPixelCount,
        )

    private fun pngHeader(
        width: Int,
        height: Int,
        animated: Boolean = false,
        imageDataBytes: Int = 1,
    ): ByteArray = Buffer().apply {
        write(PNG_SIGNATURE_FOR_TEST)
        writeInt(13)
        writeUtf8("IHDR")
        writeInt(width)
        writeInt(height)
        writeByte(8)
        writeByte(6)
        writeByte(0)
        writeByte(0)
        writeByte(0)
        writeInt(0) // CRC is deliberately not decoded by the metadata inspector.
        if (animated) {
            writeInt(8)
            writeUtf8("acTL")
            writeInt(1)
            writeInt(0)
            writeInt(0)
        }
        writeInt(imageDataBytes)
        writeUtf8("IDAT")
        write(ByteArray(imageDataBytes))
        writeInt(0)
        writeInt(0)
        writeUtf8("IEND")
        writeInt(0)
    }.readByteArray()

    private fun animatedWebpHeader(): ByteArray = Buffer().apply {
        writeUtf8("RIFF")
        writeIntLe(22)
        writeUtf8("WEBP")
        writeUtf8("VP8X")
        writeIntLe(10)
        writeByte(0x02)
        write(ByteArray(9))
    }.readByteArray()

    private fun webpHeaderOnly(width: Int, height: Int): ByteArray {
        val packed = (width - 1).toLong() or ((height - 1).toLong() shl 14)
        return Buffer().apply {
            writeUtf8("RIFF")
            writeIntLe(18)
            writeUtf8("WEBP")
            writeUtf8("VP8L")
            writeIntLe(5)
            writeByte(0x2f)
            writeIntLe(packed.toInt())
            writeByte(0)
        }.readByteArray()
    }

    companion object {
        private val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        private val JPEG_3X2: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjExLjEwMAD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABMAAEBAAAAAAAAAAAAAAAAAAAABgEBAQAAAAAAAAAAAAAAAAAABgcQAQAAAAAAAAAAAAAAAAAAAAARAQAAAAAAAAAAAAAAAAAAAAD/wAARCAACAAMDASIAAhEAAxEA/9oADAMBAAIRAxEAPwCLAE1/f//Z",
        )
        private val WEBP_7X5: ByteArray = Base64.getDecoder().decode(
            "UklGRhwAAABXRUJQVlA4TA8AAAAvBgABAAcQ0f/+ByKi/wEA",
        )
        private val PNG_SIGNATURE_FOR_TEST = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
