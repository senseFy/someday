package saien.someday.integration.testkit

import app.cash.sqldelight.db.SqlDriver
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import okio.Path.Companion.toPath
import saien.someday.data.crypto.InMemorySecureWorkspaceKeyStore
import saien.someday.data.crypto.SecureStorageAliasGenerator
import saien.someday.data.crypto.WorkspaceKeyRepository
import saien.someday.data.crypto.workspaceJoinPackageProvider
import saien.someday.data.crypto.workspaceJoiner
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.media.DecodedMediaAsset
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.media.MediaAssetDecodeValidator
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceJoinPackageProvider
import saien.someday.domain.settings.WorkspaceJoiner
import saien.someday.sync.SystemV3ClientServices
import saien.someday.sync.createSystemV3ClientServices
import saien.someday.sync.selfhosted.SelfHostedSetupService
import saien.someday.sync.selfhosted.SelfHostedWorkspacePairingService

/** One installation: stable writer id, file-backed SQLite, key store and media directory. */
internal class TestDevice(
    private val endpoint: String,
    private val account: TestAccount,
    transport: ProbingSelfHostedTransport,
    val label: String,
    val platform: String,
) : AutoCloseable {
    val deviceId: String = UUID.randomUUID().toString()
    private val root: Path = Files.createTempDirectory("someday-system-v3-$label-")
    private val driver: SqlDriver = createSomedayJdbcDriver(
        "jdbc:sqlite:${root.resolve("someday.db").toAbsolutePath()}",
    )
    val database = SomedayDatabase(driver)
    val local = SqlDelightLocalDataRepository(database, deviceId)
    private val localSettings = SqlDelightClientSettingsRepository(local)
    val workspaceKeys = WorkspaceKeyRepository(
        localRepository = local,
        secureKeyStore = InMemorySecureWorkspaceKeyStore(),
        aliasGenerator = SequentialAliasGenerator(label),
    )
    val initialRecoveryCode: String
    val sessionStore = TestSessionCredentialStore()
    val services: SystemV3ClientServices
    val workspaceJoinPackageProvider: WorkspaceJoinPackageProvider
    val workspaceJoiner: WorkspaceJoiner
    val pairing: SelfHostedWorkspacePairingService
    private val setupService: SelfHostedSetupService

    init {
        localSettings.saveLocalSnapshot(ClientSettings(activeDeviceId = deviceId))
        initialRecoveryCode = workspaceKeys
            .createFirstRunWorkspace("$label device", platform)
            .recoveryMaterial
            .revealForUserConfirmation()
        services = createSystemV3ClientServices(
            localRepository = local,
            settingsRepository = localSettings,
            workspaceKeyProvider = workspaceKeys::unlockedKeyOrNull,
            workspaceIdProvider = workspaceKeys::workspaceIdOrNull,
            localMediaAssetStore = LocalMediaAssetStore(
                database = database,
                appPrivateRoot = root.resolve("app-private").toString().toPath(),
                decodeValidator = MediaAssetDecodeValidator { source ->
                    val image = checkNotNull(ImageIO.read(ByteArrayInputStream(source.readByteArray())))
                    DecodedMediaAsset(image.width, image.height)
                },
            ),
            selfHostedTransport = transport,
            selfHostedTransportV2 = transport,
            selfHostedMediaTransportV3 = transport,
            selfHostedSessionStore = sessionStore,
        )
        setupService = SelfHostedSetupService(
            transport = transport,
            sessionStore = sessionStore,
            activeWorkspaceSessionGuard = services.activeWorkspaceSessionGuard,
            workspaceLifecycleCoordinator = services.workspaceLifecycleCoordinator,
            localDeviceIdProvider = { deviceId },
        )
        workspaceJoinPackageProvider = workspaceKeys.workspaceJoinPackageProvider()
        workspaceJoiner = workspaceKeys.workspaceJoiner(
            deviceName = "$label device",
            platform = platform,
            beforeWorkspaceReplacement = services.discardLocalWorkspaceForReplacement,
            afterWorkspaceReplacement = services.bindReplacementWorkspaceToCurrentSession,
            afterWorkspaceReplacementCommitted = services.finalizeLocalWorkspaceReplacement,
        )
        pairing = SelfHostedWorkspacePairingService(
            settingsProvider = services.settingsRepository::load,
            sessionStore = sessionStore,
            transport = transport,
            sessionExecutor = services.selfHostedSessionExecutor,
            workspaceJoinPackageProvider = workspaceJoinPackageProvider,
            workspaceJoiner = workspaceJoiner,
            workspaceLifecycleCoordinator = services.workspaceLifecycleCoordinator,
            activeWorkspaceSessionGuard = services.activeWorkspaceSessionGuard,
            workspacePairingInviterReady = services.workspacePairingInviterReady,
        )
    }

    fun connect(createAccount: Boolean) {
        updateSyncSettings(session = null)
        val result = setupService.setup(
            SelfHostedSetupInput(
                endpoint = endpoint,
                email = account.email,
                password = account.password,
                deviceName = "$label device",
                platform = platform,
                createAccount = createAccount,
            ),
        )
        check(result.success) {
            result.status.diagnosticMessage ?: result.status.reason.name
        }
        updateSyncSettings(session = checkNotNull(result.session))
    }

    private fun updateSyncSettings(session: SelfHostedSessionSummary?) {
        val current = services.settingsRepository.load()
        services.settingsRepository.saveLocalSnapshot(
            current.copy(
                activeDeviceId = deviceId,
                syncConfiguration = SyncConfiguration(
                    mode = SyncMode.SelfHosted,
                    selfHostedEndpoint = endpoint,
                    selfHostedSession = session ?: current.syncConfiguration.selfHostedSession,
                ),
            ),
        )
    }

    override fun close() {
        driver.close()
        root.toFile().deleteRecursively()
    }
}

private class SequentialAliasGenerator(private val label: String) : SecureStorageAliasGenerator {
    private var next = 0
    override fun newAlias(workspaceId: String): String = "e2e-$label-${next++}-$workspaceId"
}
