@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import kotlin.time.Instant
import kotlinx.serialization.Serializable

const val SEMANTIC_SYNC_PROTOCOL_VERSION_V2: Int = 2
const val MINIMUM_WRITER_VERSION_V2: Int = 2
const val SYNC_EPOCH_DESCRIPTOR_SCHEMA_V2: Int = 1
const val SUPPORTED_OFFLINE_WINDOW_SECONDS_V2: Long = 180L * 24L * 60L * 60L

const val SYNC_EPOCH_POINTER_OBJECT_TYPE_V2: String = "sync_epoch_pointer_v2"
const val SYNC_CHECKPOINT_MANIFEST_OBJECT_TYPE_V2: String = "sync_checkpoint_manifest_v2"
const val SYNC_CHECKPOINT_CHUNK_OBJECT_TYPE_V2: String = "sync_checkpoint_chunk_v2"

enum class SyncRemoteProfileV2(val wireValue: String) {
    SELF_HOSTED("self-hosted-v2"),
}

enum class SyncMetadataPrivacyModeV2(val wireValue: String) {
    OPAQUE("opaque"),
}

/** Frozen lineage wire field; first-release clients reject descriptors containing one. */
@Serializable
data class SyncStreamFrontierV2(
    val streamId: String,
    val cursorValue: String?,
    val streamDigest: String,
) {
    init {
        require(streamId.isValidProtocolIdentifierV2())
        require(cursorValue == null || cursorValue.isValidProtocolIdentifierV2())
        require(streamDigest.isValidProtocolIdentifierV2())
    }
}

@Serializable
data class SyncEpochDescriptorV2(
    val schemaVersion: Int = SYNC_EPOCH_DESCRIPTOR_SCHEMA_V2,
    val contractId: String = SYNC_V2_CONTRACT_ID,
    val schemaSetVersion: String = SYNC_V2_SCHEMA_SET_VERSION,
    val syncEpochId: String,
    val semanticProtocolVersion: Int = SEMANTIC_SYNC_PROTOCOL_VERSION_V2,
    val minimumWriterProtocolVersion: Int = MINIMUM_WRITER_VERSION_V2,
    val keySetVersion: String = SYNC_KEY_SET_VERSION_V2,
    val remoteProfile: String,
    val supportedOfflineWindowSeconds: Long = SUPPORTED_OFFLINE_WINDOW_SECONDS_V2,
    val metadataPrivacyMode: String = SyncMetadataPrivacyModeV2.OPAQUE.wireValue,
    val checkpointId: String,
    val checkpointDigest: String,
    val previousEpochId: String? = null,
    /** Frozen lineage wire field; first-release clients require it to be null. */
    val previousEpochPointerDigest: String? = null,
    val createdByDeviceId: String,
    val createdAt: Instant,
    val previousEpochFrontiers: List<SyncStreamFrontierV2> = emptyList(),
) {
    init {
        require(schemaVersion == SYNC_EPOCH_DESCRIPTOR_SCHEMA_V2)
        require(contractId == SYNC_V2_CONTRACT_ID)
        require(schemaSetVersion == SYNC_V2_SCHEMA_SET_VERSION)
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(syncEpochId))
        require(semanticProtocolVersion == SEMANTIC_SYNC_PROTOCOL_VERSION_V2)
        require(minimumWriterProtocolVersion >= MINIMUM_WRITER_VERSION_V2)
        require(keySetVersion == SYNC_KEY_SET_VERSION_V2)
        require(remoteProfile == SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        require(supportedOfflineWindowSeconds >= SUPPORTED_OFFLINE_WINDOW_SECONDS_V2)
        require(metadataPrivacyMode == SyncMetadataPrivacyModeV2.OPAQUE.wireValue)
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(checkpointId))
        require(CONTROL_DIGEST_PATTERN_SYSTEM_V2.matches(checkpointDigest))
        require(previousEpochId == null || UUID_V4_PATTERN_SYSTEM_V2.matches(previousEpochId))
        require(previousEpochPointerDigest == null || CONTROL_DIGEST_PATTERN_SYSTEM_V2.matches(previousEpochPointerDigest))
        require((previousEpochId == null) == (previousEpochPointerDigest == null))
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(createdByDeviceId))
        require(previousEpochFrontiers == previousEpochFrontiers.sortedBy { it.streamId })
        require(previousEpochFrontiers.map { it.streamId }.distinct().size == previousEpochFrontiers.size)
        require(previousEpochId != null || previousEpochFrontiers.isEmpty())
    }
}
