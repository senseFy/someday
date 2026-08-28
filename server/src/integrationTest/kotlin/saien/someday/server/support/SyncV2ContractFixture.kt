package saien.someday.server.support

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import saien.someday.server.persistence.SyncV2CheckpointChunkInput
import saien.someday.server.persistence.SyncV2CheckpointChunkRefRecord
import saien.someday.server.persistence.SyncV2CheckpointManifestInput
import saien.someday.server.persistence.SyncV2EpochMetadataRecord
import saien.someday.server.persistence.SyncV2ImmutablePutRepositoryResult
import saien.someday.server.persistence.SyncV2ObjectInput
import saien.someday.server.persistence.SyncV2PointerPublishRepositoryResult
import saien.someday.server.persistence.SyncV2Repository

/**
 * Canonical identities used to arrange repository contracts. Ciphertext JSON
 * stays opaque here; strict wire validation belongs to the HTTP contract layer.
 */
internal object SyncV2ContractFixture {
    fun genesis(label: String): GenesisCandidate {
        val epochId = uuid("epoch:$label")
        val checkpointId = uuid("checkpoint:$label")
        val chunk = SyncV2CheckpointChunkInput(
            epochId = epochId,
            checkpointId = checkpointId,
            ref = SyncV2CheckpointChunkRefRecord(
                chunkIndex = 0,
                chunkId = uuid("chunk:$label"),
                chunkDigest = controlDigest("chunk:$label"),
                objectCount = 1,
                plaintextBytes = 1,
            ),
            encryptedObjectJson = """{"checkpointChunk":"$label"}""",
        )
        val metadata = SyncV2EpochMetadataRecord(
            contractId = "someday-system-v2",
            schemaSetVersion = "workspace-entity-schema-set-v2",
            epochId = epochId,
            pointerDigest = controlDigest("pointer:$label"),
            semanticProtocolVersion = 2,
            minimumWriterProtocolVersion = 2,
            keySetVersion = "sync-key-set-v2",
            remoteProfile = "self-hosted-v2",
            metadataPrivacyMode = "opaque",
            supportedOfflineWindowSeconds = 15_552_000L,
            checkpointId = checkpointId,
            checkpointDigest = controlDigest("checkpoint:$label"),
            previousEpochId = null,
            previousEpochPointerDigest = null,
        )
        return GenesisCandidate(
            metadata = metadata,
            chunk = chunk,
            manifest = SyncV2CheckpointManifestInput(
                epochId = epochId,
                checkpointId = checkpointId,
                checkpointDigest = metadata.checkpointDigest,
                chunks = listOf(chunk.ref),
                totalObjectCount = 1,
                encryptedObjectJson = """{"checkpointManifest":"$label"}""",
            ),
            pointerObjectJson = """{"epochPointer":"$label"}""",
        )
    }

    fun prepareGenesis(
        repository: SyncV2Repository,
        identity: TestServerIdentity,
        workspaceId: String,
        candidate: GenesisCandidate,
    ) {
        check(
            repository.putCheckpointChunk(
                identity.userId,
                workspaceId,
                candidate.chunk,
            ) is SyncV2ImmutablePutRepositoryResult.Stored,
        )
        check(
            repository.putCheckpointManifest(
                identity.userId,
                workspaceId,
                candidate.manifest,
            ) is SyncV2ImmutablePutRepositoryResult.Stored,
        )
    }

    fun initializeWorkspace(
        repository: SyncV2Repository,
        identity: TestServerIdentity,
        workspaceId: String,
        label: String = "active",
    ): GenesisCandidate = genesis(label).also { candidate ->
        prepareGenesis(repository, identity, workspaceId, candidate)
        check(
            repository.compareAndSetEpoch(
                identity.userId,
                workspaceId,
                expectedCurrentDigest = null,
                metadata = candidate.metadata,
                pointerObjectJson = candidate.pointerObjectJson,
            ) is SyncV2PointerPublishRepositoryResult.Published,
        )
    }

    fun entity(
        candidate: GenesisCandidate,
        deviceId: UUID,
        label: String,
        objectIdentity: String = label,
        mutationIdentity: String = label,
    ): SyncV2ObjectInput = SyncV2ObjectInput(
        epochId = candidate.metadata.epochId,
        objectId = uuid("object:$objectIdentity"),
        objectType = "workspace_entity_version_v2",
        objectDigest = objectDigest("object:$label"),
        mutationId = uuid("mutation:$mutationIdentity"),
        writerDeviceId = deviceId,
        ciphertextDigest = ciphertextDigest("ciphertext:$label"),
        encodedObjectJson = """{"encryptedEntity":"$label"}""",
    )

    private fun uuid(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    private fun controlDigest(value: String): String = "cd2:hmac-sha256:${sha256Hex(value)}"

    private fun objectDigest(value: String): String = "od2:hmac-sha256:${sha256Hex(value)}"

    private fun ciphertextDigest(value: String): String = "ct2:sha256:${sha256Hex(value)}"

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal data class GenesisCandidate(
    val metadata: SyncV2EpochMetadataRecord,
    val chunk: SyncV2CheckpointChunkInput,
    val manifest: SyncV2CheckpointManifestInput,
    val pointerObjectJson: String,
)
