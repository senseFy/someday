package saien.someday.sync

import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataImportSummary
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.settings.ManualSyncPhase
import saien.someday.domain.settings.ManualSyncProgressListener
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.SyncV2MaintenanceRunner
import saien.someday.domain.settings.WebDavConnectionInput
import saien.someday.domain.settings.WebDavCredentialStore
import saien.someday.domain.settings.WebDavDiscoveredDevicesResult
import saien.someday.domain.settings.WebDavDiscoveredDevicesRunner
import saien.someday.domain.settings.WebDavAuthorityCredentials
import saien.someday.domain.settings.selfHostedV2AuthorityBindingId
import saien.someday.domain.settings.webDavV2AuthorityBindingId
import saien.someday.sync.causality.v2.ProtocolRoutingNotesRepository
import saien.someday.sync.causality.v2.AuthorityCoordinatedLocalDataTransferV2
import saien.someday.sync.causality.v2.RetainedEpochRemoteProviderV2
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.SyncRemoteTransportFactoryV2
import saien.someday.sync.causality.v2.SyncV2RuntimeService
import saien.someday.sync.causality.v2.StoredSyncEpochV2
import saien.someday.sync.causality.v2.SystemV2NotesRepository
import saien.someday.sync.causality.v2.WorkspaceCheckpointPublishProgressV2
import saien.someday.sync.causality.v2.WorkspaceLocalDataTransferV2
import saien.someday.sync.causality.v2.WorkspaceEpochRetentionServiceV2
import saien.someday.sync.causality.v2.WorkspaceRemoteMigrationResultV2
import saien.someday.sync.causality.v2.WorkspaceRemoteMigrationServiceV2
import saien.someday.sync.causality.v2.SystemV2ClientSettingsRepository
import saien.someday.sync.causality.v2.normalizeWriterDeviceIdV2
import saien.someday.sync.selfhosted.RefreshingSelfHostedSyncTransportV2
import saien.someday.sync.selfhosted.RefreshingSelfHostedSessionExecutor
import saien.someday.sync.selfhosted.SelfHostedSyncRemoteV2
import saien.someday.sync.selfhosted.SelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedSyncTransportV2
import saien.someday.sync.webdav.WebDavClient
import saien.someday.sync.webdav.WebDavConfiguration
import saien.someday.sync.webdav.WebDavTransport
import saien.someday.sync.webdav.WorkspaceWebDavSyncRemoteV2

data class SyncV2ClientServices(
    val notesRepository: NotesRepository,
    val settingsRepository: ClientSettingsRepository,
    val manualSyncRunner: ManualSyncRunner,
    val maintenanceRunner: SyncV2MaintenanceRunner,
    val webDavDiscoveredDevicesRunner: WebDavDiscoveredDevicesRunner,
    val selfHostedSessionExecutor: RefreshingSelfHostedSessionExecutor,
    val authorityMutationCoordinator: WorkspaceAuthorityMutationCoordinator,
    val localDataExportProvider: (kotlin.time.Instant) -> LocalDataExportDocument?,
    val localDataImportProvider: (LocalDataExportDocument) -> LocalDataImportSummary?,
    /**
     * Bind/unbind a listener for first-epoch (and other checkpoint) publish
     * progress while manual sync runs. Pass null to clear. Receives typed
     * phases; the UI layer formats localized product copy.
     */
    val bindManualSyncProgressListener: (ManualSyncProgressListener?) -> Unit = {},
)

fun createSyncV2ClientServices(
    localRepository: SqlDelightLocalDataRepository,
    localNotesRepository: NotesRepository,
    settingsRepository: ClientSettingsRepository,
    workspaceKeyProvider: () -> WorkspaceMasterKey?,
    webDavTransport: WebDavTransport,
    webDavCredentialStore: WebDavCredentialStore,
    selfHostedTransport: SelfHostedSyncTransport,
    selfHostedTransportV2: SelfHostedSyncTransportV2,
    selfHostedSessionStore: SelfHostedSessionCredentialStore,
    /**
     * Required. Platforms must pass BuildConfig release/dev flags.
     * There is intentionally no default true: shipping builds must not silently
     * publish the first V2 epoch without the canonical release decision.
     */
    systemV2ActivationEnabled: Boolean,
    workspaceKeyForEpochProvider: (StoredSyncEpochV2) -> WorkspaceMasterKey? = { workspaceKeyProvider() },
    releaseWorkspaceKeyForEpoch: (String) -> Unit = {},
): SyncV2ClientServices {
    val activeSelfHostedSessionExecutor = RefreshingSelfHostedSessionExecutor(
        authenticationTransport = selfHostedTransport,
        sessionStore = selfHostedSessionStore,
    )
    val authorityMutationCoordinator = WorkspaceAuthorityMutationCoordinator()
    fun createWebDavRemoteV2(retainedEpoch: StoredSyncEpochV2? = null): WorkspaceWebDavSyncRemoteV2 {
        val settings = settingsRepository.load()
        val sync = settings.syncConfiguration
        val archived = retainedEpoch?.authorityBindingId?.let(webDavCredentialStore::loadForAuthority)
        val credential = archived?.secret ?: webDavCredentialStore.load()?.takeIf { it.isNotBlank() }
            ?: error("A saved WebDAV credential is required; credentials redacted.")
        val input = WebDavConnectionInput(
            endpoint = archived?.endpoint ?: sync.webDavEndpoint.orEmpty(),
            username = archived?.username ?: sync.webDavUsername,
            password = credential,
            appDirectory = archived?.appDirectory ?: sync.webDavAppDirectory,
        ).sanitized()
        require(input.validate().isEmpty() && !input.username.isNullOrBlank()) {
            "WebDAV settings are incomplete; credentials redacted."
        }
        val binding = webDavV2AuthorityBindingId(input.endpoint, input.appDirectory)
        require(retainedEpoch?.authorityBindingId?.let { it == binding } != false) {
            "The archived WebDAV credential does not match the retained authority binding."
        }
        webDavCredentialStore.saveForAuthority(
            WebDavAuthorityCredentials(binding, input.endpoint, input.username!!, input.appDirectory, credential),
        )
        val key = retainedEpoch?.let(workspaceKeyForEpochProvider) ?: workspaceKeyProvider()
            ?: error("The workspace key for this exact epoch is unavailable.")
        return WorkspaceWebDavSyncRemoteV2(
            client = WebDavClient(WebDavConfiguration.fromConnectionInput(input), webDavTransport),
            workspaceKey = key,
            localWriterDeviceId = normalizeWriterDeviceIdV2(settings.activeDeviceId),
            protocolStore = SqlDelightSyncProtocolStoreV2(localRepository.database),
        )
    }

    fun createSelfHostedRemoteV2(retainedEpoch: StoredSyncEpochV2? = null): SelfHostedSyncRemoteV2 {
        val requestedBinding = retainedEpoch?.authorityBindingId
        val credentials = requestedBinding?.let(selfHostedSessionStore::loadForAuthority)
            ?: selfHostedSessionStore.load()
            ?: error("Self-hosted session is missing; tokens redacted.")
        val binding = selfHostedV2AuthorityBindingId(credentials.endpoint)
        require(requestedBinding?.let { it == binding } != false) {
            "The archived self-hosted session does not match the retained authority binding."
        }
        selfHostedSessionStore.saveForAuthority(binding, credentials)
        val boundSessionStore = object : SelfHostedSessionCredentialStore {
            override fun load() = selfHostedSessionStore.loadForAuthority(binding)

            override fun save(credentials: saien.someday.domain.settings.SelfHostedSessionCredentials) {
                selfHostedSessionStore.saveForAuthority(binding, credentials)
                if (selfHostedSessionStore.load()?.endpoint?.trimEnd('/') == credentials.endpoint.trimEnd('/')) {
                    selfHostedSessionStore.save(credentials)
                }
            }

            override fun clear() = selfHostedSessionStore.clearAuthority(binding)
        }
        val sessionExecutor = if (requestedBinding == null) {
            activeSelfHostedSessionExecutor
        } else {
            RefreshingSelfHostedSessionExecutor(
                authenticationTransport = selfHostedTransport,
                sessionStore = boundSessionStore,
            )
        }
        val refreshingSelfHostedV2 = RefreshingSelfHostedSyncTransportV2(
            delegate = selfHostedTransportV2,
            sessionExecutor = sessionExecutor,
        )
        val key = retainedEpoch?.let(workspaceKeyForEpochProvider) ?: workspaceKeyProvider()
            ?: error("The workspace key for this exact epoch is unavailable.")
        return SelfHostedSyncRemoteV2(
            endpoint = credentials.endpoint,
            accessTokenProvider = {
                boundSessionStore.load()?.accessToken
                    ?: error("Self-hosted session is missing; tokens redacted.")
            },
            transport = refreshingSelfHostedV2,
            workspaceKey = key,
        )
    }

    val retainedProvider = RetainedEpochRemoteProviderV2 { epoch ->
        when (epoch.remoteProfile) {
            SyncRemoteProfileV2.WEB_DAV.wireValue -> createWebDavRemoteV2(epoch)
            SyncRemoteProfileV2.SELF_HOSTED.wireValue -> createSelfHostedRemoteV2(epoch)
            else -> null
        }
    }
    val publishProgressListener = object {
        @kotlin.concurrent.Volatile
        var value: ManualSyncProgressListener? = null
    }
    val onPublishProgress: (WorkspaceCheckpointPublishProgressV2) -> Unit =
        { progress ->
            runCatching {
                publishProgressListener.value?.onProgress(progress.toManualSyncPhase())
            }
        }
    val webDavRuntime = SyncV2RuntimeService(
        mode = SyncMode.WebDav,
        localRepository = localRepository,
        settingsRepository = settingsRepository,
        workspaceKeyProvider = workspaceKeyProvider,
        writerDeviceIdProvider = {
            normalizeWriterDeviceIdV2(settingsRepository.load().activeDeviceId)
        },
        transportFactory = SyncRemoteTransportFactoryV2 { createWebDavRemoteV2() },
        retainedEpochRemoteProvider = retainedProvider,
        activationEnabled = systemV2ActivationEnabled,
        authorityMutationCoordinator = authorityMutationCoordinator,
        onPublishProgress = onPublishProgress,
    )
    val selfHostedRuntime = SyncV2RuntimeService(
        mode = SyncMode.SelfHosted,
        localRepository = localRepository,
        settingsRepository = settingsRepository,
        workspaceKeyProvider = workspaceKeyProvider,
        writerDeviceIdProvider = {
            selfHostedSessionStore.load()?.deviceId ?: error("Self-hosted session is missing; tokens redacted.")
        },
        transportFactory = SyncRemoteTransportFactoryV2 { createSelfHostedRemoteV2() },
        retainedEpochRemoteProvider = retainedProvider,
        activationEnabled = systemV2ActivationEnabled,
        authorityMutationCoordinator = authorityMutationCoordinator,
        onPublishProgress = onPublishProgress,
    )

    val manual = ManualSyncRunner {
        when (val mode = settingsRepository.load().syncConfiguration.mode) {
            SyncMode.WebDav -> webDavRuntime.run()
            SyncMode.SelfHosted -> selfHostedRuntime.run()
            SyncMode.Off -> ManualSyncResult.failure(
                mode,
                "Sync is off. Local changes remain on this device.",
            )
        }
    }
    val maintenance = object : SyncV2MaintenanceRunner {
        override fun rollEpoch(): ManualSyncResult = when (val mode = settingsRepository.load().syncConfiguration.mode) {
            SyncMode.WebDav -> webDavRuntime.rollEpoch()
            SyncMode.SelfHosted -> selfHostedRuntime.rollEpoch()
            SyncMode.Off -> ManualSyncResult.failure(mode, "Choose a sync provider before v2 maintenance.")
        }

        override fun repairIntegrity(): ManualSyncResult =
            when (val mode = settingsRepository.load().syncConfiguration.mode) {
                SyncMode.WebDav -> webDavRuntime.repairIntegrity()
                SyncMode.SelfHosted -> selfHostedRuntime.repairIntegrity()
                SyncMode.Off -> ManualSyncResult.failure(mode, "Choose a sync provider before v2 maintenance.")
            }

        override fun recoverWithVerifiedLocalCheckpoint(
            userConfirmedPotentialDataLoss: Boolean,
        ): ManualSyncResult = when (val mode = settingsRepository.load().syncConfiguration.mode) {
            SyncMode.WebDav -> webDavRuntime.recoverWithVerifiedLocalCheckpoint(userConfirmedPotentialDataLoss)
            SyncMode.SelfHosted -> selfHostedRuntime.recoverWithVerifiedLocalCheckpoint(userConfirmedPotentialDataLoss)
            SyncMode.Off -> ManualSyncResult.failure(mode, "Choose a sync provider before v2 maintenance.")
        }

        override fun migrateToConfiguredRemote(): ManualSyncResult {
            val targetMode = settingsRepository.load().syncConfiguration.mode
            if (targetMode == SyncMode.Off) {
                return ManualSyncResult.failure(targetMode, "Choose and configure the target provider before V2 migration.")
            }
            val authority = SqlDelightSyncProtocolStoreV2(localRepository.database).loadAuthoritativeEpoch()
                ?: return ManualSyncResult.failure(targetMode, "No authenticated V2 source authority exists.")
            val sourceRuntime = when (authority.remoteProfile) {
                SyncRemoteProfileV2.WEB_DAV.wireValue -> webDavRuntime
                SyncRemoteProfileV2.SELF_HOSTED.wireValue -> selfHostedRuntime
                else -> return ManualSyncResult.failure(targetMode, "The source V2 profile is unsupported.")
            }
            val targetRuntime = when (targetMode) {
                SyncMode.WebDav -> webDavRuntime
                SyncMode.SelfHosted -> selfHostedRuntime
                SyncMode.Off -> error("handled above")
            }
            val targetRemote = targetRuntime.openRemoteForMigration()
                .getOrElse { return ManualSyncResult.failure(targetMode, it.message ?: "Target V2 endpoint is unavailable.") }
            if (authority.remoteProfile == targetRemote.remoteProfile &&
                authority.authorityBindingId == targetRemote.authorityBindingId
            ) {
                return targetRuntime.run().let { exact ->
                    if (exact.success) exact.copy(
                        message = "The configured endpoint exposes the exact existing V2 authority; no migration was needed.",
                    ) else exact
                }
            }
            val sourceRemote = runCatching { retainedProvider.open(authority) }
                .getOrElse { return ManualSyncResult.failure(targetMode, it.message ?: "Source V2 endpoint is unavailable.") }
                ?: return ManualSyncResult.failure(targetMode, "The retained source V2 endpoint credential is unavailable.")
            val drained = sourceRuntime.synchronizeRemoteForMigration(sourceRemote)
            if (!drained.success) return drained
            val targetWriter = targetRuntime.configuredWriterIdForMigration()
                .getOrElse { return ManualSyncResult.failure(targetMode, it.message ?: "Target writer identity is invalid.") }
            val workspaceKey = workspaceKeyProvider()
                ?: return ManualSyncResult.failure(targetMode, "Unlock the workspace before V2 remote migration.")
            return when (val migrated = WorkspaceRemoteMigrationServiceV2(
                localRepository,
                workspaceKey,
                targetWriter,
                sourceRemote,
                targetRemote,
                authorityMutationCoordinator,
            ).migrate()) {
                is WorkspaceRemoteMigrationResultV2.Blocked -> ManualSyncResult.failure(
                    targetMode,
                    "V2 remote migration stopped safely: ${migrated.safeMessage}",
                )
                is WorkspaceRemoteMigrationResultV2.Migrated -> ManualSyncResult.success(
                    targetMode,
                    pushedObjects = 0,
                    pulledObjects = migrated.importedLateObjects,
                    conflicts = migrated.activeConflicts,
                    message = "V2 authority migrated from ${migrated.sourceProfile} to ${migrated.targetProfile}; " +
                        "the source epoch is read-only and retained for 180 days.",
                )
            }
        }

        override fun collectExpiredLocalHistory(): ManualSyncResult {
            val mode = settingsRepository.load().syncConfiguration.mode
            val synced = when (mode) {
                SyncMode.WebDav -> webDavRuntime.run()
                SyncMode.SelfHosted -> selfHostedRuntime.run()
                SyncMode.Off -> return ManualSyncResult.failure(mode, "Enable the bound provider before retention maintenance.")
            }
            if (!synced.success) return synced
            val result = WorkspaceEpochRetentionServiceV2(localRepository)
                .collectExpiredLocalEpochs(mode.syncV2Profile(), kotlin.time.Clock.System.now())
            result.collectedEpochIds.forEach(releaseWorkspaceKeyForEpoch)
            val credentialCleanupFailures = result.releasedAuthorityBindingIds.count { binding ->
                runCatching {
                    when {
                        binding.startsWith("webdav-log-v2|") -> webDavCredentialStore.clearAuthority(binding)
                        binding.startsWith("self-hosted-v2|") -> selfHostedSessionStore.clearAuthority(binding)
                    }
                }.isFailure
            }
            return ManualSyncResult.success(
                mode,
                synced.pushedObjects,
                synced.pulledObjects,
                synced.conflicts,
                "Collected ${result.collectedEpochIds.size} expired local V2 epoch(s); " +
                    "${result.pinnedEpochs.size} remain pinned by durable recovery state." +
                    if (credentialCleanupFailures == 0) "" else
                        " $credentialCleanupFailures expired secure-store credential entr${if (credentialCleanupFailures == 1) "y remains" else "ies remain"} for manual cleanup.",
            )
        }
    }
    val v2Notes = SystemV2NotesRepository(
        localRepository = localRepository,
        workspaceKeyProvider = workspaceKeyProvider,
        writerDeviceIdProvider = {
            // The durable local-authority binding normally supplies this id.
            // activeDeviceId is only a migration fallback and remains available
            // while network mode is Off.
            normalizeWriterDeviceIdV2(settingsRepository.load().activeDeviceId)
        },
        remoteProfileProvider = {
            when (settingsRepository.load().syncConfiguration.mode) {
                SyncMode.WebDav -> SyncRemoteProfileV2.WEB_DAV.wireValue
                SyncMode.SelfHosted -> SyncRemoteProfileV2.SELF_HOSTED.wireValue
                SyncMode.Off -> ""
            }
        },
        workspaceKeyForEpochProvider = workspaceKeyForEpochProvider,
    )
    val v2Settings = SystemV2ClientSettingsRepository(
        localRepository = localRepository,
        localSettings = settingsRepository,
        workspaceKeyProvider = workspaceKeyProvider,
        writerDeviceIdProvider = {
            normalizeWriterDeviceIdV2(settingsRepository.load().activeDeviceId)
        },
        remoteProfileProvider = {
            when (settingsRepository.load().syncConfiguration.mode) {
                SyncMode.WebDav -> SyncRemoteProfileV2.WEB_DAV.wireValue
                SyncMode.SelfHosted -> SyncRemoteProfileV2.SELF_HOSTED.wireValue
                SyncMode.Off -> ""
            }
        },
        authorityMutationCoordinator = authorityMutationCoordinator,
    )
    val v2LocalDataTransfer = WorkspaceLocalDataTransferV2(
        localRepository = localRepository,
        settingsRepository = settingsRepository,
        workspaceKeyProvider = workspaceKeyProvider,
        writerDeviceIdProvider = {
            normalizeWriterDeviceIdV2(settingsRepository.load().activeDeviceId)
        },
        remoteProfileProvider = {
            when (settingsRepository.load().syncConfiguration.mode) {
                SyncMode.WebDav -> SyncRemoteProfileV2.WEB_DAV.wireValue
                SyncMode.SelfHosted -> SyncRemoteProfileV2.SELF_HOSTED.wireValue
                SyncMode.Off -> ""
            }
        },
    )
    val protocolStore = SqlDelightSyncProtocolStoreV2(localRepository.database)
    val localDataTransfer = AuthorityCoordinatedLocalDataTransferV2(
        localRepository = localRepository,
        authorityMutationCoordinator = authorityMutationCoordinator,
        v2Transfer = v2LocalDataTransfer,
    )
    return SyncV2ClientServices(
        notesRepository = ProtocolRoutingNotesRepository(
            localNotes = localNotesRepository,
            systemV2 = v2Notes,
            v2AuthorityAvailable = { protocolStore.loadAuthoritativeEpoch() != null },
            authorityMutationCoordinator = authorityMutationCoordinator,
        ),
        settingsRepository = v2Settings,
        manualSyncRunner = manual,
        maintenanceRunner = maintenance,
        webDavDiscoveredDevicesRunner = WebDavDiscoveredDevicesRunner {
            runCatching {
                val epoch = SqlDelightSyncProtocolStoreV2(localRepository.database)
                    .loadActiveEpoch(SyncRemoteProfileV2.WEB_DAV.wireValue)
                    ?: return@WebDavDiscoveredDevicesRunner WebDavDiscoveredDevicesResult.success(emptyList())
                WebDavDiscoveredDevicesResult.success(
                    createWebDavRemoteV2(epoch).discoveredDevices(epoch.descriptor.syncEpochId),
                )
            }.getOrElse {
                WebDavDiscoveredDevicesResult.failure(
                    "Could not load discovered WebDAV devices. Sync once and try again.",
                )
            }
        },
        selfHostedSessionExecutor = activeSelfHostedSessionExecutor,
        authorityMutationCoordinator = authorityMutationCoordinator,
        localDataExportProvider = localDataTransfer::exportDocument,
        localDataImportProvider = localDataTransfer::importDocument,
        bindManualSyncProgressListener = { listener ->
            publishProgressListener.value = listener
        },
    )
}

private fun WorkspaceCheckpointPublishProgressV2.toManualSyncPhase(): ManualSyncPhase =
    when (this) {
        is WorkspaceCheckpointPublishProgressV2.UploadingChunks ->
            ManualSyncPhase.UploadingChunks(completed = completed, total = total)
        WorkspaceCheckpointPublishProgressV2.UploadingManifest ->
            ManualSyncPhase.UploadingManifest
        WorkspaceCheckpointPublishProgressV2.VerifyingRemote ->
            ManualSyncPhase.VerifyingRemote
        WorkspaceCheckpointPublishProgressV2.CommittingPointer ->
            ManualSyncPhase.CommittingPointer
    }

private fun SyncMode.syncV2Profile(): String = when (this) {
    SyncMode.WebDav -> SyncRemoteProfileV2.WEB_DAV.wireValue
    SyncMode.SelfHosted -> SyncRemoteProfileV2.SELF_HOSTED.wireValue
    SyncMode.Off -> error("Sync Off has no remote V2 profile.")
}
