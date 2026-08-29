package saien.someday.data.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaImageNormalizationTest {
    @Test
    fun selectedSourceBoundsAreSeparateFromFinalProtocolBounds() {
        val exact = SelectedImageImportRequest(
            maxSourceBytes = MAX_SELECTED_IMAGE_BYTE_COUNT,
            maxSourcePixelCount = MAX_SELECTED_IMAGE_PIXEL_COUNT,
        )

        assertEquals(MAX_SELECTED_IMAGE_BYTE_COUNT, exact.maxSourceBytes)
        assertEquals(MAX_SELECTED_IMAGE_PIXEL_COUNT, exact.maxSourcePixelCount)
        assertFailsWith<IllegalArgumentException> {
            SelectedImageImportRequest(maxSourceBytes = MAX_SELECTED_IMAGE_BYTE_COUNT + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            SelectedImageImportRequest(maxSourcePixelCount = MAX_SELECTED_IMAGE_PIXEL_COUNT + 1)
        }
    }
}
