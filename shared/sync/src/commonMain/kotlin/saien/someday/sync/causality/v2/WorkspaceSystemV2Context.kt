package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository

/**
 * Opens the one semantic V2 service used by product repositories and the sync
 * coordinator.  Keeping construction here prevents a platform or transport
 * from quietly selecting a note-only engine, a different key schedule, or a
 * projection-side mutation path.
 */
class WorkspaceSystemV2ContextProvider(
    private val localRepository: SqlDelightLocalDataRepository,
    private val workspaceKeyProvider: () -> WorkspaceMasterKey?,
    private val writerDeviceIdProvider: () -> String,
    private val remoteProfileProvider: () -> String,
) {
    private val protocolStore = SqlDelightSyncProtocolStoreV2(localRepository.database)

    fun openOrNull(): ActiveWorkspaceSystemV2? {
        val workspaceKey = workspaceKeyProvider() ?: return null
        val configuredProfile = remoteProfileProvider().takeIf(String::isNotBlank)
        // SyncMode.Off and an uncommitted provider settings change are network
        // choices, not authority changes. Local mutations must keep targeting
        // the one authenticated active/blocked epoch until an explicit profile
        // migration commits another pointer.
        val epoch = protocolStore.loadAuthoritativeEpoch()
            ?: configuredProfile?.let(protocolStore::loadActiveEpoch)
            ?: return null
        return openExactEpoch(epoch, workspaceKey)
    }

    /**
     * Opens an exact retained epoch for read-only history and undelete.
     *
     * This deliberately takes the already-resolved epoch and its exact key;
     * it never substitutes the current authority or guesses after key
     * rotation. Product callers use it only for locally retained read-only
     * epochs, while all mutations still go through [requireActive].
     */
    fun openRetainedEpochOrNull(
        epoch: StoredSyncEpochV2,
        workspaceKey: WorkspaceMasterKey,
    ): ActiveWorkspaceSystemV2? {
        if (epoch.lifecycle != SyncEpochLifecycleV2.READ_ONLY) return null
        return openExactEpoch(epoch, workspaceKey)
    }

    private fun openExactEpoch(
        epoch: StoredSyncEpochV2,
        workspaceKey: WorkspaceMasterKey,
    ): ActiveWorkspaceSystemV2 {
        val remoteProfile = epoch.remoteProfile
        val descriptor = epoch.descriptor
        require(descriptor.contractId == SYNC_V2_CONTRACT_ID) { "Active epoch uses another V2 contract." }
        require(descriptor.schemaSetVersion == SYNC_V2_SCHEMA_SET_VERSION) { "Active epoch uses another V2 schema set." }
        require(descriptor.keySetVersion == SYNC_KEY_SET_VERSION_V2) { "Active epoch uses another V2 key set." }
        require(descriptor.remoteProfile == remoteProfile) { "Active epoch remote profile does not match this client." }

        val localAuthority = protocolStore.loadLocalAuthority()?.takeIf {
            it.remoteProfile == remoteProfile && it.epochId == descriptor.syncEpochId
        }
        if (localAuthority != null) {
            require(localAuthority.pointerDigest == epoch.descriptorDigest) {
                "Local V2 authority binding does not match the authenticated pointer."
            }
        }
        // A retained epoch no longer owns the singleton authority binding,
        // but the binding still carries this installation's durable writer
        // identity. Reusing it keeps read-only archive access working while
        // network mode is Off without inventing an epoch-specific actor.
        val durableWriterDeviceId = localAuthority?.localWriterDeviceId
            ?: protocolStore.loadLocalAuthority()?.localWriterDeviceId
        val writerDeviceId = normalizeWriterDeviceIdV2(
            durableWriterDeviceId ?: writerDeviceIdProvider(),
        )
        val keys = SyncEpochKeyDerivationV2().derive(
            workspaceKey = workspaceKey,
            syncEpochId = descriptor.syncEpochId,
            keySetVersion = descriptor.keySetVersion,
        )
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(keys)
        val validator = WorkspaceEntityValidatorV2(materializer)
        val wireCodec = WorkspaceEntityWireCodecV2(materializer, validator)
        val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
        val outboxEncoder = WorkspaceOutboxEncoderV2 { version, mutationId ->
            val outer = cipher.encryptEntity(
                version = version,
                mutationId = mutationId,
                writerDeviceId = writerDeviceId,
                plaintext = wireCodec.encode(version),
            )
            PreparedWorkspaceOutboxObjectV2(
                writerDeviceId = writerDeviceId,
                encodedOuter = cipher.encodeJson(outer),
            )
        }
        val store = SqlDelightWorkspaceEntityStoreV2(
            database = localRepository.database,
            syncEpochId = descriptor.syncEpochId,
            engine = WorkspaceEntityCausalityEngineV2(materializer, validator),
            materializer = materializer,
            wireCodec = wireCodec,
            outboxEncoder = outboxEncoder,
        )
        return ActiveWorkspaceSystemV2(
            remoteProfile = remoteProfile,
            writerDeviceId = writerDeviceId,
            syncEpochId = descriptor.syncEpochId,
            descriptor = descriptor,
            lifecycle = epoch.lifecycle,
            health = epoch.health,
            store = store,
            factory = WorkspaceEntityVersionFactoryV2(descriptor.syncEpochId, materializer),
            materializer = materializer,
            wireCodec = wireCodec,
            cipher = cipher,
        )
    }

    fun requireActive(): ActiveWorkspaceSystemV2 {
        val context = checkNotNull(openOrNull()) {
            "Sync V2 is selected but this device has no authoritative authenticated whole-product epoch."
        }
        check(context.lifecycle == SyncEpochLifecycleV2.ACTIVE && context.health == SyncEpochHealthV2.HEALTHY) {
            "Sync V2 is read-only until the authoritative epoch integrity blocker is repaired."
        }
        return context
    }
}

data class ActiveWorkspaceSystemV2(
    val remoteProfile: String,
    val writerDeviceId: String,
    val syncEpochId: String,
    val descriptor: SyncEpochDescriptorV2,
    val lifecycle: SyncEpochLifecycleV2,
    val health: SyncEpochHealthV2,
    val store: SqlDelightWorkspaceEntityStoreV2,
    val factory: WorkspaceEntityVersionFactoryV2,
    val materializer: CanonicalWorkspaceCausalityMaterializerV2,
    val wireCodec: WorkspaceEntityWireCodecV2,
    val cipher: WorkspaceObjectCipherV2,
) {
    val deviceActorId: String = "device:$writerDeviceId"
}
