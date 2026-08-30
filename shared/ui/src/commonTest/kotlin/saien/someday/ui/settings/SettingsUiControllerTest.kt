package saien.someday.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import saien.someday.domain.notifications.OnThisDayNotificationScheduler
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.EditorPreferences
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SelfHostedConnectionSwitchResult
import saien.someday.domain.settings.SelfHostedConnectionSwitcher
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SelfHostedSetupReason
import saien.someday.domain.settings.SelfHostedSetupResult
import saien.someday.domain.settings.SelfHostedSetupStatus
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspacePairingInvitation
import saien.someday.domain.settings.WorkspacePairingInvitationCanceller
import saien.someday.domain.settings.WorkspacePairingInvitationCreator
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.domain.settings.WorkspacePairingInvitationResult
import saien.someday.domain.settings.WorkspacePairingReason
import saien.someday.domain.settings.WorkspaceRecoveryCode
import saien.someday.domain.settings.WorkspaceRecoveryCodeResult
import saien.someday.domain.settings.WorkspaceRecoveryManager
import saien.someday.domain.settings.WorkspaceRecoveryReason
import saien.someday.domain.settings.WorkspaceRecoveryRestoreResult
import saien.someday.domain.settings.WorkspaceRecoveryState
import saien.someday.domain.settings.WorkspaceRecoveryStatusResult
import saien.someday.domain.settings.WorkspaceRecoverySyncGate
import saien.someday.domain.settings.WorkspacePreferencesSnapshot
import saien.someday.domain.settings.WorkspacePreferencesSyncState
import saien.someday.domain.settings.WorkspacePreferencesSyncStatus
import saien.someday.domain.settings.resetBoundWorkspaceForConnectionSwitch
import saien.someday.ui.i18n.SettingsUiStrings
import saien.someday.ui.notes.InMemoryNotesRepository
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsUiControllerTest {
    @Test
    fun accountFormModesSeparateSetupBoundAndMissingCredentialFlows() {
        val initialSetup = SyncUiState(SyncConnectionUi.LocalOnly()).accountFormMode()
        val boundSession = SyncUiState(
            SyncConnectionUi.Connected(
                endpoint = "https://sync.example.test",
                accountEmail = "alice@example.test",
                deviceLabel = "Desktop device",
            ),
        ).accountFormMode()
        val unavailableSession = SyncUiState(
            connection = SyncConnectionUi.Unavailable(
                configuredEndpoint = "https://sync.example.test",
                accountEmail = "alice@example.test",
                deviceLabel = "Desktop device",
            ),
            issue = SyncIssueUi(SyncIssueReason.SecureSessionUnavailable),
        ).accountFormMode()
        val missingCredentials = SyncUiState(
            connection = SyncConnectionUi.LocalOnly("https://sync.example.test"),
            issue = SyncIssueUi(SyncIssueReason.SignInRequired),
        ).accountFormMode()
        val failedCredentialRecovery = SyncUiState(
            connection = SyncConnectionUi.LocalOnly("https://sync.example.test"),
            issue = SyncIssueUi(SyncIssueReason.SetupFailed),
        ).accountFormMode()
        val restartedCredentialRecovery = SyncUiState(
            connection = SyncConnectionUi.LocalOnly("https://sync.example.test"),
        ).accountFormMode()
        val failedFirstSetup = SyncUiState(
            connection = SyncConnectionUi.LocalOnly(),
            issue = SyncIssueUi(SyncIssueReason.SetupFailed),
        ).accountFormMode()
        val authorityRecovery = SyncUiState(
            connection = SyncConnectionUi.Connected(
                endpoint = "https://sync.example.test",
                accountEmail = "alice@example.test",
                deviceLabel = "Desktop device",
            ),
            issue = SyncIssueUi(SyncIssueReason.AuthorityMismatch),
        ).accountFormMode()

        assertEquals(SyncAccountFormMode.InitialSetup, initialSetup)
        assertTrue(initialSetup.allowCreateAccount)
        assertEquals(SyncAccountFormMode.BoundSession, boundSession)
        assertTrue(boundSession.serverReadOnly)
        assertTrue(boundSession.emailReadOnly)
        assertTrue(boundSession.allowManualReauthentication)
        assertEquals(SyncAccountFormMode.SessionUnavailable, unavailableSession)
        assertFalse(unavailableSession.initiallyVisible)
        assertFalse(unavailableSession.allowCreateAccount)
        assertFalse(unavailableSession.allowManualReauthentication)
        assertEquals(SyncAccountFormMode.MissingCredentials, missingCredentials)
        assertTrue(missingCredentials.serverReadOnly)
        assertFalse(missingCredentials.emailReadOnly)
        assertFalse(missingCredentials.allowCreateAccount)
        assertEquals(SyncAccountFormMode.MissingCredentials, failedCredentialRecovery)
        assertEquals(SyncAccountFormMode.MissingCredentials, restartedCredentialRecovery)
        assertEquals(SyncAccountFormMode.InitialSetup, failedFirstSetup)
        assertEquals(SyncAccountFormMode.AuthorityRecovery, authorityRecovery)
        assertTrue(authorityRecovery.serverReadOnly)
        assertFalse(authorityRecovery.emailReadOnly)
        assertFalse(authorityRecovery.allowCreateAccount)
        assertTrue(authorityRecovery.allowManualReauthentication)

        val unavailableProjection = SyncUiState(
            connection = SyncConnectionUi.LocalOnly("https://sync.example.test"),
            issue = SyncIssueUi(SyncIssueReason.SecureSessionUnavailable),
        ).accountFormMode()
        assertEquals(SyncAccountFormMode.SessionUnavailable, unavailableProjection)
        assertFalse(unavailableProjection.initiallyVisible)
        assertFalse(unavailableProjection.allowCreateAccount)
    }

    @Test
    fun nonRetryableSyncIssuesDoNotPublishAnInvalidAction() {
        assertNull(SyncIssueUi(SyncIssueReason.RemoteHistoryConflict).action)
        assertNull(SyncIssueUi(SyncIssueReason.CheckpointInvalid).action)
        assertNull(SyncIssueUi(SyncIssueReason.SyncUnavailable).action)
        assertEquals(
            SyncIssueAction.Reauthenticate,
            SyncIssueUi(SyncIssueReason.ConfigurationChanged).action,
        )
    }

    @Test
    fun pairingCanReplaceBrokenWorkspacesButNotBypassSessionFailures() {
        fun state(issue: SyncIssueUi?) = SyncUiState(
            connection = SyncConnectionUi.Connected(
                endpoint = "https://sync.example.test",
                accountEmail = "alice@example.test",
                deviceLabel = "Desktop device",
            ),
            issue = issue,
        )

        assertTrue(state(issue = null).pairingAvailable)
        assertTrue(state(SyncIssueUi(SyncIssueReason.SyncFailed)).pairingAvailable)
        assertTrue(state(SyncIssueUi(SyncIssueReason.RemoteHistoryConflict)).pairingAvailable)
        assertTrue(state(SyncIssueUi(SyncIssueReason.CheckpointInvalid)).pairingAvailable)
        assertTrue(state(SyncIssueUi(SyncIssueReason.WorkspaceLocked)).pairingAvailable)
        assertFalse(state(SyncIssueUi(SyncIssueReason.AuthorityMismatch)).pairingAvailable)
        assertFalse(state(SyncIssueUi(SyncIssueReason.ConfigurationChanged)).pairingAvailable)
        assertFalse(state(SyncIssueUi(SyncIssueReason.SyncUnavailable)).pairingAvailable)
    }

    @Test
    fun notificationsRequirePermissionBeforePersisting() = runBlocking {
        var persisted = ClientSettings()
        val scheduler = FakeOnThisDayNotificationScheduler(permissionGranted = false)
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            persistSettings = { updated -> updated.also { persisted = it } },
            onThisDayNotificationScheduler = scheduler,
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(controller.toggleOnThisDayNotifications(enabled = true))
        assertFalse(persisted.onThisDayNotifications.enabled)
        assertEquals(0, scheduler.syncCalls)
    }

    @Test
    fun notificationsPersistAndKeepThePlatformScheduleCurrent() = runBlocking {
        var persisted = ClientSettings()
        val scheduler = FakeOnThisDayNotificationScheduler(permissionGranted = true)
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            persistSettings = { updated -> updated.also { persisted = it } },
            onThisDayNotificationScheduler = scheduler,
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(controller.toggleOnThisDayNotifications(enabled = true))
        assertEquals(1, scheduler.syncCalls)
        assertTrue(controller.setOnThisDayNotificationTime(hour = 8, minute = 30))
        assertEquals(2, scheduler.syncCalls)
        assertEquals(OnThisDayNotificationPreferences(enabled = true, hour = 8, minute = 30), scheduler.lastSynced)

        controller.rescheduleOnThisDayNotifications()
        assertEquals(3, scheduler.syncCalls)

        assertTrue(controller.toggleOnThisDayNotifications(enabled = false))
        assertFalse(persisted.onThisDayNotifications.enabled)
        assertEquals(1, scheduler.cancelCalls)
    }

    @Test
    fun unavailableNotificationSchedulerDoesNotPersistTheToggle() = runBlocking {
        var persisted = ClientSettings()
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            persistSettings = { updated -> updated.also { persisted = it } },
        )

        assertFalse(controller.onThisDayNotificationsSupported)
        assertFalse(controller.toggleOnThisDayNotifications(enabled = true))
        assertFalse(persisted.onThisDayNotifications.enabled)
    }

    @Test
    fun preferencesPersistAndRepeatedFeedbackGetsDistinctEventIds() = runBlocking {
        val repository = InMemoryNotesRepository()
        val work = repository.createNotebook("Work")
        var persisted = ClientSettings()
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            notebooksProvider = repository::listNotebooks,
            persistSettings = { updated -> updated.also { persisted = it } },
        )

        assertEquals(AppliedTheme.Light, resolveAppliedTheme(ClientTheme.System, systemDark = false))
        assertTrue(controller.selectTheme(ClientTheme.Dark))
        val firstEventId = controller.state.feedbackEventId
        assertTrue(controller.selectTheme(ClientTheme.Dark))
        assertTrue(controller.state.feedbackEventId > firstEventId)
        assertTrue(controller.selectLanguage(AppLanguage.Chinese))
        assertTrue(controller.selectDefaultNotebook(work.id))

        assertEquals(ClientTheme.Dark, persisted.theme)
        assertEquals(AppLanguage.Chinese, persisted.appLanguage)
        assertEquals(work.id, persisted.defaultNotebookId)
    }

    @Test
    fun lastSelectedNotebookPersistsWithoutUserFacingFeedback() = runBlocking {
        val repository = InMemoryNotesRepository()
        val work = repository.createNotebook("Work")
        var persisted = ClientSettings()
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            notebooksProvider = repository::listNotebooks,
            persistSettings = { updated -> updated.also { persisted = it } },
        )

        assertTrue(controller.recordLastSelectedNotebook(work.id))

        assertEquals(work.id, persisted.lastSelectedNotebookId)
        assertNull(controller.state.feedbackMessage)
        assertEquals(0L, controller.state.feedbackEventId)
        assertFalse(controller.recordLastSelectedNotebook("missing-notebook"))
    }

    @Test
    fun exportSummaryStatesThatMediaBytesAndSecretsAreExcluded() = runBlocking {
        val repository = InMemoryNotesRepository()
        val diary = repository.createNotebook("Diary")
        repository.seedNote(
            notebookId = diary.id,
            title = "Exported note",
            markdownBody = "Plain local note body.",
            createdDate = LocalDate(2026, 5, 22),
        )
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            notebooksProvider = repository::listNotebooks,
            exportProvider = {
                SettingsExportSummary(
                    formatName = "Someday JSON export",
                    notebookCount = 1,
                    noteCount = 1,
                    excludedSensitiveFields = SettingsExportSummary.defaultExcludedSensitiveFields,
                )
            },
        )

        assertTrue(controller.runLocalExport())

        val summary = assertNotNull(controller.state.exportSummary)
        assertEquals(1, summary.notebookCount)
        assertEquals(1, summary.noteCount)
        assertTrue(summary.excludedSensitiveFields.contains("raw workspace keys"))
        assertTrue(summary.excludedSensitiveFields.contains("refresh tokens"))
        assertFalse(summary.includesMediaBytes)
        assertTrue(summary.assetReferencesMayBeUnresolved)
    }

    @Test
    fun asynchronousImportPublishesOneProductSummary() = runBlocking {
        var callback: ((SettingsImportSummary) -> Unit)? = null
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            dayOneImportRunner = DayOneImportRunner { onResult -> callback = onResult },
        )

        assertTrue(controller.startDayOneImport())
        assertTrue(controller.state.importRunning)

        callback?.invoke(
            SettingsImportSummary(
                sourceName = "Day One",
                success = true,
                message = "Imported 2 Day One notes.",
                journalsImported = 1,
                notesCreated = 2,
                mediaReferenced = 1,
            ),
        )

        val summary = assertNotNull(controller.state.importSummary)
        assertFalse(controller.state.importRunning)
        assertEquals(2, summary.notesImported)
        assertEquals(1, summary.mediaReferenced)
        assertFalse(summary.includesMediaBytes)
    }

    @Test
    fun secureCredentialsDriveTheConnectionProjection() = runBlocking {
        val store = FakeSelfHostedSessionCredentialStore(credentials = testCredentials())
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(endpoint = "https://stale.example.test"),
            selfHostedSessionCredentialStore = store,
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        controller.refresh()

        val connected = controller.state.sync.connection as SyncConnectionUi.Connected
        assertEquals("https://sync.example.test", connected.endpoint)
        assertEquals("alice@example.test", connected.accountEmail)
        assertEquals("Test device / desktop", connected.deviceLabel)

        store.credentials = null
        controller.refresh()

        val localOnly = controller.state.sync.connection as SyncConnectionUi.LocalOnly
        assertEquals("https://sync.example.test", localOnly.configuredEndpoint)
        assertEquals(SyncIssueAction.Reauthenticate, controller.state.sync.issue?.action)
    }

    @Test
    fun secureStoreFailureBlocksSyncAndPairingUntilReloadSucceeds() = runBlocking {
        val store = FakeSelfHostedSessionCredentialStore(
            credentials = testCredentials(),
            failOnLoad = true,
        )
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = store,
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        controller.refresh()

        assertTrue(controller.state.sync.connection is SyncConnectionUi.Unavailable)
        assertEquals(SyncIssueAction.ReloadSession, controller.state.sync.issue?.action)
        assertFalse(controller.canRunAutomaticSync())
        assertFalse(controller.runUserSync())
        assertFalse(controller.createWorkspacePairingInvitation())

        store.failOnLoad = false
        assertTrue(controller.recoverSyncIssue())
        assertTrue(controller.state.sync.connection is SyncConnectionUi.Connected)
        assertTrue(controller.canRunAutomaticSync())
    }

    @Test
    fun recoveredCredentialsNormalizeAndPersistTheSelfHostedConnection() = runBlocking {
        var persisted = ClientSettings(
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.Off,
                selfHostedEndpoint = "https://stale.example.test",
            ),
        )
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            persistSettings = { updated -> updated.also { persisted = it } },
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(testCredentials()),
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        controller.refresh()

        assertEquals(SyncMode.SelfHosted, persisted.syncConfiguration.mode)
        assertEquals("https://sync.example.test", persisted.syncConfiguration.selfHostedEndpoint)
        assertEquals("device-123", persisted.activeDeviceId)
        assertTrue(controller.state.sync.connection is SyncConnectionUi.Connected)
    }

    @Test
    fun credentialReconciliationCanRecoverAfterSettingsPersistenceReturns() = runBlocking {
        var failPersistence = true
        var persisted = ClientSettings()
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            persistSettings = { updated ->
                check(!failPersistence) { "settings store unavailable" }
                updated.also { persisted = it }
            },
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(testCredentials()),
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        controller.refresh()

        assertTrue(controller.state.sync.connection is SyncConnectionUi.LocalOnly)
        assertEquals(SyncIssueAction.ReloadSession, controller.state.sync.issue?.action)

        failPersistence = false
        assertTrue(controller.recoverSyncIssue())
        assertEquals(SyncMode.SelfHosted, persisted.syncConfiguration.mode)
        assertTrue(controller.state.sync.connection is SyncConnectionUi.Connected)
    }

    @Test
    fun setupNormalizesAccountInputAndUsesHostDeviceIdentity() = runBlocking {
        var capturedInput: SelfHostedSetupInput? = null
        var persisted = ClientSettings(activeDeviceId = "device-abcdef12")
        lateinit var controller: SettingsUiController
        controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            persistSettings = { updated -> updated.also { persisted = it } },
            selfHostedDeviceName = "Mac",
            selfHostedDevicePlatform = "desktop",
            selfHostedSetupClient = SelfHostedSetupClient { input ->
                assertEquals(SyncUiOperation.CreatingAccount, controller.state.sync.operation)
                capturedInput = input
                SelfHostedSetupResult.success(
                    status = SelfHostedSetupStatus(ready = true, reason = SelfHostedSetupReason.Ready),
                    session = SelfHostedSessionSummary(
                        loggedIn = true,
                        userEmail = input.email,
                        deviceId = "device-123",
                        deviceName = input.deviceName,
                        devicePlatform = input.platform,
                    ),
                )
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        controller.updateLocalizedStrings(
            settings = SettingsUiStrings(),
            notifications = OnThisDayNotificationStrings(),
            hostDeviceName = "桌面设备",
        )

        assertTrue(
            controller.setupSelfHosted(
                endpoint = " http://127.0.0.1:3180/ ",
                email = " Alice@Example.test ",
                password = "correct-password",
                createAccount = true,
            ),
        )

        val input = assertNotNull(capturedInput)
        assertEquals("http://127.0.0.1:3180", input.endpoint)
        assertEquals("alice@example.test", input.email)
        assertEquals("桌面设备 · cdef12", input.deviceName)
        assertEquals("desktop", input.platform)
        assertTrue(input.createAccount)
        assertEquals("device-123", persisted.activeDeviceId)
        assertTrue(controller.state.sync.connection is SyncConnectionUi.Connected)
        assertNull(controller.state.sync.operation)
    }

    @Test
    fun setupValidationAndFailureExposeOnlySafeLocalizedCopy() = runBlocking {
        val rawDiagnostic = "raw-diagnostic-must-not-reach-ui"
        var setupCalls = 0
        lateinit var controller: SettingsUiController
        controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            selfHostedSetupClient = SelfHostedSetupClient {
                assertEquals(SyncUiOperation.Authenticating, controller.state.sync.operation)
                setupCalls += 1
                SelfHostedSetupResult.failure(
                    reason = SelfHostedSetupReason.Failed,
                    diagnosticMessage = rawDiagnostic,
                )
            },
            uiStrings = SettingsUiStrings(selfHostedSetupFailed = "localized-setup-failure"),
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(
            controller.setupSelfHosted(
                endpoint = "",
                email = "alice@example.test",
                password = "super-secret",
                createAccount = false,
            ),
        )
        assertEquals(0, setupCalls)
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("endpoint"))
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("super-secret"))

        assertFalse(
            controller.setupSelfHosted(
                endpoint = "https://sync.example.test",
                email = "alice@example.test",
                password = "super-secret",
                createAccount = false,
            ),
        )
        assertEquals(1, setupCalls)
        assertEquals(SyncIssueReason.SetupFailed, controller.state.sync.issue?.reason)
        assertEquals("localized-setup-failure", controller.state.feedbackMessage)
        assertEquals(SyncIssueAction.Reauthenticate, controller.state.sync.issue?.action)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains(rawDiagnostic))
    }

    @Test
    fun failedMissingCredentialRecoveryKeepsTheBoundEndpointAndRecoveryForm() = runBlocking {
        var persisted = connectedSettings()
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            persistSettings = { updated -> updated.also { persisted = it } },
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(),
            selfHostedSetupClient = SelfHostedSetupClient {
                SelfHostedSetupResult.failure(SelfHostedSetupReason.Failed)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        controller.refresh()
        assertEquals(SyncAccountFormMode.MissingCredentials, controller.state.sync.accountFormMode())

        assertFalse(
            controller.setupSelfHosted(
                endpoint = "https://sync.example.test",
                email = "alice@example.test",
                password = "incorrect-password",
                createAccount = false,
            ),
        )

        assertEquals("https://sync.example.test", persisted.syncConfiguration.selfHostedEndpoint)
        assertEquals(SyncIssueReason.SetupFailed, controller.state.sync.issue?.reason)
        assertEquals(SyncAccountFormMode.MissingCredentials, controller.state.sync.accountFormMode())
        assertFalse(controller.state.sync.accountFormMode().allowCreateAccount)
    }

    @Test
    fun missingCredentialsRemainAReauthenticationFlowAfterRestart() = runBlocking {
        val restartedSettings = ClientSettings(
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.SelfHosted,
                selfHostedEndpoint = "https://sync.example.test",
            ),
        )
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = restartedSettings,
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(),
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        controller.refresh()

        assertEquals(SyncIssueReason.SignInRequired, controller.state.sync.issue?.reason)
        assertEquals(SyncAccountFormMode.MissingCredentials, controller.state.sync.accountFormMode())
        assertFalse(controller.state.sync.accountFormMode().allowCreateAccount)
    }

    @Test
    fun failedReplacementPreservesTheBoundEndpointDeviceAndSession() = runBlocking {
        val originalConfiguration = connectedSettings().syncConfiguration
        var persisted = connectedSettings()
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            persistSettings = { updated -> updated.also { persisted = it } },
            selfHostedSetupClient = SelfHostedSetupClient {
                SelfHostedSetupResult.failure(
                    reason = SelfHostedSetupReason.AccountChangeBlocked,
                    diagnosticMessage = "raw-bound-replacement-diagnostic",
                )
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(
            controller.setupSelfHosted(
                endpoint = "https://other.example.test",
                email = "other@example.test",
                password = "another-password",
                createAccount = false,
            ),
        )

        assertEquals("device-123", persisted.activeDeviceId)
        assertEquals(originalConfiguration.selfHostedEndpoint, persisted.syncConfiguration.selfHostedEndpoint)
        assertEquals(originalConfiguration.selfHostedSession, persisted.syncConfiguration.selfHostedSession)
        assertNull(persisted.syncConfiguration.lastError)
        assertNull(controller.state.sync.issue)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("raw-bound-replacement"))
    }

    @Test
    fun confirmedConnectionSwitchReopensEditableFirstSetupAndRefreshesReplacedWorkspace() = runBlocking {
        var stored = connectedSettings().copy(theme = ClientTheme.Dark)
        var refreshCalls = 0
        lateinit var controller: SettingsUiController
        controller = SettingsUiController(
            loadSettings = { stored },
            initialSettings = stored,
            selfHostedConnectionSwitcher = SelfHostedConnectionSwitcher {
                assertEquals(SyncUiOperation.SwitchingConnection, controller.state.sync.operation)
                stored = stored.resetBoundWorkspaceForConnectionSwitch()
                SelfHostedConnectionSwitchResult.switched(workspaceReplaced = true)
            },
            onDataRestored = { refreshCalls += 1 },
            uiStrings = SettingsUiStrings(
                selfHostedConnectionSwitchReady = "ready-for-another-authority",
            ),
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(controller.switchSelfHostedConnection())

        assertEquals(1, refreshCalls)
        assertEquals(SyncAccountFormMode.InitialSetup, controller.state.sync.accountFormMode())
        assertFalse(controller.state.sync.accountFormMode().serverReadOnly)
        assertFalse(controller.state.sync.accountFormMode().emailReadOnly)
        assertEquals(SyncMode.Off, controller.state.settings.syncConfiguration.mode)
        assertNull(controller.state.settings.syncConfiguration.selfHostedEndpoint)
        assertFalse(controller.state.settings.syncConfiguration.selfHostedSession.loggedIn)
        assertEquals(ClientTheme.System, controller.state.settings.theme)
        assertEquals("ready-for-another-authority", controller.state.feedbackMessage)
        assertNull(controller.state.sync.operation)
    }

    @Test
    fun failedConnectionSwitchKeepsTheExistingBindingAndReportsSafeFeedback() = runBlocking {
        val original = connectedSettings()
        val controller = SettingsUiController(
            loadSettings = { original },
            initialSettings = original,
            selfHostedConnectionSwitcher = SelfHostedConnectionSwitcher {
                SelfHostedConnectionSwitchResult.failure()
            },
            uiStrings = SettingsUiStrings(
                selfHostedConnectionSwitchFailed = "safe-switch-failure",
            ),
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(controller.switchSelfHostedConnection())

        assertEquals(original, controller.state.settings)
        assertEquals(SyncAccountFormMode.BoundSession, controller.state.sync.accountFormMode())
        assertEquals("safe-switch-failure", controller.state.feedbackMessage)
        assertNull(controller.state.sync.operation)
    }

    @Test
    fun userSyncUsesOneOperationAndRefreshesEvenWithZeroTransportDeltas() = runBlocking {
        var persisted = connectedSettings(lastError = "sync:Failed")
        var refreshCalls = 0
        lateinit var controller: SettingsUiController
        controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            persistSettings = { updated -> updated.also { persisted = it } },
            manualSyncRunner = {
                assertEquals(SyncUiOperation.Syncing, controller.state.sync.operation)
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            onDataRestored = { refreshCalls += 1 },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(controller.runUserSync())

        assertEquals(1, refreshCalls)
        assertNull(controller.state.sync.operation)
        assertNull(controller.state.sync.issue)
        assertNull(persisted.syncConfiguration.lastError)
        assertEquals("Sync complete.", controller.state.feedbackMessage)
    }

    @Test
    fun failedSyncRefreshesMaterializedChangesAndNeverLeaksDiagnostics() = runBlocking {
        val rawDiagnostic = "raw-sync-diagnostic-must-not-reach-ui"
        var refreshCalls = 0
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            manualSyncRunner = {
                ManualSyncResult.failure(
                    mode = SyncMode.SelfHosted,
                    reason = ManualSyncReason.AuthorityMismatch,
                    pulledObjects = 1,
                    conflicts = 1,
                    diagnosticMessage = rawDiagnostic,
                )
            },
            onDataRestored = { refreshCalls += 1 },
            uiStrings = SettingsUiStrings(syncAuthorityMismatch = "localized-authority-failure"),
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(controller.runUserSync())

        assertEquals(1, refreshCalls)
        assertEquals(SyncIssueReason.AuthorityMismatch, controller.state.sync.issue?.reason)
        assertEquals("localized-authority-failure", controller.state.feedbackMessage)
        assertEquals(SyncIssueAction.Reauthenticate, controller.state.sync.issue?.action)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains(rawDiagnostic))
    }

    @Test
    fun alreadyRunningIsTransientAndPreservesTheExistingRecoveryIssue() = runBlocking {
        var persistenceCalls = 0
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(lastError = "sync:WorkspaceLocked"),
            persistSettings = { updated ->
                persistenceCalls += 1
                updated
            },
            manualSyncRunner = {
                ManualSyncResult.failure(SyncMode.SelfHosted, ManualSyncReason.AlreadyRunning)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(controller.runUserSync())

        assertEquals(0, persistenceCalls)
        assertEquals(SyncIssueReason.WorkspaceLocked, controller.state.sync.issue?.reason)
        assertEquals("sync:WorkspaceLocked", controller.state.settings.syncConfiguration.lastError)
        assertEquals("A sync is already in progress.", controller.state.feedbackMessage)
        assertEquals(SettingsFeedbackSeverity.Info, controller.state.feedbackSeverity)
        assertNull(controller.state.sync.operation)
    }

    @Test
    fun runtimeAvailabilityReasonsExposeOnlyValidRecoveryActions() = runBlocking {
        val configurationChanged = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            manualSyncRunner = {
                ManualSyncResult.failure(SyncMode.SelfHosted, ManualSyncReason.ProviderChanged)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        val unavailable = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            manualSyncRunner = {
                ManualSyncResult.failure(SyncMode.SelfHosted, ManualSyncReason.Unavailable)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(configurationChanged.runUserSync())
        assertEquals(SyncIssueReason.ConfigurationChanged, configurationChanged.state.sync.issue?.reason)
        assertEquals(SyncIssueAction.Reauthenticate, configurationChanged.state.sync.issue?.action)

        assertFalse(unavailable.runUserSync())
        assertEquals(SyncIssueReason.SyncUnavailable, unavailable.state.sync.issue?.reason)
        assertNull(unavailable.state.sync.issue?.action)
    }

    @Test
    fun automaticSyncUsesTheSameRunnerAndStaysQuietOnSuccess() = runBlocking {
        var syncCalls = 0
        var refreshCalls = 0
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            automaticSyncEligible = { true },
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 1, 2, 0)
            },
            onDataRestored = { refreshCalls += 1 },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(controller.canRunAutomaticSync())
        assertTrue(controller.runAutomaticSync())

        assertEquals(1, syncCalls)
        assertEquals(1, refreshCalls)
        assertNull(controller.state.feedbackMessage)
        assertEquals(0L, controller.state.feedbackEventId)
        assertNull(controller.state.sync.operation)
    }

    @Test
    fun automaticSyncFailsClosedWhenEligibilityIsNotConfigured() = runBlocking {
        var syncCalls = 0
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(controller.runAutomaticSync())
        assertEquals(0, syncCalls)
        assertNull(controller.state.sync.operation)
        assertNull(controller.state.feedbackMessage)
    }

    @Test
    fun automaticSyncPreflightUsesConfiguredBackgroundDispatcherAndBlocksFirstAuthority() = runBlocking {
        var dispatchCalls = 0
        var eligibilityCalls = 0
        var syncCalls = 0
        val recordingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatchCalls += 1
                block.run()
            }
        }
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            automaticSyncEligible = {
                eligibilityCalls += 1
                false
            },
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = recordingDispatcher,
        )

        assertFalse(controller.runAutomaticSync())
        assertEquals(1, eligibilityCalls)
        assertEquals(1, dispatchCalls)
        assertEquals(0, syncCalls)
        assertNull(controller.state.sync.operation)
        assertNull(controller.state.feedbackMessage)

        assertTrue(controller.runUserSync())
        assertEquals(1, eligibilityCalls)
        assertEquals(1, syncCalls)
    }

    @Test
    fun automaticSyncSkipsADeviceWithoutAConnection() = runBlocking {
        var syncCalls = 0
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.Off, 0, 0, 0)
            },
        )

        assertFalse(controller.canRunAutomaticSync())
        assertFalse(controller.runAutomaticSync())
        assertFalse(controller.runUserSync())
        assertEquals(0, syncCalls)
    }

    @Test
    fun automaticSyncWaitsForReauthenticationAfterAnAuthorityFailure() = runBlocking {
        var syncCalls = 0
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(lastError = "sync:AuthorityMismatch"),
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
        )

        assertFalse(controller.canRunAutomaticSync())
        assertFalse(controller.runAutomaticSync())
        assertFalse(controller.runUserSync())
        assertEquals(0, syncCalls)
    }

    @Test
    fun retryableWorkspaceLockCanRunTheSharedSyncPath() = runBlocking {
        var syncCalls = 0
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(lastError = "sync:WorkspaceLocked"),
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertEquals(SyncIssueAction.RetrySync, controller.state.sync.issue?.action)
        assertTrue(controller.runUserSync())
        assertEquals(1, syncCalls)
        assertNull(controller.state.sync.issue)
    }

    @Test
    fun concurrentSyncTriggersShareTheControllerGuard() = runBlocking {
        val enteredRunner = CompletableDeferred<Unit>()
        val releaseRunner = CompletableDeferred<Unit>()
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            manualSyncRunner = {
                enteredRunner.complete(Unit)
                runBlocking { releaseRunner.await() }
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
        )

        val firstSync = async(Dispatchers.Default) { controller.runUserSync() }
        enteredRunner.await()

        assertEquals(SyncUiOperation.Syncing, controller.state.sync.operation)
        assertFalse(controller.runUserSync())
        releaseRunner.complete(Unit)
        assertTrue(firstSync.await())
        assertNull(controller.state.sync.operation)
    }

    @Test
    fun settingsWritesWaitForSyncPersistenceAndPreserveBothChanges() = runBlocking {
        val repository = InMemoryNotesRepository()
        val work = repository.createNotebook("Work")
        val enteredPersistence = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        var persisted = connectedSettings(lastError = "sync:Failed")
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = persisted,
            notebooksProvider = repository::listNotebooks,
            persistSettings = { updated ->
                if (updated.syncConfiguration.lastError == null &&
                    updated.lastSelectedNotebookId == null
                ) {
                    enteredPersistence.complete(Unit)
                    runBlocking { releasePersistence.await() }
                }
                updated.also { persisted = it }
            },
            manualSyncRunner = {
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
        )

        val sync = async(Dispatchers.Default) { controller.runUserSync() }
        enteredPersistence.await()
        val notebookSelection = async(Dispatchers.Default) {
            controller.recordLastSelectedNotebook(work.id)
        }

        assertEquals(SyncUiOperation.Syncing, controller.state.sync.operation)
        assertFalse(notebookSelection.isCompleted)
        assertFalse(controller.canRunAutomaticSync())
        releasePersistence.complete(Unit)

        assertTrue(sync.await())
        assertTrue(notebookSelection.await())
        assertNull(persisted.syncConfiguration.lastError)
        assertEquals(work.id, persisted.lastSelectedNotebookId)
        assertEquals(persisted, controller.state.settings)
    }

    @Test
    fun syncPersistenceFailureStaysVisibleAndRetryable() = runBlocking {
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            persistSettings = { error("settings store unavailable") },
            manualSyncRunner = {
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(controller.runUserSync())
        assertEquals(SyncIssueReason.SyncFailed, controller.state.sync.issue?.reason)
        assertEquals(SyncIssueAction.RetrySync, controller.state.sync.issue?.action)
        assertEquals(SettingsFeedbackSeverity.Error, controller.state.feedbackSeverity)
        assertNull(controller.state.sync.operation)
    }

    @Test
    fun cancellationClearsTheOperationAndPropagates() = runBlocking {
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            manualSyncRunner = {
                throw CancellationException("navigation scope cancelled")
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFailsWith<CancellationException> { controller.runUserSync() }
        assertNull(controller.state.sync.operation)
        assertTrue(controller.selectTheme(ClientTheme.Dark))
        assertEquals(ClientTheme.Dark, controller.state.settings.theme)
    }

    @Test
    fun localizedFeedbackCanChangeWithoutRecreatingAnActiveController() = runBlocking {
        val enteredRunner = CompletableDeferred<Unit>()
        val releaseRunner = CompletableDeferred<Unit>()
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            manualSyncRunner = {
                enteredRunner.complete(Unit)
                runBlocking { releaseRunner.await() }
                ManualSyncResult.failure(SyncMode.SelfHosted, ManualSyncReason.Failed)
            },
        )

        val sync = async(Dispatchers.Default) { controller.runUserSync() }
        enteredRunner.await()
        controller.updateLocalizedStrings(
            settings = SettingsUiStrings(syncFailed = "同步失败"),
            notifications = OnThisDayNotificationStrings(),
        )
        releaseRunner.complete(Unit)

        assertFalse(sync.await())
        assertEquals("同步失败", controller.state.feedbackMessage)
        assertEquals(SettingsFeedbackSeverity.Error, controller.state.feedbackSeverity)
    }

    @Test
    fun pairingIsUnavailableUntilTheDeviceIsConnected() = runBlocking {
        var createCalls = 0
        var joinCalls = 0
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            workspacePairingInvitationCreator = WorkspacePairingInvitationCreator {
                createCalls += 1
                WorkspacePairingInvitationResult.failure(WorkspacePairingReason.Failed)
            },
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { _, _ ->
                joinCalls += 1
                WorkspaceJoinResult.failure(WorkspacePairingReason.Failed)
            },
        )

        assertFalse(controller.createWorkspacePairingInvitation())
        assertFalse(
            controller.joinWorkspaceWithToken(
                tokenInput = "valid-looking-token",
                replaceExistingWorkspace = true,
            ),
        )
        assertEquals(0, createCalls)
        assertEquals(0, joinCalls)
    }

    @Test
    fun pairingInvitationLifecycleKeepsTokensRedacted() = runBlocking {
        val manualToken = "000G40R 40M30E2 09185GR 38E1WRJ"
        val qrPayload = "SOMEDAY:PAIR:1:000G40R40M30E209185GR38E1WRJ"
        val invitation = WorkspacePairingInvitation.create(
            manualToken = manualToken,
            qrPayload = qrPayload,
            expiresAtEpochMillis = 4_000_000_000_000L,
        )
        var cancelledInvitation: WorkspacePairingInvitation? = null
        lateinit var controller: SettingsUiController
        controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            workspacePairingInvitationCreator = WorkspacePairingInvitationCreator {
                assertEquals(SyncUiOperation.CreatingInvitation, controller.state.sync.operation)
                WorkspacePairingInvitationResult.success(WorkspacePairingReason.InvitationCreated, invitation)
            },
            workspacePairingInvitationCanceller = WorkspacePairingInvitationCanceller { value ->
                cancelledInvitation = value
                WorkspaceJoinResult.success(WorkspacePairingReason.InvitationCancelled)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(controller.createWorkspacePairingInvitation())
        val visible = assertNotNull(controller.state.sync.invitation)
        assertEquals(manualToken, visible.manualToken)
        assertEquals(qrPayload, visible.qrPayload)
        assertFalse(visible.toString().contains(manualToken))

        assertTrue(controller.cancelWorkspacePairingInvitation())
        assertNotNull(cancelledInvitation)
        assertNull(controller.state.sync.invitation)
    }

    @Test
    fun pairingFailureNeverLeaksCoreDiagnostics() = runBlocking {
        val rawDiagnostic = "raw-pairing-diagnostic-must-not-reach-ui"
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            workspacePairingInvitationCreator = WorkspacePairingInvitationCreator {
                WorkspacePairingInvitationResult.failure(
                    reason = WorkspacePairingReason.Failed,
                    diagnosticMessage = rawDiagnostic,
                )
            },
            uiStrings = SettingsUiStrings(pairingInvitationFailed = "localized-pairing-failure"),
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(controller.createWorkspacePairingInvitation())
        assertEquals("localized-pairing-failure", controller.state.feedbackMessage)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains(rawDiagnostic))
    }

    @Test
    fun expiredPairingInvitationIsRemovedFromUiState() = runBlocking {
        var now = 1_000L
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() },
            initialSettings = connectedSettings(),
            currentEpochMillis = { now },
            workspacePairingInvitationCreator = WorkspacePairingInvitationCreator {
                WorkspacePairingInvitationResult.success(
                    reason = WorkspacePairingReason.InvitationCreated,
                    invitation = WorkspacePairingInvitation.create(
                        manualToken = "000G40R 40M30E2 09185GR 38E1WRJ",
                        qrPayload = "SOMEDAY:PAIR:1:000G40R40M30E209185GR38E1WRJ",
                        expiresAtEpochMillis = 2_000L,
                    ),
                )
            },
        )

        assertTrue(controller.createWorkspacePairingInvitation())
        assertNotNull(controller.state.sync.invitation)

        now = 2_000L
        controller.discardWorkspacePairingInvitationAtExpiry(2_000L)

        assertNull(controller.state.sync.invitation)
    }

    @Test
    fun joiningAWorkspaceRejectsBlankInputAndRunsTheFirstSyncOnSuccess() = runBlocking {
        val token = "000G40R 40M30E2 09185GR 38E1WRJ"
        var capturedToken: String? = null
        var capturedReplacement: Boolean? = null
        var syncCalls = 0
        val replacementSettings = connectedSettings()
        val controller = SettingsUiController(
            loadSettings = { replacementSettings },
            initialSettings = connectedSettings(lastError = "sync:Failed"),
            automaticSyncEligible = { false },
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { value, replaceExistingWorkspace ->
                capturedToken = value
                capturedReplacement = replaceExistingWorkspace
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(
            controller.joinWorkspaceWithToken(
                tokenInput = "  ",
                replaceExistingWorkspace = true,
            ),
        )
        assertNull(capturedToken)

        assertTrue(
            controller.joinWorkspaceWithToken(
                tokenInput = token,
                replaceExistingWorkspace = true,
            ),
        )
        assertEquals(token, capturedToken)
        assertEquals(true, capturedReplacement)
        assertEquals(1, syncCalls)
        assertNull(controller.state.sync.issue)
        assertNull(controller.state.sync.operation)
    }

    @Test
    fun pairingReplacementStillSyncsWhenOnlyRecoveryVerificationIsUnavailable() = runBlocking {
        var syncCalls = 0
        var refreshCalls = 0
        val recoveryManager = FakeWorkspaceRecoveryManager(
            statusResult = WorkspaceRecoveryStatusResult.failure(
                reason = WorkspaceRecoveryReason.ServerRequestFailed,
                syncGate = WorkspaceRecoverySyncGate.Allowed,
            ),
        )
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { _, _ ->
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
            workspaceRecoveryManager = recoveryManager,
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            onDataRestored = { refreshCalls += 1 },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(
            controller.joinWorkspaceWithToken(
                tokenInput = "valid-looking-token",
                replaceExistingWorkspace = true,
            ),
        )

        assertEquals(1, syncCalls)
        assertEquals(1, refreshCalls)
        assertEquals(1, recoveryManager.discardCalls)
        assertEquals(
            WorkspaceRecoveryUiAvailability.Unavailable,
            controller.state.sync.recovery.availability,
        )
        assertTrue(controller.canRunAutomaticSync())
    }

    @Test
    fun successfulReplacementRefreshesProductDataWhenFirstSyncFailsWithoutChanges() = runBlocking {
        var refreshCalls = 0
        val replacementSettings = connectedSettings()
        val controller = SettingsUiController(
            loadSettings = { replacementSettings },
            initialSettings = connectedSettings(lastError = "sync:Failed"),
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { _, _ ->
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
            manualSyncRunner = {
                ManualSyncResult.failure(
                    mode = SyncMode.SelfHosted,
                    reason = ManualSyncReason.RetryRequired,
                )
            },
            onDataRestored = { refreshCalls += 1 },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(
            controller.joinWorkspaceWithToken(
                tokenInput = "valid-looking-token",
                replaceExistingWorkspace = true,
            ),
        )
        assertEquals(SyncIssueReason.RetryRequired, controller.state.sync.issue?.reason)
        assertEquals(SyncIssueAction.RetrySync, controller.state.sync.issue?.action)
        assertNull(controller.state.sync.operation)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun successfulReplacementReloadsCommittedSettingsBeforeFirstSync() = runBlocking {
        val oldSettings = connectedSettings(lastError = "sync:WorkspaceLocked").copy(
            theme = ClientTheme.Dark,
            editorPreferences = EditorPreferences(
                previewByDefault = true,
                markdownToolbarVisible = false,
            ),
            defaultNotebookId = "old-default-notebook",
            lastSelectedNotebookId = "old-selected-notebook",
            workspacePreferencesState = WorkspacePreferencesSyncState(
                status = WorkspacePreferencesSyncStatus.Unavailable,
                warning = "The old workspace is locked.",
            ),
        )
        val replacementSettings = connectedSettings().copy(
            theme = ClientTheme.System,
            editorPreferences = EditorPreferences(),
            defaultNotebookId = null,
            lastSelectedNotebookId = null,
            workspacePreferencesState = WorkspacePreferencesSyncState(),
        )
        var storedSettings = oldSettings
        val savedInputs = mutableListOf<ClientSettings>()
        lateinit var controller: SettingsUiController
        controller = SettingsUiController(
            loadSettings = { storedSettings },
            initialSettings = oldSettings,
            persistSettings = { updated ->
                savedInputs += updated
                updated.also { storedSettings = it }
            },
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { _, _ ->
                storedSettings = replacementSettings
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
            manualSyncRunner = {
                assertEquals(replacementSettings, controller.state.settings)
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(
            controller.joinWorkspaceWithToken(
                tokenInput = "valid-looking-token",
                replaceExistingWorkspace = true,
            ),
        )

        assertTrue(savedInputs.isNotEmpty())
        assertTrue(savedInputs.all { it.theme == ClientTheme.System })
        assertTrue(savedInputs.all { it.defaultNotebookId == null })
        assertTrue(savedInputs.all { it.lastSelectedNotebookId == null })
        assertEquals(ClientTheme.System, controller.state.settings.theme)
        assertNull(controller.state.settings.lastSelectedNotebookId)
    }

    @Test
    fun committedReplacementUsesNonDirtyFallbackWhenSettingsCannotReload() = runBlocking {
        val oldSettings = connectedSettings(lastError = "sync:WorkspaceLocked").copy(
            theme = ClientTheme.Dark,
            editorPreferences = EditorPreferences(
                previewByDefault = true,
                markdownToolbarVisible = false,
            ),
            defaultNotebookId = "old-default-notebook",
            lastSelectedNotebookId = "old-selected-notebook",
        )
        val savedInputs = mutableListOf<ClientSettings>()
        var syncCalls = 0
        var refreshCalls = 0
        val controller = SettingsUiController(
            loadSettings = { error("simulated replacement settings read failure") },
            initialSettings = oldSettings,
            persistSettings = { updated ->
                savedInputs += updated
                updated
            },
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { _, _ ->
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            onDataRestored = { refreshCalls += 1 },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(
            controller.joinWorkspaceWithToken(
                tokenInput = "valid-looking-token",
                replaceExistingWorkspace = true,
            ),
        )

        assertEquals(0, syncCalls)
        assertEquals(1, refreshCalls)
        assertEquals(
            SyncIssueReason.WorkspaceSettingsReloadRequired,
            controller.state.sync.issue?.reason,
        )
        assertEquals(SyncIssueAction.RetrySync, controller.state.sync.issue?.action)
        assertEquals(
            "This device joined the workspace, but its settings could not be loaded. Run Sync again before making changes.",
            controller.state.feedbackMessage,
        )
        assertEquals(ClientTheme.System, controller.state.settings.theme)
        assertEquals(EditorPreferences(), controller.state.settings.editorPreferences)
        assertNull(controller.state.settings.defaultNotebookId)
        assertNull(controller.state.settings.lastSelectedNotebookId)
        assertEquals(
            WorkspacePreferencesSnapshot(
                theme = ClientTheme.System,
                previewByDefault = false,
                markdownToolbarVisible = true,
                defaultNotebookId = null,
            ),
            controller.state.settings.workspacePreferencesState.displayedSnapshot,
        )

        assertTrue(controller.runUserSync())
        assertEquals(1, syncCalls)
        assertTrue(savedInputs.isNotEmpty())
        assertTrue(savedInputs.all { it.theme == ClientTheme.System })
        assertTrue(savedInputs.all { it.defaultNotebookId == null })
        assertTrue(savedInputs.all { it.lastSelectedNotebookId == null })
    }

    @Test
    fun recoveryGateRetriesPendingStateAndBlocksOnlyWhenWorkspaceVerificationRequiresIt() = runBlocking {
        suspend fun assertSyncBlocked(
            controller: SettingsUiController,
            expectedAvailability: WorkspaceRecoveryUiAvailability,
            syncCalls: () -> Int,
        ) {
            assertEquals(expectedAvailability, controller.state.sync.recovery.availability)
            assertFalse(controller.canRunAutomaticSync())
            assertFalse(controller.runUserSync())
            assertFalse(controller.runAutomaticSync())
            assertEquals(0, syncCalls())
        }

        var unknownSyncCalls = 0
        val unknown = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(testCredentials()),
            automaticSyncEligible = { true },
            workspaceRecoveryManager = FakeWorkspaceRecoveryManager(),
            manualSyncRunner = {
                unknownSyncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        assertEquals(WorkspaceRecoveryUiAvailability.Unknown, unknown.state.sync.recovery.availability)
        assertFalse(unknown.canRunAutomaticSync())
        assertFalse(unknown.runAutomaticSync())
        assertTrue(unknown.runUserSync())
        assertEquals(1, unknownSyncCalls)
        assertEquals(WorkspaceRecoveryUiAvailability.NotConfigured, unknown.state.sync.recovery.availability)

        var availableSyncCalls = 0
        val available = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(testCredentials()),
            automaticSyncEligible = { true },
            workspaceRecoveryManager = FakeWorkspaceRecoveryManager(
                statusResult = WorkspaceRecoveryStatusResult.ready(
                    state = WorkspaceRecoveryState.RecoveryAvailable,
                    syncGate = WorkspaceRecoverySyncGate.RecoveryRequired,
                    reason = WorkspaceRecoveryReason.RecoveryAvailable,
                ),
            ),
            manualSyncRunner = {
                availableSyncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        available.refresh()
        assertSyncBlocked(
            controller = available,
            expectedAvailability = WorkspaceRecoveryUiAvailability.RecoveryAvailable,
            syncCalls = { availableSyncCalls },
        )

        var unavailableSyncCalls = 0
        val unavailableManager = FakeWorkspaceRecoveryManager(
            statusResult = WorkspaceRecoveryStatusResult.failure(
                WorkspaceRecoveryReason.ServerRequestFailed,
            ),
        )
        val unavailable = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(testCredentials()),
            automaticSyncEligible = { true },
            workspaceRecoveryManager = unavailableManager,
            manualSyncRunner = {
                unavailableSyncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        unavailable.refresh()
        assertSyncBlocked(
            controller = unavailable,
            expectedAvailability = WorkspaceRecoveryUiAvailability.Unavailable,
            syncCalls = { unavailableSyncCalls },
        )

        unavailableManager.statusResult = WorkspaceRecoveryStatusResult.ready(
            state = WorkspaceRecoveryState.NotConfigured,
            syncGate = WorkspaceRecoverySyncGate.Allowed,
            reason = WorkspaceRecoveryReason.NotConfigured,
        )
        assertTrue(unavailable.retryWorkspaceRecoveryStatus())
        assertTrue(unavailable.canRunAutomaticSync())
        assertTrue(unavailable.runAutomaticSync())
        assertEquals(1, unavailableSyncCalls)
    }

    @Test
    fun recoveryControlPlaneFailureDoesNotBlockAnAlreadyVerifiedLocalWorkspace() = runBlocking {
        var syncCalls = 0
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(testCredentials()),
            automaticSyncEligible = { true },
            workspaceRecoveryManager = FakeWorkspaceRecoveryManager(
                statusResult = WorkspaceRecoveryStatusResult.failure(
                    reason = WorkspaceRecoveryReason.ServerRequestFailed,
                    syncGate = WorkspaceRecoverySyncGate.Allowed,
                ),
            ),
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        controller.refresh()

        assertEquals(WorkspaceRecoveryUiAvailability.Unavailable, controller.state.sync.recovery.availability)
        assertTrue(controller.canRunAutomaticSync())
        assertTrue(controller.runUserSync())
        assertTrue(controller.runAutomaticSync())
        assertEquals(2, syncCalls)
    }

    @Test
    fun remoteHistoryConflictRefreshesAStaleMissingRecoveryStatusBeforeAnotherSync() = runBlocking {
        val manager = FakeWorkspaceRecoveryManager()
        var syncCalls = 0
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(testCredentials()),
            workspaceRecoveryManager = manager,
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.failure(
                    mode = SyncMode.SelfHosted,
                    reason = ManualSyncReason.RemoteHistoryConflict,
                )
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        controller.refresh()
        manager.statusResult = WorkspaceRecoveryStatusResult.ready(
            state = WorkspaceRecoveryState.RecoveryAvailable,
            syncGate = WorkspaceRecoverySyncGate.RecoveryRequired,
            reason = WorkspaceRecoveryReason.RecoveryAvailable,
        )

        assertFalse(controller.runUserSync())

        assertEquals(1, syncCalls)
        assertEquals(2, manager.statusCalls)
        assertEquals(
            WorkspaceRecoveryUiAvailability.RecoveryAvailable,
            controller.state.sync.recovery.availability,
        )
        assertFalse(controller.canRunAutomaticSync())
    }

    @Test
    fun preparedRecoveryCodePublishesOnlyAfterTheExactCodeIsEnteredAgain() = runBlocking {
        val manager = FakeWorkspaceRecoveryManager(
            statusResult = WorkspaceRecoveryStatusResult.ready(
                state = WorkspaceRecoveryState.NotConfigured,
                syncGate = WorkspaceRecoverySyncGate.Allowed,
                reason = WorkspaceRecoveryReason.NotConfigured,
            ),
        )
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(testCredentials()),
            workspaceRecoveryManager = manager,
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        controller.refresh()

        assertTrue(controller.prepareWorkspaceRecoveryCode())
        assertEquals(1, manager.prepareCalls)
        assertEquals(0, manager.publishCalls)
        assertEquals(RECOVERY_CODE, controller.state.sync.recovery.preparedCode?.value)

        assertFalse(controller.confirmWorkspaceRecoveryCode("SOMEDAY-WRONG-CODE"))
        assertEquals(listOf("SOMEDAY-WRONG-CODE"), manager.confirmationCandidates)
        assertEquals(0, manager.publishCalls)
        assertEquals(RECOVERY_CODE, controller.state.sync.recovery.preparedCode?.value)
        assertEquals(
            WorkspaceRecoveryUiAvailability.NotConfigured,
            controller.state.sync.recovery.availability,
        )

        assertTrue(controller.confirmWorkspaceRecoveryCode(RECOVERY_CODE))
        assertEquals(
            listOf("SOMEDAY-WRONG-CODE", RECOVERY_CODE),
            manager.confirmationCandidates,
        )
        assertEquals(1, manager.publishCalls)
        assertNull(controller.state.sync.recovery.preparedCode)
        assertEquals(
            WorkspaceRecoveryUiAvailability.Configured,
            controller.state.sync.recovery.availability,
        )
    }

    @Test
    fun successfulRecoveryReloadsTheWorkspaceRunsFirstSyncAndRefreshesProductData() = runBlocking {
        val replacementSettings = connectedSettings().copy(theme = ClientTheme.Dark)
        val manager = FakeWorkspaceRecoveryManager(
            statusResult = WorkspaceRecoveryStatusResult.ready(
                state = WorkspaceRecoveryState.RecoveryAvailable,
                syncGate = WorkspaceRecoverySyncGate.RecoveryRequired,
                reason = WorkspaceRecoveryReason.RecoveryAvailable,
            ),
        )
        var loadCalls = 0
        var syncCalls = 0
        var refreshCalls = 0
        lateinit var controller: SettingsUiController
        controller = SettingsUiController(
            loadSettings = {
                loadCalls += 1
                replacementSettings
            },
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(testCredentials()),
            workspaceRecoveryManager = manager,
            manualSyncRunner = {
                syncCalls += 1
                assertEquals(SyncUiOperation.Syncing, controller.state.sync.operation)
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            onDataRestored = { refreshCalls += 1 },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        controller.refresh()
        assertEquals(
            WorkspaceRecoveryUiAvailability.RecoveryAvailable,
            controller.state.sync.recovery.availability,
        )

        assertTrue(
            controller.recoverWorkspaceWithCode(
                recoveryCode = RECOVERY_CODE,
                replaceExistingWorkspace = true,
            ),
        )

        assertEquals(listOf(RECOVERY_CODE to true), manager.recoveryRequests)
        assertEquals(1, loadCalls)
        assertEquals(1, syncCalls)
        assertEquals(1, refreshCalls)
        assertEquals(ClientTheme.Dark, controller.state.settings.theme)
        assertEquals(
            WorkspaceRecoveryUiAvailability.Configured,
            controller.state.sync.recovery.availability,
        )
        assertNull(controller.state.sync.operation)
    }

    @Test
    fun recoveredWorkspaceStillRunsFirstSyncWhenOnlyRecoveryVerificationChanges() = runBlocking {
        val manager = FakeWorkspaceRecoveryManager(
            statusResult = WorkspaceRecoveryStatusResult.ready(
                state = WorkspaceRecoveryState.RecoveryAvailable,
                syncGate = WorkspaceRecoverySyncGate.RecoveryRequired,
                reason = WorkspaceRecoveryReason.RecoveryAvailable,
            ),
            statusAfterSuccessfulRecovery = WorkspaceRecoveryStatusResult.failure(
                reason = WorkspaceRecoveryReason.AuthorityMismatch,
                syncGate = WorkspaceRecoverySyncGate.Allowed,
            ),
        )
        var syncCalls = 0
        var refreshCalls = 0
        val controller = SettingsUiController(
            loadSettings = ::connectedSettings,
            initialSettings = connectedSettings(),
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(testCredentials()),
            workspaceRecoveryManager = manager,
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
            onDataRestored = { refreshCalls += 1 },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        controller.refresh()

        assertTrue(controller.recoverWorkspaceWithCode(RECOVERY_CODE, replaceExistingWorkspace = true))

        assertEquals(1, syncCalls)
        assertEquals(1, refreshCalls)
        assertEquals(WorkspaceRecoveryUiAvailability.Unavailable, controller.state.sync.recovery.availability)
        assertTrue(controller.canRunAutomaticSync())
    }

    @Test
    fun failedJoinNeverReloadsWorkspaceSettings() = runBlocking {
        var loadCalls = 0
        val controller = SettingsUiController(
            loadSettings = {
                loadCalls += 1
                connectedSettings()
            },
            initialSettings = connectedSettings(),
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { _, _ ->
                WorkspaceJoinResult.failure(WorkspacePairingReason.VerificationFailed)
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(
            controller.joinWorkspaceWithToken(
                tokenInput = "valid-looking-token",
                replaceExistingWorkspace = true,
            ),
        )
        assertEquals(0, loadCalls)
        assertEquals(
            "The pairing invitation could not be verified. Make sure both devices use the same server address, then create a new invitation.",
            controller.state.feedbackMessage,
        )
    }

    @Test
    fun serverRequestFailureShowsActionableFeedbackWithoutLeakingDiagnostics() = runBlocking {
        val controller = SettingsUiController(
            loadSettings = { connectedSettings() },
            initialSettings = connectedSettings(),
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { _, _ ->
                WorkspaceJoinResult.failure(
                    reason = WorkspacePairingReason.ServerRequestFailed,
                    diagnosticMessage = "token=must-not-leak connection refused",
                )
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(
            controller.joinWorkspaceWithToken(
                tokenInput = "valid-looking-token",
                replaceExistingWorkspace = true,
            ),
        )
        assertEquals(
            "Could not complete the server request. Check the server address, network, and sign-in, then try again.",
            controller.state.feedbackMessage,
        )
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("must-not-leak"))
    }
}

private const val RECOVERY_CODE = "SOMEDAY-0123-4567-89AB-CDEF-0123-4567-89AB-CDEF"

private fun connectedSettings(
    endpoint: String = "https://sync.example.test",
    lastError: String? = null,
): ClientSettings =
    ClientSettings(
        activeDeviceId = "device-123",
        syncConfiguration = SyncConfiguration(
            mode = SyncMode.SelfHosted,
            selfHostedEndpoint = endpoint,
            lastError = lastError,
            selfHostedSession = SelfHostedSessionSummary(
                loggedIn = true,
                userEmail = "alice@example.test",
                deviceId = "device-123",
                deviceName = "Test device",
                devicePlatform = "desktop",
            ),
        ),
    )

private fun testCredentials(): SelfHostedSessionCredentials =
    SelfHostedSessionCredentials(
        endpoint = "https://sync.example.test",
        userId = "user-123",
        userEmail = "alice@example.test",
        deviceId = "device-123",
        deviceName = "Test device",
        devicePlatform = "desktop",
        accessToken = "access-token",
        refreshToken = "refresh-token",
    )

private class FakeSelfHostedSessionCredentialStore(
    var credentials: SelfHostedSessionCredentials? = null,
    var failOnLoad: Boolean = false,
) : SelfHostedSessionCredentialStore {
    override fun load(): SelfHostedSessionCredentials? {
        check(!failOnLoad) { "secure store locked" }
        return credentials
    }

    override fun save(credentials: SelfHostedSessionCredentials) {
        this.credentials = credentials
    }

    override fun clear() {
        credentials = null
    }
}

private class FakeWorkspaceRecoveryManager(
    var statusResult: WorkspaceRecoveryStatusResult = WorkspaceRecoveryStatusResult.ready(
        state = WorkspaceRecoveryState.NotConfigured,
        syncGate = WorkspaceRecoverySyncGate.Allowed,
        reason = WorkspaceRecoveryReason.NotConfigured,
    ),
    var restoreResult: WorkspaceRecoveryRestoreResult = WorkspaceRecoveryRestoreResult.recovered(),
    private val statusAfterSuccessfulRecovery: WorkspaceRecoveryStatusResult =
        WorkspaceRecoveryStatusResult.ready(
            state = WorkspaceRecoveryState.Configured,
            syncGate = WorkspaceRecoverySyncGate.Allowed,
            reason = WorkspaceRecoveryReason.Configured,
        ),
) : WorkspaceRecoveryManager {
    var statusCalls: Int = 0
        private set
    var prepareCalls: Int = 0
        private set
    var publishCalls: Int = 0
        private set
    var discardCalls: Int = 0
        private set
    val confirmationCandidates = mutableListOf<String>()
    val recoveryRequests = mutableListOf<Pair<String, Boolean>>()

    override fun status(): WorkspaceRecoveryStatusResult {
        statusCalls += 1
        return statusResult
    }

    override fun prepareCode(): WorkspaceRecoveryCodeResult {
        prepareCalls += 1
        return WorkspaceRecoveryCodeResult.prepared(
            WorkspaceRecoveryCode.fromUserVisibleValue(RECOVERY_CODE),
        )
    }

    override fun confirmPreparedCode(candidate: String): WorkspaceRecoveryCodeResult {
        confirmationCandidates += candidate
        return if (candidate == RECOVERY_CODE) {
            publishCalls += 1
            WorkspaceRecoveryCodeResult.created()
        } else {
            WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.InvalidCode)
        }
    }

    override fun discardPreparedCode() {
        discardCalls += 1
    }

    override fun recover(
        recoveryCode: String,
        replaceExistingWorkspace: Boolean,
    ): WorkspaceRecoveryRestoreResult {
        recoveryRequests += recoveryCode to replaceExistingWorkspace
        if (restoreResult.success) statusResult = statusAfterSuccessfulRecovery
        return restoreResult
    }
}

private class FakeOnThisDayNotificationScheduler(
    private val permissionGranted: Boolean,
) : OnThisDayNotificationScheduler {
    override val isSupported: Boolean = true
    var syncCalls: Int = 0
        private set
    var cancelCalls: Int = 0
        private set
    var lastSynced: OnThisDayNotificationPreferences? = null
        private set

    override suspend fun ensurePermission(): Boolean = permissionGranted

    override fun syncSchedule(preferences: OnThisDayNotificationPreferences) {
        if (!preferences.enabled) {
            cancel()
            return
        }
        syncCalls += 1
        lastSynced = preferences
    }

    override fun cancel() {
        cancelCalls += 1
    }
}
