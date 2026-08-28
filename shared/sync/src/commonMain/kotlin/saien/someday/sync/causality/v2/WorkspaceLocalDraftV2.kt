package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.settings.ClientSettingsRepository

/** Ensures the one local generation exists before any product repository is exposed. */
fun ensureWorkspaceLocalDraftV2(
    localRepository: SqlDelightLocalDataRepository,
    settingsRepository: ClientSettingsRepository,
    workspaceKey: WorkspaceMasterKey,
): StoredSyncEpochV2 {
    val protocol = SqlDelightSyncProtocolStoreV2(localRepository.database)
    protocol.loadAuthoritativeEpoch()?.let { return it }
    protocol.loadAllEpochs().singleOrNull {
        it.remoteProfile == SyncRemoteProfileV2.SELF_HOSTED.wireValue &&
            it.lifecycle == SyncEpochLifecycleV2.PREPARING &&
            it.health == SyncEpochHealthV2.HEALTHY
    }?.let { return it }
    check(protocol.loadAllEpochs().none { it.lifecycle == SyncEpochLifecycleV2.PREPARING }) {
        "The local workspace has multiple incompatible draft generations."
    }
    val writerDeviceId = normalizeWriterDeviceIdV2(localRepository.localDeviceId)
    val prepared = WorkspaceGenesisCheckpointServiceV2(
        settingsRepository = settingsRepository,
        workspaceKey = workspaceKey,
        writerDeviceId = writerDeviceId,
        remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
    ).prepare()
    val checkpoint = when (prepared) {
        is WorkspaceGenesisCheckpointResultV2.Prepared -> prepared.checkpoint
        is WorkspaceGenesisCheckpointResultV2.Blocked -> error(prepared.safeMessage)
    }
    when (val persisted = WorkspaceCheckpointPersistenceV2(
        localRepository = localRepository,
        workspaceKey = workspaceKey,
        writerDeviceId = writerDeviceId,
        protocolStore = protocol,
    ).persist(checkpoint)) {
        is WorkspaceCheckpointPersistResultV2.Ready -> return persisted.epoch
        is WorkspaceCheckpointPersistResultV2.Rejected -> error(persisted.safeMessage)
    }
}
