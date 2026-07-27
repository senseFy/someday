@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
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
        profile !in setOf(SyncRemoteProfileV2.WEB_DAV.wireValue, SyncRemoteProfileV2.SELF_HOSTED.wireValue) ->
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
    val rebootstrapRequired: Boolean = false,
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

/** Transport contract used by the whole-product coordinator only. */
interface WorkspaceSyncRemoteV2 {
    val remoteProfile: String
    /** Local-only endpoint/account binding; it is never serialized remotely. */
    val authorityBindingId: String

    fun capabilities(): WorkspaceSyncCapabilitiesV2
    fun loadEpochPointer(): EncryptedWorkspaceObjectV2?
    /** Exact retained pointer used to prove a multi-epoch successor chain. */
    fun loadRetainedEpochPointer(syncEpochId: String): EncryptedWorkspaceObjectV2?
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

    fun pull(syncEpochId: String, cursors: Map<String, String?>, limit: Int): WorkspaceSyncPullResultV2
    fun push(syncEpochId: String, objects: List<EncryptedWorkspaceObjectV2>): WorkspaceSyncPushResultV2
    fun epochFrontiers(syncEpochId: String): List<SyncStreamFrontierV2>
    fun fetchRepairReplicas(syncEpochId: String, objectId: String, objectDigest: String): List<EncryptedWorkspaceObjectV2>
    fun publishRepairReplica(objectValue: EncryptedWorkspaceObjectV2): WorkspaceImmutablePutResultV2
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
    private val protocolStore: SqlDelightSyncProtocolStoreV2 = SqlDelightSyncProtocolStoreV2(localRepository.database),
    private val clock: () -> Instant = { Clock.System.now() },
    /**
     * Exact source authority authorized to hand off to an authenticated
     * checkpoint winner.  Used only by explicit migration/empty-remote CAS
     * recovery; ordinary sync never supplies it.
     */
    private val authorityHandoffFrom: StoredLocalAuthorityV2? = null,
) {
    init {
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(localWriterDeviceId))
        require(remote.remoteProfile in setOf(SyncRemoteProfileV2.WEB_DAV.wireValue, SyncRemoteProfileV2.SELF_HOSTED.wireValue))
    }

    fun syncOnce(): WorkspaceSyncSummaryV2 {
        val started = clock()
        val run = protocolStore.startRun(remote.remoteProfile, null, started)
        val counts = MutableWorkspaceSyncCountsV2()
        return try {
            val capabilities = remote.capabilities()
            val incompatibility = capabilities.incompatibility()
            if (capabilities.profile != remote.remoteProfile || incompatibility != null) {
                return blocked(run.runId, counts, null, incompatibility ?: "remote_profile_mismatch", "Remote V2 capabilities are incompatible.")
            }
            val pointerOuter = remote.loadEpochPointer()
                ?: return blocked(run.runId, counts, null, "v2_epoch_not_initialized", "No authenticated whole-product V2 epoch exists.")
            val epoch = crypto(pointerOuter.syncEpochId)
            val pointer = when (val decoded = epoch.control.decodeEpochPointer(pointerOuter)) {
                is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
                is WorkspaceControlDecodeResultV2.Rejected -> return blocked(
                    run.runId, counts, pointerOuter.syncEpochId, decoded.error.code.wireValue, decoded.error.safeMessage,
                )
            }
            val descriptor = pointer.descriptor
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
                } else if (authorityHandoffFrom == null || boundAuthority != authorityHandoffFrom) {
                    return blocked(
                        run.runId,
                        counts,
                        descriptor.syncEpochId,
                        "remote_migration_required",
                        "The configured endpoint is not the locally bound V2 authority; explicit remote migration is required.",
                    )
                }
            }

            val localActive = protocolStore.loadActiveEpoch(remote.remoteProfile)
            val ancestryAnchor = localActive ?: authorityHandoffFrom?.let { handoff ->
                protocolStore.loadEpoch(handoff.remoteProfile, handoff.epochId)
            }
            if (ancestryAnchor?.descriptor?.syncEpochId == descriptor.syncEpochId &&
                ancestryAnchor.descriptorDigest != pointerOuter.objectDigest
            ) {
                protocolStore.blockEpoch(
                    ancestryAnchor.remoteProfile,
                    ancestryAnchor.descriptor.syncEpochId,
                    "remote_rollback_detected",
                    "Authenticated epoch pointer changed without a new epoch.",
                )
                return blocked(run.runId, counts, descriptor.syncEpochId, "remote_rollback_detected", "Authenticated epoch pointer changed without a new epoch.")
            }
            val independentlyInitializedTargetWonAuthorizedRace =
                localActive == null && authorityHandoffFrom != null && descriptor.previousEpochId == null
            if (ancestryAnchor != null && ancestryAnchor.descriptor.syncEpochId != descriptor.syncEpochId &&
                !independentlyInitializedTargetWonAuthorizedRace
            ) {
                verifySuccessorChain(pointer, ancestryAnchor)?.let { failure ->
                    return blocked(run.runId, counts, descriptor.syncEpochId, failure.first, failure.second)
                }
            }

            val store = entityStore(epoch, descriptor.syncEpochId)
            if (localActive?.descriptor?.syncEpochId != descriptor.syncEpochId) {
                val bootstrapError = bootstrap(pointerOuter, pointer, descriptor, epoch, store, counts)
                if (bootstrapError != null) {
                    return blocked(run.runId, counts, descriptor.syncEpochId, bootstrapError.first, bootstrapError.second)
                }
            }
            val active = protocolStore.loadActiveEpoch(remote.remoteProfile)
            if (active?.lifecycle == SyncEpochLifecycleV2.BLOCKED || active?.health == SyncEpochHealthV2.BLOCKED) {
                return blocked(
                    run.runId, counts, descriptor.syncEpochId,
                    active.safeErrorCode ?: "repair_required",
                    active.safeErrorMessage ?: "Workspace integrity repair is required before sync can continue.",
                )
            }
            if (protocolStore.loadActiveDeadLetters(remote.remoteProfile, descriptor.syncEpochId).isNotEmpty()) {
                return blocked(run.runId, counts, descriptor.syncEpochId, "repair_required", "A blocking cursor unit must be repaired before push.")
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
            val message = (failure.message ?: "Whole-product V2 sync failed safely.").safeSyncMessageV2()
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
        when (protocolStore.persistPreparingEpoch(remote.remoteProfile, descriptor, pointerOuter.objectDigest)) {
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
                quarantine(descriptor.syncEpochId, "checkpoint", ref.chunkId, decodedObjects.outer, decodedObjects.code)
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
        val queries = localRepository.database.somedayQueries
        localRepository.database.transaction {
            queries.insertCheckpointSystemV2(
                remote.remoteProfile,
                descriptor.syncEpochId,
                descriptor.checkpointId,
                bundle.manifest.objectDigest,
                epoch.cipher.encodeJson(bundle.manifest),
                "active",
                manifest.createdAt.toEpochMilliseconds(),
                clock().toEpochMilliseconds(),
            )
            protocolStore.activateEpoch(
                remote.remoteProfile,
                descriptor.syncEpochId,
                clock(),
                localWriterDeviceId,
                remote.authorityBindingId,
            )
        }
        return null
    }

    private fun pullUntilStable(
        descriptor: SyncEpochDescriptorV2,
        epoch: WorkspaceEpochCryptoV2,
        store: SqlDelightWorkspaceEntityStoreV2,
        capabilities: WorkspaceSyncCapabilitiesV2,
        counts: MutableWorkspaceSyncCountsV2,
    ): Pair<String, String>? {
        repeat(MAX_PULL_ROUNDS_SYSTEM_V2) {
            val cursors = store.loadCursors(remote.remoteProfile).associate { it.streamId to it.cursorValue }
            val pulled = remote.pull(descriptor.syncEpochId, cursors, capabilities.maxPullUnits)
            pulled.safeErrorCode?.let { code ->
                return code to when (code) {
                    "remote_rollback_detected" -> "The remote cursor frontier is older than this device's durable authenticated cursor."
                    else -> "The remote rejected this cursor without allowing push."
                }
            }
            if (pulled.rebootstrapRequired) return "rebootstrap_required" to "Remote history is older than the supported 180-day horizon."
            if (pulled.units.size > capabilities.maxPullUnits) return "transport_metadata_mismatch" to "Remote exceeded its advertised pull-unit bound."
            if (pulled.units.isEmpty()) {
                if (pulled.frontierStable) return null
                return@repeat
            }
            for (unit in pulled.units) {
                if (unit.syncEpochId != descriptor.syncEpochId || unit.objects.size > 100) {
                    return "transport_metadata_mismatch" to "Remote cursor unit has invalid epoch or bounds."
                }
                val current = store.loadCursor(remote.remoteProfile, unit.streamId)
                if (current?.cursorValue != unit.expectedCursorValue) {
                    return "remote_rollback_detected" to "Remote cursor unit does not continue the durable authenticated stream."
                }
                val decoded = decodeEntityObjects(epoch, descriptor.syncEpochId, unit.objects)
                if (decoded is DecodeEntityObjectsResultV2.Rejected) {
                    quarantine(descriptor.syncEpochId, unit.streamId, unit.unitId, decoded.outer, decoded.code)
                    recordBlocker(descriptor.syncEpochId, unit, decoded)
                    return decoded.code to decoded.message
                }
                decoded as DecodeEntityObjectsResultV2.Decoded
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
                        recordBlocker(descriptor.syncEpochId, unit, synthetic)
                        return synthetic.code to synthetic.message
                    }
                    is WorkspaceRemoteUnitApplyResultV2.Applied -> {
                        counts.observeApplied(applied, decoded.mutations)
                    }
                    is WorkspaceRemoteUnitApplyResultV2.AlreadyApplied -> counts.replays += unit.objects.size
                }
                counts.pulledUnits++
                counts.pulledObjects += unit.objects.size
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
        if (protocolStore.loadActiveDeadLetters(remote.remoteProfile, descriptor.syncEpochId).isNotEmpty()) {
            return "repair_required" to "Push is blocked while a cursor unit is unresolved."
        }
        while (true) {
            val pending = store.loadPending(remote.remoteProfile)
            if (pending.isEmpty()) return null
            val batch = pending.sortedWith(compareBy<StoredWorkspacePendingMutationV2>(
                { store.loadVersion(it.objectId)?.generation ?: Long.MAX_VALUE },
                { store.loadVersion(it.objectId)?.entityType?.wireValue ?: "" },
                { store.loadVersion(it.objectId)?.entityId ?: "" },
                { it.objectId },
            )).take(capabilities.maxPushObjects)
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
                        return "transport_metadata_mismatch" to "Remote returned an incomplete V2 acknowledgement set."
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

    private fun quarantine(epochId: String, streamId: String, unitId: String, outer: EncryptedWorkspaceObjectV2?, code: String) {
        if (outer == null) return
        val encoded = runCatching { crypto(epochId).cipher.encodeJson(outer) }.getOrDefault("<invalid-outer>")
        val now = clock().toEpochMilliseconds()
        localRepository.database.somedayQueries.insertQuarantinedObjectV2(
            remote.remoteProfile,
            epochId,
            streamId,
            unitId,
            outer.objectId,
            outer.objectDigest,
            encoded,
            code,
            now,
            now,
            "active",
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

    /** Proves a forward epoch transition from exact authenticated pointer digests. */
    private fun verifySuccessorChain(
        newest: WorkspaceSyncEpochPointerV2,
        trusted: StoredSyncEpochV2,
    ): Pair<String, String>? {
        var cursor = newest
        val seenEpochs = mutableSetOf<String>()
        repeat(MAX_POINTER_ANCESTRY_DEPTH_SYSTEM_V2) {
            val previousEpochId = cursor.descriptor.previousEpochId
                ?: return "remote_rollback_detected" to
                    "The authenticated remote pointer does not descend from this device's trusted epoch."
            val previousDigest = cursor.descriptor.previousEpochPointerDigest
                ?: return "remote_rollback_detected" to
                    "The authenticated remote pointer omits its predecessor digest."
            if (!seenEpochs.add(previousEpochId)) {
                return "cyclic_epoch_history" to "The authenticated epoch-pointer history contains a cycle."
            }
            if (previousEpochId == trusted.descriptor.syncEpochId) {
                return if (previousDigest == trusted.descriptorDigest) null else {
                    "remote_rollback_detected" to
                        "The remote successor names a different representation of the trusted predecessor."
                }
            }
            val retained = runCatching { remote.loadRetainedEpochPointer(previousEpochId) }.getOrElse {
                return "missing_remote_object" to
                    "A retained epoch pointer required to prove the successor chain is unavailable."
            } ?: return "missing_remote_object" to
                "A retained epoch pointer required to prove the successor chain is unavailable."
            if (retained.syncEpochId != previousEpochId || retained.objectDigest != previousDigest) {
                return "remote_rollback_detected" to
                    "A retained pointer does not match the authenticated successor link."
            }
            cursor = when (val decoded = crypto(previousEpochId).control.decodeEpochPointer(retained)) {
                is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
                is WorkspaceControlDecodeResultV2.Rejected ->
                    return decoded.error.code.wireValue to decoded.error.safeMessage
            }
        }
        return "epoch_history_bound_exceeded" to
            "The successor proof exceeds the retained epoch-history bound; authorized rebootstrap is required."
    }

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
                    counts.captureWorkspaceState(store, protocolStore, remote.remoteProfile, descriptor, code)
                } else {
                    counts.activeConflicts = store.loadActiveConflicts().size
                }
            }
        }
        if (code in setOf("rebootstrap_required", "epoch_history_bound_exceeded", "missing_remote_object")) {
            counts.repairState = SyncRunRepairStateV2.REBOOTSTRAP_REQUIRED
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
        const val MAX_POINTER_ANCESTRY_DEPTH_SYSTEM_V2 = 256
    }
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
    var checkpointHorizonEpochMilliseconds: Long? = null,
    var repairState: SyncRunRepairStateV2 = SyncRunRepairStateV2.HEALTHY,
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
        blockingCode: String? = null,
    ) {
        val conflicts = store.loadActiveConflicts()
        activeConflicts = conflicts.size
        activeNoteConflicts = conflicts.count { it.descriptor.entityType == WorkspaceEntityTypeV2.NOTE }
        activeNotebookConflicts = conflicts.count { it.descriptor.entityType == WorkspaceEntityTypeV2.NOTEBOOK }
        activePreferenceConflicts = conflicts.count {
            it.descriptor.entityType == WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES
        }
        projectionWarnings = store.loadProjections().count { it.warning != null }
        deadLetters = protocolStore.loadActiveDeadLetters(remoteProfile, descriptor.syncEpochId).size
        checkpointHorizonEpochMilliseconds = safeRunHorizonV2(
            descriptor.createdAt.toEpochMilliseconds(),
            descriptor.supportedOfflineWindowSeconds,
        )
        repairState = when {
            blockingCode == "rebootstrap_required" || blockingCode == "epoch_history_bound_exceeded" ->
                SyncRunRepairStateV2.REBOOTSTRAP_REQUIRED
            deadLetters > 0 || protocolStore.loadEpoch(remoteProfile, descriptor.syncEpochId)?.health == SyncEpochHealthV2.BLOCKED ->
                SyncRunRepairStateV2.REPAIR_REQUIRED
            else -> SyncRunRepairStateV2.HEALTHY
        }
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
        checkpointHorizonEpochMilliseconds = checkpointHorizonEpochMilliseconds,
        repairState = repairState,
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
