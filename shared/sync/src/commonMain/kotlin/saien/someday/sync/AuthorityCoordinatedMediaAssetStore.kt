package saien.someday.sync

import kotlin.time.Instant
import okio.BufferedSink
import okio.Source
import saien.someday.data.media.LocalMediaAsset
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.media.MediaAssetCleanupResult
import saien.someday.data.media.MediaAssetImportRequest
import saien.someday.data.media.MediaAssetImportResult
import saien.someday.data.media.MediaAssetVerificationResult
import saien.someday.domain.media.MediaAssetId

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
) {
    fun getAsset(assetId: MediaAssetId): LocalMediaAsset? = delegate.getAsset(assetId)

    fun importAsset(
        source: Source,
        request: MediaAssetImportRequest,
    ): MediaAssetImportResult = localMutation { importAsset(source, request) }

    fun importAsset(
        request: MediaAssetImportRequest,
        write: (BufferedSink) -> Unit,
    ): MediaAssetImportResult = localMutation { importAsset(request, write) }

    fun verifyAsset(assetId: MediaAssetId): MediaAssetVerificationResult =
        localMutation { verifyAsset(assetId) }

    fun openSource(assetId: MediaAssetId): Source = localMutation { openSource(assetId) }

    fun cleanupOrphans(orphanedBefore: Instant): MediaAssetCleanupResult =
        localMutation { cleanupOrphans(orphanedBefore) }

    fun cleanupOrphans(): MediaAssetCleanupResult = localMutation { cleanupOrphans() }

    internal fun listAssetsPendingPublication(
        authorityBindingId: String,
        workspaceId: String,
    ): List<LocalMediaAsset> = delegate.listAssetsPendingPublication(authorityBindingId, workspaceId)

    internal fun <T> localMutation(block: LocalMediaAssetStore.() -> T): T =
        workspaceLifecycleCoordinator.productAccess { delegate.block() }
}
