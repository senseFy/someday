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
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.notes.NotesRepository
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ManualSyncProgressListener
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.WorkspacePairingInvitationCanceller
import saien.someday.domain.settings.WorkspacePairingInvitationCreator
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.sync.AuthorityCoordinatedMediaAssetStore
import saien.someday.sync.createSystemV3ClientServices
import saien.someday.sync.selfhosted.IosSelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedSetupService
import saien.someday.sync.selfhosted.SelfHostedWorkspacePairingService
import saien.someday.sync.selfhosted.SystemV3MediaCoordinator
import saien.someday.sync.selfhosted.WorkspaceBoundSessionCredentialStore
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
import okio.Path.Companion.toPath

class IosClientRepositories(
    val notesRepository: NotesRepository,
    val settingsRepository: ClientSettingsRepository,
    val selfHostedSetupClient: SelfHostedSetupClient,
    val selfHostedSessionCredentialStore: SelfHostedSessionCredentialStore,
    val manualSyncRunner: ManualSyncRunner,
    val bindManualSyncProgressListener: (ManualSyncProgressListener?) -> Unit = {},
    val workspacePairingInvitationCreator: WorkspacePairingInvitationCreator,
    val workspacePairingInvitationJoiner: WorkspacePairingInvitationJoiner,
    val workspacePairingInvitationCanceller: WorkspacePairingInvitationCanceller,
    val localMediaAssetStore: AuthorityCoordinatedMediaAssetStore,
    val mediaCoordinator: SystemV3MediaCoordinator,
    private val localDataExporter: LocalDataExporter,
    private val localDataImportProvider: (saien.someday.data.export.LocalDataExportDocument) ->
        saien.someday.data.export.LocalDataImportSummary,
    private val driver: NativeSqliteDriver,
    private val selfHostedTransport: IosSelfHostedSyncTransport,
) {
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        try {
            selfHostedTransport.close()
        } finally {
            driver.close()
        }
    }

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
            includesMediaBytes = document.includesMediaBytes,
            assetReferencesMayBeUnresolved = document.assetReferencesMayBeUnresolved,
            destinationLabel = filePath,
        )
    }

    fun importDayOneArchive(
        archiveBytes: ByteArray,
        fallbackJournalTitle: String,
    ): SettingsImportSummary =
        DayOneImportService(localDataImportProvider)
            .importArchive(archiveBytes, fallbackJournalTitle)
            .toSettingsImportSummary()
}

fun createIosClientRepositories(): IosClientRepositories {
    val localData = createIosLocalDataRepository(resolveIosLocalDeviceId())
    return runCatching {
        assembleIosClientRepositoriesWithOwnedTransport(localData)
    }.getOrElse { failure ->
        runCatching { localData.driver.close() }
        throw failure
    }
}

private fun assembleIosClientRepositoriesWithOwnedTransport(
    localData: IosLocalData,
): IosClientRepositories {
    val selfHostedTransport = IosSelfHostedSyncTransport()
    return runCatching {
        assembleIosClientRepositories(localData, selfHostedTransport)
    }.getOrElse { failure ->
        runCatching { selfHostedTransport.close() }
        throw failure
    }
}

private fun assembleIosClientRepositories(
    localData: IosLocalData,
    selfHostedTransport: IosSelfHostedSyncTransport,
): IosClientRepositories {
    val localRepository = localData.repository
    val settingsRepository = SqlDelightClientSettingsRepository(localRepository)
    ensureActiveDeviceId(
        settingsRepository = settingsRepository,
        deviceId = localRepository.localDeviceId,
    )
    val workspaceKeys = bootstrapIosWorkspaceKeys(localRepository)
    val selfHostedSessionCredentialStore = IosSelfHostedSessionCredentialStore()
    val localMediaAssetStore = LocalMediaAssetStore(
        database = localRepository.database,
        appPrivateRoot = "${NSHomeDirectory()}/Library/Application Support/Someday".toPath(),
        decodeValidator = IosMediaAssetDecodeValidator,
    )
    val systemV3Services = createSystemV3ClientServices(
        localRepository = localRepository,
        settingsRepository = settingsRepository,
        workspaceKeyProvider = workspaceKeys::unlockedOrUnlock,
        workspaceIdProvider = workspaceKeys::workspaceIdOrNull,
        localMediaAssetStore = localMediaAssetStore,
        selfHostedTransport = selfHostedTransport,
        selfHostedTransportV2 = selfHostedTransport,
        selfHostedMediaTransportV3 = selfHostedTransport,
        selfHostedSessionStore = selfHostedSessionCredentialStore,
    )
    runCatching { systemV3Services.localMediaAssetStore.cleanupOrphans() }
    val workspaceJoinPackageProvider = workspaceKeys.workspaceJoinPackageProvider()
    val workspaceJoiner = workspaceKeys.workspaceJoiner(
        deviceName = "iOS device",
        platform = "ios",
        adoptionPolicy = systemV3Services.workspaceAdoptionPolicy,
        beforeWorkspaceReplacement = systemV3Services.discardEmptyDraftForWorkspaceAdoption,
        afterWorkspaceReplacement = systemV3Services.bindAdoptedWorkspaceToCurrentSession,
    )
    val selfHostedPairingService = SelfHostedWorkspacePairingService(
        settingsProvider = settingsRepository::load,
        sessionStore = selfHostedSessionCredentialStore,
        transport = selfHostedTransport,
        sessionExecutor = systemV3Services.selfHostedSessionExecutor,
        workspaceJoinPackageProvider = workspaceJoinPackageProvider,
        workspaceJoiner = workspaceJoiner,
        adoptionPolicy = systemV3Services.workspaceAdoptionPolicy,
        authorityMutationCoordinator = systemV3Services.authorityMutationCoordinator,
        activeWorkspaceSessionGuard = systemV3Services.activeWorkspaceSessionGuard,
        workspacePairingInviterReady = systemV3Services.workspacePairingInviterReady,
    )
    return IosClientRepositories(
        notesRepository = systemV3Services.notesRepository,
        settingsRepository = systemV3Services.settingsRepository,
        selfHostedSetupClient = SelfHostedSetupService(
            transport = selfHostedTransport,
            sessionStore = selfHostedSessionCredentialStore,
            activeWorkspaceSessionGuard = systemV3Services.activeWorkspaceSessionGuard,
            authorityMutationCoordinator = systemV3Services.authorityMutationCoordinator,
            localDeviceIdProvider = { localRepository.localDeviceId },
        ),
        selfHostedSessionCredentialStore = WorkspaceBoundSessionCredentialStore(
            selfHostedSessionCredentialStore,
            systemV3Services.activeWorkspaceSessionGuard,
        ),
        manualSyncRunner = systemV3Services.manualSyncRunner,
        bindManualSyncProgressListener = systemV3Services.bindManualSyncProgressListener,
        workspacePairingInvitationCreator = selfHostedPairingService,
        workspacePairingInvitationJoiner = selfHostedPairingService,
        workspacePairingInvitationCanceller = selfHostedPairingService,
        localMediaAssetStore = systemV3Services.localMediaAssetStore,
        mediaCoordinator = systemV3Services.mediaCoordinator,
        localDataExporter = LocalDataExporter(
            authoritativeDocumentProvider = systemV3Services.localDataExportProvider,
        ),
        localDataImportProvider = systemV3Services.localDataImportProvider,
        driver = localData.driver,
        selfHostedTransport = selfHostedTransport,
    )
}

private fun createIosLocalDataRepository(deviceId: String): IosLocalData {
    val driver = NativeSqliteDriver(SomedayDatabase.Schema, "someday.db")
    return runCatching {
        IosLocalData(
            repository = SqlDelightLocalDataRepository(
                database = SomedayDatabase(driver),
                deviceId = deviceId,
            ),
            driver = driver,
        )
    }.getOrElse { failure ->
        driver.close()
        throw failure
    }
}

private data class IosLocalData(
    val repository: SqlDelightLocalDataRepository,
    val driver: NativeSqliteDriver,
)

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
        includesMediaBytes = false,
        assetReferencesMayBeUnresolved = true,
    )
