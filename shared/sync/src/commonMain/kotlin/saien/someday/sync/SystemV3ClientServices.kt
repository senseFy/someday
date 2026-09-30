package saien.someday.sync

import saien.someday.data.account.SqlDelightAccountStateRepository
import saien.someday.data.account.AccountNetworkBlockedException
import saien.someday.data.account.AccountResetIntentState
import saien.someday.data.account.AccountStateMutationBoundary
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataImportSummary
import saien.someday.data.importing.dayone.DayOneImportService
import saien.someday.data.importing.dayone.DayOneImportSummary
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.media.MediaImageNormalizer
import saien.someday.data.media.SelectedImageImportException
import saien.someday.data.media.SelectedImageImportRequest
import okio.Buffer
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.media.findSomedayAssetIds
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SyncMode
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
import saien.someday.sync.causality.v2.discardLocalWorkspaceForReplacementV2
import saien.someday.sync.selfhosted.RefreshingSelfHostedSyncTransportV2
import saien.someday.sync.selfhosted.RefreshingSelfHostedSessionExecutor
import saien.someday.sync.selfhosted.SelfHostedSyncRemoteV2
import saien.someday.sync.selfhosted.SelfHostedMediaTransportV3
import saien.someday.sync.selfhosted.SelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedSyncTransportV2
import saien.someday.sync.selfhosted.SystemV3MediaCoordinator
import saien.someday.sync.selfhosted.ActiveWorkspaceSessionGuard
import saien.someday.sync.selfhosted.ActiveWorkspaceSessionRequirement
import saien.someday.sync.selfhosted.SelfHostedAccountControlTransport
import saien.someday.sync.selfhosted.SelfHostedAccountDiscoveryService
import saien.someday.sync.selfhosted.SelfHostedAccountDiscoveryResult
import saien.someday.sync.selfhosted.SelfHostedErrorCode
import saien.someday.sync.selfhosted.SelfHostedSyncHttpException
import saien.someday.sync.selfhosted.accountRequestContext
import saien.someday.sync.selfhosted.SelfHostedAccountResetService
import saien.someday.sync.selfhosted.AccountResetLocalWorkspace
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.selfHostedAuthorityBindingId
import saien.someday.domain.settings.parseSelfHostedAuthorityBindingId
import saien.someday.domain.settings.WorkspaceJoinAuthorityCapture
import saien.someday.domain.workspace.WorkspaceProductAccess
import saien.someday.domain.workspace.WorkspaceProductSnapshot

data class SystemV3ClientServices(
    val notesRepository: NotesRepository,
    val accountStateRepository: SqlDelightAccountStateRepository,
    val accountDiscoveryService: SelfHostedAccountDiscoveryService?,
    val accountResetService: SelfHostedAccountResetService?,
    val settingsRepository: ClientSettingsRepository,
    val manualSyncRunner: ManualSyncRunner,
    val automaticSyncEligible: () -> Boolean,
    val selfHostedSessionExecutor: RefreshingSelfHostedSessionExecutor,
    val activeWorkspaceSessionGuard: ActiveWorkspaceSessionGuard,
    val localMediaAssetStore: AuthorityCoordinatedMediaAssetStore,
    val mediaCoordinator: SystemV3MediaCoordinator,
    val workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator,
    val workspaceProductAccess: WorkspaceProductAccess,
    val discardLocalWorkspaceForReplacement: () -> Boolean,
    val finalizeLocalWorkspaceReplacement: () -> Unit,
    val bindReplacementWorkspaceToCurrentSession: (WorkspaceJoinPackage, WorkspaceMasterKey, String) -> Boolean,
    val bindFreshWorkspaceAfterAccountReset: (WorkspaceMasterKey, String, WorkspaceJoinAuthorityCapture) -> Boolean,
    val workspacePairingInviterReady: () -> Boolean,
    val localDataExportProvider: (kotlin.time.Instant) -> LocalDataExportDocument,
    val localDataImportProvider: (LocalDataExportDocument, WorkspaceProductSnapshot?) -> LocalDataImportSummary,
    val dayOneArchiveImporter: (ByteArray, String, MediaImageNormalizer, WorkspaceProductSnapshot?) -> DayOneImportSummary,
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
    val workspaceLifecycleCoordinator = WorkspaceLifecycleCoordinator()
    val accountStates = SqlDelightAccountStateRepository(
        localRepository.database,
        mutationBoundary = object : AccountStateMutationBoundary {
            override fun <T> mutate(block: () -> T): T = workspaceLifecycleCoordinator.productAccess(block)
        },
    )
    val accountDiscovery = (selfHostedTransport as? SelfHostedAccountControlTransport)?.let {
        SelfHostedAccountDiscoveryService(it, accountStates)
    }
    val protocolStore = SqlDelightSyncProtocolStoreV2(localRepository.database)
    workspaceKeyProvider()?.let { key ->
        ensureWorkspaceLocalDraftV2(localRepository, settingsRepository, key)
    }
    fun localAccountGate(): saien.someday.data.account.AccountWorkspaceGate? {
        val workspace = workspaceIdProvider() ?: return null
        val intent = accountStates.loadIntent()?.takeIf { it.originalWorkspaceId == workspace }
        val pendingGate = intent?.let { accountStates.loadGate(it.endpoint, it.userId, workspace) }
        check(pendingGate == null || pendingGate.expectedIncarnation == intent?.originalIncarnation) {
            "The pending reset gate does not match this local copy."
        }
        if (intent != null && pendingGate == null) {
            // The independent intent survives damaged/missing gate state. Rebuild only a frozen
            // gate, never infer an old offline choice or resurrect saved discard consent.
            if (intent.receiptIncarnation != null) {
                accountStates.freezeForLocalDiscardConfirmation(intent.operationId)
            } else {
                accountStates.markOutcomeUnknown(intent.operationId)
            }
        }
        val localAuthority = protocolStore.loadLocalAuthority()
        val boundAuthority = localAuthority?.authorityBindingId?.let(::parseSelfHostedAuthorityBindingId)
        if (localAuthority != null && boundAuthority != null) {
            val knownCredentials = listOfNotNull(
                selfHostedSessionStore.load()?.takeIf { it.authorityBindingId == localAuthority.authorityBindingId },
                selfHostedSessionStore.loadForAuthority(localAuthority.authorityBindingId)
                    ?.takeIf { it.authorityBindingId == localAuthority.authorityBindingId },
            )
            if (knownCredentials.any { it.accountIncarnation != localAuthority.accountIncarnation }) {
                val existing = accountStates.loadGate(boundAuthority.endpoint, boundAuthority.authenticatedUserId, workspace)
                if (existing?.reason != saien.someday.data.account.AccountWorkspaceGateReason.ResetRequired ||
                    existing?.expectedIncarnation != localAuthority.accountIncarnation) {
                    // This admission runs before every product write, even before Settings opens.
                    // markResetRequired preserves a deliberately selected offline-editing state.
                    accountStates.markResetRequired(boundAuthority.endpoint, boundAuthority.authenticatedUserId,
                        workspace, localAuthority.accountIncarnation)
                }
            }
        }
        val binding = localAuthority?.authorityBindingId
            ?: intent?.let { selfHostedAuthorityBindingId(it.endpoint, it.userId) }
            ?: selfHostedSessionStore.load()?.authorityBindingId
        val authority = binding?.let(::parseSelfHostedAuthorityBindingId)
        return authority?.let { accountStates.loadGate(it.endpoint, it.authenticatedUserId, workspace) }
            ?: intent?.let { accountStates.loadGate(it.endpoint, it.userId, workspace) }
    }
    // Bootstrap runs off-main. Reconstruct durable admission before any controller can observe
    // the installation; capture/isCurrent still retain their IO/pure-memory split below.
    workspaceLifecycleCoordinator.productAccess { localAccountGate() }
    val workspaceProductAccess = CoordinatedWorkspaceProductAccess(
        workspaceLifecycleCoordinator,
        snapshotProvider = {
            val binding = protocolStore.loadLocalAuthority()
            WorkspaceProductSnapshot(checkNotNull(workspaceIdProvider()), binding?.accountIncarnation ?: INITIAL_ACCOUNT_INCARNATION,
                binding?.authorityBindingId, normalizeWriterDeviceIdV2(localRepository.localDeviceId))
        },
        productReadOnly = {
            // A pending operation for a different local copy is an inconsistent installation,
            // not permission to edit a replacement that never completed reconciliation.
            val pending = accountStates.loadIntent()
            (pending != null && pending.originalWorkspaceId != workspaceIdProvider()) ||
                localAccountGate()?.productReadOnly == true
        },
    )
    val coordinatedMediaAssetStore = AuthorityCoordinatedMediaAssetStore(
        delegate = localMediaAssetStore,
        workspaceLifecycleCoordinator = workspaceLifecycleCoordinator,
        workspaceProductAccess = workspaceProductAccess,
    )
    val activeWorkspaceSessionGuard = ActiveWorkspaceSessionGuard(
        requireSetupAccess = {
            // An unbound first-run workspace can also own a reset intent. Check before
            // password login/device enrollment, even when secure credentials are missing.
            accountStates.loadIntent()?.let { intent ->
                accountStates.requireNetworkAllowed(intent.endpoint, intent.userId, intent.originalWorkspaceId)
            }
            localAccountGate()?.let { throw AccountNetworkBlockedException(it) }
        },
        requireNetworkAccess = { credentials, workspaceId ->
            accountStates.requireNetworkAllowed(credentials.endpoint, credentials.userId, workspaceId)
        },
        persistIncarnationGate = { requirement ->
            val authority = checkNotNull(parseSelfHostedAuthorityBindingId(requirement.authorityBindingId)) {
                "The bound account identity is unavailable."
            }
            // The durable binding already identifies the account. Missing/expired secure
            // credentials must not prevent recording a verified incarnation failure.
            accountStates.markResetRequired(authority.endpoint, authority.authenticatedUserId,
                requirement.workspaceId, requirement.accountIncarnation)
        },
        replacementWorkspaceProvider = {
            workspaceIdProvider()?.let { it to (protocolStore.loadLocalAuthority()?.accountIncarnation ?: INITIAL_ACCOUNT_INCARNATION) }
        },
        protocol1Known = accountStates::hasProtocol1,
        onProtocol1 = accountStates::markProtocol1,
        requireReplacementAllowed = { credentials ->
            localAccountGate()?.let { gate ->
                check(!gate.offlineEditing && gate.discardTargetIncarnation == credentials.accountIncarnation) {
                    "Fresh consent for the current account incarnation is required."
                }
            }
            accountStates.loadIntent()?.takeIf {
                selfHostedAuthorityBindingId(it.endpoint, it.userId) == credentials.authorityBindingId
            }?.let { intent ->
                val original = resolveActiveWorkspaceSessionRequirement(protocolStore, workspaceIdProvider)
                val originalIncarnation = original?.accountIncarnation ?: INITIAL_ACCOUNT_INCARNATION
                val currentWriter = original?.localWriterDeviceId ?: normalizeWriterDeviceIdV2(localRepository.localDeviceId)
                if (intent.state != AccountResetIntentState.RemoteCommittedLocalPending ||
                    intent.consentTargetIncarnation != credentials.accountIncarnation ||
                    intent.originalWorkspaceId != workspaceIdProvider() || intent.originalIncarnation != originalIncarnation ||
                    intent.originalWriterId != currentWriter
                ) accountStates.requireNetworkAllowed(credentials.endpoint, credentials.userId, intent.originalWorkspaceId)
            }
        },
        revalidateReplacement = { credentials ->
            when (val discovered = accountDiscovery?.discover(credentials)) {
                is SelfHostedAccountDiscoveryResult.Protocol1 -> if (discovered.state.accountIncarnation != credentials.accountIncarnation) {
                    throw SelfHostedSyncHttpException(409, "The replacement account incarnation changed.", SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, true)
                }
                else -> Unit
            }
        },
    ) { resolveActiveWorkspaceSessionRequirement(protocolStore, workspaceIdProvider) }
    val activeSelfHostedSessionExecutor = RefreshingSelfHostedSessionExecutor(
        authenticationTransport = selfHostedTransport,
        sessionStore = selfHostedSessionStore,
        protocol1Known = accountStates::hasProtocol1,
        verifyLegacy = { accountDiscovery?.isVerifiedLegacy(it) == true },
        onIssuance = { credentials -> accountDiscovery?.recordIssuance(credentials) },
        onAccountFailure = { endpoint, userId, capturedContext, failure ->
            if (failure.protocol1) accountStates.markProtocol1(endpoint, userId)
            if (failure.errorCode in setOf(SelfHostedErrorCode.ACCOUNT_SESSION_STALE,
                    SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED)) {
                activeWorkspaceSessionGuard.currentRequirement()?.takeIf {
                    it.authorityBindingId == selfHostedAuthorityBindingId(endpoint, userId) &&
                        it.accountIncarnation == capturedContext.accountIncarnation
                }?.let { requirement ->
                    accountStates.markResetRequired(endpoint, userId, requirement.workspaceId, requirement.accountIncarnation)
                }
            }
        },
    )
    val accountResetService = if (accountDiscovery != null && selfHostedTransport is SelfHostedAccountControlTransport) {
        SelfHostedAccountResetService(accountStates, selfHostedSessionStore, selfHostedTransport, accountDiscovery,
            workspaceLifecycleCoordinator) {
            val requirement = activeWorkspaceSessionGuard.currentRequirement()
            AccountResetLocalWorkspace(
                workspaceId = requirement?.workspaceId ?: checkNotNull(workspaceIdProvider()),
                writerDeviceId = requirement?.localWriterDeviceId ?: normalizeWriterDeviceIdV2(localRepository.localDeviceId),
                authorityBindingId = requirement?.authorityBindingId,
                accountIncarnation = requirement?.accountIncarnation ?: INITIAL_ACCOUNT_INCARNATION,
            )
        }
    } else null
    val mediaCoordinator = SystemV3MediaCoordinator(
        localStore = coordinatedMediaAssetStore,
        transport = selfHostedMediaTransportV3,
        sessionStore = selfHostedSessionStore,
        workspaceKeyProvider = workspaceKeyProvider,
        workspaceIdProvider = workspaceIdProvider,
        activeWorkspaceSessionGuard = activeWorkspaceSessionGuard,
        sessionExecutor = activeSelfHostedSessionExecutor,
        workspaceLifecycleCoordinator = workspaceLifecycleCoordinator,
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
        activeWorkspaceSessionGuard.requireCompatible(credentials, workspaceId)
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
                activeWorkspaceSessionGuard.requireCompatible(credentials, workspaceId)
                boundSessionStore.load()?.also { current ->
                    require(current.accountIncarnation == credentials.accountIncarnation) { "The captured account incarnation changed." }
                }?.accessToken ?: error("Self-hosted session is missing; tokens redacted.")
            },
            transport = refreshingSelfHostedV2,
            workspaceKey = key,
            accountContext = credentials.accountRequestContext(),
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
        workspaceLifecycleCoordinator = workspaceLifecycleCoordinator,
        beforeEntityPublication = { versions ->
            val mediaIds = versions.asSequence()
                .mapNotNull { it.contentPayload as? NoteContentV2 }
                .flatMap { findSomedayAssetIds(it.markdownBody).asSequence() }
                .toSet()
            mediaCoordinator.ensurePublishedWithinWorkspaceLifecycle(mediaIds)
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
        workspaceLifecycleCoordinator = workspaceLifecycleCoordinator,
        workspaceProductAccess = workspaceProductAccess,
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
    val bindInstalledWorkspace: (WorkspaceMasterKey, String, WorkspaceJoinAuthorityCapture) -> Boolean = { key, workspaceId, capture ->
    runCatching {
        val credentials = selfHostedSessionStore.load()
            ?: error("The authenticated self-hosted session is unavailable.")
        require(workspaceId == workspaceIdProvider()) {
            "The restored workspace id does not match the authenticated pairing package."
        }
        require(capture.authorityBindingId == credentials.authorityBindingId &&
            capture.deviceId == credentials.deviceId && capture.accountIncarnation == credentials.accountIncarnation) {
            "The authenticated replacement account changed."
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
            accountIncarnation = capture.accountIncarnation,
        )
        val accepted = persisted !is saien.someday.sync.causality.v2.SyncEpochPersistResultV2.ImmutableMismatch
        val previousWorkspaceId = capture.previousWorkspaceId
        val previousIncarnation = capture.previousAccountIncarnation
        if (accepted && previousWorkspaceId != null && previousIncarnation != null) {
            val intent = accountStates.loadIntent()?.takeIf {
                selfHostedAuthorityBindingId(it.endpoint, it.userId) == credentials.authorityBindingId &&
                    it.originalWorkspaceId == previousWorkspaceId && it.originalIncarnation == previousIncarnation &&
                    it.originalWriterId == writer
            }
            if (intent != null) {
                check(accountStates.completeLocalReconciliation(intent.operationId, capture.accountIncarnation)) {
                    "The reset replacement consent is unavailable."
                }
            } else {
                accountStates.clearGateAfterReplacement(credentials.endpoint, credentials.userId,
                    previousWorkspaceId, previousIncarnation)
            }
        }
        accepted
    }.getOrDefault(false)
    }
    return SystemV3ClientServices(
        notesRepository = AuthorityCoordinatedNotesRepository(v2Notes, workspaceLifecycleCoordinator, workspaceProductAccess),
        accountStateRepository = accountStates,
        accountDiscoveryService = accountDiscovery,
        accountResetService = accountResetService,
        settingsRepository = v2Settings,
        manualSyncRunner = manual,
        automaticSyncEligible = {
            isAutomaticSyncEligible(protocolStore) && selfHostedSessionStore.load()?.let {
                activeWorkspaceSessionGuard.isCompatible(it)
            } == true
        },
        selfHostedSessionExecutor = activeSelfHostedSessionExecutor,
        activeWorkspaceSessionGuard = activeWorkspaceSessionGuard,
        localMediaAssetStore = coordinatedMediaAssetStore,
        mediaCoordinator = mediaCoordinator,
        workspaceLifecycleCoordinator = workspaceLifecycleCoordinator,
        workspaceProductAccess = workspaceProductAccess,
        discardLocalWorkspaceForReplacement = {
            discardLocalWorkspaceForReplacementV2(localRepository, settingsRepository)
        },
        finalizeLocalWorkspaceReplacement = {
            workspaceProductAccess.invalidate()
            runCatching {
                localMediaAssetStore.purgeUnreferencedFilesWithoutGracePeriod()
            }
            Unit
        },
        bindReplacementWorkspaceToCurrentSession = { packageData, key, workspaceId ->
            packageData.workspaceId == workspaceId && packageData.capturedAuthority?.let {
                bindInstalledWorkspace(key, workspaceId, it)
            } == true
        },
        bindFreshWorkspaceAfterAccountReset = bindInstalledWorkspace,
        workspacePairingInviterReady = {
            protocolStore.loadAuthoritativeEpoch()?.let { epoch ->
                epoch.lifecycle == SyncEpochLifecycleV2.ACTIVE &&
                    epoch.health == SyncEpochHealthV2.HEALTHY &&
                    activeWorkspaceSessionGuard.currentRequirement() != null
            } == true
        },
        localDataExportProvider = { exportedAt ->
            workspaceLifecycleCoordinator.exclusive {
                workspaceLifecycleCoordinator.productAccess {
                    v2LocalDataTransfer.exportDocument(exportedAt)
                }
            }
        },
        localDataImportProvider = { document, capturedWorkspace ->
            val captured = capturedWorkspace ?: workspaceProductAccess.capture()
            workspaceLifecycleCoordinator.exclusive {
                workspaceProductAccess.mutate(captured) {
                    v2LocalDataTransfer.importDocument(document)
                }
            }
        },
        dayOneArchiveImporter = { bytes, title, normalizer, capturedWorkspace ->
            val captured = capturedWorkspace ?: workspaceProductAccess.capture()
            // One workspace identity from the first asset through the last note. Do not call
            // localDataImportProvider here: the lifecycle mutex is deliberately non-reentrant.
            workspaceLifecycleCoordinator.exclusive {
                workspaceProductAccess.mutate(captured) { Unit }
                DayOneImportService(
                    importPhoto = { image, fileName ->
                        try {
                            coordinatedMediaAssetStore.importSelectedImage(
                                Buffer().write(image), SelectedImageImportRequest(fileName), normalizer,
                            ).asset.metadata.uri
                        } catch (_: SelectedImageImportException) {
                            null
                        }
                    },
                    authoritativeImporter = { document ->
                        workspaceProductAccess.mutate(captured) {
                            v2LocalDataTransfer.importDocument(document)
                        }
                    },
                ).importArchive(bytes, title)
            }
        },
    )
}

internal fun isAutomaticSyncEligible(protocolStore: SqlDelightSyncProtocolStoreV2): Boolean =
    protocolStore.loadLocalAuthority() != null ||
        protocolStore.loadEpochs(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
            .any { it.authorityBindingId != null }

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
        accountIncarnation = localAuthority.accountIncarnation,
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
    } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        return ManualSyncResult.failure(
            mode = SyncMode.SelfHosted,
            reason = ManualSyncReason.AuthorityMismatch,
        )
    }
    return entitySync()
}
