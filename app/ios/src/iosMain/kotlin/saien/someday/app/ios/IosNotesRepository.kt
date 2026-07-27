@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package saien.someday.app.ios

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import saien.someday.data.crypto.IosSecureWorkspaceKeyStore
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
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.settings.ClientSettings
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
import saien.someday.sync.selfhosted.IosSelfHostedSyncTransport
import saien.someday.sync.selfhosted.ModeRoutingWorkspacePairingService
import saien.someday.sync.selfhosted.SelfHostedSetupService
import saien.someday.sync.selfhosted.SelfHostedWorkspacePairingService
import saien.someday.sync.webdav.IosWebDavTransport
import saien.someday.sync.webdav.WebDavBackupService
import saien.someday.sync.webdav.WebDavWorkspacePairingService
import saien.someday.ui.settings.SettingsExportSummary
import saien.someday.ui.settings.SettingsImportSummary
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.writeToFile

data class IosClientRepositories(
    val notesRepository: NotesRepository,
    val settingsRepository: ClientSettingsRepository,
    val webDavBackupService: WebDavBackupService,
    val webDavCredentialStore: WebDavCredentialStore,
    val selfHostedSetupClient: SelfHostedSetupClient,
    val selfHostedSessionCredentialStore: SelfHostedSessionCredentialStore,
    val manualSyncRunner: ManualSyncRunner,
    val syncV2MaintenanceRunner: SyncV2MaintenanceRunner,
    val workspacePairingInvitationCreator: WorkspacePairingInvitationCreator,
    val workspacePairingInvitationJoiner: WorkspacePairingInvitationJoiner,
    val workspacePairingInvitationCanceller: WorkspacePairingInvitationCanceller,
    val webDavDiscoveredDevicesRunner: WebDavDiscoveredDevicesRunner,
    private val localRepository: SqlDelightLocalDataRepository,
    private val localDataExporter: LocalDataExporter,
    private val localDataImportProvider: (saien.someday.data.export.LocalDataExportDocument) ->
        saien.someday.data.export.LocalDataImportSummary?,
) {
    fun exportLocalDataSummary(): SettingsExportSummary {
        val document = localDataExporter.exportDocument()
        val exportDirectory = "${NSHomeDirectory()}/Documents/Someday Exports"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = exportDirectory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val filePath = "$exportDirectory/someday-export-${document.exportedAt.toExportFileStamp()}.json"
        val saved = NSString.create(string = localDataExporter.encodeDocument(document)).writeToFile(
            path = filePath,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        require(saved) { "iOS could not save the local export." }
        return SettingsExportSummary(
            formatName = "${document.format}+json",
            notebookCount = document.notebooks.size,
            noteCount = document.notes.size,
            excludedSensitiveFields = document.excludedSensitiveFields,
            destinationLabel = filePath,
        )
    }

    fun importDayOneArchive(
        archiveBytes: ByteArray,
        fallbackJournalTitle: String,
    ): SettingsImportSummary =
        DayOneImportService(localRepository, localDataImportProvider)
            .importArchive(archiveBytes, fallbackJournalTitle)
            .toSettingsImportSummary()
}

fun createIosClientRepositories(): IosClientRepositories {
    val localRepository = createIosLocalDataRepository(resolveIosLocalDeviceId())
    val settingsRepository = SqlDelightClientSettingsRepository(localRepository)
    ensureActiveDeviceId(
        settingsRepository = settingsRepository,
        deviceId = localRepository.localDeviceId,
    )
    val workspaceKeys = bootstrapIosWorkspaceKeys(localRepository)
    val webDavTransport = IosWebDavTransport()
    val webDavCredentialStore = IosWebDavCredentialStore()
    val selfHostedTransport = IosSelfHostedSyncTransport()
    val selfHostedSessionCredentialStore = IosSelfHostedSessionCredentialStore()
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
            IosBuildConfig.SOMEDAY_SYSTEM_V2_RELEASE_ENABLED ||
                IosBuildConfig.SOMEDAY_SYSTEM_V2_DEVELOPMENT_ENABLED,
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
        deviceName = "iOS device",
        platform = "ios",
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
    return IosClientRepositories(
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
    )
}

private fun createIosLocalDataRepository(deviceId: String): SqlDelightLocalDataRepository {
    val driver = NativeSqliteDriver(SomedayDatabase.Schema, "someday.db")
    val database = SomedayDatabase(driver)
    return SqlDelightLocalDataRepository(
        database = database,
        deviceId = deviceId,
    )
}

private fun resolveIosLocalDeviceId(): String {
    val defaults = NSUserDefaults.standardUserDefaults
    defaults.stringForKey(IosDeviceIdPreferenceKey)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val generated = NSUUID().UUIDString.lowercase()
    defaults.setObject(generated, forKey = IosDeviceIdPreferenceKey)
    return generated
}

private fun bootstrapIosWorkspaceKeys(localRepository: SqlDelightLocalDataRepository): WorkspaceKeyRepository =
    WorkspaceKeyRepository(
        localRepository = localRepository,
        secureKeyStore = IosSecureWorkspaceKeyStore(),
    ).also { workspaceKeys ->
        workspaceKeys.bootstrapIfNeeded(
            deviceName = "iOS device",
            platform = "ios",
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

private const val IosDeviceIdPreferenceKey = "local_device_id_v2"

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
