@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.SyncMode
import saien.someday.sync.WorkspaceLifecycleCoordinator
import kotlin.time.Clock
import kotlin.time.Instant

fun interface SyncRemoteTransportFactoryV2 {
    fun create(): WorkspaceSyncRemoteV2
}

/**
 * Internal entity-DAG runtime embedded by the product-facing System V3 coordinator.
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
    private val workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator,
    private val clock: () -> Instant = { Clock.System.now() },
    private val onPublishProgress: (WorkspaceCheckpointPublishProgressV2) -> Unit = {},
    /** Required product boundary; System V3 supplies its media reachability gate. */
    private val beforeEntityPublication: (List<WorkspaceEntityVersionV2>) -> Unit,
) : ManualSyncRunner {
    private val protocolStore = SqlDelightSyncProtocolStoreV2(localRepository.database)
    private val checkpointCleanup = WorkspaceCheckpointCleanupServiceV2(localRepository, protocolStore)

    override fun run(): ManualSyncResult =
        workspaceLifecycleCoordinator.exclusive {
            runWithWorkspaceLifecycleLock()
        }

    private fun runWithWorkspaceLifecycleLock(): ManualSyncResult {
        val configured = settingsRepository.load().syncConfiguration
        if (configured.mode != mode) {
            return ManualSyncResult.failure(mode, ManualSyncReason.ProviderChanged)
        }
        val authority = protocolStore.loadAuthoritativeEpoch()
        if (authority != null && authority.remoteProfile != mode.remoteProfileV2()) {
            return ManualSyncResult.failure(
                mode = mode,
                reason = ManualSyncReason.AuthorityMismatch,
            )
        }
        if (authority == null) {
            return initializeWorkspace()
        }
        return runV2()
    }

    private fun initializeWorkspace(): ManualSyncResult {
        val configured = settingsRepository.load()
        if (configured.syncConfiguration.mode != mode) {
            return failedInitialization(reason = ManualSyncReason.ProviderChanged)
        }
        val workspaceKey = workspaceKeyProvider()
            ?: return failedInitialization(reason = ManualSyncReason.WorkspaceLocked)
        val writerDeviceId = normalizedWriterIdOrFailure()
            .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
        val remote = createRemoteOrFailure()
            .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
        val capabilities = runCatching(remote::capabilities)
            .getOrElse { return failedInitialization(it.safeRuntimeMessage()) }
        capabilities.incompatibility()?.let { code ->
            return failedInitialization("Remote entity-sync capabilities are incompatible ($code).")
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
                    when (val bound = protocolStore.persistPreparingEpoch(
                        recovered.prepared.remoteProfile,
                        recovered.prepared.descriptor,
                        recovered.prepared.pointerObject.objectDigest,
                        remote.authorityBindingId,
                        writerDeviceId,
                    )) {
                        is SyncEpochPersistResultV2.ImmutableMismatch ->
                            return failedInitialization(bound.safeMessage)
                        is SyncEpochPersistResultV2.AlreadyStored,
                        is SyncEpochPersistResultV2.Stored,
                        -> Unit
                    }
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
                        diagnosticMessage =
                            "A prepared first-epoch checkpoint is waiting for pointer commit; run Sync again to resume.",
                        reason = ManualSyncReason.RetryRequired,
                    )
                }
                // First epoch: checkpoint from local product state (or rebuild after stale prepare).
                // Still inside workspaceLifecycleCoordinator.exclusive from run().
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
                                    diagnosticMessage =
                                        "Local product state kept changing during first-epoch publish; try Sync again.",
                                    reason = ManualSyncReason.RetryRequired,
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

        if (pointer != null && localDraftHasSemanticChanges(pointer)) {
            return failedInitialization(
                diagnosticMessage = "This server workspace already has an authoritative history. " +
                    "The non-empty local workspace was not merged or replaced.",
                reason = ManualSyncReason.RemoteHistoryConflict,
            )
        }

        var pushedObjects = 0
        var pulledObjects = 0
        var summary = coordinator(workspaceKey, writerDeviceId, remote).syncOnce()
        pushedObjects += summary.pushedObjects
        pulledObjects += summary.pulledObjects
        if (summary.status != SyncCoordinatorStatusV2.SUCCESS) {
            return failedInitialization(
                diagnosticMessage =
                    summary.safeMessage ?: "Sync initialization stopped before local bootstrap completed.",
                reason = if (summary.status == SyncCoordinatorStatusV2.BLOCKED) {
                    ManualSyncReason.Blocked
                } else {
                    ManualSyncReason.Failed
                },
                conflicts = summary.activeConflicts,
                pushedObjects = pushedObjects,
                pulledObjects = pulledObjects,
            )
        }
        collectObsoleteCheckpointDraftsAfterAuthenticatedPointer(
            remote = remote,
            workspaceKey = workspaceKey,
        )

        return ManualSyncResult.success(
            mode = mode,
            reason = ManualSyncReason.Initialized,
            pushedObjects = pushedObjects,
            pulledObjects = pulledObjects,
            conflicts = summary.activeConflicts,
        )
    }

    private fun runV2(): ManualSyncResult {
        val workspaceKey = workspaceKeyProvider()
            ?: return ManualSyncResult.failure(mode, ManualSyncReason.WorkspaceLocked)
        val writerDeviceId = normalizedWriterIdOrFailure()
            .getOrElse {
                return ManualSyncResult.failure(
                    mode,
                    ManualSyncReason.Failed,
                    it.safeRuntimeMessage(),
                )
            }
        val remote = createRemoteOrFailure()
            .getOrElse {
                return ManualSyncResult.failure(
                    mode,
                    ManualSyncReason.Failed,
                    it.safeRuntimeMessage(),
                )
            }
        return runV2(workspaceKey, writerDeviceId, remote)
    }

    private fun runV2(
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
        remote: WorkspaceSyncRemoteV2,
    ): ManualSyncResult {
        val pointer = runCatching(remote::loadEpochPointer)
            .getOrElse {
                return ManualSyncResult.failure(
                    mode,
                    ManualSyncReason.Failed,
                    it.safeRuntimeMessage(),
                )
            }
        when (val recovered = WorkspacePreparedCheckpointRecoveryV2(
            localRepository,
            workspaceKey,
            protocolStore,
        ).loadCompatible(remote.remoteProfile, pointer)) {
            WorkspacePreparedCheckpointLoadResultV2.None -> Unit
            is WorkspacePreparedCheckpointLoadResultV2.Rejected -> {
                if (pointer?.syncEpochId == recovered.epochId) {
                    return ManualSyncResult.failure(
                        mode = mode,
                        reason = ManualSyncReason.CheckpointInvalid,
                        diagnosticMessage =
                            "The committed sync checkpoint cannot be reconstructed locally: ${recovered.safeMessage}",
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
                                        mode = mode,
                                        reason = ManualSyncReason.RetryRequired,
                                    )
                                is FirstEpochPublishAttemptV2.KeepPreparingAndStop ->
                                    return ManualSyncResult.failure(
                                        mode,
                                        ManualSyncReason.RetryRequired,
                                        rebuilt.safeMessage,
                                    )
                                is FirstEpochPublishAttemptV2.Failed ->
                                    return ManualSyncResult.failure(
                                        mode,
                                        ManualSyncReason.Failed,
                                        rebuilt.safeMessage,
                                    )
                            }
                        }
                    }
                    is FirstEpochPublishAttemptV2.KeepPreparingAndStop ->
                        return ManualSyncResult.failure(
                            mode,
                            ManualSyncReason.RetryRequired,
                            attempt.safeMessage,
                        )
                    is FirstEpochPublishAttemptV2.Failed ->
                        return ManualSyncResult.failure(
                            mode,
                            ManualSyncReason.Failed,
                            attempt.safeMessage,
                        )
                }
            }
        }
        val summary = coordinator(workspaceKey, writerDeviceId, remote).syncOnce()
        return if (summary.status == SyncCoordinatorStatusV2.SUCCESS) {
            ManualSyncResult.success(
                mode,
                summary.pushedObjects,
                summary.pulledObjects,
                summary.activeConflicts,
                ManualSyncReason.Completed,
            )
        } else {
            ManualSyncResult.failure(
                mode = mode,
                reason = if (summary.status == SyncCoordinatorStatusV2.BLOCKED) {
                    ManualSyncReason.Blocked
                } else {
                    ManualSyncReason.Failed
                },
                diagnosticMessage = summary.safeMessage ?: summary.safeErrorCode,
                pushedObjects = summary.pushedObjects,
                pulledObjects = summary.pulledObjects,
                conflicts = summary.activeConflicts,
            )
        }
    }

    private fun coordinator(
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
        remote: WorkspaceSyncRemoteV2,
    ): WorkspaceSyncCoordinatorV2 {
        return WorkspaceSyncCoordinatorV2(
            localRepository = localRepository,
            workspaceKey = workspaceKey,
            localWriterDeviceId = writerDeviceId,
            remote = remote,
            protocolStore = protocolStore,
            clock = clock,
            workspaceLifecycleCoordinator = workspaceLifecycleCoordinator,
            beforeEntityPublication = beforeEntityPublication,
        )
    }

    private fun localDraftHasSemanticChanges(remotePointer: EncryptedWorkspaceObjectV2): Boolean {
        val draft = protocolStore.loadAllEpochs().singleOrNull {
            it.lifecycle == SyncEpochLifecycleV2.PREPARING &&
                it.remoteProfile == SyncRemoteProfileV2.SELF_HOSTED.wireValue
        } ?: return false
        if (draft.descriptorDigest == remotePointer.objectDigest) return false
        val key = workspaceKeyProvider() ?: return true
        val context = WorkspaceSystemV2ContextProvider(
            localRepository,
            { key },
            { normalizeWriterDeviceIdV2(localRepository.localDeviceId) },
            { SyncRemoteProfileV2.SELF_HOSTED.wireValue },
        ).openOrNull() ?: return true
        return context.store.loadPending(context.remoteProfile).isNotEmpty() ||
            context.store.loadEntityKeys().any {
                it.entityType == WorkspaceEntityTypeV2.NOTEBOOK ||
                    it.entityType == WorkspaceEntityTypeV2.NOTE
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
    ): WorkspaceCheckpointPublisherV2 {
        val genesisDraft = prepared?.takeIf(::isGenesisLocalProductDraft)
        val snapshotValidator: () -> Boolean = when {
            genesisDraft != null && workspaceKey != null && writerDeviceId != null ->
                fun(): Boolean =
                    !isGenesisLocalProductDraftStale(workspaceKey, writerDeviceId, genesisDraft)
            else -> fun(): Boolean = true
        }
        return WorkspaceCheckpointPublisherV2(
            localRepository = localRepository,
            remote = remote,
            protocolStore = protocolStore,
            onProgress = onPublishProgress,
            beforeEntityPublication = beforeEntityPublication,
            localSnapshotStillMatches = snapshotValidator,
            commitPointerBarrier = { commit ->
                workspaceLifecycleCoordinator.productAccess(commit)
            },
        )
    }

    private fun prepareAndPublishGenesis(
        remote: WorkspaceSyncRemoteV2,
        workspaceKey: WorkspaceMasterKey,
        writerDeviceId: String,
    ): FirstEpochPublishAttemptV2 {
        val genesis = WorkspaceGenesisCheckpointServiceV2(
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
                        localRepository,
                        workspaceKey,
                        writerDeviceId,
                        protocolStore,
                        remote.authorityBindingId,
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

    /** True only for a locally prepared first-generation checkpoint. */
    private fun isGenesisLocalProductDraft(prepared: PreparedWorkspaceEpochCheckpointV2): Boolean {
        if (prepared.descriptor.previousEpochId != null) return false
        if (prepared.pointer.previousPointerDigest != null) return false
        val localDraftPrefix = "local-draft:"
        return prepared.entities.any { entity ->
            val provenance = entity.version.provenance ?: return@any false
            provenance.type == WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT &&
                provenance.sourceProfile?.startsWith(localDraftPrefix) == true
        }
    }

    /**
     * Genesis drafts are stale when the current local-draft inventory fingerprint
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
            if (provenance.sourceProfile?.startsWith("local-draft:") != true) return@mapNotNull null
            val digest = provenance.sourceDigest ?: return@mapNotNull null
            genesisSourceFingerprint(entity.version.entityType, entity.version.entityId, digest)
        }.toSet()

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

    /** Cleans only never-authoritative first-generation CAS drafts. */
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
                if (draft.pointer.previousPointerDigest == active.descriptorDigest) return@forEach
                if (epoch.lifecycle == SyncEpochLifecycleV2.PREPARING) {
                    protocolStore.abandonPreparingEpoch(
                        remote.remoteProfile,
                        epoch.descriptor.syncEpochId,
                        "epoch_pointer_compare_and_set_lost",
                        "Another authenticated checkpoint won the pointer compare-and-set.",
                    )
                }
                if (runCatching { remote.cleanupCheckpointDraft(draft) }.getOrNull()
                    is WorkspaceCheckpointDraftCleanupResultV2.Deleted
                ) {
                    checkpointCleanup.collect(remote.remoteProfile, epoch.descriptor.syncEpochId)
                }
            }
    }

    private fun failedInitialization(
        diagnosticMessage: String? = null,
        reason: ManualSyncReason = ManualSyncReason.Failed,
        conflicts: Int = 0,
        pushedObjects: Int = 0,
        pulledObjects: Int = 0,
    ) = ManualSyncResult.failure(
        mode = mode,
        reason = reason,
        diagnosticMessage = diagnosticMessage,
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
    (message ?: "System V3 sync setup failed safely.")
        .replace(Regex("(?i)(bearer|token|password|secret)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
        .take(500)

private fun SyncMode.remoteProfileV2(): String = when (this) {
    SyncMode.SelfHosted -> SyncRemoteProfileV2.SELF_HOSTED.wireValue
    SyncMode.Off -> error("SyncMode.Off has no network remote profile.")
}
