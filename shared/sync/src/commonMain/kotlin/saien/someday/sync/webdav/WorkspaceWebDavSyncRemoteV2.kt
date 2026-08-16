@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.webdav

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.domain.settings.WebDavDiscoveredDevice
import saien.someday.sync.causality.v2.CanonicalWorkspaceCausalityMaterializerV2
import saien.someday.sync.causality.v2.EncryptedWorkspaceObjectDecodeResultV2
import saien.someday.sync.causality.v2.EncryptedWorkspaceObjectV2
import saien.someday.sync.causality.v2.MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2
import saien.someday.sync.causality.v2.MINIMUM_WRITER_VERSION_V2
import saien.someday.sync.causality.v2.RandomUuidCausalityIdGeneratorV2
import saien.someday.sync.causality.v2.SEMANTIC_SYNC_PROTOCOL_VERSION_V2
import saien.someday.sync.causality.v2.SYNC_KEY_SET_VERSION_V2
import saien.someday.sync.causality.v2.SYNC_EPOCH_POINTER_ID_SYSTEM_V2
import saien.someday.sync.causality.v2.SYNC_EPOCH_POINTER_OBJECT_TYPE_V2
import saien.someday.sync.causality.v2.SYNC_V2_CONTRACT_ID
import saien.someday.sync.causality.v2.SYNC_V2_SCHEMA_SET_VERSION
import saien.someday.sync.causality.v2.SealWorkspaceTransportUnitResultV2
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.StoredWorkspaceTransportUnitV2
import saien.someday.sync.causality.v2.SyncEpochDescriptorV2
import saien.someday.sync.causality.v2.SyncEpochKeyDerivationV2
import saien.someday.sync.causality.v2.SyncMetadataPrivacyModeV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.SyncStreamFrontierV2
import saien.someday.sync.causality.v2.UUID_V4_PATTERN_SYSTEM_V2
import saien.someday.sync.causality.v2.WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2
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
import saien.someday.sync.causality.v2.WorkspaceSyncControlCodecV2
import saien.someday.sync.causality.v2.WorkspaceSyncPullResultV2
import saien.someday.sync.causality.v2.WorkspaceSyncPushResultV2
import saien.someday.sync.causality.v2.WorkspaceSyncRemoteV2
import saien.someday.sync.causality.v2.WorkspaceWebDavLogSegmentV2
import saien.someday.sync.causality.v2.WorkspaceWebDavSegmentRefV2
import saien.someday.sync.causality.v2.WorkspaceWebDavWriterManifestV2
import saien.someday.sync.causality.v2.WorkspaceObjectCipherV2
import saien.someday.sync.causality.v2.normalizeWriterDeviceIdV2
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/** Exact WebDAV log-v2 profile for the whole-product V2 contract. */
class WorkspaceWebDavSyncRemoteV2(
    private val client: WebDavClient,
    private val workspaceKey: WorkspaceMasterKey,
    private val localWriterDeviceId: String,
    private val protocolStore: SqlDelightSyncProtocolStoreV2,
    private val idGenerator: RandomUuidCausalityIdGeneratorV2 = RandomUuidCausalityIdGeneratorV2(),
    private val clock: () -> Instant = { Clock.System.now() },
) : WorkspaceSyncRemoteV2 {
    fun discoveredDevices(syncEpochId: String): List<WebDavDiscoveredDevice> {
        val controls = codecs(syncEpochId)
        val seenWriters = mutableSetOf<String>()
        return client.listDirectory(paths.v2LogManifestDirectory(syncEpochId))
            .filter { !it.collection && it.path.endsWith(".enc") }
            .map { resource ->
                val manifest = decodeManifest(controls, requireRemote(resource.path, "writer_manifest_missing"))
                requireCanonicalManifestSlot(syncEpochId, resource.path, manifest, seenWriters)
                WebDavDiscoveredDevice(
                    deviceId = manifest.writerDeviceId,
                    firstSeenAtEpochMillis = manifest.segments.firstOrNull()?.createdAt?.toEpochMilliseconds(),
                    lastSeenAtEpochMillis = manifest.segments.lastOrNull()?.createdAt?.toEpochMilliseconds(),
                    isCurrentDevice = manifest.writerDeviceId == localWriterDeviceId,
                )
            }
            .sortedWith(compareByDescending<WebDavDiscoveredDevice> {
                it.isCurrentDevice
            }.thenByDescending { it.lastSeenAtEpochMillis ?: Long.MIN_VALUE })
    }
    override val remoteProfile: String = SyncRemoteProfileV2.WEB_DAV.wireValue
    override val authorityBindingId: String = client.v2AuthorityBindingId()
    private val paths = client.pathResolver()
    private val framingCipher by lazy { codecs(FRAMING_EPOCH_ID).cipher }

    init {
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(localWriterDeviceId))
    }

    override fun capabilities() = WorkspaceSyncCapabilitiesV2(
        profile = remoteProfile,
        contractId = SYNC_V2_CONTRACT_ID,
        semanticProtocolVersion = SEMANTIC_SYNC_PROTOCOL_VERSION_V2,
        schemaSetVersion = SYNC_V2_SCHEMA_SET_VERSION,
        keySetVersion = SYNC_KEY_SET_VERSION_V2,
        metadataPrivacyMode = SyncMetadataPrivacyModeV2.OPAQUE.wireValue,
        maxPushObjects = MAX_SEGMENT_OBJECTS,
        maxPullUnits = MAX_PULL_UNITS,
        maxEncodedBodyBytes = MAX_ENCODED_BODY_BYTES,
        supportsCheckpoints = true,
    )

    override fun loadEpochPointer(): EncryptedWorkspaceObjectV2? =
        client.getRawObject(paths.v2EpochPointer())?.decodeOuter()

    override fun loadRetainedEpochPointer(syncEpochId: String): EncryptedWorkspaceObjectV2? {
        val current = loadEpochPointer()
        if (current?.syncEpochId == syncEpochId) return current
        return client.getRawObject(paths.v2RetainedEpochPointer(syncEpochId))?.decodeOuter()?.also { retained ->
            if (retained.syncEpochId != syncEpochId || retained.objectType != SYNC_EPOCH_POINTER_OBJECT_TYPE_V2 ||
                retained.objectId != SYNC_EPOCH_POINTER_ID_SYSTEM_V2
            ) {
                throw WebDavV2ProtocolException(
                    "epoch_pointer_history_mismatch",
                    "Retained pointer path and authenticated identity disagree.",
                )
            }
        }
    }

    override fun fetchCheckpoint(
        pointer: EncryptedWorkspaceObjectV2,
        descriptor: SyncEpochDescriptorV2,
    ): WorkspaceRemoteCheckpointBundleV2 {
        require(pointer.syncEpochId == descriptor.syncEpochId)
        val controls = codecs(descriptor.syncEpochId)
        val manifestOuter = requireRemote(
            paths.v2CheckpointManifest(descriptor.syncEpochId, descriptor.checkpointId),
            "checkpoint_manifest_missing",
        )
        val manifest = when (val result = controls.control.decodeCheckpointManifest(
            manifestOuter, descriptor.syncEpochId, descriptor.checkpointId,
        )) {
            is WorkspaceControlDecodeResultV2.Decoded -> result.value
            is WorkspaceControlDecodeResultV2.Rejected -> throw WebDavV2ProtocolException(
                result.error.code.wireValue, result.error.safeMessage,
            )
        }
        require(manifestOuter.objectDigest == descriptor.checkpointDigest) {
            "Checkpoint manifest does not match the authenticated descriptor."
        }
        val chunks = manifest.chunks.map { ref ->
            requireRemote(
                paths.v2CheckpointChunk(
                    descriptor.syncEpochId, descriptor.checkpointId, ref.chunkIndex, ref.chunkId,
                ),
                "checkpoint_chunk_missing",
            ).also { outer ->
                when (val decoded = controls.control.decodeCheckpointChunk(
                    outer, descriptor.syncEpochId, descriptor.checkpointId, ref,
                )) {
                    is WorkspaceControlDecodeResultV2.Decoded -> Unit
                    is WorkspaceControlDecodeResultV2.Rejected -> throw WebDavV2ProtocolException(
                        decoded.error.code.wireValue, decoded.error.safeMessage,
                    )
                }
            }
        }
        return WorkspaceRemoteCheckpointBundleV2(pointer, manifestOuter, chunks)
    }

    override fun putCheckpointChunk(
        descriptor: SyncEpochDescriptorV2,
        ref: WorkspaceCheckpointChunkRefV2,
        chunk: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 {
        when (val decoded = codecs(descriptor.syncEpochId).control.decodeCheckpointChunk(
            chunk, descriptor.syncEpochId, descriptor.checkpointId, ref,
        )) {
            is WorkspaceControlDecodeResultV2.Rejected -> return WorkspaceImmutablePutResultV2.Rejected(
                decoded.error.code.wireValue, decoded.error.safeMessage,
            )
            is WorkspaceControlDecodeResultV2.Decoded -> Unit
        }
        return putImmutable(
            paths.v2CheckpointChunk(descriptor.syncEpochId, descriptor.checkpointId, ref.chunkIndex, ref.chunkId),
            chunk,
        )
    }

    override fun putCheckpointManifest(
        descriptor: SyncEpochDescriptorV2,
        manifest: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 {
        if (manifest.objectDigest != descriptor.checkpointDigest) {
            return WorkspaceImmutablePutResultV2.Rejected(
                "checkpoint_integrity_mismatch", "Manifest digest differs from the epoch descriptor.",
            )
        }
        when (val decoded = codecs(descriptor.syncEpochId).control.decodeCheckpointManifest(
            manifest, descriptor.syncEpochId, descriptor.checkpointId,
        )) {
            is WorkspaceControlDecodeResultV2.Rejected -> return WorkspaceImmutablePutResultV2.Rejected(
                decoded.error.code.wireValue, decoded.error.safeMessage,
            )
            is WorkspaceControlDecodeResultV2.Decoded -> Unit
        }
        return putImmutable(paths.v2CheckpointManifest(descriptor.syncEpochId, descriptor.checkpointId), manifest)
    }

    override fun compareAndSetEpochPointer(
        descriptor: SyncEpochDescriptorV2,
        expectedCurrentDigest: String?,
        pointer: EncryptedWorkspaceObjectV2,
    ): WorkspacePointerPublishResultV2 {
        val controls = codecs(descriptor.syncEpochId)
        val decodedPointer = when (val result = controls.control.decodeEpochPointer(pointer)) {
            is WorkspaceControlDecodeResultV2.Decoded -> result.value
            is WorkspaceControlDecodeResultV2.Rejected -> return WorkspacePointerPublishResultV2.Rejected(
                result.error.code.wireValue, result.error.safeMessage,
            )
        }
        if (decodedPointer.descriptor != descriptor || decodedPointer.previousPointerDigest != expectedCurrentDigest) {
            return WorkspacePointerPublishResultV2.Rejected(
                "epoch_pointer_metadata_mismatch", "Pointer transition does not match its descriptor and expected predecessor.",
            )
        }
        val path = paths.v2EpochPointer()
        val currentRaw = client.getRawObject(path)
        val current = currentRaw?.decodeOuter()
        if (current != null && semanticallyEqual(current, pointer)) {
            return WorkspacePointerPublishResultV2.Published(idempotentReplay = true)
        }
        if (current?.objectDigest != expectedCurrentDigest) {
            return WorkspacePointerPublishResultV2.CompareAndSetFailed(current)
        }
        if (current != null && current.syncEpochId == pointer.syncEpochId) {
            return WorkspacePointerPublishResultV2.Rejected(
                "epoch_id_reuse", "A changed pointer cannot reuse an existing epoch id.",
            )
        }
        if (current != null) {
            when (val archived = putImmutable(paths.v2RetainedEpochPointer(current.syncEpochId), current)) {
                is WorkspaceImmutablePutResultV2.Stored -> Unit
                is WorkspaceImmutablePutResultV2.Rejected -> return WorkspacePointerPublishResultV2.Rejected(
                    archived.safeErrorCode,
                    archived.safeMessage,
                )
            }
        }
        return when (val result = client.uploadRawMutable(path, encodeBytes(pointer), currentRaw?.etag)) {
            is WebDavRawUploadResult.Uploaded -> WorkspacePointerPublishResultV2.Published(false)
            is WebDavRawUploadResult.Rejected -> WorkspacePointerPublishResultV2.Rejected(
                "webdav_pointer_write_failed", result.safeMessage,
            )
            is WebDavRawUploadResult.PreconditionConflict -> {
                val competing = result.remote?.decodeOuter()
                if (competing != null && semanticallyEqual(competing, pointer)) {
                    WorkspacePointerPublishResultV2.Published(true)
                } else {
                    WorkspacePointerPublishResultV2.CompareAndSetFailed(competing)
                }
            }
        }
    }

    override fun cleanupCheckpointDraft(
        draft: WorkspaceCheckpointDraftCleanupV2,
    ): WorkspaceCheckpointDraftCleanupResultV2 {
        if (draft.remoteProfile != remoteProfile) {
            return retainedCleanup(
                "remote_profile_mismatch",
                "Checkpoint cleanup targets another remote profile.",
            )
        }
        val draftControls = codecs(draft.descriptor.syncEpochId).control
        val decodedDraftPointer = when (val decoded = draftControls.decodeEpochPointer(draft.pointerObject)) {
            is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
            is WorkspaceControlDecodeResultV2.Rejected ->
                return retainedCleanup(decoded.error.code.wireValue, decoded.error.safeMessage)
        }
        if (decodedDraftPointer != draft.pointer) {
            return retainedCleanup(
                "checkpoint_cleanup_identity_mismatch",
                "Local checkpoint cleanup pointer does not match its durable descriptor.",
            )
        }
        when (val decoded = draftControls.decodeCheckpointManifest(
            draft.manifestObject,
            draft.descriptor.syncEpochId,
            draft.descriptor.checkpointId,
        )) {
            is WorkspaceControlDecodeResultV2.Decoded -> Unit
            is WorkspaceControlDecodeResultV2.Rejected ->
                return retainedCleanup(decoded.error.code.wireValue, decoded.error.safeMessage)
        }
        draft.chunks.forEach { chunk ->
            when (val decoded = draftControls.decodeCheckpointChunk(
                chunk.encryptedObject,
                draft.descriptor.syncEpochId,
                draft.descriptor.checkpointId,
                chunk.ref,
            )) {
                is WorkspaceControlDecodeResultV2.Decoded -> Unit
                is WorkspaceControlDecodeResultV2.Rejected ->
                    return retainedCleanup(decoded.error.code.wireValue, decoded.error.safeMessage)
            }
        }

        val currentOuter = runCatching(::loadEpochPointer).getOrElse {
            return retainedCleanup("epoch_pointer_read_failed", it.message ?: "Current pointer could not be read.")
        } ?: return retainedCleanup(
            "checkpoint_still_publishable",
            "An empty remote cannot prove that this checkpoint draft is obsolete.",
        )
        val currentPointer = when (
            val decoded = codecs(currentOuter.syncEpochId).control.decodeEpochPointer(currentOuter)
        ) {
            is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
            is WorkspaceControlDecodeResultV2.Rejected ->
                return retainedCleanup(decoded.error.code.wireValue, decoded.error.safeMessage)
        }
        if (currentPointer.descriptor.syncEpochId == draft.descriptor.syncEpochId) {
            return retainedCleanup(
                "checkpoint_referenced",
                "The checkpoint is referenced by the authenticated current pointer.",
            )
        }
        if (currentPointer.descriptor.previousEpochId == draft.descriptor.syncEpochId) {
            return retainedCleanup(
                "checkpoint_referenced",
                if (currentPointer.descriptor.previousEpochPointerDigest == draft.pointerObject.objectDigest) {
                    "The checkpoint is the authenticated current pointer's direct predecessor."
                } else {
                    "The current pointer names this predecessor epoch with a different authenticated digest."
                },
            )
        }
        if (currentOuter.objectDigest == draft.pointer.previousPointerDigest) {
            return retainedCleanup(
                "checkpoint_still_publishable",
                "The checkpoint draft is still a valid successor of the current pointer.",
            )
        }

        val retainedOuter = runCatching {
            client.getRawObject(paths.v2RetainedEpochPointer(draft.descriptor.syncEpochId))?.decodeOuter()
        }.getOrElse {
            return retainedCleanup("epoch_pointer_history_read_failed", it.message ?: "Pointer history could not be read.")
        }
        if (retainedOuter != null) {
            val retainedPointer = when (val decoded = draftControls.decodeEpochPointer(retainedOuter)) {
                is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
                is WorkspaceControlDecodeResultV2.Rejected ->
                    return retainedCleanup(decoded.error.code.wireValue, decoded.error.safeMessage)
            }
            if (retainedPointer.descriptor.syncEpochId == draft.descriptor.syncEpochId) {
                return retainedCleanup(
                    "checkpoint_referenced",
                    "The checkpoint is referenced by authenticated pointer history.",
                )
            }
            return retainedCleanup(
                "epoch_pointer_history_mismatch",
                "Retained pointer identity differs from the checkpoint cleanup record.",
            )
        }

        var alreadyAbsent = true
        val objects = buildList {
            add(
                paths.v2CheckpointManifest(draft.descriptor.syncEpochId, draft.descriptor.checkpointId) to
                    draft.manifestObject,
            )
            draft.chunks.forEach { chunk ->
                add(
                    paths.v2CheckpointChunk(
                        draft.descriptor.syncEpochId,
                        draft.descriptor.checkpointId,
                        chunk.ref.chunkIndex,
                        chunk.ref.chunkId,
                    ) to chunk.encryptedObject,
                )
            }
        }
        objects.forEach { (path, outer) ->
            when (val deleted = client.deleteRawObjectIfUnchanged(path, encodeBytes(outer))) {
                is WebDavRawDeleteResult.Deleted -> if (!deleted.alreadyAbsent) alreadyAbsent = false
                is WebDavRawDeleteResult.Rejected ->
                    return retainedCleanup("checkpoint_cleanup_failed", deleted.safeMessage)
            }
        }
        return WorkspaceCheckpointDraftCleanupResultV2.Deleted(alreadyAbsent)
    }

    override fun pull(
        syncEpochId: String,
        cursors: Map<String, String?>,
        limit: Int,
    ): WorkspaceSyncPullResultV2 {
        require(limit in 1..MAX_PULL_UNITS) { "WebDAV V2 pull limit is outside the advertised capability." }
        val controls = codecs(syncEpochId)
        val resources = client.listDirectory(paths.v2LogManifestDirectory(syncEpochId))
            .filter { !it.collection && it.path.endsWith(".enc") }
            .sortedBy { it.path }
        val seenWriters = mutableSetOf<String>()
        val streams = mutableListOf<Pair<WorkspaceWebDavWriterManifestV2, Int>>()
        for (resource in resources) {
            val manifestOuter = requireRemote(resource.path, "writer_manifest_missing")
            val manifest = decodeManifest(controls, manifestOuter)
            requireCanonicalManifestSlot(syncEpochId, resource.path, manifest, seenWriters)
            val currentCursor = cursors[manifest.writerDeviceId]
            val start = if (currentCursor == null) 0 else {
                manifest.segments.indexOfFirst { it.cursorValue() == currentCursor }
                    .takeIf { it >= 0 }
                    ?.plus(1)
                    ?: return WorkspaceSyncPullResultV2(
                        emptyList(),
                        frontierStable = true,
                        safeErrorCode = "remote_rollback_detected",
                    )
            }
            streams += manifest to start
        }
        val totalAvailable = streams.sumOf { (manifest, start) -> manifest.segments.size - start }
        val units = mutableListOf<WorkspaceEncryptedCursorUnitV2>()
        var offset = 0
        while (units.size < limit) {
            var foundAtOffset = false
            for ((manifest, start) in streams) {
                if (units.size >= limit) break
                val index = start + offset
                if (index !in manifest.segments.indices) continue
                foundAtOffset = true
                val ref = manifest.segments[index]
                val path = paths.v2LogSegment(syncEpochId, manifest.writerDeviceId, ref.ordinal, ref.segmentId)
                val segmentOuter = requireRemote(path, "segment_missing")
                val segment = decodeSegment(controls, segmentOuter)
                if (segment.syncEpochId != syncEpochId || segment.writerDeviceId != manifest.writerDeviceId ||
                    segment.ordinal != ref.ordinal || segment.segmentId != ref.segmentId ||
                    segment.previousSegmentDigest != ref.previousSegmentDigest ||
                    segmentOuter.objectDigest != ref.segmentDigest || segment.objects.size != ref.entryCount ||
                    controls.control.logSegmentPlaintextBytes(segment) != ref.plaintextBytes
                ) {
                    throw WebDavV2ProtocolException("segment_manifest_mismatch", "Segment does not match its authenticated manifest reference.")
                }
                units += WorkspaceEncryptedCursorUnitV2(
                    syncEpochId,
                    manifest.writerDeviceId,
                    manifest.segments.getOrNull(index - 1)?.cursorValue(),
                    ref.cursorValue(),
                    ref.segmentId,
                    ref.segmentDigest,
                    segment.objects,
                )
            }
            if (!foundAtOffset) break
            offset++
        }
        val stable = units.size == totalAvailable
        return WorkspaceSyncPullResultV2(units, frontierStable = stable)
    }

    override fun push(
        syncEpochId: String,
        objects: List<EncryptedWorkspaceObjectV2>,
    ): WorkspaceSyncPushResultV2 {
        if (objects.isEmpty()) return WorkspaceSyncPushResultV2.Accepted(emptyList())
        if (objects.size > MAX_SEGMENT_OBJECTS || objects.any {
                it.syncEpochId != syncEpochId || it.objectType != WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2 ||
                    it.writerDeviceId != localWriterDeviceId || it.mutationId == null
            }
        ) {
            return WorkspaceSyncPushResultV2.Rejected("transport_metadata_mismatch", "WebDAV segment input violates V2 bounds or writer identity.")
        }
        if (loadEpochPointer()?.syncEpochId != syncEpochId) {
            return WorkspaceSyncPushResultV2.Rejected("incompatible_epoch", "WebDAV pointer no longer names this epoch.")
        }
        val controls = codecs(syncEpochId)
        val tuples = objects.tupleString()
        val open = protocolStore.loadOpenWorkspaceTransportUnits(remoteProfile, syncEpochId, localWriterDeviceId)
        if (open.size > 1) {
            return WorkspaceSyncPushResultV2.Rejected("local_transaction_failed", "Multiple WebDAV units are open for one writer.")
        }
        val sealed = open.singleOrNull() ?: createSealedSegment(syncEpochId, objects, tuples, controls)
            .getOrElse { return WorkspaceSyncPushResultV2.Rejected("writer_stream_fork", it.message ?: "WebDAV writer stream could not be sealed.") }
        if (sealed.orderedMutationTuples != tuples) {
            return WorkspaceSyncPushResultV2.Rejected("pending_segment_busy", "A sealed WebDAV unit must finish before another batch.")
        }
        val segmentOuter = decode(sealed.encodedUnitOuter)
        val segment = decodeSegment(controls, segmentOuter)
        val segmentPath = paths.v2LogSegment(syncEpochId, localWriterDeviceId, segment.ordinal, segment.segmentId)
        when (val upload = client.uploadRawAppendOnly(segmentPath, encodeBytes(segmentOuter))) {
            is WebDavRawUploadResult.Uploaded -> Unit
            is WebDavRawUploadResult.Rejected -> return WorkspaceSyncPushResultV2.Rejected(
                "segment_write_failed", upload.safeMessage,
            )
            is WebDavRawUploadResult.PreconditionConflict -> {
                val remoteOuter = upload.remote?.decodeOuter()
                if (remoteOuter == null || !semanticallyEqual(remoteOuter, segmentOuter)) {
                    return WorkspaceSyncPushResultV2.Rejected("immutable_object_mismatch", "Segment path already contains another semantic object.")
                }
            }
        }
        protocolStore.updateWorkspaceTransportUnitState(
            remoteProfile, syncEpochId, localWriterDeviceId, segment.segmentId, "object_published", clock(),
        )
        val ref = WorkspaceWebDavSegmentRefV2(
            segment.ordinal,
            segment.segmentId,
            segmentOuter.objectDigest,
            segment.previousSegmentDigest,
            segment.objects.size,
            controls.control.logSegmentPlaintextBytes(segment),
            segment.createdAt,
        )
        val replayed = when (val result = publishManifest(syncEpochId, ref, controls)) {
            is ManifestPublishV2.Rejected -> return WorkspaceSyncPushResultV2.Rejected(result.code, result.message)
            is ManifestPublishV2.Published -> result.replayed
        }
        protocolStore.updateWorkspaceTransportUnitState(
            remoteProfile, syncEpochId, localWriterDeviceId, segment.segmentId, "committed", clock(),
        )
        return WorkspaceSyncPushResultV2.Accepted(objects.map { value ->
            WorkspaceMutationAckV2(
                checkNotNull(value.mutationId), value.objectId, value.objectDigest, replayed,
            )
        })
    }

    override fun epochFrontiers(syncEpochId: String): List<SyncStreamFrontierV2> {
        val controls = codecs(syncEpochId)
        val seenWriters = mutableSetOf<String>()
        return client.listDirectory(paths.v2LogManifestDirectory(syncEpochId))
            .filter { !it.collection && it.path.endsWith(".enc") }
            .map { resource ->
                val outer = requireRemote(resource.path, "writer_manifest_missing")
                val manifest = decodeManifest(controls, outer)
                requireCanonicalManifestSlot(syncEpochId, resource.path, manifest, seenWriters)
                SyncStreamFrontierV2(
                    manifest.writerDeviceId,
                    manifest.segments.lastOrNull()?.cursorValue(),
                    outer.objectDigest,
                )
            }
            .sortedBy { it.streamId }
    }

    private fun requireCanonicalManifestSlot(
        syncEpochId: String,
        resourcePath: String,
        manifest: WorkspaceWebDavWriterManifestV2,
        seenWriters: MutableSet<String>,
    ) {
        if (manifest.syncEpochId != syncEpochId) {
            throw WebDavV2ProtocolException("manifest_epoch_mismatch", "Writer manifest belongs to another epoch.")
        }
        if (resourcePath != paths.v2LogManifest(syncEpochId, manifest.writerDeviceId)) {
            throw WebDavV2ProtocolException(
                "manifest_path_mismatch",
                "Writer manifest path and authenticated writer identity disagree.",
            )
        }
        if (!seenWriters.add(manifest.writerDeviceId)) {
            throw WebDavV2ProtocolException(
                "duplicate_writer_manifest",
                "One authenticated writer appears more than once in the manifest inventory.",
            )
        }
    }

    override fun fetchRepairReplicas(
        syncEpochId: String,
        objectId: String,
        objectDigest: String,
    ): List<EncryptedWorkspaceObjectV2> {
        val resources = client.listDirectory(paths.v2RepairDirectory(syncEpochId, objectId))
            .filter { !it.collection && it.path.endsWith(".enc") }
            .sortedBy { it.path }
        if (resources.size > MAX_REPAIR_REPLICAS) {
            throw WebDavV2ProtocolException("repair_replica_set_invalid", "Repair replica count exceeds the protocol bound.")
        }
        return resources.map { resource ->
            val outer = requireRemote(resource.path, "repair_replica_missing")
            val pathWriter = resource.path.substringAfterLast('/').removeSuffix(".enc")
            if (outer.syncEpochId != syncEpochId || outer.objectId != objectId || outer.objectDigest != objectDigest ||
                outer.writerDeviceId != pathWriter
            ) {
                throw WebDavV2ProtocolException("repair_replica_set_invalid", "Repair path and authenticated outer identity disagree.")
            }
            outer
        }
    }

    override fun publishRepairReplica(objectValue: EncryptedWorkspaceObjectV2): WorkspaceImmutablePutResultV2 {
        if (objectValue.writerDeviceId != localWriterDeviceId) {
            return WorkspaceImmutablePutResultV2.Rejected("repair_replica_writer_mismatch", "A writer may publish only its own repair replica.")
        }
        val path = paths.v2RepairReplica(objectValue.syncEpochId, objectValue.objectId, localWriterDeviceId)
        val existingRaw = client.getRawObject(path)
        val existing = existingRaw?.decodeOuter()
        if (existing != null && semanticallyEqual(existing, objectValue)) {
            return WorkspaceImmutablePutResultV2.Stored(true)
        }
        return when (val result = client.uploadRawMutable(path, encodeBytes(objectValue), existingRaw?.etag)) {
            is WebDavRawUploadResult.Uploaded -> WorkspaceImmutablePutResultV2.Stored(false)
            is WebDavRawUploadResult.PreconditionConflict -> {
                val current = result.remote?.decodeOuter()
                if (current != null && semanticallyEqual(current, objectValue)) {
                    WorkspaceImmutablePutResultV2.Stored(true)
                } else WorkspaceImmutablePutResultV2.Rejected("repair_replica_compare_and_set_failed", result.safeMessage)
            }
            is WebDavRawUploadResult.Rejected -> WorkspaceImmutablePutResultV2.Rejected("repair_replica_write_failed", result.safeMessage)
        }
    }

    private fun createSealedSegment(
        epochId: String,
        objects: List<EncryptedWorkspaceObjectV2>,
        tuples: String,
        controls: EpochWebDavCodecsV2,
    ): Result<StoredWorkspaceTransportUnitV2> = runCatching {
        val remoteManifest = loadWriterManifest(epochId, controls)?.second
        val localUnits = protocolStore.loadWorkspaceTransportUnits(remoteProfile, epochId, localWriterDeviceId)
        val remoteCount = remoteManifest?.segments?.size ?: 0
        require(localUnits.count { it.state == "committed" } <= remoteCount) { "Remote writer manifest rolled back." }
        val ordinal = remoteCount.toLong() + 1L
        val previous = remoteManifest?.segments?.lastOrNull()?.segmentDigest
        val segment = WorkspaceWebDavLogSegmentV2(
            syncEpochId = epochId,
            writerDeviceId = localWriterDeviceId,
            ordinal = ordinal,
            segmentId = idGenerator.newId(),
            previousSegmentDigest = previous,
            createdAt = clock(),
            objects = objects,
        )
        require(controls.control.logSegmentPlaintextBytes(segment) <= MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2)
        val outer = controls.control.encodeLogSegment(segment, localWriterDeviceId)
        val value = StoredWorkspaceTransportUnitV2(
            remoteProfile, epochId, localWriterDeviceId, segment.segmentId, outer.objectDigest,
            previous, ordinal, encodeText(outer), tuples, "sealed", segment.createdAt.toEpochMilliseconds(), null,
        )
        when (val result = protocolStore.sealWorkspaceTransportUnit(value)) {
            is SealWorkspaceTransportUnitResultV2.ImmutableMismatch -> error(result.safeMessage)
            is SealWorkspaceTransportUnitResultV2.Sealed -> result.value
        }
    }

    private fun publishManifest(
        epochId: String,
        ref: WorkspaceWebDavSegmentRefV2,
        controls: EpochWebDavCodecsV2,
    ): ManifestPublishV2 {
        val path = paths.v2LogManifest(epochId, localWriterDeviceId)
        repeat(MAX_MANIFEST_CAS_ATTEMPTS) {
            val currentPair = loadWriterManifest(epochId, controls)
            val currentRaw = currentPair?.first
            val current = currentPair?.second ?: WorkspaceWebDavWriterManifestV2(
                syncEpochId = epochId,
                writerDeviceId = localWriterDeviceId,
                previousManifestDigest = null,
                segments = emptyList(),
            )
            current.segments.firstOrNull { it.ordinal == ref.ordinal || it.segmentId == ref.segmentId }?.let { existing ->
                return if (existing == ref) ManifestPublishV2.Published(true)
                else ManifestPublishV2.Rejected("writer_stream_fork", "One writer ordinal names another segment.")
            }
            if (ref.ordinal != current.segments.size.toLong() + 1L ||
                ref.previousSegmentDigest != current.segments.lastOrNull()?.segmentDigest
            ) return ManifestPublishV2.Rejected("writer_stream_fork", "Segment is not a strict manifest suffix.")
            val updated = current.copy(
                previousManifestDigest = currentRaw?.second?.objectDigest,
                segments = current.segments + ref,
            )
            if (workspaceWebDavManifestRequiresEpochRolloverV2(
                    controls.control.writerManifestPlaintextBytes(updated),
                )
            ) {
                return ManifestPublishV2.Rejected(
                    "manifest_full_epoch_rollover_required", "WebDAV writer manifest reached its bound; roll the epoch.",
                )
            }
            val encoded = controls.control.encodeWriterManifest(updated, localWriterDeviceId)
            when (val upload = client.uploadRawMutable(path, encodeBytes(encoded), currentRaw?.first?.etag)) {
                is WebDavRawUploadResult.Uploaded -> return ManifestPublishV2.Published(false)
                is WebDavRawUploadResult.Rejected -> return ManifestPublishV2.Rejected("manifest_write_failed", upload.safeMessage)
                is WebDavRawUploadResult.PreconditionConflict -> Unit
            }
        }
        return ManifestPublishV2.Rejected("manifest_changed_pull_required", "Writer manifest changed repeatedly; refetch before retry.")
    }

    /** Pair is raw+outer, then decoded manifest. */
    private fun loadWriterManifest(
        epochId: String,
        controls: EpochWebDavCodecsV2,
    ): Pair<Pair<WebDavRawStoredObject, EncryptedWorkspaceObjectV2>, WorkspaceWebDavWriterManifestV2>? {
        val raw = client.getRawObject(paths.v2LogManifest(epochId, localWriterDeviceId)) ?: return null
        val outer = raw.decodeOuter()
        return (raw to outer) to decodeManifest(controls, outer)
    }

    private fun decodeManifest(
        controls: EpochWebDavCodecsV2,
        outer: EncryptedWorkspaceObjectV2,
    ): WorkspaceWebDavWriterManifestV2 = when (val value = controls.control.decodeWriterManifest(outer)) {
        is WorkspaceControlDecodeResultV2.Decoded -> value.value
        is WorkspaceControlDecodeResultV2.Rejected -> throw WebDavV2ProtocolException(value.error.code.wireValue, value.error.safeMessage)
    }

    private fun decodeSegment(
        controls: EpochWebDavCodecsV2,
        outer: EncryptedWorkspaceObjectV2,
    ): WorkspaceWebDavLogSegmentV2 = when (val value = controls.control.decodeLogSegment(outer)) {
        is WorkspaceControlDecodeResultV2.Decoded -> value.value
        is WorkspaceControlDecodeResultV2.Rejected -> throw WebDavV2ProtocolException(value.error.code.wireValue, value.error.safeMessage)
    }

    private fun putImmutable(path: String, value: EncryptedWorkspaceObjectV2): WorkspaceImmutablePutResultV2 =
        when (val result = client.uploadRawAppendOnly(path, encodeBytes(value))) {
            is WebDavRawUploadResult.Uploaded -> WorkspaceImmutablePutResultV2.Stored(false)
            is WebDavRawUploadResult.Rejected -> WorkspaceImmutablePutResultV2.Rejected("webdav_immutable_write_failed", result.safeMessage)
            is WebDavRawUploadResult.PreconditionConflict -> {
                val current = result.remote?.decodeOuter()
                if (current != null && semanticallyEqual(current, value)) {
                    WorkspaceImmutablePutResultV2.Stored(true)
                } else WorkspaceImmutablePutResultV2.Rejected(
                    "immutable_object_mismatch", "Immutable WebDAV path contains another authenticated semantic object.",
                )
            }
        }

    private fun semanticallyEqual(first: EncryptedWorkspaceObjectV2, second: EncryptedWorkspaceObjectV2): Boolean {
        if (first.syncEpochId != second.syncEpochId || first.objectType != second.objectType ||
            first.objectId != second.objectId || first.objectDigest != second.objectDigest || first.mutationId != second.mutationId
        ) return false
        val cipher = codecs(first.syncEpochId).cipher
        val firstPlain = (cipher.decrypt(first) as? EncryptedWorkspaceObjectDecodeResultV2.Decoded)?.plaintext ?: return false
        val secondPlain = (cipher.decrypt(second) as? EncryptedWorkspaceObjectDecodeResultV2.Decoded)?.plaintext ?: return false
        return firstPlain.contentEquals(secondPlain)
    }

    private fun requireRemote(path: String, code: String): EncryptedWorkspaceObjectV2 =
        client.getRawObject(path)?.decodeOuter() ?: throw WebDavV2ProtocolException(code, "Required WebDAV V2 object is missing.")

    private fun WebDavRawStoredObject.decodeOuter(): EncryptedWorkspaceObjectV2 = decode(bytes.decodeToString())

    private fun decode(value: String): EncryptedWorkspaceObjectV2 = framingCipher.decodeJson(value).getOrElse {
        throw WebDavV2ProtocolException("malformed_outer", "WebDAV V2 outer JSON violates the exact framing contract.")
    }

    private fun encodeText(value: EncryptedWorkspaceObjectV2): String = codecs(value.syncEpochId).cipher.encodeJson(value)

    private fun encodeBytes(value: EncryptedWorkspaceObjectV2): ByteArray =
        encodeText(value).encodeToByteArray().also { encoded ->
            require(encoded.size <= MAX_ENCODED_BODY_BYTES) {
                "WebDAV V2 object exceeds the negotiated encoded body limit."
            }
        }

    private fun retainedCleanup(
        code: String,
        message: String,
    ) = WorkspaceCheckpointDraftCleanupResultV2.Retained(code, message.take(500))

    private fun codecs(epochId: String): EpochWebDavCodecsV2 {
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(workspaceKey, epochId),
        )
        val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
        return EpochWebDavCodecsV2(cipher, WorkspaceSyncControlCodecV2(cipher))
    }

    private sealed interface ManifestPublishV2 {
        data class Published(val replayed: Boolean) : ManifestPublishV2
        data class Rejected(val code: String, val message: String) : ManifestPublishV2
    }

    private companion object {
        const val MAX_SEGMENT_OBJECTS = 64
        const val MAX_PULL_UNITS = 256
        const val MAX_ENCODED_BODY_BYTES = 16 * 1_024 * 1_024
        const val MAX_REPAIR_REPLICAS = 64
        const val MAX_MANIFEST_CAS_ATTEMPTS = 4
        const val FRAMING_EPOCH_ID = "00000000-0000-4000-8000-000000000001"
    }
}

internal fun workspaceWebDavManifestRequiresEpochRolloverV2(plaintextBytes: Int): Boolean {
    require(plaintextBytes >= 0)
    return plaintextBytes > MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2
}

private data class EpochWebDavCodecsV2(
    val cipher: WorkspaceObjectCipherV2,
    val control: WorkspaceSyncControlCodecV2,
)

private fun WorkspaceWebDavSegmentRefV2.cursorValue(): String = "$ordinal:$segmentId:$segmentDigest"

private fun List<EncryptedWorkspaceObjectV2>.tupleString(): String = joinToString("\n") { value ->
    "${value.mutationId}|${value.objectId}|${value.objectDigest}"
}

private class WebDavV2ProtocolException(val code: String, message: String) : IllegalStateException(message)
