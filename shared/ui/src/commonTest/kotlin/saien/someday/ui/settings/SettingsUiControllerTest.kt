package saien.someday.ui.settings

import saien.someday.domain.notifications.OnThisDayNotificationScheduler
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.ManualSyncPhase
import saien.someday.domain.settings.ManualSyncProgressListener
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SelfHostedSetupResult
import saien.someday.domain.settings.SelfHostedSetupReason
import saien.someday.domain.settings.SelfHostedSetupStatus
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspacePairingInvitation
import saien.someday.domain.settings.WorkspacePairingInvitationCreator
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.domain.settings.WorkspacePairingInvitationResult
import saien.someday.domain.settings.WorkspacePairingReason
import saien.someday.ui.i18n.SettingsUiStrings
import saien.someday.ui.notes.InMemoryNotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsUiControllerTest {
    @Test
    fun settingsSectionsExposeSelfHostedPairingAndLocalDataTools() = runBlocking {
        val repository = InMemoryNotesRepository()
        repository.createNotebook("Diary")
        val controller = SettingsUiController(
            initialSettings = ClientSettings(
                syncConfiguration = SyncConfiguration(
                    mode = SyncMode.Off,
                    lastError = "Last sync failed without secrets.",
                ),
            ),
            notebooksProvider = repository::listNotebooks,
        )

        val hierarchyText = controller.state.sections
            .flatMap { section -> listOf(section.title, section.description) + section.entryPoints }
            .joinToString(separator = "\n")

        assertTrue(hierarchyText.contains("Sync mode/account"))
        assertTrue(hierarchyText.contains("Self-hosted device management"))
        assertTrue(hierarchyText.contains("Device pairing"))
        assertFalse(hierarchyText.contains("Encryption/recovery"))
        assertTrue(hierarchyText.contains("Editor preferences"))
        assertTrue(hierarchyText.contains("Theme/default notebook"))
        assertTrue(hierarchyText.contains("Sync status/last error"))
        assertTrue(hierarchyText.contains("Export local data"))
        assertTrue(hierarchyText.contains("Create a one-use workspace pairing invitation"))
        assertTrue(hierarchyText.contains("QR scan or high-entropy token"))
        assertTrue(hierarchyText.contains("must not log raw tokens"))
    }

    @Test
    fun onThisDayNotificationsRequirePermissionBeforePersistingEnabledState() = runBlocking {
        var persisted = ClientSettings()
        val scheduler = FakeOnThisDayNotificationScheduler(permissionGranted = false)
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            onThisDayNotificationScheduler = scheduler,
        )

        assertTrue(controller.onThisDayNotificationsSupported)
        assertFalse(controller.toggleOnThisDayNotifications(enabled = true))
        assertFalse(persisted.onThisDayNotifications.enabled)
        assertEquals(0, scheduler.syncCalls)
        assertTrue(controller.state.feedbackMessage != null)
    }

    @Test
    fun onThisDayNotificationsPersistAndSyncScheduleWhenEnabled() = runBlocking {
        var persisted = ClientSettings()
        val scheduler = FakeOnThisDayNotificationScheduler(permissionGranted = true)
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            onThisDayNotificationScheduler = scheduler,
        )

        assertTrue(controller.toggleOnThisDayNotifications(enabled = true))
        assertTrue(persisted.onThisDayNotifications.enabled)
        assertEquals(1, scheduler.syncCalls)
        assertEquals(10, scheduler.lastSynced?.hour)
        assertEquals(0, scheduler.lastSynced?.minute)

        assertTrue(controller.setOnThisDayNotificationTime(hour = 8, minute = 30))
        assertEquals(8, persisted.onThisDayNotifications.hour)
        assertEquals(30, persisted.onThisDayNotifications.minute)
        assertEquals(2, scheduler.syncCalls)
        assertEquals(8, scheduler.lastSynced?.hour)
        assertEquals(30, scheduler.lastSynced?.minute)

        assertTrue(controller.toggleOnThisDayNotifications(enabled = false))
        assertFalse(persisted.onThisDayNotifications.enabled)
        assertEquals(1, scheduler.cancelCalls)
    }

    @Test
    fun onThisDayNotificationsCanBeRescheduledAfterNotesChange() = runBlocking {
        val scheduler = FakeOnThisDayNotificationScheduler(permissionGranted = true)
        val controller = SettingsUiController(
            initialSettings = ClientSettings(
                onThisDayNotifications = OnThisDayNotificationPreferences(
                    enabled = true,
                    hour = 8,
                    minute = 30,
                ),
            ),
            onThisDayNotificationScheduler = scheduler,
        )

        controller.rescheduleOnThisDayNotifications()

        assertEquals(1, scheduler.syncCalls)
        assertEquals(8, scheduler.lastSynced?.hour)
        assertEquals(30, scheduler.lastSynced?.minute)
    }

    @Test
    fun unavailableOnThisDaySchedulerDoesNotPersistToggle() = runBlocking {
        var persisted = ClientSettings()
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
        )

        assertFalse(controller.onThisDayNotificationsSupported)
        assertFalse(controller.toggleOnThisDayNotifications(enabled = true))
        assertFalse(persisted.onThisDayNotifications.enabled)
    }

    @Test
    fun themeAndDefaultNotebookPreferenceChangesPersist() = runBlocking {
        val repository = InMemoryNotesRepository()
        val work = repository.createNotebook("Work")
        var persisted = ClientSettings()
        val controller = SettingsUiController(
            initialSettings = persisted,
            notebooksProvider = repository::listNotebooks,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
        )

        assertEquals(AppliedTheme.Light, resolveAppliedTheme(ClientTheme.System, systemDark = false))
        assertTrue(controller.selectTheme(ClientTheme.Dark))
        assertEquals(ClientTheme.Dark, persisted.theme)
        assertEquals(AppliedTheme.Dark, resolveAppliedTheme(persisted.theme, systemDark = false))

        assertTrue(controller.selectDefaultNotebook(work.id))
        assertEquals(work.id, persisted.defaultNotebookId)
    }

    @Test
    fun lastSelectedNotebookPreferencePersistsWithoutUserFacingFeedback() = runBlocking {
        val repository = InMemoryNotesRepository()
        val diary = repository.createNotebook("Diary")
        val work = repository.createNotebook("Work")
        var persisted = ClientSettings(defaultNotebookId = diary.id)
        val controller = SettingsUiController(
            initialSettings = persisted,
            notebooksProvider = repository::listNotebooks,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
        )

        assertTrue(controller.recordLastSelectedNotebook(work.id))

        assertEquals(work.id, persisted.lastSelectedNotebookId)
        assertEquals(work.id, controller.state.settings.lastSelectedNotebookId)
        assertEquals(null, controller.state.feedbackMessage)
        assertEquals(0L, controller.state.feedbackEventId)
        assertFalse(controller.recordLastSelectedNotebook("missing-notebook"))
        assertEquals(work.id, persisted.lastSelectedNotebookId)
    }

    @Test
    fun repeatedSettingsFeedbackEmitsDistinctEventsForToastConsumers() = runBlocking {
        val controller = SettingsUiController()

        assertTrue(controller.selectTheme(ClientTheme.Dark))
        val firstFeedbackEventId = controller.state.feedbackEventId
        assertTrue(firstFeedbackEventId > 0L)

        assertTrue(controller.selectTheme(ClientTheme.Dark))
        val secondFeedbackEventId = controller.state.feedbackEventId
        assertTrue(secondFeedbackEventId > firstFeedbackEventId)
        assertEquals("Theme updated.", controller.state.feedbackMessage)
    }

    @Test
    fun selectLanguagePersistsDeviceLocalPreferenceAndFeedback() = runBlocking {
        var persisted = ClientSettings()
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
        )

        assertEquals(AppLanguage.System, controller.state.settings.appLanguage)
        assertTrue(controller.selectLanguage(AppLanguage.Chinese))
        assertEquals(AppLanguage.Chinese, persisted.appLanguage)
        assertEquals(AppLanguage.Chinese, controller.state.settings.appLanguage)
        assertEquals("Language updated.", controller.state.feedbackMessage)

        assertTrue(controller.selectLanguage(AppLanguage.System))
        assertEquals(AppLanguage.System, persisted.appLanguage)
    }

    @Test
    fun localExportEntryPointReportsIncludedDataAndSecretExclusions() = runBlocking {
        val repository = InMemoryNotesRepository()
        val diary = repository.createNotebook("Diary")
        repository.seedNote(
            notebookId = diary.id,
            title = "Exported note",
            markdownBody = "Plain local note body is part of the user-requested export.",
            createdDate = LocalDate(2026, 5, 22),
        )
        val controller = SettingsUiController(
            initialSettings = ClientSettings(),
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
        assertEquals("Someday JSON export", summary.formatName)
        assertEquals(1, summary.notebookCount)
        assertEquals(1, summary.noteCount)
        assertTrue(summary.excludedSensitiveFields.contains("raw workspace keys"))
        assertTrue(summary.excludedSensitiveFields.contains("refresh tokens"))
        assertTrue(summary.excludedSensitiveFields.contains("recovery material"))
        assertFalse(summary.includesMediaBytes)
        assertTrue(summary.assetReferencesMayBeUnresolved)
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("Export prepared"))
    }

    @Test
    fun dayOneImportEntryPointReportsAsynchronousImportSummary() = runBlocking {
        var callback: ((SettingsImportSummary) -> Unit)? = null
        val controller = SettingsUiController(
            dayOneImportRunner = DayOneImportRunner { onResult ->
                callback = onResult
            },
        )

        assertTrue(controller.startDayOneImport())
        assertTrue(controller.state.importRunning)
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("Choose a Day One"))

        callback?.invoke(
            SettingsImportSummary(
                sourceName = "Day One",
                success = true,
                message = "Imported 2 Day One notes.",
                journalsImported = 1,
                notebooksCreated = 1,
                notesCreated = 2,
                richTextConverted = 2,
                mediaReferenced = 1,
            ),
        )

        val summary = assertNotNull(controller.state.importSummary)
        assertFalse(controller.state.importRunning)
        assertEquals(2, summary.notesImported)
        assertEquals(1, summary.journalsImported)
        assertEquals(1, summary.mediaReferenced)
        assertFalse(summary.includesMediaBytes)
        assertTrue(summary.assetReferencesMayBeUnresolved)
        assertEquals("Imported 2 Day One notes.", controller.state.feedbackMessage)
    }

    @Test
    fun workspacePairingInvitationCreatesAndJoinsWithoutExposingSecretInObjects() = runBlocking {
        val manualToken = "000G40R 40M30E2 09185GR 38E1WRJ"
        val qrPayload = "SOMEDAY:PAIR:1:000G40R40M30E209185GR38E1WRJ"
        var capturedToken: String? = null
        val controller = SettingsUiController(
            workspacePairingInvitationCreator = WorkspacePairingInvitationCreator {
                WorkspacePairingInvitationResult.success(
                    reason = WorkspacePairingReason.InvitationCreated,
                    invitation = WorkspacePairingInvitation.create(
                        manualToken = manualToken,
                        qrPayload = qrPayload,
                        expiresAtEpochMillis = 4_000_000_000_000L,
                    ),
                )
            },
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { token ->
                capturedToken = token
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
        )

        assertTrue(controller.createWorkspacePairingInvitation())
        val invitation = assertNotNull(controller.state.workspacePairingInvitation)
        assertEquals(manualToken, invitation.manualToken)
        assertEquals(qrPayload, invitation.qrPayload)
        assertFalse(invitation.toString().contains(manualToken))

        assertFalse(controller.joinWorkspaceWithToken("  "))
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("pairing token"))

        assertTrue(controller.joinWorkspaceWithToken(manualToken))
        assertEquals(manualToken, capturedToken)
        assertEquals(null, controller.state.workspacePairingInvitation)
    }

    @Test
    fun expiredWorkspacePairingInvitationIsNotPublishedByUiState() = runBlocking {
        var now = 1_000L
        val controller = SettingsUiController(
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
        assertNotNull(controller.state.workspacePairingInvitation)

        now = 2_000L
        controller.discardWorkspacePairingInvitationAtExpiry(2_000L)

        assertEquals(null, controller.state.workspacePairingInvitation)
        now = 1_500L
        controller.refresh()
        assertEquals(null, controller.state.workspacePairingInvitation)
    }

    @Test
    fun typedCoreDiagnosticsAreMappedToLocalizedCopyAndNeverShown() = runBlocking {
        val rawDiagnostic = "raw-diagnostic-must-not-reach-ui"
        val controller = SettingsUiController(
            initialSettings = ClientSettings(
                syncConfiguration = loggedInSelfHostedConfiguration(),
            ),
            selfHostedSetupClient = SelfHostedSetupClient {
                SelfHostedSetupResult.failure(
                    reason = SelfHostedSetupReason.Failed,
                    diagnosticMessage = rawDiagnostic,
                )
            },
            manualSyncRunner = {
                ManualSyncResult.failure(
                    mode = SyncMode.SelfHosted,
                    reason = ManualSyncReason.AuthorityMismatch,
                    diagnosticMessage = rawDiagnostic,
                )
            },
            workspacePairingInvitationCreator = WorkspacePairingInvitationCreator {
                WorkspacePairingInvitationResult.failure(
                    reason = WorkspacePairingReason.Failed,
                    diagnosticMessage = rawDiagnostic,
                )
            },
            uiStrings = SettingsUiStrings(
                selfHostedSetupFailed = "localized-setup-failure",
                syncAuthorityMismatch = "localized-sync-authority-failure",
                pairingInvitationFailed = "localized-pairing-failure",
            ),
            backgroundDispatcher = Dispatchers.Unconfined,
            uiDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(
            controller.setupSelfHosted(
                SelfHostedSetupInput(
                    endpoint = "https://sync.example.test",
                    email = "alice@example.test",
                    password = "redacted-password",
                    deviceName = "Test device",
                    platform = "test",
                    createAccount = false,
                ),
            ),
        )
        assertEquals("localized-setup-failure", controller.state.feedbackMessage)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains(rawDiagnostic))

        assertFalse(controller.runManualSync())
        assertEquals("localized-sync-authority-failure", controller.state.feedbackMessage)
        assertEquals("localized-sync-authority-failure", controller.state.manualSyncProgress.message)
        assertFalse(controller.state.manualSyncProgress.message.contains(rawDiagnostic))

        assertFalse(controller.createWorkspacePairingInvitation())
        assertEquals("localized-pairing-failure", controller.state.feedbackMessage)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains(rawDiagnostic))
    }

    @Test
    fun manualSyncProgressUsesLocalizedCopyAndUnbindsListener() = runBlocking {
        var progressListener: ManualSyncProgressListener? = null
        var initialMessage = ""
        var chunkMessage = ""
        lateinit var controller: SettingsUiController
        controller = SettingsUiController(
            initialSettings = ClientSettings(
                syncConfiguration = loggedInSelfHostedConfiguration(),
            ),
            manualSyncRunner = {
                initialMessage = controller.state.manualSyncProgress.message
                checkNotNull(progressListener).onProgress(
                    ManualSyncPhase.UploadingChunks(completed = 2, total = 5),
                )
                chunkMessage = controller.state.manualSyncProgress.message
                ManualSyncResult.success(
                    mode = SyncMode.SelfHosted,
                    pushedObjects = 0,
                    pulledObjects = 0,
                    conflicts = 0,
                    diagnosticMessage = "raw completion diagnostic",
                )
            },
            bindManualSyncProgressListener = { progressListener = it },
            uiStrings = SettingsUiStrings(
                syncReady = "同步已就绪。",
                syncInProgress = "正在同步更改。",
                syncUploadingCheckpointChunks = "正在上传快照（%1\$s/%2\$s）。",
            ),
            backgroundDispatcher = Dispatchers.Default,
            uiDispatcher = Dispatchers.Unconfined,
        )
        controller.refresh()

        assertEquals("同步已就绪。", controller.state.manualSyncProgress.message)
        assertTrue(controller.runManualSync())

        assertEquals("正在同步更改。", initialMessage)
        assertEquals("正在上传快照（2/5）。", chunkMessage)
        assertEquals(null, progressListener)
    }

    @Test
    fun selfHostedModeRunsManualSyncAndRefreshesPulledData() = runBlocking {
        var persisted = ClientSettings(
            syncConfiguration = loggedInSelfHostedConfiguration(),
        )
        var syncCalled = false
        var restored = false
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            manualSyncRunner = {
                syncCalled = true
                ManualSyncResult.success(
                    mode = SyncMode.SelfHosted,
                    pushedObjects = 3,
                    pulledObjects = 2,
                    conflicts = 0,
                    diagnosticMessage = "raw self-hosted completion diagnostic",
                )
            },
            onDataRestored = { restored = true },
        )
        controller.refresh()

        assertTrue(controller.runManualSync())

        assertTrue(syncCalled)
        assertTrue(restored)
        assertFalse(controller.state.manualSyncProgress.running)
        assertEquals(3, controller.state.manualSyncProgress.pushedObjects)
        assertEquals(2, controller.state.manualSyncProgress.pulledObjects)
        assertEquals(null, persisted.syncConfiguration.lastError)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("session-token"))
    }

    @Test
    fun manualSyncRefreshesLocalStateForFailedResultWithConflicts() = runBlocking {
        var refreshed = false
        val controller = SettingsUiController(
            initialSettings = ClientSettings(
                syncConfiguration = loggedInSelfHostedConfiguration(),
            ),
            manualSyncRunner = {
                ManualSyncResult.failure(
                    mode = SyncMode.SelfHosted,
                    reason = ManualSyncReason.Blocked,
                    pushedObjects = 0,
                    pulledObjects = 1,
                    conflicts = 1,
                    diagnosticMessage = "raw conflict diagnostic",
                )
            },
            onDataRestored = { refreshed = true },
        )
        controller.refresh()

        assertFalse(controller.runManualSync())

        assertTrue(refreshed)
        assertEquals(1, controller.state.manualSyncProgress.conflicts)
    }

    @Test
    fun successfulManualSyncWithZeroTransportDeltasStillRefreshesLocalState() = runBlocking {
        var refreshed = false
        val controller = SettingsUiController(
            initialSettings = ClientSettings(
                syncConfiguration = loggedInSelfHostedConfiguration(),
            ),
            manualSyncRunner = {
                ManualSyncResult.success(
                    mode = SyncMode.SelfHosted,
                    pushedObjects = 0,
                    pulledObjects = 0,
                    conflicts = 0,
                    diagnosticMessage = "raw bootstrap completion diagnostic",
                )
            },
            onDataRestored = { refreshed = true },
        )
        controller.refresh()

        assertTrue(controller.runManualSync())
        assertTrue(
            refreshed,
            "Successful first-time V2 activation must refresh Notes even when transport deltas are zero.",
        )
    }

    @Test
    fun automaticSyncRefreshesLocalStateAfterPushWithoutConfigurationToasts() = runBlocking {
        var persisted = ClientSettings(
            syncConfiguration = loggedInSelfHostedConfiguration(),
        )
        var syncCalls = 0
        var refreshed = false
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(
                    mode = SyncMode.SelfHosted,
                    pushedObjects = 1,
                    pulledObjects = 0,
                    conflicts = 0,
                    diagnosticMessage = "raw automatic sync diagnostic",
                )
            },
            onDataRestored = { refreshed = true },
        )
        controller.refresh()
        val initialFeedbackEventId = controller.state.feedbackEventId

        assertTrue(controller.canRunAutomaticSync())
        assertTrue(controller.runAutomaticSync())

        assertEquals(1, syncCalls)
        assertTrue(refreshed)
        assertFalse(controller.state.manualSyncProgress.running)
        assertEquals(1, controller.state.manualSyncProgress.pushedObjects)
        assertEquals(initialFeedbackEventId, controller.state.feedbackEventId)
        assertEquals(null, persisted.syncConfiguration.lastError)
    }

    @Test
    fun automaticSyncSkipsWhenSyncModeIsNotConfigured() = runBlocking {
        var syncCalls = 0
        val controller = SettingsUiController(
            initialSettings = ClientSettings(
                syncConfiguration = SyncConfiguration(mode = SyncMode.Off),
            ),
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(
                    mode = SyncMode.Off,
                    pushedObjects = 0,
                    pulledObjects = 0,
                    conflicts = 0,
                    diagnosticMessage = "unexpected sync diagnostic",
                )
            },
        )

        assertFalse(controller.canRunAutomaticSync())
        assertFalse(controller.runAutomaticSync())
        assertEquals(0, syncCalls)
        assertEquals(0L, controller.state.feedbackEventId)
    }

    @Test
    fun selfHostedSetupShowsSessionDeviceStateAndManualSyncProgress() = runBlocking {
        var persisted = ClientSettings()
        var nextManualSyncResult = ManualSyncResult.success(
            mode = SyncMode.SelfHosted,
            pushedObjects = 2,
            pulledObjects = 1,
            conflicts = 0,
            diagnosticMessage = "raw self-hosted success diagnostic",
        )
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            selfHostedSetupClient = SelfHostedSetupClient { input ->
                if (input.password == "bad-password") {
                    SelfHostedSetupResult.failure(
                        reason = SelfHostedSetupReason.Failed,
                        diagnosticMessage = "Self-hosted login failed: invalid credentials; password redacted.",
                    )
                } else {
                    SelfHostedSetupResult.success(
                        status = SelfHostedSetupStatus(
                            ready = true,
                            reason = SelfHostedSetupReason.Ready,
                            diagnosticMessage = "Self-hosted account and device registered; password redacted.",
                        ),
                        session = SelfHostedSessionSummary(
                            loggedIn = true,
                            userEmail = input.email,
                            deviceId = "device-123",
                            deviceName = input.deviceName,
                            devicePlatform = input.platform,
                        ),
                    )
                }
            },
            manualSyncRunner = {
                nextManualSyncResult
            },
        )

        assertFalse(
            controller.setupSelfHosted(
                SelfHostedSetupInput(
                    endpoint = "",
                    email = "alice@example.com",
                    password = "super-secret",
                    deviceName = "MacBook",
                    platform = "desktop",
                    createAccount = true,
                ),
            ),
        )
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("endpoint is required"))
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("super-secret"))

        assertFalse(
            controller.setupSelfHosted(
                SelfHostedSetupInput(
                    endpoint = "http://127.0.0.1:3180",
                    email = "alice@example.com",
                    password = "bad-password",
                    deviceName = "MacBook",
                    platform = "desktop",
                    createAccount = false,
                ),
            ),
        )
        assertEquals("Self-hosted setup failed safely.", controller.state.feedbackMessage)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("invalid credentials"))
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("bad-password"))

        assertTrue(
            controller.setupSelfHosted(
                SelfHostedSetupInput(
                    endpoint = " http://127.0.0.1:3180/ ",
                    email = " Alice@Example.com ",
                    password = "correct-password",
                    deviceName = "MacBook",
                    platform = "desktop",
                    createAccount = true,
                ),
            ),
        )

        assertEquals(SyncMode.SelfHosted, persisted.syncConfiguration.mode)
        assertEquals("http://127.0.0.1:3180", persisted.syncConfiguration.selfHostedEndpoint)
        assertEquals("alice@example.com", persisted.syncConfiguration.selfHostedSession.userEmail)
        assertEquals("device-123", persisted.syncConfiguration.selfHostedSession.deviceId)
        assertEquals("device-123", persisted.activeDeviceId)

        val hierarchyText = controller.state.sections
            .flatMap { section -> listOf(section.title, section.description) + section.entryPoints }
            .joinToString(separator = "\n")
        assertTrue(hierarchyText.contains("Logged in as alice@example.com"))
        assertTrue(hierarchyText.contains("Endpoint: http://127.0.0.1:3180"))
        assertTrue(hierarchyText.contains("Active self-hosted device: device-123"))
        assertFalse(hierarchyText.contains("correct-password"))

        assertTrue(controller.beginManualSync())
        assertTrue(controller.state.manualSyncProgress.running)
        assertTrue(controller.state.manualSyncProgress.message.contains("Syncing"))
        assertFalse(controller.beginManualSync())
        assertTrue(controller.state.manualSyncProgress.running)

        assertTrue(controller.completeManualSync(nextManualSyncResult))
        assertFalse(controller.state.manualSyncProgress.running)
        assertTrue(controller.state.manualSyncProgress.message.contains("Sync complete"))
        assertFalse(controller.state.manualSyncProgress.message.contains("raw self-hosted"))
        assertEquals(2, controller.state.manualSyncProgress.pushedObjects)
        assertEquals(1, controller.state.manualSyncProgress.pulledObjects)
        assertEquals(null, persisted.syncConfiguration.lastError)

        nextManualSyncResult = ManualSyncResult.failure(
            mode = SyncMode.SelfHosted,
            reason = ManualSyncReason.Failed,
            diagnosticMessage = "Self-hosted sync failed safely: HTTP 503; retryable.",
        )
        assertFalse(controller.runManualSync())
        assertFalse(controller.state.manualSyncProgress.running)
        assertTrue(controller.state.manualSyncProgress.message.contains("failed"))
        assertEquals("sync:Failed", persisted.syncConfiguration.lastError)
    }

    @Test
    fun failedSelfHostedReplacementPreservesTheBoundEndpointDeviceAndSession() = runBlocking {
        val originalConfiguration = loggedInSelfHostedConfiguration()
        var persisted = ClientSettings(
            activeDeviceId = "device-123",
            syncConfiguration = originalConfiguration,
        )
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            selfHostedSetupClient = SelfHostedSetupClient {
                SelfHostedSetupResult.failure(
                    reason = SelfHostedSetupReason.AccountChangeBlocked,
                    diagnosticMessage = "raw-bound-replacement-diagnostic",
                )
            },
        )

        assertFalse(
            controller.setupSelfHosted(
                SelfHostedSetupInput(
                    endpoint = "https://other.example.com",
                    email = "other@example.com",
                    password = "another-password",
                    deviceName = "Replacement device",
                    platform = "desktop",
                    createAccount = false,
                ),
            ),
        )

        assertEquals("device-123", persisted.activeDeviceId)
        assertEquals(originalConfiguration.selfHostedEndpoint, persisted.syncConfiguration.selfHostedEndpoint)
        assertEquals(originalConfiguration.selfHostedSession, persisted.syncConfiguration.selfHostedSession)
        assertEquals("setup:AccountChangeBlocked", persisted.syncConfiguration.lastError)
        assertEquals(
            "This workspace is already bound to an account; sign in to that account instead.",
            controller.state.feedbackMessage,
        )
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("raw-bound-replacement"))
    }

    @Test
    fun selfHostedSessionClearDoesNotPersistLoggedOutStateWhenSecureStoreFails() = runBlocking {
        var persisted = ClientSettings(
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.SelfHosted,
                selfHostedEndpoint = "http://127.0.0.1:3180",
                selfHostedSession = SelfHostedSessionSummary(
                    loggedIn = true,
                    userEmail = "alice@example.com",
                    deviceId = "device-123",
                    deviceName = "MacBook",
                    devicePlatform = "desktop",
                ),
            ),
        )
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            selfHostedSessionCredentialStore = FakeSelfHostedSessionCredentialStore(failOnClear = true),
        )

        assertFalse(controller.clearSelfHostedSession())

        assertTrue(persisted.syncConfiguration.selfHostedSession.loggedIn)
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("could not be removed"))
    }
}

private fun loggedInSelfHostedConfiguration(): SyncConfiguration =
    SyncConfiguration(
        mode = SyncMode.SelfHosted,
        selfHostedEndpoint = "https://sync.example.com",
        selfHostedSession = SelfHostedSessionSummary(
            loggedIn = true,
            userEmail = "alice@example.com",
            deviceId = "device-123",
            deviceName = "Test device",
            devicePlatform = "desktop",
        ),
    )

private class FakeSelfHostedSessionCredentialStore(
    private val failOnClear: Boolean = false,
) : SelfHostedSessionCredentialStore {
    override fun load(): SelfHostedSessionCredentials? = null

    override fun save(credentials: SelfHostedSessionCredentials) = Unit

    override fun clear() {
        check(!failOnClear) { "secure store locked" }
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
