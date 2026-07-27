@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import kotlin.time.Instant
import kotlinx.serialization.Serializable

const val SYNC_EPOCH_POINTER_ID_SYSTEM_V2: String = "epoch-pointer"
const val MAX_CHECKPOINT_CHUNK_OBJECTS_SYSTEM_V2: Int = 64
const val MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2: Int = 8 * 1_024 * 1_024
const val MAX_CONTROL_DESCRIPTOR_PLAINTEXT_SYSTEM_V2: Int = 1 * 1_024 * 1_024

data class WorkspaceSyncEpochPointerV2(
    val schemaVersion: Int = 1,
    val previousPointerDigest: String?,
    val descriptor: SyncEpochDescriptorV2,
)

@Serializable
data class WorkspaceCheckpointChunkRefV2(
    val chunkIndex: Int,
    val chunkId: String,
    val chunkDigest: String,
    val objectCount: Int,
    val plaintextBytes: Int,
)

data class WorkspaceCheckpointManifestV2(
    val schemaVersion: Int = 1,
    val contractId: String = SYNC_V2_CONTRACT_ID,
    val schemaSetVersion: String = SYNC_V2_SCHEMA_SET_VERSION,
    val syncEpochId: String,
    val checkpointId: String,
    val createdAt: Instant,
    val chunks: List<WorkspaceCheckpointChunkRefV2>,
    val totalObjectCount: Int,
)

data class WorkspaceCheckpointChunkV2(
    val schemaVersion: Int = 1,
    val contractId: String = SYNC_V2_CONTRACT_ID,
    val schemaSetVersion: String = SYNC_V2_SCHEMA_SET_VERSION,
    val syncEpochId: String,
    val checkpointId: String,
    val chunkIndex: Int,
    val chunkId: String,
    val objects: List<EncryptedWorkspaceObjectV2>,
)

data class WorkspaceWebDavSegmentRefV2(
    val ordinal: Long,
    val segmentId: String,
    val segmentDigest: String,
    val previousSegmentDigest: String?,
    val entryCount: Int,
    val plaintextBytes: Int,
    val createdAt: Instant,
)

data class WorkspaceWebDavWriterManifestV2(
    val schemaVersion: Int = 1,
    val contractId: String = SYNC_V2_CONTRACT_ID,
    val syncEpochId: String,
    val writerDeviceId: String,
    val previousManifestDigest: String?,
    val segments: List<WorkspaceWebDavSegmentRefV2>,
)

data class WorkspaceWebDavLogSegmentV2(
    val schemaVersion: Int = 1,
    val contractId: String = SYNC_V2_CONTRACT_ID,
    val syncEpochId: String,
    val writerDeviceId: String,
    val ordinal: Long,
    val segmentId: String,
    val previousSegmentDigest: String?,
    val createdAt: Instant,
    val objects: List<EncryptedWorkspaceObjectV2>,
)

enum class WorkspaceControlErrorCodeV2(val wireValue: String) {
    WRONG_OBJECT_TYPE("wrong_object_type"),
    AUTHENTICATION_FAILED("authentication_failed"),
    CONTROL_DIGEST_MISMATCH("control_digest_mismatch"),
    MALFORMED_CBOR("malformed_cbor"),
    INVALID_CONTROL_PAYLOAD("invalid_control_payload"),
    INCOMPATIBLE_CONTRACT("incompatible_contract"),
    BOUNDS_EXCEEDED("bounds_exceeded"),
}

data class WorkspaceControlErrorV2(
    val code: WorkspaceControlErrorCodeV2,
    val safeMessage: String,
)

sealed interface WorkspaceControlDecodeResultV2<out T> {
    data class Decoded<T>(val value: T) : WorkspaceControlDecodeResultV2<T>
    data class Rejected(val error: WorkspaceControlErrorV2) : WorkspaceControlDecodeResultV2<Nothing>
}

/** One strict commonMain codec for every authenticated V2 control payload. */
class WorkspaceSyncControlCodecV2(
    private val cipher: WorkspaceObjectCipherV2,
) {
    fun checkpointChunkPlaintextBytes(chunk: WorkspaceCheckpointChunkV2): Int {
        validateChunk(chunk)
        return DeterministicCborV2.encode(chunk.toCborV2()).size
    }

    fun writerManifestPlaintextBytes(manifest: WorkspaceWebDavWriterManifestV2): Int {
        validateWriterManifest(manifest)
        return DeterministicCborV2.encode(manifest.toCborV2()).size
    }

    fun logSegmentPlaintextBytes(segment: WorkspaceWebDavLogSegmentV2): Int {
        validateSegment(segment)
        return DeterministicCborV2.encode(segment.toCborV2()).size
    }

    fun encodeEpochPointer(
        pointer: WorkspaceSyncEpochPointerV2,
        writerDeviceId: String,
    ): EncryptedWorkspaceObjectV2 {
        validatePointer(pointer)
        return encrypt(
            pointer.descriptor.syncEpochId,
            SYNC_EPOCH_POINTER_OBJECT_TYPE_V2,
            SYNC_EPOCH_POINTER_ID_SYSTEM_V2,
            writerDeviceId,
            pointer.toCborV2(),
            MAX_CONTROL_DESCRIPTOR_PLAINTEXT_SYSTEM_V2,
        )
    }

    fun decodeEpochPointer(outer: EncryptedWorkspaceObjectV2): WorkspaceControlDecodeResultV2<WorkspaceSyncEpochPointerV2> =
        decode(
            outer,
            SYNC_EPOCH_POINTER_OBJECT_TYPE_V2,
            SYNC_EPOCH_POINTER_ID_SYSTEM_V2,
            MAX_CONTROL_DESCRIPTOR_PLAINTEXT_SYSTEM_V2,
        ) { value ->
            value.toEpochPointerV2().also(::validatePointer)
        }

    fun encodeCheckpointManifest(
        manifest: WorkspaceCheckpointManifestV2,
        writerDeviceId: String,
    ): EncryptedWorkspaceObjectV2 {
        validateManifest(manifest)
        return encrypt(
            manifest.syncEpochId,
            SYNC_CHECKPOINT_MANIFEST_OBJECT_TYPE_V2,
            manifest.checkpointId,
            writerDeviceId,
            manifest.toCborV2(),
            MAX_CONTROL_DESCRIPTOR_PLAINTEXT_SYSTEM_V2,
        )
    }

    fun decodeCheckpointManifest(
        outer: EncryptedWorkspaceObjectV2,
        expectedEpochId: String,
        expectedCheckpointId: String,
    ): WorkspaceControlDecodeResultV2<WorkspaceCheckpointManifestV2> =
        decode(
            outer,
            SYNC_CHECKPOINT_MANIFEST_OBJECT_TYPE_V2,
            expectedCheckpointId,
            MAX_CONTROL_DESCRIPTOR_PLAINTEXT_SYSTEM_V2,
        ) { value ->
            value.toCheckpointManifestV2().also {
                validateManifest(it)
                require(it.syncEpochId == expectedEpochId)
            }
        }

    fun encodeCheckpointChunk(
        chunk: WorkspaceCheckpointChunkV2,
        writerDeviceId: String,
    ): EncryptedWorkspaceObjectV2 {
        validateChunk(chunk)
        return encrypt(
            chunk.syncEpochId,
            SYNC_CHECKPOINT_CHUNK_OBJECT_TYPE_V2,
            chunk.chunkId,
            writerDeviceId,
            chunk.toCborV2(),
            MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2,
        )
    }

    fun decodeCheckpointChunk(
        outer: EncryptedWorkspaceObjectV2,
        expectedEpochId: String,
        expectedCheckpointId: String,
        expectedRef: WorkspaceCheckpointChunkRefV2,
    ): WorkspaceControlDecodeResultV2<WorkspaceCheckpointChunkV2> =
        decode(
            outer,
            SYNC_CHECKPOINT_CHUNK_OBJECT_TYPE_V2,
            expectedRef.chunkId,
            MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2,
        ) { value ->
            value.toCheckpointChunkV2().also { chunk ->
                validateChunk(chunk)
                require(chunk.syncEpochId == expectedEpochId && chunk.checkpointId == expectedCheckpointId)
                require(chunk.chunkIndex == expectedRef.chunkIndex)
                require(chunk.objects.size == expectedRef.objectCount)
                require(DeterministicCborV2.encode(value).size == expectedRef.plaintextBytes)
                require(outer.objectDigest == expectedRef.chunkDigest)
            }
        }

    fun encodeWriterManifest(
        manifest: WorkspaceWebDavWriterManifestV2,
        writerDeviceId: String,
    ): EncryptedWorkspaceObjectV2 {
        validateWriterManifest(manifest)
        require(writerDeviceId == manifest.writerDeviceId)
        return encrypt(
            manifest.syncEpochId,
            "webdav_writer_manifest_v2",
            "writer-manifest:${manifest.writerDeviceId}",
            writerDeviceId,
            manifest.toCborV2(),
            MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2,
        )
    }

    fun decodeWriterManifest(outer: EncryptedWorkspaceObjectV2): WorkspaceControlDecodeResultV2<WorkspaceWebDavWriterManifestV2> =
        decode(
            outer,
            "webdav_writer_manifest_v2",
            outer.objectId,
            MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2,
        ) { value ->
            value.toWriterManifestV2().also { manifest ->
                validateWriterManifest(manifest)
                require(outer.objectId == "writer-manifest:${manifest.writerDeviceId}")
                require(outer.writerDeviceId == manifest.writerDeviceId)
            }
        }

    fun encodeLogSegment(
        segment: WorkspaceWebDavLogSegmentV2,
        writerDeviceId: String,
    ): EncryptedWorkspaceObjectV2 {
        validateSegment(segment)
        require(writerDeviceId == segment.writerDeviceId)
        return encrypt(
            segment.syncEpochId,
            "webdav_log_segment_v2",
            segment.segmentId,
            writerDeviceId,
            segment.toCborV2(),
            MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2,
        )
    }

    fun decodeLogSegment(outer: EncryptedWorkspaceObjectV2): WorkspaceControlDecodeResultV2<WorkspaceWebDavLogSegmentV2> =
        decode(
            outer,
            "webdav_log_segment_v2",
            outer.objectId,
            MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2,
        ) { value ->
            value.toLogSegmentV2().also { segment ->
                validateSegment(segment)
                require(segment.segmentId == outer.objectId && segment.writerDeviceId == outer.writerDeviceId)
            }
        }

    private fun encrypt(
        epochId: String,
        type: String,
        id: String,
        writerDeviceId: String,
        value: CborValueV2,
        maxBytes: Int,
    ): EncryptedWorkspaceObjectV2 {
        val plaintext = DeterministicCborV2.encode(value)
        require(plaintext.size <= maxBytes)
        return cipher.encryptControl(epochId, type, id, writerDeviceId, plaintext)
    }

    private inline fun <T> decode(
        outer: EncryptedWorkspaceObjectV2,
        expectedType: String,
        expectedId: String,
        maxPlaintextBytes: Int,
        parse: (CborValueV2) -> T,
    ): WorkspaceControlDecodeResultV2<T> {
        if (outer.objectType != expectedType || outer.objectId != expectedId) {
            return rejected(WorkspaceControlErrorCodeV2.WRONG_OBJECT_TYPE, "Control outer type or slot id does not match.")
        }
        val plaintext = when (val decrypted = cipher.decrypt(outer)) {
            is EncryptedWorkspaceObjectDecodeResultV2.Decoded -> decrypted.plaintext
            is EncryptedWorkspaceObjectDecodeResultV2.Rejected -> return rejected(
                WorkspaceControlErrorCodeV2.AUTHENTICATION_FAILED,
                "Control object authentication failed (${decrypted.error.code.wireValue}).",
            )
        }
        if (plaintext.size > maxPlaintextBytes) {
            return rejected(
                WorkspaceControlErrorCodeV2.BOUNDS_EXCEEDED,
                "Control plaintext exceeds the exact object-type limit.",
            )
        }
        if (runCatching { cipher.controlDigest(expectedType, expectedId, plaintext) }.getOrNull() != outer.objectDigest) {
            return rejected(WorkspaceControlErrorCodeV2.CONTROL_DIGEST_MISMATCH, "Control payload digest does not match its outer identity.")
        }
        val value = try {
            DeterministicCborV2.decode(plaintext)
        } catch (_: Exception) {
            return rejected(WorkspaceControlErrorCodeV2.MALFORMED_CBOR, "Control plaintext is not deterministic protocol CBOR.")
        }
        return try {
            WorkspaceControlDecodeResultV2.Decoded(parse(value))
        } catch (_: Exception) {
            rejected(WorkspaceControlErrorCodeV2.INVALID_CONTROL_PAYLOAD, "Control payload violates its exact schema or bounds.")
        }
    }

    private fun validatePointer(value: WorkspaceSyncEpochPointerV2) {
        require(value.schemaVersion == 1)
        require(value.previousPointerDigest == null || CONTROL_DIGEST_PATTERN_SYSTEM_V2.matches(value.previousPointerDigest))
        require(value.descriptor.contractId == SYNC_V2_CONTRACT_ID)
        require(value.descriptor.schemaSetVersion == SYNC_V2_SCHEMA_SET_VERSION)
    }

    private fun validateManifest(value: WorkspaceCheckpointManifestV2) {
        require(value.schemaVersion == 1 && value.contractId == SYNC_V2_CONTRACT_ID)
        require(value.schemaSetVersion == SYNC_V2_SCHEMA_SET_VERSION)
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(value.syncEpochId) && UUID_V4_PATTERN_SYSTEM_V2.matches(value.checkpointId))
        require(value.chunks.isNotEmpty() && value.chunks.map { it.chunkIndex } == value.chunks.indices.toList())
        require(value.chunks.sumOf { it.objectCount } == value.totalObjectCount)
        value.chunks.forEach {
            require(UUID_V4_PATTERN_SYSTEM_V2.matches(it.chunkId))
            require(CONTROL_DIGEST_PATTERN_SYSTEM_V2.matches(it.chunkDigest))
            require(it.objectCount in 1..MAX_CHECKPOINT_CHUNK_OBJECTS_SYSTEM_V2)
            require(it.plaintextBytes in 1..MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2)
        }
    }

    private fun validateChunk(value: WorkspaceCheckpointChunkV2) {
        require(value.schemaVersion == 1 && value.contractId == SYNC_V2_CONTRACT_ID)
        require(value.schemaSetVersion == SYNC_V2_SCHEMA_SET_VERSION)
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(value.syncEpochId))
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(value.checkpointId) && UUID_V4_PATTERN_SYSTEM_V2.matches(value.chunkId))
        require(value.chunkIndex >= 0 && value.objects.size in 1..MAX_CHECKPOINT_CHUNK_OBJECTS_SYSTEM_V2)
        require(value.objects.all {
            it.objectType == WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2 && it.syncEpochId == value.syncEpochId
        })
        require(value.objects.map { it.objectId }.distinct().size == value.objects.size)
    }

    private fun validateWriterManifest(value: WorkspaceWebDavWriterManifestV2) {
        require(value.schemaVersion == 1 && value.contractId == SYNC_V2_CONTRACT_ID)
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(value.syncEpochId) && UUID_V4_PATTERN_SYSTEM_V2.matches(value.writerDeviceId))
        require(value.previousManifestDigest == null || CONTROL_DIGEST_PATTERN_SYSTEM_V2.matches(value.previousManifestDigest))
        value.segments.forEachIndexed { index, ref ->
            require(ref.ordinal == index + 1L)
            require(UUID_V4_PATTERN_SYSTEM_V2.matches(ref.segmentId))
            require(CONTROL_DIGEST_PATTERN_SYSTEM_V2.matches(ref.segmentDigest))
            require(ref.previousSegmentDigest == value.segments.getOrNull(index - 1)?.segmentDigest)
            require(ref.entryCount in 1..64 && ref.plaintextBytes in 1..MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2)
        }
    }

    private fun validateSegment(value: WorkspaceWebDavLogSegmentV2) {
        require(value.schemaVersion == 1 && value.contractId == SYNC_V2_CONTRACT_ID)
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(value.syncEpochId))
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(value.writerDeviceId) && UUID_V4_PATTERN_SYSTEM_V2.matches(value.segmentId))
        require(value.ordinal >= 1 && value.objects.size in 1..64)
        require((value.ordinal == 1L) == (value.previousSegmentDigest == null))
        require(value.previousSegmentDigest == null || CONTROL_DIGEST_PATTERN_SYSTEM_V2.matches(value.previousSegmentDigest))
        require(value.objects.all {
            it.objectType == WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2 &&
                it.syncEpochId == value.syncEpochId && it.writerDeviceId == value.writerDeviceId
        })
        require(value.objects.map { it.mutationId }.distinct().size == value.objects.size)
    }

    private fun <T> rejected(code: WorkspaceControlErrorCodeV2, message: String): WorkspaceControlDecodeResultV2<T> =
        WorkspaceControlDecodeResultV2.Rejected(WorkspaceControlErrorV2(code, message))
}

private fun WorkspaceSyncEpochPointerV2.toCborV2() = cborMap(
    "schemaVersion" to cborInt(schemaVersion.toLong()),
    "previousPointerDigest" to cborNullableText(previousPointerDigest),
    "descriptor" to descriptor.toCborV2(),
)

private fun SyncEpochDescriptorV2.toCborV2() = cborMap(
    "schemaVersion" to cborInt(schemaVersion.toLong()),
    "contractId" to cborText(contractId),
    "semanticProtocolVersion" to cborInt(semanticProtocolVersion.toLong()),
    "schemaSetVersion" to cborText(schemaSetVersion),
    "minimumWriterProtocolVersion" to cborInt(minimumWriterProtocolVersion.toLong()),
    "keySetVersion" to cborText(keySetVersion),
    "syncEpochId" to cborText(syncEpochId),
    "remoteProfile" to cborText(remoteProfile),
    "supportedOfflineWindowSeconds" to cborInt(supportedOfflineWindowSeconds),
    "metadataPrivacyMode" to cborText(metadataPrivacyMode),
    "checkpointId" to cborText(checkpointId),
    "checkpointDigest" to cborText(checkpointDigest),
    "previousEpochId" to cborNullableText(previousEpochId),
    "previousEpochPointerDigest" to cborNullableText(previousEpochPointerDigest),
    "createdByDeviceId" to cborText(createdByDeviceId),
    "createdAt" to controlInstantV2(createdAt),
    "previousEpochFrontiers" to cborArray(previousEpochFrontiers.map { it.toCborV2() }),
)

private fun SyncStreamFrontierV2.toCborV2() = cborMap(
    "streamId" to cborText(streamId),
    "cursorValue" to cborNullableText(cursorValue),
    "streamDigest" to cborText(streamDigest),
)

private fun WorkspaceCheckpointManifestV2.toCborV2() = cborMap(
    "schemaVersion" to cborInt(schemaVersion.toLong()),
    "contractId" to cborText(contractId),
    "schemaSetVersion" to cborText(schemaSetVersion),
    "syncEpochId" to cborText(syncEpochId),
    "checkpointId" to cborText(checkpointId),
    "createdAt" to controlInstantV2(createdAt),
    "chunks" to cborArray(chunks.map { it.toCborV2() }),
    "totalObjectCount" to cborInt(totalObjectCount.toLong()),
)

private fun WorkspaceCheckpointChunkRefV2.toCborV2() = cborMap(
    "chunkIndex" to cborInt(chunkIndex.toLong()),
    "chunkId" to cborText(chunkId),
    "chunkDigest" to cborText(chunkDigest),
    "objectCount" to cborInt(objectCount.toLong()),
    "plaintextBytes" to cborInt(plaintextBytes.toLong()),
)

private fun WorkspaceCheckpointChunkV2.toCborV2() = cborMap(
    "schemaVersion" to cborInt(schemaVersion.toLong()),
    "contractId" to cborText(contractId),
    "schemaSetVersion" to cborText(schemaSetVersion),
    "syncEpochId" to cborText(syncEpochId),
    "checkpointId" to cborText(checkpointId),
    "chunkIndex" to cborInt(chunkIndex.toLong()),
    "chunkId" to cborText(chunkId),
    "objects" to cborArray(objects.map { it.toCborV2() }),
)

private fun WorkspaceWebDavWriterManifestV2.toCborV2() = cborMap(
    "schemaVersion" to cborInt(schemaVersion.toLong()),
    "contractId" to cborText(contractId),
    "syncEpochId" to cborText(syncEpochId),
    "writerDeviceId" to cborText(writerDeviceId),
    "previousManifestDigest" to cborNullableText(previousManifestDigest),
    "segments" to cborArray(segments.map { it.toCborV2() }),
)

private fun WorkspaceWebDavSegmentRefV2.toCborV2() = cborMap(
    "ordinal" to cborInt(ordinal),
    "segmentId" to cborText(segmentId),
    "segmentDigest" to cborText(segmentDigest),
    "previousSegmentDigest" to cborNullableText(previousSegmentDigest),
    "entryCount" to cborInt(entryCount.toLong()),
    "plaintextBytes" to cborInt(plaintextBytes.toLong()),
    "createdAt" to controlInstantV2(createdAt),
)

private fun WorkspaceWebDavLogSegmentV2.toCborV2() = cborMap(
    "schemaVersion" to cborInt(schemaVersion.toLong()),
    "contractId" to cborText(contractId),
    "syncEpochId" to cborText(syncEpochId),
    "writerDeviceId" to cborText(writerDeviceId),
    "ordinal" to cborInt(ordinal),
    "segmentId" to cborText(segmentId),
    "previousSegmentDigest" to cborNullableText(previousSegmentDigest),
    "createdAt" to controlInstantV2(createdAt),
    "objects" to cborArray(objects.map { it.toCborV2() }),
)

private fun EncryptedWorkspaceObjectV2.toCborV2() = cborMap(
    "outerSchemaVersion" to cborInt(outerSchemaVersion.toLong()),
    "contractId" to cborText(contractId),
    "schemaSetVersion" to cborText(schemaSetVersion),
    "keySetVersion" to cborText(keySetVersion),
    "syncEpochId" to cborText(syncEpochId),
    "objectType" to cborText(objectType),
    "objectId" to cborText(objectId),
    "objectDigest" to cborText(objectDigest),
    "mutationId" to cborNullableText(mutationId),
    "writerDeviceId" to cborText(writerDeviceId),
    "cipherSuite" to cborText(cipherSuite),
    "nonceBase64" to cborText(nonceBase64),
    "ciphertextBase64" to cborText(ciphertextBase64),
    "ciphertextDigest" to cborText(ciphertextDigest),
)

private fun controlInstantV2(value: Instant) = cborArray(
    listOf(cborInt(value.epochSeconds), cborInt(value.nanosecondsOfSecond.toLong())),
)

private fun CborValueV2.toEpochPointerV2(): WorkspaceSyncEpochPointerV2 {
    val map = exactControlMapV2(setOf("schemaVersion", "previousPointerDigest", "descriptor"))
    return WorkspaceSyncEpochPointerV2(
        schemaVersion = map.intV2("schemaVersion").toIntExactV2(),
        previousPointerDigest = map.nullableTextV2("previousPointerDigest"),
        descriptor = map.getValue("descriptor").toEpochDescriptorV2(),
    )
}

private fun CborValueV2.toEpochDescriptorV2(): SyncEpochDescriptorV2 {
    val keys = setOf(
        "schemaVersion", "contractId", "semanticProtocolVersion", "schemaSetVersion",
        "minimumWriterProtocolVersion", "keySetVersion", "syncEpochId", "remoteProfile",
        "supportedOfflineWindowSeconds", "metadataPrivacyMode", "checkpointId", "checkpointDigest",
        "previousEpochId", "previousEpochPointerDigest", "createdByDeviceId", "createdAt",
        "previousEpochFrontiers",
    )
    val map = exactControlMapV2(keys)
    return SyncEpochDescriptorV2(
        schemaVersion = map.intV2("schemaVersion").toIntExactV2(),
        contractId = map.textV2("contractId"),
        semanticProtocolVersion = map.intV2("semanticProtocolVersion").toIntExactV2(),
        schemaSetVersion = map.textV2("schemaSetVersion"),
        minimumWriterProtocolVersion = map.intV2("minimumWriterProtocolVersion").toIntExactV2(),
        keySetVersion = map.textV2("keySetVersion"),
        syncEpochId = map.textV2("syncEpochId"),
        remoteProfile = map.textV2("remoteProfile"),
        supportedOfflineWindowSeconds = map.intV2("supportedOfflineWindowSeconds"),
        metadataPrivacyMode = map.textV2("metadataPrivacyMode"),
        checkpointId = map.textV2("checkpointId"),
        checkpointDigest = map.textV2("checkpointDigest"),
        previousEpochId = map.nullableTextV2("previousEpochId"),
        previousEpochPointerDigest = map.nullableTextV2("previousEpochPointerDigest"),
        createdByDeviceId = map.textV2("createdByDeviceId"),
        createdAt = map.getValue("createdAt").instantV2(),
        previousEpochFrontiers = map.arrayV2("previousEpochFrontiers").map { it.toStreamFrontierV2() },
    )
}

private fun CborValueV2.toStreamFrontierV2(): SyncStreamFrontierV2 {
    val map = exactControlMapV2(setOf("streamId", "cursorValue", "streamDigest"))
    return SyncStreamFrontierV2(map.textV2("streamId"), map.nullableTextV2("cursorValue"), map.textV2("streamDigest"))
}

private fun CborValueV2.toCheckpointManifestV2(): WorkspaceCheckpointManifestV2 {
    val map = exactControlMapV2(setOf(
        "schemaVersion", "contractId", "schemaSetVersion", "syncEpochId", "checkpointId",
        "createdAt", "chunks", "totalObjectCount",
    ))
    return WorkspaceCheckpointManifestV2(
        map.intV2("schemaVersion").toIntExactV2(),
        map.textV2("contractId"),
        map.textV2("schemaSetVersion"),
        map.textV2("syncEpochId"),
        map.textV2("checkpointId"),
        map.getValue("createdAt").instantV2(),
        map.arrayV2("chunks").map { it.toChunkRefV2() },
        map.intV2("totalObjectCount").toIntExactV2(),
    )
}

private fun CborValueV2.toChunkRefV2(): WorkspaceCheckpointChunkRefV2 {
    val map = exactControlMapV2(setOf("chunkIndex", "chunkId", "chunkDigest", "objectCount", "plaintextBytes"))
    return WorkspaceCheckpointChunkRefV2(
        map.intV2("chunkIndex").toIntExactV2(),
        map.textV2("chunkId"),
        map.textV2("chunkDigest"),
        map.intV2("objectCount").toIntExactV2(),
        map.intV2("plaintextBytes").toIntExactV2(),
    )
}

private fun CborValueV2.toCheckpointChunkV2(): WorkspaceCheckpointChunkV2 {
    val map = exactControlMapV2(setOf(
        "schemaVersion", "contractId", "schemaSetVersion", "syncEpochId", "checkpointId",
        "chunkIndex", "chunkId", "objects",
    ))
    return WorkspaceCheckpointChunkV2(
        map.intV2("schemaVersion").toIntExactV2(),
        map.textV2("contractId"),
        map.textV2("schemaSetVersion"),
        map.textV2("syncEpochId"),
        map.textV2("checkpointId"),
        map.intV2("chunkIndex").toIntExactV2(),
        map.textV2("chunkId"),
        map.arrayV2("objects").map { it.toEncryptedOuterV2() },
    )
}

private fun CborValueV2.toWriterManifestV2(): WorkspaceWebDavWriterManifestV2 {
    val map = exactControlMapV2(setOf(
        "schemaVersion", "contractId", "syncEpochId", "writerDeviceId", "previousManifestDigest", "segments",
    ))
    return WorkspaceWebDavWriterManifestV2(
        map.intV2("schemaVersion").toIntExactV2(), map.textV2("contractId"), map.textV2("syncEpochId"),
        map.textV2("writerDeviceId"), map.nullableTextV2("previousManifestDigest"),
        map.arrayV2("segments").map { it.toSegmentRefV2() },
    )
}

private fun CborValueV2.toSegmentRefV2(): WorkspaceWebDavSegmentRefV2 {
    val map = exactControlMapV2(setOf(
        "ordinal", "segmentId", "segmentDigest", "previousSegmentDigest", "entryCount", "plaintextBytes", "createdAt",
    ))
    return WorkspaceWebDavSegmentRefV2(
        map.intV2("ordinal"), map.textV2("segmentId"), map.textV2("segmentDigest"),
        map.nullableTextV2("previousSegmentDigest"), map.intV2("entryCount").toIntExactV2(),
        map.intV2("plaintextBytes").toIntExactV2(), map.getValue("createdAt").instantV2(),
    )
}

private fun CborValueV2.toLogSegmentV2(): WorkspaceWebDavLogSegmentV2 {
    val map = exactControlMapV2(setOf(
        "schemaVersion", "contractId", "syncEpochId", "writerDeviceId", "ordinal", "segmentId",
        "previousSegmentDigest", "createdAt", "objects",
    ))
    return WorkspaceWebDavLogSegmentV2(
        map.intV2("schemaVersion").toIntExactV2(), map.textV2("contractId"), map.textV2("syncEpochId"),
        map.textV2("writerDeviceId"), map.intV2("ordinal"), map.textV2("segmentId"),
        map.nullableTextV2("previousSegmentDigest"), map.getValue("createdAt").instantV2(),
        map.arrayV2("objects").map { it.toEncryptedOuterV2() },
    )
}

private fun CborValueV2.toEncryptedOuterV2(): EncryptedWorkspaceObjectV2 {
    val map = exactControlMapV2(setOf(
        "outerSchemaVersion", "contractId", "schemaSetVersion", "keySetVersion", "syncEpochId", "objectType",
        "objectId", "objectDigest", "mutationId", "writerDeviceId", "cipherSuite", "nonceBase64",
        "ciphertextBase64", "ciphertextDigest",
    ))
    return EncryptedWorkspaceObjectV2(
        map.intV2("outerSchemaVersion").toIntExactV2(), map.textV2("contractId"), map.textV2("schemaSetVersion"),
        map.textV2("keySetVersion"), map.textV2("syncEpochId"), map.textV2("objectType"), map.textV2("objectId"),
        map.textV2("objectDigest"), map.nullableTextV2("mutationId"), map.textV2("writerDeviceId"),
        map.textV2("cipherSuite"), map.textV2("nonceBase64"), map.textV2("ciphertextBase64"), map.textV2("ciphertextDigest"),
    )
}

private fun CborValueV2.exactControlMapV2(expected: Set<String>): Map<String, CborValueV2> {
    val entries = (this as? CborValueV2.Map)?.entries ?: error("Expected control map.")
    val mapped = entries.associate { (key, value) ->
        val name = (key as? CborValueV2.TextString)?.value ?: error("Control map key is not text.")
        name to value
    }
    require(mapped.size == entries.size && mapped.keys == expected)
    return mapped
}

private fun Map<String, CborValueV2>.textV2(key: String): String =
    (getValue(key) as? CborValueV2.TextString)?.value ?: error("Expected text $key.")

private fun Map<String, CborValueV2>.nullableTextV2(key: String): String? = when (val value = getValue(key)) {
    CborValueV2.Null -> null
    is CborValueV2.TextString -> value.value
    else -> error("Expected nullable text $key.")
}

private fun Map<String, CborValueV2>.intV2(key: String): Long =
    (getValue(key) as? CborValueV2.Integer)?.value ?: error("Expected integer $key.")

private fun Map<String, CborValueV2>.arrayV2(key: String): List<CborValueV2> =
    (getValue(key) as? CborValueV2.Array)?.values ?: error("Expected array $key.")

private fun CborValueV2.instantV2(): Instant {
    val values = (this as? CborValueV2.Array)?.values ?: error("Expected instant array.")
    require(values.size == 2)
    val seconds = (values[0] as? CborValueV2.Integer)?.value ?: error("Invalid instant seconds.")
    val nanos = (values[1] as? CborValueV2.Integer)?.value ?: error("Invalid instant nanos.")
    require(nanos in 0..999_999_999)
    return Instant.fromEpochSeconds(seconds, nanos)
}

private fun Long.toIntExactV2(): Int = toInt().also { require(it.toLong() == this) }
