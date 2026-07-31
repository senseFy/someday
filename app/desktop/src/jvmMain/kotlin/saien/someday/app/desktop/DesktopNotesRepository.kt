@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.app.desktop

import saien.someday.data.crypto.JvmFileSecureWorkspaceKeyStore
import saien.someday.data.crypto.WorkspaceKeyRepository
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.crypto.WorkspaceUnlockState
import saien.someday.data.crypto.workspaceJoinPackageProvider
import saien.someday.data.crypto.workspaceJoiner
import saien.someday.data.export.LocalDataExporter
import saien.someday.data.importing.dayone.DayOneImportService
import saien.someday.data.importing.dayone.DayOneImportSummary
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.SqlDelightNotesRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ManualSyncProgressListener
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.SyncV2MaintenanceRunner
import saien.someday.domain.settings.WebDavCredentialStore
import saien.someday.domain.settings.WebDavDiscoveredDevicesRunner
import saien.someday.domain.settings.WorkspacePairingInvitationCanceller
import saien.someday.domain.settings.WorkspacePairingInvitationCreator
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.createSyncV2ClientServices
import saien.someday.sync.selfhosted.JdkSelfHostedSyncTransport
import saien.someday.sync.selfhosted.ModeRoutingWorkspacePairingService
import saien.someday.sync.selfhosted.SelfHostedSetupService
import saien.someday.sync.selfhosted.SelfHostedWorkspacePairingService
import saien.someday.sync.webdav.JdkWebDavTransport
import saien.someday.sync.webdav.WebDavBackupService
import saien.someday.sync.webdav.WebDavWorkspacePairingService
import saien.someday.ui.settings.SettingsExportSummary
import saien.someday.ui.settings.SettingsImportSummary
import java.io.File
import java.util.UUID

data class DesktopClientRepositories(
    val notesRepository: NotesRepository,
    val settingsRepository: ClientSettingsRepository,
    val webDavBackupService: WebDavBackupService,
    val webDavCredentialStore: WebDavCredentialStore,
    val selfHostedSetupClient: SelfHostedSetupClient,
    val selfHostedSessionCredentialStore: SelfHostedSessionCredentialStore,
    val manualSyncRunner: ManualSyncRunner,
    val bindManualSyncProgressListener: (ManualSyncProgressListener?) -> Unit = {},
    val syncV2MaintenanceRunner: SyncV2MaintenanceRunner,
    val workspacePairingInvitationCreator: WorkspacePairingInvitationCreator,
    val workspacePairingInvitationJoiner: WorkspacePairingInvitationJoiner,
    val workspacePairingInvitationCanceller: WorkspacePairingInvitationCanceller,
    val webDavDiscoveredDevicesRunner: WebDavDiscoveredDevicesRunner,
    private val localRepository: SqlDelightLocalDataRepository,
    private val localDataExporter: LocalDataExporter,
    private val localDataImportProvider: (saien.someday.data.export.LocalDataExportDocument) ->
        saien.someday.data.export.LocalDataImportSummary?,
    private val exportDirectory: File,
) {
    fun exportLocalDataSummary(): SettingsExportSummary {
        val document = localDataExporter.exportDocument()
        exportDirectory.mkdirs()
        val file = File(exportDirectory, "someday-export-${document.exportedAt.toExportFileStamp()}.json")
        file.writeText(localDataExporter.encodeDocument(document))
        return SettingsExportSummary(
            formatName = "${document.format}+json",
            notebookCount = document.notebooks.size,
            noteCount = document.notes.size,
            excludedSensitiveFields = document.excludedSensitiveFields,
            destinationLabel = file.absolutePath,
        )
    }

    fun importDayOneArchive(file: File): SettingsImportSummary =
        DayOneImportService(localRepository, localDataImportProvider)
            .importArchive(file.readBytes(), file.nameWithoutExtension)
            .toSettingsImportSummary()
}

fun createDesktopClientRepositories(): DesktopClientRepositories {
    val localRepository = createDesktopLocalDataRepository(resolveDesktopLocalDeviceId())
    val settingsRepository = SqlDelightClientSettingsRepository(localRepository)
    ensureActiveDeviceId(
        settingsRepository = settingsRepository,
        deviceId = localRepository.localDeviceId,
    )
    val workspaceKeys = bootstrapDesktopWorkspaceKeys(localRepository)
    val webDavTransport = JdkWebDavTransport()
    val webDavCredentialStore = DesktopWebDavCredentialStore()
    val selfHostedTransport = JdkSelfHostedSyncTransport()
    val selfHostedSessionCredentialStore = DesktopSelfHostedSessionCredentialStore()
    val syncV2Services = createSyncV2ClientServices(
        localRepository = localRepository,
        localNotesRepository = SqlDelightNotesRepository(localRepository),
        settingsRepository = settingsRepository,
        workspaceKeyProvider = workspaceKeys::unlockedOrUnlock,
        webDavTransport = webDavTransport,
        webDavCredentialStore = webDavCredentialStore,
        selfHostedTransport = selfHostedTransport,
        selfHostedTransportV2 = selfHostedTransport,
        selfHostedSessionStore = selfHostedSessionCredentialStore,
        systemV2ActivationEnabled =
            DesktopBuildConfig.SOMEDAY_SYSTEM_V2_RELEASE_ENABLED ||
                DesktopBuildConfig.SOMEDAY_SYSTEM_V2_DEVELOPMENT_ENABLED,
        workspaceKeyForEpochProvider = { epoch ->
            workspaceKeys.workspaceKeyForEpochOrNull(epoch.descriptor.syncEpochId)
        },
        releaseWorkspaceKeyForEpoch = { epochId ->
            workspaceKeys.releaseWorkspaceKeyForEpoch(epochId)
        },
    )
    val webDavBackupService = WebDavBackupService(
        localRepository = localRepository,
        transport = webDavTransport,
        authoritativeDocumentProvider = syncV2Services.localDataExportProvider,
        authoritativeImporter = syncV2Services.localDataImportProvider,
    )
    val workspaceJoinPackageProvider = workspaceKeys.workspaceJoinPackageProvider()
    val localV2KeyBoundStatePresent = {
        SqlDelightSyncProtocolStoreV2(localRepository.database).hasKeyBoundLocalV2State()
    }
    val workspaceJoiner = workspaceKeys.workspaceJoiner(
        deviceName = "Desktop device",
        platform = "desktop",
        localV2KeyBoundStatePresent = localV2KeyBoundStatePresent,
    )
    val webDavPairingService = WebDavWorkspacePairingService(
        settingsProvider = settingsRepository::load,
        credentialStore = webDavCredentialStore,
        transport = webDavTransport,
        workspaceJoinPackageProvider = workspaceJoinPackageProvider,
        workspaceJoiner = workspaceJoiner,
        localV2KeyBoundStatePresent = localV2KeyBoundStatePresent,
        authorityMutationCoordinator = syncV2Services.authorityMutationCoordinator,
    )
    val selfHostedPairingService = SelfHostedWorkspacePairingService(
        settingsProvider = settingsRepository::load,
        sessionStore = selfHostedSessionCredentialStore,
        transport = selfHostedTransport,
        sessionExecutor = syncV2Services.selfHostedSessionExecutor,
        workspaceJoinPackageProvider = workspaceJoinPackageProvider,
        workspaceJoiner = workspaceJoiner,
        localV2KeyBoundStatePresent = localV2KeyBoundStatePresent,
        authorityMutationCoordinator = syncV2Services.authorityMutationCoordinator,
    )
    val workspacePairingService = ModeRoutingWorkspacePairingService(
        settingsProvider = settingsRepository::load,
        webDavCreator = webDavPairingService,
        webDavJoiner = webDavPairingService,
        webDavCanceller = webDavPairingService,
        selfHostedCreator = selfHostedPairingService,
        selfHostedJoiner = selfHostedPairingService,
        selfHostedCanceller = selfHostedPairingService,
    )
    return DesktopClientRepositories(
        notesRepository = syncV2Services.notesRepository,
        settingsRepository = syncV2Services.settingsRepository,
        webDavBackupService = webDavBackupService,
        webDavCredentialStore = webDavCredentialStore,
        selfHostedSetupClient = SelfHostedSetupService(
            transport = selfHostedTransport,
            sessionStore = selfHostedSessionCredentialStore,
        ),
        selfHostedSessionCredentialStore = selfHostedSessionCredentialStore,
        manualSyncRunner = syncV2Services.manualSyncRunner,
        bindManualSyncProgressListener = syncV2Services.bindManualSyncProgressListener,
        syncV2MaintenanceRunner = syncV2Services.maintenanceRunner,
        workspacePairingInvitationCreator = workspacePairingService,
        workspacePairingInvitationJoiner = workspacePairingService,
        workspacePairingInvitationCanceller = workspacePairingService,
        webDavDiscoveredDevicesRunner = syncV2Services.webDavDiscoveredDevicesRunner,
        localRepository = localRepository,
        localDataExporter = LocalDataExporter(
            localRepository,
            authoritativeDocumentProvider = syncV2Services.localDataExportProvider,
        ),
        localDataImportProvider = syncV2Services.localDataImportProvider,
        exportDirectory = File(File(System.getProperty("user.home"), ".someday"), "exports"),
    )
}

private fun createDesktopLocalDataRepository(deviceId: String): SqlDelightLocalDataRepository {
    val databaseFile = File(
        File(System.getProperty("user.home"), ".someday"),
        "someday.db",
    )
    databaseFile.parentFile?.mkdirs()
    val driver = createSomedayJdbcDriver("jdbc:sqlite:${databaseFile.absolutePath}")
    val database = SomedayDatabase(driver)
    return SqlDelightLocalDataRepository(
        database = database,
        deviceId = deviceId,
    )
}

private fun resolveDesktopLocalDeviceId(): String {
    val somedayDirectory = File(System.getProperty("user.home"), ".someday")
    val deviceIdFile = File(somedayDirectory, "device-id")
    deviceIdFile.takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val generated = UUID.randomUUID().toString()
    somedayDirectory.mkdirs()
    deviceIdFile.writeText(generated)
    return generated
}

private fun bootstrapDesktopWorkspaceKeys(localRepository: SqlDelightLocalDataRepository): WorkspaceKeyRepository =
    WorkspaceKeyRepository(
        localRepository = localRepository,
        secureKeyStore = JvmFileSecureWorkspaceKeyStore(),
    ).also { workspaceKeys ->
        workspaceKeys.bootstrapIfNeeded(
            deviceName = "Desktop device",
            platform = "desktop",
        )
    }

private fun WorkspaceKeyRepository.bootstrapIfNeeded(
    deviceName: String,
    platform: String,
) {
    when (startupState()) {
        WorkspaceUnlockState.Uninitialized -> createFirstRunWorkspace(deviceName, platform)
        is WorkspaceUnlockState.Locked -> {
            unlockWithSecureStorage()
            ensureLocalDeviceRegistered(deviceName, platform)
        }
        is WorkspaceUnlockState.Unlocked -> ensureLocalDeviceRegistered(deviceName, platform)
    }
}

private fun WorkspaceKeyRepository.unlockedOrUnlock(): WorkspaceMasterKey? {
    unlockedKeyOrNull()?.let { return it }
    if (startupState() is WorkspaceUnlockState.Locked) {
        unlockWithSecureStorage()
    }
    return unlockedKeyOrNull()
}

private fun ensureActiveDeviceId(
    settingsRepository: ClientSettingsRepository,
    deviceId: String,
) {
    val loaded = settingsRepository.load()
    if (loaded.activeDeviceId == ClientSettings.DefaultActiveDeviceId) {
        settingsRepository.save(loaded.copy(activeDeviceId = deviceId))
    }
}


private fun String.toExportFileStamp(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "local" }

private fun DayOneImportSummary.toSettingsImportSummary(): SettingsImportSummary =
    SettingsImportSummary(
        sourceName = "Day One",
        success = true,
        message = toUserMessage(),
        journalsImported = journalsImported,
        notebooksCreated = notebooksCreated,
        notebooksReused = notebooksReused,
        notesCreated = notesCreated,
        notesUpdated = notesUpdated,
        notesSkipped = notesSkipped,
        richTextConverted = richTextConverted,
        mediaReferenced = photosReferenced + audiosReferenced + videosReferenced + pdfsReferenced,
        unsupportedItems = unsupportedEmbeddedObjects + tagsFound + starredFound + pinnedFound + weatherFound,
    )
