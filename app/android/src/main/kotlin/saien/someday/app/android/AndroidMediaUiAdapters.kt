package saien.someday.app.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
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

internal fun ContentResolver.importSelectedImage(
    uri: Uri,
    store: AuthorityCoordinatedMediaAssetStore,
): MediaImportUiResult {
    val fileName = runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
        ?.takeIf(::isSafeOriginalFileName)
    return try {
        val imported = openInputStream(uri)?.use { input ->
            input.source().use { source ->
                store.importSelectedImage(
                    source = source,
                    request = SelectedImageImportRequest(originalFileName = fileName),
                    normalizer = AndroidMediaImageNormalizer,
                )
            }
        } ?: return MediaImportUiResult.Failed(MediaUiFailureReason.ImportFailed)
        MediaImportUiResult.Imported(
            assetId = imported.asset.metadata.id,
            suggestedAltText = fileName?.substringBeforeLast('.')?.take(120).orEmpty(),
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
        val bytes = result.copyBytes()
        MediaPreviewUiResult.Loaded(bytes.decodeAndroidPreview().asImageBitmap())
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

private fun ByteArray.decodeAndroidPreview(): Bitmap =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(this))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    } else {
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(this, 0, size)) {
            "Android image decoder rejected verified preview bytes."
        }
        decoded.applyImageOrientation(androidImageOrientation())
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
