@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.app.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import saien.someday.data.local.createSomedayJdbcDriver
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
import saien.someday.sync.selfhosted.JdkSelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedSetupService
import saien.someday.sync.selfhosted.SelfHostedWorkspacePairingService
import saien.someday.sync.selfhosted.SystemV3MediaCoordinator
import saien.someday.sync.selfhosted.WorkspaceBoundSessionCredentialStore
import saien.someday.ui.settings.SettingsExportSummary
import saien.someday.ui.settings.SettingsImportSummary
import java.io.File
import java.util.UUID
import okio.Path.Companion.toPath

class DesktopClientRepositories(
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
    private val exportDirectory: File,
    private val driver: JdbcSqliteDriver,
    private val selfHostedTransport: JdkSelfHostedSyncTransport,
) : AutoCloseable {
    private var closed = false

    override fun close() {
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
        exportDirectory.mkdirs()
        val file = File(exportDirectory, "someday-export-${document.exportedAt.toExportFileStamp()}.json")
        file.writeText(localDataExporter.encodeDocument(document))
        return SettingsExportSummary(
            formatName = "${document.format}+json",
            notebookCount = document.notebooks.size,
            noteCount = document.notes.size,
            excludedSensitiveFields = document.excludedSensitiveFields,
            includesMediaBytes = document.includesMediaBytes,
            assetReferencesMayBeUnresolved = document.assetReferencesMayBeUnresolved,
            destinationLabel = file.absolutePath,
        )
    }

    fun importDayOneArchive(file: File): SettingsImportSummary =
        DayOneImportService(localDataImportProvider)
            .importArchive(file.readBytes(), file.nameWithoutExtension)
            .toSettingsImportSummary()
}

fun createDesktopClientRepositories(): DesktopClientRepositories {
    val localData = createDesktopLocalDataRepository(resolveDesktopLocalDeviceId())
    return runCatching {
        assembleDesktopClientRepositoriesWithOwnedTransport(localData)
    }.getOrElse { failure ->
        runCatching { localData.driver.close() }
        throw failure
    }
}

private fun assembleDesktopClientRepositoriesWithOwnedTransport(
    localData: DesktopLocalData,
): DesktopClientRepositories {
    val selfHostedTransport = JdkSelfHostedSyncTransport()
    return runCatching {
        assembleDesktopClientRepositories(localData, selfHostedTransport)
    }.getOrElse { failure ->
        runCatching { selfHostedTransport.close() }
        throw failure
    }
}

private fun assembleDesktopClientRepositories(
    localData: DesktopLocalData,
    selfHostedTransport: JdkSelfHostedSyncTransport,
): DesktopClientRepositories {
    val localRepository = localData.repository
    val settingsRepository = SqlDelightClientSettingsRepository(localRepository)
    ensureActiveDeviceId(
        settingsRepository = settingsRepository,
        deviceId = localRepository.localDeviceId,
    )
    val workspaceKeys = bootstrapDesktopWorkspaceKeys(localRepository)
    val selfHostedSessionCredentialStore = DesktopSelfHostedSessionCredentialStore()
    val localMediaAssetStore = LocalMediaAssetStore(
        database = localRepository.database,
        appPrivateRoot = File(System.getProperty("user.home"), ".someday").absolutePath.toPath(),
        decodeValidator = DesktopMediaAssetDecodeValidator,
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
        deviceName = "Desktop device",
        platform = "desktop",
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
    return DesktopClientRepositories(
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
        exportDirectory = File(File(System.getProperty("user.home"), ".someday"), "exports"),
        driver = localData.driver,
        selfHostedTransport = selfHostedTransport,
    )
}

private fun createDesktopLocalDataRepository(deviceId: String): DesktopLocalData {
    val databaseFile = File(
        File(System.getProperty("user.home"), ".someday"),
        "someday.db",
    )
    databaseFile.parentFile?.mkdirs()
    val driver = createSomedayJdbcDriver("jdbc:sqlite:${databaseFile.absolutePath}")
    return runCatching {
        DesktopLocalData(
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

private data class DesktopLocalData(
    val repository: SqlDelightLocalDataRepository,
    val driver: JdbcSqliteDriver,
)

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
        includesMediaBytes = false,
        assetReferencesMayBeUnresolved = true,
    )
