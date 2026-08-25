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
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.SystemV2ClientSettingsRepository
import saien.someday.sync.causality.v2.SystemV2NotesRepository
import saien.someday.sync.causality.v2.ensureWorkspaceLocalDraftV2
import saien.someday.sync.createSystemV3ClientServices
import saien.someday.sync.selfhosted.AndroidSelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedSetupService
import saien.someday.sync.selfhosted.SelfHostedWorkspacePairingService
import saien.someday.sync.selfhosted.SystemV3MediaCoordinator
import saien.someday.sync.selfhosted.WorkspaceBoundSessionCredentialStore
import saien.someday.ui.settings.SettingsExportSummary
import saien.someday.ui.settings.SettingsImportSummary
import java.io.File
import java.util.UUID
import okio.Path.Companion.toPath

class AndroidClientRepositories(
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
    private val driver: AndroidSqliteDriver,
    private val selfHostedTransport: AndroidSelfHostedSyncTransport,
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

    fun importDayOneArchive(
        archiveBytes: ByteArray,
        fallbackJournalTitle: String,
    ): SettingsImportSummary =
        DayOneImportService(localDataImportProvider)
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
        val localSettings = SqlDelightClientSettingsRepository(localRepository)
        ensureActiveDeviceId(localSettings, localRepository.localDeviceId)
        val workspaceKeys = bootstrapAndroidWorkspaceKeys(context, localRepository)
        val keyProvider = workspaceKeys::unlockedOrUnlock
        keyProvider()?.let { ensureWorkspaceLocalDraftV2(localRepository, localSettings, it) }
        val profile = { SyncRemoteProfileV2.SELF_HOSTED.wireValue }
        val settings = SystemV2ClientSettingsRepository(
            localRepository,
            localSettings,
            keyProvider,
            { localRepository.localDeviceId },
            profile,
        )
        AndroidOnThisDayRepositories(
            notesRepository = SystemV2NotesRepository(
                localRepository,
                keyProvider,
                { localRepository.localDeviceId },
                profile,
            ),
            settingsRepository = settings,
            driver = driver,
        )
    }.getOrElse { failure ->
        driver.close()
        throw failure
    }
}

fun createAndroidClientRepositories(context: Context): AndroidClientRepositories {
    val localData = createAndroidLocalDataRepository(context, resolveAndroidLocalDeviceId(context))
    return runCatching {
        assembleAndroidClientRepositoriesWithOwnedTransport(context, localData)
    }.getOrElse { failure ->
        runCatching { localData.driver.close() }
        throw failure
    }
}

private fun assembleAndroidClientRepositoriesWithOwnedTransport(
    context: Context,
    localData: AndroidLocalData,
): AndroidClientRepositories {
    val selfHostedTransport = AndroidSelfHostedSyncTransport()
    return runCatching {
        assembleAndroidClientRepositories(context, localData, selfHostedTransport)
    }.getOrElse { failure ->
        runCatching { selfHostedTransport.close() }
        throw failure
    }
}

private fun assembleAndroidClientRepositories(
    context: Context,
    localData: AndroidLocalData,
    selfHostedTransport: AndroidSelfHostedSyncTransport,
): AndroidClientRepositories {
    val localRepository = localData.repository
    val settingsRepository = SqlDelightClientSettingsRepository(localRepository)
    ensureActiveDeviceId(
        settingsRepository = settingsRepository,
        deviceId = localRepository.localDeviceId,
    )
    val workspaceKeys = bootstrapAndroidWorkspaceKeys(
        context = context,
        localRepository = localRepository,
    )
    val selfHostedSessionCredentialStore = AndroidSelfHostedSessionCredentialStore(context)
    val localMediaAssetStore = LocalMediaAssetStore(
        database = localRepository.database,
        appPrivateRoot = context.filesDir.absolutePath.toPath(),
        decodeValidator = AndroidMediaAssetDecodeValidator,
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
        deviceName = "Android device",
        platform = "android",
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
    return AndroidClientRepositories(
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
        exportDirectory = File(context.filesDir, "exports"),
        driver = localData.driver,
        selfHostedTransport = selfHostedTransport,
    )
}

private fun createAndroidLocalDataRepository(
    context: Context,
    deviceId: String,
): AndroidLocalData {
    val driver = AndroidSqliteDriver(
        schema = SomedayDatabase.Schema,
        context = context,
        name = "someday.db",
    )
    return runCatching {
        AndroidLocalData(
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

private data class AndroidLocalData(
    val repository: SqlDelightLocalDataRepository,
    val driver: AndroidSqliteDriver,
)

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
        includesMediaBytes = false,
        assetReferencesMayBeUnresolved = true,
    )
