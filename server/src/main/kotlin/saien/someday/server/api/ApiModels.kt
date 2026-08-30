package saien.someday.server.api

import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class LogoutRequest(
    val refreshToken: String? = null,
)

@Serializable
data class DeviceRegistrationRequest(
    val deviceId: String,
    val name: String,
    val platform: String,
)

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
)

@Serializable
data class AuthTokensResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: UserResponse,
)

@Serializable
data class MeResponse(
    val id: String,
    val email: String,
    val deviceId: String? = null,
    val scopes: List<String>,
)

@Serializable
data class DeviceResponse(
    val id: String,
    val name: String,
    val platform: String,
    val revoked: Boolean,
)

@Serializable
data class DeviceRegistrationResponse(
    val device: DeviceResponse,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

@Serializable
data class DevicesResponse(
    val devices: List<DeviceResponse>,
)

@Serializable
data class PairingInviteCreateRequest(
    val envelopeJson: String,
    val envelopeDigest: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class PairingInviteCreateResponse(
    val status: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class PairingInviteClaimRequest(
    val claimId: String,
)

@Serializable
data class PairingInviteClaimResponse(
    val envelopeJson: String,
    val envelopeDigest: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class PairingInviteCompleteRequest(
    val claimId: String,
)

@Serializable
data class WorkspaceRecoveryEnvelopePutRequest(
    val workspaceId: String,
    val keyFingerprint: String,
    val envelopeJson: String,
    val envelopeDigest: String,
    val expectedRevision: Long? = null,
) {
    override fun toString(): String =
        "WorkspaceRecoveryEnvelopePutRequest(workspaceId=$workspaceId, keyFingerprint=$keyFingerprint, " +
            "envelopeDigest=$envelopeDigest, expectedRevision=$expectedRevision, envelopeJson=<redacted>)"
}

@Serializable
data class WorkspaceRecoveryEnvelopeResponse(
    val workspaceId: String,
    val keyFingerprint: String,
    val envelopeJson: String,
    val envelopeDigest: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "WorkspaceRecoveryEnvelopeResponse(workspaceId=$workspaceId, keyFingerprint=$keyFingerprint, " +
            "envelopeDigest=$envelopeDigest, revision=$revision, updatedAtEpochMillis=$updatedAtEpochMillis, " +
            "envelopeJson=<redacted>)"
}

@Serializable
data class SyncV2ObjectPayload(
    val outerSchemaVersion: Int = 1,
    val contractId: String = "someday-system-v2",
    val schemaSetVersion: String = "workspace-entity-schema-set-v2",
    val keySetVersion: String = "sync-key-set-v2",
    val syncEpochId: String,
    val objectType: String,
    val objectId: String,
    val objectDigest: String,
    val mutationId: String? = null,
    val writerDeviceId: String,
    val cipherSuite: String = "xchacha20-poly1305-ietf",
    val nonceBase64: String,
    val ciphertextBase64: String,
    val ciphertextDigest: String,
) {
    init {
        require(outerSchemaVersion == 1)
        require(contractId == "someday-system-v2")
        require(schemaSetVersion == "workspace-entity-schema-set-v2")
        require(keySetVersion == "sync-key-set-v2")
        require(syncEpochId.isNotBlank())
        require(objectType.isNotBlank())
        require(objectId.isNotBlank())
        require(objectDigest.isNotBlank())
        require(writerDeviceId.isNotBlank())
        require(cipherSuite == "xchacha20-poly1305-ietf")
        require(nonceBase64.isNotBlank())
        require(ciphertextBase64.isNotBlank())
        require(ciphertextDigest.isNotBlank())
    }
}

@Serializable
data class SyncV2CapabilitiesResponse(
    val profile: String = "self-hosted-v2",
    val contractId: String = "someday-system-v2",
    val semanticProtocolVersion: Int = 2,
    val schemaSetVersion: String = "workspace-entity-schema-set-v2",
    val keySetVersion: String = "sync-key-set-v2",
    val metadataPrivacyMode: String = "opaque",
    val maxPushObjects: Int = 100,
    val maxPullUnits: Int = 500,
    val maxEncodedBodyBytes: Int = 16 * 1024 * 1024,
    val supportsCheckpoints: Boolean = true,
    val parentIndexedMetadata: Boolean = false,
)

@Serializable
data class SyncV2EpochMetadata(
    val contractId: String = "someday-system-v2",
    val schemaSetVersion: String = "workspace-entity-schema-set-v2",
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
data class SyncV2EpochResponse(
    val metadata: SyncV2EpochMetadata? = null,
    val pointer: SyncV2ObjectPayload? = null,
)

@Serializable
data class SyncV2EpochCompareAndSetRequest(
    val expectedCurrentDigest: String? = null,
    val metadata: SyncV2EpochMetadata,
    val pointer: SyncV2ObjectPayload,
)

@Serializable
data class SyncV2EpochCompareAndSetResponse(
    val published: Boolean,
    val idempotentReplay: Boolean = false,
    val current: SyncV2EpochResponse? = null,
    val error: String? = null,
)

@Serializable
data class SyncV2CheckpointChunkRequest(
    val epochId: String,
    val checkpointId: String,
    val ref: SyncV2CheckpointChunkRef,
    val objectValue: SyncV2ObjectPayload,
)

@Serializable
data class SyncV2CheckpointChunkRef(
    val chunkIndex: Int,
    val chunkId: String,
    val chunkDigest: String,
    val objectCount: Int,
    val plaintextBytes: Int,
)

@Serializable
data class SyncV2CheckpointManifestRequest(
    val epochId: String,
    val checkpointId: String,
    val checkpointDigest: String,
    val chunks: List<SyncV2CheckpointChunkRef>,
    val totalObjectCount: Int,
    val objectValue: SyncV2ObjectPayload,
)

@Serializable
data class SyncV2ImmutablePutResponse(
    val stored: Boolean,
    val idempotentReplay: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SyncV2CheckpointFetchRequest(
    val epochId: String,
    val checkpointId: String,
    val chunkIndex: Int? = null,
)

@Serializable
data class SyncV2CheckpointFetchResponse(
    val manifest: SyncV2ObjectPayload? = null,
    val chunk: SyncV2ObjectPayload? = null,
)

@Serializable
data class SyncV2CheckpointCleanupRequest(
    val epochId: String,
    val checkpointId: String,
    val checkpointDigest: String,
    val previousPointerDigest: String? = null,
    val chunks: List<SyncV2CheckpointChunkRef>,
)

@Serializable
data class SyncV2CheckpointCleanupResponse(
    val deleted: Boolean,
    val alreadyAbsent: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SyncV2PushRequest(
    val epochId: String,
    val writerProtocolVersion: Int,
    val objects: List<SyncV2ObjectPayload>,
)

@Serializable
data class SyncV2MutationAckResponse(
    val mutationId: String,
    val objectId: String,
    val objectDigest: String,
    val idempotentReplay: Boolean,
)

@Serializable
data class SyncV2PushResponse(
    val accepted: Boolean,
    val acknowledgements: List<SyncV2MutationAckResponse> = emptyList(),
    val error: String? = null,
)

@Serializable
data class SyncV2PullRequest(
    val epochId: String,
    val afterCursor: Long? = null,
    val limit: Int = 100,
)

@Serializable
data class SyncV2CursorUnitResponse(
    val epochId: String,
    val streamId: String = "global",
    val expectedCursorValue: String? = null,
    val nextCursorValue: String,
    val unitId: String,
    val unitDigest: String,
    val objects: List<SyncV2ObjectPayload>,
)

@Serializable
data class SyncV2PullResponse(
    val units: List<SyncV2CursorUnitResponse>,
    val complete: Boolean,
    val error: String? = null,
)

@Serializable
data class SyncV2FrontierRequest(val epochId: String)

@Serializable
data class SyncV2StreamFrontier(
    val streamId: String,
    val cursorValue: String? = null,
    val streamDigest: String,
)

@Serializable
data class SyncV2FrontierResponse(val frontiers: List<SyncV2StreamFrontier>)

@Serializable
data class SyncV2StatusResponse(
    val status: String,
    val activeEpochId: String? = null,
    val cursor: Long = 0,
    val immutableObjects: Long = 0,
    val deviceId: String,
)

@Serializable
data class StatusResponse(
    val status: String,
)

@Serializable
data class ErrorResponse(
    val error: String,
)
