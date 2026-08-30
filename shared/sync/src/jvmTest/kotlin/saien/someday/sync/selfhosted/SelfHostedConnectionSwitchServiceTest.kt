package saien.someday.sync.selfhosted

import saien.someday.data.crypto.InMemorySecureWorkspaceKeyStore
import saien.someday.data.crypto.WorkspaceKeyRepository
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.authorityBindingId
import saien.someday.sync.WorkspaceLifecycleCoordinator
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.discardLocalWorkspaceForReplacementV2
import saien.someday.sync.causality.v2.ensureWorkspaceLocalDraftV2
import saien.someday.sync.resolveActiveWorkspaceSessionRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SelfHostedConnectionSwitchServiceTest {
    @Test
    fun unboundDraftKeepsWorkspaceAndOnlyForgetsTheAttemptedConnection() = withFixture { fixture ->
        val originalWorkspaceId = fixture.keys.workspaceIdOrNull()
        val originalFingerprint = fixture.keys.unlockedKeyOrNull()?.fingerprint
        val originalEpoch = fixture.protocol.loadAllEpochs().single().descriptor.syncEpochId
        var discardCalls = 0
        val service = fixture.service(
            discard = {
                discardCalls += 1
                discardLocalWorkspaceForReplacementV2(fixture.local, fixture.settings)
            },
        )

        val result = service.switchConnection()

        assertTrue(result.success)
        assertFalse(result.workspaceReplaced)
        assertEquals(0, discardCalls)
        assertEquals(originalWorkspaceId, fixture.keys.workspaceIdOrNull())
        assertEquals(originalFingerprint, fixture.keys.unlockedKeyOrNull()?.fingerprint)
        assertEquals(originalEpoch, fixture.protocol.loadAllEpochs().single().descriptor.syncEpochId)
        assertNull(fixture.sessions.load())
        assertResetConnection(fixture)
    }

    @Test
    fun boundWorkspaceIsReplacedWithFreshIdentityBeforeAnotherConnectionCanBeChosen() =
        withFixture { fixture ->
            fixture.bindWorkspace()
            val originalWorkspaceId = fixture.keys.workspaceIdOrNull()
            val originalFingerprint = fixture.keys.unlockedKeyOrNull()?.fingerprint
            val originalEpoch = fixture.protocol.loadAuthoritativeEpoch()?.descriptor?.syncEpochId
            var finalized = 0
            val service = fixture.service(
                discard = { discardLocalWorkspaceForReplacementV2(fixture.local, fixture.settings) },
                finalize = { finalized += 1 },
            )

            val result = service.switchConnection()

            assertTrue(result.success)
            assertTrue(result.workspaceReplaced)
            assertNotEquals(originalWorkspaceId, fixture.keys.workspaceIdOrNull())
            assertNotEquals(originalFingerprint, fixture.keys.unlockedKeyOrNull()?.fingerprint)
            assertNull(fixture.protocol.loadEpoch(SyncRemoteProfileV2.SELF_HOSTED.wireValue, originalEpoch!!))
            assertNull(fixture.protocol.loadAuthoritativeEpoch())
            assertNull(fixture.protocol.loadLocalAuthority())
            assertEquals(1, fixture.protocol.loadAllEpochs().size)
            assertEquals(1, finalized)
            assertNull(fixture.sessions.load())
            assertResetConnection(fixture)
        }

    @Test
    fun failedBoundReplacementRollsBackWorkspaceAndRestoresTheSession() = withFixture { fixture ->
        fixture.bindWorkspace()
        val originalWorkspaceId = fixture.keys.workspaceIdOrNull()
        val originalFingerprint = fixture.keys.unlockedKeyOrNull()?.fingerprint
        val originalSettings = fixture.settings.load()
        val originalSession = assertNotNull(fixture.sessions.load())
        val service = fixture.service(discard = { false })

        val result = service.switchConnection()

        assertFalse(result.success)
        assertEquals(originalWorkspaceId, fixture.keys.workspaceIdOrNull())
        assertEquals(originalFingerprint, fixture.keys.unlockedKeyOrNull()?.fingerprint)
        assertEquals(originalSettings, fixture.settings.load())
        assertEquals(originalSession, fixture.sessions.load())
        assertNotNull(fixture.protocol.loadAuthoritativeEpoch())
    }

    @Test
    fun sessionClearFailurePreservesWorkspaceAndRestoresTheSession() = withFixture { fixture ->
        fixture.bindWorkspace()
        val originalWorkspaceId = fixture.keys.workspaceIdOrNull()
        val originalFingerprint = fixture.keys.unlockedKeyOrNull()?.fingerprint
        val originalSettings = fixture.settings.load()
        val originalSession = assertNotNull(fixture.sessions.load())
        fixture.sessions.failNextClearAfterRemoval = true

        val result = fixture.service(
            discard = { discardLocalWorkspaceForReplacementV2(fixture.local, fixture.settings) },
        ).switchConnection()

        assertFalse(result.success)
        assertEquals(originalWorkspaceId, fixture.keys.workspaceIdOrNull())
        assertEquals(originalFingerprint, fixture.keys.unlockedKeyOrNull()?.fingerprint)
        assertEquals(originalSettings, fixture.settings.load())
        assertEquals(originalSession, fixture.sessions.load())
        assertNotNull(fixture.protocol.loadAuthoritativeEpoch())
    }

    private fun assertResetConnection(fixture: Fixture) {
        val configuration = fixture.settings.load().syncConfiguration
        assertEquals(SyncMode.Off, configuration.mode)
        assertNull(configuration.selfHostedEndpoint)
        assertFalse(configuration.selfHostedSession.loggedIn)
        assertNull(configuration.lastError)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val database = SomedayDatabase(driver)
            val local = SqlDelightLocalDataRepository(database, DEVICE_ID, clock = { NOW })
            val settings = SqlDelightClientSettingsRepository(local)
            val keys = WorkspaceKeyRepository(
                localRepository = local,
                secureKeyStore = InMemorySecureWorkspaceKeyStore(),
                clock = { NOW },
            )
            keys.createFirstRunWorkspace("Desktop device", "desktop")
            val credentials = credentials()
            settings.save(
                ClientSettings(
                    activeDeviceId = DEVICE_ID,
                    syncConfiguration = SyncConfiguration(
                        mode = SyncMode.SelfHosted,
                        selfHostedEndpoint = credentials.endpoint,
                        selfHostedSession = credentials.toSummary(),
                        lastError = "setup:Failed",
                    ),
                ),
            )
            val key = assertNotNull(keys.unlockedKeyOrNull())
            ensureWorkspaceLocalDraftV2(local, settings, key)
            val sessions = InMemorySessionStore(credentials)
            val protocol = SqlDelightSyncProtocolStoreV2(database)
            val guard = ActiveWorkspaceSessionGuard {
                resolveActiveWorkspaceSessionRequirement(protocol, keys::workspaceIdOrNull)
            }
            block(Fixture(local, settings, keys, sessions, protocol, guard))
        } finally {
            driver.close()
        }
    }

    private data class Fixture(
        val local: SqlDelightLocalDataRepository,
        val settings: SqlDelightClientSettingsRepository,
        val keys: WorkspaceKeyRepository,
        val sessions: InMemorySessionStore,
        val protocol: SqlDelightSyncProtocolStoreV2,
        val guard: ActiveWorkspaceSessionGuard,
    ) {
        fun bindWorkspace() {
            val draft = protocol.loadAllEpochs().single()
            val credentials = assertNotNull(sessions.load())
            protocol.activateEpoch(
                remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
                epochId = draft.descriptor.syncEpochId,
                activatedAt = NOW,
                localWriterDeviceId = DEVICE_ID,
                authorityBindingId = credentials.authorityBindingId,
            )
        }

        fun service(
            discard: () -> Boolean,
            finalize: () -> Unit = {},
        ): SelfHostedConnectionSwitchService =
            SelfHostedConnectionSwitchService(
                localRepository = local,
                settingsRepository = settings,
                workspaceKeyRepository = keys,
                sessionStore = sessions,
                activeWorkspaceSessionGuard = guard,
                workspaceLifecycleCoordinator = WorkspaceLifecycleCoordinator(),
                discardLocalWorkspaceForReplacement = discard,
                finalizeLocalWorkspaceReplacement = finalize,
                deviceName = "Desktop device",
                platform = "desktop",
            )
    }

    private class InMemorySessionStore(
        initial: SelfHostedSessionCredentials,
    ) : SelfHostedSessionCredentialStore {
        private val sessions = mutableMapOf(initial.authorityBindingId to initial)
        private var activeAuthority: String? = initial.authorityBindingId
        var failNextClearAfterRemoval: Boolean = false

        override fun load(): SelfHostedSessionCredentials? = activeAuthority?.let(sessions::get)

        override fun save(credentials: SelfHostedSessionCredentials) {
            activeAuthority = credentials.authorityBindingId
            sessions[credentials.authorityBindingId] = credentials
        }

        override fun clear() {
            activeAuthority = null
            sessions.clear()
            if (failNextClearAfterRemoval) {
                failNextClearAfterRemoval = false
                error("injected session clear failure")
            }
        }

        override fun loadForAuthority(authorityBindingId: String): SelfHostedSessionCredentials? =
            sessions[authorityBindingId]

        override fun saveForAuthority(authorityBindingId: String, credentials: SelfHostedSessionCredentials) {
            require(authorityBindingId == credentials.authorityBindingId)
            sessions[authorityBindingId] = credentials
        }

        override fun clearAuthority(authorityBindingId: String) {
            sessions.remove(authorityBindingId)
            if (activeAuthority == authorityBindingId) activeAuthority = null
        }
    }

    private companion object {
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000001"
        val NOW: Instant = Instant.fromEpochMilliseconds(1_000)

        fun credentials(): SelfHostedSessionCredentials =
            SelfHostedSessionCredentials(
                endpoint = "http://127.0.0.1:3180",
                userId = "user-1",
                userEmail = "owner@example.test",
                deviceId = DEVICE_ID,
                deviceName = "Desktop device",
                devicePlatform = "desktop",
                accessToken = "access-token",
                refreshToken = "refresh-token",
            )
    }
}
