package saien.someday.integration.testkit

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import okio.Path.Companion.toPath
import saien.someday.data.crypto.InMemorySecureWorkspaceKeyStore
import saien.someday.data.crypto.SecureStorageAliasGenerator
import saien.someday.data.crypto.WorkspaceKeyRepository
import saien.someday.data.crypto.WorkspaceUnlockResult
import saien.someday.data.crypto.workspaceJoinPackageProvider
import saien.someday.data.crypto.workspaceJoiner
import saien.someday.data.crypto.workspaceRecoveryPackageProvider
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
import saien.someday.sync.createSystemV3ClientServices
import saien.someday.sync.selfhosted.SelfHostedAccountResetManager
import saien.someday.sync.selfhosted.SelfHostedSetupService
import saien.someday.sync.selfhosted.SelfHostedWorkspacePairingService
import saien.someday.sync.selfhosted.SelfHostedWorkspaceRecoveryService

/**
 * A real file-backed client installation. restart() closes JDBC and recreates every
 * client service/key cache against the same SQLite and media files. Only the test
 * secure-store adapters survive; this does not claim an OS process or Keychain test.
 */
internal class AccountResetInstallation(
    val endpoint: String,
    val account: TestAccount,
    val transport: AccountResetJourneyTransport,
    private val label: String,
) : AutoCloseable {
    val writerId = UUID.randomUUID().toString()
    private val root = Files.createTempDirectory("someday-reset-acceptance-$label-")
    private val secureKeys = InMemorySecureWorkspaceKeyStore()
    val sessionStore = TestSessionCredentialStore()
    var process = ClientProcess(firstRun = true)
        private set

    fun restart() {
        process.close()
        process = ClientProcess(firstRun = false)
    }

    inner class ClientProcess(firstRun: Boolean) : AutoCloseable {
        private val driver = createSomedayJdbcDriver("jdbc:sqlite:${root.resolve("someday.db").toAbsolutePath()}")
        val database = SomedayDatabase(driver)
        val local = SqlDelightLocalDataRepository(database, writerId)
        private val settings = SqlDelightClientSettingsRepository(local)
        val keys = WorkspaceKeyRepository(
            local,
            secureKeys,
            aliasGenerator = object : SecureStorageAliasGenerator {
                override fun newAlias(workspaceId: String) = "reset-acceptance-${UUID.randomUUID()}"
            },
        )

        init {
            if (firstRun) {
                settings.saveLocalSnapshot(ClientSettings(activeDeviceId = writerId))
                keys.createFirstRunWorkspace(label, "desktop")
            } else {
                check(keys.unlockWithSecureStorage() is WorkspaceUnlockResult.Unlocked)
            }
        }

        val services = createSystemV3ClientServices(
            localRepository = local,
            settingsRepository = settings,
            workspaceKeyProvider = keys::unlockedKeyOrNull,
            workspaceIdProvider = keys::workspaceIdOrNull,
            localMediaAssetStore = LocalMediaAssetStore(
                database,
                root.resolve("media").toString().toPath(),
                decodeValidator = MediaAssetDecodeValidator { source ->
                    val decoded = checkNotNull(ImageIO.read(ByteArrayInputStream(source.readByteArray())))
                    DecodedMediaAsset(decoded.width, decoded.height)
                },
            ),
            selfHostedTransport = transport,
            selfHostedTransportV2 = transport,
            selfHostedMediaTransportV3 = transport,
            selfHostedSessionStore = sessionStore,
        )
        private val joiner = keys.workspaceJoiner(
            label,
            "desktop",
            services.discardLocalWorkspaceForReplacement,
            services.bindReplacementWorkspaceToCurrentSession,
            services.finalizeLocalWorkspaceReplacement,
        )
        val pairing = SelfHostedWorkspacePairingService(
            services.settingsRepository::load,
            sessionStore,
            transport,
            services.selfHostedSessionExecutor,
            keys.workspaceJoinPackageProvider(),
            joiner,
            services.workspaceLifecycleCoordinator,
            services.activeWorkspaceSessionGuard,
            services.workspacePairingInviterReady,
        )
        val recovery = SelfHostedWorkspaceRecoveryService(
            services.settingsRepository::load,
            sessionStore,
            transport,
            services.selfHostedSessionExecutor,
            keys.workspaceRecoveryPackageProvider(),
            joiner,
            services.workspaceLifecycleCoordinator,
            services.activeWorkspaceSessionGuard,
            services.workspacePairingInviterReady,
            { keys.unlockedKeyOrNull()?.fingerprint },
        )
        val reset = SelfHostedAccountResetManager(
            services,
            sessionStore,
            transport,
            services.settingsRepository,
            keys::workspaceIdOrNull,
            { writerId },
            label,
            "desktop",
            { before, after ->
                keys.replaceWithFreshWorkspace(label, "desktop", before, after)
                Unit
            },
            pairing,
            recovery,
            { keys.unlockedKeyOrNull() != null },
        )

        fun connect(createAccount: Boolean) {
            updateSettings(null)
            val setup = SelfHostedSetupService(
                transport,
                sessionStore,
                services.activeWorkspaceSessionGuard,
                services.workspaceLifecycleCoordinator,
                localDeviceIdProvider = { writerId },
            )
            val result = setup.setup(
                SelfHostedSetupInput(endpoint, account.email, account.password, label, "desktop", createAccount),
            )
            check(result.success) { "Real HTTP setup failed: ${result.status.reason.name}" }
            updateSettings(checkNotNull(result.session))
        }

        private fun updateSettings(session: SelfHostedSessionSummary?) {
            val current = services.settingsRepository.load()
            services.settingsRepository.saveLocalSnapshot(
                current.copy(
                    activeDeviceId = writerId,
                    syncConfiguration = SyncConfiguration(
                        SyncMode.SelfHosted,
                        endpoint,
                        session ?: current.syncConfiguration.selfHostedSession,
                    ),
                ),
            )
        }

        override fun close() {
            keys.lock()
            driver.close()
        }
    }

    override fun close() {
        try {
            process.close()
        } finally {
            try {
                transport.close()
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }
}
