package saien.someday.sync.causality.v2

import saien.someday.data.local.SqlDelightLocalDataRepository

/** Local protocol cleanup for safe CAS-loser collection and explicit workspace replacement. */
internal class WorkspaceCheckpointCleanupServiceV2(
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

            deleteEpoch(remoteProfile, epochId)
            collected = true
        }
        return collected
    }

    /**
     * Deletes every local protocol generation after explicit user approval to
     * replace this installation's workspace. The caller owns the surrounding
     * transaction that also swaps workspace metadata and authority binding.
     */
    fun discardAllForWorkspaceReplacement() {
        queries.deleteAllLocalAuthorityForWorkspaceReplacementV2()
        queries.deleteAllCheckpointObjectsForWorkspaceReplacementV2()
        queries.deleteAllCheckpointsForWorkspaceReplacementV2()
        queries.deleteAllPendingMutationsForWorkspaceReplacementV2()
        queries.deleteAllAppliedMutationsForWorkspaceReplacementV2()
        queries.deleteAllRemoteCursorsForWorkspaceReplacementV2()
        queries.deleteAllSourceImportsForWorkspaceReplacementV2()
        queries.deleteAllProjectionWarningsForWorkspaceReplacementV2()
        queries.deleteAllPreferencesProjectionsForWorkspaceReplacementV2()
        queries.deleteAllNoteProjectionsForWorkspaceReplacementV2()
        queries.deleteAllNotebookProjectionsForWorkspaceReplacementV2()
        queries.deleteAllConflictHeadsForWorkspaceReplacementV2()
        queries.deleteAllConflictsForWorkspaceReplacementV2()
        queries.deleteAllHeadsForWorkspaceReplacementV2()
        queries.deleteAllParentsForWorkspaceReplacementV2()
        queries.deleteAllVersionsForWorkspaceReplacementV2()
        queries.deleteAllControlObjectsForWorkspaceReplacementV2()
        queries.deleteAllDeadLettersForWorkspaceReplacementV2()
        queries.deleteAllRunHistorySystemV2()
        queries.deleteAllEpochsForWorkspaceReplacementV2()
        check(queries.selectWorkspaceOwnedRowCountV2().executeAsOne() == 0L)
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
