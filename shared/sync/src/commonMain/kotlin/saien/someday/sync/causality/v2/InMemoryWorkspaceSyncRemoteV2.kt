package saien.someday.sync.causality.v2

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fault switches used by coordinator transaction and recovery tests. */
data class WorkspaceSyncFaultPlanV2(
    var failNextPull: Boolean = false,
    var failNextPushBeforeCommit: Boolean = false,
    var failNextPushAfterCommit: Boolean = false,
    var dropNextAcknowledgement: Boolean = false,
    var corruptNextAcknowledgement: Boolean = false,
    var corruptNextPulledObject: Boolean = false,
    var failNextPointerCompareAndSet: Boolean = false,
    var beforeNextPointerCompareAndSet: (() -> Unit)? = null,
)

/**
 * Semantic in-memory implementation of the exact whole-product remote contract.
 * It stores one canonical encrypted outer for each immutable identity.
 */
class InMemoryWorkspaceSyncRemoteV2(
    override val remoteProfile: String = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
    private val maxPushObjects: Int = 100,
    private val maxPullUnits: Int = 500,
    private val maxEncodedBodyBytes: Int = 16 * 1_024 * 1_024,
    val faults: WorkspaceSyncFaultPlanV2 = WorkspaceSyncFaultPlanV2(),
    bindingId: String? = null,
) : WorkspaceSyncRemoteV2 {
    override val authorityBindingId: String = bindingId
        ?: "$remoteProfile|in-memory|${RandomUuidCausalityIdGeneratorV2().newId()}"
    init {
        require(remoteProfile == SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        require(maxPushObjects in 1..100 && maxPullUnits in 1..500)
    }

    private var pointer: EncryptedWorkspaceObjectV2? = null
    private val checkpointManifests = linkedMapOf<Pair<String, String>, EncryptedWorkspaceObjectV2>()
    private val checkpointChunks = linkedMapOf<Triple<String, String, String>, EncryptedWorkspaceObjectV2>()
    /** chunkIndex -> chunkId so read-back stays ordered under concurrent puts. */
    private val checkpointChunkOrder =
        linkedMapOf<Pair<String, String>, MutableMap<Int, String>>()
    private val semanticObjects = linkedMapOf<Pair<String, String>, StoredSemanticObjectV2>()
    private val mutationIdentities = linkedMapOf<Pair<String, String>, Pair<String, String>>()
    private val changes = mutableListOf<StoredWorkspaceChangeV2>()
    /** Guards concurrent first-epoch chunk puts against non-thread-safe map mutation. */
    private val checkpointMutationMutex = Mutex()

    private fun <T> withCheckpointLock(block: () -> T): T =
        runBlocking {
            checkpointMutationMutex.withLock { block() }
        }

    override fun capabilities(): WorkspaceSyncCapabilitiesV2 = WorkspaceSyncCapabilitiesV2(
        profile = remoteProfile,
        contractId = SYNC_V2_CONTRACT_ID,
        semanticProtocolVersion = SEMANTIC_SYNC_PROTOCOL_VERSION_V2,
        schemaSetVersion = SYNC_V2_SCHEMA_SET_VERSION,
        keySetVersion = SYNC_KEY_SET_VERSION_V2,
        metadataPrivacyMode = SyncMetadataPrivacyModeV2.OPAQUE.wireValue,
        maxPushObjects = maxPushObjects,
        maxPullUnits = maxPullUnits,
        maxEncodedBodyBytes = maxEncodedBodyBytes,
        supportsCheckpoints = true,
    )

    override fun loadEpochPointer(): EncryptedWorkspaceObjectV2? = withCheckpointLock { pointer }

    override fun fetchCheckpoint(
        pointer: EncryptedWorkspaceObjectV2,
        descriptor: SyncEpochDescriptorV2,
    ): WorkspaceRemoteCheckpointBundleV2 {
        require(pointer.syncEpochId == descriptor.syncEpochId) { "Requested checkpoint belongs to another epoch." }
        return withCheckpointLock {
            val manifest = checkpointManifests[descriptor.syncEpochId to descriptor.checkpointId]
                ?: error("Checkpoint manifest is missing.")
            val orderKey = descriptor.syncEpochId to descriptor.checkpointId
            // KMP-safe sort (avoid JVM-only toSortedMap).
            val orderedIds = checkpointChunkOrder[orderKey]
                ?.entries
                ?.sortedBy { entry -> entry.key }
                ?.map { entry -> entry.value }
                ?: error("Checkpoint chunk order is missing.")
            val chunks = orderedIds.map { chunkId ->
                checkpointChunks[Triple(descriptor.syncEpochId, descriptor.checkpointId, chunkId)]
                    ?: error("Checkpoint chunk is missing: $chunkId")
            }
            WorkspaceRemoteCheckpointBundleV2(pointer, manifest, chunks)
        }
    }

    override fun putCheckpointChunk(
        descriptor: SyncEpochDescriptorV2,
        ref: WorkspaceCheckpointChunkRefV2,
        chunk: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 = withCheckpointLock {
        val result = immutablePut(
            checkpointChunks,
            Triple(descriptor.syncEpochId, descriptor.checkpointId, ref.chunkId),
            chunk,
        )
        if (result is WorkspaceImmutablePutResultV2.Stored) {
            val orderKey = descriptor.syncEpochId to descriptor.checkpointId
            checkpointChunkOrder.getOrPut(orderKey) { linkedMapOf() }[ref.chunkIndex] = ref.chunkId
        }
        result
    }

    override fun putCheckpointManifest(
        descriptor: SyncEpochDescriptorV2,
        manifest: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 = withCheckpointLock {
        immutablePut(
            checkpointManifests,
            descriptor.syncEpochId to descriptor.checkpointId,
            manifest,
        )
    }

    override fun compareAndSetEpochPointer(
        descriptor: SyncEpochDescriptorV2,
        expectedCurrentDigest: String?,
        pointer: EncryptedWorkspaceObjectV2,
    ): WorkspacePointerPublishResultV2 {
        faults.beforeNextPointerCompareAndSet?.let { race ->
            faults.beforeNextPointerCompareAndSet = null
            race()
        }
        if (faults.failNextPointerCompareAndSet) {
            faults.failNextPointerCompareAndSet = false
            return withCheckpointLock {
                WorkspacePointerPublishResultV2.CompareAndSetFailed(this.pointer)
            }
        }
        return withCheckpointLock {
            val current = this.pointer
            if (current != null) {
                if (current == pointer) {
                    return@withCheckpointLock WorkspacePointerPublishResultV2.Published(idempotentReplay = true)
                }
                return@withCheckpointLock WorkspacePointerPublishResultV2.CompareAndSetFailed(current)
            }
            if (expectedCurrentDigest != null || descriptor.previousEpochId != null ||
                descriptor.previousEpochPointerDigest != null
            ) {
                return@withCheckpointLock WorkspacePointerPublishResultV2.Rejected(
                    "previous_epoch_mismatch",
                    "The initial pointer cannot name a predecessor.",
                )
            }
            val manifest = checkpointManifests[descriptor.syncEpochId to descriptor.checkpointId]
                ?: return@withCheckpointLock WorkspacePointerPublishResultV2.Rejected(
                    "checkpoint_manifest_missing",
                    "The referenced checkpoint manifest is not stored.",
                )
            if (manifest.objectDigest != descriptor.checkpointDigest || pointer.syncEpochId != descriptor.syncEpochId) {
                return@withCheckpointLock WorkspacePointerPublishResultV2.Rejected(
                    "checkpoint_integrity_mismatch",
                    "The pointer does not identify its stored checkpoint.",
                )
            }
            this.pointer = pointer
            WorkspacePointerPublishResultV2.Published(idempotentReplay = false)
        }
    }

    override fun cleanupCheckpointDraft(
        draft: WorkspaceCheckpointDraftCleanupV2,
    ): WorkspaceCheckpointDraftCleanupResultV2 = withCheckpointLock {
        if (draft.remoteProfile != remoteProfile) {
            return@withCheckpointLock WorkspaceCheckpointDraftCleanupResultV2.Retained(
                "remote_profile_mismatch",
                "Checkpoint cleanup targets another remote profile.",
            )
        }
        val current = pointer
            ?: return@withCheckpointLock WorkspaceCheckpointDraftCleanupResultV2.Retained(
                "checkpoint_still_publishable",
                "An empty remote cannot prove that this checkpoint draft is obsolete.",
            )
        if (current.syncEpochId == draft.descriptor.syncEpochId) {
            return@withCheckpointLock WorkspaceCheckpointDraftCleanupResultV2.Retained(
                "checkpoint_referenced",
                "The checkpoint is current or retained by authenticated pointer history.",
            )
        }
        if (current.objectDigest == draft.pointer.previousPointerDigest) {
            return@withCheckpointLock WorkspaceCheckpointDraftCleanupResultV2.Retained(
                "checkpoint_still_publishable",
                "The checkpoint draft is still a valid successor of the current pointer.",
            )
        }

        val manifestKey = draft.descriptor.syncEpochId to draft.descriptor.checkpointId
        val existingManifest = checkpointManifests[manifestKey]
        if (existingManifest != null && existingManifest.objectDigest != draft.manifestObject.objectDigest) {
            return@withCheckpointLock WorkspaceCheckpointDraftCleanupResultV2.Retained(
                "immutable_object_mismatch",
                "Remote checkpoint manifest identity differs from the local cleanup record.",
            )
        }
        val expectedChunks = draft.chunks.associateBy { it.ref.chunkId }
        val existingChunkEntries = checkpointChunks.filterKeys { (epochId, checkpointId, _) ->
            epochId == draft.descriptor.syncEpochId && checkpointId == draft.descriptor.checkpointId
        }
        val chunksMatch = existingChunkEntries.all { (key, outer) ->
            expectedChunks[key.third]?.let { expected ->
                expected.encryptedObject.objectDigest == outer.objectDigest
            } == true
        }
        if (!chunksMatch) {
            return@withCheckpointLock WorkspaceCheckpointDraftCleanupResultV2.Retained(
                "immutable_object_mismatch",
                "Remote checkpoint chunks differ from the local cleanup record.",
            )
        }
        val alreadyAbsent = existingManifest == null && existingChunkEntries.isEmpty()
        checkpointManifests.remove(manifestKey)
        checkpointChunks.keys.removeAll {
            it.first == draft.descriptor.syncEpochId && it.second == draft.descriptor.checkpointId
        }
        checkpointChunkOrder.remove(manifestKey)
        WorkspaceCheckpointDraftCleanupResultV2.Deleted(alreadyAbsent)
    }

    override fun pull(
        syncEpochId: String,
        cursors: Map<String, String?>,
        limit: Int,
    ): WorkspaceSyncPullResultV2 {
        if (faults.failNextPull) {
            faults.failNextPull = false
            error("Injected pull failure.")
        }
        val after = cursors[GLOBAL_STREAM_ID]?.toLongOrNull() ?: 0L
        var previousCursor = cursors[GLOBAL_STREAM_ID]
        var units = changes.asSequence()
            .filter { it.epochId == syncEpochId && it.cursor > after }
            .take(limit.coerceIn(1, maxPullUnits))
            .map { change ->
                WorkspaceEncryptedCursorUnitV2(
                    syncEpochId = change.epochId,
                    streamId = GLOBAL_STREAM_ID,
                    expectedCursorValue = previousCursor,
                    nextCursorValue = change.cursor.toString(),
                    unitId = "change-${change.cursor}",
                    unitDigest = change.objectValue.ciphertextDigest,
                    objects = listOf(change.objectValue),
                ).also { previousCursor = change.cursor.toString() }
            }
            .toList()
        if (faults.corruptNextPulledObject && units.isNotEmpty()) {
            faults.corruptNextPulledObject = false
            val first = units.first()
            units = listOf(first.copy(objects = listOf(first.objects.single().copy(
                ciphertextDigest = "ct2:sha256:${"0".repeat(64)}",
            )))) + units.drop(1)
        }
        val lastReturned = units.lastOrNull()?.nextCursorValue?.toLongOrNull() ?: after
        val stable = changes.none { it.epochId == syncEpochId && it.cursor > lastReturned }
        return WorkspaceSyncPullResultV2(units, frontierStable = stable)
    }

    override fun push(
        syncEpochId: String,
        objects: List<EncryptedWorkspaceObjectV2>,
    ): WorkspaceSyncPushResultV2 {
        if (faults.failNextPushBeforeCommit) {
            faults.failNextPushBeforeCommit = false
            error("Injected push failure before commit.")
        }
        if (pointer?.syncEpochId != syncEpochId) {
            return WorkspaceSyncPushResultV2.Rejected("incompatible_epoch", "Push targets a non-active epoch.")
        }
        if (objects.size !in 1..maxPushObjects) {
            return WorkspaceSyncPushResultV2.Rejected("push_batch_too_large", "Push exceeds the negotiated bound.")
        }
        val staged = mutableListOf<Pair<EncryptedWorkspaceObjectV2, Boolean>>()
        objects.forEach { value ->
            if (value.syncEpochId != syncEpochId || value.objectType != WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2 || value.mutationId == null) {
                return WorkspaceSyncPushResultV2.Rejected("transport_metadata_mismatch", "Push contains an invalid entity outer.")
            }
            val mutationKey = syncEpochId to value.mutationId
            val identity = value.objectId to value.objectDigest
            mutationIdentities[mutationKey]?.let { existing ->
                if (existing != identity) {
                    return WorkspaceSyncPushResultV2.Rejected("mutation_reuse_mismatch", "Mutation id names another semantic object.")
                }
            }
            semanticObjects[syncEpochId to value.objectId]?.let { existing ->
                if (existing.objectValue != value) {
                    return WorkspaceSyncPushResultV2.Rejected("immutable_object_mismatch", "Object id names another encrypted outer.")
                }
            }
            val replay = mutationIdentities.containsKey(mutationKey)
            staged += value to replay
        }
        staged.forEach { (value, replay) ->
            val mutationId = checkNotNull(value.mutationId)
            mutationIdentities[syncEpochId to mutationId] = value.objectId to value.objectDigest
            val key = syncEpochId to value.objectId
            semanticObjects.getOrPut(key) { StoredSemanticObjectV2(value) }
            if (!replay) {
                changes += StoredWorkspaceChangeV2(syncEpochId, changes.size.toLong() + 1L, value)
            }
        }
        if (faults.failNextPushAfterCommit) {
            faults.failNextPushAfterCommit = false
            error("Injected push failure after commit.")
        }
        var acks = staged.map { (value, replay) ->
            WorkspaceMutationAckV2(checkNotNull(value.mutationId), value.objectId, value.objectDigest, replay)
        }
        if (faults.dropNextAcknowledgement && acks.isNotEmpty()) {
            faults.dropNextAcknowledgement = false
            acks = acks.dropLast(1)
        }
        if (faults.corruptNextAcknowledgement && acks.isNotEmpty()) {
            faults.corruptNextAcknowledgement = false
            acks = listOf(acks.first().copy(objectDigest = "od2:hmac-sha256:${"0".repeat(64)}")) + acks.drop(1)
        }
        return WorkspaceSyncPushResultV2.Accepted(acks)
    }

    override fun epochFrontiers(syncEpochId: String): List<SyncStreamFrontierV2> {
        val last = changes.lastOrNull { it.epochId == syncEpochId }
        return listOf(
            SyncStreamFrontierV2(
                streamId = GLOBAL_STREAM_ID,
                cursorValue = last?.cursor?.toString(),
                streamDigest = last?.objectValue?.ciphertextDigest ?: EMPTY_FRONTIER_DIGEST,
            ),
        )
    }

    fun allChanges(): List<EncryptedWorkspaceObjectV2> = changes.map { it.objectValue }

    /** Fault injection only: exposes an authenticated provider rollback. */
    internal fun forceEpochPointerForTest(value: EncryptedWorkspaceObjectV2) {
        pointer = value
    }

    internal fun hasCheckpointDraftForTest(epochId: String): Boolean = withCheckpointLock {
        checkpointManifests.keys.any { it.first == epochId } ||
            checkpointChunks.keys.any { it.first == epochId }
    }

    private fun <K> immutablePut(
        target: MutableMap<K, EncryptedWorkspaceObjectV2>,
        key: K,
        value: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 {
        val existing = target[key]
        return when {
            existing == null -> {
                target[key] = value
                WorkspaceImmutablePutResultV2.Stored(idempotentReplay = false)
            }
            existing.objectDigest == value.objectDigest -> WorkspaceImmutablePutResultV2.Stored(idempotentReplay = true)
            else -> WorkspaceImmutablePutResultV2.Rejected(
                "immutable_object_mismatch",
                "Immutable control id names another authenticated digest.",
            )
        }
    }

    private data class StoredSemanticObjectV2(
        val objectValue: EncryptedWorkspaceObjectV2,
    )

    private data class StoredWorkspaceChangeV2(
        val epochId: String,
        val cursor: Long,
        val objectValue: EncryptedWorkspaceObjectV2,
    )

    private companion object {
        const val GLOBAL_STREAM_ID = "global"
        const val EMPTY_FRONTIER_DIGEST = "ct2:sha256:0000000000000000000000000000000000000000000000000000000000000000"
    }
}
