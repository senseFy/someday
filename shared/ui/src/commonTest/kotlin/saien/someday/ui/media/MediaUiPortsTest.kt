package saien.someday.ui.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import saien.someday.domain.media.MediaAssetId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaUiPortsTest {
    @Test
    fun importedResultExposesOnlyCanonicalAppOwnedUri() {
        val id = MediaAssetId.fromCanonicalValue("ab".repeat(32))
        val result = MediaImportUiResult.Imported(id, "Sunset")

        assertEquals("someday-asset://${"ab".repeat(32)}", result.uri.toString())
        assertEquals("Sunset", result.suggestedAltText)
    }

    @Test
    fun previewResultCarriesDecodedPlatformImage() {
        val loaded = MediaPreviewUiResult.Loaded(FakeImageBitmap(width = 3, height = 2))

        assertEquals(3, loaded.bitmap.width)
        assertEquals(2, loaded.bitmap.height)
    }

    @Test
    fun unavailablePortsReportExplicitOutcomes() = runBlocking {
        val id = MediaAssetId.fromCanonicalValue("cd".repeat(32))
        assertEquals(MediaPreviewUiResult.Missing, UnavailableMediaPreviewLoader.loadPreview(id))

        var importResult: MediaImportUiResult? = null
        UnavailableMediaImportRunner.start("画像を挿入") { importResult = it }
        assertEquals(
            MediaImportUiResult.Failed(MediaUiFailureReason.Unavailable),
            importResult,
        )

        var materializationResult: MediaMaterializationUiResult? = null
        UnavailableMediaMaterializationRunner.start(id) { materializationResult = it }
        assertEquals(
            MediaMaterializationUiResult.Failed(MediaUiFailureReason.Unavailable),
            materializationResult,
        )
        Unit
    }

    @Test
    fun localizedPickerTitleIsForwardedToPlatformRunner() {
        var receivedTitle: String? = null
        var result: MediaImportUiResult? = null
        val runner = MediaImportRunner { pickerTitle, onResult ->
            receivedTitle = pickerTitle
            onResult(MediaImportUiResult.Cancelled)
        }

        runner.start("画像を挿入") { result = it }

        assertEquals("画像を挿入", receivedTitle)
        assertEquals(MediaImportUiResult.Cancelled, result)
    }

    @Test
    fun cancellationAndTypedFailuresAreFirstClassResults() {
        assertEquals(MediaImportUiResult.Cancelled, MediaImportUiResult.Cancelled)
        assertEquals(MediaMaterializationUiResult.Cancelled, MediaMaterializationUiResult.Cancelled)
        assertEquals(
            MediaUiFailureReason.ImportFailed,
            MediaImportUiResult.Failed(MediaUiFailureReason.ImportFailed).reason,
        )
        assertEquals(
            MediaUiFailureReason.PreviewTooLarge,
            MediaPreviewUiResult.Failed(MediaUiFailureReason.PreviewTooLarge).reason,
        )
        assertEquals(
            MediaUiFailureReason.MaterializationFailed,
            MediaMaterializationUiResult.Failed(MediaUiFailureReason.MaterializationFailed).reason,
        )
    }

    private class FakeImageBitmap(
        override val width: Int,
        override val height: Int,
    ) : ImageBitmap {
        override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
        override val hasAlpha: Boolean = true
        override val colorSpace: ColorSpace = ColorSpaces.Srgb

        override fun prepareToDraw() = Unit

        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = Unit
    }
}
