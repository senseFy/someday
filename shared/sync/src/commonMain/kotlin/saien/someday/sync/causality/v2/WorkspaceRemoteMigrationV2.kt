@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import kotlin.time.Clock
import kotlin.time.Instant

sealed interface WorkspaceRemoteMigrationResultV2 {
    data class Migrated(
        val sourceProfile: String,
        val sourceEpochId: String,
        val targetProfile: String,
        val targetEpochId: String,
        val rootCount: Int,
        val importedLateObjects: Int,
        val activeConflicts: Int,
    ) : WorkspaceRemoteMigrationResultV2

    data class Blocked(
        val safeErrorCode: String,
        val safeMessage: String,
    ) : WorkspaceRemoteMigrationResultV2
}

/**
 * Explicit, one-way authority migration between independent V2 remotes.
 * The source is drained and snapshotted, the target is required to be empty,
 * and only the target pointer CAS changes authority.  There is no dual write.
 */
class WorkspaceRemoteMigrationServiceV2(
    private val localRepository: SqlDelightLocalDataRepository,
    private val workspaceKey: WorkspaceMasterKey,
    private val targetWriterDeviceId: String,
    private val sourceRemote: WorkspaceSyncRemoteV2,
    private val targetRemote: WorkspaceSyncRemoteV2,
    private val protocolStore: SqlDelightSyncProtocolStoreV2 =
        SqlDelightSyncProtocolStoreV2(localRepository.database),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    init {
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(targetWriterDeviceId))
        require(sourceRemote.authorityBindingId != targetRemote.authorityBindingId) {
            "Source and target authority bindings must differ for remote migration."
        }
    }

    fun migrate(): WorkspaceRemoteMigrationResultV2 {
        val sourceEpoch = protocolStore.loadAuthoritativeEpoch()
            ?: return blocked("source_authority_missing", "No authenticated V2 source authority exists.")
        if (sourceEpoch.remoteProfile != sourceRemote.remoteProfile ||
            sourceEpoch.authorityBindingId?.let { it != sourceRemote.authorityBindingId } == true
        ) {
            return blocked("source_authority_mismatch", "The source connection is not the authenticated local V2 authority.")
        }
        if (sourceEpoch.lifecycle != SyncEpochLifecycleV2.ACTIVE || sourceEpoch.health != SyncEpochHealthV2.HEALTHY) {
            return blocked("source_authority_unhealthy", "Repair the source V2 authority before remote migration.")
        }
        val sourcePointer = runCatching(sourceRemote::loadEpochPointer).getOrElse {
            return blocked("source_pointer_unavailable", it.safeMigrationMessageV2())
        }
        if (sourcePointer?.syncEpochId != sourceEpoch.descriptor.syncEpochId ||
            sourcePointer.objectDigest != sourceEpoch.descriptorDigest
        ) {
            return blocked("source_pointer_mismatch", "The source endpoint no longer exposes the bound authenticated pointer.")
        }
        val capabilities = runCatching(targetRemote::capabilities).getOrElse {
            return blocked("target_unavailable", it.safeMigrationMessageV2())
        }
        capabilities.incompatibility()?.let {
            return blocked(it, "The target does not implement the frozen whole-product V2 contract.")
        }
        if (capabilities.profile != targetRemote.remoteProfile) {
            return blocked("remote_profile_mismatch", "The target capability profile is inconsistent.")
        }
        val targetPointer = runCatching(targetRemote::loadEpochPointer).getOrElse {
            return blocked("target_pointer_unavailable", it.safeMigrationMessageV2())
        }
        if (targetPointer != null) {
            return blocked(
                "target_remote_not_empty",
                "The target already has an authenticated V2 authority. Migration will not overwrite or merge it implicitly.",
            )
        }
        val sourceContext = context(sourceEpoch)
        if (sourceContext.store.loadPending(sourceEpoch.remoteProfile).isNotEmpty()) {
            return blocked("source_outbox_not_drained", "Synchronize and acknowledge the source V2 outbox before migration.")
        }
        val firstFrontier = runCatching {
            sourceRemote.epochFrontiers(sourceEpoch.descriptor.syncEpochId).sortedBy { it.streamId }
        }.getOrElse { return blocked("source_frontier_unavailable", it.safeMigrationMessageV2()) }
        if (!frontierIsApplied(sourceContext.store, sourceEpoch.remoteProfile, firstFrontier)) {
            return blocked("source_frontier_not_applied", "The source graph has not applied its authenticated remote frontier.")
        }

        val sourceHeads = sourceContext.store.loadEntityKeys()
            .flatMap(sourceContext.store::loadHeads)
            .map { head ->
                WorkspaceCheckpointSourceHeadV2(
                    entityType = head.entityType,
                    entityId = head.entityId,
                    content = head.contentPayload,
                    deletion = head.deletionPayload,
                    sourceProfile = sourceEpoch.remoteProfile,
                    sourceEpoch = sourceEpoch.descriptor.syncEpochId,
                    sourceWriterId = null,
                    sourceMutationId = null,
                    sourceObjectId = head.versionId,
                    sourceObjectDigest = head.objectDigest,
                    sourceAuthoredAt = head.authoredAt,
                )
            }
            .sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)
        if (sourceHeads.none {
                it.entityType == WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES &&
                    it.entityId == WORKSPACE_PREFERENCES_ENTITY_ID_V2
            }
        ) {
            return blocked("source_checkpoint_incomplete", "The source lacks the required workspace-preferences root.")
        }
        val secondFrontier = runCatching {
            sourceRemote.epochFrontiers(sourceEpoch.descriptor.syncEpochId).sortedBy { it.streamId }
        }.getOrElse { return blocked("source_frontier_unavailable", it.safeMigrationMessageV2()) }
        if (firstFrontier != secondFrontier || sourceContext.store.loadPending(sourceEpoch.remoteProfile).isNotEmpty()) {
            return blocked("source_frontier_changed", "The source changed during migration preparation; retry after another clean sync.")
        }

        val prepared = runCatching {
            WorkspaceCheckpointBuilderV2(workspaceKey, targetWriterDeviceId).build(
                remoteProfile = targetRemote.remoteProfile,
                sourceHeads = sourceHeads,
                createdAt = clock(),
                previousPointerDigest = null,
                previousEpochId = sourceEpoch.descriptor.syncEpochId,
                previousEpochPointerDigest = sourceEpoch.descriptorDigest,
                previousEpochFrontiers = firstFrontier,
            )
        }.getOrElse { return blocked("target_checkpoint_build_failed", it.safeMigrationMessageV2()) }
        when (val persisted = WorkspaceCheckpointPersistenceV2(
            localRepository,
            workspaceKey,
            targetWriterDeviceId,
            protocolStore,
        ).persist(prepared)) {
            is WorkspaceCheckpointPersistResultV2.Rejected -> return blocked(
                persisted.safeErrorCode,
                persisted.safeMessage,
            )
            is WorkspaceCheckpointPersistResultV2.Ready -> Unit
        }
        val handoffAuthority = protocolStore.loadLocalAuthority()
            ?: return blocked("source_authority_missing", "The exact local source authority binding is unavailable.")
        var targetEpochId = prepared.descriptor.syncEpochId
        var publishedCheckpointObjects = prepared.entities.size
        var importedLate = 0
        when (val published = WorkspaceCheckpointPublisherV2(
            localRepository,
            targetRemote,
            protocolStore,
        ).publish(prepared)) {
            is WorkspaceCheckpointPublishResultV2.Rejected -> return blocked(
                published.safeErrorCode,
                published.safeMessage,
            )
            is WorkspaceCheckpointPublishResultV2.LostRace -> {
                val winner = WorkspaceSyncCoordinatorV2(
                    localRepository,
                    workspaceKey,
                    targetWriterDeviceId,
                    targetRemote,
                    protocolStore,
                    clock,
                    authorityHandoffFrom = handoffAuthority,
                ).syncOnce()
                if (winner.status != SyncCoordinatorStatusV2.SUCCESS || winner.epochId == null) {
                    return blocked(
                        winner.safeErrorCode ?: "target_pointer_compare_and_set_lost",
                        winner.safeMessage ?: "Another initializer won, but its checkpoint could not be authenticated and bootstrapped.",
                    )
                }
                protocolStore.abandonPreparingEpoch(
                    targetRemote.remoteProfile,
                    prepared.descriptor.syncEpochId,
                    "epoch_pointer_compare_and_set_lost",
                    "Another authenticated checkpoint won the target pointer.",
                )
                targetEpochId = winner.epochId
                publishedCheckpointObjects = 0
                when (val snapshotImport = WorkspaceSourceSnapshotImporterV2(
                    localRepository,
                    workspaceKey,
                    targetWriterDeviceId,
                    targetRemote.remoteProfile,
                ).import(sourceHeads)) {
                    is WorkspaceSourceSnapshotImportResultV2.Blocked -> return blocked(
                        snapshotImport.safeErrorCode,
                        snapshotImport.safeMessage,
                    )
                    is WorkspaceSourceSnapshotImportResultV2.Imported ->
                        importedLate += snapshotImport.importedVersions
                }
            }
            is WorkspaceCheckpointPublishResultV2.Published -> Unit
        }

        when (val localImport = WorkspacePriorEpochImporterV2(
            localRepository,
            workspaceKey,
            targetWriterDeviceId,
            targetRemote.remoteProfile,
            protocolStore,
            clock,
        ).importUncheckpointed(sourceEpoch.descriptor.syncEpochId, sourceEpoch.remoteProfile)) {
            is WorkspacePriorEpochImportResultV2.Blocked -> return blocked(localImport.safeErrorCode, localImport.safeMessage)
            is WorkspacePriorEpochImportResultV2.Imported -> importedLate += localImport.importedVersions
        }
        when (val remoteImport = WorkspacePriorEpochRemoteImporterV2(
            localRepository,
            workspaceKey,
            targetWriterDeviceId,
            sourceRemote,
            protocolStore,
            clock,
        ).importUntilStable(sourceEpoch.descriptor.syncEpochId)) {
            is WorkspacePriorEpochRemoteImportResultV2.Blocked -> return blocked(
                remoteImport.safeErrorCode,
                remoteImport.safeMessage,
            )
            is WorkspacePriorEpochRemoteImportResultV2.Imported -> importedLate += remoteImport.importedVersions
        }
        val summary = WorkspaceSyncCoordinatorV2(
            localRepository,
            workspaceKey,
            targetWriterDeviceId,
            targetRemote,
            protocolStore,
            clock,
        ).syncOnce()
        if (summary.status != SyncCoordinatorStatusV2.SUCCESS) {
            return blocked(
                summary.safeErrorCode ?: "target_first_sync_failed",
                summary.safeMessage ?: "Target authority committed, but its first synchronization stopped safely.",
            )
        }
        return WorkspaceRemoteMigrationResultV2.Migrated(
            sourceEpoch.remoteProfile,
            sourceEpoch.descriptor.syncEpochId,
            targetRemote.remoteProfile,
            targetEpochId,
            publishedCheckpointObjects,
            importedLate,
            summary.activeConflicts,
        )
    }

    private fun context(epoch: StoredSyncEpochV2): ActiveWorkspaceSystemV2 =
        WorkspaceSystemV2ContextProvider(
            localRepository,
            { workspaceKey },
            { targetWriterDeviceId },
            { epoch.remoteProfile },
        ).requireActive()

    private fun frontierIsApplied(
        store: SqlDelightWorkspaceEntityStoreV2,
        remoteProfile: String,
        frontiers: List<SyncStreamFrontierV2>,
    ): Boolean = frontiers.all { frontier ->
        store.loadCursor(remoteProfile, frontier.streamId)?.cursorValue == frontier.cursorValue
    }

    private fun blocked(code: String, message: String) =
        WorkspaceRemoteMigrationResultV2.Blocked(code, message.take(500))
}

private fun Throwable.safeMigrationMessageV2(): String =
    (message ?: "V2 remote migration failed safely.")
        .replace(Regex("(?i)(password|token|credential|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=redacted")
        .take(500)
