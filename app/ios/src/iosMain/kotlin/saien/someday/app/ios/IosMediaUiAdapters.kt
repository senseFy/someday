package saien.someday.app.ios

import androidx.compose.ui.graphics.decodeToImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerConfigurationAssetRepresentationModeCompatible
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import saien.someday.data.media.SelectedImageImportException
import saien.someday.data.media.SelectedImageImportFailureReason
import saien.someday.data.media.SelectedImageImportRequest
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.media.isSafeOriginalFileName
import saien.someday.sync.AuthorityCoordinatedMediaAssetStore
import saien.someday.sync.CoordinatedMediaPreviewReadResult
import saien.someday.ui.media.MediaImportRunner
import saien.someday.ui.media.MediaImportUiResult
import saien.someday.ui.media.MediaPreviewUiResult
import saien.someday.ui.media.MediaUiFailureReason

@OptIn(ExperimentalForeignApi::class)
internal class IosMediaImportRunner(
    private val rootControllerProvider: () -> UIViewController?,
    private val store: AuthorityCoordinatedMediaAssetStore,
) : MediaImportRunner {
    private var activeDelegate: IosMediaPickerDelegate? = null

    @Suppress("UNUSED_PARAMETER")
    override fun start(
        pickerTitle: String,
        onResult: (MediaImportUiResult) -> Unit,
    ) {
        val rootController = rootControllerProvider()
        if (rootController == null || activeDelegate != null) {
            onResult(MediaImportUiResult.Failed(MediaUiFailureReason.Unavailable))
            return
        }
        val delegate = IosMediaPickerDelegate(store) { result ->
            activeDelegate = null
            onResult(result)
        }
        activeDelegate = delegate
        val configuration = PHPickerConfiguration().apply {
            selectionLimit = 1
            filter = PHPickerFilter.imagesFilter
            preferredAssetRepresentationMode = PHPickerConfigurationAssetRepresentationModeCompatible
        }
        val picker = PHPickerViewController(configuration)
        picker.delegate = delegate
        rootController.presentViewController(picker, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosMediaPickerDelegate(
    private val store: AuthorityCoordinatedMediaAssetStore,
    private val onComplete: (MediaImportUiResult) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    private var completed = false

    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) {
            completeOnMain(MediaImportUiResult.Cancelled)
            return
        }
        val provider = result.itemProvider
        val suggestedName = provider.suggestedName?.takeIf(::isSafeOriginalFileName)
        provider.loadFileRepresentationForTypeIdentifier(UTTypeImage.identifier) { url, _ ->
            val importResult = runBlocking(Dispatchers.Default) {
                if (url == null) {
                    MediaImportUiResult.Failed(MediaUiFailureReason.ImportFailed)
                } else {
                    // The provider URL is temporary, so the callback waits until
                    // the background import has copied it into app-owned storage.
                    url.importSelectedImage(store, suggestedName)
                }
            }
            completeOnMain(importResult)
        }
    }

    private fun completeOnMain(result: MediaImportUiResult) {
        if (completed) return
        completed = true
        CoroutineScope(Dispatchers.Main).launch { onComplete(result) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.importSelectedImage(
    store: AuthorityCoordinatedMediaAssetStore,
    suggestedName: String?,
): MediaImportUiResult {
    val localPath = path ?: return MediaImportUiResult.Failed(MediaUiFailureReason.ImportFailed)
    val originalName = suggestedName ?: lastPathComponent?.takeIf(::isSafeOriginalFileName)
    return try {
        val source = FileSystem.SYSTEM.source(localPath.toPath())
        val imported = try {
            store.importSelectedImage(
                source = source,
                request = SelectedImageImportRequest(originalFileName = originalName),
                normalizer = IosMediaImageNormalizer,
            )
        } finally {
            source.close()
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
