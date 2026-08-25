package saien.someday.sync.causality.v2

import saien.someday.data.local.SqlDelightLocalDataRepository

/** First-generation CAS-loser hygiene; not retained-generation lifecycle management. */
internal class WorkspaceCheckpointDraftCleanupServiceV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val protocolStore: SqlDelightSyncProtocolStoreV2,
) {
    private val queries = localRepository.database.somedayQueries

    fun collect(remoteProfile: String, epochId: String): Boolean {
        require(remoteProfile == SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        var collected = false
        localRepository.database.transaction {
            val epoch = protocolStore.loadEpoch(remoteProfile, epochId) ?: return@transaction
            if (epoch.lifecycle != SyncEpochLifecycleV2.ABANDONED) return@transaction
            if (epoch.activatedAtEpochMilliseconds != null) return@transaction
            val pins = queries.selectDraftCleanupPinCountsSystemV2(remoteProfile, epochId).executeAsOne()
            val totalPins = pins.pending_mutations + pins.unresolved_dead_letters +
                pins.unpublished_imports + pins.local_authority_pins
            if (totalPins > 0) return@transaction

            queries.deleteEpochCheckpointObjectsSystemV2(remoteProfile, epochId)
            queries.deleteEpochCheckpointsSystemV2(remoteProfile, epochId)
            queries.deleteEpochPendingMutationsSystemV2(remoteProfile, epochId)
            queries.deleteEpochAppliedMutationsSystemV2(remoteProfile, epochId)
            queries.deleteEpochRemoteCursorsSystemV2(remoteProfile, epochId)
            queries.deleteEpochSourceImportsSystemV2(remoteProfile, epochId)
            queries.deleteEpochProjectionWarningsSystemV2(epochId)
            queries.deleteEpochPreferencesProjectionSystemV2(epochId)
            queries.deleteEpochNoteProjectionsSystemV2(epochId)
            queries.deleteEpochNotebookProjectionsSystemV2(epochId)
            queries.deleteEpochConflictHeadsSystemV2(epochId)
            queries.deleteEpochConflictsSystemV2(epochId)
            queries.deleteEpochHeadsSystemV2(epochId)
            queries.deleteEpochParentsSystemV2(epochId)
            queries.deleteEpochVersionsSystemV2(epochId)
            queries.deleteEpochControlObjectsSystemV2(remoteProfile, epochId)
            queries.deleteEpochDeadLettersSystemV2(remoteProfile, epochId)
            queries.deleteEpochRunHistorySystemV2(remoteProfile, epochId)
            queries.deleteSyncEpochAnyLifecycleSystemV2(remoteProfile, epochId)
            check(protocolStore.loadEpoch(remoteProfile, epochId) == null)
            collected = true
        }
        return collected
    }

    fun discardEmptyUnboundDraft(remoteProfile: String, epochId: String): Boolean {
        require(remoteProfile == SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        var discarded = false
        localRepository.database.transaction {
            val epoch = protocolStore.loadEpoch(remoteProfile, epochId) ?: return@transaction
            if (epoch.lifecycle != SyncEpochLifecycleV2.PREPARING ||
                epoch.health != SyncEpochHealthV2.HEALTHY ||
                epoch.authorityBindingId != null ||
                protocolStore.loadLocalAuthority() != null
            ) return@transaction

            deleteEpoch(remoteProfile, epochId)
            discarded = true
        }
        return discarded
    }

    private fun deleteEpoch(remoteProfile: String, epochId: String) {
        queries.deleteEpochCheckpointObjectsSystemV2(remoteProfile, epochId)
        queries.deleteEpochCheckpointsSystemV2(remoteProfile, epochId)
        queries.deleteEpochPendingMutationsSystemV2(remoteProfile, epochId)
        queries.deleteEpochAppliedMutationsSystemV2(remoteProfile, epochId)
        queries.deleteEpochRemoteCursorsSystemV2(remoteProfile, epochId)
        queries.deleteEpochSourceImportsSystemV2(remoteProfile, epochId)
        queries.deleteEpochProjectionWarningsSystemV2(epochId)
        queries.deleteEpochPreferencesProjectionSystemV2(epochId)
        queries.deleteEpochNoteProjectionsSystemV2(epochId)
        queries.deleteEpochNotebookProjectionsSystemV2(epochId)
        queries.deleteEpochConflictHeadsSystemV2(epochId)
        queries.deleteEpochConflictsSystemV2(epochId)
        queries.deleteEpochHeadsSystemV2(epochId)
        queries.deleteEpochParentsSystemV2(epochId)
        queries.deleteEpochVersionsSystemV2(epochId)
        queries.deleteEpochControlObjectsSystemV2(remoteProfile, epochId)
        queries.deleteEpochDeadLettersSystemV2(remoteProfile, epochId)
        queries.deleteEpochRunHistorySystemV2(remoteProfile, epochId)
        queries.deleteSyncEpochAnyLifecycleSystemV2(remoteProfile, epochId)
        check(protocolStore.loadEpoch(remoteProfile, epochId) == null)
    }
}
