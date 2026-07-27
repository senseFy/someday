@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.local.SqlDelightLocalDataRepository
import kotlin.time.Instant

data class WorkspaceEpochRetentionPinsV2(
    val pendingMutations: Long,
    val openTransportUnits: Long,
    val activeDeadLetters: Long,
    val activeQuarantines: Long,
    val repairPins: Long,
    val unpublishedImports: Long,
    val localAuthorityPins: Long,
) {
    val total: Long
        get() = pendingMutations + openTransportUnits + activeDeadLetters +
            activeQuarantines + repairPins + unpublishedImports + localAuthorityPins

    val safeReasons: List<String>
        get() = buildList {
            if (pendingMutations > 0) add("pending_mutations")
            if (openTransportUnits > 0) add("open_transport_units")
            if (activeDeadLetters > 0) add("active_dead_letters")
            if (activeQuarantines > 0) add("active_quarantines")
            if (repairPins > 0) add("repair_pins")
            if (unpublishedImports > 0) add("unpublished_imports")
            if (localAuthorityPins > 0) add("local_authority")
        }
}

data class WorkspaceEpochRetentionCollectionV2(
    val collectedEpochIds: List<String>,
    val pinnedEpochs: Map<String, WorkspaceEpochRetentionPinsV2>,
    val retainedEpochIds: List<String>,
    val releasedAuthorityBindingIds: List<String>,
)

/**
 * Local half of epoch-history collection.
 *
 * The active epoch is never considered.  A read-only epoch becomes eligible
 * from the successor checkpoint's authenticated creation time, and deletion
 * is one transaction which fails closed while any outbox, cursor-unit,
 * repair, quarantine, import, or authority pin remains.  Active-epoch
 * ancestry is intentionally not pruned by this service.
 */
class WorkspaceEpochRetentionServiceV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 =
        SqlDelightSyncProtocolStoreV2(localRepository.database),
) {
    private val queries = localRepository.database.somedayQueries

    fun collectExpiredLocalEpochs(remoteProfile: String, now: Instant): WorkspaceEpochRetentionCollectionV2 {
        require(remoteProfile in setOf(
            SyncRemoteProfileV2.WEB_DAV.wireValue,
            SyncRemoteProfileV2.SELF_HOSTED.wireValue,
        ))
        val nowMillis = now.toEpochMilliseconds()
        val collected = mutableListOf<String>()
        val collectedBindings = mutableListOf<String>()
        val pinned = mutableMapOf<String, WorkspaceEpochRetentionPinsV2>()

        protocolStore.retentionPlan(remoteProfile, now).eligibleReadOnlyEpochIds.forEach { epochId ->
            localRepository.database.transaction {
                val epoch = protocolStore.loadEpoch(remoteProfile, epochId)
                if (epoch?.lifecycle != SyncEpochLifecycleV2.READ_ONLY ||
                    epoch.retainUntilEpochMilliseconds?.let { it <= nowMillis } != true
                ) {
                    return@transaction
                }
                val pins = loadPins(remoteProfile, epochId)
                if (pins.total > 0) {
                    pinned[epochId] = pins
                    return@transaction
                }
                deleteEpoch(remoteProfile, epochId)
                check(protocolStore.loadEpoch(remoteProfile, epochId) == null) {
                    "Expired V2 epoch collection did not remove its lifecycle row."
                }
                collected += epochId
                epoch.authorityBindingId?.let(collectedBindings::add)
            }
        }

        val stillReferencedBindings = protocolStore.loadAllEpochs().mapNotNull { it.authorityBindingId }.toSet()
        return WorkspaceEpochRetentionCollectionV2(
            collectedEpochIds = collected.sorted(),
            pinnedEpochs = pinned.entries.sortedBy { it.key }.associate { it.key to it.value },
            retainedEpochIds = protocolStore.loadEpochs(remoteProfile)
                .map { it.descriptor.syncEpochId }
                .sorted(),
            releasedAuthorityBindingIds = collectedBindings.distinct().filterNot(stillReferencedBindings::contains).sorted(),
        )
    }

    fun loadPins(remoteProfile: String, epochId: String): WorkspaceEpochRetentionPinsV2 {
        val row = queries.selectEpochRetentionPinCountsSystemV2(remoteProfile, epochId).executeAsOne()
        return WorkspaceEpochRetentionPinsV2(
            row.pending_mutations,
            row.open_transport_units,
            row.active_dead_letters,
            row.active_quarantines,
            row.repair_pins,
            row.unpublished_imports,
            row.local_authority_pins,
        )
    }

    private fun deleteEpoch(remoteProfile: String, epochId: String) {
        // Protocol/control rows first.  Checkpoint objects precede their
        // manifest row; every version reference precedes immutable versions.
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
        queries.deleteEpochTransportUnitsSystemV2(remoteProfile, epochId)
        queries.deleteEpochQuarantinesSystemV2(remoteProfile, epochId)
        queries.deleteEpochRepairReplicasSystemV2(remoteProfile, epochId)
        queries.deleteEpochDeadLettersSystemV2(remoteProfile, epochId)
        queries.deleteEpochRunHistorySystemV2(remoteProfile, epochId)

        queries.deleteSyncEpochSystemV2(remoteProfile, epochId)
    }
}
