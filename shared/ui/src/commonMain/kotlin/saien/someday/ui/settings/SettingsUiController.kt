@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import saien.someday.domain.notes.NotebookSummary
import saien.someday.domain.notifications.OnThisDayNotificationScheduler
import saien.someday.domain.notifications.UnavailableOnThisDayNotificationScheduler
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.ManualSyncProgress
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SelfHostedSetupResult
import saien.someday.domain.settings.SelfHostedSetupStatus
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncErrorCode
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.SyncV2MaintenanceRunner
import saien.someday.domain.settings.UnavailableSelfHostedSessionCredentialStore
import saien.someday.domain.settings.UnavailableWebDavCredentialStore
import saien.someday.domain.settings.WebDavAutoBackupFrequency
import saien.someday.domain.settings.WebDavBackupCatalogRunner
import saien.someday.domain.settings.WebDavBackupListResult
import saien.someday.domain.settings.WebDavBackupResult
import saien.someday.domain.settings.WebDavBackupRunner
import saien.someday.domain.settings.WebDavBackupStatus
import saien.someday.domain.settings.WebDavBackupVersion
import saien.someday.domain.settings.WebDavConnectionInput
import saien.someday.domain.settings.WebDavConnectionStatus
import saien.someday.domain.settings.WebDavConnectionTestResult
import saien.someday.domain.settings.WebDavConnectionTester
import saien.someday.domain.settings.WebDavAuthorityCredentials
import saien.someday.domain.settings.WebDavCredentialStore
import saien.someday.domain.settings.WebDavDiscoveredDevice
import saien.someday.domain.settings.WebDavDiscoveredDevicesRunner
import saien.someday.domain.settings.WebDavRestoreResult
import saien.someday.domain.settings.WebDavRestoreRunner
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspacePreferencesConflictResolver
import saien.someday.domain.settings.WorkspacePairingInvitation
import saien.someday.domain.settings.WorkspacePairingInvitationCanceller
import saien.someday.domain.settings.WorkspacePairingInvitationCreator
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.domain.settings.WorkspacePairingInvitationResult
import saien.someday.domain.settings.isSecureSyncEndpoint
import saien.someday.domain.settings.normalizeSelfHostedEndpoint
import saien.someday.domain.settings.normalizeWebDavAppDirectory
import saien.someday.domain.settings.webDavV2AuthorityBindingId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import saien.someday.ui.i18n.SettingsUiStrings
import saien.someday.ui.i18n.formatUiString
import kotlinx.coroutines.withContext

data class OnThisDayNotificationStrings(
    val unavailable: String = "On This Day notifications are not available on this platform.",
    val permissionRequired: String = "Notification permission is required to enable On This Day reminders.",
    val enabled: String = "On This Day notifications enabled.",
    val disabled: String = "On This Day notifications disabled.",
    val invalidTime: String = "Choose a valid notification time.",
    val timeUpdated: String = "On This Day notification time updated.",
)

data class WebDavDiscoveredDeviceStrings(
    val loadFailure: String = "Could not load discovered WebDAV devices. Sync once and try again.",
)

class SettingsUiController(
    initialSettings: ClientSettings = ClientSettings(),
    private val notebooksProvider: () -> List<NotebookSummary> = { emptyList() },
    private val persistSettings: (ClientSettings) -> ClientSettings = { it },
    private val workspacePreferencesConflictResolver: WorkspacePreferencesConflictResolver? = null,
    private val exportProvider: () -> SettingsExportSummary = { SettingsExportSummary.unavailable() },
    private val dayOneImportRunner: DayOneImportRunner = DayOneImportRunner { onResult ->
        onResult(SettingsImportSummary.unavailable("Day One import is unavailable in this build."))
    },
    private val webDavConnectionTester: WebDavConnectionTester = WebDavConnectionTester { input ->
        val errors = input.validate()
        if (errors.isEmpty()) {
            WebDavConnectionTestResult(
                success = false,
                status = WebDavConnectionStatus(
                    ready = false,
                    message = "WebDAV connection testing is unavailable in this build.",
                    appDirectory = input.sanitized().appDirectory,
                ),
            )
        } else {
            WebDavConnectionTestResult.validationFailed(errors)
        }
    },
    private val webDavCredentialStore: WebDavCredentialStore = UnavailableWebDavCredentialStore,
    private val webDavBackupRunner: WebDavBackupRunner? = null,
    private val webDavBackupCatalogRunner: WebDavBackupCatalogRunner? = null,
    private val webDavRestoreRunner: WebDavRestoreRunner? = null,
    private val webDavDiscoveredDevicesRunner: WebDavDiscoveredDevicesRunner? = null,
    private val webDavDiscoveredDeviceStrings: WebDavDiscoveredDeviceStrings =
        WebDavDiscoveredDeviceStrings(),
    private val onDataRestored: () -> Unit = {},
    private val selfHostedSetupClient: SelfHostedSetupClient = SelfHostedSetupClient { input ->
        val errors = input.validate()
        if (errors.isEmpty()) {
            SelfHostedSetupResult.failure(
                "Self-hosted setup is unavailable in this build.",
            )
        } else {
            SelfHostedSetupResult.failure(errors.joinToString(separator = " "))
        }
    },
    private val selfHostedSessionCredentialStore: SelfHostedSessionCredentialStore =
        UnavailableSelfHostedSessionCredentialStore,
    private val manualSyncRunner: ManualSyncRunner = ManualSyncRunner {
        ManualSyncResult.failure(
            mode = SyncMode.Off,
            message = "Manual sync is not connected in this build.",
        )
    },
    private val syncV2MaintenanceRunner: SyncV2MaintenanceRunner = object : SyncV2MaintenanceRunner {
        override fun rollEpoch(): ManualSyncResult = ManualSyncResult.failure(
            SyncMode.Off,
            "Sync v2 maintenance is not connected in this build.",
        )

        override fun repairIntegrity(): ManualSyncResult = rollEpoch()
    },
    private val workspacePairingInvitationCreator: WorkspacePairingInvitationCreator =
        WorkspacePairingInvitationCreator {
            WorkspacePairingInvitationResult.failure("Workspace pairing is unavailable in this build.")
        },
    private val workspacePairingInvitationJoiner: WorkspacePairingInvitationJoiner =
        WorkspacePairingInvitationJoiner {
            WorkspaceJoinResult.failure("Workspace pairing is unavailable in this build.")
        },
    private val workspacePairingInvitationCanceller: WorkspacePairingInvitationCanceller =
        WorkspacePairingInvitationCanceller {
            WorkspaceJoinResult.failure("Workspace pairing is unavailable in this build.")
        },
    private val onThisDayNotificationScheduler: OnThisDayNotificationScheduler =
        UnavailableOnThisDayNotificationScheduler,
    private val onThisDayNotificationStrings: OnThisDayNotificationStrings =
        OnThisDayNotificationStrings(),
    private val uiStrings: SettingsUiStrings = SettingsUiStrings(),
    private val currentEpochMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val onThisDayNotificationsSupported: Boolean = onThisDayNotificationScheduler.isSupported

    private var webDavBackupVersions: List<WebDavBackupVersion> = emptyList()
    private var webDavDiscoveredDevices: List<WebDavDiscoveredDevice> = emptyList()
    private var currentWorkspacePairingInvitation: WorkspacePairingInvitationUi? = null
    private var currentImportSummary: SettingsImportSummary? = null
    private var importRunning: Boolean = false
    private var nextFeedbackEventId = 0L
    private var webDavCredentialSaved: Boolean = false

    var state: SettingsUiState by mutableStateOf(
        buildState(
            settings = initialSettings,
            manualSyncProgress = ManualSyncProgress.idle(initialSettings.syncConfiguration.mode),
        ),
    )
        private set

    suspend fun refresh() {
        webDavCredentialSaved = withContext(backgroundDispatcher) {
            runCatching { webDavCredentialStore.hasSavedCredential() }.getOrDefault(false)
        }
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = state.feedbackMessage,
            feedbackEventId = state.feedbackEventId,
            manualSyncProgress = state.manualSyncProgress,
        )
        rescheduleOnThisDayNotifications()
    }

    suspend fun refreshWebDavDiscoveredDevices(): Boolean {
        val runner = webDavDiscoveredDevicesRunner ?: return false
        val result = withContext(backgroundDispatcher) { runner.listDiscoveredDevices() }
        if (result.success) webDavDiscoveredDevices = result.devices
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = if (result.success) null else webDavDiscoveredDeviceStrings.loadFailure,
            manualSyncProgress = state.manualSyncProgress,
        )
        return result.success
    }

    suspend fun rescheduleOnThisDayNotifications() {
        syncOnThisDayNotificationSchedule(state.settings.onThisDayNotifications)
    }

    suspend fun toggleOnThisDayNotifications(enabled: Boolean): Boolean {
        if (!onThisDayNotificationScheduler.isSupported) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = onThisDayNotificationStrings.unavailable,
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }
        if (enabled) {
            val permitted = withContext(backgroundDispatcher) {
                onThisDayNotificationScheduler.ensurePermission()
            }
            if (!permitted) {
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = onThisDayNotificationStrings.permissionRequired,
                    manualSyncProgress = state.manualSyncProgress,
                )
                return false
            }
        }
        val updatedPreferences = state.settings.onThisDayNotifications.copy(enabled = enabled)
        val persisted = persist(
            updated = state.settings.copy(onThisDayNotifications = updatedPreferences),
            successMessage = if (enabled) {
                onThisDayNotificationStrings.enabled
            } else {
                onThisDayNotificationStrings.disabled
            },
        )
        if (persisted) {
            syncOnThisDayNotificationSchedule(updatedPreferences)
        }
        return persisted
    }

    suspend fun setOnThisDayNotificationTime(
        hour: Int,
        minute: Int,
    ): Boolean {
        if (!onThisDayNotificationScheduler.isSupported) {
            return false
        }
        if (hour !in 0..23 || minute !in 0..59) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = onThisDayNotificationStrings.invalidTime,
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }
        val updatedPreferences = state.settings.onThisDayNotifications.copy(
            hour = hour,
            minute = minute,
        )
        val persisted = persist(
            updated = state.settings.copy(onThisDayNotifications = updatedPreferences),
            successMessage = onThisDayNotificationStrings.timeUpdated,
        )
        if (persisted) {
            syncOnThisDayNotificationSchedule(updatedPreferences)
        }
        return persisted
    }

    private suspend fun syncOnThisDayNotificationSchedule(preferences: OnThisDayNotificationPreferences) {
        if (!onThisDayNotificationScheduler.isSupported) {
            return
        }
        withContext(backgroundDispatcher) {
            runCatching {
                onThisDayNotificationScheduler.syncSchedule(preferences)
            }
        }
    }

    suspend fun selectTheme(theme: ClientTheme): Boolean =
        persist(
            updated = state.settings.copy(theme = theme),
            successMessage = uiStrings.themeUpdated,
        )

    /**
     * Device-local language override. Always editable, including during workspace
     * preferences conflicts, because it is not workspace-synced.
     */
    suspend fun selectLanguage(language: AppLanguage): Boolean =
        persist(
            updated = state.settings.copy(appLanguage = language),
            successMessage = uiStrings.languageUpdated,
        )

    suspend fun togglePreviewByDefault(enabled: Boolean): Boolean =
        persist(
            updated = state.settings.copy(
                editorPreferences = state.settings.editorPreferences.copy(previewByDefault = enabled),
            ),
            successMessage = uiStrings.previewUpdated,
        )

    suspend fun toggleMarkdownToolbarVisible(enabled: Boolean): Boolean =
        persist(
            updated = state.settings.copy(
                editorPreferences = state.settings.editorPreferences.copy(markdownToolbarVisible = enabled),
            ),
            successMessage = uiStrings.toolbarUpdated,
        )

    suspend fun selectDefaultNotebook(notebookId: String?): Boolean {
        val validNotebookId = notebookId?.takeIf { candidate ->
            state.defaultNotebookOptions.any { it.id == candidate }
        }
        if (notebookId != null && validNotebookId == null) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = uiStrings.missingNotebook,
            )
            return false
        }

        return persist(
            updated = state.settings.copy(defaultNotebookId = validNotebookId),
            successMessage = if (validNotebookId == null) {
                uiStrings.defaultNotebookCleared
            } else {
                uiStrings.defaultNotebookUpdated
            },
        )
    }

    suspend fun resolveWorkspacePreferencesBranch(versionId: String): Boolean {
        val conflict = state.settings.workspacePreferencesState.conflict ?: return false
        val resolver = workspacePreferencesConflictResolver ?: run {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = uiStrings.prefsConflictUnavailable,
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }
        return runCatching {
            withContext(backgroundDispatcher) {
                resolver.resolveWorkspacePreferencesBranch(
                    conflict.conflictId,
                    versionId,
                    conflict.expectedHeadVersionIds,
                )
            }
        }.fold(
            onSuccess = { resolved ->
                state = buildState(
                    settings = resolved,
                    exportSummary = state.exportSummary,
                    feedbackMessage = uiStrings.prefsConflictResolved,
                    manualSyncProgress = state.manualSyncProgress,
                )
                true
            },
            onFailure = { failure ->
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = formatUiString(uiStrings.cannotResolvePrefs, failure.message ?: uiStrings.unknownError),
                    manualSyncProgress = state.manualSyncProgress,
                )
                false
            },
        )
    }

    suspend fun recordLastSelectedNotebook(notebookId: String): Boolean {
        if (state.settings.lastSelectedNotebookId == notebookId) {
            return true
        }
        val validNotebookId = notebookId.takeIf { candidate ->
            notebooksProvider().any { it.id == candidate }
        } ?: return false

        return runCatching {
            withContext(backgroundDispatcher) {
                persistSettings(state.settings.copy(lastSelectedNotebookId = validNotebookId))
            }
        }.fold(
            onSuccess = { persisted ->
                state = buildState(
                    settings = persisted,
                    exportSummary = state.exportSummary,
                    feedbackMessage = state.feedbackMessage,
                    feedbackEventId = state.feedbackEventId,
                    manualSyncProgress = if (persisted.syncConfiguration.mode == state.manualSyncProgress.mode) {
                        state.manualSyncProgress
                    } else {
                        ManualSyncProgress.idle(persisted.syncConfiguration.mode)
                    },
                )
                true
            },
            onFailure = { false },
        )
    }

    suspend fun selectSyncMode(mode: SyncMode): Boolean =
        persist(
            updated = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(mode = mode),
            ),
            successMessage = uiStrings.syncModeUpdated,
        )

    suspend fun toggleWebDavAutoBackup(enabled: Boolean): Boolean =
        persist(
            updated = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    mode = SyncMode.WebDav,
                    webDavAutoBackupEnabled = enabled,
                ),
            ),
            successMessage = if (enabled) {
                uiStrings.autoBackupEnabled
            } else {
                uiStrings.autoBackupDisabled
            },
        )

    suspend fun selectWebDavAutoBackupFrequency(frequency: WebDavAutoBackupFrequency): Boolean =
        persist(
            updated = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    mode = SyncMode.WebDav,
                    webDavAutoBackupFrequency = frequency,
                ),
            ),
            successMessage = formatUiString(uiStrings.cadenceSet, frequencyDisplayName(frequency)),
        )

    suspend fun saveWebDavEndpoint(endpoint: String): Boolean =
        saveWebDavConfiguration(
            endpoint = endpoint,
            username = state.settings.syncConfiguration.webDavUsername,
            password = null,
            appDirectory = state.settings.syncConfiguration.webDavAppDirectory,
            successMessage = uiStrings.webdavSaved,
        )

    suspend fun saveWebDavConfiguration(
        endpoint: String,
        username: String?,
        password: String? = null,
        appDirectory: String,
        lastTest: WebDavConnectionStatus? = null,
        successMessage: String = uiStrings.webdavSaved,
    ): Boolean {
        val input = WebDavConnectionInput(
            endpoint = endpoint,
            username = username,
            password = null,
            appDirectory = appDirectory,
        ).sanitized()
        val validationErrors = input.validate()
        if (validationErrors.isNotEmpty()) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = validationErrors.joinToString(separator = " "),
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }

        val connectionChanged = !state.settings.syncConfiguration.matchesWebDavConnection(input)
        if (connectionChanged && !retainCurrentWebDavAuthorityBeforeChange()) return false
        val credentialUpdated = persistWebDavCredentialIfProvided(password) ?: return false
        if (!retainConfiguredWebDavAuthority(input)) return false
        val persistedLastTest = lastTest ?: state.settings.syncConfiguration.webDavLastTest
            ?.takeIf { state.settings.syncConfiguration.matchesWebDavConnection(input) }
        val shouldClearSyncError = connectionChanged || lastTest?.ready != false
        return persist(
            updated = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    webDavEndpoint = input.endpoint,
                    webDavUsername = input.username,
                    webDavAppDirectory = input.appDirectory,
                    webDavLastTest = persistedLastTest,
                    lastError = if (shouldClearSyncError) null else state.settings.syncConfiguration.lastError,
                    lastErrorCode = if (shouldClearSyncError) null else state.settings.syncConfiguration.lastErrorCode,
                ),
            ),
            successMessage = if (credentialUpdated) {
                "$successMessage ${uiStrings.credentialSavedSuffix}"
            } else {
                successMessage
            },
        )
    }

    suspend fun testAndSaveWebDavConnection(
        endpoint: String,
        username: String?,
        password: String?,
        appDirectory: String,
    ): Boolean {
        val input = createWebDavInput(
            endpoint = endpoint,
            username = username,
            password = password,
            appDirectory = appDirectory,
        ).sanitized()
        val validationErrors = input.validate()
        val credentialErrors = input.validateWebDavCredential()
        if (validationErrors.isNotEmpty() || credentialErrors.isNotEmpty()) {
            val status = WebDavConnectionStatus(
                ready = false,
                message = (validationErrors + credentialErrors).joinToString(separator = " "),
                appDirectory = input.appDirectory,
            )
            state = buildState(
                settings = state.settings.copy(
                    syncConfiguration = state.settings.syncConfiguration.copy(webDavLastTest = status),
                ),
                exportSummary = state.exportSummary,
                feedbackMessage = status.message,
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }

        val result = runCatching {
            withContext(backgroundDispatcher) { webDavConnectionTester.testConnection(input) }
        }.getOrElse { failure ->
            WebDavConnectionTestResult(
                success = false,
                status = WebDavConnectionStatus(
                    ready = false,
                    message = formatUiString(uiStrings.webdavConnectionFailed, failure.message ?: uiStrings.unknownError),
                    appDirectory = input.appDirectory,
                ),
            )
        }
        if (!result.success) {
            val current = state.settings.syncConfiguration
            if (current.mode == SyncMode.WebDav && !current.matchesWebDavConnection(input)) {
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = result.status.message,
                    manualSyncProgress = state.manualSyncProgress,
                )
                return false
            }
            persist(
                updated = state.settings.copy(
                    syncConfiguration = current.copy(
                        mode = if (current.mode == SyncMode.WebDav) SyncMode.WebDav else SyncMode.Off,
                        webDavEndpoint = input.endpoint,
                        webDavUsername = input.username,
                        webDavAppDirectory = input.appDirectory,
                        webDavLastTest = result.status,
                    ),
                ),
                successMessage = result.status.message,
            )
            return false
        }

        val connectionChanged = !state.settings.syncConfiguration.matchesWebDavConnection(input)
        if (connectionChanged && !retainCurrentWebDavAuthorityBeforeChange()) return false
        val credentialUpdated = persistWebDavCredentialIfProvided(password) ?: return false
        if (!retainConfiguredWebDavAuthority(input)) return false
        return persist(
            updated = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    mode = SyncMode.WebDav,
                    webDavEndpoint = input.endpoint,
                    webDavUsername = input.username,
                    webDavAppDirectory = input.appDirectory,
                    webDavLastTest = result.status,
                    lastError = null,
                    lastErrorCode = null,
                ),
            ),
            successMessage = if (credentialUpdated) {
                uiStrings.webdavConnectionOkSaved
            } else {
                uiStrings.webdavConnectionOk
            },
        )
    }

    suspend fun backupToWebDav(
        endpoint: String,
        username: String?,
        password: String?,
        appDirectory: String,
    ): Boolean {
        val input = createWebDavInput(
            endpoint = endpoint,
            username = username,
            password = password,
            appDirectory = appDirectory,
        ).sanitized()
        val validationErrors = input.validate()
        val credentialErrors = input.validateWebDavCredential()
        if (validationErrors.isNotEmpty() || credentialErrors.isNotEmpty()) {
            return recordWebDavBackupResult(
                WebDavBackupResult.failure((validationErrors + credentialErrors).joinToString(separator = " ")),
            )
        }
        val runner = webDavBackupRunner ?: return recordWebDavBackupResult(
            WebDavBackupResult.failure(uiStrings.webdavBackupUnavailable),
        )
        val result = runCatching {
            withContext(backgroundDispatcher) { runner.backup(input) }
        }.getOrElse { failure ->
            WebDavBackupResult.failure(formatUiString(uiStrings.webdavBackupFailed, failure.message ?: uiStrings.unknownError))
        }
        if (result.success && persistWebDavCredentialIfProvided(password) == null) {
            return false
        }
        val updated = state.settings.copy(
            syncConfiguration = state.settings.syncConfiguration.copy(
                mode = SyncMode.WebDav,
                webDavEndpoint = input.endpoint,
                webDavUsername = input.username,
                webDavAppDirectory = input.appDirectory,
                webDavLastTest = WebDavConnectionStatus(
                    ready = result.success,
                    message = result.message,
                    appDirectory = input.appDirectory,
                ),
                webDavLastBackup = WebDavBackupStatus(
                    success = result.success,
                    message = result.message,
                    versionLabel = result.version?.label,
                    completedAtEpochMillis = currentEpochMillis(),
                ),
                lastError = if (result.success) null else result.message,
                lastErrorCode = null,
            ),
        )
        val persisted = runCatching {
            withContext(backgroundDispatcher) { persistSettings(updated) }
        }.getOrElse { failure ->
            state = buildState(
                settings = state.settings.copy(
                    syncConfiguration = state.settings.syncConfiguration.copy(
                        lastError = failure.message,
                        lastErrorCode = null,
                    ),
                ),
                exportSummary = state.exportSummary,
                feedbackMessage = formatUiString(uiStrings.webdavSaveFailed, failure.message ?: uiStrings.unknownError),
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }
        result.version?.let { version ->
            webDavBackupVersions = (listOf(version) + webDavBackupVersions).distinctBy { it.path ?: it.id }
        }
        state = buildState(
            settings = persisted,
            exportSummary = state.exportSummary,
            feedbackMessage = result.message,
            manualSyncProgress = state.manualSyncProgress,
        )
        return result.success
    }

    suspend fun runDueWebDavAutoBackup(): Boolean {
        val sync = state.settings.syncConfiguration
        if (!sync.webDavAutoBackupEnabled || sync.mode != SyncMode.WebDav) {
            return false
        }
        val endpoint = sync.webDavEndpoint ?: return false
        val username = sync.webDavUsername ?: return false
        if (!webDavCredentialSaved) {
            return false
        }
        val now = currentEpochMillis()
        val lastCompletedAt = sync.webDavLastBackup?.completedAtEpochMillis
        if (lastCompletedAt != null && now - lastCompletedAt < sync.webDavAutoBackupFrequency.intervalMillis) {
            return false
        }
        return backupToWebDav(
            endpoint = endpoint,
            username = username,
            password = null,
            appDirectory = sync.webDavAppDirectory,
        )
    }

    suspend fun refreshWebDavBackupVersions(
        endpoint: String,
        username: String?,
        password: String?,
        appDirectory: String,
    ): Boolean {
        val input = createWebDavInput(
            endpoint = endpoint,
            username = username,
            password = password,
            appDirectory = appDirectory,
        ).sanitized()
        val validationErrors = input.validate()
        val credentialErrors = input.validateWebDavCredential()
        if (validationErrors.isNotEmpty() || credentialErrors.isNotEmpty()) {
            return recordWebDavBackupListResult(
                WebDavBackupListResult.failure((validationErrors + credentialErrors).joinToString(separator = " ")),
            )
        }
        val runner = webDavBackupCatalogRunner ?: return recordWebDavBackupListResult(
            WebDavBackupListResult.failure(uiStrings.backupHistoryUnavailable),
        )
        val result = runCatching {
            withContext(backgroundDispatcher) { runner.listBackups(input) }
        }.getOrElse { failure ->
            WebDavBackupListResult.failure(formatUiString(uiStrings.backupHistoryFailed, failure.message ?: uiStrings.unknownError))
        }
        return recordWebDavBackupListResult(result)
    }

    suspend fun restoreFromWebDav(
        endpoint: String,
        username: String?,
        password: String?,
        appDirectory: String,
        backupPath: String? = null,
    ): Boolean {
        val input = createWebDavInput(
            endpoint = endpoint,
            username = username,
            password = password,
            appDirectory = appDirectory,
        ).sanitized()
        val validationErrors = input.validate()
        val credentialErrors = input.validateWebDavCredential()
        if (validationErrors.isNotEmpty() || credentialErrors.isNotEmpty()) {
            return recordWebDavRestoreResult(
                WebDavRestoreResult.failure((validationErrors + credentialErrors).joinToString(separator = " ")),
            )
        }
        val runner = webDavRestoreRunner ?: return recordWebDavRestoreResult(
            WebDavRestoreResult.failure(uiStrings.restoreUnavailable),
        )
        val result = runCatching {
            withContext(backgroundDispatcher) { runner.restore(input, backupPath) }
        }.getOrElse { failure ->
            WebDavRestoreResult.failure(formatUiString(uiStrings.restoreFailed, failure.message ?: uiStrings.unknownError))
        }
        if (result.success && persistWebDavCredentialIfProvided(password) == null) {
            return false
        }
        val updated = state.settings.copy(
            syncConfiguration = state.settings.syncConfiguration.copy(
                mode = SyncMode.WebDav,
                webDavEndpoint = input.endpoint,
                webDavUsername = input.username,
                webDavAppDirectory = input.appDirectory,
                webDavLastTest = WebDavConnectionStatus(
                    ready = result.success,
                    message = result.message,
                    appDirectory = input.appDirectory,
                ),
                lastError = if (result.success) null else result.message,
                lastErrorCode = null,
            ),
        )
        val persisted = runCatching {
            withContext(backgroundDispatcher) { persistSettings(updated) }
        }.getOrElse { failure ->
            state = buildState(
                settings = state.settings.copy(
                    syncConfiguration = state.settings.syncConfiguration.copy(
                        lastError = failure.message,
                        lastErrorCode = null,
                    ),
                ),
                exportSummary = state.exportSummary,
                feedbackMessage = formatUiString(uiStrings.webdavSaveFailed, failure.message ?: uiStrings.unknownError),
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }
        state = buildState(
            settings = persisted,
            exportSummary = state.exportSummary,
            feedbackMessage = result.message,
            manualSyncProgress = state.manualSyncProgress,
        )
        if (result.success) {
            onDataRestored()
        }
        return result.success
    }

    suspend fun clearWebDavCredential(): Boolean {
        runCatching {
            withContext(backgroundDispatcher) { webDavCredentialStore.clear() }
        }.getOrElse { failure ->
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = formatUiString(uiStrings.credentialRemoveFailed, failure.message ?: uiStrings.unknownError),
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }
        webDavCredentialSaved = false
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = uiStrings.credentialRemoved,
            manualSyncProgress = state.manualSyncProgress,
        )
        return true
    }

    suspend fun saveSelfHostedEndpoint(endpoint: String): Boolean {
        val normalizedEndpoint = normalizeSelfHostedEndpoint(endpoint)
        if (!isSecureSyncEndpoint(normalizedEndpoint)) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = uiStrings.selfHostedHttps,
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }
        return persist(
            updated = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    mode = SyncMode.SelfHosted,
                    selfHostedEndpoint = normalizedEndpoint,
                ),
            ),
            successMessage = uiStrings.selfHostedSaved,
        )
    }

    suspend fun setupSelfHosted(input: SelfHostedSetupInput): Boolean {
        val sanitized = input.sanitized()
        val validationErrors = sanitized.validate()
        if (validationErrors.isNotEmpty()) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = validationErrors.joinToString(separator = " "),
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }

        val result = runCatching {
            withContext(backgroundDispatcher) { selfHostedSetupClient.setup(sanitized) }
        }.getOrElse { failure ->
            SelfHostedSetupResult.failure(
                formatUiString(uiStrings.selfHostedSetupFailed, failure.message ?: uiStrings.unknownError),
            )
        }
        val session = result.session ?: state.settings.syncConfiguration.selfHostedSession.copy(loggedIn = false)
        val updatedSettings = state.settings.copy(
            activeDeviceId = session.deviceId ?: state.settings.activeDeviceId,
            syncConfiguration = state.settings.syncConfiguration.copy(
                mode = SyncMode.SelfHosted,
                selfHostedEndpoint = sanitized.endpoint,
                selfHostedSession = session,
                lastError = if (result.success) null else result.status.message,
                lastErrorCode = null,
            ),
        )
        return persist(
            updated = updatedSettings,
            successMessage = result.status.message,
        ) && result.success
    }

    suspend fun clearSelfHostedSession(): Boolean {
        runCatching {
            withContext(backgroundDispatcher) { selfHostedSessionCredentialStore.clear() }
        }.getOrElse { failure ->
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = formatUiString(uiStrings.sessionRemoveFailed, failure.message ?: uiStrings.unknownError),
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }
        return persist(
            updated = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    mode = SyncMode.SelfHosted,
                    selfHostedSession = SelfHostedSessionSummary(),
                ),
            ),
            successMessage = uiStrings.sessionCleared,
        )
    }

    fun canRunAutomaticSync(): Boolean =
        !state.manualSyncProgress.running &&
            state.settings.syncConfiguration.mode != SyncMode.Off &&
            syncBlockingMessage(state.settings.syncConfiguration.mode) == null

    fun beginManualSync(): Boolean =
        beginManualSync(showFeedback = true)

    private fun beginManualSync(showFeedback: Boolean): Boolean {
        val mode = state.settings.syncConfiguration.mode
        val blockingMessage = syncBlockingMessage(mode)
        if (blockingMessage != null) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = if (showFeedback) blockingMessage else state.feedbackMessage,
                feedbackEventId = if (showFeedback) null else state.feedbackEventId,
                manualSyncProgress = ManualSyncProgress.idle(mode),
            )
            return false
        }
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = if (showFeedback) uiStrings.syncStarted else state.feedbackMessage,
            feedbackEventId = if (showFeedback) null else state.feedbackEventId,
            manualSyncProgress = ManualSyncProgress.inProgress(mode),
        )
        return true
    }

    suspend fun completeManualSync(result: ManualSyncResult): Boolean =
        completeManualSync(result, showFeedback = true)

    private suspend fun completeManualSync(
        result: ManualSyncResult,
        showFeedback: Boolean,
    ): Boolean {
        val updatedSettings = state.settings.copy(
            syncConfiguration = state.settings.syncConfiguration.copy(
                lastError = if (result.success) null else result.message,
                lastErrorCode = if (result.success) null else result.errorCode,
            ),
        )
        val persisted = runCatching {
            withContext(backgroundDispatcher) { persistSettings(updatedSettings) }
        }.getOrElse { state.settings }
        state = buildState(
            settings = persisted,
            exportSummary = state.exportSummary,
            feedbackMessage = if (showFeedback) result.message else state.feedbackMessage,
            feedbackEventId = if (showFeedback) null else state.feedbackEventId,
            manualSyncProgress = ManualSyncProgress.fromResult(result),
        )
        // Always refresh product lists after a successful sync: first-time V2
        // activation / join bootstrap can materialize notebooks and notes even
        // when a later no-op pass reports zero transport deltas. Partial
        // failures that already surfaced conflicts or pulls also need a refresh.
        if (result.success || result.hasVisibleSyncChanges) {
            onDataRestored()
        }
        return result.success
    }

    suspend fun runManualSync(): Boolean {
        if (!beginManualSync()) {
            return false
        }
        val mode = state.settings.syncConfiguration.mode
        val result = runCatching {
            withContext(backgroundDispatcher) { manualSyncRunner.run() }
        }.getOrElse { failure ->
            ManualSyncResult.failure(
                mode = mode,
                message = formatUiString(uiStrings.syncFailed, failure.message ?: uiStrings.unknownError),
            )
        }
        return completeManualSync(result)
    }

    suspend fun rollSyncV2Epoch(): Boolean = runV2Maintenance { syncV2MaintenanceRunner.rollEpoch() }

    suspend fun repairSyncV2Integrity(): Boolean = runV2Maintenance {
        syncV2MaintenanceRunner.repairIntegrity()
    }

    suspend fun recoverSyncV2FromVerifiedLocalCheckpoint(): Boolean = runV2Maintenance {
        syncV2MaintenanceRunner.recoverWithVerifiedLocalCheckpoint(userConfirmedPotentialDataLoss = true)
    }

    suspend fun migrateSyncV2Authority(): Boolean = runV2Maintenance {
        syncV2MaintenanceRunner.migrateToConfiguredRemote()
    }

    suspend fun collectExpiredSyncV2History(): Boolean = runV2Maintenance {
        syncV2MaintenanceRunner.collectExpiredLocalHistory()
    }

    private suspend fun runV2Maintenance(operation: () -> ManualSyncResult): Boolean {
        if (!beginManualSync()) return false
        val mode = state.settings.syncConfiguration.mode
        val result = runCatching {
            withContext(backgroundDispatcher) { operation() }
        }.getOrElse { failure ->
            ManualSyncResult.failure(
                mode,
                formatUiString(uiStrings.v2MaintenanceFailed, failure.message ?: uiStrings.unknownError),
            )
        }
        return completeManualSync(result)
    }

    suspend fun runAutomaticSync(): Boolean {
        if (!canRunAutomaticSync() || !beginManualSync(showFeedback = false)) {
            return false
        }
        val mode = state.settings.syncConfiguration.mode
        val result = runCatching {
            withContext(backgroundDispatcher) { manualSyncRunner.run() }
        }.getOrElse { failure ->
            ManualSyncResult.failure(
                mode = mode,
                message = formatUiString(uiStrings.syncFailed, failure.message ?: uiStrings.unknownError),
            )
        }
        val shouldShowFeedback = !result.success || result.pulledObjects > 0 || result.conflicts > 0
        return completeManualSync(result, showFeedback = shouldShowFeedback)
    }

    suspend fun recordSyncError(
        error: String?,
        errorCode: SyncErrorCode? = null,
    ): Boolean =
        persist(
            updated = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    lastError = error?.takeIf { it.isNotBlank() },
                    lastErrorCode = error?.takeIf { it.isNotBlank() }?.let { errorCode },
                ),
            ),
            successMessage = if (error.isNullOrBlank()) {
                uiStrings.syncStatusCleared
            } else {
                uiStrings.syncStatusUpdated
            },
        )

    suspend fun runLocalExport(): Boolean =
        runCatching {
            withContext(backgroundDispatcher) { exportProvider() }
        }.fold(
            onSuccess = { summary ->
                state = buildState(
                    settings = state.settings,
                    exportSummary = summary,
                    feedbackMessage = if (summary.destinationLabel == null) {
                        formatUiString(uiStrings.exportPrepared, summary.notebookCount, summary.noteCount)
                    } else {
                        formatUiString(uiStrings.exportSaved, summary.notebookCount, summary.noteCount)
                    },
                    manualSyncProgress = state.manualSyncProgress,
                )
                true
            },
            onFailure = { failure ->
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = formatUiString(uiStrings.exportFailed, failure.message ?: uiStrings.unknownError),
                    manualSyncProgress = state.manualSyncProgress,
                )
                false
            },
        )

    fun startDayOneImport(): Boolean {
        if (importRunning) {
            return false
        }
        importRunning = true
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = uiStrings.chooseDayOne,
            manualSyncProgress = state.manualSyncProgress,
        )
        return runCatching {
            dayOneImportRunner.start { summary ->
                importRunning = false
                currentImportSummary = summary
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = summary.message,
                    manualSyncProgress = state.manualSyncProgress,
                )
                if (summary.success) {
                    onDataRestored()
                }
            }
        }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                importRunning = false
                val summary = SettingsImportSummary.failure(
                    sourceName = "Day One",
                    message = formatUiString(uiStrings.dayOneImportFailed, failure.message ?: uiStrings.unknownError),
                )
                currentImportSummary = summary
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = summary.message,
                    manualSyncProgress = state.manualSyncProgress,
                )
                false
            },
        )
    }

    suspend fun createWorkspacePairingInvitation(): Boolean {
        val result = runCatching {
            withContext(backgroundDispatcher) { workspacePairingInvitationCreator.createInvitation() }
        }.getOrElse {
            WorkspacePairingInvitationResult.failure(uiStrings.pairingInvitationFailed)
        }
        currentWorkspacePairingInvitation = result.invitation?.let(::WorkspacePairingInvitationUi)
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = result.message,
            manualSyncProgress = state.manualSyncProgress,
        )
        return result.success
    }

    suspend fun cancelWorkspacePairingInvitation(): Boolean {
        val invitation = currentWorkspacePairingInvitation?.domainInvitation()
        if (invitation == null) {
            return true
        }
        val result = runCatching {
            withContext(backgroundDispatcher) {
                workspacePairingInvitationCanceller.cancelInvitation(invitation)
            }
        }.getOrElse {
            WorkspaceJoinResult.failure(uiStrings.pairingFailed)
        }
        if (result.success) {
            currentWorkspacePairingInvitation = null
        }
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = result.message,
            manualSyncProgress = state.manualSyncProgress,
        )
        return result.success
    }

    fun discardWorkspacePairingInvitationAtExpiry(expiresAtEpochMillis: Long) {
        val current = currentWorkspacePairingInvitation ?: return
        if (current.expiresAtEpochMillis != expiresAtEpochMillis ||
            currentEpochMillis() < expiresAtEpochMillis
        ) {
            return
        }
        currentWorkspacePairingInvitation = null
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = state.feedbackMessage,
            feedbackEventId = state.feedbackEventId,
            manualSyncProgress = state.manualSyncProgress,
        )
    }

    suspend fun joinWorkspaceWithToken(tokenInput: String): Boolean {
        if (tokenInput.isBlank()) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = uiStrings.enterPairingToken,
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }
        val result = runCatching {
            withContext(backgroundDispatcher) {
                workspacePairingInvitationJoiner.joinWithToken(tokenInput)
            }
        }.getOrElse {
            WorkspaceJoinResult.failure(uiStrings.pairingFailed)
        }
        if (result.success) {
            currentWorkspacePairingInvitation = null
            // Prior sync failures (wrong first-run key against a remote epoch)
            // are stale after a successful join package restore.
            val cleared = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    lastError = null,
                    lastErrorCode = null,
                ),
            )
            val persisted = runCatching {
                withContext(backgroundDispatcher) { persistSettings(cleared) }
            }.getOrElse { cleared }
            state = buildState(
                settings = persisted,
                exportSummary = state.exportSummary,
                feedbackMessage = result.message,
                manualSyncProgress = state.manualSyncProgress,
            )
            return true
        }
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = result.message,
            manualSyncProgress = state.manualSyncProgress,
        )
        return false
    }

    private val WebDavAutoBackupFrequency.intervalMillis: Long
        get() =
            when (this) {
                WebDavAutoBackupFrequency.Daily -> 24L * 60L * 60L * 1000L
                WebDavAutoBackupFrequency.Weekly -> 7L * 24L * 60L * 60L * 1000L
            }

    private fun recordWebDavBackupResult(result: WebDavBackupResult): Boolean {
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = result.message,
            manualSyncProgress = state.manualSyncProgress,
        )
        return result.success
    }

    private fun recordWebDavRestoreResult(result: WebDavRestoreResult): Boolean {
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = result.message,
            manualSyncProgress = state.manualSyncProgress,
        )
        return result.success
    }

    private fun recordWebDavBackupListResult(result: WebDavBackupListResult): Boolean {
        if (result.success) {
            webDavBackupVersions = result.versions
        }
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = result.message,
            manualSyncProgress = state.manualSyncProgress,
        )
        return result.success
    }

    private suspend fun createWebDavInput(
        endpoint: String,
        username: String?,
        password: String?,
        appDirectory: String,
    ): WebDavConnectionInput {
        val typedSecret = password?.takeIf { it.isNotBlank() }
        val savedSecret = if (typedSecret == null) {
            withContext(backgroundDispatcher) {
                runCatching { webDavCredentialStore.load() }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        } else {
            null
        }
        if (savedSecret != null) {
            webDavCredentialSaved = true
        }
        return WebDavConnectionInput(
            endpoint = endpoint,
            username = username,
            password = typedSecret ?: savedSecret,
            appDirectory = appDirectory,
        ).sanitized()
    }

    private suspend fun persistWebDavCredentialIfProvided(password: String?): Boolean? {
        val secret = password?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            withContext(backgroundDispatcher) { webDavCredentialStore.save(secret) }
            webDavCredentialSaved = true
            true
        }.getOrElse { failure ->
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = formatUiString(uiStrings.credentialSaveFailed, failure.message ?: uiStrings.unknownError),
                manualSyncProgress = state.manualSyncProgress,
            )
            null
        }
    }

    private suspend fun retainCurrentWebDavAuthorityBeforeChange(): Boolean {
        val current = state.settings.syncConfiguration
        val endpoint = current.webDavEndpoint?.takeIf(String::isNotBlank) ?: return true
        val username = current.webDavUsername?.takeIf(String::isNotBlank) ?: return true
        val secret = withContext(backgroundDispatcher) {
            runCatching(webDavCredentialStore::load).getOrNull()
        }?.takeIf(String::isNotBlank) ?: return credentialVaultFailure(
            "The current V2 WebDAV credential is missing; the source authority was not replaced.",
        )
        val record = runCatching {
            WebDavAuthorityCredentials(
                webDavV2AuthorityBindingId(endpoint, current.webDavAppDirectory),
                endpoint,
                username,
                current.webDavAppDirectory,
                secret,
            )
        }.getOrElse { return credentialVaultFailure(it.message ?: "The current WebDAV authority binding is invalid.") }
        return runCatching {
            withContext(backgroundDispatcher) { webDavCredentialStore.saveForAuthority(record) }
        }.fold(
            onSuccess = { true },
            onFailure = { credentialVaultFailure(it.message ?: "The current WebDAV authority credential could not be retained.") },
        )
    }

    private suspend fun retainConfiguredWebDavAuthority(input: WebDavConnectionInput): Boolean {
        val username = input.username?.takeIf(String::isNotBlank)
            ?: return credentialVaultFailure("The target WebDAV username is missing.")
        val secret = withContext(backgroundDispatcher) {
            runCatching(webDavCredentialStore::load).getOrNull()
        }?.takeIf(String::isNotBlank)
            ?: return credentialVaultFailure("The target V2 WebDAV credential is missing.")
        val record = WebDavAuthorityCredentials(
            webDavV2AuthorityBindingId(input.endpoint, input.appDirectory),
            input.endpoint,
            username,
            input.appDirectory,
            secret,
        )
        return runCatching {
            withContext(backgroundDispatcher) { webDavCredentialStore.saveForAuthority(record) }
        }.fold(
            onSuccess = { true },
            onFailure = { credentialVaultFailure(it.message ?: "The target WebDAV authority credential could not be retained.") },
        )
    }

    private fun credentialVaultFailure(message: String): Boolean {
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = formatUiString(uiStrings.credentialSaveFailed, message),
            manualSyncProgress = state.manualSyncProgress,
        )
        return false
    }


    private fun frequencyDisplayName(frequency: WebDavAutoBackupFrequency): String =
        when (frequency) {
            WebDavAutoBackupFrequency.Daily -> uiStrings.freqDaily
            WebDavAutoBackupFrequency.Weekly -> uiStrings.freqWeekly
        }

    private fun WebDavConnectionInput.validateWebDavCredential(): List<String> =
        buildList {
            if (username.isNullOrBlank()) {
                add(uiStrings.enterUsername)
            }
            if (password.isNullOrBlank()) {
                add(uiStrings.enterCredential)
            }
        }

    private fun SyncConfiguration.webDavSyncBlockingMessage(): String? =
        when {
            webDavEndpoint.isNullOrBlank() -> uiStrings.configureBeforeSync
            webDavUsername.isNullOrBlank() -> uiStrings.usernameBeforeSync
            !webDavCredentialSaved ->
                uiStrings.credentialBeforeSync
            else -> null
        }

    private fun syncBlockingMessage(mode: SyncMode): String? =
        when (mode) {
            SyncMode.WebDav -> state.settings.syncConfiguration.webDavSyncBlockingMessage()
            SyncMode.SelfHosted -> if (state.settings.syncConfiguration.selfHostedSession.loggedIn) {
                null
            } else {
                uiStrings.signInBeforeSync
            }
            SyncMode.Off -> uiStrings.chooseWebdavOrSelf
        }

    private fun SyncConfiguration.matchesWebDavConnection(input: WebDavConnectionInput): Boolean =
        webDavEndpoint == input.endpoint &&
            webDavUsername == input.username &&
            normalizeWebDavAppDirectory(webDavAppDirectory) == input.appDirectory

    private suspend fun persist(
        updated: ClientSettings,
        successMessage: String,
    ): Boolean =
        runCatching {
            withContext(backgroundDispatcher) { persistSettings(updated) }
        }.fold(
            onSuccess = { persisted ->
                state = buildState(
                    settings = persisted,
                    exportSummary = state.exportSummary,
                    feedbackMessage = successMessage,
                    manualSyncProgress = if (persisted.syncConfiguration.mode == state.manualSyncProgress.mode) {
                        state.manualSyncProgress
                    } else {
                        ManualSyncProgress.idle(persisted.syncConfiguration.mode)
                    },
                )
                true
            },
            onFailure = { failure ->
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = formatUiString(uiStrings.settingsSaveFailed, failure.message ?: uiStrings.unknownError),
                    manualSyncProgress = state.manualSyncProgress,
                )
                false
            },
        )

    private fun buildState(
        settings: ClientSettings,
        exportSummary: SettingsExportSummary? = null,
        feedbackMessage: String? = null,
        feedbackEventId: Long? = null,
        manualSyncProgress: ManualSyncProgress = ManualSyncProgress.idle(settings.syncConfiguration.mode),
    ): SettingsUiState =
        SettingsUiState(
            settings = settings,
            sections = requiredSettingsSections(settings),
            defaultNotebookOptions = notebooksProvider().map { notebook ->
                DefaultNotebookOption(
                    id = notebook.id,
                    title = notebook.title,
                    selected = notebook.id == settings.defaultNotebookId,
                )
            },
            exportSummary = exportSummary,
            importSummary = currentImportSummary,
            importRunning = importRunning,
            feedbackMessage = feedbackMessage,
            feedbackEventId = when {
                feedbackMessage == null -> 0L
                feedbackEventId != null -> feedbackEventId
                else -> ++nextFeedbackEventId
            },
            manualSyncProgress = manualSyncProgress,
            webDavCredentialSaved = webDavCredentialSaved,
            webDavBackupVersions = webDavBackupVersions,
            workspacePairingInvitation = currentWorkspacePairingInvitation
                ?.takeIf { it.expiresAtEpochMillis > currentEpochMillis() },
            webDavDiscoveredDevices = webDavDiscoveredDevices,
        )
}

private val ManualSyncResult.hasVisibleSyncChanges: Boolean
    get() = pushedObjects > 0 || pulledObjects > 0 || conflicts > 0

data class SettingsUiState(
    val settings: ClientSettings,
    val sections: List<SettingsSectionUi>,
    val defaultNotebookOptions: List<DefaultNotebookOption>,
    val exportSummary: SettingsExportSummary? = null,
    val importSummary: SettingsImportSummary? = null,
    val importRunning: Boolean = false,
    val feedbackMessage: String? = null,
    val feedbackEventId: Long = 0L,
    val manualSyncProgress: ManualSyncProgress = ManualSyncProgress.idle(settings.syncConfiguration.mode),
    val webDavCredentialSaved: Boolean = false,
    val webDavBackupVersions: List<WebDavBackupVersion> = emptyList(),
    val workspacePairingInvitation: WorkspacePairingInvitationUi? = null,
    val webDavDiscoveredDevices: List<WebDavDiscoveredDevice> = emptyList(),
) {
    val selectedDefaultNotebookTitle: String? =
        defaultNotebookOptions.firstOrNull { it.selected }?.title
}

class WorkspacePairingInvitationUi(
    private val invitation: WorkspacePairingInvitation,
) {
    val manualToken: String get() = invitation.revealManualToken()
    val qrPayload: String get() = invitation.revealQrPayload()
    val expiresAtEpochMillis: Long get() = invitation.expiresAtEpochMillis

    internal fun domainInvitation(): WorkspacePairingInvitation = invitation

    override fun toString(): String =
        "WorkspacePairingInvitationUi(expiresAtEpochMillis=$expiresAtEpochMillis, token=<redacted>)"
}

data class SettingsSectionUi(
    val title: String,
    val description: String,
    val entryPoints: List<String>,
)

data class DefaultNotebookOption(
    val id: String,
    val title: String,
    val selected: Boolean,
)

data class SettingsExportSummary(
    val formatName: String,
    val notebookCount: Int,
    val noteCount: Int,
    val excludedSensitiveFields: List<String>,
    val destinationLabel: String? = null,
) {
    companion object {
        val defaultExcludedSensitiveFields: List<String> = listOf(
            "raw workspace keys",
            "refresh tokens",
            "passwords",
            "recovery material",
            "secure storage aliases",
            "credential secrets",
            "device workspace key metadata",
            "sync account sessions",
        )

        fun unavailable(): SettingsExportSummary =
            SettingsExportSummary(
                formatName = "someday.local-export.v2+json",
                notebookCount = 0,
                noteCount = 0,
                excludedSensitiveFields = defaultExcludedSensitiveFields,
            )
    }
}

fun interface DayOneImportRunner {
    fun start(onResult: (SettingsImportSummary) -> Unit)
}

data class SettingsImportSummary(
    val sourceName: String,
    val success: Boolean,
    val message: String,
    val journalsImported: Int = 0,
    val notebooksCreated: Int = 0,
    val notebooksReused: Int = 0,
    val notesCreated: Int = 0,
    val notesUpdated: Int = 0,
    val notesSkipped: Int = 0,
    val richTextConverted: Int = 0,
    val mediaReferenced: Int = 0,
    val unsupportedItems: Int = 0,
) {
    val notesImported: Int = notesCreated + notesUpdated

    companion object {
        fun unavailable(message: String): SettingsImportSummary =
            failure(sourceName = "Day One", message = message)

        fun failure(
            sourceName: String,
            message: String,
        ): SettingsImportSummary =
            SettingsImportSummary(
                sourceName = sourceName,
                success = false,
                message = message,
            )
    }
}

enum class AppliedTheme {
    Light,
    Dark,
}

fun resolveAppliedTheme(
    theme: ClientTheme,
    systemDark: Boolean,
): AppliedTheme =
    when (theme) {
        ClientTheme.System -> if (systemDark) AppliedTheme.Dark else AppliedTheme.Light
        ClientTheme.Light -> AppliedTheme.Light
        ClientTheme.Dark -> AppliedTheme.Dark
    }

fun settingsCapabilityLog(): String =
    "settings-sections=sync-mode-account|webdav-config|self-hosted-device-management|device-pairing|" +
        "editor-preferences|theme-default-notebook|sync-status-last-error|import-export-entry-points " +
        "self-hosted=endpoint|login-register|device-session|manual-sync-progress|tokens-redacted " +
        "workspace-pairing=one-use-invitation|qr-or-token|redacted-logs " +
        "theme=system|light|dark default-notebook=add-target-unless-overridden " +
        "import=day-one-json-zip export=notes-notebooks|excludes-raw-keys-tokens-passwords-recovery-material"

private fun requiredSettingsSections(settings: ClientSettings): List<SettingsSectionUi> {
    val selfHostedSession = settings.syncConfiguration.selfHostedSession
    return listOf(
        SettingsSectionUi(
            title = "Sync mode/account",
            description = "Choose Off, WebDAV, or Self-hosted sync without blocking local-first note editing.",
            entryPoints = listOf(
                "Current mode: ${settings.syncConfiguration.mode.name}",
                "Account/session entry point",
            ),
        ),
        SettingsSectionUi(
            title = "WebDAV config",
            description = "Configure WebDAV Sync V2 and disaster recovery backup without showing credentials.",
            entryPoints = listOf(
                "Endpoint: ${settings.syncConfiguration.webDavEndpoint ?: "not configured"}",
                "Username: ${settings.syncConfiguration.webDavUsername ?: "anonymous"}",
                "App-owned path: ${normalizeWebDavAppDirectory(settings.syncConfiguration.webDavAppDirectory)}",
                "Manual WebDAV Sync V2 trigger",
                "Disaster recovery backup and restore versions",
                "Test WebDAV connection",
                "Credentials: redacted and stored in local encrypted credential storage",
                "WebDAV readiness: ${settings.syncConfiguration.webDavLastTest?.message ?: "not tested"}",
            ),
        ),
        SettingsSectionUi(
            title = "Self-hosted device management",
            description = "Configure self-hosted endpoint, account session, current device identity, and manual sync without plaintext note data.",
            entryPoints = listOf(
                "Endpoint: ${settings.syncConfiguration.selfHostedEndpoint ?: "not configured"}",
                if (selfHostedSession.loggedIn && selfHostedSession.userEmail != null) {
                    "Logged in as ${selfHostedSession.userEmail}"
                } else {
                    "Logged out"
                },
                "Active self-hosted device: ${selfHostedSession.deviceId ?: settings.activeDeviceId}",
                "Device label: ${selfHostedSession.deviceLabel}",
                "Register or log in and register this device",
                "Device management entry",
                "Manual sync now with progress and error state",
                "Plaintext note data: not shown",
            ),
        ),
        SettingsSectionUi(
            title = "Device pairing",
            description = "Pair through the selected WebDAV or Self-hosted profile without exposing workspace secrets to the remote.",
            entryPoints = listOf(
                "Create a one-use workspace pairing invitation",
                "Join a workspace with a QR scan or high-entropy token before Sync V2",
                "Pairing messages must not log raw tokens",
            ),
        ),
        SettingsSectionUi(
            title = "Editor preferences",
            description = "Control Markdown preview and toolbar assistance defaults.",
            entryPoints = listOf(
                "Preview by default: ${settings.editorPreferences.previewByDefault}",
                "Markdown toolbar visible: ${settings.editorPreferences.markdownToolbarVisible}",
            ),
        ),
        SettingsSectionUi(
            title = "Theme/default notebook",
            description = "Theme applies across primary tabs; default notebook controls the persistent add-note target unless a notebook is selected.",
            entryPoints = listOf(
                "Theme: ${settings.theme.name}",
                "Default notebook: ${settings.defaultNotebookId ?: "selected notebook context"}",
            ),
        ),
        SettingsSectionUi(
            title = "Sync status/last error",
            description = "Show sync readiness, active mode, manual sync progress, and the most recent issue.",
            entryPoints = listOf(
                "Current mode: ${settings.syncConfiguration.mode.name}",
                "Manual sync trigger available for WebDAV and Self-hosted modes",
                "Last error: ${settings.syncConfiguration.lastError ?: "none"}",
            ),
        ),
        SettingsSectionUi(
            title = "Import external journals",
            description = "Import notes from supported journal apps while preserving source dates, time zones, and supported location metadata.",
            entryPoints = listOf(
                "Day One JSON zip import",
                "Rich text converted to Markdown",
                "Unsupported media remains as references",
            ),
        ),
        SettingsSectionUi(
            title = "Export local data",
            description = "Export local notes and notebooks in someday.local-export.v2+json while excluding raw keys, refresh tokens, passwords, and recovery material.",
            entryPoints = listOf(
                "Export local data",
                "Secrets excluded by default",
            ),
        ),
    )
}
