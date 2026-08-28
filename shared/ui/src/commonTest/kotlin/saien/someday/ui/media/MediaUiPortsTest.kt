package saien.someday.ui.media

import saien.someday.domain.media.MediaAssetId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaUiPortsTest {
    @Test
    fun importedResultExposesOnlyCanonicalAppOwnedUri() {
        val id = MediaAssetId.fromCanonicalValue("ab".repeat(32))
        val result = MediaImportUiResult.Imported(id, "Sunset")

        assertEquals("someday-asset://${"ab".repeat(32)}", result.uri.toString())
        assertEquals("Sunset", result.suggestedAltText)
    }

    @Test
    fun previewResultOwnsAndBoundsBytes() {
        val source = byteArrayOf(1, 2, 3)
        val loaded = MediaPreviewUiResult.Loaded(source)
        source[0] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), loaded.copyBytes())
        assertEquals(3, loaded.byteCount)
        assertFailsWith<IllegalArgumentException> {
            MediaPreviewUiResult.Loaded(ByteArray(MAX_MEDIA_PREVIEW_BYTE_COUNT + 1))
        }
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
}
