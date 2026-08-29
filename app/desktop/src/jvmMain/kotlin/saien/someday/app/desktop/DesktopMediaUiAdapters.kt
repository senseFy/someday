package saien.someday.app.desktop

import androidx.compose.ui.graphics.decodeToImageBitmap
import java.io.File
import okio.source
import saien.someday.data.media.SelectedImageImportException
import saien.someday.data.media.SelectedImageImportFailureReason
import saien.someday.data.media.SelectedImageImportRequest
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.media.isSafeOriginalFileName
import saien.someday.sync.AuthorityCoordinatedMediaAssetStore
import saien.someday.sync.CoordinatedMediaPreviewReadResult
import saien.someday.ui.media.MediaImportUiResult
import saien.someday.ui.media.MediaPreviewUiResult
import saien.someday.ui.media.MediaUiFailureReason

internal fun File.importSelectedImage(
    store: AuthorityCoordinatedMediaAssetStore,
): MediaImportUiResult {
    val originalName = name.takeIf(::isSafeOriginalFileName)
    return try {
        val imported = source().use { source ->
            store.importSelectedImage(
                source = source,
                request = SelectedImageImportRequest(originalFileName = originalName),
                normalizer = DesktopMediaImageNormalizer,
            )
        }
        MediaImportUiResult.Imported(
            imported.asset.metadata.id,
            originalName?.substringBeforeLast('.')?.take(120).orEmpty(),
        )
    } catch (failure: SelectedImageImportException) {
        MediaImportUiResult.Failed(failure.reason.toUiFailureReason())
    } catch (_: Exception) {
        MediaImportUiResult.Failed(MediaUiFailureReason.ImportFailed)
    }
}

internal fun AuthorityCoordinatedMediaAssetStore.loadMediaPreview(
    assetId: MediaAssetId,
): MediaPreviewUiResult = when (val result = readVerifiedPreview(assetId)) {
    is CoordinatedMediaPreviewReadResult.Loaded -> runCatching {
        MediaPreviewUiResult.Loaded(result.copyBytes().decodeToImageBitmap())
    }.getOrElse {
        MediaPreviewUiResult.Failed(MediaUiFailureReason.PreviewDecodeFailed)
    }
    CoordinatedMediaPreviewReadResult.Missing,
    CoordinatedMediaPreviewReadResult.Corrupt,
    -> MediaPreviewUiResult.Missing
    CoordinatedMediaPreviewReadResult.TooLarge ->
        MediaPreviewUiResult.Failed(MediaUiFailureReason.PreviewTooLarge)
    CoordinatedMediaPreviewReadResult.Failed ->
        MediaPreviewUiResult.Failed(MediaUiFailureReason.PreviewLoadFailed)
}

private fun SelectedImageImportFailureReason.toUiFailureReason(): MediaUiFailureReason = when (this) {
    SelectedImageImportFailureReason.SourceTooLarge -> MediaUiFailureReason.SourceTooLarge
    SelectedImageImportFailureReason.SourcePixelLimitExceeded -> MediaUiFailureReason.SourcePixelLimitExceeded
    SelectedImageImportFailureReason.UnsupportedFormat -> MediaUiFailureReason.UnsupportedFormat
    SelectedImageImportFailureReason.AnimatedImage -> MediaUiFailureReason.AnimatedImage
    SelectedImageImportFailureReason.InvalidEncoding -> MediaUiFailureReason.InvalidEncoding
    SelectedImageImportFailureReason.NormalizationFailed -> MediaUiFailureReason.NormalizationFailed
    SelectedImageImportFailureReason.NormalizationWouldViolateQualityBounds ->
        MediaUiFailureReason.NormalizationWouldViolateQualityBounds
}
