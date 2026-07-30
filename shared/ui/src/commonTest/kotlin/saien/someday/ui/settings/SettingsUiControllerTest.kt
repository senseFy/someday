package saien.someday.ui.settings

import saien.someday.domain.notifications.OnThisDayNotificationScheduler
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.ManualSyncPhase
import saien.someday.domain.settings.ManualSyncProgressListener
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SelfHostedSetupResult
import saien.someday.domain.settings.SelfHostedSetupStatus
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncErrorCode
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.SyncV2MaintenanceRunner
import saien.someday.domain.settings.WebDavBackupResult
import saien.someday.domain.settings.WebDavBackupRunner
import saien.someday.domain.settings.WebDavBackupCatalogRunner
import saien.someday.domain.settings.WebDavBackupListResult
import saien.someday.domain.settings.WebDavBackupVersion
import saien.someday.domain.settings.WebDavConnectionStatus
import saien.someday.domain.settings.WebDavConnectionTestResult
import saien.someday.domain.settings.WebDavConnectionTester
import saien.someday.domain.settings.WebDavAuthorityCredentials
import saien.someday.domain.settings.WebDavCredentialStore
import saien.someday.domain.settings.WebDavDiscoveredDevice
import saien.someday.domain.settings.WebDavDiscoveredDevicesResult
import saien.someday.domain.settings.WebDavDiscoveredDevicesRunner
import saien.someday.domain.settings.WebDavRestoreResult
import saien.someday.domain.settings.WebDavRestoreRunner
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspacePairingInvitation
import saien.someday.domain.settings.WorkspacePairingInvitationCreator
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.domain.settings.WorkspacePairingInvitationResult
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
    fun refreshDiscoveredDevicesPublishesAuthenticatedManifestInventory() = runBlocking {
        val controller = SettingsUiController(
            webDavDiscoveredDevicesRunner = WebDavDiscoveredDevicesRunner {
                WebDavDiscoveredDevicesResult.success(
                    listOf(
                        WebDavDiscoveredDevice("device-current", 1_000, 3_000, true),
                        WebDavDiscoveredDevice("device-other", 2_000, 2_500, false),
                    ),
                )
            },
        )

        assertTrue(controller.refreshWebDavDiscoveredDevices())
        assertEquals(2, controller.state.webDavDiscoveredDevices.size)
        assertTrue(controller.state.webDavDiscoveredDevices.first().isCurrentDevice)
    }

    @Test
    fun settingsSectionsExposePairingForBothRemoteProfiles() = runBlocking {
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
        assertTrue(hierarchyText.contains("WebDAV config"))
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
                    formatName = "someday.local-export.v2+json",
                    notebookCount = 1,
                    noteCount = 1,
                    excludedSensitiveFields = SettingsExportSummary.defaultExcludedSensitiveFields,
                )
            },
        )

        assertTrue(controller.runLocalExport())

        val summary = assertNotNull(controller.state.exportSummary)
        assertEquals("someday.local-export.v2+json", summary.formatName)
        assertEquals(1, summary.notebookCount)
        assertEquals(1, summary.noteCount)
        assertTrue(summary.excludedSensitiveFields.contains("raw workspace keys"))
        assertTrue(summary.excludedSensitiveFields.contains("refresh tokens"))
        assertTrue(summary.excludedSensitiveFields.contains("recovery material"))
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
                    message = "Pairing invitation created.",
                    invitation = WorkspacePairingInvitation.create(
                        manualToken = manualToken,
                        qrPayload = qrPayload,
                        expiresAtEpochMillis = 4_000_000_000_000L,
                    ),
                )
            },
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { token ->
                capturedToken = token
                WorkspaceJoinResult.success("Joined workspace workspace-a.")
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
                    message = "Pairing invitation created.",
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
    fun webDavSetupValidatesRequiredFieldsTestsConnectionAndRedactsCredentials() = runBlocking {
        var persisted = ClientSettings()
        var testedCredential: String? = null
        val credentialStore = FakeWebDavCredentialStore()
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            webDavConnectionTester = WebDavConnectionTester { input ->
                testedCredential = input.password
                WebDavConnectionTestResult(
                    success = true,
                    status = WebDavConnectionStatus(
                        ready = true,
                        message = "WebDAV connection succeeded for app-owned path; credentials redacted.",
                        appDirectory = input.appDirectory,
                    ),
                )
            },
            webDavCredentialStore = credentialStore,
        )

        assertFalse(
            controller.testAndSaveWebDavConnection(
                endpoint = "",
                username = "alice",
                password = "super-secret",
                appDirectory = "/someday/",
            ),
        )
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("endpoint is required"))
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("super-secret"))

        assertTrue(
            controller.testAndSaveWebDavConnection(
                endpoint = "http://127.0.0.1:3182",
                username = "alice",
                password = "super-secret",
                appDirectory = "someday",
            ),
        )

        assertEquals("super-secret", testedCredential)
        assertEquals("super-secret", credentialStore.load())
        assertEquals(SyncMode.WebDav, persisted.syncConfiguration.mode)
        assertEquals("http://127.0.0.1:3182", persisted.syncConfiguration.webDavEndpoint)
        assertEquals("alice", persisted.syncConfiguration.webDavUsername)
        assertEquals("/someday/", persisted.syncConfiguration.webDavAppDirectory)
        assertEquals(true, persisted.syncConfiguration.webDavLastTest?.ready)
        assertFalse(persisted.syncConfiguration.webDavLastTest?.message.orEmpty().contains("PROPFIND"))
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("super-secret"))
        val hierarchyText = controller.state.sections
            .flatMap { section -> listOf(section.title, section.description) + section.entryPoints }
            .joinToString(separator = "\n")
        assertTrue(hierarchyText.contains("Credentials: redacted"))
        assertFalse(hierarchyText.contains("super-secret"))
    }

    @Test
    fun webDavTestReusesSavedCredentialWhenPasswordIsBlank() = runBlocking {
        var persisted = ClientSettings()
        var testedCredential: String? = null
        val credentialStore = FakeWebDavCredentialStore(initialSecret = "saved-secret")
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            webDavConnectionTester = WebDavConnectionTester { input ->
                testedCredential = input.password
                WebDavConnectionTestResult(
                    success = true,
                    status = WebDavConnectionStatus(
                        ready = true,
                        message = "WebDAV connection succeeded; credentials redacted.",
                        appDirectory = input.appDirectory,
                    ),
                )
            },
            webDavCredentialStore = credentialStore,
        )

        assertTrue(
            controller.testAndSaveWebDavConnection(
                endpoint = "https://dav.example.com/dav/",
                username = "alice",
                password = "",
                appDirectory = "someday",
            ),
        )

        assertEquals("saved-secret", testedCredential)
        assertEquals("saved-secret", credentialStore.load())
        assertEquals(SyncMode.WebDav, persisted.syncConfiguration.mode)
        assertEquals(true, persisted.syncConfiguration.webDavLastTest?.ready)
        assertTrue(controller.state.webDavCredentialSaved)
        assertEquals("WebDAV connection succeeded.", controller.state.feedbackMessage)
    }

    @Test
    fun webDavSuccessfulTestDoesNotMarkReadyWhenCredentialCannotBeSaved() = runBlocking {
        var persisted = ClientSettings()
        var testedCredential: String? = null
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            webDavConnectionTester = WebDavConnectionTester { input ->
                testedCredential = input.password
                WebDavConnectionTestResult(
                    success = true,
                    status = WebDavConnectionStatus(
                        ready = true,
                        message = "WebDAV connection succeeded; credentials redacted.",
                        appDirectory = input.appDirectory,
                    ),
                )
            },
            webDavCredentialStore = FakeWebDavCredentialStore(failOnSave = true),
        )

        assertFalse(
            controller.testAndSaveWebDavConnection(
                endpoint = "https://dav.example.com/dav/",
                username = "alice",
                password = "new-secret",
                appDirectory = "someday",
            ),
        )

        assertEquals("new-secret", testedCredential)
        assertEquals(SyncMode.Off, persisted.syncConfiguration.mode)
        assertEquals(null, persisted.syncConfiguration.webDavLastTest)
        assertEquals(null, persisted.syncConfiguration.webDavEndpoint)
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("could not be saved locally"))
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("new-secret"))
    }

    @Test
    fun webDavFailedTestSavesNonSecretFieldsWithoutSavingCredentialOrEnablingMode() = runBlocking {
        var persisted = ClientSettings()
        var testedCredential: String? = null
        val credentialStore = FakeWebDavCredentialStore()
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            webDavConnectionTester = WebDavConnectionTester { input ->
                testedCredential = input.password
                WebDavConnectionTestResult(
                    success = false,
                    status = WebDavConnectionStatus(
                        ready = false,
                        message = "WebDAV connection failed: HTTP 401; credentials redacted.",
                        appDirectory = input.appDirectory,
                    ),
                )
            },
            webDavCredentialStore = credentialStore,
        )

        assertFalse(
            controller.testAndSaveWebDavConnection(
                endpoint = "https://dav.example.com/dav/",
                username = "alice",
                password = "bad-secret",
                appDirectory = "someday",
            ),
        )

        assertEquals("bad-secret", testedCredential)
        assertEquals(null, credentialStore.load())
        assertEquals(SyncMode.Off, persisted.syncConfiguration.mode)
        assertEquals("https://dav.example.com/dav", persisted.syncConfiguration.webDavEndpoint)
        assertEquals("alice", persisted.syncConfiguration.webDavUsername)
        assertEquals("/someday/", persisted.syncConfiguration.webDavAppDirectory)
        assertEquals(false, persisted.syncConfiguration.webDavLastTest?.ready)
        assertTrue(persisted.syncConfiguration.webDavLastTest?.message.orEmpty().contains("HTTP 401"))
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("bad-secret"))
    }

    @Test
    fun webDavFailedTestCannotReplaceADifferentActiveAuthority() = runBlocking {
        val activeConfiguration = SyncConfiguration(
            mode = SyncMode.WebDav,
            webDavEndpoint = "https://active.example.com/dav",
            webDavUsername = "active-user",
            webDavAppDirectory = "/active-workspace/",
            webDavLastTest = WebDavConnectionStatus(
                ready = true,
                message = "WebDAV connection succeeded; credentials redacted.",
                appDirectory = "/active-workspace/",
            ),
        )
        var persisted = ClientSettings(syncConfiguration = activeConfiguration)
        val credentialStore = FakeWebDavCredentialStore(initialSecret = "active-secret")
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            webDavConnectionTester = WebDavConnectionTester { input ->
                WebDavConnectionTestResult(
                    success = false,
                    status = WebDavConnectionStatus(
                        ready = false,
                        message = "WebDAV connection failed: HTTP 401; credentials redacted.",
                        appDirectory = input.appDirectory,
                    ),
                )
            },
            webDavCredentialStore = credentialStore,
        )

        assertFalse(
            controller.testAndSaveWebDavConnection(
                endpoint = "https://candidate.example.com/dav",
                username = "candidate-user",
                password = "bad-candidate-secret",
                appDirectory = "candidate-workspace",
            ),
        )

        assertEquals(activeConfiguration, persisted.syncConfiguration)
        assertEquals("active-secret", credentialStore.load())
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("HTTP 401"))
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("bad-candidate-secret"))
    }

    @Test
    fun webDavSaveClearsStaleReadinessWhenServerFieldsChange() = runBlocking {
        var persisted = ClientSettings(
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.WebDav,
                webDavEndpoint = "https://dav.example.com/dav",
                webDavUsername = "alice",
                webDavAppDirectory = "/someday/",
                webDavLastTest = WebDavConnectionStatus(
                    ready = true,
                    message = "WebDAV connection succeeded; credentials redacted.",
                    appDirectory = "/someday/",
                ),
            ),
        )
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            webDavCredentialStore = FakeWebDavCredentialStore(initialSecret = "saved-secret"),
        )

        assertTrue(
            controller.saveWebDavConfiguration(
                endpoint = "https://dav.example.com/dav",
                username = "alice",
                password = "",
                appDirectory = "other-folder",
            ),
        )

        assertEquals(SyncMode.WebDav, persisted.syncConfiguration.mode)
        assertEquals("/other-folder/", persisted.syncConfiguration.webDavAppDirectory)
        assertEquals(null, persisted.syncConfiguration.webDavLastTest)
    }

    @Test
    fun webDavOperationsRequireLocalCredentialBeforeNetworkRequests() = runBlocking {
        var testerCalled = false
        val controller = SettingsUiController(
            webDavConnectionTester = WebDavConnectionTester {
                testerCalled = true
                WebDavConnectionTestResult(
                    success = true,
                    status = WebDavConnectionStatus(
                        ready = true,
                        message = "WebDAV connection succeeded; credentials redacted.",
                    ),
                )
            },
            webDavCredentialStore = FakeWebDavCredentialStore(),
        )

        assertFalse(
            controller.testAndSaveWebDavConnection(
                endpoint = "https://dav.example.com/remote.php/dav/files/alice",
                username = "alice",
                password = "",
                appDirectory = "/someday/",
            ),
        )

        assertFalse(testerCalled)
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("credential"))
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("MKCOL"))
    }

    @Test
    fun webDavBackupAndRestorePersistConfigurationAndRedactCredentials() = runBlocking {
        var persisted = ClientSettings()
        var backupCredential: String? = null
        var restoreCredential: String? = null
        var restoredBackupPath: String? = null
        var restored = false
        val version = WebDavBackupVersion(
            id = "20260524T100000Z",
            label = "Snapshot 20260524T100000Z",
            path = "Someday/backups/20260524T100000Z.json",
        )
        val credentialStore = FakeWebDavCredentialStore()
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            webDavBackupRunner = WebDavBackupRunner { input ->
                backupCredential = input.password
                WebDavBackupResult(
                    success = true,
                    message = "WebDAV backup saved: 1 notebooks and 2 notes.",
                    notebookCount = 1,
                    noteCount = 2,
                    version = version,
                )
            },
            webDavBackupCatalogRunner = WebDavBackupCatalogRunner {
                WebDavBackupListResult(
                    success = true,
                    message = "Found 1 WebDAV backup versions.",
                    versions = listOf(version),
                )
            },
            webDavRestoreRunner = WebDavRestoreRunner { input, backupPath ->
                restoreCredential = input.password
                restoredBackupPath = backupPath
                WebDavRestoreResult(
                    success = true,
                    message = "WebDAV backup restored: 2 notes imported, 0 already present.",
                    notebooksCreated = 1,
                    notesCreated = 2,
                )
            },
            webDavCredentialStore = credentialStore,
            onDataRestored = { restored = true },
        )

        assertTrue(
            controller.backupToWebDav(
                endpoint = "https://dav.example.com/remote.php/dav/files/alice/",
                username = "alice",
                password = "super-secret",
                appDirectory = "Someday",
            ),
        )
        assertEquals("super-secret", backupCredential)
        assertEquals("super-secret", credentialStore.load())
        assertEquals(SyncMode.WebDav, persisted.syncConfiguration.mode)
        assertEquals("https://dav.example.com/remote.php/dav/files/alice", persisted.syncConfiguration.webDavEndpoint)
        assertEquals("alice", persisted.syncConfiguration.webDavUsername)
        assertEquals("/Someday/", persisted.syncConfiguration.webDavAppDirectory)
        assertEquals(true, persisted.syncConfiguration.webDavLastTest?.ready)
        assertEquals("Snapshot 20260524T100000Z", persisted.syncConfiguration.webDavLastBackup?.versionLabel)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("super-secret"))
        assertTrue(controller.state.webDavBackupVersions.any { it.path == version.path })

        assertTrue(
            controller.refreshWebDavBackupVersions(
                endpoint = "https://dav.example.com/remote.php/dav/files/alice/",
                username = "alice",
                password = "",
                appDirectory = "Someday",
            ),
        )
        assertEquals(listOf(version), controller.state.webDavBackupVersions)

        assertTrue(
            controller.restoreFromWebDav(
                endpoint = "https://dav.example.com/remote.php/dav/files/alice/",
                username = "alice",
                password = "",
                appDirectory = "Someday",
                backupPath = version.path,
            ),
        )
        assertEquals("super-secret", restoreCredential)
        assertEquals(version.path, restoredBackupPath)
        assertTrue(restored)
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("super-secret"))

        assertTrue(controller.clearWebDavCredential())
        assertEquals(null, credentialStore.load())
    }

    @Test
    fun manualSyncProgressUsesLocalizedCopyAndUnbindsListener() = runBlocking {
        var progressListener: ManualSyncProgressListener? = null
        var initialMessage = ""
        var chunkMessage = ""
        lateinit var controller: SettingsUiController
        controller = SettingsUiController(
            initialSettings = ClientSettings(
                syncConfiguration = SyncConfiguration(
                    mode = SyncMode.WebDav,
                    webDavEndpoint = "https://dav.example.com/remote.php/dav/files/alice",
                    webDavUsername = "alice",
                    webDavAppDirectory = "/someday/",
                ),
            ),
            webDavCredentialStore = FakeWebDavCredentialStore(initialSecret = "saved-secret"),
            manualSyncRunner = {
                initialMessage = controller.state.manualSyncProgress.message
                checkNotNull(progressListener).onProgress(
                    ManualSyncPhase.UploadingChunks(completed = 2, total = 5),
                )
                chunkMessage = controller.state.manualSyncProgress.message
                ManualSyncResult.success(
                    mode = SyncMode.WebDav,
                    pushedObjects = 0,
                    pulledObjects = 0,
                    conflicts = 0,
                    message = "done",
                )
            },
            bindManualSyncProgressListener = { progressListener = it },
            uiStrings = SettingsUiStrings(
                syncInProgress = "正在同步更改。",
                syncUploadingCheckpointChunks = "正在上传快照（%1\$s/%2\$s）。",
            ),
            backgroundDispatcher = Dispatchers.Default,
            uiDispatcher = Dispatchers.Unconfined,
        )
        controller.refresh()

        assertTrue(controller.runManualSync())

        assertEquals("正在同步更改。", initialMessage)
        assertEquals("正在上传快照（2/5）。", chunkMessage)
        assertEquals(null, progressListener)
    }

    @Test
    fun webDavModeRunsManualSyncWithSavedCredentialAndRefreshesPulledData() = runBlocking {
        var persisted = ClientSettings(
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.WebDav,
                webDavEndpoint = "https://dav.example.com/remote.php/dav/files/alice",
                webDavUsername = "alice",
                webDavAppDirectory = "/someday/",
            ),
        )
        var syncCalled = false
        var restored = false
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            webDavCredentialStore = FakeWebDavCredentialStore(initialSecret = "saved-secret"),
            manualSyncRunner = {
                syncCalled = true
                ManualSyncResult.success(
                    mode = SyncMode.WebDav,
                    pushedObjects = 3,
                    pulledObjects = 2,
                    conflicts = 0,
                    message = "WebDAV sync complete: pushed 3, pulled 2, conflicts 0.",
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
        assertFalse(controller.state.feedbackMessage.orEmpty().contains("saved-secret"))
    }

    @Test
    fun v2EpochAndRepairMaintenanceUseTheConnectedRuntime() = runBlocking {
        var rollCalls = 0
        var repairCalls = 0
        val controller = SettingsUiController(
            initialSettings = ClientSettings(
                syncConfiguration = SyncConfiguration(
                    mode = SyncMode.WebDav,
                    webDavEndpoint = "https://dav.example.com/remote.php/dav/files/alice",
                    webDavUsername = "alice",
                ),
            ),
            webDavCredentialStore = FakeWebDavCredentialStore(initialSecret = "saved-secret"),
            syncV2MaintenanceRunner = object : SyncV2MaintenanceRunner {
                override fun rollEpoch(): ManualSyncResult {
                    rollCalls += 1
                    return ManualSyncResult.success(
                        mode = SyncMode.WebDav,
                        pushedObjects = 2,
                        pulledObjects = 0,
                        conflicts = 0,
                        message = "Epoch rolled.",
                    )
                }

                override fun repairIntegrity(): ManualSyncResult {
                    repairCalls += 1
                    return ManualSyncResult.success(
                        mode = SyncMode.WebDav,
                        pushedObjects = 0,
                        pulledObjects = 1,
                        conflicts = 0,
                        message = "Integrity repaired.",
                    )
                }
            },
        )

        controller.refresh()
        assertTrue(controller.rollSyncV2Epoch())
        assertTrue(controller.repairSyncV2Integrity())

        assertEquals(1, rollCalls)
        assertEquals(1, repairCalls)
        assertEquals(1, controller.state.manualSyncProgress.pulledObjects)
        assertEquals("Integrity repaired.", controller.state.feedbackMessage)
    }

    @Test
    fun manualSyncPersistsAndClearsMachineReadableErrorCode() = runBlocking {
        var persisted = ClientSettings(
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.WebDav,
                webDavEndpoint = "https://dav.example.com/remote.php/dav/files/alice",
                webDavUsername = "alice",
                webDavAppDirectory = "/someday/",
            ),
        )
        var nextResult = ManualSyncResult.failure(
            mode = SyncMode.WebDav,
            message = "WebDAV sync cannot decrypt remote Someday data; credentials redacted.",
            errorCode = SyncErrorCode.WebDavWorkspaceKeyMismatch,
        )
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            webDavCredentialStore = FakeWebDavCredentialStore(initialSecret = "saved-secret"),
            manualSyncRunner = { nextResult },
        )
        controller.refresh()

        assertFalse(controller.runManualSync())
        assertEquals(SyncErrorCode.WebDavWorkspaceKeyMismatch, persisted.syncConfiguration.lastErrorCode)

        nextResult = ManualSyncResult.success(
            mode = SyncMode.WebDav,
            pushedObjects = 0,
            pulledObjects = 0,
            conflicts = 0,
            message = "WebDAV sync complete: pushed 0, pulled 0, conflicts 0.",
        )

        assertTrue(controller.runManualSync())
        assertEquals(null, persisted.syncConfiguration.lastError)
        assertEquals(null, persisted.syncConfiguration.lastErrorCode)
    }

    @Test
    fun manualSyncRefreshesLocalStateForFailedResultWithConflicts() = runBlocking {
        var refreshed = false
        val controller = SettingsUiController(
            initialSettings = ClientSettings(
                syncConfiguration = SyncConfiguration(
                    mode = SyncMode.WebDav,
                    webDavEndpoint = "https://dav.example.com/remote.php/dav/files/alice",
                    webDavUsername = "alice",
                    webDavAppDirectory = "/someday/",
                ),
            ),
            webDavCredentialStore = FakeWebDavCredentialStore(initialSecret = "saved-secret"),
            manualSyncRunner = {
                ManualSyncResult.failure(
                    mode = SyncMode.WebDav,
                    pushedObjects = 0,
                    pulledObjects = 1,
                    conflicts = 1,
                    message = "WebDAV sync finished with conflicts: pushed 0, pulled 1, conflicts 1.",
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
                syncConfiguration = SyncConfiguration(
                    mode = SyncMode.WebDav,
                    webDavEndpoint = "https://dav.example.com/remote.php/dav/files/alice",
                    webDavUsername = "alice",
                    webDavAppDirectory = "/someday/",
                ),
            ),
            webDavCredentialStore = FakeWebDavCredentialStore(initialSecret = "saved-secret"),
            manualSyncRunner = {
                ManualSyncResult.success(
                    mode = SyncMode.WebDav,
                    pushedObjects = 0,
                    pulledObjects = 0,
                    conflicts = 0,
                    message = "Whole-product Sync V2 is active; checkpoint, local imports, and first synchronization completed.",
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
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.WebDav,
                webDavEndpoint = "https://dav.example.com/remote.php/dav/files/alice",
                webDavUsername = "alice",
                webDavAppDirectory = "/someday/",
            ),
        )
        var syncCalls = 0
        var refreshed = false
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            webDavCredentialStore = FakeWebDavCredentialStore(initialSecret = "saved-secret"),
            manualSyncRunner = {
                syncCalls += 1
                ManualSyncResult.success(
                    mode = SyncMode.WebDav,
                    pushedObjects = 1,
                    pulledObjects = 0,
                    conflicts = 0,
                    message = "WebDAV sync complete: pushed 1, pulled 0, conflicts 0.",
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
                    message = "Unexpected sync.",
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
            message = "Self-hosted sync succeeded.",
        )
        val controller = SettingsUiController(
            initialSettings = persisted,
            persistSettings = { updated ->
                persisted = updated
                updated
            },
            selfHostedSetupClient = SelfHostedSetupClient { input ->
                if (input.password == "bad-password") {
                    SelfHostedSetupResult.failure("Self-hosted login failed: invalid credentials; password redacted.")
                } else {
                    SelfHostedSetupResult.success(
                        status = SelfHostedSetupStatus(
                            ready = true,
                            message = "Self-hosted account and device registered; password redacted.",
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
        assertTrue(controller.state.feedbackMessage.orEmpty().contains("invalid credentials"))
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

        assertTrue(controller.completeManualSync(nextManualSyncResult))
        assertFalse(controller.state.manualSyncProgress.running)
        assertTrue(controller.state.manualSyncProgress.message.contains("succeeded"))
        assertEquals(2, controller.state.manualSyncProgress.pushedObjects)
        assertEquals(1, controller.state.manualSyncProgress.pulledObjects)
        assertEquals(null, persisted.syncConfiguration.lastError)

        nextManualSyncResult = ManualSyncResult.failure(
            mode = SyncMode.SelfHosted,
            message = "Self-hosted sync failed safely: HTTP 503; retryable.",
        )
        assertFalse(controller.runManualSync())
        assertFalse(controller.state.manualSyncProgress.running)
        assertTrue(controller.state.manualSyncProgress.message.contains("failed"))
        assertTrue(persisted.syncConfiguration.lastError.orEmpty().contains("HTTP 503"))
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

private class FakeWebDavCredentialStore(
    initialSecret: String? = null,
    private val failOnSave: Boolean = false,
) : WebDavCredentialStore {
    private var secret: String? = initialSecret
    private val authorityCredentials = mutableMapOf<String, WebDavAuthorityCredentials>()

    override fun load(): String? = secret

    override fun save(secret: String) {
        require(secret.isNotBlank())
        check(!failOnSave) { "secure store locked" }
        this.secret = secret
    }

    override fun clear() {
        secret = null
        authorityCredentials.clear()
    }

    override fun loadForAuthority(authorityBindingId: String): WebDavAuthorityCredentials? =
        authorityCredentials[authorityBindingId]

    override fun saveForAuthority(credentials: WebDavAuthorityCredentials) {
        check(!failOnSave) { "secure store locked" }
        authorityCredentials[credentials.authorityBindingId] = credentials
    }

    override fun clearAuthority(authorityBindingId: String) {
        authorityCredentials.remove(authorityBindingId)
    }
}

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
