@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import kotlin.time.Instant

data class WorkspaceCheckpointSourceHeadV2(
    val entityType: WorkspaceEntityTypeV2,
    val entityId: String,
    val content: WorkspaceEntityContentV2?,
    val deletion: WorkspaceDeletionV2?,
    val sourceProfile: String,
    val sourceEpoch: String?,
    val sourceWriterId: String?,
    val sourceMutationId: String?,
    val sourceObjectId: String,
    val sourceObjectDigest: String,
    /** Local preparation metadata; checkpoint wire provenance does not expose it. */
    val sourceAuthoredAt: Instant? = null,
) {
    init {
        require((content == null) != (deletion == null))
        require(content == null || content.entityType == entityType)
        require(entityType != WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES || deletion == null)
        require(entityId.isWholeProductProtocolIdentifierV2())
        require(sourceProfile.isNotBlank() && sourceObjectId.isNotBlank() && sourceObjectDigest.isNotBlank())
    }
}

data class PreparedWorkspaceCheckpointEntityV2(
    val version: WorkspaceEntityVersionV2,
    val mutationId: String,
    val encryptedObject: EncryptedWorkspaceObjectV2,
)

data class WorkspaceCheckpointSourceImportV2(
    val entityType: WorkspaceEntityTypeV2,
    val entityId: String,
    val content: WorkspaceEntityContentV2?,
    val deletion: WorkspaceDeletionV2?,
    val sourceProfile: String,
    val sourceEpoch: String?,
    val sourceWriterId: String?,
    val sourceMutationId: String?,
    val sourceObjectId: String,
    val sourceObjectDigest: String,
    /** Exact sourceObjectId of a checkpoint root whose state was verified. */
    val verifiedCheckpointSourceObjectId: String?,
    val authoredAt: Instant,
) {
    init {
        require((content == null) != (deletion == null))
        require(content == null || content.entityType == entityType)
        require(entityType != WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES || deletion == null)
        require(entityId.isWholeProductProtocolIdentifierV2())
        require(sourceProfile.isNotBlank() && sourceObjectId.isNotBlank() && sourceObjectDigest.isNotBlank())
    }
}

data class PreparedWorkspaceCheckpointChunkV2(
    val value: WorkspaceCheckpointChunkV2,
    val ref: WorkspaceCheckpointChunkRefV2,
    val encryptedObject: EncryptedWorkspaceObjectV2,
)

data class PreparedWorkspaceEpochCheckpointV2(
    val remoteProfile: String,
    val pointer: WorkspaceSyncEpochPointerV2,
    val pointerObject: EncryptedWorkspaceObjectV2,
    val manifest: WorkspaceCheckpointManifestV2,
    val manifestObject: EncryptedWorkspaceObjectV2,
    val chunks: List<PreparedWorkspaceCheckpointChunkV2>,
    val entities: List<PreparedWorkspaceCheckpointEntityV2>,
) {
    val descriptor: SyncEpochDescriptorV2 get() = pointer.descriptor

    init {
        require(remoteProfile == descriptor.remoteProfile)
        require(pointerObject.objectType == SYNC_EPOCH_POINTER_OBJECT_TYPE_V2)
        require(pointerObject.objectId == SYNC_EPOCH_POINTER_ID_SYSTEM_V2)
        require(pointerObject.syncEpochId == descriptor.syncEpochId)
        require(manifestObject.objectDigest == descriptor.checkpointDigest)
        require(chunks.map { it.ref } == manifest.chunks)
        require(entities.size == manifest.totalObjectCount)
    }
}

/** Builds roots and deterministic convergence versions before the checkpoint is published. */
class WorkspaceCheckpointBuilderV2(
    private val workspaceKey: WorkspaceMasterKey,
    private val writerDeviceId: String,
    private val idGenerator: CausalityIdGeneratorV2 = RandomUuidCausalityIdGeneratorV2(),
    private val keyDerivation: SyncEpochKeyDerivationV2 = SyncEpochKeyDerivationV2(),
) {
    init {
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(writerDeviceId))
    }

    fun build(
        remoteProfile: String,
        sourceHeads: List<WorkspaceCheckpointSourceHeadV2>,
        sourceImports: List<WorkspaceCheckpointSourceImportV2> = emptyList(),
        createdAt: Instant,
        previousPointerDigest: String? = null,
        previousEpochId: String? = null,
        previousEpochPointerDigest: String? = previousPointerDigest,
        previousEpochFrontiers: List<SyncStreamFrontierV2> = emptyList(),
        syncEpochId: String = idGenerator.newId(),
        checkpointId: String = idGenerator.newId(),
    ): PreparedWorkspaceEpochCheckpointV2 {
        require(remoteProfile in setOf(
            SyncRemoteProfileV2.WEB_DAV.wireValue,
            SyncRemoteProfileV2.SELF_HOSTED.wireValue,
        ))
        require(sourceHeads.isNotEmpty())
        require(sourceHeads.any {
            it.entityType == WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES &&
                it.entityId == WORKSPACE_PREFERENCES_ENTITY_ID_V2
        }) { "Every checkpoint must include the workspace-preferences singleton." }
        require(sourceHeads == sourceHeads.sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2))
        require(sourceHeads.map { Triple(it.entityType, it.entityId, it.sourceObjectId) }.distinct().size == sourceHeads.size)
        require(sourceImports == sourceImports.sortedWith(CHECKPOINT_IMPORT_COMPARATOR_SYSTEM_V2))
        require(sourceImports.map { Triple(it.entityType, it.entityId, it.sourceObjectId) }.distinct().size == sourceImports.size)

        val keys = keyDerivation.derive(workspaceKey, syncEpochId)
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(keys)
        val validator = WorkspaceEntityValidatorV2(materializer)
        val factory = WorkspaceEntityVersionFactoryV2(syncEpochId, materializer, idGenerator)
        val engine = WorkspaceEntityCausalityEngineV2(materializer, validator)
        val wire = WorkspaceEntityWireCodecV2(materializer, validator)
        val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
        val control = WorkspaceSyncControlCodecV2(cipher)

        val roots = sourceHeads.map { source ->
            factory.createCheckpointRoot(
                entityType = source.entityType,
                entityId = source.entityId,
                content = source.content,
                deletion = source.deletion,
                provenance = WorkspaceVersionProvenanceV2(
                    type = WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT,
                    sourceProfile = source.sourceProfile,
                    sourceEpoch = source.sourceEpoch,
                    sourceWriterId = source.sourceWriterId,
                    sourceMutationId = source.sourceMutationId,
                    sourceObjectId = source.sourceObjectId,
                    sourceDigest = source.sourceObjectDigest,
                ),
                authoredAt = createdAt,
            )
        }
        val rootBySource = sourceHeads.zip(roots).associate { (source, root) ->
            Triple(source.entityType, source.entityId, source.sourceObjectId) to root
        }
        val imports = sourceImports.map { source ->
            val parent = source.verifiedCheckpointSourceObjectId?.let { sourceObjectId ->
                rootBySource[Triple(source.entityType, source.entityId, sourceObjectId)]
                    ?: error("Verified checkpoint import parent is not part of this checkpoint.")
            }
            factory.createSourceImport(
                source.entityType,
                source.entityId,
                source.content,
                source.deletion,
                WorkspaceVersionProvenanceV2(
                    WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT,
                    source.sourceProfile,
                    source.sourceEpoch,
                    source.sourceWriterId,
                    source.sourceMutationId,
                    source.sourceObjectId,
                    source.sourceObjectDigest,
                ),
                source.authoredAt,
                parent,
            )
        }
        val initialVersions = roots + imports
        val groupedVersions = initialVersions.groupBy { it.key }
        val generated = groupedVersions.keys
            .sortedWith(compareBy({ it.entityType.wireValue }, { it.entityId }))
            .flatMap { entityKey ->
                val versions = checkNotNull(groupedVersions[entityKey])
                when (val result = engine.reconcile(syncEpochId, entityKey, versions, emptyList())) {
                    is WorkspaceReconciliationResultV2.InvalidGraph -> error(
                        "Checkpoint graph failed before publication: " +
                            result.errors.joinToString { it.code.wireValue },
                    )
                    is WorkspaceReconciliationResultV2.Reconciled -> result.plan.generatedVersions
                }
            }
        val versions = (initialVersions + generated).distinctBy { it.versionId }.sortedWith(CHECKPOINT_VERSION_COMPARATOR_SYSTEM_V2)
        val entities = versions.map { version ->
            val mutationId = if (version.mergeAlgorithmVersion in AUTOMATIC_MERGE_ALGORITHMS_V2 ||
                version.provenance?.type == WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT
            ) {
                materializer.deterministicSystemMutationId(version)
            } else {
                factory.newMutationId()
            }
            PreparedWorkspaceCheckpointEntityV2(
                version,
                mutationId,
                cipher.encryptEntity(version, mutationId, writerDeviceId, wire.encode(version)),
            )
        }

        val chunks = partition(syncEpochId, checkpointId, entities.map { it.encryptedObject }, control)
            .map { chunk ->
                val outer = control.encodeCheckpointChunk(chunk, writerDeviceId)
                PreparedWorkspaceCheckpointChunkV2(
                    value = chunk,
                    ref = WorkspaceCheckpointChunkRefV2(
                        chunkIndex = chunk.chunkIndex,
                        chunkId = chunk.chunkId,
                        chunkDigest = outer.objectDigest,
                        objectCount = chunk.objects.size,
                        plaintextBytes = control.checkpointChunkPlaintextBytes(chunk),
                    ),
                    encryptedObject = outer,
                )
            }
        val manifest = WorkspaceCheckpointManifestV2(
            syncEpochId = syncEpochId,
            checkpointId = checkpointId,
            createdAt = createdAt,
            chunks = chunks.map { it.ref },
            totalObjectCount = entities.size,
        )
        val manifestOuter = control.encodeCheckpointManifest(manifest, writerDeviceId)
        val descriptor = SyncEpochDescriptorV2(
            syncEpochId = syncEpochId,
            remoteProfile = remoteProfile,
            checkpointId = checkpointId,
            checkpointDigest = manifestOuter.objectDigest,
            previousEpochId = previousEpochId,
            previousEpochPointerDigest = previousEpochPointerDigest,
            createdByDeviceId = writerDeviceId,
            createdAt = createdAt,
            previousEpochFrontiers = previousEpochFrontiers.sortedBy { it.streamId },
        )
        val pointer = WorkspaceSyncEpochPointerV2(
            previousPointerDigest = previousPointerDigest,
            descriptor = descriptor,
        )
        return PreparedWorkspaceEpochCheckpointV2(
            remoteProfile,
            pointer,
            control.encodeEpochPointer(pointer, writerDeviceId),
            manifest,
            manifestOuter,
            chunks,
            entities,
        )
    }

    private fun partition(
        epochId: String,
        checkpointId: String,
        objects: List<EncryptedWorkspaceObjectV2>,
        control: WorkspaceSyncControlCodecV2,
    ): List<WorkspaceCheckpointChunkV2> {
        require(objects.isNotEmpty())
        val result = mutableListOf<WorkspaceCheckpointChunkV2>()
        var current = mutableListOf<EncryptedWorkspaceObjectV2>()
        var chunkId = idGenerator.newId()
        objects.forEach { value ->
            val candidate = WorkspaceCheckpointChunkV2(
                syncEpochId = epochId,
                checkpointId = checkpointId,
                chunkIndex = result.size,
                chunkId = chunkId,
                objects = current + value,
            )
            val exceeds = candidate.objects.size > MAX_CHECKPOINT_CHUNK_OBJECTS_SYSTEM_V2 ||
                runCatching { control.checkpointChunkPlaintextBytes(candidate) }
                    .getOrDefault(Int.MAX_VALUE) > MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2
            if (exceeds) {
                require(current.isNotEmpty()) { "One encrypted entity object exceeds the checkpoint chunk bound." }
                result += candidate.copy(objects = current)
                current = mutableListOf(value)
                chunkId = idGenerator.newId()
                val single = candidate.copy(
                    chunkIndex = result.size,
                    chunkId = chunkId,
                    objects = current,
                )
                require(control.checkpointChunkPlaintextBytes(single) <= MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2)
            } else {
                current += value
            }
        }
        result += WorkspaceCheckpointChunkV2(
            syncEpochId = epochId,
            checkpointId = checkpointId,
            chunkIndex = result.size,
            chunkId = chunkId,
            objects = current,
        )
        return result
    }
}

sealed interface WorkspaceCheckpointPersistResultV2 {
    data class Ready(val epoch: StoredSyncEpochV2) : WorkspaceCheckpointPersistResultV2
    data class Rejected(val safeErrorCode: String, val safeMessage: String) : WorkspaceCheckpointPersistResultV2
}

/** Makes a prepared checkpoint crash-recoverable and locally verifies its complete graph. */
class WorkspaceCheckpointPersistenceV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val writerDeviceId: String,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 = SqlDelightSyncProtocolStoreV2(localRepository.database),
) {
    fun persist(prepared: PreparedWorkspaceEpochCheckpointV2): WorkspaceCheckpointPersistResultV2 {
        val descriptor = prepared.descriptor
        val components = components(descriptor.syncEpochId)
        val store = components.store(prepared.remoteProfile)
        val queries = localRepository.database.somedayQueries
        return try {
            localRepository.database.transaction {
                when (val epoch = protocolStore.persistPreparingEpoch(
                    prepared.remoteProfile,
                    descriptor,
                    prepared.pointerObject.objectDigest,
                )) {
                    is SyncEpochPersistResultV2.ImmutableMismatch -> error(epoch.safeMessage)
                    is SyncEpochPersistResultV2.AlreadyStored,
                    is SyncEpochPersistResultV2.Stored,
                    -> Unit
                }
                insertControl(prepared.remoteProfile, descriptor.syncEpochId, prepared.pointerObject, descriptor.createdAt)
                insertControl(prepared.remoteProfile, descriptor.syncEpochId, prepared.manifestObject, descriptor.createdAt)
                prepared.chunks.forEach { chunk ->
                    insertControl(prepared.remoteProfile, descriptor.syncEpochId, chunk.encryptedObject, descriptor.createdAt)
                }
                prepared.entities.filter {
                    it.version.provenance?.type == WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT
                }.forEach { entity ->
                    val provenance = checkNotNull(entity.version.provenance)
                    val sourceProfile = checkNotNull(provenance.sourceProfile)
                    val sourceObjectId = checkNotNull(provenance.sourceObjectId)
                    val sourceDigest = checkNotNull(provenance.sourceDigest)
                    val existing = queries.selectSourceImportSystemV2(
                        prepared.remoteProfile,
                        descriptor.syncEpochId,
                        sourceProfile,
                        sourceObjectId,
                        sourceDigest,
                    ).executeAsOneOrNull()
                    if (existing == null) {
                        queries.insertSourceImportSystemV2(
                            prepared.remoteProfile,
                            descriptor.syncEpochId,
                            sourceProfile,
                            provenance.sourceEpoch,
                            provenance.sourceWriterId,
                            provenance.sourceMutationId,
                            sourceObjectId,
                            sourceDigest,
                            entity.version.entityType.wireValue,
                            entity.version.entityId,
                            entity.version.entityId,
                            entity.version.versionId,
                            entity.mutationId,
                            "committed",
                            descriptor.createdAt.toEpochMilliseconds(),
                            null,
                        )
                    } else {
                        require(existing.version_id == entity.version.versionId && existing.mutation_id == entity.mutationId)
                    }
                }
                val checkpoint = queries.selectCheckpointSystemV2(
                    prepared.remoteProfile,
                    descriptor.syncEpochId,
                    descriptor.checkpointId,
                ).executeAsOneOrNull()
                if (checkpoint == null) {
                    queries.insertCheckpointSystemV2(
                        prepared.remoteProfile,
                        descriptor.syncEpochId,
                        descriptor.checkpointId,
                        prepared.manifestObject.objectDigest,
                        components.cipher.encodeJson(prepared.manifestObject),
                        "preparing",
                        descriptor.createdAt.toEpochMilliseconds(),
                        null,
                    )
                } else {
                    require(checkpoint.manifest_digest == prepared.manifestObject.objectDigest &&
                        checkpoint.encoded_manifest == components.cipher.encodeJson(prepared.manifestObject))
                }
                var expectedCursor: String? = null
                prepared.chunks.forEach { chunk ->
                    chunk.value.objects.forEachIndexed { objectIndex, outer ->
                        val existing = queries.selectCheckpointObjectsSystemV2(
                            prepared.remoteProfile,
                            descriptor.syncEpochId,
                            descriptor.checkpointId,
                        ).executeAsList().firstOrNull {
                            it.chunk_index == chunk.value.chunkIndex.toLong() && it.object_index == objectIndex.toLong()
                        }
                        val encoded = components.cipher.encodeJson(outer)
                        if (existing == null) {
                            queries.insertCheckpointObjectSystemV2(
                                prepared.remoteProfile,
                                descriptor.syncEpochId,
                                descriptor.checkpointId,
                                chunk.value.chunkIndex.toLong(),
                                objectIndex.toLong(),
                                outer.objectId,
                                outer.objectDigest,
                                encoded,
                            )
                        } else {
                            require(existing.object_id == outer.objectId && existing.object_digest == outer.objectDigest &&
                                existing.encoded_outer == encoded)
                        }
                    }
                    val mutations = chunk.value.objects.map { outer ->
                        val preparedEntity = prepared.entities.single { it.encryptedObject.objectId == outer.objectId }
                        RemoteWorkspaceMutationV2(
                            checkNotNull(outer.mutationId),
                            outer.objectId,
                            outer.objectDigest,
                            outer.writerDeviceId,
                            preparedEntity.version,
                        )
                    }
                    val applied = store.applyRemoteCursorUnit(
                        RemoteWorkspaceCursorUnitV2(
                            prepared.remoteProfile,
                            WorkspaceRemoteCursorAdvanceV2(
                                "checkpoint:${descriptor.checkpointId}",
                                expectedCursor,
                                "${chunk.ref.chunkIndex}:${chunk.ref.chunkDigest}",
                                chunk.ref.chunkId,
                                chunk.ref.chunkDigest,
                            ),
                            mutations,
                            descriptor.createdAt,
                        ),
                    )
                    when (applied) {
                        is WorkspaceRemoteUnitApplyResultV2.Rejected -> error(applied.error.safeMessage)
                        is WorkspaceRemoteUnitApplyResultV2.Applied -> require(
                            applied.plans.values.none { it.generatedVersions.isNotEmpty() },
                        ) { "Prepared checkpoint omitted deterministic convergence versions." }
                        is WorkspaceRemoteUnitApplyResultV2.AlreadyApplied -> Unit
                    }
                    expectedCursor = "${chunk.ref.chunkIndex}:${chunk.ref.chunkDigest}"
                }
                require(store.loadProjection(WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                )) != null)
            }
            WorkspaceCheckpointPersistResultV2.Ready(
                checkNotNull(protocolStore.loadEpoch(prepared.remoteProfile, descriptor.syncEpochId)),
            )
        } catch (failure: Exception) {
            WorkspaceCheckpointPersistResultV2.Rejected(
                "checkpoint_local_verification_failed",
                (failure.message ?: "Prepared V2 checkpoint failed local verification.").take(500),
            )
        }
    }

    private fun insertControl(profile: String, epochId: String, outer: EncryptedWorkspaceObjectV2, at: Instant) {
        val queries = localRepository.database.somedayQueries
        val components = components(epochId)
        val encoded = components.cipher.encodeJson(outer)
        val existing = queries.selectControlObjectSystemV2(profile, epochId, outer.objectType, outer.objectId)
            .executeAsOneOrNull()
        if (existing == null) {
            queries.insertControlObjectSystemV2(
                profile, epochId, outer.objectType, outer.objectId, outer.objectDigest,
                encoded, "prepared", at.toEpochMilliseconds(), null,
            )
        } else {
            require(existing.object_digest == outer.objectDigest && existing.encoded_outer == encoded)
        }
    }

    private fun components(epochId: String): WorkspaceCheckpointComponentsV2 {
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(workspaceKey, epochId),
        )
        val validator = WorkspaceEntityValidatorV2(materializer)
        val wire = WorkspaceEntityWireCodecV2(materializer, validator)
        val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
        return WorkspaceCheckpointComponentsV2(
            epochId, materializer, validator, wire, cipher, localRepository, writerDeviceId,
        )
    }
}

sealed interface WorkspaceCheckpointPublishResultV2 {
    data class Published(val epoch: StoredSyncEpochV2, val idempotentReplay: Boolean) : WorkspaceCheckpointPublishResultV2
    data class LostRace(val currentPointer: EncryptedWorkspaceObjectV2?) : WorkspaceCheckpointPublishResultV2
    data class Rejected(val safeErrorCode: String, val safeMessage: String) : WorkspaceCheckpointPublishResultV2
}

sealed interface WorkspacePreparedCheckpointLoadResultV2 {
    data object None : WorkspacePreparedCheckpointLoadResultV2
    data class Loaded(val prepared: PreparedWorkspaceEpochCheckpointV2) : WorkspacePreparedCheckpointLoadResultV2
    data class Rejected(
        val epochId: String,
        val safeErrorCode: String,
        val safeMessage: String,
    ) : WorkspacePreparedCheckpointLoadResultV2
}

/**
 * Reconstructs a locally verified, not-yet-authoritative checkpoint after a
 * process crash. Publication must reuse these durable ids and ciphertexts;
 * rebuilding a new checkpoint would lose the exact retry identity.
 */
class WorkspacePreparedCheckpointRecoveryV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 = SqlDelightSyncProtocolStoreV2(localRepository.database),
) {
    fun loadCompatible(
        remoteProfile: String,
        currentPointer: EncryptedWorkspaceObjectV2?,
    ): WorkspacePreparedCheckpointLoadResultV2 {
        val candidate = protocolStore.loadEpochs(remoteProfile)
            .asSequence()
            .filter { it.lifecycle == SyncEpochLifecycleV2.PREPARING }
            .firstOrNull { epoch ->
                currentPointer?.syncEpochId == epoch.descriptor.syncEpochId ||
                    epochPointerPredecessor(remoteProfile, epoch)?.let { predecessor ->
                        predecessor == currentPointer?.objectDigest
                    } == true ||
                    (currentPointer == null && epochPointerPredecessor(remoteProfile, epoch) == null)
            }
            ?: return WorkspacePreparedCheckpointLoadResultV2.None
        return load(candidate)
    }

    fun load(epoch: StoredSyncEpochV2): WorkspacePreparedCheckpointLoadResultV2 {
        if (epoch.lifecycle != SyncEpochLifecycleV2.PREPARING) {
            return rejected(epoch, "checkpoint_not_preparing", "Only a prepared checkpoint can be resumed.")
        }
        return try {
            val descriptor = epoch.descriptor
            val materializer = CanonicalWorkspaceCausalityMaterializerV2(
                SyncEpochKeyDerivationV2().derive(workspaceKey, descriptor.syncEpochId),
            )
            val validator = WorkspaceEntityValidatorV2(materializer)
            val wire = WorkspaceEntityWireCodecV2(materializer, validator)
            val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
            val control = WorkspaceSyncControlCodecV2(cipher)
            val queries = localRepository.database.somedayQueries
            val controlRows = queries.selectControlObjectsSystemV2(
                epoch.remoteProfile,
                descriptor.syncEpochId,
            ).executeAsList()

            fun outer(type: String, id: String): EncryptedWorkspaceObjectV2 {
                val row = controlRows.singleOrNull { it.object_type == type && it.object_id == id }
                    ?: error("Prepared checkpoint control object is missing.")
                val decoded = cipher.decodeJson(row.encoded_outer).getOrElse {
                    error("Prepared checkpoint outer framing is invalid.")
                }
                require(decoded.objectDigest == row.object_digest)
                return decoded
            }

            val pointerObject = outer(SYNC_EPOCH_POINTER_OBJECT_TYPE_V2, SYNC_EPOCH_POINTER_ID_SYSTEM_V2)
            require(pointerObject.objectDigest == epoch.descriptorDigest)
            val pointer = when (val decoded = control.decodeEpochPointer(pointerObject)) {
                is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
                is WorkspaceControlDecodeResultV2.Rejected -> error(decoded.error.safeMessage)
            }
            require(pointer.descriptor == descriptor)

            val manifestObject = outer(SYNC_CHECKPOINT_MANIFEST_OBJECT_TYPE_V2, descriptor.checkpointId)
            require(manifestObject.objectDigest == descriptor.checkpointDigest)
            val manifest = when (val decoded = control.decodeCheckpointManifest(
                manifestObject,
                descriptor.syncEpochId,
                descriptor.checkpointId,
            )) {
                is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
                is WorkspaceControlDecodeResultV2.Rejected -> error(decoded.error.safeMessage)
            }
            val checkpointRow = queries.selectCheckpointSystemV2(
                epoch.remoteProfile,
                descriptor.syncEpochId,
                descriptor.checkpointId,
            ).executeAsOneOrNull() ?: error("Prepared checkpoint record is missing.")
            require(checkpointRow.manifest_digest == manifestObject.objectDigest)
            require(checkpointRow.encoded_manifest == cipher.encodeJson(manifestObject))

            val chunks = manifest.chunks.map { ref ->
                val chunkObject = outer(SYNC_CHECKPOINT_CHUNK_OBJECT_TYPE_V2, ref.chunkId)
                val chunk = when (val decoded = control.decodeCheckpointChunk(
                    chunkObject,
                    descriptor.syncEpochId,
                    descriptor.checkpointId,
                    ref,
                )) {
                    is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
                    is WorkspaceControlDecodeResultV2.Rejected -> error(decoded.error.safeMessage)
                }
                PreparedWorkspaceCheckpointChunkV2(chunk, ref, chunkObject)
            }
            val checkpointObjects = queries.selectCheckpointObjectsSystemV2(
                epoch.remoteProfile,
                descriptor.syncEpochId,
                descriptor.checkpointId,
            ).executeAsList()
            val entities = chunks.flatMap { chunk ->
                chunk.value.objects.mapIndexed { objectIndex, encrypted ->
                    val persisted = checkpointObjects.singleOrNull {
                        it.chunk_index == chunk.ref.chunkIndex.toLong() && it.object_index == objectIndex.toLong()
                    } ?: error("Prepared checkpoint entity object is missing.")
                    require(persisted.object_id == encrypted.objectId)
                    require(persisted.object_digest == encrypted.objectDigest)
                    require(persisted.encoded_outer == cipher.encodeJson(encrypted))
                    val plaintext = when (val decrypted = cipher.decrypt(encrypted)) {
                        is EncryptedWorkspaceObjectDecodeResultV2.Decoded -> decrypted.plaintext
                        is EncryptedWorkspaceObjectDecodeResultV2.Rejected -> error(decrypted.error.safeMessage)
                    }
                    val version = when (val decoded = wire.decode(
                        plaintext,
                        WorkspaceVersionOuterMetadataV2(
                            encrypted.syncEpochId,
                            encrypted.objectId,
                            encrypted.objectDigest,
                        ),
                    )) {
                        is WorkspaceEntityWireDecodeResultV2.Decoded -> decoded.version
                        is WorkspaceEntityWireDecodeResultV2.Rejected -> error(decoded.error.safeMessage)
                    }
                    PreparedWorkspaceCheckpointEntityV2(
                        version,
                        checkNotNull(encrypted.mutationId),
                        encrypted,
                    )
                }
            }
            require(checkpointObjects.size == entities.size)
            require(entities.size == manifest.totalObjectCount)
            WorkspacePreparedCheckpointLoadResultV2.Loaded(
                PreparedWorkspaceEpochCheckpointV2(
                    epoch.remoteProfile,
                    pointer,
                    pointerObject,
                    manifest,
                    manifestObject,
                    chunks,
                    entities,
                ),
            )
        } catch (failure: Exception) {
            rejected(
                epoch,
                "checkpoint_local_state_incomplete",
                (failure.message ?: "Prepared checkpoint cannot be resumed safely.").take(500),
            )
        }
    }

    private fun epochPointerPredecessor(remoteProfile: String, epoch: StoredSyncEpochV2): String? {
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(workspaceKey, epoch.descriptor.syncEpochId),
        )
        val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
        val row = localRepository.database.somedayQueries.selectControlObjectSystemV2(
            remoteProfile,
            epoch.descriptor.syncEpochId,
            SYNC_EPOCH_POINTER_OBJECT_TYPE_V2,
            SYNC_EPOCH_POINTER_ID_SYSTEM_V2,
        ).executeAsOneOrNull() ?: return "missing"
        val outer = cipher.decodeJson(row.encoded_outer).getOrNull() ?: return "invalid"
        val decoded = WorkspaceSyncControlCodecV2(cipher).decodeEpochPointer(outer)
        return (decoded as? WorkspaceControlDecodeResultV2.Decoded)?.value?.previousPointerDigest ?: when (decoded) {
            is WorkspaceControlDecodeResultV2.Decoded -> null
            is WorkspaceControlDecodeResultV2.Rejected -> "invalid"
        }
    }

    private fun rejected(
        epoch: StoredSyncEpochV2,
        code: String,
        message: String,
    ) = WorkspacePreparedCheckpointLoadResultV2.Rejected(
        epoch.descriptor.syncEpochId,
        code,
        message,
    )
}

/** Publishes immutable chunks/manifest, verifies them, then commits the sole mutable pointer. */
class WorkspaceCheckpointPublisherV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val remote: WorkspaceSyncRemoteV2,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 = SqlDelightSyncProtocolStoreV2(localRepository.database),
) {
    fun publish(prepared: PreparedWorkspaceEpochCheckpointV2): WorkspaceCheckpointPublishResultV2 {
        val descriptor = prepared.descriptor
        if (remote.remoteProfile != prepared.remoteProfile) {
            return WorkspaceCheckpointPublishResultV2.Rejected("remote_profile_mismatch", "Checkpoint targets another remote profile.")
        }
        prepared.chunks.forEach { chunk ->
            when (val result = remote.putCheckpointChunk(descriptor, chunk.ref, chunk.encryptedObject)) {
                is WorkspaceImmutablePutResultV2.Rejected -> return WorkspaceCheckpointPublishResultV2.Rejected(
                    result.safeErrorCode, result.safeMessage,
                )
                is WorkspaceImmutablePutResultV2.Stored -> markControl(prepared, chunk.encryptedObject, "published")
            }
        }
        when (val result = remote.putCheckpointManifest(descriptor, prepared.manifestObject)) {
            is WorkspaceImmutablePutResultV2.Rejected -> return WorkspaceCheckpointPublishResultV2.Rejected(
                result.safeErrorCode, result.safeMessage,
            )
            is WorkspaceImmutablePutResultV2.Stored -> markControl(prepared, prepared.manifestObject, "published")
        }
        val fetched = runCatching { remote.fetchCheckpoint(prepared.pointerObject, descriptor) }.getOrElse {
            return WorkspaceCheckpointPublishResultV2.Rejected(
                "checkpoint_remote_verification_failed",
                (it.message ?: "Published checkpoint could not be read back.").take(500),
            )
        }
        if (fetched.manifest.objectDigest != prepared.manifestObject.objectDigest ||
            fetched.chunks.map { it.objectDigest } != prepared.chunks.map { it.encryptedObject.objectDigest }
        ) {
            return WorkspaceCheckpointPublishResultV2.Rejected(
                "checkpoint_integrity_mismatch",
                "Read-back checkpoint identities do not match the prepared checkpoint.",
            )
        }
        return when (val result = remote.compareAndSetEpochPointer(
            descriptor,
            prepared.pointer.previousPointerDigest,
            prepared.pointerObject,
        )) {
            is WorkspacePointerPublishResultV2.CompareAndSetFailed -> WorkspaceCheckpointPublishResultV2.LostRace(result.current)
            is WorkspacePointerPublishResultV2.Rejected -> WorkspaceCheckpointPublishResultV2.Rejected(
                result.safeErrorCode, result.safeMessage,
            )
            is WorkspacePointerPublishResultV2.Published -> {
                val now = descriptor.createdAt
                localRepository.database.transaction {
                    markControl(prepared, prepared.pointerObject, "active")
                    localRepository.database.somedayQueries.updateCheckpointStateSystemV2(
                        "active", now.toEpochMilliseconds(), prepared.remoteProfile,
                        descriptor.syncEpochId, descriptor.checkpointId,
                    )
                    protocolStore.activateEpoch(
                        prepared.remoteProfile,
                        descriptor.syncEpochId,
                        now,
                        descriptor.createdByDeviceId,
                        remote.authorityBindingId,
                    )
                    prepared.entities.filter {
                        it.version.provenance?.type == WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT
                    }.forEach { entity ->
                        val provenance = checkNotNull(entity.version.provenance)
                        localRepository.database.somedayQueries.updateSourceImportStateSystemV2(
                            "published",
                            now.toEpochMilliseconds(),
                            prepared.remoteProfile,
                            descriptor.syncEpochId,
                            checkNotNull(provenance.sourceProfile),
                            checkNotNull(provenance.sourceObjectId),
                            checkNotNull(provenance.sourceDigest),
                        )
                    }
                }
                WorkspaceCheckpointPublishResultV2.Published(
                    checkNotNull(protocolStore.loadActiveEpoch(prepared.remoteProfile)),
                    result.idempotentReplay,
                )
            }
        }
    }

    private fun markControl(
        prepared: PreparedWorkspaceEpochCheckpointV2,
        outer: EncryptedWorkspaceObjectV2,
        state: String,
    ) {
        localRepository.database.somedayQueries.updateControlObjectStateSystemV2(
            state,
            prepared.descriptor.createdAt.toEpochMilliseconds(),
            prepared.remoteProfile,
            prepared.descriptor.syncEpochId,
            outer.objectType,
            outer.objectId,
        )
    }
}

private data class WorkspaceCheckpointComponentsV2(
    val epochId: String,
    val materializer: CanonicalWorkspaceCausalityMaterializerV2,
    val validator: WorkspaceEntityValidatorV2,
    val wire: WorkspaceEntityWireCodecV2,
    val cipher: WorkspaceObjectCipherV2,
    val localRepository: SqlDelightLocalDataRepository,
    val writerDeviceId: String,
) {
    fun store(remoteProfile: String): SqlDelightWorkspaceEntityStoreV2 = SqlDelightWorkspaceEntityStoreV2(
        localRepository.database,
        epochId,
        WorkspaceEntityCausalityEngineV2(materializer, validator),
        materializer,
        wire,
        WorkspaceOutboxEncoderV2 { version, mutationId ->
            PreparedWorkspaceOutboxObjectV2(
                writerDeviceId,
                cipher.encodeJson(cipher.encryptEntity(version, mutationId, writerDeviceId, wire.encode(version))),
            )
        },
    )
}

val CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2: Comparator<WorkspaceCheckpointSourceHeadV2> =
    compareBy<WorkspaceCheckpointSourceHeadV2>({ it.entityType.wireValue }, { it.entityId }, { it.sourceObjectId }, { it.sourceObjectDigest })

val CHECKPOINT_IMPORT_COMPARATOR_SYSTEM_V2: Comparator<WorkspaceCheckpointSourceImportV2> =
    compareBy<WorkspaceCheckpointSourceImportV2>({ it.entityType.wireValue }, { it.entityId }, { it.sourceObjectId }, { it.sourceObjectDigest })

private val CHECKPOINT_VERSION_COMPARATOR_SYSTEM_V2: Comparator<WorkspaceEntityVersionV2> =
    compareBy<WorkspaceEntityVersionV2>({ it.entityType.wireValue }, { it.entityId }, { it.generation }, { it.versionId })
