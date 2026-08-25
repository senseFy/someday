@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.server.support

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.server.api.SyncV2CheckpointChunkRef
import saien.someday.server.api.SyncV2CheckpointCleanupRequest
import saien.someday.server.api.SyncV2ObjectPayload
import saien.someday.sync.causality.v2.CanonicalWorkspaceCausalityMaterializerV2
import saien.someday.sync.causality.v2.EncryptedWorkspaceObjectDecodeResultV2
import saien.someday.sync.causality.v2.EncryptedWorkspaceObjectV2
import saien.someday.sync.causality.v2.NotebookContentV2
import saien.someday.sync.causality.v2.PreparedWorkspaceEpochCheckpointV2
import saien.someday.sync.causality.v2.SyncEpochKeyDerivationV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointBuilderV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointSourceHeadV2
import saien.someday.sync.causality.v2.WorkspaceEntityTypeV2
import saien.someday.sync.causality.v2.WorkspaceEntityValidatorV2
import saien.someday.sync.causality.v2.WorkspaceEntityVersionFactoryV2
import saien.someday.sync.causality.v2.WorkspaceEntityWireCodecV2
import saien.someday.sync.causality.v2.WorkspaceObjectCipherV2
import saien.someday.sync.causality.v2.WorkspacePreferencesV2
import kotlin.time.Instant

internal val SYNC_V2_HTTP_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

internal fun checkpoint(
    writerDeviceId: String,
    previous: PreparedWorkspaceEpochCheckpointV2?,
    previousEpochId: String? = previous?.descriptor?.syncEpochId,
    previousEpochPointerDigest: String? = previous?.pointerObject?.objectDigest,
): PreparedWorkspaceEpochCheckpointV2 = WorkspaceCheckpointBuilderV2(
    WORKSPACE_KEY,
    writerDeviceId,
).build(
    remoteProfile = "self-hosted-v2",
    sourceHeads = listOf(
        WorkspaceCheckpointSourceHeadV2(
            WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            "workspace-preferences",
            WorkspacePreferencesV2(),
            null,
            "integration-test",
            previous?.descriptor?.syncEpochId,
            writerDeviceId,
            null,
            "source-${previous?.descriptor?.syncEpochId ?: "genesis"}",
            "source-digest-${previous?.descriptor?.syncEpochId ?: "genesis"}",
        ),
    ),
    createdAt = NOW,
    previousPointerDigest = previous?.pointerObject?.objectDigest,
    previousEpochId = previousEpochId,
    previousEpochPointerDigest = previousEpochPointerDigest,
)

internal fun entityObject(
    checkpoint: PreparedWorkspaceEpochCheckpointV2,
    writerDeviceId: String,
    entityId: String,
    title: String,
): SyncV2ObjectPayload {
    val epochId = checkpoint.descriptor.syncEpochId
    val materializer = CanonicalWorkspaceCausalityMaterializerV2(
        SyncEpochKeyDerivationV2().derive(WORKSPACE_KEY, epochId),
    )
    val validator = WorkspaceEntityValidatorV2(materializer)
    val wire = WorkspaceEntityWireCodecV2(materializer, validator)
    val factory = WorkspaceEntityVersionFactoryV2(epochId, materializer)
    val version = factory.createGenesis(
        WorkspaceEntityTypeV2.NOTEBOOK,
        entityId,
        NotebookContentV2(title, 1, NOW),
        "device:$writerDeviceId",
        NOW,
    )
    return WorkspaceObjectCipherV2(WORKSPACE_KEY, materializer)
        .encryptEntity(version, factory.newMutationId(), writerDeviceId, wire.encode(version))
        .toServer()
}

internal fun reencrypt(
    original: SyncV2ObjectPayload,
    writerDeviceId: String,
    checkpoint: PreparedWorkspaceEpochCheckpointV2,
): SyncV2ObjectPayload {
    val materializer = CanonicalWorkspaceCausalityMaterializerV2(
        SyncEpochKeyDerivationV2().derive(WORKSPACE_KEY, checkpoint.descriptor.syncEpochId),
    )
    val cipher = WorkspaceObjectCipherV2(WORKSPACE_KEY, materializer)
    val shared = SYNC_V2_HTTP_JSON.decodeFromString<EncryptedWorkspaceObjectV2>(
        SYNC_V2_HTTP_JSON.encodeToString(original),
    )
    val plaintext = when (val decoded = cipher.decrypt(shared)) {
        is EncryptedWorkspaceObjectDecodeResultV2.Decoded -> decoded.plaintext
        is EncryptedWorkspaceObjectDecodeResultV2.Rejected -> error(decoded.error.safeMessage)
    }
    return cipher.reencryptReplica(shared, writerDeviceId, plaintext).toServer()
}

internal fun EncryptedWorkspaceObjectV2.toServer(): SyncV2ObjectPayload =
    SYNC_V2_HTTP_JSON.decodeFromString(SYNC_V2_HTTP_JSON.encodeToString(this))

internal fun PreparedWorkspaceEpochCheckpointV2.toCleanupRequest() =
    SyncV2CheckpointCleanupRequest(
        epochId = descriptor.syncEpochId,
        checkpointId = descriptor.checkpointId,
        checkpointDigest = descriptor.checkpointDigest,
        previousPointerDigest = pointer.previousPointerDigest,
        chunks = chunks.map { chunk ->
            chunk.ref.let {
                SyncV2CheckpointChunkRef(
                    it.chunkIndex,
                    it.chunkId,
                    it.chunkDigest,
                    it.objectCount,
                    it.plaintextBytes,
                )
            }
        },
    )

internal const val WORKSPACE_ID = "workspace-00000000000000000000000000000001"
internal const val OTHER_WORKSPACE_ID = "workspace-00000000000000000000000000000002"
internal const val NOTEBOOK_ID = "00000000-0000-4000-8000-000000000111"
internal const val OTHER_OBJECT_ID = "00000000-0000-4000-8000-000000000222"
internal const val EXTERNAL_SOURCE_EPOCH_ID = "00000000-0000-4000-8000-000000000999"
internal const val EXTERNAL_SOURCE_POINTER_DIGEST =
    "cd2:hmac-sha256:abababababababababababababababababababababababababababababababab"
internal val NOW = Instant.parse("2026-07-19T00:00:00Z")
internal val WORKSPACE_KEY = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 41).toByte() })
