@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.app.android

import android.content.Context
import androidx.core.content.edit
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import saien.someday.data.crypto.AndroidSecureWorkspaceKeyStore
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
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.SyncMode
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.createSyncV2ClientServices
import saien.someday.sync.selfhosted.AndroidSelfHostedSyncTransport
import saien.someday.sync.selfhosted.ModeRoutingWorkspacePairingService
import saien.someday.sync.selfhosted.SelfHostedSetupService
import saien.someday.sync.selfhosted.SelfHostedWorkspacePairingService
import saien.someday.sync.webdav.AndroidWebDavTransport
import saien.someday.sync.webdav.WebDavBackupService
import saien.someday.sync.webdav.WebDavWorkspacePairingService
import saien.someday.ui.settings.SettingsExportSummary
import saien.someday.ui.settings.SettingsImportSummary
import java.io.File
import java.util.UUID

data class AndroidClientRepositories(
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

    fun importDayOneArchive(
        archiveBytes: ByteArray,
        fallbackJournalTitle: String,
    ): SettingsImportSummary =
        DayOneImportService(localRepository, localDataImportProvider)
            .importArchive(archiveBytes, fallbackJournalTitle)
            .toSettingsImportSummary()
}

internal class AndroidOnThisDayRepositories(
    val notesRepository: NotesRepository,
    val settingsRepository: ClientSettingsRepository,
    private val driver: AndroidSqliteDriver,
) : AutoCloseable {
    override fun close() {
        driver.close()
    }
}

internal fun createAndroidOnThisDayRepositories(context: Context): AndroidOnThisDayRepositories {
    val driver = AndroidSqliteDriver(
        schema = SomedayDatabase.Schema,
        context = context,
        name = "someday.db",
    )
    return runCatching {
        val localRepository = SqlDelightLocalDataRepository(
            database = SomedayDatabase(driver),
            deviceId = resolveAndroidLocalDeviceId(context),
        )
        AndroidOnThisDayRepositories(
            notesRepository = SqlDelightNotesRepository(localRepository),
            settingsRepository = SqlDelightClientSettingsRepository(localRepository),
            driver = driver,
        )
    }.getOrElse { failure ->
        driver.close()
        throw failure
    }
}

fun createAndroidClientRepositories(context: Context): AndroidClientRepositories {
    val localRepository = createAndroidLocalDataRepository(context, resolveAndroidLocalDeviceId(context))
    val settingsRepository = SqlDelightClientSettingsRepository(localRepository)
    ensureActiveDeviceId(
        settingsRepository = settingsRepository,
        deviceId = localRepository.localDeviceId,
    )
    val workspaceKeys = bootstrapAndroidWorkspaceKeys(
        context = context,
        localRepository = localRepository,
    )
    val webDavTransport = AndroidWebDavTransport()
    val webDavCredentialStore = AndroidWebDavCredentialStore(context)
    val selfHostedTransport = AndroidSelfHostedSyncTransport()
    val selfHostedSessionCredentialStore = AndroidSelfHostedSessionCredentialStore(context)
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
            BuildConfig.SOMEDAY_SYSTEM_V2_RELEASE_ENABLED || BuildConfig.DEBUG,
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
        deviceName = "Android device",
        platform = "android",
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
    return AndroidClientRepositories(
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
        exportDirectory = File(context.filesDir, "exports"),
    )
}

private fun createAndroidLocalDataRepository(
    context: Context,
    deviceId: String,
): SqlDelightLocalDataRepository {
    val driver = AndroidSqliteDriver(
        schema = SomedayDatabase.Schema,
        context = context,
        name = "someday.db",
    )
    val database = SomedayDatabase(driver)
    return SqlDelightLocalDataRepository(
        database = database,
        deviceId = deviceId,
    )
}

private fun resolveAndroidLocalDeviceId(context: Context): String {
    val preferences = context.getSharedPreferences(AndroidDevicePreferencesName, Context.MODE_PRIVATE)
    preferences.getString(AndroidDeviceIdPreferenceKey, null)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val generated = UUID.randomUUID().toString()
    preferences.edit {
        putString(AndroidDeviceIdPreferenceKey, generated)
    }
    return generated
}

private fun bootstrapAndroidWorkspaceKeys(
    context: Context,
    localRepository: SqlDelightLocalDataRepository,
): WorkspaceKeyRepository =
    WorkspaceKeyRepository(
        localRepository = localRepository,
        secureKeyStore = AndroidSecureWorkspaceKeyStore(context),
    ).also { workspaceKeys ->
        workspaceKeys.bootstrapIfNeeded(
            deviceName = "Android device",
            platform = "android",
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

private const val AndroidDevicePreferencesName = "someday-device"
private const val AndroidDeviceIdPreferenceKey = "local_device_id_v2"

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
