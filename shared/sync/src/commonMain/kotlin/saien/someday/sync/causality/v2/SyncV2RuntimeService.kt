@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.SyncV2MaintenanceRunner
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator
import kotlin.time.Clock
import kotlin.time.Instant

fun interface SyncRemoteTransportFactoryV2 {
    fun create(): WorkspaceSyncRemoteV2
}

/** Opens the original authority for retained-epoch late-writer monitoring. */
fun interface RetainedEpochRemoteProviderV2 {
    fun open(epoch: StoredSyncEpochV2): WorkspaceSyncRemoteV2?
}

/**
 * Product boundary for System V2 (DAG + epoch). This is the only sync start point.
 *
 * Local product rows feed a one-time genesis checkpoint when no epoch exists.
 */
class SyncV2RuntimeService(
    private val mode: SyncMode,
    private val localRepository: SqlDelightLocalDataRepository,
    private val settingsRepository: ClientSettingsRepository,
    private val workspaceKeyProvider: () -> WorkspaceMasterKey?,
    private val writerDeviceIdProvider: () -> String,
    private val transportFactory: SyncRemoteTransportFactoryV2,
    private val retainedEpochRemoteProvider: RetainedEpochRemoteProviderV2 =
        RetainedEpochRemoteProviderV2 { null },
    private val activationEnabled: Boolean,
    private val authorityMutationCoordinator: WorkspaceAuthorityMutationCoordinator,
    private val clock: () -> Instant = { Clock.System.now() },
    private val onPublishProgress: (WorkspaceCheckpointPublishProgressV2) -> Unit = {},
) : ManualSyncRunner, SyncV2MaintenanceRunner {
    private val protocolStore = SqlDelightSyncProtocolStoreV2(localRepository.database)
    private val epochRetention = WorkspaceEpochRetentionServiceV2(localRepository, protocolStore)

    override fun run(): ManualSyncResult {
        val configured = settingsRepository.load().syncConfiguration
        if (configured.mode != mode) {
            return ManualSyncResult.failure(mode, "The selected sync provider changed before this run started.")
        }
        val authority = protocolStore.loadAuthoritativeEpoch()
        if (authority != null && authority.remoteProfile != mode.remoteProfileV2()) {
            return ManualSyncResult.failure(
                mode,
                "The selected provider is not the authenticated V2 authority. Use explicit remote migration; ordinary sync cannot change authority.",
            )
        }
        if (authority == null) {
            return authorityMutationCoordinator.exclusive {
                val authorityAfterLock = protocolStore.loadAuthoritativeEpoch()
                if (authorityAfterLock == null) {
                    initializeWorkspace()
                } else if (authorityAfterLock.remoteProfile != mode.remoteProfileV2()) {
                    ManualSyncResult.failure(
                        mode,
                        "The selected provider is not the authenticated V2 authority. Use explicit remote migration; ordinary sync cannot change authority.",
                    )
                } else {
                    runV2()
                }
            }
        }
        return runV2()
    }

    private fun initializeWorkspace(): ManualSyncResult {
        if (!activationEnabled) {
            return failedInitialization(
                "Sync V2 is release-disabled in this build configuration; no first epoch was published.",
            )
        }
        val configured = settingsRepository.load()
        if (configured.syncConfiguration.mode != mode) {
            return failedInitialization("Select $mode before running sync.")
        }
        val workspaceKey = workspaceKeyProvider()
            ?: return failedInitialization("Unlock the workspace before running sync.")
        val writerDeviceId = normalizedWriterIdOrFailure()
            .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
        val remote = createRemoteOrFailure()
            .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
        val capabilities = runCatching(remote::capabilities)
            .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
        capabilities.incompatibility()?.let { code ->
            return failedInitialization("Remote V2 capabilities are incompatible ($code).")
        }

        // Already on an active local epoch for this profile: ordinary sync.
        if (protocolStore.loadAuthoritativeEpoch()?.remoteProfile == remote.remoteProfile) {
            return runV2(workspaceKey, writerDeviceId, remote)
        }

        var pointer = runCatching(remote::loadEpochPointer)
            .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
        if (pointer == null) {
            when (val recovered = WorkspacePreparedCheckpointRecoveryV2(
                localRepository,
                workspaceKey,
                protocolStore,
            ).loadCompatible(remote.remoteProfile, null)) {
                is WorkspacePreparedCheckpointLoadResultV2.Rejected -> {
                    discardNeverAuthoritativeEpoch(
                        remote.remoteProfile,
                        recovered.epochId,
                        recovered.safeErrorCode,
                        recovered.safeMessage,
                    )
                }
                is WorkspacePreparedCheckpointLoadResultV2.Loaded -> {
                    when (
                        val attempt = publishRecoveredOrFreshDraft(
                            remote = remote,
                            workspaceKey = workspaceKey,
                            writerDeviceId = writerDeviceId,
                            prepared = recovered.prepared,
                        )
                    ) {
                        FirstEpochPublishAttemptV2.Published -> {
                            pointer = runCatching(remote::loadEpochPointer)
                                .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
                        }
                        FirstEpochPublishAttemptV2.RemoteWon -> {
                            pointer = runCatching(remote::loadEpochPointer)
                                .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
                        }
                        FirstEpochPublishAttemptV2.RebuildAfterStale -> Unit
                        is FirstEpochPublishAttemptV2.KeepPreparingAndStop ->
                            return failedInitialization(attempt.safeMessage)
                        is FirstEpochPublishAttemptV2.Failed ->
                            return failedInitialization(attempt.safeMessage)
                    }
                }
                WorkspacePreparedCheckpointLoadResultV2.None -> Unit
            }
            if (pointer == null) {
                // Do not create a second genesis while a PREPARING draft still exists
                // (e.g. transient empty-remote CAS failure). Next Sync resumes it.
                val stillPreparing = protocolStore.loadAllEpochs().any {
                    it.remoteProfile == remote.remoteProfile &&
                        it.lifecycle == SyncEpochLifecycleV2.PREPARING
                }
                if (stillPreparing) {
                    return failedInitialization(
                        "A prepared first-epoch checkpoint is waiting for pointer commit; run Sync again to resume.",
                    )
                }
                // First epoch: checkpoint from local product state (or rebuild after stale prepare).
                // Still inside authorityMutationCoordinator.exclusive from run().
                when (
                    val attempt = prepareAndPublishGenesis(
                        remote = remote,
                        workspaceKey = workspaceKey,
                        writerDeviceId = writerDeviceId,
                    )
                ) {
                    FirstEpochPublishAttemptV2.Published,
                    FirstEpochPublishAttemptV2.RemoteWon,
                    -> {
                        pointer = runCatching(remote::loadEpochPointer)
                            .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
                    }
                    FirstEpochPublishAttemptV2.RebuildAfterStale -> {
                        // One immediate rebuild inside the same exclusive lock.
                        when (
                            val retry = prepareAndPublishGenesis(
                                remote = remote,
                                workspaceKey = workspaceKey,
                                writerDeviceId = writerDeviceId,
                            )
                        ) {
                            FirstEpochPublishAttemptV2.Published,
                            FirstEpochPublishAttemptV2.RemoteWon,
                            -> {
                                pointer = runCatching(remote::loadEpochPointer)
                                    .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
                            }
                            FirstEpochPublishAttemptV2.RebuildAfterStale ->
                                return failedInitialization(
                                    "Local product state kept changing during first-epoch publish; try Sync again.",
                                )
                            is FirstEpochPublishAttemptV2.KeepPreparingAndStop ->
                                return failedInitialization(retry.safeMessage)
                            is FirstEpochPublishAttemptV2.Failed ->
                                return failedInitialization(retry.safeMessage)
                        }
                    }
                    is FirstEpochPublishAttemptV2.KeepPreparingAndStop ->
                        return failedInitialization(attempt.safeMessage)
                    is FirstEpochPublishAttemptV2.Failed ->
                        return failedInitialization(attempt.safeMessage)
                }
            }
        }

        var pushedObjects = 0
        var pulledObjects = 0
        var summary = coordinator(workspaceKey, writerDeviceId, remote).syncOnce()
        pushedObjects += summary.pushedObjects
        pulledObjects += summary.pulledObjects
        if (summary.status != SyncCoordinatorStatusV2.SUCCESS) {
            return failedInitialization(
                summary.safeMessage ?: "V2 initialization stopped before local bootstrap completed.",
                conflicts = summary.activeConflicts,
                pushedObjects = pushedObjects,
                pulledObjects = pulledObjects,
            )
        }
        collectObsoleteCheckpointDraftsAfterAuthenticatedPointer(
            remote = remote,
            workspaceKey = workspaceKey,
        )

        val importResult = WorkspaceJoiningDeviceImporterV2(
            localRepository = localRepository,
            settingsRepository = settingsRepository,
            workspaceKey = workspaceKey,
            writerDeviceId = writerDeviceId,
            remoteProfile = remote.remoteProfile,
            protocolStore = protocolStore,
            clock = clock,
        ).captureLocalProductState()
        if (importResult is WorkspaceJoiningImportResultV2.Blocked) {
            return failedInitialization(
                importResult.safeMessage,
                conflicts = summary.activeConflicts,
                pushedObjects = pushedObjects,
                pulledObjects = pulledObjects,
            )
        }
        summary = coordinator(workspaceKey, writerDeviceId, remote).syncOnce()
        pushedObjects += summary.pushedObjects
        pulledObjects += summary.pulledObjects
        if (summary.status != SyncCoordinatorStatusV2.SUCCESS) {
            return failedInitialization(
                summary.safeMessage ?: "V2 initialization stopped before local imports were acknowledged.",
                conflicts = summary.activeConflicts,
                pushedObjects = pushedObjects,
                pulledObjects = pulledObjects,
            )
        }

        return ManualSyncResult.success(
            mode = mode,
            message = "Whole-product Sync V2 is active; checkpoint, local imports, and first synchronization completed.",
            pushedObjects = pushedObjects,
            pulledObjects = pulledObjects,
            conflicts = summary.activeConflicts,
        )
    }

    override fun rollEpoch(): ManualSyncResult {
        val configured = settingsRepository.load().syncConfiguration
        if (configured.mode != mode) {
            return ManualSyncResult.failure(mode, "Whole-product Sync V2 must be active before epoch rollover.")
        }
        val workspaceKey = workspaceKeyProvider()
            ?: return ManualSyncResult.failure(mode, "Unlock the workspace before epoch rollover.")
        val writerDeviceId = normalizedWriterIdOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        val remote = createRemoteOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }

        repeat(MAX_FRONTIER_STABILIZATION_ROUNDS_V2) {
            val drained = runV2(workspaceKey, writerDeviceId, remote)
            if (!drained.success) return drained
            val active = protocolStore.loadActiveEpoch(remote.remoteProfile)
                ?: return ManualSyncResult.failure(mode, "A healthy active V2 epoch is required before rollover.")
            if (active.health != SyncEpochHealthV2.HEALTHY ||
                protocolStore.loadActiveDeadLetters(remote.remoteProfile, active.descriptor.syncEpochId).isNotEmpty()
            ) {
                return ManualSyncResult.failure(mode, "Repair the active V2 epoch before rollover.")
            }
            val context = activeContext(workspaceKey, writerDeviceId, remote.remoteProfile)
            if (context.store.loadPending(remote.remoteProfile).isNotEmpty()) {
                return@repeat
            }
            val firstFrontier = runCatching { remote.epochFrontiers(active.descriptor.syncEpochId) }
                .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
                .sortedBy { it.streamId }
            if (!frontierIsApplied(context.store, remote.remoteProfile, firstFrontier)) return@repeat

            val sources = context.store.loadEntityKeys()
                .flatMap { context.store.loadHeads(it) }
                .map { head ->
                    WorkspaceCheckpointSourceHeadV2(
                        entityType = head.entityType,
                        entityId = head.entityId,
                        content = head.contentPayload,
                        deletion = head.deletionPayload,
                        sourceProfile = remote.remoteProfile,
                        sourceEpoch = active.descriptor.syncEpochId,
                        sourceWriterId = null,
                        sourceMutationId = null,
                        sourceObjectId = head.versionId,
                        sourceObjectDigest = head.objectDigest,
                        sourceAuthoredAt = head.authoredAt,
                    )
                }
                .sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)
            val secondFrontier = runCatching { remote.epochFrontiers(active.descriptor.syncEpochId) }
                .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
                .sortedBy { it.streamId }
            if (firstFrontier != secondFrontier || context.store.loadPending(remote.remoteProfile).isNotEmpty()) {
                return@repeat
            }
            val prepared = runCatching {
                WorkspaceCheckpointBuilderV2(workspaceKey, writerDeviceId).build(
                    remoteProfile = remote.remoteProfile,
                    sourceHeads = sources,
                    createdAt = clock(),
                    previousPointerDigest = active.descriptorDigest,
                    previousEpochId = active.descriptor.syncEpochId,
                    previousEpochFrontiers = firstFrontier,
                )
            }.getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
            when (val persisted = WorkspaceCheckpointPersistenceV2(
                localRepository, workspaceKey, writerDeviceId, protocolStore,
            ).persist(prepared)) {
                is WorkspaceCheckpointPersistResultV2.Rejected ->
                    return ManualSyncResult.failure(mode, persisted.safeMessage)
                is WorkspaceCheckpointPersistResultV2.Ready -> Unit
            }
            return when (val published = checkpointPublisher(
                remote = remote,
                workspaceKey = workspaceKey,
                writerDeviceId = writerDeviceId,
                prepared = prepared,
                requireNoPendingSource = true,
            ).publish(prepared)) {
                is WorkspaceCheckpointPublishResultV2.Rejected -> {
                    if (published.safeErrorCode == "prepared_checkpoint_stale") {
                        discardNeverAuthoritativeEpoch(
                            remote.remoteProfile,
                            prepared.descriptor.syncEpochId,
                            published.safeErrorCode,
                            published.safeMessage,
                        )
                    }
                    ManualSyncResult.failure(mode, published.safeMessage)
                }
                is WorkspaceCheckpointPublishResultV2.LostRace -> runV2(workspaceKey, writerDeviceId, remote)
                is WorkspaceCheckpointPublishResultV2.Published -> {
                    val imported = WorkspacePriorEpochImporterV2(
                        localRepository, workspaceKey, writerDeviceId, remote.remoteProfile, protocolStore, clock,
                    ).importUncheckpointed(active.descriptor.syncEpochId)
                    if (imported is WorkspacePriorEpochImportResultV2.Blocked) {
                        ManualSyncResult.failure(mode, imported.safeMessage)
                    } else {
                        val completed = runV2(workspaceKey, writerDeviceId, remote)
                        if (completed.success) completed.copy(
                            message = "Sync V2 rolled to epoch ${prepared.descriptor.syncEpochId} and preserved every normalized head.",
                        ) else completed
                    }
                }
            }
        }
        return ManualSyncResult.failure(
            mode,
            "The authenticated frontier kept changing; rollover was deferred without changing the active epoch.",
        )
    }

    override fun repairIntegrity(): ManualSyncResult {
        val configured = settingsRepository.load().syncConfiguration
        if (configured.mode != mode) {
            return ManualSyncResult.failure(mode, "Whole-product Sync V2 must be active before integrity repair.")
        }
        val workspaceKey = workspaceKeyProvider()
            ?: return ManualSyncResult.failure(mode, "Unlock the workspace before integrity repair.")
        val writerDeviceId = normalizedWriterIdOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        val remote = createRemoteOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        val active = protocolStore.loadActiveEpoch(remote.remoteProfile)
            ?: return ManualSyncResult.failure(mode, "No local authenticated V2 epoch exists to repair.")
        val blockers = protocolStore.loadActiveDeadLetters(remote.remoteProfile, active.descriptor.syncEpochId)
        if (blockers.isEmpty()) {
            return ManualSyncResult.success(mode, 0, 0, 0, "Sync V2 integrity state is healthy; no repair is required.")
        }
        val repair = WorkspaceImmutableObjectRepairServiceV2(
            localRepository, workspaceKey, writerDeviceId, remote, protocolStore, clock,
        )
        blockers.filter {
            it.input.failureClass != SyncDeadLetterFailureClassV2.RETRYABLE_DEPENDENCY
        }.forEach { deadLetter ->
            when (val result = repair.repair(deadLetter)) {
                is WorkspaceRepairResultV2.Repaired -> Unit
                is WorkspaceRepairResultV2.RebootstrapRequired ->
                    return ManualSyncResult.failure(mode, result.safeMessage)
                is WorkspaceRepairResultV2.StillBlocked ->
                    return ManualSyncResult.failure(mode, result.safeMessage)
            }
        }
        return runV2(workspaceKey, writerDeviceId, remote)
    }

    override fun recoverWithVerifiedLocalCheckpoint(
        userConfirmedPotentialDataLoss: Boolean,
    ): ManualSyncResult {
        if (!userConfirmedPotentialDataLoss) {
            return ManualSyncResult.failure(
                mode,
                "Authorized V2 recovery requires explicit confirmation that this device is the selected healthy source. No pointer changed.",
            )
        }
        val configured = settingsRepository.load().syncConfiguration
        if (configured.mode != mode) {
            return ManualSyncResult.failure(mode, "Whole-product Sync V2 must be active before authorized recovery.")
        }
        val workspaceKey = workspaceKeyProvider()
            ?: return ManualSyncResult.failure(mode, "Unlock the workspace before authorized recovery.")
        val writerDeviceId = normalizedWriterIdOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        val remote = createRemoteOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        val prior = protocolStore.loadAuthoritativeEpoch()
            ?: return ManualSyncResult.failure(mode, "No authenticated V2 authority exists to recover.")
        if (prior.remoteProfile != remote.remoteProfile ||
            prior.authorityBindingId?.let { it != remote.authorityBindingId } == true
        ) {
            return ManualSyncResult.failure(mode, "The configured endpoint is not the authenticated V2 authority.")
        }
        val context = WorkspaceSystemV2ContextProvider(
            localRepository,
            { workspaceKey },
            { writerDeviceId },
            { prior.remoteProfile },
        ).openOrNull() ?: return ManualSyncResult.failure(
            mode,
            "The local authenticated V2 DAG cannot be opened for verification.",
        )
        if (context.syncEpochId != prior.descriptor.syncEpochId) {
            return ManualSyncResult.failure(mode, "The local recovery DAG is not the authoritative blocked epoch.")
        }
        val keys = context.store.loadEntityKeys()
        val preferenceKey = WorkspaceEntityKeyV2(
            WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            WORKSPACE_PREFERENCES_ENTITY_ID_V2,
        )
        if (preferenceKey !in keys) {
            return ManualSyncResult.failure(mode, "The local recovery DAG is incomplete: workspace preferences are missing.")
        }
        val validator = WorkspaceEntityValidatorV2(context.materializer)
        val verifier = WorkspaceEntityCausalityEngineV2(context.materializer, validator)
        for (key in keys) {
            val storedHeads = context.store.loadHeads(key).map { it.versionId }.sorted()
            val verified = verifier.reconcile(
                context.syncEpochId,
                key,
                context.store.loadVersions(key),
                context.store.loadConflicts(key).map { it.descriptor },
            )
            val plan = (verified as? WorkspaceReconciliationResultV2.Reconciled)?.plan
                ?: return ManualSyncResult.failure(mode, "A local V2 entity graph failed deterministic verification.")
            if (plan.generatedVersions.isNotEmpty() || plan.finalHeadVersionIds.sorted() != storedHeads) {
                return ManualSyncResult.failure(
                    mode,
                    "The local V2 graph is not fully normalized; repair it before authorizing a recovery checkpoint.",
                )
            }
        }
        runCatching { context.store.rebuildProjections(clock()) }.getOrElse {
            return ManualSyncResult.failure(mode, "The local V2 projections could not be rebuilt from the verified DAG.")
        }
        val currentPointer = runCatching(remote::loadEpochPointer).getOrElse {
            return ManualSyncResult.failure(mode, it.safeRuntimeMessage())
        } ?: return ManualSyncResult.failure(mode, "The authenticated V2 pointer is missing; restore the control register first.")
        val decodedPointer = when (val decoded = WorkspaceSyncControlCodecV2(context.cipher).decodeEpochPointer(currentPointer)) {
            is WorkspaceControlDecodeResultV2.Decoded -> decoded.value
            is WorkspaceControlDecodeResultV2.Rejected -> return ManualSyncResult.failure(mode, decoded.error.safeMessage)
        }
        if (decodedPointer.descriptor != prior.descriptor || currentPointer.objectDigest != prior.descriptorDigest) {
            return ManualSyncResult.failure(mode, "The remote pointer no longer matches the locally authenticated recovery source.")
        }
        val previousFrontiers = runCatching { remote.epochFrontiers(prior.descriptor.syncEpochId) }.getOrElse {
            return ManualSyncResult.failure(mode, "The prior authenticated frontier could not be captured for recovery provenance.")
        }
        val sources = keys.flatMap { key ->
            context.store.loadHeads(key).map { head ->
                WorkspaceCheckpointSourceHeadV2(
                    head.entityType,
                    head.entityId,
                    head.contentPayload,
                    head.deletionPayload,
                    "authorized-local-rebootstrap",
                    prior.descriptor.syncEpochId,
                    null,
                    null,
                    head.versionId,
                    head.objectDigest,
                    head.authoredAt,
                )
            }
        }.sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)
        val prepared = runCatching {
            WorkspaceCheckpointBuilderV2(workspaceKey, writerDeviceId).build(
                remoteProfile = remote.remoteProfile,
                sourceHeads = sources,
                createdAt = clock(),
                previousPointerDigest = currentPointer.objectDigest,
                previousEpochId = prior.descriptor.syncEpochId,
                previousEpochFrontiers = previousFrontiers,
            )
        }.getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        when (val persisted = WorkspaceCheckpointPersistenceV2(
            localRepository,
            workspaceKey,
            writerDeviceId,
            protocolStore,
        ).persist(prepared)) {
            is WorkspaceCheckpointPersistResultV2.Rejected -> return ManualSyncResult.failure(mode, persisted.safeMessage)
            is WorkspaceCheckpointPersistResultV2.Ready -> Unit
        }
        return when (val published = checkpointPublisher(
            remote = remote,
            workspaceKey = workspaceKey,
            writerDeviceId = writerDeviceId,
            prepared = prepared,
        ).publish(prepared)) {
            is WorkspaceCheckpointPublishResultV2.Rejected -> {
                if (published.safeErrorCode == "prepared_checkpoint_stale") {
                    discardNeverAuthoritativeEpoch(
                        remote.remoteProfile,
                        prepared.descriptor.syncEpochId,
                        published.safeErrorCode,
                        published.safeMessage,
                    )
                }
                ManualSyncResult.failure(mode, published.safeMessage)
            }
            is WorkspaceCheckpointPublishResultV2.LostRace -> runV2(workspaceKey, writerDeviceId, remote)
            is WorkspaceCheckpointPublishResultV2.Published -> {
                protocolStore.archiveAfterAuthorizedRebootstrap(
                    prior.remoteProfile,
                    prior.descriptor.syncEpochId,
                    clock(),
                )
                val completed = runV2(workspaceKey, writerDeviceId, remote)
                if (completed.success) completed.copy(
                    message = "Authorized Sync V2 recovery committed a verified local checkpoint as epoch " +
                        "${prepared.descriptor.syncEpochId}; the prior blocked epoch remains a time-bounded read-only archive.",
                ) else completed
            }
        }
    }

    internal fun synchronizeBoundAuthorityForMigration(): ManualSyncResult {
        val authority = protocolStore.loadAuthoritativeEpoch()
            ?: return ManualSyncResult.failure(mode, "No authenticated V2 source authority exists.")
        if (authority.remoteProfile != mode.remoteProfileV2()) {
            return ManualSyncResult.failure(mode, "This runtime is not the authenticated V2 source authority.")
        }
        val workspaceKey = workspaceKeyProvider()
            ?: return ManualSyncResult.failure(mode, "Unlock the workspace before remote migration.")
        val writerDeviceId = normalizedWriterIdOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        val remote = createRemoteOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        if (authority.authorityBindingId?.let { it != remote.authorityBindingId } == true) {
            return ManualSyncResult.failure(mode, "The source endpoint no longer matches the authenticated V2 authority binding.")
        }
        return runV2(workspaceKey, writerDeviceId, remote)
    }

    internal fun synchronizeRemoteForMigration(remote: WorkspaceSyncRemoteV2): ManualSyncResult {
        val authority = protocolStore.loadAuthoritativeEpoch()
            ?: return ManualSyncResult.failure(mode, "No authenticated V2 source authority exists.")
        if (authority.remoteProfile != remote.remoteProfile ||
            authority.authorityBindingId?.let { it != remote.authorityBindingId } == true
        ) {
            return ManualSyncResult.failure(mode, "The supplied source does not match the authenticated V2 authority binding.")
        }
        val workspaceKey = workspaceKeyProvider()
            ?: return ManualSyncResult.failure(mode, "Unlock the workspace before remote migration.")
        val writerDeviceId = normalizedWriterIdOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        return runV2(workspaceKey, writerDeviceId, remote)
    }

    internal fun openRemoteForMigration(): Result<WorkspaceSyncRemoteV2> = createRemoteOrFailure()

    internal fun configuredWriterIdForMigration(): Result<String> =
        runCatching { normalizeWriterDeviceIdV2(writerDeviceIdProvider()) }

    private fun runV2(): ManualSyncResult {
        val workspaceKey = workspaceKeyProvider()
            ?: return ManualSyncResult.failure(mode, "Unlock the workspace before running Sync V2.")
        val writerDeviceId = normalizedWriterIdOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        val remote = createRemoteOrFailure()
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        return runV2(workspaceKey, writerDeviceId, remote)
    }

    private fun runV2(
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
        remote: WorkspaceSyncRemoteV2,
    ): ManualSyncResult {
        val restoredBackupPending = protocolStore.loadReconciliationState()?.reason ==
            "restored_backup_pending_reconciliation"
        val pointer = runCatching(remote::loadEpochPointer)
            .getOrElse { return ManualSyncResult.failure(mode, it.safeRuntimeMessage()) }
        when (val recovered = WorkspacePreparedCheckpointRecoveryV2(
            localRepository,
            workspaceKey,
            protocolStore,
        ).loadCompatible(remote.remoteProfile, pointer)) {
            WorkspacePreparedCheckpointLoadResultV2.None -> Unit
            is WorkspacePreparedCheckpointLoadResultV2.Rejected -> {
                if (pointer?.syncEpochId == recovered.epochId) {
                    return ManualSyncResult.failure(
                        mode,
                        "The committed V2 checkpoint cannot be reconstructed locally: ${recovered.safeMessage}",
                    )
                }
                discardNeverAuthoritativeEpoch(
                    remote.remoteProfile,
                    recovered.epochId,
                    recovered.safeErrorCode,
                    recovered.safeMessage,
                )
            }
            is WorkspacePreparedCheckpointLoadResultV2.Loaded -> {
                when (
                    val attempt = publishRecoveredOrFreshDraft(
                        remote = remote,
                        workspaceKey = workspaceKey,
                        writerDeviceId = writerDeviceId,
                        prepared = recovered.prepared,
                    )
                ) {
                    FirstEpochPublishAttemptV2.Published -> Unit
                    FirstEpochPublishAttemptV2.RemoteWon -> Unit
                    FirstEpochPublishAttemptV2.RebuildAfterStale -> {
                        // Ordinary sync must not invent a new successor genesis here;
                        // first-epoch rebuild only happens in initializeWorkspace.
                        if (isGenesisLocalProductDraft(recovered.prepared) &&
                            protocolStore.loadAuthoritativeEpoch() == null &&
                            runCatching(remote::loadEpochPointer).getOrNull() == null
                        ) {
                            when (
                                val rebuilt = prepareAndPublishGenesis(
                                    remote = remote,
                                    workspaceKey = workspaceKey,
                                    writerDeviceId = writerDeviceId,
                                )
                            ) {
                                FirstEpochPublishAttemptV2.Published -> Unit
                                FirstEpochPublishAttemptV2.RemoteWon -> Unit
                                FirstEpochPublishAttemptV2.RebuildAfterStale ->
                                    return ManualSyncResult.failure(
                                        mode,
                                        "Local product state kept changing during first-epoch publish; try Sync again.",
                                    )
                                is FirstEpochPublishAttemptV2.KeepPreparingAndStop ->
                                    return ManualSyncResult.failure(mode, rebuilt.safeMessage)
                                is FirstEpochPublishAttemptV2.Failed ->
                                    return ManualSyncResult.failure(mode, rebuilt.safeMessage)
                            }
                        }
                    }
                    is FirstEpochPublishAttemptV2.KeepPreparingAndStop ->
                        return ManualSyncResult.failure(mode, attempt.safeMessage)
                    is FirstEpochPublishAttemptV2.Failed ->
                        return ManualSyncResult.failure(mode, attempt.safeMessage)
                }
            }
        }
        val authorityBeforePush = protocolStore.loadActiveEpoch(remote.remoteProfile)
        if (authorityBeforePush != null &&
            authorityBeforePush.lifecycle == SyncEpochLifecycleV2.ACTIVE &&
            authorityBeforePush.health == SyncEpochHealthV2.HEALTHY
        ) {
            val nowMillis = clock().toEpochMilliseconds()
            val retainedPriorEpochs = buildSet {
                protocolStore.loadAllEpochs()
                    .filter { epoch ->
                        epoch.lifecycle == SyncEpochLifecycleV2.READ_ONLY &&
                            epoch.health == SyncEpochHealthV2.HEALTHY &&
                            epoch.retainUntilEpochMilliseconds?.let { it > nowMillis } != false
                    }
                    .forEach(::add)
            }.toList().sortedWith(compareBy({ it.remoteProfile }, { it.descriptor.syncEpochId }))
            for (sourceEpoch in retainedPriorEpochs) {
                val sourceRemote = if (sourceEpoch.remoteProfile == remote.remoteProfile &&
                    sourceEpoch.authorityBindingId == remote.authorityBindingId
                ) {
                    remote
                } else {
                    runCatching { retainedEpochRemoteProvider.open(sourceEpoch) }
                        .getOrElse {
                            return ManualSyncResult.failure(
                                mode,
                                "Sync V2 paused current-epoch push because a retained prior authority " +
                                    "could not be opened safely: ${it.safeRuntimeMessage()}",
                            )
                        }
                }
                if (sourceRemote == null || sourceRemote.remoteProfile != sourceEpoch.remoteProfile ||
                    sourceEpoch.authorityBindingId?.let { it != sourceRemote.authorityBindingId } == true
                ) {
                    return ManualSyncResult.failure(
                        mode,
                        "Sync V2 paused current-epoch push because a retained prior authority cannot be authenticated for late-writer monitoring.",
                    )
                }
                val monitor = WorkspacePriorEpochRemoteImporterV2(
                    localRepository,
                    workspaceKey,
                    writerDeviceId,
                    sourceRemote,
                    protocolStore,
                    clock,
                )
                when (val imported = monitor.importUntilStable(sourceEpoch.descriptor.syncEpochId)) {
                    is WorkspacePriorEpochRemoteImportResultV2.Imported -> Unit
                    is WorkspacePriorEpochRemoteImportResultV2.Blocked -> return ManualSyncResult.failure(
                        mode,
                        "Sync V2 paused current-epoch push while checking a retained epoch: ${imported.safeMessage}",
                    )
                }
            }
        }
        var summary = coordinator(workspaceKey, writerDeviceId, remote).syncOnce()
        if (summary.status == SyncCoordinatorStatusV2.SUCCESS) {
            collectObsoleteCheckpointDraftsAfterAuthenticatedPointer(
                remote = remote,
                workspaceKey = workspaceKey,
            )
            val activeEpochId = summary.epochId
            val oldEpochsWithPending = protocolStore.loadAllEpochs()
                .filter {
                    it.descriptor.syncEpochId != activeEpochId &&
                        it.lifecycle == SyncEpochLifecycleV2.READ_ONLY
                }
                .filter { old ->
                    workspaceStore(workspaceKey, writerDeviceId, old.remoteProfile, old.descriptor.syncEpochId)
                        .loadPending(old.remoteProfile)
                        .isNotEmpty()
                }
            for (old in oldEpochsWithPending) {
                when (val imported = WorkspacePriorEpochImporterV2(
                    localRepository,
                    workspaceKey,
                    writerDeviceId,
                    remote.remoteProfile,
                    protocolStore,
                    clock,
                ).importUncheckpointed(old.descriptor.syncEpochId, old.remoteProfile)) {
                    is WorkspacePriorEpochImportResultV2.Blocked -> return ManualSyncResult.failure(
                        mode,
                        "Sync V2 prior-epoch import stopped safely: ${imported.safeMessage}",
                        summary.pushedObjects,
                        summary.pulledObjects,
                        summary.activeConflicts,
                    )
                    is WorkspacePriorEpochImportResultV2.Imported -> Unit
                }
            }
            if (oldEpochsWithPending.isNotEmpty()) {
                summary = coordinator(workspaceKey, writerDeviceId, remote).syncOnce()
            }
        }
        return if (summary.status == SyncCoordinatorStatusV2.SUCCESS) {
            if (restoredBackupPending) {
                // Coordinator success proves pointer authentication, pull-
                // first replay, stable frontier, exact outbox acks, and a
                // durable current cursor before this flag is cleared.
                protocolStore.clearBackupReconciliation()
            }
            ManualSyncResult.success(
                mode,
                summary.pushedObjects,
                summary.pulledObjects,
                summary.activeConflicts,
                "Sync V2 complete: pushed ${summary.pushedObjects}, pulled ${summary.pulledObjects}, " +
                    "active conflicts ${summary.activeConflicts}." +
                    if (restoredBackupPending) " Restored backup reconciliation completed." else "",
            )
        } else {
            ManualSyncResult.failure(
                mode,
                "Sync V2 ${if (summary.status == SyncCoordinatorStatusV2.BLOCKED) "is blocked" else "failed"} safely: " +
                    "${summary.safeMessage ?: summary.safeErrorCode ?: "unknown error"}.",
                summary.pushedObjects,
                summary.pulledObjects,
                summary.activeConflicts,
            )
        }
    }

    private fun coordinator(
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
        remote: WorkspaceSyncRemoteV2,
    ): WorkspaceSyncCoordinatorV2 {
        val authorityBeforeBootstrap = protocolStore.loadAuthoritativeEpoch()
        return WorkspaceSyncCoordinatorV2(
            localRepository = localRepository,
            workspaceKey = workspaceKey,
            localWriterDeviceId = writerDeviceId,
            remote = remote,
            protocolStore = protocolStore,
            clock = clock,
            authorityMutationCoordinator = authorityMutationCoordinator,
            bootstrapCommitHook = {
                preserveLocalStateAtBootstrapCommit(
                    workspaceKey,
                    writerDeviceId,
                    remote.remoteProfile,
                    authorityBeforeBootstrap,
                )
            },
        )
    }

    private fun preserveLocalStateAtBootstrapCommit(
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
        targetRemoteProfile: String,
        priorAuthority: StoredSyncEpochV2?,
    ): Pair<String, String>? =
        if (priorAuthority == null) {
            when (val imported = WorkspaceJoiningDeviceImporterV2(
                localRepository = localRepository,
                settingsRepository = settingsRepository,
                workspaceKey = workspaceKey,
                writerDeviceId = writerDeviceId,
                remoteProfile = targetRemoteProfile,
                protocolStore = protocolStore,
                clock = clock,
            ).captureLocalProductState()) {
                is WorkspaceJoiningImportResultV2.Captured -> null
                is WorkspaceJoiningImportResultV2.Blocked ->
                    imported.safeErrorCode to imported.safeMessage
            }
        } else {
            when (val imported = WorkspacePriorEpochImporterV2(
                localRepository,
                workspaceKey,
                writerDeviceId,
                targetRemoteProfile,
                protocolStore,
                clock,
            ).importUncheckpointed(
                priorAuthority.descriptor.syncEpochId,
                priorAuthority.remoteProfile,
            )) {
                is WorkspacePriorEpochImportResultV2.Imported -> null
                is WorkspacePriorEpochImportResultV2.Blocked ->
                    imported.safeErrorCode to imported.safeMessage
            }
        }

    private fun activeContext(
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
        remoteProfile: String,
    ): ActiveWorkspaceSystemV2 = WorkspaceSystemV2ContextProvider(
        localRepository,
        { workspaceKey },
        { writerDeviceId },
        { remoteProfile },
    ).requireActive()

    private fun workspaceStore(
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
        remoteProfile: String,
        epochId: String,
    ): SqlDelightWorkspaceEntityStoreV2 {
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(workspaceKey, epochId),
        )
        val validator = WorkspaceEntityValidatorV2(materializer)
        val wire = WorkspaceEntityWireCodecV2(materializer, validator)
        val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
        return SqlDelightWorkspaceEntityStoreV2(
            localRepository.database,
            epochId,
            WorkspaceEntityCausalityEngineV2(materializer, validator),
            materializer,
            wire,
            WorkspaceOutboxEncoderV2 { version, mutationId ->
                PreparedWorkspaceOutboxObjectV2(
                    writerDeviceId,
                    cipher.encodeJson(cipher.encryptEntity(version, mutationId, writerDeviceId, wire.encode(version))),
                )
            },
        )
    }

    private fun frontierIsApplied(
        store: SqlDelightWorkspaceEntityStoreV2,
        remoteProfile: String,
        frontiers: List<SyncStreamFrontierV2>,
    ): Boolean = frontiers.all { frontier ->
        store.loadCursor(remoteProfile, frontier.streamId)?.cursorValue == frontier.cursorValue
    }

    private fun normalizedWriterIdOrFailure(): Result<String> =
        runCatching {
            val authorityWriter = protocolStore.loadLocalAuthority()?.takeIf {
                it.remoteProfile == mode.remoteProfileV2()
            }?.localWriterDeviceId
            normalizeWriterDeviceIdV2(authorityWriter ?: writerDeviceIdProvider())
        }

    private fun createRemoteOrFailure(): Result<WorkspaceSyncRemoteV2> = runCatching(transportFactory::create)

    private fun checkpointPublisher(
        remote: WorkspaceSyncRemoteV2,
        workspaceKey: WorkspaceMasterKey? = null,
        writerDeviceId: String? = null,
        prepared: PreparedWorkspaceEpochCheckpointV2? = null,
        requireNoPendingSource: Boolean = false,
    ): WorkspaceCheckpointPublisherV2 {
        val genesisDraft = prepared?.takeIf(::isGenesisLocalProductDraft)
        val sourceDraft = prepared?.takeIf { genesisDraft == null }
        val snapshotValidator: () -> Boolean = when {
            genesisDraft != null && workspaceKey != null && writerDeviceId != null ->
                fun(): Boolean =
                    !isGenesisLocalProductDraftStale(workspaceKey, writerDeviceId, genesisDraft)
            sourceDraft != null && workspaceKey != null && writerDeviceId != null ->
                fun(): Boolean =
                    !isV2SourceCheckpointDraftStale(
                        workspaceKey,
                        writerDeviceId,
                        sourceDraft,
                        requireNoPendingSource,
                    )
            else -> fun(): Boolean = true
        }
        return WorkspaceCheckpointPublisherV2(
            localRepository = localRepository,
            remote = remote,
            protocolStore = protocolStore,
            onProgress = onPublishProgress,
            localSnapshotStillMatches = snapshotValidator,
            commitPointerBarrier = { commit ->
                authorityMutationCoordinator.productAccess(commit)
            },
        )
    }

    private fun prepareAndPublishGenesis(
        remote: WorkspaceSyncRemoteV2,
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
    ): FirstEpochPublishAttemptV2 {
        val genesis = WorkspaceGenesisCheckpointServiceV2(
            localRepository = localRepository,
            settingsRepository = settingsRepository,
            workspaceKey = workspaceKey,
            writerDeviceId = writerDeviceId,
            remoteProfile = remote.remoteProfile,
            clock = clock,
        )
        return when (val prepared = genesis.prepare()) {
            is WorkspaceGenesisCheckpointResultV2.Blocked ->
                FirstEpochPublishAttemptV2.Failed(prepared.safeMessage)
            is WorkspaceGenesisCheckpointResultV2.Prepared -> {
                when (
                    val persisted = WorkspaceCheckpointPersistenceV2(
                        localRepository, workspaceKey, writerDeviceId, protocolStore,
                    ).persist(prepared.checkpoint)
                ) {
                    is WorkspaceCheckpointPersistResultV2.Rejected ->
                        FirstEpochPublishAttemptV2.Failed(persisted.safeMessage)
                    is WorkspaceCheckpointPersistResultV2.Ready ->
                        publishRecoveredOrFreshDraft(
                            remote = remote,
                            workspaceKey = workspaceKey,
                            writerDeviceId = writerDeviceId,
                            prepared = prepared.checkpoint,
                        )
                }
            }
        }
    }

    private fun publishRecoveredOrFreshDraft(
        remote: WorkspaceSyncRemoteV2,
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
        prepared: PreparedWorkspaceEpochCheckpointV2,
    ): FirstEpochPublishAttemptV2 {
        if (isGenesisLocalProductDraft(prepared) &&
            isGenesisLocalProductDraftStale(workspaceKey, writerDeviceId, prepared)
        ) {
            discardNeverAuthoritativeEpoch(
                remote.remoteProfile,
                prepared.descriptor.syncEpochId,
                "prepared_checkpoint_stale",
                "Local product state changed after the prepared first-epoch checkpoint.",
            )
            return FirstEpochPublishAttemptV2.RebuildAfterStale
        }
        return when (
            val published = checkpointPublisher(
                remote = remote,
                workspaceKey = workspaceKey,
                writerDeviceId = writerDeviceId,
                prepared = prepared,
            ).publish(prepared)
        ) {
            is WorkspaceCheckpointPublishResultV2.Published -> FirstEpochPublishAttemptV2.Published
            is WorkspaceCheckpointPublishResultV2.LostRace -> {
                if (published.currentPointer == null ||
                    published.currentPointer.objectDigest == prepared.pointer.previousPointerDigest
                ) {
                    FirstEpochPublishAttemptV2.KeepPreparingAndStop(
                        "Checkpoint pointer commit failed transiently; run Sync again to resume the same prepared checkpoint.",
                    )
                } else {
                    FirstEpochPublishAttemptV2.RemoteWon
                }
            }
            is WorkspaceCheckpointPublishResultV2.Rejected -> {
                if (published.safeErrorCode == "prepared_checkpoint_stale") {
                    discardNeverAuthoritativeEpoch(
                        remote.remoteProfile,
                        prepared.descriptor.syncEpochId,
                        published.safeErrorCode,
                        published.safeMessage,
                    )
                    FirstEpochPublishAttemptV2.RebuildAfterStale
                } else {
                    FirstEpochPublishAttemptV2.Failed(published.safeMessage)
                }
            }
        }
    }

    /**
     * True only for a first-epoch draft built from local product rows
     * (not rollover / recovery / remote-migration successors).
     */
    private fun isGenesisLocalProductDraft(prepared: PreparedWorkspaceEpochCheckpointV2): Boolean {
        if (prepared.descriptor.previousEpochId != null) return false
        if (prepared.pointer.previousPointerDigest != null) return false
        val localProductPrefix = "local-product:"
        return prepared.entities.any { entity ->
            val provenance = entity.version.provenance ?: return@any false
            provenance.type == WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT &&
                provenance.sourceProfile?.startsWith(localProductPrefix) == true
        }
    }

    /**
     * Genesis drafts are stale when the current local-product inventory fingerprint
     * no longer matches the frozen EPOCH_CHECKPOINT source digests.
     * Non-genesis drafts always return false (callers must use other validation).
     */
    private fun isGenesisLocalProductDraftStale(
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
        prepared: PreparedWorkspaceEpochCheckpointV2,
    ): Boolean {
        if (!isGenesisLocalProductDraft(prepared)) return false
        val preparedFingerprint = preparedGenesisSourceFingerprint(prepared)
        val currentFingerprint = runCatching {
            WorkspaceGenesisCheckpointServiceV2(
                localRepository = localRepository,
                settingsRepository = settingsRepository,
                workspaceKey = workspaceKey,
                writerDeviceId = writerDeviceId,
                remoteProfile = prepared.remoteProfile,
                clock = clock,
            ).inventory().sourceHeads
                .map { head -> genesisSourceFingerprint(head.entityType, head.entityId, head.sourceObjectDigest) }
                .toSet()
        }.getOrElse { return true }
        return preparedFingerprint != currentFingerprint
    }

    private fun preparedGenesisSourceFingerprint(prepared: PreparedWorkspaceEpochCheckpointV2): Set<String> =
        prepared.entities.mapNotNull { entity ->
            val provenance = entity.version.provenance ?: return@mapNotNull null
            if (provenance.type != WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT) return@mapNotNull null
            if (provenance.sourceProfile?.startsWith("local-product:") != true) return@mapNotNull null
            val digest = provenance.sourceDigest ?: return@mapNotNull null
            genesisSourceFingerprint(entity.version.entityType, entity.version.entityId, digest)
        }.toSet()

    private fun isV2SourceCheckpointDraftStale(
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
        prepared: PreparedWorkspaceEpochCheckpointV2,
        requireNoPendingSource: Boolean,
    ): Boolean {
        val sourceEpochId = prepared.descriptor.previousEpochId ?: return false
        val expected = prepared.checkpointSourceIdentitiesV2()
        if (expected.isEmpty()) return true
        return runCatching {
            val sourceEpoch = protocolStore.loadAllEpochs().singleOrNull {
                it.descriptor.syncEpochId == sourceEpochId
            } ?: return@runCatching false
            val contexts = WorkspaceSystemV2ContextProvider(
                localRepository,
                { workspaceKey },
                { writerDeviceId },
                { sourceEpoch.remoteProfile },
            )
            val context = when (sourceEpoch.lifecycle) {
                SyncEpochLifecycleV2.READ_ONLY ->
                    contexts.openRetainedEpochOrNull(sourceEpoch, workspaceKey)
                else ->
                    contexts.openOrNull()?.takeIf { it.syncEpochId == sourceEpochId }
            } ?: return@runCatching false
            checkpointSourceStateStillMatchesV2(
                context.store,
                sourceEpoch.remoteProfile,
                expected,
                requireNoPendingSource,
            )
        }.getOrDefault(false).not()
    }

    private fun genesisSourceFingerprint(
        entityType: WorkspaceEntityTypeV2,
        entityId: String,
        sourceObjectDigest: String,
    ): String = "${entityType.wireValue}|$entityId|$sourceObjectDigest"

    /** Marks a never-authoritative draft abandoned while retaining exact cleanup identities. */
    private fun discardNeverAuthoritativeEpoch(
        remoteProfile: String,
        epochId: String,
        safeErrorCode: String,
        safeErrorMessage: String,
    ) {
        val epoch = protocolStore.loadEpoch(remoteProfile, epochId) ?: return
        if (epoch.lifecycle == SyncEpochLifecycleV2.PREPARING ||
            epoch.lifecycle == SyncEpochLifecycleV2.ABANDONED
        ) {
            if (epoch.lifecycle == SyncEpochLifecycleV2.PREPARING) {
                protocolStore.abandonPreparingEpoch(
                    remoteProfile,
                    epochId,
                    safeErrorCode,
                    safeErrorMessage,
                )
            }
        }
    }

    /**
     * Runs only after the coordinator authenticated and activated the current
     * pointer. A draft whose expected predecessor is still current remains
     * resumable; every other never-authoritative draft is remotely collected
     * before its local cleanup identities are removed.
     */
    private fun collectObsoleteCheckpointDraftsAfterAuthenticatedPointer(
        remote: WorkspaceSyncRemoteV2,
        workspaceKey: WorkspaceMasterKey,
    ) {
        val active = protocolStore.loadActiveEpoch(remote.remoteProfile) ?: return
        val loader = WorkspaceCheckpointDraftCleanupLoaderV2(localRepository, workspaceKey)
        protocolStore.loadEpochs(remote.remoteProfile)
            .filter {
                it.descriptor.syncEpochId != active.descriptor.syncEpochId &&
                    it.activatedAtEpochMilliseconds == null &&
                    (it.lifecycle == SyncEpochLifecycleV2.PREPARING ||
                        it.lifecycle == SyncEpochLifecycleV2.ABANDONED)
            }
            .forEach { epoch ->
                val draft = loader.load(epoch).getOrNull() ?: return@forEach
                if (draft.pointer.previousPointerDigest == active.descriptorDigest) {
                    return@forEach
                }
                if (epoch.lifecycle == SyncEpochLifecycleV2.PREPARING) {
                    protocolStore.abandonPreparingEpoch(
                        remote.remoteProfile,
                        epoch.descriptor.syncEpochId,
                        "epoch_pointer_compare_and_set_lost",
                        "Another authenticated checkpoint won the pointer compare-and-set.",
                    )
                }
                when (runCatching { remote.cleanupCheckpointDraft(draft) }.getOrNull()) {
                    is WorkspaceCheckpointDraftCleanupResultV2.Deleted ->
                        epochRetention.collectAbandonedNeverAuthoritativeEpoch(
                            remote.remoteProfile,
                            epoch.descriptor.syncEpochId,
                        )
                    is WorkspaceCheckpointDraftCleanupResultV2.Retained,
                    null,
                    -> Unit
                }
            }
    }

    private fun failedInitialization(
        message: String,
        conflicts: Int = 0,
        pushedObjects: Int = 0,
        pulledObjects: Int = 0,
    ) = ManualSyncResult.failure(
        mode = mode,
        message = message,
        pushedObjects = pushedObjects,
        pulledObjects = pulledObjects,
        conflicts = conflicts,
    )

    private companion object {
        const val MAX_FRONTIER_STABILIZATION_ROUNDS_V2 = 8
    }
}

/** Outcome of publishing a recovered or freshly prepared first-epoch (or other) draft. */
private sealed interface FirstEpochPublishAttemptV2 {
    data object Published : FirstEpochPublishAttemptV2
    data object RemoteWon : FirstEpochPublishAttemptV2
    data object RebuildAfterStale : FirstEpochPublishAttemptV2
    data class KeepPreparingAndStop(val safeMessage: String) : FirstEpochPublishAttemptV2
    data class Failed(val safeMessage: String) : FirstEpochPublishAttemptV2
}

internal fun Throwable.safeRuntimeMessage(): String =
    (message ?: "Sync V2 setup failed safely.")
        .replace(Regex("(?i)(bearer|token|password|secret)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
        .take(500)

private fun SyncMode.remoteProfileV2(): String = when (this) {
    SyncMode.WebDav -> SyncRemoteProfileV2.WEB_DAV.wireValue
    SyncMode.SelfHosted -> SyncRemoteProfileV2.SELF_HOSTED.wireValue
    SyncMode.Off -> error("SyncMode.Off has no network remote profile.")
}
