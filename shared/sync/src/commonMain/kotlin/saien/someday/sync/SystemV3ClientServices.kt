package saien.someday.sync

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataImportSummary
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.media.findSomedayAssetIds
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.LocalWorkspaceAdoptionPolicy
import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspacePairingReason
import saien.someday.domain.settings.authorityBindingId
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.SyncRemoteTransportFactoryV2
import saien.someday.sync.causality.v2.SyncV2RuntimeService
import saien.someday.sync.causality.v2.SystemV2NotesRepository
import saien.someday.sync.causality.v2.NoteContentV2
import saien.someday.sync.causality.v2.SyncEpochLifecycleV2
import saien.someday.sync.causality.v2.SyncEpochHealthV2
import saien.someday.sync.causality.v2.WorkspaceLocalDataTransferV2
import saien.someday.sync.causality.v2.SystemV2ClientSettingsRepository
import saien.someday.sync.causality.v2.normalizeWriterDeviceIdV2
import saien.someday.sync.causality.v2.ensureWorkspaceLocalDraftV2
import saien.someday.sync.causality.v2.localWorkspaceAdoptionRefusalReasonV2
import saien.someday.sync.causality.v2.discardEmptyLocalWorkspaceDraftForAdoptionV2
import saien.someday.sync.selfhosted.RefreshingSelfHostedSyncTransportV2
import saien.someday.sync.selfhosted.RefreshingSelfHostedSessionExecutor
import saien.someday.sync.selfhosted.SelfHostedSyncRemoteV2
import saien.someday.sync.selfhosted.SelfHostedMediaTransportV3
import saien.someday.sync.selfhosted.SelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedSyncTransportV2
import saien.someday.sync.selfhosted.SystemV3MediaCoordinator
import saien.someday.sync.selfhosted.ActiveWorkspaceSessionGuard
import saien.someday.sync.selfhosted.ActiveWorkspaceSessionRequirement

data class SystemV3ClientServices(
    val notesRepository: NotesRepository,
    val settingsRepository: ClientSettingsRepository,
    val manualSyncRunner: ManualSyncRunner,
    val selfHostedSessionExecutor: RefreshingSelfHostedSessionExecutor,
    val activeWorkspaceSessionGuard: ActiveWorkspaceSessionGuard,
    val localMediaAssetStore: AuthorityCoordinatedMediaAssetStore,
    val mediaCoordinator: SystemV3MediaCoordinator,
    val authorityMutationCoordinator: WorkspaceAuthorityMutationCoordinator,
    val workspaceAdoptionPolicy: LocalWorkspaceAdoptionPolicy,
    val discardEmptyDraftForWorkspaceAdoption: () -> Boolean,
    val bindAdoptedWorkspaceToCurrentSession: (WorkspaceJoinPackage, WorkspaceMasterKey, String) -> Boolean,
    val workspacePairingInviterReady: () -> Boolean,
    val localDataExportProvider: (kotlin.time.Instant) -> LocalDataExportDocument,
    val localDataImportProvider: (LocalDataExportDocument) -> LocalDataImportSummary,
)

fun createSystemV3ClientServices(
    localRepository: SqlDelightLocalDataRepository,
    settingsRepository: ClientSettingsRepository,
    workspaceKeyProvider: () -> WorkspaceMasterKey?,
    workspaceIdProvider: () -> String?,
    localMediaAssetStore: LocalMediaAssetStore,
    selfHostedTransport: SelfHostedSyncTransport,
    selfHostedTransportV2: SelfHostedSyncTransportV2,
    selfHostedMediaTransportV3: SelfHostedMediaTransportV3,
    selfHostedSessionStore: SelfHostedSessionCredentialStore,
): SystemV3ClientServices {
    val activeSelfHostedSessionExecutor = RefreshingSelfHostedSessionExecutor(
        authenticationTransport = selfHostedTransport,
        sessionStore = selfHostedSessionStore,
    )
    val authorityMutationCoordinator = WorkspaceAuthorityMutationCoordinator()
    val coordinatedMediaAssetStore = AuthorityCoordinatedMediaAssetStore(
        delegate = localMediaAssetStore,
        authorityMutationCoordinator = authorityMutationCoordinator,
    )
    val protocolStore = SqlDelightSyncProtocolStoreV2(localRepository.database)
    workspaceKeyProvider()?.let { key ->
        ensureWorkspaceLocalDraftV2(localRepository, settingsRepository, key)
    }
    val activeWorkspaceSessionGuard = ActiveWorkspaceSessionGuard {
        resolveActiveWorkspaceSessionRequirement(protocolStore, workspaceIdProvider)
    }
    val mediaCoordinator = SystemV3MediaCoordinator(
        localStore = coordinatedMediaAssetStore,
        transport = selfHostedMediaTransportV3,
        sessionStore = selfHostedSessionStore,
        workspaceKeyProvider = workspaceKeyProvider,
        workspaceIdProvider = workspaceIdProvider,
        activeWorkspaceSessionGuard = activeWorkspaceSessionGuard,
        sessionExecutor = activeSelfHostedSessionExecutor,
    )
    fun createSelfHostedRemoteV2(): SelfHostedSyncRemoteV2 {
        val credentials = selfHostedSessionStore.load()
            ?: error("Self-hosted session is missing; tokens redacted.")
        val workspaceId = workspaceIdProvider()?.trim()?.takeIf(String::isNotEmpty)
            ?: error("The current workspace id is unavailable.")
        activeWorkspaceSessionGuard.currentRequirement()?.let { requirement ->
            require(requirement.workspaceId == workspaceId) {
                "The local workspace scope does not match the bound publication authority."
            }
        }
        val binding = credentials.authorityBindingId
        activeWorkspaceSessionGuard.requireCompatible(credentials)
        selfHostedSessionStore.saveForAuthority(binding, credentials)
        val boundSessionStore = object : SelfHostedSessionCredentialStore {
            override fun load() = selfHostedSessionStore.loadForAuthority(binding)

            override fun save(credentials: saien.someday.domain.settings.SelfHostedSessionCredentials) {
                selfHostedSessionStore.saveForAuthority(binding, credentials)
                if (selfHostedSessionStore.load()?.authorityBindingId == credentials.authorityBindingId) {
                    selfHostedSessionStore.save(credentials)
                }
            }

            override fun clear() = selfHostedSessionStore.clearAuthority(binding)
        }
        val refreshingSelfHostedV2 = RefreshingSelfHostedSyncTransportV2(
            delegate = selfHostedTransportV2,
            sessionExecutor = activeSelfHostedSessionExecutor,
            authenticatedUserId = credentials.userId,
        )
        val key = workspaceKeyProvider()
            ?: error("The workspace key is unavailable.")
        return SelfHostedSyncRemoteV2(
            endpoint = credentials.endpoint,
            authenticatedUserId = credentials.userId,
            workspaceId = workspaceId,
            accessTokenProvider = {
                boundSessionStore.load()?.accessToken
                    ?: error("Self-hosted session is missing; tokens redacted.")
            },
            transport = refreshingSelfHostedV2,
            workspaceKey = key,
        )
    }

    val selfHostedRuntime = SyncV2RuntimeService(
        mode = SyncMode.SelfHosted,
        localRepository = localRepository,
        settingsRepository = settingsRepository,
        workspaceKeyProvider = workspaceKeyProvider,
        writerDeviceIdProvider = {
            normalizeWriterDeviceIdV2(localRepository.localDeviceId)
        },
        transportFactory = SyncRemoteTransportFactoryV2 { createSelfHostedRemoteV2() },
        authorityMutationCoordinator = authorityMutationCoordinator,
        beforeEntityPublication = { versions ->
            val mediaIds = versions.asSequence()
                .mapNotNull { it.contentPayload as? NoteContentV2 }
                .flatMap { findSomedayAssetIds(it.markdownBody).asSequence() }
                .toSet()
            mediaCoordinator.ensurePublished(mediaIds)
        },
    )

    val manual = SerializedManualSyncRunner(
        modeProvider = { settingsRepository.load().syncConfiguration.mode },
        delegate = ManualSyncRunner {
            when (val mode = settingsRepository.load().syncConfiguration.mode) {
                SyncMode.SelfHosted -> runSystemV3OrderedSync(mediaCoordinator, selfHostedRuntime::run)
                SyncMode.Off -> ManualSyncResult.failure(
                    mode = mode,
                    reason = ManualSyncReason.Disabled,
                )
            }
        },
    )
    val v2Notes = SystemV2NotesRepository(
        localRepository = localRepository,
        workspaceKeyProvider = workspaceKeyProvider,
        writerDeviceIdProvider = {
            normalizeWriterDeviceIdV2(localRepository.localDeviceId)
        },
        remoteProfileProvider = { SyncRemoteProfileV2.SELF_HOSTED.wireValue },
    )
    val v2Settings = SystemV2ClientSettingsRepository(
        localRepository = localRepository,
        localSettings = settingsRepository,
        workspaceKeyProvider = workspaceKeyProvider,
        writerDeviceIdProvider = {
            normalizeWriterDeviceIdV2(localRepository.localDeviceId)
        },
        remoteProfileProvider = { SyncRemoteProfileV2.SELF_HOSTED.wireValue },
        authorityMutationCoordinator = authorityMutationCoordinator,
    )
    val v2LocalDataTransfer = WorkspaceLocalDataTransferV2(
        localRepository = localRepository,
        settingsRepository = settingsRepository,
        workspaceKeyProvider = workspaceKeyProvider,
        writerDeviceIdProvider = {
            normalizeWriterDeviceIdV2(localRepository.localDeviceId)
        },
        remoteProfileProvider = { SyncRemoteProfileV2.SELF_HOSTED.wireValue },
    )
    return SystemV3ClientServices(
        notesRepository = AuthorityCoordinatedNotesRepository(v2Notes, authorityMutationCoordinator),
        settingsRepository = v2Settings,
        manualSyncRunner = manual,
        selfHostedSessionExecutor = activeSelfHostedSessionExecutor,
        activeWorkspaceSessionGuard = activeWorkspaceSessionGuard,
        localMediaAssetStore = coordinatedMediaAssetStore,
        mediaCoordinator = mediaCoordinator,
        authorityMutationCoordinator = authorityMutationCoordinator,
        workspaceAdoptionPolicy = LocalWorkspaceAdoptionPolicy {
            val key = workspaceKeyProvider()
                ?: return@LocalWorkspaceAdoptionPolicy WorkspacePairingReason.WorkspaceLocked
            localWorkspaceAdoptionRefusalReasonV2(localRepository, key, localMediaAssetStore)
        },
        discardEmptyDraftForWorkspaceAdoption = {
            workspaceKeyProvider()?.let { key ->
                discardEmptyLocalWorkspaceDraftForAdoptionV2(localRepository, key, localMediaAssetStore)
            } ?: false
        },
        bindAdoptedWorkspaceToCurrentSession = { packageData, key, workspaceId ->
            runCatching {
                val credentials = selfHostedSessionStore.load()
                    ?: error("The authenticated self-hosted session is unavailable.")
                require(packageData.workspaceId == workspaceId && workspaceId == workspaceIdProvider()) {
                    "The restored workspace id does not match the authenticated pairing package."
                }
                val writer = normalizeWriterDeviceIdV2(localRepository.localDeviceId)
                require(credentials.deviceId == writer) {
                    "The authenticated device does not match this installation writer."
                }
                val draft = ensureWorkspaceLocalDraftV2(localRepository, settingsRepository, key)
                val persisted = protocolStore.persistPreparingEpoch(
                    remoteProfile = draft.remoteProfile,
                    descriptor = draft.descriptor,
                    descriptorDigest = draft.descriptorDigest,
                    authorityBindingId = credentials.authorityBindingId,
                    localWriterDeviceId = writer,
                )
                persisted !is saien.someday.sync.causality.v2.SyncEpochPersistResultV2.ImmutableMismatch
            }.getOrDefault(false)
        },
        workspacePairingInviterReady = {
            protocolStore.loadAuthoritativeEpoch()?.let { epoch ->
                epoch.lifecycle == SyncEpochLifecycleV2.ACTIVE &&
                    epoch.health == SyncEpochHealthV2.HEALTHY &&
                    activeWorkspaceSessionGuard.currentRequirement() != null
            } == true
        },
        localDataExportProvider = { exportedAt ->
            authorityMutationCoordinator.exclusive {
                authorityMutationCoordinator.productAccess {
                    v2LocalDataTransfer.exportDocument(exportedAt)
                }
            }
        },
        localDataImportProvider = { document ->
            authorityMutationCoordinator.exclusive {
                authorityMutationCoordinator.productAccess {
                    v2LocalDataTransfer.importDocument(document)
                }
            }
        },
    )
}

internal fun resolveActiveWorkspaceSessionRequirement(
    protocolStore: SqlDelightSyncProtocolStoreV2,
    workspaceIdProvider: () -> String?,
): ActiveWorkspaceSessionRequirement? {
    val allPreparing = protocolStore.loadAllEpochs()
        .filter { it.lifecycle == SyncEpochLifecycleV2.PREPARING }
    val authoritative = protocolStore.loadAuthoritativeEpoch()
    val epoch = authoritative ?: when (allPreparing.size) {
        0 -> return null
        1 -> allPreparing.single().also { draft ->
            require(draft.health == SyncEpochHealthV2.HEALTHY) {
                "The local draft workspace is not healthy."
            }
            if (draft.authorityBindingId == null) {
                check(protocolStore.loadLocalAuthority() == null) {
                    "An unbound local draft unexpectedly has a publication authority."
                }
                return null
            }
        }
        else -> error("Multiple first-epoch workspace authorities are prepared.")
    }
    val localAuthority = protocolStore.loadLocalAuthority()
        ?.takeIf {
            it.remoteProfile == epoch.remoteProfile &&
                it.epochId == epoch.descriptor.syncEpochId &&
                it.pointerDigest == epoch.descriptorDigest
        }
        ?: error("The bound workspace writer identity is unavailable.")
    val authorityBindingId = epoch.authorityBindingId ?: localAuthority.authorityBindingId
    require(localAuthority.authorityBindingId == authorityBindingId) {
        "The prepared workspace authority binding is inconsistent."
    }
    return ActiveWorkspaceSessionRequirement(
        authorityBindingId = authorityBindingId,
        localWriterDeviceId = localAuthority.localWriterDeviceId,
        workspaceId = workspaceIdProvider()
            ?: error("The bound workspace id is unavailable."),
    )
}

/**
 * Shared entry guard for System V3 operations.
 *
 * Media ordering is enforced against the exact immutable entity versions inside the outbox and
 * checkpoint publishers. A blanket pending-media drain here would make an unrelated corrupt or
 * abandoned local import block every sync, and would still be racy with concurrent note edits.
 */
internal fun runSystemV3OrderedSync(
    mediaCoordinator: SystemV3MediaCoordinator,
    entitySync: () -> ManualSyncResult,
): ManualSyncResult {
    try {
        mediaCoordinator.verifyActiveAuthorityBinding()
    } catch (_: Exception) {
        return ManualSyncResult.failure(
            mode = SyncMode.SelfHosted,
            reason = ManualSyncReason.AuthorityMismatch,
        )
    }
    return entitySync()
}
