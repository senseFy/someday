package saien.someday.app.ios

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Buffer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
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
    }

    private fun createPng(width: Int, height: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            surface.canvas.clear(0x80336699.toInt())
            val image = surface.makeImageSnapshot()
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)
                    ?: error("Skia could not encode the test PNG.")
                return try {
                    data.bytes
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            surface.close()
        }
    }
}
