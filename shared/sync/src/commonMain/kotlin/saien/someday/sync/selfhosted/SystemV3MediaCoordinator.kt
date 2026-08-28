package saien.someday.sync.selfhosted

import okio.Buffer
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.media.LocalMediaAsset
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.media.MediaAssetIdentityConflictException
import saien.someday.data.media.MediaAssetImportRequest
import saien.someday.data.media.MediaAssetIntegrityException
import saien.someday.data.media.MediaAssetVerificationResult
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId
import saien.someday.sync.AuthorityCoordinatedMediaAssetStore

data class SystemV3MediaPublicationSummary(
    val publishedAssets: Int,
    val uploadedObjects: Int,
    val reusedObjects: Int,
)

data class SystemV3MediaMaterialization(val asset: LocalMediaAsset, val downloaded: Boolean)

/** One immutable encrypted object per asset; no draft or retry state machine is required. */
class SystemV3MediaCoordinator(
    private val localStore: AuthorityCoordinatedMediaAssetStore,
    private val transport: SelfHostedMediaTransportV3,
    private val sessionStore: SelfHostedSessionCredentialStore,
    private val workspaceKeyProvider: () -> WorkspaceMasterKey?,
    private val workspaceIdProvider: () -> String?,
    private val activeWorkspaceSessionGuard: ActiveWorkspaceSessionGuard =
        ActiveWorkspaceSessionGuard { null },
    private val sessionExecutor: RefreshingSelfHostedSessionExecutor? = null,
) {
    fun verifyActiveAuthorityBinding(): String {
        val credentials = sessionStore.load()
            ?: error("Self-hosted session is missing; credentials redacted.")
        val workspaceId = requireSystemV3WorkspaceId(
            workspaceIdProvider() ?: error("The active workspace id is unavailable."),
        )
        activeWorkspaceSessionGuard.requireCompatible(
            credentials,
            workspaceId,
        )
        return credentials.authorityBindingId
    }

    fun publishPending(): SystemV3MediaPublicationSummary {
        val connection = connectedService()
        val pending = localStore.listAssetsPendingPublication(
            connection.credentials.authorityBindingId,
            connection.workspaceId,
        )
        if (pending.isEmpty()) return SystemV3MediaPublicationSummary(0, 0, 0)
        var uploaded = 0
        var reused = 0
        pending.forEach { asset ->
            val result = publishLocalAsset(asset, connection)
            uploaded += result.summary.uploadedObjects
            reused += result.summary.reusedObjects
        }
        return SystemV3MediaPublicationSummary(pending.size, uploaded, reused)
    }

    /** Exact reachability gate used immediately before publishing an entity/checkpoint batch. */
    fun ensurePublished(assetIds: Set<MediaAssetId>) {
        if (assetIds.isEmpty()) return
        val connection = connectedService()
        assetIds.sortedBy { it.value }.forEach { ensurePublished(it, connection) }
    }

    fun materialize(assetId: MediaAssetId): SystemV3MediaMaterialization {
        localStore.getAsset(assetId)?.let {
            if (localStore.verifyAsset(assetId) is MediaAssetVerificationResult.Verified) {
                return SystemV3MediaMaterialization(it, downloaded = false)
            }
        }
        val connection = connectedService()
        val fetched = connection.service.fetchObject(
            connection.credentials.endpoint,
            connection.credentials.accessToken,
            connection.workspaceId,
            assetId,
        )
        return SystemV3MediaMaterialization(importFetched(assetId, fetched, connection), downloaded = true)
    }

    private fun ensurePublished(assetId: MediaAssetId, connection: Connection) {
        val local = localStore.getAsset(assetId)
        val hasCurrentProof = local != null &&
            local.publishedAuthorityBindingId == connection.credentials.authorityBindingId &&
            local.publishedWorkspaceId == connection.workspaceId &&
            local.publishedObjectDigest != null
        if (hasCurrentProof) {
            val head = connection.service.headObject(
                connection.credentials.endpoint,
                connection.credentials.accessToken,
                connection.workspaceId,
                assetId,
            )
            if (head != null) {
                require(head.ciphertextSha256 == local.publishedObjectDigest) {
                    "The immutable remote media object differs from local publication proof."
                }
                return
            }
            publishLocalAsset(requireVerifiedLocal(local), connection)
            return
        }

        // Remote-only references are downloaded/authenticated once; import caches bytes and proof.
        val fetched = runCatching {
            connection.service.fetchObject(
                connection.credentials.endpoint,
                connection.credentials.accessToken,
                connection.workspaceId,
                assetId,
            )
        }
        fetched.getOrNull()?.let { remote ->
            if (local == null || localStore.verifyAsset(assetId) !is MediaAssetVerificationResult.Verified) {
                importFetched(assetId, remote, connection)
            } else {
                commitToCapturedWorkspace(connection) {
                    val current = getAsset(assetId)
                        ?: throw MediaAssetIntegrityException("The local media asset disappeared before publication proof commit.")
                    requireSameImmutableIdentity(current, remote.metadata)
                    markPublished(
                        assetId,
                        connection.credentials.authorityBindingId,
                        connection.workspaceId,
                        remote.encryptedSha256,
                    )
                }
            }
            return
        }
        val verified = local?.let(::requireVerifiedLocal) ?: throw fetched.exceptionOrNull()!!
        publishLocalAsset(verified, connection)
    }

    private fun connectedService(): Connection {
        val credentials = sessionStore.load()
            ?: error("Self-hosted session is missing; credentials redacted.")
        val workspaceId = requireSystemV3WorkspaceId(
            workspaceIdProvider() ?: error("The active workspace id is unavailable."),
        )
        activeWorkspaceSessionGuard.requireCompatible(credentials, workspaceId)
        val workspaceKey = workspaceKeyProvider()
            ?: error("Unlock the workspace before synchronizing media.")
        val authenticatedTransport = sessionExecutor?.let {
            RefreshingSelfHostedMediaTransportV3(transport, it, credentials.userId)
        } ?: transport
        return Connection(
            credentials,
            workspaceId,
            workspaceKey.fingerprint,
            SelfHostedMediaServiceV3(authenticatedTransport, SelfHostedMediaCipherV3(workspaceKey)),
        )
    }

    private fun publishLocalAsset(
        asset: LocalMediaAsset,
        connection: Connection,
    ): SelfHostedMediaSourceUploadResultV3 {
        val verified = requireVerifiedLocal(asset)
        val source = localStore.openSource(verified.metadata.id)
        val result = try {
            connection.service.uploadSource(
                connection.credentials.endpoint,
                connection.credentials.accessToken,
                connection.workspaceId,
                verified.metadata.id,
                verified.metadata.mediaType,
                verified.metadata.pixelWidth,
                verified.metadata.pixelHeight,
                verified.metadata.byteSize,
                source,
                verified.metadata.originalFileName,
            )
        } finally {
            source.close()
        }
        commitToCapturedWorkspace(connection) {
            markPublished(
                verified.metadata.id,
                connection.credentials.authorityBindingId,
                connection.workspaceId,
                result.encryptedObjectSha256,
            )
        }
        return result
    }

    private fun importFetched(
        assetId: MediaAssetId,
        fetched: SelfHostedDecryptedMediaObjectV3,
        connection: Connection,
    ): LocalMediaAsset {
        val metadata = fetched.metadata
        val result = commitToCapturedWorkspace(connection) {
            importAsset(
                source = Buffer().write(fetched.plaintextBytes),
                request = MediaAssetImportRequest(
                    mediaType = metadata.mediaType,
                    originalFileName = metadata.originalFileName,
                    maxBytes = metadata.plaintextBytes.toLong(),
                    expectedByteSize = metadata.plaintextBytes.toLong(),
                    expectedContentSha256 = metadata.plaintextSha256.removePrefix("sha256:"),
                    expectedAssetId = assetId,
                    expectedPixelWidth = metadata.pixelWidth,
                    expectedPixelHeight = metadata.pixelHeight,
                    maxDecodedPixelCount = metadata.pixelWidth.toLong() * metadata.pixelHeight,
                    publishedAuthorityBindingId = connection.credentials.authorityBindingId,
                    publishedWorkspaceId = connection.workspaceId,
                    publishedObjectDigest = fetched.encryptedSha256,
                ),
            )
        }
        requireSameImmutableIdentity(result.asset, metadata)
        return result.asset
    }

    /**
     * Network I/O is deliberately completed before this short critical section.
     * A pairing flow may replace the workspace while a request is in flight, so
     * every resulting local write revalidates the captured authority, writer,
     * workspace id, and key before committing.
     */
    private fun <T> commitToCapturedWorkspace(
        connection: Connection,
        block: LocalMediaAssetStore.() -> T,
    ): T = localStore.localMutation {
        requireConnectionStillCurrent(connection)
        block()
    }

    private fun requireConnectionStillCurrent(connection: Connection) {
        val currentCredentials = sessionStore.load()
            ?: error("Self-hosted session changed while synchronizing media; credentials redacted.")
        require(currentCredentials.authorityBindingId == connection.credentials.authorityBindingId) {
            "The self-hosted account changed while synchronizing media."
        }
        require(currentCredentials.deviceId == connection.credentials.deviceId) {
            "The self-hosted writer device changed while synchronizing media."
        }
        val currentWorkspaceId = requireSystemV3WorkspaceId(
            workspaceIdProvider() ?: error("The active workspace changed while synchronizing media."),
        )
        require(currentWorkspaceId == connection.workspaceId) {
            "The active workspace changed while synchronizing media."
        }
        activeWorkspaceSessionGuard.requireCompatible(currentCredentials, currentWorkspaceId)
        val currentKey = workspaceKeyProvider()
            ?: error("The workspace was locked while synchronizing media.")
        require(currentKey.fingerprint == connection.workspaceKeyFingerprint) {
            "The workspace key changed while synchronizing media."
        }
    }

    private fun requireVerifiedLocal(asset: LocalMediaAsset): LocalMediaAsset =
        when (val verification = localStore.verifyAsset(asset.metadata.id)) {
            is MediaAssetVerificationResult.Verified -> verification.asset
            is MediaAssetVerificationResult.Missing ->
                throw MediaAssetIntegrityException("A referenced media asset file is missing.")
            is MediaAssetVerificationResult.Corrupt ->
                throw MediaAssetIntegrityException("A referenced media asset file is corrupt.")
        }

    private fun requireSameImmutableIdentity(
        local: LocalMediaAsset,
        remote: SelfHostedMediaObjectMetadataV3,
    ) {
        val metadata = local.metadata
        val matches = remote.mediaId == metadata.id.value &&
            remote.plaintextSha256 == "sha256:${local.contentSha256}" &&
            remote.plaintextBytes.toLong() == metadata.byteSize &&
            remote.mediaType == metadata.mediaType &&
            remote.originalFileName == metadata.originalFileName &&
            remote.pixelWidth == metadata.pixelWidth &&
            remote.pixelHeight == metadata.pixelHeight
        if (!matches) throw MediaAssetIdentityConflictException(
            "The remote media id is already bound to a different immutable asset.",
        )
    }

    private data class Connection(
        val credentials: SelfHostedSessionCredentials,
        val workspaceId: String,
        val workspaceKeyFingerprint: String,
        val service: SelfHostedMediaServiceV3,
    )
}
