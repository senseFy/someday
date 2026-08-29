package saien.someday.ui.media

import androidx.compose.ui.graphics.ImageBitmap
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.media.MAX_MEDIA_ASSET_PIXEL_COUNT
import saien.someday.domain.media.SomedayAssetUri

/** Presentation-neutral failures produced by platform media adapters. */
enum class MediaUiFailureReason {
    Unavailable,
    ImportFailed,
    SourceTooLarge,
    SourcePixelLimitExceeded,
    UnsupportedFormat,
    AnimatedImage,
    InvalidEncoding,
    NormalizationFailed,
    NormalizationWouldViolateQualityBounds,
    PreviewTooLarge,
    PreviewDecodeFailed,
    PreviewLoadFailed,
    MaterializationFailed,
}

/** Result of a platform picker that has already copied bytes into app storage. */
sealed interface MediaImportUiResult {
    data class Imported(
        val assetId: MediaAssetId,
        val suggestedAltText: String = "",
    ) : MediaImportUiResult {
        val uri: SomedayAssetUri = SomedayAssetUri(assetId)
    }

    data object Cancelled : MediaImportUiResult

    data class Failed(val reason: MediaUiFailureReason) : MediaImportUiResult
}

/**
 * Opens the platform image picker and imports the selection off the UI thread.
 * [pickerTitle] is localized by the shared UI before crossing this boundary.
 * The callback receives only a durable app-owned identity, never a temporary
 * platform URI or the original bytes. Implementations invoke [onResult]
 * exactly once on the UI thread; picker cancellation is [MediaImportUiResult.Cancelled].
 */
fun interface MediaImportRunner {
    fun start(
        pickerTitle: String,
        onResult: (MediaImportUiResult) -> Unit,
    )
}

/**
 * Resolves a bounded, correctly oriented preview for an app-owned asset.
 * [MediaPreviewUiResult.Missing] means the asset is not materialized locally.
 * This port performs no network IO.
 */
fun interface MediaPreviewLoader {
    suspend fun loadPreview(assetId: MediaAssetId): MediaPreviewUiResult
}

sealed interface MediaPreviewUiResult {
    data class Loaded(val bitmap: ImageBitmap) : MediaPreviewUiResult {
        init {
            require(bitmap.width > 0 && bitmap.height > 0) {
                "Preview dimensions must be positive."
            }
            require(bitmap.width.toLong() * bitmap.height <= MAX_MEDIA_ASSET_PIXEL_COUNT) {
                "Preview dimensions exceed the UI decode limit."
            }
        }
    }

    data object Missing : MediaPreviewUiResult

    data class Failed(val reason: MediaUiFailureReason) : MediaPreviewUiResult
}

/** Explicit result of a user-requested remote-to-local materialization. */
sealed interface MediaMaterializationUiResult {
    data object Materialized : MediaMaterializationUiResult
    data object Cancelled : MediaMaterializationUiResult

    data class Failed(val reason: MediaUiFailureReason) : MediaMaterializationUiResult
}

/**
 * Materializes one remote asset into app-owned local storage. UI calls this
 * only after an explicit user action; implementations invoke [onResult]
 * exactly once on the UI thread.
 */
fun interface MediaMaterializationRunner {
    fun start(
        assetId: MediaAssetId,
        onResult: (MediaMaterializationUiResult) -> Unit,
    )
}

val UnavailableMediaImportRunner = MediaImportRunner { _, callback ->
    callback(MediaImportUiResult.Failed(MediaUiFailureReason.Unavailable))
}

val UnavailableMediaPreviewLoader = MediaPreviewLoader { MediaPreviewUiResult.Missing }

val UnavailableMediaMaterializationRunner = MediaMaterializationRunner { _, callback ->
    callback(MediaMaterializationUiResult.Failed(MediaUiFailureReason.Unavailable))
}

/** Explicit app-composition boundary for all media UI capabilities. */
data class MediaUiPorts(
    val importRunner: MediaImportRunner = UnavailableMediaImportRunner,
    val previewLoader: MediaPreviewLoader = UnavailableMediaPreviewLoader,
    val materializationRunner: MediaMaterializationRunner = UnavailableMediaMaterializationRunner,
)
