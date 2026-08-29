package saien.someday.sync.causality.v2

import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.resetWorkspaceStateForReplacement

/**
 * Clears the current workspace's local state inside the caller's replacement
 * transaction. Device identity, authentication, language, and notification
 * preferences remain local to this installation.
 */
internal fun discardLocalWorkspaceForReplacementV2(
    localRepository: SqlDelightLocalDataRepository,
    settingsRepository: ClientSettingsRepository,
): Boolean {
    val protocol = SqlDelightSyncProtocolStoreV2(localRepository.database)
    WorkspaceCheckpointCleanupServiceV2(localRepository, protocol)
        .discardAllForWorkspaceReplacement()
    localRepository.database.somedayQueries.deleteAllMediaAssets()

    val current = settingsRepository.load()
    settingsRepository.saveLocalSnapshot(current.resetWorkspaceStateForReplacement())
    return protocol.loadAllEpochs().isEmpty() &&
        protocol.loadLocalAuthority() == null &&
        localRepository.database.somedayQueries.selectAllMediaAssets().executeAsList().isEmpty()
}
