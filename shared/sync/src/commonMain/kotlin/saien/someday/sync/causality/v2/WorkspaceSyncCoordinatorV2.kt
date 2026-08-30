@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.sync.WorkspaceLifecycleCoordinator
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class SyncCoordinatorStatusV2 {
    SUCCESS,
    BLOCKED,
    FAILED,
}

data class WorkspaceSyncCapabilitiesV2(
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
) {
    fun incompatibility(): String? = when {
        profile != SyncRemoteProfileV2.SELF_HOSTED.wireValue ->
            "remote_profile_mismatch"
        contractId != SYNC_V2_CONTRACT_ID -> "unsupported_contract"
        semanticProtocolVersion != SEMANTIC_SYNC_PROTOCOL_VERSION_V2 -> "unsupported_contract"
        schemaSetVersion != SYNC_V2_SCHEMA_SET_VERSION -> "unsupported_schema_set"
        keySetVersion != SYNC_KEY_SET_VERSION_V2 -> "unsupported_key_set"
        metadataPrivacyMode != SyncMetadataPrivacyModeV2.OPAQUE.wireValue -> "unsupported_privacy_mode"
        maxPushObjects !in 1..100 || maxPullUnits !in 1..500 || maxEncodedBodyBytes <= 0 -> "invalid_remote_bounds"
        !supportsCheckpoints -> "checkpoint_not_supported"
        else -> null
    }
}

@Serializable
data class WorkspaceEncryptedCursorUnitV2(
    val syncEpochId: String,
    val streamId: String,
    val expectedCursorValue: String?,
    val nextCursorValue: String,
    val unitId: String,
    val unitDigest: String,
    val objects: List<EncryptedWorkspaceObjectV2>,
)

data class WorkspaceSyncPullResultV2(
    val units: List<WorkspaceEncryptedCursorUnitV2>,
    val frontierStable: Boolean,
    val safeErrorCode: String? = null,
)

data class WorkspaceMutationAckV2(
    val mutationId: String,
    val objectId: String,
    val objectDigest: String,
    val idempotentReplay: Boolean,
)

sealed interface WorkspaceSyncPushResultV2 {
    data class Accepted(val acknowledgements: List<WorkspaceMutationAckV2>) : WorkspaceSyncPushResultV2
    data class Rejected(val safeErrorCode: String, val safeMessage: String) : WorkspaceSyncPushResultV2
}

data class WorkspaceRemoteCheckpointBundleV2(
    val pointer: EncryptedWorkspaceObjectV2,
    val manifest: EncryptedWorkspaceObjectV2,
    val chunks: List<EncryptedWorkspaceObjectV2>,
)

sealed interface WorkspaceImmutablePutResultV2 {
    data class Stored(val idempotentReplay: Boolean) : WorkspaceImmutablePutResultV2
    data class Rejected(val safeErrorCode: String, val safeMessage: String) : WorkspaceImmutablePutResultV2
}

sealed interface WorkspacePointerPublishResultV2 {
    data class Published(val idempotentReplay: Boolean) : WorkspacePointerPublishResultV2
    data class CompareAndSetFailed(val current: EncryptedWorkspaceObjectV2?) : WorkspacePointerPublishResultV2
    data class Rejected(val safeErrorCode: String, val safeMessage: String) : WorkspacePointerPublishResultV2
}

data class WorkspaceCheckpointDraftChunkV2(
    val ref: WorkspaceCheckpointChunkRefV2,
    val encryptedObject: EncryptedWorkspaceObjectV2,
)

/**
 * Exact remote identities retained locally until an unreferenced checkpoint
 * draft has been collected. It intentionally excludes semantic entity rows.
 */
data class WorkspaceCheckpointDraftCleanupV2(
    val remoteProfile: String,
    val pointer: WorkspaceSyncEpochPointerV2,
    val pointerObject: EncryptedWorkspaceObjectV2,
    val manifestObject: EncryptedWorkspaceObjectV2,
    val chunks: List<WorkspaceCheckpointDraftChunkV2>,
) {
    val descriptor: SyncEpochDescriptorV2 get() = pointer.descriptor

    init {
        require(remoteProfile == descriptor.remoteProfile)
        require(pointerObject.syncEpochId == descriptor.syncEpochId)
        require(pointerObject.objectDigest.isNotBlank())
        require(pointerObject.objectType == SYNC_EPOCH_POINTER_OBJECT_TYPE_V2)
        require(pointerObject.objectId == SYNC_EPOCH_POINTER_ID_SYSTEM_V2)
        require(manifestObject.syncEpochId == descriptor.syncEpochId)
        require(manifestObject.objectDigest == descriptor.checkpointDigest)
        require(manifestObject.objectType == SYNC_CHECKPOINT_MANIFEST_OBJECT_TYPE_V2)
        require(manifestObject.objectId == descriptor.checkpointId)
        require(chunks.isNotEmpty())
        require(chunks.map { it.ref.chunkIndex } == chunks.indices.toList())
        require(chunks.all {
            it.encryptedObject.syncEpochId == descriptor.syncEpochId &&
                it.encryptedObject.objectType == SYNC_CHECKPOINT_CHUNK_OBJECT_TYPE_V2 &&
                it.encryptedObject.objectId == it.ref.chunkId &&
                it.encryptedObject.objectDigest == it.ref.chunkDigest
        })
    }
}

sealed interface WorkspaceCheckpointDraftCleanupResultV2 {
    data class Deleted(val alreadyAbsent: Boolean) : WorkspaceCheckpointDraftCleanupResultV2
    data class Retained(
        val safeErrorCode: String,
        val safeMessage: String,
    ) : WorkspaceCheckpointDraftCleanupResultV2
}

/** Transport contract used by the whole-product coordinator only. */
interface WorkspaceSyncRemoteV2 {
    val remoteProfile: String
    /** Local-only endpoint/account binding; it is never serialized remotely. */
    val authorityBindingId: String

    fun capabilities(): WorkspaceSyncCapabilitiesV2
    fun loadEpochPointer(): EncryptedWorkspaceObjectV2?
    fun fetchCheckpoint(pointer: EncryptedWorkspaceObjectV2, descriptor: SyncEpochDescriptorV2): WorkspaceRemoteCheckpointBundleV2
    fun putCheckpointChunk(
        descriptor: SyncEpochDescriptorV2,
        ref: WorkspaceCheckpointChunkRefV2,
        chunk: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2

    fun putCheckpointManifest(
        descriptor: SyncEpochDescriptorV2,
        manifest: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2

    fun compareAndSetEpochPointer(
        descriptor: SyncEpochDescriptorV2,
        expectedCurrentDigest: String?,
        pointer: EncryptedWorkspaceObjectV2,
    ): WorkspacePointerPublishResultV2

    /**
     * Deletes only the exact immutable objects of a never-authoritative draft.
     * Implementations must fail closed while the draft is current or still
     * publishable from the authenticated current pointer.
     */
    fun cleanupCheckpointDraft(
        draft: WorkspaceCheckpointDraftCleanupV2,
    ): WorkspaceCheckpointDraftCleanupResultV2

    fun pull(syncEpochId: String, cursors: Map<String, String?>, limit: Int): WorkspaceSyncPullResultV2
    fun push(syncEpochId: String, objects: List<EncryptedWorkspaceObjectV2>): WorkspaceSyncPushResultV2
    fun epochFrontiers(syncEpochId: String): List<SyncStreamFrontierV2>
}

data class WorkspaceSyncSummaryV2(
    val status: SyncCoordinatorStatusV2,
    val remoteProfile: String,
    val epochId: String?,
    val pulledUnits: Int,
    val pulledObjects: Int,
    val pushedObjects: Int,
    val generatedVersions: Int,
    val activeConflicts: Int,
    val safeErrorCode: String? = null,
    val safeMessage: String? = null,
)

/** Pull-before-push coordinator with no entity-specific or transport-specific semantic branch. */
class WorkspaceSyncCoordinatorV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val localWriterDeviceId: String,
    private val remote: WorkspaceSyncRemoteV2,
    /** Product-level prerequisite for each exact immutable outbox batch. */
    private val beforeEntityPublication: (List<WorkspaceEntityVersionV2>) -> Unit,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 = SqlDelightSyncProtocolStoreV2(localRepository.database),
    private val clock: () -> Instant = { Clock.System.now() },
    /**
     * Required by product-facing runtimes so an authenticated remote bootstrap
     * cannot switch the active epoch while a repository operation is still
     * routing to the previous local surface.
     */
    private val workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator? = null,
    /**
     * Runs after the checkpoint is locally activated but before that activation
     * transaction and the product-routing barrier commit. A failure rolls the
     * activation back, leaving the authenticated checkpoint PREPARING so a retry
     * can preserve local fallback or prior-epoch writes before switching routes.
     */
    private val bootstrapCommitHook: (() -> Pair<String, String>?)? = null,
) {
    init {
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(localWriterDeviceId))
        require(remote.remoteProfile == SyncRemoteProfileV2.SELF_HOSTED.wireValue)
    }

    fun syncOnce(): WorkspaceSyncSummaryV2 {
        val started = clock()
        val run = protocolStore.startRun(remote.remoteProfile, null, started)
        val counts = MutableWorkspaceSyncCountsV2()
        return try {
            val capabilities = remote.capabilities()
            val incompatibility = capabilities.incompatibility()
            if (capabilities.profile != remote.remoteProfile || incompatibility != null) {
                return blocked(run.runId, counts, null, incompatibility ?: "remote_profile_mismatch", "Remote entity-sync capabilities are incompatible.")
            }
            val pointerOuter = remote.loadEpochPointer()
                ?: return blocked(run.runId, counts, null, "v2_epoch_not_initialized", "No authenticated workspace sync epoch exists.")
            val epoch = crypto(pointerOuter.syncEpochId)
            val pointer = when (val decoded = epoch.control.decodeEpochPointer(pointerOuter)) {
                is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
                is WorkspaceControlDecodeResultV2.Rejected -> return blocked(
                    run.runId, counts, pointerOuter.syncEpochId, decoded.error.code.wireValue, decoded.error.safeMessage,
                )
            }
            val descriptor = pointer.descriptor
            if (descriptor.previousEpochId != null || descriptor.previousEpochPointerDigest != null ||
                descriptor.previousEpochFrontiers.isNotEmpty()
            ) {
                return blocked(
                    run.runId,
                    counts,
                    descriptor.syncEpochId,
                    "unsupported_generation_ancestry",
                    "This client supports only a first-generation workspace checkpoint.",
                )
            }
            if (descriptor.remoteProfile != remote.remoteProfile) {
                return blocked(run.runId, counts, descriptor.syncEpochId, "remote_profile_mismatch", "Authenticated epoch names another remote profile.")
            }
            if (descriptor.contractId != capabilities.contractId || descriptor.schemaSetVersion != capabilities.schemaSetVersion ||
                descriptor.keySetVersion != capabilities.keySetVersion
            ) {
                return blocked(run.runId, counts, descriptor.syncEpochId, "unsupported_contract", "Authenticated epoch and remote capabilities disagree.")
            }

            val boundAuthority = protocolStore.loadLocalAuthority()
            if (boundAuthority != null && boundAuthority.authorityBindingId != remote.authorityBindingId) {
                if (boundAuthority.remoteProfile == remote.remoteProfile &&
                    boundAuthority.epochId == descriptor.syncEpochId &&
                    boundAuthority.pointerDigest == pointerOuter.objectDigest
                ) {
                    protocolStore.rebindExactAuthority(
                        remote.remoteProfile,
                        descriptor.syncEpochId,
                        pointerOuter.objectDigest,
                        remote.authorityBindingId,
                        clock(),
                    )
                } else {
                    return blocked(
                        run.runId,
                        counts,
                        descriptor.syncEpochId,
                        "authority_binding_mismatch",
                        "The configured self-hosted account is not the authority bound to this workspace.",
                    )
                }
            }

            val localActive = protocolStore.loadActiveEpoch(remote.remoteProfile)
            val ancestryAnchor = localActive
            if (ancestryAnchor?.descriptor?.syncEpochId == descriptor.syncEpochId &&
                ancestryAnchor.descriptorDigest != pointerOuter.objectDigest
            ) {
                protocolStore.blockEpoch(
                    ancestryAnchor.remoteProfile,
                    ancestryAnchor.descriptor.syncEpochId,
                    "remote_rollback_detected",
                    "Authenticated epoch pointer changed without a new epoch.",
                )
                return blocked(
                    run.runId,
                    counts,
                    descriptor.syncEpochId,
                    "remote_rollback_detected",
                    "Authenticated epoch pointer changed without a new epoch.",
                )
            }
            if (ancestryAnchor != null && ancestryAnchor.descriptor.syncEpochId != descriptor.syncEpochId) {
                return blocked(
                    run.runId,
                    counts,
                    ancestryAnchor.descriptor.syncEpochId,
                    "incompatible_epoch",
                    "The remote authority changed its immutable initial sync generation.",
                )
            }

            val store = entityStore(epoch, descriptor.syncEpochId)
            if (localActive?.descriptor?.syncEpochId != descriptor.syncEpochId) {
                val bootstrapError = bootstrap(
                    pointerOuter,
                    pointer,
                    descriptor,
                    epoch,
                    store,
                    counts,
                    boundAuthority,
                )
                if (bootstrapError != null) {
                    return blocked(run.runId, counts, descriptor.syncEpochId, bootstrapError.first, bootstrapError.second)
                }
            }
            val active = protocolStore.loadActiveEpoch(remote.remoteProfile)
            if (active?.lifecycle == SyncEpochLifecycleV2.BLOCKED || active?.health == SyncEpochHealthV2.BLOCKED) {
                return blocked(
                    run.runId, counts, descriptor.syncEpochId,
                    active.safeErrorCode ?: "blocked_remote_input",
                    active.safeErrorMessage ?: "Workspace sync is blocked by unresolved authenticated remote input.",
                )
            }
            if (protocolStore.loadUnresolvedDeadLetters(remote.remoteProfile, descriptor.syncEpochId).any {
                    it.input.failureClass != SyncDeadLetterFailureClassV2.RETRYABLE_DEPENDENCY
                }
            ) {
                return blocked(run.runId, counts, descriptor.syncEpochId, "remote_input_unresolved", "A blocking cursor unit remains unresolved.")
            }

            val pullError = pullUntilStable(descriptor, epoch, store, capabilities, counts)
            if (pullError != null) return blocked(run.runId, counts, descriptor.syncEpochId, pullError.first, pullError.second)

            val pushError = pushPending(descriptor, epoch, store, capabilities, counts)
            if (pushError != null) return blocked(run.runId, counts, descriptor.syncEpochId, pushError.first, pushError.second)

            val finalPullError = pullUntilStable(descriptor, epoch, store, capabilities, counts)
            if (finalPullError != null) return blocked(run.runId, counts, descriptor.syncEpochId, finalPullError.first, finalPullError.second)

            counts.captureWorkspaceState(store, protocolStore, remote.remoteProfile, descriptor)
            protocolStore.finishRun(
                run.runId,
                SyncRunStatusV2.SUCCESS,
                counts.toStored(),
                clock(),
                epochId = descriptor.syncEpochId,
            )
            counts.summary(SyncCoordinatorStatusV2.SUCCESS, remote.remoteProfile, descriptor.syncEpochId)
        } catch (failure: Exception) {
            val message = (failure.message ?: "Workspace sync failed safely.").safeSyncMessageV2()
            protocolStore.finishRun(
                run.runId,
                SyncRunStatusV2.FAILED,
                counts.toStored(),
                clock(),
                epochId = protocolStore.loadActiveEpoch(remote.remoteProfile)?.descriptor?.syncEpochId,
                safeErrorCode = "v2_sync_failed",
                safeErrorMessage = message,
            )
            counts.summary(
                SyncCoordinatorStatusV2.FAILED,
                remote.remoteProfile,
                protocolStore.loadActiveEpoch(remote.remoteProfile)?.descriptor?.syncEpochId,
                "v2_sync_failed",
                message,
            )
        }
    }

    private fun bootstrap(
        pointerOuter: EncryptedWorkspaceObjectV2,
        pointer: WorkspaceSyncEpochPointerV2,
        descriptor: SyncEpochDescriptorV2,
        epoch: WorkspaceEpochCryptoV2,
        store: SqlDelightWorkspaceEntityStoreV2,
        counts: MutableWorkspaceSyncCountsV2,
        expectedAuthority: StoredLocalAuthorityV2?,
    ): Pair<String, String>? {
        val bundle = runCatching { remote.fetchCheckpoint(pointerOuter, descriptor) }.getOrElse {
            return "missing_remote_object" to (it.message ?: "Checkpoint retrieval failed.").safeSyncMessageV2()
        }
        if (bundle.pointer.objectDigest != pointerOuter.objectDigest || bundle.pointer.objectId != SYNC_EPOCH_POINTER_ID_SYSTEM_V2) {
            return "checkpoint_integrity_mismatch" to "Checkpoint bundle names another epoch pointer."
        }
        if (bundle.manifest.objectDigest != descriptor.checkpointDigest) {
            return "checkpoint_integrity_mismatch" to "Descriptor checkpoint digest does not name the fetched manifest."
        }
        val manifest = when (val decoded = epoch.control.decodeCheckpointManifest(
            bundle.manifest,
            descriptor.syncEpochId,
            descriptor.checkpointId,
        )) {
            is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
            is WorkspaceControlDecodeResultV2.Rejected -> return decoded.error.code.wireValue to decoded.error.safeMessage
        }
        if (bundle.chunks.size != manifest.chunks.size) {
            return "missing_remote_object" to "Checkpoint chunk set is incomplete."
        }
        when (protocolStore.persistAuthenticatedRemotePreparingEpoch(
            remote.remoteProfile,
            descriptor,
            pointerOuter.objectDigest,
            remote.authorityBindingId,
            localWriterDeviceId,
        )) {
            is SyncEpochPersistResultV2.ImmutableMismatch ->
                return "immutable_object_mismatch" to "The checkpoint epoch identity is already bound differently."
            is SyncEpochPersistResultV2.AlreadyStored,
            is SyncEpochPersistResultV2.Stored,
            -> Unit
        }

        var expectedCursor: String? = null
        var totalObjects = 0
        manifest.chunks.forEachIndexed { index, ref ->
            val outer = bundle.chunks[index]
            val chunk = when (val decoded = epoch.control.decodeCheckpointChunk(
                outer,
                descriptor.syncEpochId,
                descriptor.checkpointId,
                ref,
            )) {
                is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
                is WorkspaceControlDecodeResultV2.Rejected -> return decoded.error.code.wireValue to decoded.error.safeMessage
            }
            val decodedObjects = decodeEntityObjects(epoch, descriptor.syncEpochId, chunk.objects)
            if (decodedObjects is DecodeEntityObjectsResultV2.Rejected) {
                return decodedObjects.code to decodedObjects.message
            }
            decodedObjects as DecodeEntityObjectsResultV2.Decoded
            val nextCursor = "${ref.chunkIndex}:${ref.chunkDigest}"
            val result = store.applyRemoteCursorUnit(
                RemoteWorkspaceCursorUnitV2(
                    remoteProfile = remote.remoteProfile,
                    cursor = WorkspaceRemoteCursorAdvanceV2(
                        streamId = "checkpoint:${descriptor.checkpointId}",
                        expectedCursorValue = expectedCursor,
                        nextCursorValue = nextCursor,
                        unitId = ref.chunkId,
                        unitDigest = ref.chunkDigest,
                    ),
                    mutations = decodedObjects.mutations,
                    appliedAt = clock(),
                ),
            )
            if (result is WorkspaceRemoteUnitApplyResultV2.Rejected) {
                return result.error.code.wireValue to result.error.safeMessage
            }
            expectedCursor = nextCursor
            totalObjects += chunk.objects.size
            counts.pulledUnits++
            counts.pulledObjects += chunk.objects.size
            when (result) {
                is WorkspaceRemoteUnitApplyResultV2.Applied -> counts.observeApplied(result, decodedObjects.mutations)
                is WorkspaceRemoteUnitApplyResultV2.AlreadyApplied -> counts.replays += chunk.objects.size
                is WorkspaceRemoteUnitApplyResultV2.Rejected -> Unit
            }
        }
        if (totalObjects != manifest.totalObjectCount ||
            store.loadProjection(WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            )) == null
        ) {
            return "checkpoint_integrity_mismatch" to "Checkpoint did not reconstruct its complete declared workspace."
        }
        val activate: () -> Pair<String, String>? = {
            val currentAuthority = protocolStore.loadLocalAuthority()
            val targetAlreadyActive = currentAuthority?.let {
                it.remoteProfile == remote.remoteProfile &&
                    it.epochId == descriptor.syncEpochId &&
                    it.pointerDigest == pointerOuter.objectDigest
            } == true
            if (!targetAlreadyActive && !sameAuthorityIdentityV2(currentAuthority, expectedAuthority)) {
                "local_authority_changed" to
                    "The local workspace authority changed before checkpoint activation; retry against the current authority."
            } else {
                val queries = localRepository.database.somedayQueries
                val encodedManifest = epoch.cipher.encodeJson(bundle.manifest)
                var hookFailure: Pair<String, String>? = null
                localRepository.database.transaction {
                    val existingCheckpoint = queries.selectCheckpointSystemV2(
                        remote.remoteProfile,
                        descriptor.syncEpochId,
                        descriptor.checkpointId,
                    ).executeAsOneOrNull()
                    if (existingCheckpoint == null) {
                        queries.insertCheckpointSystemV2(
                            remote.remoteProfile,
                            descriptor.syncEpochId,
                            descriptor.checkpointId,
                            bundle.manifest.objectDigest,
                            encodedManifest,
                            "active",
                            manifest.createdAt.toEpochMilliseconds(),
                            clock().toEpochMilliseconds(),
                        )
                    } else {
                        require(existingCheckpoint.manifest_digest == bundle.manifest.objectDigest &&
                            existingCheckpoint.encoded_manifest == encodedManifest) {
                            "The local checkpoint identity changed before bootstrap activation."
                        }
                        queries.updateCheckpointStateSystemV2(
                            "active",
                            clock().toEpochMilliseconds(),
                            remote.remoteProfile,
                            descriptor.syncEpochId,
                            descriptor.checkpointId,
                        )
                    }
                    protocolStore.activateEpoch(
                        remote.remoteProfile,
                        descriptor.syncEpochId,
                        clock(),
                        localWriterDeviceId,
                        remote.authorityBindingId,
                    )
                    hookFailure = bootstrapCommitHook?.invoke()
                    if (hookFailure != null) rollback()
                }
                hookFailure
            }
        }
        return if (workspaceLifecycleCoordinator != null) {
            workspaceLifecycleCoordinator.productAccess(activate)
        } else {
            activate()
        }
    }

    private fun pullUntilStable(
        descriptor: SyncEpochDescriptorV2,
        epoch: WorkspaceEpochCryptoV2,
        store: SqlDelightWorkspaceEntityStoreV2,
        capabilities: WorkspaceSyncCapabilitiesV2,
        counts: MutableWorkspaceSyncCountsV2,
    ): Pair<String, String>? {
        val retryableBlockers = protocolStore.loadUnresolvedDeadLetters(
            remote.remoteProfile,
            descriptor.syncEpochId,
        ).filter {
            it.input.failureClass == SyncDeadLetterFailureClassV2.RETRYABLE_DEPENDENCY
        }.associateByTo(mutableMapOf()) {
            it.input.streamId to it.input.unitId
        }

        fun markRetryableBlockerResolved(key: Pair<String, String>) {
            protocolStore.resolveDeadLetter(
                remote.remoteProfile,
                descriptor.syncEpochId,
                key.first,
                key.second,
            )
            retryableBlockers.remove(key)
        }

        fun resolveRetryableBlocker(unit: WorkspaceEncryptedCursorUnitV2) {
            val key = unit.streamId to unit.unitId
            val blocker = retryableBlockers[key] ?: return
            val input = blocker.input
            val matchesAuthenticatedUnit = input.cursorValue == unit.expectedCursorValue &&
                input.unitDigest?.let { it == unit.unitDigest } != false &&
                input.objectId?.let { objectId ->
                    unit.objects.any { it.objectId == objectId && input.objectDigest == it.objectDigest }
                } != false
            if (matchesAuthenticatedUnit) {
                markRetryableBlockerResolved(key)
            }
        }

        retryableBlockers.values.toList().forEach { blocker ->
            val input = blocker.input
            val cursor = store.loadCursor(remote.remoteProfile, input.streamId)
            val appliedBeforeInterruption = cursor?.unitId == input.unitId &&
                input.unitDigest?.let { it == cursor.unitDigest } == true
            if (appliedBeforeInterruption) {
                markRetryableBlockerResolved(input.streamId to input.unitId)
            }
        }

        repeat(MAX_PULL_ROUNDS_SYSTEM_V2) {
            val cursors = store.loadCursors(remote.remoteProfile).associate { it.streamId to it.cursorValue }
            val pulled = remote.pull(descriptor.syncEpochId, cursors, capabilities.maxPullUnits)
            pulled.safeErrorCode?.let { code ->
                return code to when (code) {
                    "remote_rollback_detected" -> "The remote cursor frontier is older than this device's durable authenticated cursor."
                    else -> "The remote rejected this cursor without allowing push."
                }
            }
            if (pulled.units.size > capabilities.maxPullUnits) return "transport_metadata_mismatch" to "Remote exceeded its advertised pull-unit bound."
            if (pulled.units.isEmpty()) {
                if (pulled.frontierStable) return null
                return@repeat
            }
            if (pulled.units.map { it.streamId to it.unitId }.distinct().size != pulled.units.size) {
                return "transport_metadata_mismatch" to "Remote repeated a cursor unit in one bounded pull response."
            }
            val remaining = pulled.units.toMutableList()
            val decodedUnits = mutableMapOf<Pair<String, String>, DecodeEntityObjectsResultV2.Decoded>()
            val missingParents = mutableMapOf<Pair<String, String>, DecodeEntityObjectsResultV2.Rejected>()
            data class BatchCandidate(
                val encrypted: WorkspaceEncryptedCursorUnitV2,
                val decoded: DecodeEntityObjectsResultV2.Decoded,
                val remoteUnit: RemoteWorkspaceCursorUnitV2,
            )

            while (remaining.isNotEmpty()) {
                val virtualCursors = store.loadCursors(remote.remoteProfile)
                    .associateTo(mutableMapOf<String, String?>()) { it.streamId to it.cursorValue }
                val selected = mutableSetOf<Pair<String, String>>()
                val candidates = mutableListOf<BatchCandidate>()
                var selectedObjects = 0
                selection@ while (
                    candidates.size < MAX_REMOTE_APPLY_BATCH_UNITS_V2 &&
                    selectedObjects < MAX_REMOTE_APPLY_BATCH_MUTATIONS_V2
                ) {
                    var selectedInPass = false
                    for (unit in remaining) {
                        val unitKey = unit.streamId to unit.unitId
                        if (unitKey in selected || virtualCursors[unit.streamId] != unit.expectedCursorValue) continue
                        if (unit.syncEpochId != descriptor.syncEpochId || unit.objects.size > 100) break@selection
                        if (selectedObjects + unit.objects.size > MAX_REMOTE_APPLY_BATCH_MUTATIONS_V2) break@selection
                        val decoded = decodedUnits[unitKey] ?: when (
                            val value = decodeEntityObjects(epoch, descriptor.syncEpochId, unit.objects)
                        ) {
                            is DecodeEntityObjectsResultV2.Rejected -> break@selection
                            is DecodeEntityObjectsResultV2.Decoded -> value.also { decodedUnits[unitKey] = it }
                        }
                        candidates += BatchCandidate(
                            encrypted = unit,
                            decoded = decoded,
                            remoteUnit = RemoteWorkspaceCursorUnitV2(
                                remote.remoteProfile,
                                WorkspaceRemoteCursorAdvanceV2(
                                    unit.streamId,
                                    unit.expectedCursorValue,
                                    unit.nextCursorValue,
                                    unit.unitId,
                                    unit.unitDigest,
                                ),
                                decoded.mutations,
                                clock(),
                            ),
                        )
                        selected += unitKey
                        selectedObjects += unit.objects.size
                        virtualCursors[unit.streamId] = unit.nextCursorValue
                        selectedInPass = true
                        if (candidates.size == MAX_REMOTE_APPLY_BATCH_UNITS_V2 ||
                            selectedObjects == MAX_REMOTE_APPLY_BATCH_MUTATIONS_V2
                        ) break@selection
                    }
                    if (!selectedInPass) break
                }

                var committedBatch = false
                var candidateCount = candidates.size
                while (candidateCount > 0 && !committedBatch) {
                    val attempted = candidates.take(candidateCount)
                    when (val batch = store.applyRemoteCursorUnitsAtomically(attempted.map { it.remoteUnit })) {
                        is WorkspaceRemoteBatchApplyResultV2.Committed -> {
                            check(batch.units.size == attempted.size)
                            batch.units.forEach { committed ->
                                val candidate = attempted[committed.inputIndex]
                                when (val applied = committed.result) {
                                    is WorkspaceRemoteUnitApplyResultV2.Applied ->
                                        counts.observeApplied(applied, candidate.decoded.mutations)
                                    is WorkspaceRemoteUnitApplyResultV2.AlreadyApplied ->
                                        counts.replays += candidate.encrypted.objects.size
                                }
                                resolveRetryableBlocker(candidate.encrypted)
                                missingParents.remove(candidate.encrypted.streamId to candidate.encrypted.unitId)
                                check(remaining.remove(candidate.encrypted))
                                counts.pulledUnits++
                                counts.pulledObjects += candidate.encrypted.objects.size
                            }
                            committedBatch = true
                        }
                        is WorkspaceRemoteBatchApplyResultV2.Rejected -> {
                            candidateCount = batch.failedUnitIndex
                        }
                    }
                }
                if (committedBatch) continue

                // A dependency or authenticated input error can invalidate the optimistic
                // ordered batch. Advance one independently eligible unit, then rebuild the
                // bounded batch from the new durable frontier.
                var madeProgress = false
                val iterator = remaining.listIterator()
                while (iterator.hasNext()) {
                    val unit = iterator.next()
                    val unitKey = unit.streamId to unit.unitId
                    if (unit.syncEpochId != descriptor.syncEpochId || unit.objects.size > 100) {
                        return "transport_metadata_mismatch" to "Remote cursor unit has invalid epoch or bounds."
                    }
                    val current = store.loadCursor(remote.remoteProfile, unit.streamId)
                    if (current?.cursorValue != unit.expectedCursorValue) {
                        val waitsForPulledPredecessor = remaining.any { predecessor ->
                            predecessor.streamId == unit.streamId &&
                                predecessor.unitId != unit.unitId &&
                                predecessor.nextCursorValue == unit.expectedCursorValue
                        }
                        if (waitsForPulledPredecessor) continue
                        return "remote_rollback_detected" to "Remote cursor unit does not continue the durable authenticated stream."
                    }
                    val decoded = decodedUnits[unitKey] ?: when (
                        val value = decodeEntityObjects(epoch, descriptor.syncEpochId, unit.objects)
                    ) {
                        is DecodeEntityObjectsResultV2.Rejected -> {
                            recordBlocker(descriptor.syncEpochId, unit, value)
                            return value.code to value.message
                        }
                        is DecodeEntityObjectsResultV2.Decoded -> value.also { decodedUnits[unitKey] = it }
                    }
                    when (val applied = store.applyRemoteCursorUnit(
                        RemoteWorkspaceCursorUnitV2(
                            remote.remoteProfile,
                            WorkspaceRemoteCursorAdvanceV2(
                                unit.streamId,
                                unit.expectedCursorValue,
                                unit.nextCursorValue,
                                unit.unitId,
                                unit.unitDigest,
                            ),
                            decoded.mutations,
                            clock(),
                        ),
                    )) {
                        is WorkspaceRemoteUnitApplyResultV2.Rejected -> {
                            val synthetic = DecodeEntityObjectsResultV2.Rejected(
                                unit.objects.firstOrNull(),
                                applied.error.code.wireValue,
                                applied.error.safeMessage,
                            )
                            if (applied.error.code == WorkspaceStoreErrorCodeV2.MISSING_PARENT) {
                                missingParents[unitKey] = synthetic
                                continue
                            }
                            recordBlocker(descriptor.syncEpochId, unit, synthetic)
                            return synthetic.code to synthetic.message
                        }
                        is WorkspaceRemoteUnitApplyResultV2.Applied -> {
                            counts.observeApplied(applied, decoded.mutations)
                        }
                        is WorkspaceRemoteUnitApplyResultV2.AlreadyApplied -> counts.replays += unit.objects.size
                    }
                    resolveRetryableBlocker(unit)
                    missingParents.remove(unitKey)
                    iterator.remove()
                    counts.pulledUnits++
                    counts.pulledObjects += unit.objects.size
                    madeProgress = true
                    break
                }
                if (!madeProgress) {
                    val blocker = remaining.firstOrNull { unit ->
                        store.loadCursor(remote.remoteProfile, unit.streamId)?.cursorValue == unit.expectedCursorValue
                    }
                    val failure = blocker?.let { missingParents[it.streamId to it.unitId] }
                    if (blocker != null && failure != null) {
                        recordBlocker(descriptor.syncEpochId, blocker, failure)
                        return failure.code to failure.message
                    }
                    return "remote_rollback_detected" to "Remote cursor units do not form continuous per-writer streams."
                }
            }
            if (pulled.frontierStable) return null
        }
        return "pull_safety_bound" to "Remote frontier did not stabilize within the bounded sync pass."
    }

    private fun pushPending(
        descriptor: SyncEpochDescriptorV2,
        epoch: WorkspaceEpochCryptoV2,
        store: SqlDelightWorkspaceEntityStoreV2,
        capabilities: WorkspaceSyncCapabilitiesV2,
        counts: MutableWorkspaceSyncCountsV2,
    ): Pair<String, String>? {
        if (protocolStore.loadUnresolvedDeadLetters(remote.remoteProfile, descriptor.syncEpochId).isNotEmpty()) {
            return "remote_input_unresolved" to "Push is blocked while a cursor unit is unresolved."
        }
        while (true) {
            val plan = store.loadPendingPublicationPlan(remote.remoteProfile)
            if (plan.isEmpty()) return null
            plan.chunked(capabilities.maxPushObjects).forEach publicationBatch@ { plannedBatch ->
                val publications = runCatching {
                    store.loadPendingPublicationBatch(remote.remoteProfile, plannedBatch)
                }.getOrElse {
                    return "local_transaction_failed" to
                        "A durable publication plan no longer matches the local outbox."
                }
                if (publications.isEmpty()) return@publicationBatch
                val batch = publications.map { it.pending }
                runCatching { beforeEntityPublication(publications.map { it.version }) }.getOrElse {
                    return "entity_publication_prerequisite_failed" to
                        "A referenced media asset is not fully published for this workspace authority."
                }
                val objects = batch.map { pendingValue ->
                    epoch.cipher.decodeJson(pendingValue.encodedOuter).getOrElse {
                        return "local_transaction_failed" to "A durable outbox outer object is malformed."
                    }.also { outer ->
                        if (outer.mutationId != pendingValue.mutationId || outer.objectId != pendingValue.objectId ||
                            outer.objectDigest != pendingValue.objectDigest || outer.writerDeviceId != pendingValue.writerDeviceId
                        ) return "mutation_reuse_mismatch" to "A durable outbox tuple no longer matches its encrypted object."
                    }
                }
                when (val pushed = remote.push(descriptor.syncEpochId, objects)) {
                    is WorkspaceSyncPushResultV2.Rejected -> return pushed.safeErrorCode to pushed.safeMessage
                    is WorkspaceSyncPushResultV2.Accepted -> {
                        if (pushed.acknowledgements.size != batch.size) {
                            return "transport_metadata_mismatch" to "Remote returned an incomplete entity acknowledgement set."
                        }
                        batch.zip(pushed.acknowledgements).forEach { (pendingValue, ack) ->
                            if (ack.mutationId != pendingValue.mutationId || ack.objectId != pendingValue.objectId ||
                                ack.objectDigest != pendingValue.objectDigest
                            ) return "mutation_reuse_mismatch" to "Remote acknowledgement does not match the exact outbox tuple."
                        }
                        batch.zip(pushed.acknowledgements).forEach { (pendingValue, ack) ->
                            check(store.acknowledgePending(remote.remoteProfile, ack.mutationId, ack.objectId, ack.objectDigest))
                            counts.pushedObjects++
                            counts.pushedMutations++
                            if (ack.idempotentReplay) counts.replays++
                        }
                    }
                }
            }
        }
    }

    private fun decodeEntityObjects(
        epoch: WorkspaceEpochCryptoV2,
        epochId: String,
        objects: List<EncryptedWorkspaceObjectV2>,
    ): DecodeEntityObjectsResultV2 {
        val mutations = mutableListOf<RemoteWorkspaceMutationV2>()
        val identities = mutableMapOf<String, Pair<String, String>>()
        objects.forEach { outer ->
            if (outer.syncEpochId != epochId || outer.objectType != WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2 || outer.mutationId == null) {
                return DecodeEntityObjectsResultV2.Rejected(outer, "transport_metadata_mismatch", "Remote object is not an entity version for this epoch.")
            }
            val prior = identities.put(outer.mutationId, outer.objectId to outer.objectDigest)
            if (prior != null && prior != outer.objectId to outer.objectDigest) {
                return DecodeEntityObjectsResultV2.Rejected(outer, "mutation_reuse_mismatch", "One cursor unit reuses a mutation id for another object.")
            }
            val plaintext = when (val decrypted = epoch.cipher.decrypt(outer)) {
                is EncryptedWorkspaceObjectDecodeResultV2.Decoded -> decrypted.plaintext
                is EncryptedWorkspaceObjectDecodeResultV2.Rejected -> return DecodeEntityObjectsResultV2.Rejected(
                    outer,
                    decrypted.error.code.wireValue,
                    decrypted.error.safeMessage,
                )
            }
            val version = when (val decoded = epoch.wire.decode(
                plaintext,
                WorkspaceVersionOuterMetadataV2(outer.syncEpochId, outer.objectId, outer.objectDigest),
            )) {
                is WorkspaceEntityWireDecodeResultV2.Decoded -> decoded.version
                is WorkspaceEntityWireDecodeResultV2.Rejected -> return DecodeEntityObjectsResultV2.Rejected(
                    outer,
                    decoded.error.code.wireValue,
                    decoded.error.safeMessage,
                )
            }
            mutations += RemoteWorkspaceMutationV2(
                mutationId = outer.mutationId,
                objectId = outer.objectId,
                objectDigest = outer.objectDigest,
                writerDeviceId = outer.writerDeviceId,
                version = version,
            )
        }
        return DecodeEntityObjectsResultV2.Decoded(mutations)
    }

    private fun recordBlocker(
        epochId: String,
        unit: WorkspaceEncryptedCursorUnitV2,
        error: DecodeEntityObjectsResultV2.Rejected,
    ) {
        val persistent = error.code !in setOf("missing_parent", "transport_unavailable")
        protocolStore.recordDeadLetter(
            SyncDeadLetterInputV2(
                remote.remoteProfile,
                epochId,
                unit.streamId,
                unit.unitId,
                unit.expectedCursorValue,
                unit.unitDigest,
                error.outer?.objectId,
                error.outer?.objectDigest,
                WORKSPACE_CURSOR_UNIT_JSON_V2.encodeToString(unit),
                if (persistent) SyncDeadLetterFailureClassV2.PERSISTENT_INTEGRITY else SyncDeadLetterFailureClassV2.RETRYABLE_DEPENDENCY,
                error.code,
                error.message,
            ),
            clock(),
        )
    }

    private fun crypto(epochId: String): WorkspaceEpochCryptoV2 {
        val keys = SyncEpochKeyDerivationV2().derive(workspaceKey, epochId)
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(keys)
        val validator = WorkspaceEntityValidatorV2(materializer)
        val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
        return WorkspaceEpochCryptoV2(
            materializer,
            validator,
            WorkspaceEntityWireCodecV2(materializer, validator),
            cipher,
            WorkspaceSyncControlCodecV2(cipher),
        )
    }

    private fun entityStore(epoch: WorkspaceEpochCryptoV2, epochId: String): SqlDelightWorkspaceEntityStoreV2 =
        SqlDelightWorkspaceEntityStoreV2(
            localRepository.database,
            epochId,
            WorkspaceEntityCausalityEngineV2(epoch.materializer, epoch.validator),
            epoch.materializer,
            epoch.wire,
            WorkspaceOutboxEncoderV2 { version, mutationId ->
                val outer = epoch.cipher.encryptEntity(
                    version,
                    mutationId,
                    localWriterDeviceId,
                    epoch.wire.encode(version),
                )
                PreparedWorkspaceOutboxObjectV2(localWriterDeviceId, epoch.cipher.encodeJson(outer))
            },
        )

    private fun blocked(
        runId: String,
        counts: MutableWorkspaceSyncCountsV2,
        epochId: String?,
        code: String,
        message: String,
    ): WorkspaceSyncSummaryV2 {
        val safe = message.safeSyncMessageV2()
        epochId?.let { id ->
            runCatching {
                val descriptor = protocolStore.loadEpoch(remote.remoteProfile, id)?.descriptor
                val store = entityStore(crypto(id), id)
                if (descriptor != null) {
                    counts.captureWorkspaceState(store, protocolStore, remote.remoteProfile, descriptor)
                } else {
                    counts.activeConflicts = store.loadStateCounts().activeConflicts
                }
            }
        }
        protocolStore.finishRun(
            runId,
            SyncRunStatusV2.BLOCKED,
            counts.toStored(),
            clock(),
            epochId = epochId,
            safeErrorCode = code,
            safeErrorMessage = safe,
        )
        return counts.summary(SyncCoordinatorStatusV2.BLOCKED, remote.remoteProfile, epochId, code, safe)
    }

    private companion object {
        const val MAX_PULL_ROUNDS_SYSTEM_V2 = 8
    }
}

private fun sameAuthorityIdentityV2(
    left: StoredLocalAuthorityV2?,
    right: StoredLocalAuthorityV2?,
): Boolean =
    when {
        left == null || right == null -> left == null && right == null
        else ->
            left.remoteProfile == right.remoteProfile &&
                left.epochId == right.epochId &&
                left.authorityBindingId == right.authorityBindingId &&
                left.pointerDigest == right.pointerDigest
    }

private data class WorkspaceEpochCryptoV2(
    val materializer: CanonicalWorkspaceCausalityMaterializerV2,
    val validator: WorkspaceEntityValidatorV2,
    val wire: WorkspaceEntityWireCodecV2,
    val cipher: WorkspaceObjectCipherV2,
    val control: WorkspaceSyncControlCodecV2,
)

private sealed interface DecodeEntityObjectsResultV2 {
    data class Decoded(val mutations: List<RemoteWorkspaceMutationV2>) : DecodeEntityObjectsResultV2
    data class Rejected(
        val outer: EncryptedWorkspaceObjectV2?,
        val code: String,
        val message: String,
    ) : DecodeEntityObjectsResultV2
}

private data class MutableWorkspaceSyncCountsV2(
    var pulledUnits: Int = 0,
    var pulledObjects: Int = 0,
    var pushedObjects: Int = 0,
    var generatedVersions: Int = 0,
    var activeConflicts: Int = 0,
    var storedVersions: Int = 0,
    var replays: Int = 0,
    var fastForwards: Int = 0,
    var equivalentMerges: Int = 0,
    var deletionMerges: Int = 0,
    var fieldMerges: Int = 0,
    var activeNoteConflicts: Int = 0,
    var activeNotebookConflicts: Int = 0,
    var activePreferenceConflicts: Int = 0,
    var supersededConflicts: Int = 0,
    var projectionWarnings: Int = 0,
    var deadLetters: Int = 0,
    var pushedMutations: Int = 0,
) {
    fun observeApplied(
        applied: WorkspaceRemoteUnitApplyResultV2.Applied,
        incoming: List<RemoteWorkspaceMutationV2>,
    ) {
        val generated = applied.plans.values.flatMap { it.generatedVersions }
        generatedVersions += generated.size
        storedVersions += (incoming.size - applied.replayedMutations).coerceAtLeast(0) + generated.size
        replays += applied.replayedMutations
        equivalentMerges += generated.count { it.mergeAlgorithmVersion == EQUIVALENT_MERGE_ALGORITHM_V2 }
        deletionMerges += generated.count { it.mergeAlgorithmVersion == DELETION_MERGE_ALGORITHM_V2 }
        fieldMerges += generated.count { it.mergeAlgorithmVersion == FIELD_MERGE_ALGORITHM_V2 }
        val incomingById = incoming.associate { it.version.versionId to it.version }
        fastForwards += applied.plans.values.count { plan ->
            plan.generatedVersions.isEmpty() &&
                (plan.outcome as? WorkspaceReconciliationOutcomeV2.Projected)?.headVersionId?.let { headId ->
                    incomingById[headId]?.generation?.let { it > 0 } == true
                } == true
        }
        supersededConflicts += applied.plans.values.sumOf { plan ->
            plan.conflictStates.count { it.lifecycle == WorkspaceConflictLifecycleV2.SUPERSEDED }
        }
    }

    fun captureWorkspaceState(
        store: SqlDelightWorkspaceEntityStoreV2,
        protocolStore: SqlDelightSyncProtocolStoreV2,
        remoteProfile: String,
        descriptor: SyncEpochDescriptorV2,
    ) {
        val state = store.loadStateCounts()
        activeConflicts = state.activeConflicts
        activeNoteConflicts = state.activeConflictsByEntityType[WorkspaceEntityTypeV2.NOTE] ?: 0
        activeNotebookConflicts = state.activeConflictsByEntityType[WorkspaceEntityTypeV2.NOTEBOOK] ?: 0
        activePreferenceConflicts = state.activeConflictsByEntityType[WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES] ?: 0
        projectionWarnings = state.projectionWarnings
        deadLetters = protocolStore.loadUnresolvedDeadLetters(remoteProfile, descriptor.syncEpochId).size
    }

    fun toStored() = SyncRunCountersV2(
        pulledUnits = pulledUnits.toLong(),
        pulledObjects = pulledObjects.toLong(),
        pushedObjects = pushedObjects.toLong(),
        autoMergedEntities = generatedVersions.toLong(),
        activeConflicts = activeConflicts.toLong(),
        storedVersions = storedVersions.toLong(),
        replays = replays.toLong(),
        fastForwards = fastForwards.toLong(),
        equivalentMerges = equivalentMerges.toLong(),
        deletionMerges = deletionMerges.toLong(),
        fieldMerges = fieldMerges.toLong(),
        activeNoteConflicts = activeNoteConflicts.toLong(),
        activeNotebookConflicts = activeNotebookConflicts.toLong(),
        activePreferenceConflicts = activePreferenceConflicts.toLong(),
        supersededConflicts = supersededConflicts.toLong(),
        projectionWarnings = projectionWarnings.toLong(),
        deadLetters = deadLetters.toLong(),
        pushedMutations = pushedMutations.toLong(),
    )

    fun summary(
        status: SyncCoordinatorStatusV2,
        profile: String,
        epochId: String?,
        code: String? = null,
        message: String? = null,
    ) = WorkspaceSyncSummaryV2(
        status,
        profile,
        epochId,
        pulledUnits,
        pulledObjects,
        pushedObjects,
        generatedVersions,
        activeConflicts,
        code,
        message,
    )
}

private fun safeRunHorizonV2(createdAtMilliseconds: Long, windowSeconds: Long): Long {
    val increment = if (windowSeconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else windowSeconds * 1_000L
    return if (increment == Long.MAX_VALUE || createdAtMilliseconds > Long.MAX_VALUE - increment) {
        Long.MAX_VALUE
    } else {
        createdAtMilliseconds + increment
    }
}

private fun String.safeSyncMessageV2(): String =
    replace(Regex("(?i)(bearer|token|password|secret|title|body|place)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
        .take(500)

private val WORKSPACE_CURSOR_UNIT_JSON_V2 = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
