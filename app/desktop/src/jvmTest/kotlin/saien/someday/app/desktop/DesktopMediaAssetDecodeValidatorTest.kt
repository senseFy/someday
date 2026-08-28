package saien.someday.app.desktop

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import okio.Buffer

class DesktopMediaAssetDecodeValidatorTest {
    @Test
    fun decodesCompleteJpegPngAndWebpImages() {
        assertDecoded(PNG_1X1, 1, 1)
        assertDecoded(JPEG_3X2, 3, 2)
        assertDecoded(WEBP_7X5, 7, 5)
    }

    @Test
    fun rejectsEmptyAndUndecodableImagePayloads() {
        listOf(
            "empty" to ByteArray(0),
            "JPEG header only" to jpegHeaderOnly(),
            "PNG empty IDAT" to pngWithEmptyImageData(),
        ).forEach { (description, bytes) ->
            assertFails(description) { DesktopMediaAssetDecodeValidator.decode(Buffer().write(bytes)) }
        }
    }

    private fun assertDecoded(bytes: ByteArray, expectedWidth: Int, expectedHeight: Int) {
        val decoded = DesktopMediaAssetDecodeValidator.decode(Buffer().write(bytes))
        assertEquals(expectedWidth, decoded.pixelWidth)
        assertEquals(expectedHeight, decoded.pixelHeight)
    }

    private fun jpegHeaderOnly(): ByteArray = Buffer()
        .write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte()))
        .writeShort(17)
        .writeByte(8)
        .writeShort(2)
        .writeShort(3)
        .writeByte(3)
        .write(byteArrayOf(1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0))
        .write(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
        .readByteArray()

    private fun pngWithEmptyImageData(): ByteArray = Buffer().apply {
        write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        writeInt(13)
        writeUtf8("IHDR")
        writeInt(1)
        writeInt(1)
        write(byteArrayOf(8, 6, 0, 0, 0))
        writeInt(0)
        writeInt(0)
        writeUtf8("IDAT")
        writeInt(0)
        writeInt(0)
        writeUtf8("IEND")
        writeInt(0)
    }.readByteArray()

    private companion object {
        val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val JPEG_3X2: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjExLjEwMAD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABMAAEBAAAAAAAAAAAAAAAAAAAABgEBAQAAAAAAAAAAAAAAAAAABgcQAQAAAAAAAAAAAAAAAAAAAAARAQAAAAAAAAAAAAAAAAAAAAD/wAARCAACAAMDASIAAhEAAxEA/9oADAMBAAIRAxEAPwCLAE1/f//Z",
        )
        val WEBP_7X5: ByteArray = Base64.getDecoder().decode(
            "UklGRhwAAABXRUJQVlA4TA8AAAAvBgABAAcQ0f/+ByKi/wEA",
        )
    }
}
