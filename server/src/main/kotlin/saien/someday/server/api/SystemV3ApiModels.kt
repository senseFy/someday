package saien.someday.server.api

import kotlinx.serialization.Serializable

@Serializable
data class SystemV3CapabilitiesResponse(
    val contractId: String = "someday-system-v3",
    val semanticProtocolVersion: Int = 3,
    val authorityMode: String = "self-hosted",
    val entityDag: SystemV3EntityDagCapabilities = SystemV3EntityDagCapabilities(),
    val media: SystemV3MediaCapabilities = SystemV3MediaCapabilities(),
)

@Serializable
data class SystemV3EntityDagCapabilities(
    val subsystemContractId: String = "someday-system-v2",
    val apiBasePath: String = "/sync/v3/workspaces/{workspaceId}/entities",
    val schemaSetVersion: String = "workspace-entity-schema-set-v2",
    val encrypted: Boolean = true,
    val semanticProtocolVersion: Int = 2,
    val keySetVersion: String = "sync-key-set-v2",
    val metadataPrivacyMode: String = "opaque",
    val maxPushObjects: Int = 100,
    val maxPullUnits: Int = 500,
    val maxEncodedBodyBytes: Int = 16 * 1024 * 1024,
    val supportsCheckpoints: Boolean = true,
    val parentIndexedMetadata: Boolean = false,
)

@Serializable
data class SystemV3MediaCapabilities(
    val subsystemContractId: String = "someday-system-v3-media-v1",
    val apiBasePath: String = "/sync/v3/workspaces/{workspaceId}/media",
    val objectSchemaVersion: Int = 1,
    val cipherSuite: String = "xchacha20-poly1305-ietf",
    val ciphertextMode: String = "deterministic-single-object-v1",
    val maxPlaintextBytes: Int = 4 * 1024 * 1024,
    val maxCiphertextBytes: Int = 4 * 1024 * 1024 + 4 * 1024 + 4 + 40,
    val supportsHead: Boolean = true,
    val immutablePut: Boolean = true,
)

@Serializable
data class SystemV3MediaPutResponse(
    val stored: Boolean,
    val idempotentReplay: Boolean = false,
    val error: String? = null,
)
