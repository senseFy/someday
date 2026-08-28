package saien.someday.sync.selfhosted

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.authorityBindingId
import saien.someday.domain.settings.normalizeSelfHostedEndpoint
import saien.someday.domain.settings.selfHostedAuthorityBindingId
import saien.someday.sync.causality.v2.CanonicalWorkspaceCausalityMaterializerV2
import saien.someday.sync.causality.v2.EncryptedWorkspaceObjectV2
import saien.someday.sync.causality.v2.MINIMUM_WRITER_VERSION_V2
import saien.someday.sync.causality.v2.SYNC_V2_CONTRACT_ID
import saien.someday.sync.causality.v2.SYNC_V2_SCHEMA_SET_VERSION
import saien.someday.sync.causality.v2.SyncEpochDescriptorV2
import saien.someday.sync.causality.v2.SyncEpochKeyDerivationV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.SyncStreamFrontierV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointChunkRefV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointDraftCleanupResultV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointDraftCleanupV2
import saien.someday.sync.causality.v2.WorkspaceControlDecodeResultV2
import saien.someday.sync.causality.v2.WorkspaceEncryptedCursorUnitV2
import saien.someday.sync.causality.v2.WorkspaceImmutablePutResultV2
import saien.someday.sync.causality.v2.WorkspaceMutationAckV2
import saien.someday.sync.causality.v2.WorkspacePointerPublishResultV2
import saien.someday.sync.causality.v2.WorkspaceRemoteCheckpointBundleV2
import saien.someday.sync.causality.v2.WorkspaceSyncCapabilitiesV2
import saien.someday.sync.causality.v2.WorkspaceSyncPullResultV2
import saien.someday.sync.causality.v2.WorkspaceSyncPushResultV2
import saien.someday.sync.causality.v2.WorkspaceSyncRemoteV2
import saien.someday.sync.causality.v2.WorkspaceSyncControlCodecV2
import saien.someday.sync.causality.v2.WorkspaceObjectCipherV2
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RefreshingSelfHostedSessionExecutor(
    private val authenticationTransport: SelfHostedSyncTransport,
    private val sessionStore: SelfHostedSessionCredentialStore,
) {
    private val refreshMutex = Mutex()

    fun <T> authorized(
        endpoint: String,
        authenticatedUserId: String,
        suppliedToken: String,
        request: (String) -> T,
    ): T {
        val expectedBinding = selfHostedAuthorityBindingId(endpoint, authenticatedUserId)
        val firstToken = sessionStore.load()
            ?.takeIf { it.authorityBindingId == expectedBinding }
            ?.accessToken
            ?: suppliedToken
        return try {
            request(firstToken)
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.status != 401) throw failure
            val retryToken = runBlocking {
                refreshMutex.withLock {
                    val current = sessionStore.load()
                        ?.takeIf { it.authorityBindingId == expectedBinding }
                        ?: throw SelfHostedSyncHttpException(
                            401,
                            "Self-hosted account session is missing; credentials redacted.",
                        )
                    if (current.accessToken != firstToken) {
                        return@withLock current.accessToken
                    }
                    val refreshed = authenticationTransport.refresh(
                        current.endpoint,
                        SelfHostedRefreshRequest(current.refreshToken),
                    )
                    if (refreshed.user.id != current.userId) {
                        throw SelfHostedSyncHttpException(
                            401,
                            "Self-hosted refresh changed the authenticated account; credentials redacted.",
                        )
                    }
                    val updated = current.copy(
                        userEmail = refreshed.user.email,
                        accessToken = refreshed.accessToken,
                        refreshToken = refreshed.refreshToken,
                    )
                    sessionStore.saveForAuthority(expectedBinding, updated)
                    sessionStore.save(updated)
                    updated.accessToken
                }
            }
            request(retryToken)
        }
    }
}

interface SelfHostedSyncTransportV2 {
    fun v2Capabilities(endpoint: String, accessToken: String): SelfHostedV2CapabilitiesResponse
    fun v2Epoch(endpoint: String, accessToken: String, workspaceId: String): SelfHostedV2EpochResponse
    fun v2PutCheckpointChunk(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointChunkRequest,
    ): SelfHostedV2ImmutablePutResponse

    fun v2PutCheckpointManifest(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointManifestRequest,
    ): SelfHostedV2ImmutablePutResponse

    fun v2FetchCheckpoint(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointFetchRequest,
    ): SelfHostedV2CheckpointFetchResponse

    fun v2CompareAndSetEpoch(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2EpochCompareAndSetRequest,
    ): SelfHostedV2EpochCompareAndSetResponse

    fun v2CleanupCheckpointDraft(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointCleanupRequest,
    ): SelfHostedV2CheckpointCleanupResponse

    fun v2Push(endpoint: String, accessToken: String, request: SelfHostedV2PushRequest): SelfHostedV2PushResponse
    fun v2Pull(endpoint: String, accessToken: String, request: SelfHostedV2PullRequest): SelfHostedV2PullResponse
    fun v2Frontiers(endpoint: String, accessToken: String, request: SelfHostedV2FrontierRequest): SelfHostedV2FrontierResponse
}

class RefreshingSelfHostedSyncTransportV2(
    private val delegate: SelfHostedSyncTransportV2,
    private val sessionExecutor: RefreshingSelfHostedSessionExecutor,
    private val authenticatedUserId: String,
) : SelfHostedSyncTransportV2 {
    override fun v2Capabilities(endpoint: String, accessToken: String) =
        authorized(endpoint, accessToken) { delegate.v2Capabilities(endpoint, it) }
    override fun v2Epoch(endpoint: String, accessToken: String, workspaceId: String) =
        authorized(endpoint, accessToken) { delegate.v2Epoch(endpoint, it, workspaceId) }
    override fun v2PutCheckpointChunk(endpoint: String, accessToken: String, request: SelfHostedV2CheckpointChunkRequest) =
        authorized(endpoint, accessToken) { delegate.v2PutCheckpointChunk(endpoint, it, request) }
    override fun v2PutCheckpointManifest(endpoint: String, accessToken: String, request: SelfHostedV2CheckpointManifestRequest) =
        authorized(endpoint, accessToken) { delegate.v2PutCheckpointManifest(endpoint, it, request) }
    override fun v2FetchCheckpoint(endpoint: String, accessToken: String, request: SelfHostedV2CheckpointFetchRequest) =
        authorized(endpoint, accessToken) { delegate.v2FetchCheckpoint(endpoint, it, request) }
    override fun v2CompareAndSetEpoch(endpoint: String, accessToken: String, request: SelfHostedV2EpochCompareAndSetRequest) =
        authorized(endpoint, accessToken) { delegate.v2CompareAndSetEpoch(endpoint, it, request) }
    override fun v2CleanupCheckpointDraft(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointCleanupRequest,
    ) = authorized(endpoint, accessToken) { delegate.v2CleanupCheckpointDraft(endpoint, it, request) }
    override fun v2Push(endpoint: String, accessToken: String, request: SelfHostedV2PushRequest) =
        authorized(endpoint, accessToken) { delegate.v2Push(endpoint, it, request) }
    override fun v2Pull(endpoint: String, accessToken: String, request: SelfHostedV2PullRequest) =
        authorized(endpoint, accessToken) { delegate.v2Pull(endpoint, it, request) }
    override fun v2Frontiers(endpoint: String, accessToken: String, request: SelfHostedV2FrontierRequest) =
        authorized(endpoint, accessToken) { delegate.v2Frontiers(endpoint, it, request) }

    private fun <T> authorized(endpoint: String, suppliedToken: String, request: (String) -> T): T {
        return sessionExecutor.authorized(endpoint, authenticatedUserId, suppliedToken, request)
    }
}

@Serializable
data class SelfHostedV2CapabilitiesResponse(
    val profile: String,
    val contractId: String,
    val semanticProtocolVersion: Int,
    val schemaSetVersion: String,
    val keySetVersion: String,
    val metadataPrivacyMode: String,
    val maxPushObjects: Int,
    val maxPullUnits: Int,
    val maxEncodedBodyBytes: Int,
    val supportsCheckpoints: Boolean,
    val parentIndexedMetadata: Boolean = false,
)

@Serializable
data class SelfHostedV2EpochMetadata(
    val contractId: String = SYNC_V2_CONTRACT_ID,
    val schemaSetVersion: String = SYNC_V2_SCHEMA_SET_VERSION,
    val epochId: String,
    val pointerDigest: String,
    val semanticProtocolVersion: Int,
    val minimumWriterProtocolVersion: Int,
    val keySetVersion: String,
    val remoteProfile: String,
    val metadataPrivacyMode: String,
    val supportedOfflineWindowSeconds: Long,
    val checkpointId: String,
    val checkpointDigest: String,
    val previousEpochId: String? = null,
    val previousEpochPointerDigest: String? = null,
)

@Serializable
data class SelfHostedV2EpochResponse(
    val metadata: SelfHostedV2EpochMetadata? = null,
    val pointer: EncryptedWorkspaceObjectV2? = null,
)

@Serializable
data class SelfHostedV2CheckpointChunkRequest(
    val epochId: String,
    val checkpointId: String,
    val ref: WorkspaceCheckpointChunkRefV2,
    val objectValue: EncryptedWorkspaceObjectV2,
    @Transient val workspaceId: String = "",
)

@Serializable
data class SelfHostedV2CheckpointManifestRequest(
    val epochId: String,
    val checkpointId: String,
    val checkpointDigest: String,
    val chunks: List<WorkspaceCheckpointChunkRefV2>,
    val totalObjectCount: Int,
    val objectValue: EncryptedWorkspaceObjectV2,
    @Transient val workspaceId: String = "",
)

@Serializable
data class SelfHostedV2ImmutablePutResponse(
    val stored: Boolean,
    val idempotentReplay: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SelfHostedV2CheckpointFetchRequest(
    val epochId: String,
    val checkpointId: String,
    /** Null fetches only the manifest; a value fetches exactly one immutable chunk. */
    val chunkIndex: Int? = null,
    @Transient val workspaceId: String = "",
)

@Serializable
data class SelfHostedV2CheckpointFetchResponse(
    val manifest: EncryptedWorkspaceObjectV2? = null,
    val chunk: EncryptedWorkspaceObjectV2? = null,
)

@Serializable
data class SelfHostedV2CheckpointCleanupRequest(
    val epochId: String,
    val checkpointId: String,
    val checkpointDigest: String,
    val previousPointerDigest: String? = null,
    val chunks: List<WorkspaceCheckpointChunkRefV2>,
    @Transient val workspaceId: String = "",
)

@Serializable
data class SelfHostedV2CheckpointCleanupResponse(
    val deleted: Boolean,
    val alreadyAbsent: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SelfHostedV2EpochCompareAndSetRequest(
    val expectedCurrentDigest: String? = null,
    val metadata: SelfHostedV2EpochMetadata,
    val pointer: EncryptedWorkspaceObjectV2,
    @Transient val workspaceId: String = "",
)

@Serializable
data class SelfHostedV2EpochCompareAndSetResponse(
    val published: Boolean,
    val idempotentReplay: Boolean = false,
    val current: SelfHostedV2EpochResponse? = null,
    val error: String? = null,
)

@Serializable
data class SelfHostedV2PushRequest(
    val epochId: String,
    val writerProtocolVersion: Int,
    val objects: List<EncryptedWorkspaceObjectV2>,
    @Transient val workspaceId: String = "",
)

@Serializable
data class SelfHostedV2MutationAckResponse(
    val mutationId: String,
    val objectId: String,
    val objectDigest: String,
    val idempotentReplay: Boolean,
)

@Serializable
data class SelfHostedV2PushResponse(
    val accepted: Boolean,
    val acknowledgements: List<SelfHostedV2MutationAckResponse> = emptyList(),
    val error: String? = null,
)

@Serializable
data class SelfHostedV2PullRequest(
    val epochId: String,
    val afterCursor: Long? = null,
    val limit: Int = 100,
    @Transient val workspaceId: String = "",
)

@Serializable
data class SelfHostedV2CursorUnitResponse(
    val epochId: String,
    val streamId: String,
    val expectedCursorValue: String? = null,
    val nextCursorValue: String,
    val unitId: String,
    val unitDigest: String,
    val objects: List<EncryptedWorkspaceObjectV2>,
)

@Serializable
data class SelfHostedV2PullResponse(
    val units: List<SelfHostedV2CursorUnitResponse>,
    val complete: Boolean,
    val error: String? = null,
)

@Serializable data class SelfHostedV2FrontierRequest(val epochId: String, @Transient val workspaceId: String = "")
@Serializable data class SelfHostedV2StreamFrontier(val streamId: String, val cursorValue: String?, val streamDigest: String)
@Serializable data class SelfHostedV2FrontierResponse(val frontiers: List<SelfHostedV2StreamFrontier>)

class SelfHostedSyncRemoteV2(
    endpoint: String,
    authenticatedUserId: String,
    private val workspaceId: String,
    private val workspaceKey: WorkspaceMasterKey,
    private val accessTokenProvider: () -> String,
    private val transport: SelfHostedSyncTransportV2,
) : WorkspaceSyncRemoteV2 {
    private val endpoint = normalizeSelfHostedEndpoint(endpoint)
    init {
        require(SELF_HOSTED_WORKSPACE_ID.matches(workspaceId)) { "Invalid workspace scope." }
    }
    override val remoteProfile: String = SyncRemoteProfileV2.SELF_HOSTED.wireValue
    override val authorityBindingId: String = selfHostedAuthorityBindingId(this.endpoint, authenticatedUserId)

    override fun capabilities(): WorkspaceSyncCapabilitiesV2 {
        val value = transport.v2Capabilities(endpoint, token())
        if (value.parentIndexedMetadata) error("Initial V2 profile must keep parent metadata opaque.")
        return WorkspaceSyncCapabilitiesV2(
            value.profile, value.contractId, value.semanticProtocolVersion, value.schemaSetVersion,
            value.keySetVersion, value.metadataPrivacyMode, value.maxPushObjects, value.maxPullUnits,
            value.maxEncodedBodyBytes, value.supportsCheckpoints,
        )
    }

    override fun loadEpochPointer(): EncryptedWorkspaceObjectV2? = transport.v2Epoch(endpoint, token(), workspaceId).pointer

    override fun fetchCheckpoint(
        pointer: EncryptedWorkspaceObjectV2,
        descriptor: SyncEpochDescriptorV2,
    ): WorkspaceRemoteCheckpointBundleV2 {
        val manifestValue = transport.v2FetchCheckpoint(
            endpoint, token(), SelfHostedV2CheckpointFetchRequest(
                descriptor.syncEpochId, descriptor.checkpointId, workspaceId = workspaceId,
            ),
        )
        val manifestOuter = requireNotNull(manifestValue.manifest) {
            "Self-hosted V2 checkpoint manifest response is incomplete."
        }
        require(manifestValue.chunk == null) { "Manifest fetch returned an unexpected checkpoint chunk." }
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(workspaceKey, descriptor.syncEpochId),
        )
        val manifest = when (val decoded = WorkspaceSyncControlCodecV2(
            WorkspaceObjectCipherV2(workspaceKey, materializer),
        ).decodeCheckpointManifest(manifestOuter, descriptor.syncEpochId, descriptor.checkpointId)) {
            is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
            is WorkspaceControlDecodeResultV2.Rejected -> error(decoded.error.safeMessage)
        }
        val chunks = manifest.chunks.map { ref ->
            val value = transport.v2FetchCheckpoint(
                endpoint,
                token(),
                SelfHostedV2CheckpointFetchRequest(
                    descriptor.syncEpochId,
                    descriptor.checkpointId,
                    chunkIndex = ref.chunkIndex,
                    workspaceId = workspaceId,
                ),
            )
            require(value.manifest == null) { "Chunk fetch returned an unexpected checkpoint manifest." }
            requireNotNull(value.chunk) { "Self-hosted V2 checkpoint chunk ${ref.chunkIndex} is missing." }
        }
        return WorkspaceRemoteCheckpointBundleV2(pointer, manifestOuter, chunks)
    }

    override fun putCheckpointChunk(
        descriptor: SyncEpochDescriptorV2,
        ref: WorkspaceCheckpointChunkRefV2,
        chunk: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 = transport.v2PutCheckpointChunk(
        endpoint, token(), SelfHostedV2CheckpointChunkRequest(
            descriptor.syncEpochId, descriptor.checkpointId, ref, chunk, workspaceId,
        ),
    ).toDomain()

    override fun putCheckpointManifest(
        descriptor: SyncEpochDescriptorV2,
        manifest: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 {
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(workspaceKey, descriptor.syncEpochId),
        )
        val decoded = when (val result = WorkspaceSyncControlCodecV2(
            WorkspaceObjectCipherV2(workspaceKey, materializer),
        ).decodeCheckpointManifest(manifest, descriptor.syncEpochId, descriptor.checkpointId)) {
            is WorkspaceControlDecodeResultV2.Decoded -> result.value
            is WorkspaceControlDecodeResultV2.Rejected -> return WorkspaceImmutablePutResultV2.Rejected(
                result.error.code.wireValue, result.error.safeMessage,
            )
        }
        val bundle = transport.v2PutCheckpointManifest(
            endpoint, token(), SelfHostedV2CheckpointManifestRequest(
                descriptor.syncEpochId,
                descriptor.checkpointId,
                descriptor.checkpointDigest,
                decoded.chunks,
                decoded.totalObjectCount,
                manifest,
                workspaceId,
            ),
        )
        return bundle.toDomain()
    }

    /** Used by checkpoint publisher when exact manifest refs are available. */
    fun putCheckpointManifest(
        descriptor: SyncEpochDescriptorV2,
        chunks: List<WorkspaceCheckpointChunkRefV2>,
        totalObjectCount: Int,
        manifest: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 = transport.v2PutCheckpointManifest(
        endpoint, token(), SelfHostedV2CheckpointManifestRequest(
            descriptor.syncEpochId, descriptor.checkpointId, descriptor.checkpointDigest,
            chunks, totalObjectCount, manifest, workspaceId,
        ),
    ).toDomain()

    override fun compareAndSetEpochPointer(
        descriptor: SyncEpochDescriptorV2,
        expectedCurrentDigest: String?,
        pointer: EncryptedWorkspaceObjectV2,
    ): WorkspacePointerPublishResultV2 {
        val response = transport.v2CompareAndSetEpoch(
            endpoint, token(), SelfHostedV2EpochCompareAndSetRequest(
                expectedCurrentDigest, descriptor.toWire(pointer.objectDigest), pointer, workspaceId,
            ),
        )
        return when {
            response.published -> WorkspacePointerPublishResultV2.Published(response.idempotentReplay)
            response.error == "epoch_pointer_compare_and_set_failed" ->
                WorkspacePointerPublishResultV2.CompareAndSetFailed(response.current?.pointer)
            else -> WorkspacePointerPublishResultV2.Rejected(
                response.error ?: "epoch_pointer_rejected", "Self-hosted server rejected the V2 epoch pointer.",
            )
        }
    }

    override fun cleanupCheckpointDraft(
        draft: WorkspaceCheckpointDraftCleanupV2,
    ): WorkspaceCheckpointDraftCleanupResultV2 {
        if (draft.remoteProfile != remoteProfile) {
            return WorkspaceCheckpointDraftCleanupResultV2.Retained(
                "remote_profile_mismatch",
                "Checkpoint cleanup targets another remote profile.",
            )
        }
        val response = transport.v2CleanupCheckpointDraft(
            endpoint,
            token(),
            SelfHostedV2CheckpointCleanupRequest(
                epochId = draft.descriptor.syncEpochId,
                checkpointId = draft.descriptor.checkpointId,
                checkpointDigest = draft.descriptor.checkpointDigest,
                previousPointerDigest = draft.pointer.previousPointerDigest,
                chunks = draft.chunks.map { it.ref },
                workspaceId = workspaceId,
            ),
        )
        return if (response.deleted) {
            WorkspaceCheckpointDraftCleanupResultV2.Deleted(response.alreadyAbsent)
        } else {
            WorkspaceCheckpointDraftCleanupResultV2.Retained(
                response.error ?: "checkpoint_cleanup_rejected",
                "Self-hosted server retained the checkpoint draft safely.",
            )
        }
    }

    override fun pull(syncEpochId: String, cursors: Map<String, String?>, limit: Int): WorkspaceSyncPullResultV2 {
        val value = transport.v2Pull(
            endpoint, token(), SelfHostedV2PullRequest(
                syncEpochId, cursors["global"]?.toLongOrNull(), limit, workspaceId,
            ),
        )
        return WorkspaceSyncPullResultV2(
            value.units.map { unit ->
                WorkspaceEncryptedCursorUnitV2(
                    unit.epochId, unit.streamId, unit.expectedCursorValue, unit.nextCursorValue,
                    unit.unitId, unit.unitDigest, unit.objects,
                )
            },
            frontierStable = value.complete,
            safeErrorCode = value.error,
        )
    }

    override fun push(syncEpochId: String, objects: List<EncryptedWorkspaceObjectV2>): WorkspaceSyncPushResultV2 {
        val value = transport.v2Push(
            endpoint, token(), SelfHostedV2PushRequest(syncEpochId, MINIMUM_WRITER_VERSION_V2, objects, workspaceId),
        )
        return if (value.accepted) WorkspaceSyncPushResultV2.Accepted(value.acknowledgements.map {
            WorkspaceMutationAckV2(it.mutationId, it.objectId, it.objectDigest, it.idempotentReplay)
        }) else WorkspaceSyncPushResultV2.Rejected(
            value.error ?: "push_rejected", "Self-hosted server rejected the V2 immutable-object push.",
        )
    }

    override fun epochFrontiers(syncEpochId: String): List<SyncStreamFrontierV2> = transport.v2Frontiers(
        endpoint, token(), SelfHostedV2FrontierRequest(syncEpochId, workspaceId),
    ).frontiers.map { SyncStreamFrontierV2(it.streamId, it.cursorValue, it.streamDigest) }.sortedBy { it.streamId }


    private fun token(): String = accessTokenProvider().takeIf(String::isNotBlank)
        ?: error("Self-hosted V2 session token is missing; credentials redacted.")
}

private fun SyncEpochDescriptorV2.toWire(pointerDigest: String) = SelfHostedV2EpochMetadata(
    epochId = syncEpochId,
    pointerDigest = pointerDigest,
    semanticProtocolVersion = semanticProtocolVersion,
    minimumWriterProtocolVersion = minimumWriterProtocolVersion,
    keySetVersion = keySetVersion,
    remoteProfile = remoteProfile,
    metadataPrivacyMode = metadataPrivacyMode,
    supportedOfflineWindowSeconds = supportedOfflineWindowSeconds,
    checkpointId = checkpointId,
    checkpointDigest = checkpointDigest,
    previousEpochId = previousEpochId,
    previousEpochPointerDigest = previousEpochPointerDigest,
)

private fun SelfHostedV2ImmutablePutResponse.toDomain(): WorkspaceImmutablePutResultV2 =
    if (stored) WorkspaceImmutablePutResultV2.Stored(idempotentReplay)
    else WorkspaceImmutablePutResultV2.Rejected(
        error ?: "immutable_put_rejected", "Self-hosted server rejected an immutable V2 object.",
    )

private val SELF_HOSTED_WORKSPACE_ID = Regex("^workspace-[0-9a-f]{32}$")
