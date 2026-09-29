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
    private val protocol1Known: (String, String) -> Boolean = { _, _ -> false },
    private val verifyLegacy: (saien.someday.domain.settings.SelfHostedSessionCredentials) -> Boolean = { false },
    private val onIssuance: (saien.someday.domain.settings.SelfHostedSessionCredentials) -> Unit = {},
    private val onAccountFailure: (String, String, SelfHostedAccountRequestContext, SelfHostedSyncHttpException) -> Unit = { _, _, _, _ -> },
) {
    private val refreshMutex = Mutex()

    fun isVerifiedLegacy(credentials: saien.someday.domain.settings.SelfHostedSessionCredentials): Boolean =
        credentials.accountProtocolVersion == null && !protocol1Known(credentials.endpoint, credentials.userId) && verifyLegacy(credentials)

    fun <T> authorized(
        endpoint: String,
        authenticatedUserId: String,
        suppliedToken: String,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
        request: (String, SelfHostedAccountRequestContext) -> T,
    ): T {
        val expectedBinding = selfHostedAuthorityBindingId(endpoint, authenticatedUserId)
        var effectiveContext = accountContext.copy(protocol1Known = accountContext.protocol1Known || protocol1Known(endpoint, authenticatedUserId))
        fun reject(code: SelfHostedErrorCode): Nothing = throw SelfHostedSyncHttpException(
            code.statuses.first(), "Self-hosted session authority changed; credentials redacted.", code, protocol1 = true,
        )
        fun currentCredentials() = sessionStore.load()?.takeIf { it.authorityBindingId == expectedBinding }
            ?: sessionStore.loadForAuthority(expectedBinding)
        fun requireIncarnation(credentials: saien.someday.domain.settings.SelfHostedSessionCredentials) {
            if (credentials.accountIncarnation != accountContext.accountIncarnation) reject(SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH)
        }
        fun <R> observed(action: () -> R): R = try {
            action()
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.protocol1) effectiveContext = effectiveContext.copy(protocol1Known = true)
            onAccountFailure(endpoint, authenticatedUserId, effectiveContext, failure)
            throw failure
        }
        val initial = observed { currentCredentials()?.also(::requireIncarnation) }
        if (initial?.accountProtocolVersion == 1) effectiveContext = effectiveContext.copy(protocol1Known = true)
        // Verification is tied to the same credential, before an ordinary legacy
        // 401 can permit a refresh. Unknown authority is never legacy evidence.
        val verifiedLegacy = observed { initial?.let { !effectiveContext.protocol1Known && isVerifiedLegacy(it) } == true }
        effectiveContext = effectiveContext.copy(protocol1Known = effectiveContext.protocol1Known || protocol1Known(endpoint, authenticatedUserId))
        val firstToken = initial?.accessToken ?: suppliedToken
        return try {
            observed { request(firstToken, effectiveContext) }
        } catch (failure: SelfHostedSyncHttpException) {
            val refreshable = failure.status == 401 && (
                failure.errorCode == SelfHostedErrorCode.UNAUTHORIZED ||
                    (failure.errorCode == null && !effectiveContext.protocol1Known && verifiedLegacy)
                )
            if (!refreshable) throw failure
            val retryToken = observed {
                runBlocking {
                    refreshMutex.withLock {
                        val current = currentCredentials() ?: reject(SelfHostedErrorCode.UNAUTHORIZED)
                        requireIncarnation(current)
                        if (current.accessToken != firstToken) return@withLock current.accessToken
                        val refreshed = authenticationTransport.refresh(
                            current.endpoint, SelfHostedRefreshRequest(current.refreshToken), effectiveContext,
                        )
                        if (refreshed.user.id != current.userId) reject(SelfHostedErrorCode.UNAUTHORIZED)
                        val issuedIncarnation = refreshed.accountIncarnation
                        if (issuedIncarnation == null && (effectiveContext.protocol1Known || !verifiedLegacy)) {
                            throw SelfHostedProtocolException(SelfHostedProtocolFailureReason.MISSING_ISSUANCE_HEADER)
                        }
                        if (issuedIncarnation != null && issuedIncarnation != current.accountIncarnation) {
                            reject(SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH)
                        }
                        // Login, replacement or logout may have finished while
                        // refresh was in flight. Never overwrite their credentials.
                        val latest = currentCredentials() ?: reject(SelfHostedErrorCode.UNAUTHORIZED)
                        if (latest != current) {
                            requireIncarnation(latest)
                            effectiveContext = effectiveContext.copy(protocol1Known = effectiveContext.protocol1Known || latest.accountProtocolVersion == 1)
                            return@withLock latest.accessToken
                        }
                        val updated = current.copy(
                            userEmail = refreshed.user.email,
                            accessToken = refreshed.accessToken,
                            refreshToken = refreshed.refreshToken,
                            accountIncarnation = issuedIncarnation ?: current.accountIncarnation,
                            accountProtocolVersion = refreshed.accountProtocolVersion ?: current.accountProtocolVersion,
                        )
                        onIssuance(updated)
                        sessionStore.saveForAuthority(expectedBinding, updated)
                        if (sessionStore.load()?.authorityBindingId == expectedBinding) sessionStore.save(updated)
                        if (updated.accountProtocolVersion == 1) effectiveContext = effectiveContext.copy(protocol1Known = true)
                        updated.accessToken
                    }
                }
            }
            // Exactly one replay, retaining the attempt's captured incarnation.
            observed { request(retryToken, effectiveContext) }
        }
    }
}

interface SelfHostedSyncTransportV2 {
    fun v2Capabilities(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext()): SelfHostedV2CapabilitiesResponse
    fun v2Epoch(endpoint: String, accessToken: String, workspaceId: String, accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext()): SelfHostedV2EpochResponse
    fun v2PutCheckpointChunk(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointChunkRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedV2ImmutablePutResponse

    fun v2PutCheckpointManifest(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointManifestRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedV2ImmutablePutResponse

    fun v2FetchCheckpoint(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointFetchRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedV2CheckpointFetchResponse

    fun v2CompareAndSetEpoch(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2EpochCompareAndSetRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedV2EpochCompareAndSetResponse

    fun v2CleanupCheckpointDraft(
        endpoint: String,
        accessToken: String,
        request: SelfHostedV2CheckpointCleanupRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedV2CheckpointCleanupResponse

    fun v2Push(endpoint: String, accessToken: String, request: SelfHostedV2PushRequest, accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext()): SelfHostedV2PushResponse
    fun v2Pull(endpoint: String, accessToken: String, request: SelfHostedV2PullRequest, accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext()): SelfHostedV2PullResponse
    fun v2Frontiers(endpoint: String, accessToken: String, request: SelfHostedV2FrontierRequest, accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext()): SelfHostedV2FrontierResponse
}

class RefreshingSelfHostedSyncTransportV2(
    private val delegate: SelfHostedSyncTransportV2,
    private val sessionExecutor: RefreshingSelfHostedSessionExecutor,
    private val authenticatedUserId: String,
) : SelfHostedSyncTransportV2 {
    override fun v2Capabilities(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext) =
        sessionExecutor.authorized(endpoint, authenticatedUserId, accessToken, accountContext) { token, context ->
            delegate.v2Capabilities(endpoint, token, context)
        }
    override fun v2Epoch(endpoint: String, accessToken: String, workspaceId: String, accountContext: SelfHostedAccountRequestContext) =
        sessionExecutor.authorized(endpoint, authenticatedUserId, accessToken, accountContext) { token, context ->
            delegate.v2Epoch(endpoint, token, workspaceId, context)
        }
    override fun v2PutCheckpointChunk(endpoint: String, accessToken: String, request: SelfHostedV2CheckpointChunkRequest, accountContext: SelfHostedAccountRequestContext) =
        sessionExecutor.authorized(endpoint, authenticatedUserId, accessToken, accountContext) { token, context ->
            delegate.v2PutCheckpointChunk(endpoint, token, request, context)
        }
    override fun v2PutCheckpointManifest(endpoint: String, accessToken: String, request: SelfHostedV2CheckpointManifestRequest, accountContext: SelfHostedAccountRequestContext) =
        sessionExecutor.authorized(endpoint, authenticatedUserId, accessToken, accountContext) { token, context ->
            delegate.v2PutCheckpointManifest(endpoint, token, request, context)
        }
    override fun v2FetchCheckpoint(endpoint: String, accessToken: String, request: SelfHostedV2CheckpointFetchRequest, accountContext: SelfHostedAccountRequestContext) =
        sessionExecutor.authorized(endpoint, authenticatedUserId, accessToken, accountContext) { token, context ->
            delegate.v2FetchCheckpoint(endpoint, token, request, context)
        }
    override fun v2CompareAndSetEpoch(endpoint: String, accessToken: String, request: SelfHostedV2EpochCompareAndSetRequest, accountContext: SelfHostedAccountRequestContext) =
        sessionExecutor.authorized(endpoint, authenticatedUserId, accessToken, accountContext) { token, context ->
            delegate.v2CompareAndSetEpoch(endpoint, token, request, context)
        }
    override fun v2CleanupCheckpointDraft(endpoint: String, accessToken: String, request: SelfHostedV2CheckpointCleanupRequest, accountContext: SelfHostedAccountRequestContext) =
        sessionExecutor.authorized(endpoint, authenticatedUserId, accessToken, accountContext) { token, context ->
            delegate.v2CleanupCheckpointDraft(endpoint, token, request, context)
        }
    override fun v2Push(endpoint: String, accessToken: String, request: SelfHostedV2PushRequest, accountContext: SelfHostedAccountRequestContext) =
        sessionExecutor.authorized(endpoint, authenticatedUserId, accessToken, accountContext) { token, context ->
            delegate.v2Push(endpoint, token, request, context)
        }
    override fun v2Pull(endpoint: String, accessToken: String, request: SelfHostedV2PullRequest, accountContext: SelfHostedAccountRequestContext) =
        sessionExecutor.authorized(endpoint, authenticatedUserId, accessToken, accountContext) { token, context ->
            delegate.v2Pull(endpoint, token, request, context)
        }
    override fun v2Frontiers(endpoint: String, accessToken: String, request: SelfHostedV2FrontierRequest, accountContext: SelfHostedAccountRequestContext) =
        sessionExecutor.authorized(endpoint, authenticatedUserId, accessToken, accountContext) { token, context ->
            delegate.v2Frontiers(endpoint, token, request, context)
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
    private val accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
) : WorkspaceSyncRemoteV2 {
    private val endpoint = normalizeSelfHostedEndpoint(endpoint)
    init {
        require(SELF_HOSTED_WORKSPACE_ID.matches(workspaceId)) { "Invalid workspace scope." }
    }
    override val accountIncarnation: String get() = accountContext.accountIncarnation
    override val remoteProfile: String = SyncRemoteProfileV2.SELF_HOSTED.wireValue
    override val authorityBindingId: String = selfHostedAuthorityBindingId(this.endpoint, authenticatedUserId)

    override fun capabilities(): WorkspaceSyncCapabilitiesV2 {
        val value = transport.v2Capabilities(endpoint, token(), accountContext)
        if (value.parentIndexedMetadata) error("Initial V2 profile must keep parent metadata opaque.")
        return WorkspaceSyncCapabilitiesV2(
            value.profile, value.contractId, value.semanticProtocolVersion, value.schemaSetVersion,
            value.keySetVersion, value.metadataPrivacyMode, value.maxPushObjects, value.maxPullUnits,
            value.maxEncodedBodyBytes, value.supportsCheckpoints,
        )
    }

    override fun loadEpochPointer(): EncryptedWorkspaceObjectV2? = transport.v2Epoch(endpoint, token(), workspaceId, accountContext).pointer

    override fun fetchCheckpoint(
        pointer: EncryptedWorkspaceObjectV2,
        descriptor: SyncEpochDescriptorV2,
    ): WorkspaceRemoteCheckpointBundleV2 {
        val manifestValue = transport.v2FetchCheckpoint(
            endpoint, token(), SelfHostedV2CheckpointFetchRequest(
                descriptor.syncEpochId, descriptor.checkpointId, workspaceId = workspaceId,
            ), accountContext = accountContext)
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
                ), accountContext = accountContext)
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
        ), accountContext = accountContext).toDomain()

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
            ), accountContext = accountContext)
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
        ), accountContext = accountContext).toDomain()

    override fun compareAndSetEpochPointer(
        descriptor: SyncEpochDescriptorV2,
        expectedCurrentDigest: String?,
        pointer: EncryptedWorkspaceObjectV2,
    ): WorkspacePointerPublishResultV2 {
        val response = transport.v2CompareAndSetEpoch(
            endpoint, token(), SelfHostedV2EpochCompareAndSetRequest(
                expectedCurrentDigest, descriptor.toWire(pointer.objectDigest), pointer, workspaceId,
            ), accountContext = accountContext)
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
            ), accountContext = accountContext)
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
            ), accountContext = accountContext)
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
            endpoint, token(), SelfHostedV2PushRequest(syncEpochId, MINIMUM_WRITER_VERSION_V2, objects, workspaceId), accountContext = accountContext)
        return if (value.accepted) WorkspaceSyncPushResultV2.Accepted(value.acknowledgements.map {
            WorkspaceMutationAckV2(it.mutationId, it.objectId, it.objectDigest, it.idempotentReplay)
        }) else WorkspaceSyncPushResultV2.Rejected(
            value.error ?: "push_rejected", "Self-hosted server rejected the V2 immutable-object push.",
        )
    }

    override fun epochFrontiers(syncEpochId: String): List<SyncStreamFrontierV2> = transport.v2Frontiers(
        endpoint, token(), SelfHostedV2FrontierRequest(syncEpochId, workspaceId), accountContext = accountContext).frontiers.map { SyncStreamFrontierV2(it.streamId, it.cursorValue, it.streamDigest) }.sortedBy { it.streamId }


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
