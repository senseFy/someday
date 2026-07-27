package saien.someday.sync.causality.v2

/** Fault switches used by coordinator transaction and recovery tests. */
data class WorkspaceSyncFaultPlanV2(
    var failNextPull: Boolean = false,
    var failNextPushBeforeCommit: Boolean = false,
    var failNextPushAfterCommit: Boolean = false,
    var dropNextAcknowledgement: Boolean = false,
    var corruptNextAcknowledgement: Boolean = false,
    var corruptNextPulledObject: Boolean = false,
    var requireRebootstrapOnNextPull: Boolean = false,
    var failNextPointerCompareAndSet: Boolean = false,
    var beforeNextPointerCompareAndSet: (() -> Unit)? = null,
)

/**
 * Semantic in-memory implementation of the exact whole-product remote contract.
 * It deliberately stores ciphertext replicas separately from semantic identity,
 * just like the self-hosted profile, so coordinator tests exercise replay and
 * immutable-object mismatch rules rather than Kotlin object equality.
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
        require(remoteProfile in setOf(
            SyncRemoteProfileV2.WEB_DAV.wireValue,
            SyncRemoteProfileV2.SELF_HOSTED.wireValue,
        ))
        require(maxPushObjects in 1..100 && maxPullUnits in 1..500)
    }

    private var pointer: EncryptedWorkspaceObjectV2? = null
    private val retainedPointers = linkedMapOf<String, EncryptedWorkspaceObjectV2>()
    private val checkpointManifests = linkedMapOf<Pair<String, String>, EncryptedWorkspaceObjectV2>()
    private val checkpointChunks = linkedMapOf<Triple<String, String, String>, EncryptedWorkspaceObjectV2>()
    private val semanticObjects = linkedMapOf<Pair<String, String>, StoredSemanticObjectV2>()
    private val mutationIdentities = linkedMapOf<Pair<String, String>, Pair<String, String>>()
    private val changes = mutableListOf<StoredWorkspaceChangeV2>()
    private val repairReplicas = linkedMapOf<Triple<String, String, String>, LinkedHashMap<String, EncryptedWorkspaceObjectV2>>()
    private val collectedEpochs = mutableSetOf<String>()

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

    override fun loadEpochPointer(): EncryptedWorkspaceObjectV2? = pointer

    override fun loadRetainedEpochPointer(syncEpochId: String): EncryptedWorkspaceObjectV2? =
        pointer?.takeIf { it.syncEpochId == syncEpochId } ?: retainedPointers[syncEpochId]

    override fun fetchCheckpoint(
        pointer: EncryptedWorkspaceObjectV2,
        descriptor: SyncEpochDescriptorV2,
    ): WorkspaceRemoteCheckpointBundleV2 {
        require(pointer.syncEpochId == descriptor.syncEpochId) { "Requested checkpoint belongs to another epoch." }
        val manifest = checkpointManifests[descriptor.syncEpochId to descriptor.checkpointId]
            ?: error("Checkpoint manifest is missing.")
        val chunks = checkpointChunks
            .filterKeys { (epochId, checkpointId, _) ->
                epochId == descriptor.syncEpochId && checkpointId == descriptor.checkpointId
            }
            .values
            .toList()
        return WorkspaceRemoteCheckpointBundleV2(pointer, manifest, chunks)
    }

    override fun putCheckpointChunk(
        descriptor: SyncEpochDescriptorV2,
        ref: WorkspaceCheckpointChunkRefV2,
        chunk: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 = immutablePut(
        checkpointChunks,
        Triple(descriptor.syncEpochId, descriptor.checkpointId, ref.chunkId),
        chunk,
    )

    override fun putCheckpointManifest(
        descriptor: SyncEpochDescriptorV2,
        manifest: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 = immutablePut(
        checkpointManifests,
        descriptor.syncEpochId to descriptor.checkpointId,
        manifest,
    )

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
            return WorkspacePointerPublishResultV2.CompareAndSetFailed(this.pointer)
        }
        val current = this.pointer
        if (current?.objectDigest != expectedCurrentDigest) {
            if (current?.objectDigest == pointer.objectDigest) {
                return WorkspacePointerPublishResultV2.Published(idempotentReplay = true)
            }
            return WorkspacePointerPublishResultV2.CompareAndSetFailed(current)
        }
        val manifest = checkpointManifests[descriptor.syncEpochId to descriptor.checkpointId]
            ?: return WorkspacePointerPublishResultV2.Rejected(
                "checkpoint_manifest_missing",
                "The referenced checkpoint manifest is not stored.",
            )
        if (manifest.objectDigest != descriptor.checkpointDigest || pointer.syncEpochId != descriptor.syncEpochId) {
            return WorkspacePointerPublishResultV2.Rejected(
                "checkpoint_integrity_mismatch",
                "The pointer does not identify its stored checkpoint.",
            )
        }
        current?.let { retainedPointers[it.syncEpochId] = it }
        this.pointer = pointer
        return WorkspacePointerPublishResultV2.Published(idempotentReplay = false)
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
        if (faults.requireRebootstrapOnNextPull) {
            faults.requireRebootstrapOnNextPull = false
            return WorkspaceSyncPullResultV2(emptyList(), frontierStable = true, rebootstrapRequired = true)
        }
        if (syncEpochId in collectedEpochs) {
            return WorkspaceSyncPullResultV2(emptyList(), frontierStable = true, rebootstrapRequired = true)
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
                if (existing.objectDigest != value.objectDigest) {
                    return WorkspaceSyncPushResultV2.Rejected("immutable_object_mismatch", "Object id names another semantic digest.")
                }
            }
            val replay = mutationIdentities.containsKey(mutationKey)
            staged += value to replay
        }
        staged.forEach { (value, replay) ->
            val mutationId = checkNotNull(value.mutationId)
            mutationIdentities[syncEpochId to mutationId] = value.objectId to value.objectDigest
            val key = syncEpochId to value.objectId
            val semantic = semanticObjects.getOrPut(key) {
                StoredSemanticObjectV2(value.objectDigest, linkedMapOf())
            }
            if (semantic.replicas.size < MAX_REPLICAS_PER_OBJECT || value.ciphertextDigest in semantic.replicas) {
                semantic.replicas[value.ciphertextDigest] = value
            }
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

    override fun fetchRepairReplicas(
        syncEpochId: String,
        objectId: String,
        objectDigest: String,
    ): List<EncryptedWorkspaceObjectV2> {
        val ordinary = semanticObjects[syncEpochId to objectId]
            ?.takeIf { it.objectDigest == objectDigest }
            ?.replicas
            ?.values
            .orEmpty()
        val repairs = repairReplicas[Triple(syncEpochId, objectId, objectDigest)]?.values.orEmpty()
        return (ordinary + repairs).distinctBy { it.writerDeviceId to it.ciphertextDigest }.take(MAX_REPAIR_CANDIDATES)
    }

    override fun publishRepairReplica(objectValue: EncryptedWorkspaceObjectV2): WorkspaceImmutablePutResultV2 {
        val key = Triple(objectValue.syncEpochId, objectValue.objectId, objectValue.objectDigest)
        val values = repairReplicas.getOrPut(key) { linkedMapOf() }
        val existing = values[objectValue.writerDeviceId]
        if (existing?.ciphertextDigest == objectValue.ciphertextDigest) {
            return WorkspaceImmutablePutResultV2.Stored(idempotentReplay = true)
        }
        if (values.size >= MAX_REPAIR_CANDIDATES && existing == null) {
            return WorkspaceImmutablePutResultV2.Rejected("repair_replica_set_invalid", "Repair replica bound exceeded.")
        }
        values[objectValue.writerDeviceId] = objectValue
        return WorkspaceImmutablePutResultV2.Stored(idempotentReplay = false)
    }

    fun allChanges(): List<EncryptedWorkspaceObjectV2> = changes.map { it.objectValue }

    /** Fault injection only: exposes an authenticated provider rollback. */
    internal fun forceEpochPointerForTest(value: EncryptedWorkspaceObjectV2) {
        pointer = value
    }

    /** Simulates an already-published stale WebDAV writer after epoch rollover. */
    internal fun injectRetainedEpochObjectForTest(value: EncryptedWorkspaceObjectV2) {
        require(pointer?.syncEpochId != value.syncEpochId)
        require(value.objectType == WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2 && value.mutationId != null)
        val mutationKey = value.syncEpochId to value.mutationId
        val identity = value.objectId to value.objectDigest
        require(mutationIdentities[mutationKey]?.let { it == identity } != false)
        val semanticKey = value.syncEpochId to value.objectId
        val semantic = semanticObjects[semanticKey]
        require(semantic?.objectDigest?.let { it == value.objectDigest } != false)
        if (mutationKey !in mutationIdentities) {
            mutationIdentities[mutationKey] = identity
            val stored = semanticObjects.getOrPut(semanticKey) {
                StoredSemanticObjectV2(value.objectDigest, linkedMapOf())
            }
            stored.replicas[value.ciphertextDigest] = value
            changes += StoredWorkspaceChangeV2(value.syncEpochId, changes.size.toLong() + 1L, value)
        }
    }

    /** Simulates authorized collection after the disclosed offline horizon. */
    internal fun collectReadOnlyEpochForTest(epochId: String) {
        require(pointer?.syncEpochId != epochId)
        checkpointManifests.keys.removeAll { it.first == epochId }
        checkpointChunks.keys.removeAll { it.first == epochId }
        semanticObjects.keys.removeAll { it.first == epochId }
        mutationIdentities.keys.removeAll { it.first == epochId }
        changes.removeAll { it.epochId == epochId }
        repairReplicas.keys.removeAll { it.first == epochId }
        collectedEpochs += epochId
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
        val objectDigest: String,
        val replicas: LinkedHashMap<String, EncryptedWorkspaceObjectV2>,
    )

    private data class StoredWorkspaceChangeV2(
        val epochId: String,
        val cursor: Long,
        val objectValue: EncryptedWorkspaceObjectV2,
    )

    private companion object {
        const val GLOBAL_STREAM_ID = "global"
        const val MAX_REPLICAS_PER_OBJECT = 4
        const val MAX_REPAIR_CANDIDATES = 64
        const val EMPTY_FRONTIER_DIGEST = "ct2:sha256:0000000000000000000000000000000000000000000000000000000000000000"
    }
}
