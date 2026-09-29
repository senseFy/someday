package saien.someday.sync

import kotlin.time.Instant
import okio.BufferedSink
import okio.Source
import okio.buffer
import saien.someday.data.media.LocalMediaAsset
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.media.MediaAssetCleanupResult
import saien.someday.data.media.MediaAssetImportRequest
import saien.someday.data.media.MediaAssetImportResult
import saien.someday.data.media.MediaAssetVerificationResult
import saien.someday.data.media.MediaImageNormalizer
import saien.someday.data.media.SelectedImageImportRequest
import saien.someday.domain.media.MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT
import saien.someday.domain.media.MAX_MEDIA_ASSET_PIXEL_COUNT
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.workspace.UnrestrictedWorkspaceProductAccess
import saien.someday.domain.workspace.WorkspaceProductAccess
import saien.someday.domain.workspace.WorkspaceProductSnapshot

sealed interface CoordinatedMediaPreviewReadResult {
    class Loaded(bytes: ByteArray) : CoordinatedMediaPreviewReadResult {
        private val content = bytes.copyOf()

        fun copyBytes(): ByteArray = content.copyOf()
    }

    data object Missing : CoordinatedMediaPreviewReadResult
    data object Corrupt : CoordinatedMediaPreviewReadResult
    data object TooLarge : CoordinatedMediaPreviewReadResult
    data object Failed : CoordinatedMediaPreviewReadResult
}

/**
 * The only writable local-media entry point exposed by System V3 composition.
 *
 * Workspace replacement discards local media rows and swaps authority under the
 * same [WorkspaceLifecycleCoordinator.productAccess] lock. Keeping
 * imports and maintenance behind this facade prevents an old-workspace media
 * row from appearing during replacement.
 */
class AuthorityCoordinatedMediaAssetStore internal constructor(
    private val delegate: LocalMediaAssetStore,
    private val workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator,
    private val workspaceProductAccess: WorkspaceProductAccess = UnrestrictedWorkspaceProductAccess,
) {
    /** Capture on an IO dispatcher before opening a picker, then pass it to the eventual import. */
    fun captureWorkspace(): WorkspaceProductSnapshot = workspaceProductAccess.capture()

    fun getAsset(assetId: MediaAssetId): LocalMediaAsset? = delegate.getAsset(assetId)

    fun importAsset(
        source: Source,
        request: MediaAssetImportRequest,
        expectedWorkspace: WorkspaceProductSnapshot? = null,
    ): MediaAssetImportResult = productMutation(expectedWorkspace) { importAsset(source, request) }

    fun importAsset(
        request: MediaAssetImportRequest,
        expectedWorkspace: WorkspaceProductSnapshot? = null,
        write: (BufferedSink) -> Unit,
    ): MediaAssetImportResult = productMutation(expectedWorkspace) { importAsset(request, write) }

    fun importSelectedImage(
        source: Source,
        request: SelectedImageImportRequest,
        normalizer: MediaImageNormalizer,
        expectedWorkspace: WorkspaceProductSnapshot? = null,
    ): MediaAssetImportResult = productMutation(expectedWorkspace) { importSelectedImage(source, request, normalizer) }

    fun verifyAsset(assetId: MediaAssetId): MediaAssetVerificationResult =
        localMutation { verifyAsset(assetId) }

    fun openSource(assetId: MediaAssetId): Source = localMutation { openSource(assetId) }

    /** Copies verified preview bytes before releasing the workspace lifecycle lock. */
    fun readVerifiedPreview(
        assetId: MediaAssetId,
        byteLimit: Long = MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT,
        pixelLimit: Long = MAX_MEDIA_ASSET_PIXEL_COUNT,
    ): CoordinatedMediaPreviewReadResult = localMutation {
        require(byteLimit in 1L..MAX_MEDIA_ASSET_ENCODED_BYTE_COUNT)
        require(pixelLimit in 1L..MAX_MEDIA_ASSET_PIXEL_COUNT)
        val initial = getAsset(assetId) ?: return@localMutation CoordinatedMediaPreviewReadResult.Missing
        if (initial.metadata.byteSize > byteLimit || initial.metadata.decodedPixelCount > pixelLimit) {
            return@localMutation CoordinatedMediaPreviewReadResult.TooLarge
        }
        try {
            when (verifyAsset(assetId)) {
                is MediaAssetVerificationResult.Missing ->
                    CoordinatedMediaPreviewReadResult.Missing
                is MediaAssetVerificationResult.Corrupt ->
                    CoordinatedMediaPreviewReadResult.Corrupt
                is MediaAssetVerificationResult.Verified -> {
                    val source = openSource(assetId).buffer()
                    val bytes = try {
                        source.readByteArray(initial.metadata.byteSize)
                    } finally {
                        source.close()
                    }
                    CoordinatedMediaPreviewReadResult.Loaded(bytes)
                }
            }
        } catch (_: Exception) {
            when (getAsset(assetId)?.localState) {
                saien.someday.data.media.MediaAssetLocalState.Missing ->
                    CoordinatedMediaPreviewReadResult.Missing
                saien.someday.data.media.MediaAssetLocalState.Corrupt ->
                    CoordinatedMediaPreviewReadResult.Corrupt
                else -> CoordinatedMediaPreviewReadResult.Failed
            }
        }
    }

    fun cleanupOrphans(
        orphanedBefore: Instant,
        expectedWorkspace: WorkspaceProductSnapshot? = null,
    ): MediaAssetCleanupResult = productMutation(expectedWorkspace) { cleanupOrphans(orphanedBefore) }

    fun cleanupOrphans(expectedWorkspace: WorkspaceProductSnapshot? = null): MediaAssetCleanupResult =
        productMutation(expectedWorkspace) { cleanupOrphans() }

    private fun <T> productMutation(
        expectedWorkspace: WorkspaceProductSnapshot?,
        block: LocalMediaAssetStore.() -> T,
    ): T = workspaceLifecycleCoordinator.productAccess {
        workspaceProductAccess.mutate(expectedWorkspace) { delegate.block() }
    }

    internal fun listAssetsPendingPublication(
        authorityBindingId: String,
        workspaceId: String,
    ): List<LocalMediaAsset> = delegate.listAssetsPendingPublication(authorityBindingId, workspaceId)

    internal fun <T> localMutation(block: LocalMediaAssetStore.() -> T): T =
        workspaceLifecycleCoordinator.productAccess { delegate.block() }
}
