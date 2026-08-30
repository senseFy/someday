@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import saien.someday.domain.notes.NotebookSummary
import saien.someday.domain.notifications.OnThisDayNotificationScheduler
import saien.someday.domain.notifications.UnavailableOnThisDayNotificationScheduler
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SelfHostedConnectionSwitcher
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SelfHostedSetupReason
import saien.someday.domain.settings.SelfHostedSetupResult
import saien.someday.domain.settings.SelfHostedSetupValidationIssue
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.UnavailableSelfHostedSessionCredentialStore
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspacePreferencesConflictResolver
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
import saien.someday.domain.settings.resetBoundWorkspaceForConnectionSwitch
import saien.someday.domain.settings.resetUnboundSelfHostedConnection
import saien.someday.domain.settings.resetWorkspaceStateForReplacement
import saien.someday.ui.i18n.SettingsUiStrings
import saien.someday.ui.i18n.formatUiString

data class OnThisDayNotificationStrings(
    val unavailable: String = "On This Day notifications are not available on this platform.",
    val permissionRequired: String = "Notification permission is required to enable On This Day reminders.",
    val enabled: String = "On This Day notifications enabled.",
    val disabled: String = "On This Day notifications disabled.",
    val invalidTime: String = "Choose a valid notification time.",
    val timeUpdated: String = "On This Day notification time updated.",
)

enum class SettingsFeedbackSeverity {
    Info,
    Success,
    Warning,
    Error,
}

class SettingsUiController(
    initialSettings: ClientSettings = ClientSettings(),
    private val notebooksProvider: () -> List<NotebookSummary> = { emptyList() },
    private val loadSettings: () -> ClientSettings,
    private val persistSettings: (ClientSettings) -> ClientSettings = { it },
    private val workspacePreferencesConflictResolver: WorkspacePreferencesConflictResolver? = null,
    private val exportProvider: () -> SettingsExportSummary = { SettingsExportSummary.unavailable() },
    private val dayOneImportRunner: DayOneImportRunner = DayOneImportRunner { onResult ->
        onResult(SettingsImportSummary.unavailable("Day One import is unavailable in this build."))
    },
    private val onDataRestored: () -> Unit = {},
    private val selfHostedSetupClient: SelfHostedSetupClient = SelfHostedSetupClient {
        SelfHostedSetupResult.failure(SelfHostedSetupReason.Unavailable)
    },
    private val selfHostedSessionCredentialStore: SelfHostedSessionCredentialStore =
        UnavailableSelfHostedSessionCredentialStore,
    private val selfHostedConnectionSwitcher: SelfHostedConnectionSwitcher =
        SelfHostedConnectionSwitcher { saien.someday.domain.settings.SelfHostedConnectionSwitchResult.failure() },
    selfHostedDeviceName: String = "Someday device",
    private val selfHostedDevicePlatform: String = "shared",
    private val manualSyncRunner: ManualSyncRunner = ManualSyncRunner {
        ManualSyncResult.failure(
            mode = SyncMode.Off,
            reason = ManualSyncReason.Unavailable,
        )
    },
    private val automaticSyncEligible: () -> Boolean = { false },
    private val workspacePairingInvitationCreator: WorkspacePairingInvitationCreator =
        WorkspacePairingInvitationCreator {
            WorkspacePairingInvitationResult.failure(WorkspacePairingReason.Unavailable)
        },
    private val workspacePairingInvitationJoiner: WorkspacePairingInvitationJoiner =
        WorkspacePairingInvitationJoiner { _, _ ->
            WorkspaceJoinResult.failure(WorkspacePairingReason.Unavailable)
        },
    private val workspacePairingInvitationCanceller: WorkspacePairingInvitationCanceller =
        WorkspacePairingInvitationCanceller {
            WorkspaceJoinResult.failure(WorkspacePairingReason.Unavailable)
        },
    private val workspaceRecoveryManager: WorkspaceRecoveryManager? = null,
    private val onThisDayNotificationScheduler: OnThisDayNotificationScheduler =
        UnavailableOnThisDayNotificationScheduler,
    onThisDayNotificationStrings: OnThisDayNotificationStrings = OnThisDayNotificationStrings(),
    uiStrings: SettingsUiStrings = SettingsUiStrings(),
    private val currentEpochMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val onThisDayNotificationsSupported: Boolean = onThisDayNotificationScheduler.isSupported
    private var onThisDayNotificationStrings = onThisDayNotificationStrings
    private var uiStrings = uiStrings
    private var selfHostedDeviceName = selfHostedDeviceName

    private var currentWorkspacePairingInvitation: WorkspacePairingInvitationUi? = null
    private var currentWorkspaceRecovery = WorkspaceRecoveryUiState(
        availability = if (workspaceRecoveryManager == null) {
            WorkspaceRecoveryUiAvailability.NotConfigured
        } else {
            WorkspaceRecoveryUiAvailability.Unknown
        },
        syncGate = if (workspaceRecoveryManager == null) {
            WorkspaceRecoverySyncGate.Allowed
        } else {
            WorkspaceRecoverySyncGate.Pending
        },
    )
    private var currentSyncOperation: SyncUiOperation? = null
    /**
     * Serializes every read-modify-write of [ClientSettings] with sync and pairing.
     * The persistence callback stores the whole immutable settings value, so a
     * narrower "sync-only" lock would allow unrelated preference writes to
     * silently restore stale session metadata.
     */
    private val settingsMutationMutex = Mutex()
    private var secureSessionAccess: SecureSessionAccess = SecureSessionAccess.Unknown
    private var currentSyncIssue: SyncIssueUi? =
        syncIssueFromLastError(initialSettings.syncConfiguration.lastError)
    private var currentImportSummary: SettingsImportSummary? = null
    private var importRunning: Boolean = false
    private var nextFeedbackEventId = 0L

    var state: SettingsUiState by mutableStateOf(buildState(settings = initialSettings))
        private set

    fun updateLocalizedStrings(
        settings: SettingsUiStrings,
        notifications: OnThisDayNotificationStrings,
        hostDeviceName: String? = null,
    ) {
        uiStrings = settings
        onThisDayNotificationStrings = notifications
        if (hostDeviceName != null) {
            selfHostedDeviceName = hostDeviceName
        }
    }

    suspend fun refresh() = withSettingsMutation { refreshLocked() }

    suspend fun retryWorkspaceRecoveryStatus(): Boolean =
        runExclusiveSyncLifecycle(false) {
            if (workspaceRecoveryManager == null || currentSyncOperation != null) {
                return@runExclusiveSyncLifecycle false
            }
            currentSyncOperation = SyncUiOperation.CheckingRecovery
            publishCurrentState()
            try {
                refreshWorkspaceRecoveryStatusLocked(showFeedback = true)
            } finally {
                currentSyncOperation = null
                publishCurrentState()
            }
        }

    private suspend fun refreshLocked() {
        val previousSettings = state.settings
        val previousConfiguration = previousSettings.syncConfiguration
        val credentialsResult = runCatching {
            withContext(backgroundDispatcher) { selfHostedSessionCredentialStore.load() }
        }
        val reconciledSettings = credentialsResult.fold(
            onSuccess = { credentials ->
                val recovered = previousSettings.copy(
                    activeDeviceId = credentials?.deviceId ?: previousSettings.activeDeviceId,
                    syncConfiguration = previousConfiguration.copy(
                        mode = if (credentials != null) SyncMode.SelfHosted else previousConfiguration.mode,
                        selfHostedEndpoint = credentials?.endpoint ?: previousConfiguration.selfHostedEndpoint,
                        selfHostedSession = credentials?.toSummary() ?: SelfHostedSessionSummary(),
                    ),
                )
                val persistenceResult = if (recovered == previousSettings) {
                    Result.success(recovered)
                } else {
                    runCatching {
                        withContext(backgroundDispatcher) { persistSettings(recovered) }
                    }
                }
                persistenceResult.fold(
                    onSuccess = { persisted ->
                        secureSessionAccess = if (credentials == null) {
                            SecureSessionAccess.Missing
                        } else {
                            SecureSessionAccess.Available
                        }
                        when {
                            credentials == null &&
                                (
                                    previousConfiguration.selfHostedSession.loggedIn ||
                                        !previousConfiguration.selfHostedEndpoint.isNullOrBlank()
                                ) -> {
                                currentSyncIssue = SyncIssueUi(SyncIssueReason.SignInRequired)
                            }
                            credentials != null && currentSyncIssue?.reason in setOf(
                                SyncIssueReason.SignInRequired,
                                SyncIssueReason.SecureSessionUnavailable,
                            ) -> currentSyncIssue = null
                            credentials == null &&
                                currentSyncIssue?.reason == SyncIssueReason.SecureSessionUnavailable -> {
                                currentSyncIssue = null
                            }
                        }
                        persisted
                    },
                    onFailure = { failure ->
                        failure.rethrowCancellation()
                        secureSessionAccess = if (credentials == null) {
                            SecureSessionAccess.Missing
                        } else {
                            SecureSessionAccess.Unavailable
                        }
                        currentSyncIssue = SyncIssueUi(
                            if (credentials == null) {
                                SyncIssueReason.SignInRequired
                            } else {
                                SyncIssueReason.SecureSessionUnavailable
                            },
                        )
                        previousSettings
                    },
                )
            },
            onFailure = { failure ->
                failure.rethrowCancellation()
                secureSessionAccess = SecureSessionAccess.Unavailable
                currentSyncIssue = SyncIssueUi(SyncIssueReason.SecureSessionUnavailable)
                previousSettings
            },
        )
        state = buildState(
            settings = reconciledSettings,
            exportSummary = state.exportSummary,
            feedbackMessage = state.feedbackMessage,
            feedbackSeverity = state.feedbackSeverity,
            feedbackEventId = state.feedbackEventId,
        )
        if (state.sync.connection is SyncConnectionUi.Connected) {
            refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
        }
        rescheduleOnThisDayNotifications()
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
                feedbackSeverity = SettingsFeedbackSeverity.Error,
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
                    feedbackSeverity = SettingsFeedbackSeverity.Warning,
                )
                return false
            }
        }
        return withSettingsMutation {
            val updatedPreferences = state.settings.onThisDayNotifications.copy(enabled = enabled)
            val persisted = persistLocked(
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
            persisted
        }
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
                feedbackSeverity = SettingsFeedbackSeverity.Error,
            )
            return false
        }
        return withSettingsMutation {
            val updatedPreferences = state.settings.onThisDayNotifications.copy(
                hour = hour,
                minute = minute,
            )
            val persisted = persistLocked(
                updated = state.settings.copy(onThisDayNotifications = updatedPreferences),
                successMessage = onThisDayNotificationStrings.timeUpdated,
            )
            if (persisted) {
                syncOnThisDayNotificationSchedule(updatedPreferences)
            }
            persisted
        }
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
        withSettingsMutation {
            persistLocked(
                updated = state.settings.copy(theme = theme),
                successMessage = uiStrings.themeUpdated,
            )
        }

    /**
     * Device-local language override. Always editable, including during workspace
     * preferences conflicts, because it is not workspace-synced.
     */
    suspend fun selectLanguage(language: AppLanguage): Boolean =
        withSettingsMutation {
            persistLocked(
                updated = state.settings.copy(appLanguage = language),
                successMessage = uiStrings.languageUpdated,
            )
        }

    suspend fun togglePreviewByDefault(enabled: Boolean): Boolean =
        withSettingsMutation {
            persistLocked(
                updated = state.settings.copy(
                    editorPreferences = state.settings.editorPreferences.copy(previewByDefault = enabled),
                ),
                successMessage = uiStrings.previewUpdated,
            )
        }

    suspend fun toggleMarkdownToolbarVisible(enabled: Boolean): Boolean =
        withSettingsMutation {
            persistLocked(
                updated = state.settings.copy(
                    editorPreferences = state.settings.editorPreferences.copy(markdownToolbarVisible = enabled),
                ),
                successMessage = uiStrings.toolbarUpdated,
            )
        }

    suspend fun selectDefaultNotebook(notebookId: String?): Boolean =
        withSettingsMutation {
            val validNotebookId = notebookId?.takeIf { candidate ->
                state.defaultNotebookOptions.any { it.id == candidate }
            }
            if (notebookId != null && validNotebookId == null) {
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = uiStrings.missingNotebook,
                    feedbackSeverity = SettingsFeedbackSeverity.Error,
                )
                false
            } else {
                persistLocked(
                    updated = state.settings.copy(defaultNotebookId = validNotebookId),
                    successMessage = if (validNotebookId == null) {
                        uiStrings.defaultNotebookCleared
                    } else {
                        uiStrings.defaultNotebookUpdated
                    },
                )
            }
        }

    suspend fun resolveWorkspacePreferencesBranch(versionId: String): Boolean =
        withSettingsMutation {
            val conflict = state.settings.workspacePreferencesState.conflict ?: return@withSettingsMutation false
            val resolver = workspacePreferencesConflictResolver
            if (resolver == null) {
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = uiStrings.prefsConflictUnavailable,
                    feedbackSeverity = SettingsFeedbackSeverity.Error,
                )
                false
            } else {
                runCatching {
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
                            feedbackSeverity = SettingsFeedbackSeverity.Success,
                        )
                        true
                    },
                    onFailure = { failure ->
                        failure.rethrowCancellation()
                        state = buildState(
                            settings = state.settings,
                            exportSummary = state.exportSummary,
                            feedbackMessage = formatUiString(
                                uiStrings.cannotResolvePrefs,
                                failure.message ?: uiStrings.unknownError,
                            ),
                            feedbackSeverity = SettingsFeedbackSeverity.Error,
                        )
                        false
                    },
                )
            }
        }

    suspend fun recordLastSelectedNotebook(notebookId: String): Boolean =
        withSettingsMutation {
            if (state.settings.lastSelectedNotebookId == notebookId) {
                return@withSettingsMutation true
            }
            val validNotebookId = notebookId.takeIf { candidate ->
                notebooksProvider().any { it.id == candidate }
            } ?: return@withSettingsMutation false

            runCatching {
                withContext(backgroundDispatcher) {
                    persistSettings(state.settings.copy(lastSelectedNotebookId = validNotebookId))
                }
            }.fold(
                onSuccess = { persisted ->
                    state = buildState(
                        settings = persisted,
                        exportSummary = state.exportSummary,
                        feedbackMessage = state.feedbackMessage,
                        feedbackSeverity = state.feedbackSeverity,
                        feedbackEventId = state.feedbackEventId,
                    )
                    true
                },
                onFailure = { failure ->
                    failure.rethrowCancellation()
                    false
                },
            )
        }

    suspend fun setupSelfHosted(
        endpoint: String,
        email: String,
        password: String,
        createAccount: Boolean,
    ): Boolean = runExclusiveSyncLifecycle(false) {
        setupSelfHostedLocked(endpoint, email, password, createAccount)
    }

    suspend fun switchSelfHostedConnection(): Boolean {
        val completion = runExclusiveSyncLifecycle(ConnectionSwitchCompletion()) {
            switchSelfHostedConnectionLocked()
        }
        if (completion.refreshProductData) {
            onDataRestored()
        }
        return completion.switched
    }

    private suspend fun switchSelfHostedConnectionLocked(): ConnectionSwitchCompletion {
        if (currentSyncOperation != null) return ConnectionSwitchCompletion()
        currentSyncOperation = SyncUiOperation.SwitchingConnection
        publishCurrentState()
        return try {
            val result = try {
                withContext(backgroundDispatcher) { selfHostedConnectionSwitcher.switchConnection() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                saien.someday.domain.settings.SelfHostedConnectionSwitchResult.failure()
            }
            if (!result.success) {
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = uiStrings.selfHostedConnectionSwitchFailed,
                    feedbackSeverity = SettingsFeedbackSeverity.Error,
                )
                return ConnectionSwitchCompletion()
            }

            currentWorkspacePairingInvitation = null
            discardPendingWorkspaceRecoveryCodeLocked()
            currentWorkspaceRecovery = WorkspaceRecoveryUiState(
                availability = if (workspaceRecoveryManager == null) {
                    WorkspaceRecoveryUiAvailability.NotConfigured
                } else {
                    WorkspaceRecoveryUiAvailability.Unknown
                },
                syncGate = if (workspaceRecoveryManager == null) {
                    WorkspaceRecoverySyncGate.Allowed
                } else {
                    WorkspaceRecoverySyncGate.Pending
                },
            )
            currentSyncIssue = null
            secureSessionAccess = SecureSessionAccess.Missing
            val fallback = if (result.workspaceReplaced) {
                state.settings.resetBoundWorkspaceForConnectionSwitch()
            } else {
                state.settings.resetUnboundSelfHostedConnection()
            }
            val resetSettings = runCatching {
                withContext(backgroundDispatcher) { loadSettings() }
            }.getOrElse { failure ->
                failure.rethrowCancellation()
                fallback
            }
            state = buildState(
                settings = resetSettings,
                exportSummary = state.exportSummary,
                feedbackMessage = uiStrings.selfHostedConnectionSwitchReady,
                feedbackSeverity = SettingsFeedbackSeverity.Success,
            )
            ConnectionSwitchCompletion(
                switched = true,
                refreshProductData = result.workspaceReplaced,
            )
        } finally {
            currentSyncOperation = null
            publishCurrentState()
        }
    }

    private suspend fun setupSelfHostedLocked(
        endpoint: String,
        email: String,
        password: String,
        createAccount: Boolean,
    ): Boolean {
        val currentSession = state.settings.syncConfiguration.selfHostedSession
        val sanitized = SelfHostedSetupInput(
            endpoint = endpoint,
            email = email,
            password = password,
            deviceName = currentSession.deviceName ?: hostDeviceLabel(),
            platform = currentSession.devicePlatform ?: selfHostedDevicePlatform,
            createAccount = createAccount,
        ).sanitized()
        val validationErrors = sanitized.validate()
        if (validationErrors.isNotEmpty()) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = validationErrors.joinToString(separator = " ", transform = ::selfHostedValidationMessage),
                feedbackSeverity = SettingsFeedbackSeverity.Error,
            )
            return false
        }

        if (currentSyncOperation != null) return false
        currentSyncOperation = if (createAccount) {
            SyncUiOperation.CreatingAccount
        } else {
            SyncUiOperation.Authenticating
        }
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = state.feedbackMessage,
            feedbackSeverity = state.feedbackSeverity,
            feedbackEventId = state.feedbackEventId,
        )

        return try {
            val result = try {
                withContext(backgroundDispatcher) { selfHostedSetupClient.setup(sanitized) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                SelfHostedSetupResult.failure(
                    reason = SelfHostedSetupReason.Failed,
                    diagnosticMessage = failure.message,
                )
            }
            val displayMessage = selfHostedSetupMessage(result.status.reason)
            if (!result.success || result.session == null) {
                val rejectedReplacement = currentSession.loggedIn &&
                    result.status.reason in setOf(
                        SelfHostedSetupReason.AccountChangeBlocked,
                        SelfHostedSetupReason.EndpointMismatch,
                    )
                if (!rejectedReplacement) {
                    currentSyncIssue = SyncIssueUi(SyncIssueReason.SetupFailed)
                }
                // Failed account/device replacement must not damage the previously
                // bound endpoint or its usable session summary.
                val preserved = state.settings.copy(
                    syncConfiguration = state.settings.syncConfiguration.copy(
                        lastError = if (rejectedReplacement) {
                            state.settings.syncConfiguration.lastError
                        } else {
                            "setup:${result.status.reason.name}"
                        },
                    ),
                )
                persistLocked(
                    updated = preserved,
                    successMessage = displayMessage,
                    successSeverity = SettingsFeedbackSeverity.Error,
                )
                false
            } else {
                val session = checkNotNull(result.session)
                secureSessionAccess = SecureSessionAccess.Available
                currentSyncIssue = null
                val updatedSettings = state.settings.copy(
                    activeDeviceId = session.deviceId ?: state.settings.activeDeviceId,
                    syncConfiguration = state.settings.syncConfiguration.copy(
                        mode = SyncMode.SelfHosted,
                        selfHostedEndpoint = sanitized.endpoint,
                        selfHostedSession = session,
                        lastError = null,
                    ),
                )
                val persisted = persistLocked(
                    updated = updatedSettings,
                    successMessage = displayMessage,
                )
                if (!persisted) {
                    secureSessionAccess = SecureSessionAccess.Unavailable
                    currentSyncIssue = SyncIssueUi(SyncIssueReason.SecureSessionUnavailable)
                    publishCurrentState()
                } else {
                    refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
                }
                persisted
            }
        } finally {
            currentSyncOperation = null
            publishCurrentState()
        }
    }

    fun canRunAutomaticSync(): Boolean =
        !settingsMutationMutex.isLocked && canStartSync()

    private fun canStartSync(): Boolean =
        currentSyncOperation == null &&
            state.sync.connection is SyncConnectionUi.Connected &&
            !currentWorkspaceRecovery.blocksSync &&
            (currentSyncIssue == null || currentSyncIssue?.action == SyncIssueAction.RetrySync)

    private fun canUseWorkspacePairing(): Boolean =
        state.sync.pairingAvailable

    private suspend fun refreshWorkspaceRecoveryStatusLocked(showFeedback: Boolean): Boolean {
        val manager = workspaceRecoveryManager ?: return true
        val result = try {
            withContext(backgroundDispatcher) { manager.status() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            WorkspaceRecoveryStatusResult.failure(WorkspaceRecoveryReason.Failed)
        }
        currentWorkspaceRecovery = currentWorkspaceRecovery.copy(
            availability = when {
                !result.success -> WorkspaceRecoveryUiAvailability.Unavailable
                result.state == WorkspaceRecoveryState.NotConfigured ->
                    WorkspaceRecoveryUiAvailability.NotConfigured
                result.state == WorkspaceRecoveryState.Configured ->
                    WorkspaceRecoveryUiAvailability.Configured
                result.state == WorkspaceRecoveryState.RecoveryAvailable ->
                    WorkspaceRecoveryUiAvailability.RecoveryAvailable
                else -> WorkspaceRecoveryUiAvailability.Unavailable
            },
            syncGate = result.syncGate,
        )
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = if (showFeedback) workspaceRecoveryMessage(result.reason) else state.feedbackMessage,
            feedbackSeverity = if (!showFeedback) {
                state.feedbackSeverity
            } else if (result.success) {
                SettingsFeedbackSeverity.Info
            } else {
                SettingsFeedbackSeverity.Error
            },
            feedbackEventId = if (showFeedback) null else state.feedbackEventId,
        )
        return result.success
    }

    private suspend fun discardPendingWorkspaceRecoveryCodeLocked() {
        runCatching {
            withContext(backgroundDispatcher) { workspaceRecoveryManager?.discardPreparedCode() }
        }.exceptionOrNull()?.rethrowCancellation()
        currentWorkspaceRecovery = currentWorkspaceRecovery.copy(preparedCode = null)
    }

    private fun beginSync(showFeedback: Boolean): Boolean {
        if (currentSyncOperation != null) return false
        val connected = state.sync.connection is SyncConnectionUi.Connected
        val blockingIssue = currentSyncIssue
            ?.takeUnless { it.action == SyncIssueAction.RetrySync }
        val blockingMessage = when {
            !connected -> uiStrings.signInBeforeSync
            currentWorkspaceRecovery.availability == WorkspaceRecoveryUiAvailability.RecoveryAvailable ->
                uiStrings.recoveryRequiredBeforeSync
            currentWorkspaceRecovery.blocksSync -> uiStrings.recoveryStatusUnavailable
            blockingIssue != null -> syncIssueMessage(blockingIssue.reason)
            else -> null
        }
        if (blockingMessage != null) {
            if (!connected && currentSyncIssue == null) {
                currentSyncIssue = SyncIssueUi(reason = SyncIssueReason.SignInRequired)
            }
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = if (showFeedback) blockingMessage else state.feedbackMessage,
                feedbackSeverity = if (showFeedback) SettingsFeedbackSeverity.Error else state.feedbackSeverity,
                feedbackEventId = if (showFeedback) null else state.feedbackEventId,
            )
            return false
        }
        currentSyncOperation = SyncUiOperation.Syncing
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = if (showFeedback) uiStrings.syncStarted else state.feedbackMessage,
            feedbackSeverity = if (showFeedback) SettingsFeedbackSeverity.Info else state.feedbackSeverity,
            feedbackEventId = if (showFeedback) null else state.feedbackEventId,
        )
        return true
    }

    private suspend fun completeSync(
        result: ManualSyncResult,
        showFeedback: Boolean,
    ): Boolean {
        val displayMessage = syncResultMessage(result)
        if (result.reason == ManualSyncReason.AlreadyRunning) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = if (showFeedback) displayMessage else state.feedbackMessage,
                feedbackSeverity = if (showFeedback) SettingsFeedbackSeverity.Info else state.feedbackSeverity,
                feedbackEventId = if (showFeedback) null else state.feedbackEventId,
            )
            return false
        }
        currentSyncIssue = if (result.success) {
            null
        } else {
            SyncIssueUi(
                reason = syncIssueReason(result.reason),
            )
        }
        val updatedSettings = state.settings.copy(
            syncConfiguration = state.settings.syncConfiguration.copy(
                lastError = if (result.success) null else "sync:${result.reason.name}",
            ),
        )
        val persistenceResult = runCatching {
            withContext(backgroundDispatcher) { persistSettings(updatedSettings) }
        }
        persistenceResult.exceptionOrNull()?.rethrowCancellation()
        val persistenceFailure = persistenceResult.exceptionOrNull()
        if (persistenceFailure != null && result.success) {
            currentSyncIssue = SyncIssueUi(SyncIssueReason.SyncFailed)
        }
        state = buildState(
            settings = persistenceResult.getOrNull() ?: state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = when {
                persistenceFailure != null -> formatUiString(
                    uiStrings.settingsSaveFailed,
                    persistenceFailure.message ?: uiStrings.unknownError,
                )
                showFeedback -> displayMessage
                else -> state.feedbackMessage
            },
            feedbackSeverity = when {
                persistenceFailure != null -> SettingsFeedbackSeverity.Error
                showFeedback && result.success -> SettingsFeedbackSeverity.Success
                showFeedback -> SettingsFeedbackSeverity.Error
                else -> state.feedbackSeverity
            },
            feedbackEventId = if (showFeedback || persistenceFailure != null) null else state.feedbackEventId,
        )
        if (result.reason == ManualSyncReason.RemoteHistoryConflict && workspaceRecoveryManager != null) {
            refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
        }
        return result.success && persistenceFailure == null
    }

    suspend fun runUserSync(): Boolean = runSync(userInitiated = true)

    suspend fun runAutomaticSync(): Boolean = runSync(userInitiated = false)

    suspend fun recoverSyncIssue(): Boolean =
        when (currentSyncIssue?.action) {
            SyncIssueAction.RetrySync -> runUserSync()
            SyncIssueAction.ReloadSession -> {
                if (currentSyncOperation != null) {
                    false
                } else {
                    runExclusiveSyncLifecycle(false) {
                        currentSyncOperation = SyncUiOperation.ReloadingSession
                        publishCurrentState()
                        try {
                            refreshLocked()
                            currentSyncIssue?.reason != SyncIssueReason.SecureSessionUnavailable
                        } finally {
                            currentSyncOperation = null
                            publishCurrentState()
                        }
                    }
                }
            }
            SyncIssueAction.Reauthenticate,
            null,
            -> false
        }

    private suspend fun runSync(userInitiated: Boolean): Boolean {
        val completion = runExclusiveSyncLifecycle(SyncCompletion()) {
            runSyncLocked(userInitiated)
        }
        if (completion.refreshProductData) {
            onDataRestored()
        }
        return completion.success
    }

    private suspend fun runSyncLocked(userInitiated: Boolean): SyncCompletion {
        if (!userInitiated && !canStartSync()) return SyncCompletion()
        if (!userInitiated && !preflightAutomaticSync()) return SyncCompletion()
        if (userInitiated &&
            state.sync.connection is SyncConnectionUi.Connected &&
            currentWorkspaceRecovery.blocksSync &&
            workspaceRecoveryManager != null
        ) {
            refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
        }
        if (!beginSync(showFeedback = userInitiated)) return SyncCompletion()
        return try {
            val result = executeSyncRunner()
            val shouldShowFeedback = userInitiated || !result.success
            SyncCompletion(
                success = completeSync(result, showFeedback = shouldShowFeedback),
                // First-time activation can materialize product data even when
                // a later transport pass reports zero deltas. Partial failures
                // with pulls or conflicts also need a product refresh.
                refreshProductData = result.success || result.hasVisibleSyncChanges,
            )
        } finally {
            if (currentSyncOperation == SyncUiOperation.Syncing) {
                currentSyncOperation = null
                publishCurrentState()
            }
        }
    }

    private suspend fun preflightAutomaticSync(): Boolean =
        runCatching {
            withContext(backgroundDispatcher) { automaticSyncEligible() }
        }.getOrElse { failure ->
            failure.rethrowCancellation()
            false
        }

    private suspend fun executeSyncRunner(): ManualSyncResult {
        val mode = state.settings.syncConfiguration.mode
        return runCatching {
            withContext(backgroundDispatcher) { manualSyncRunner.run() }
        }.getOrElse { failure ->
            failure.rethrowCancellation()
            ManualSyncResult.failure(
                mode = mode,
                reason = ManualSyncReason.Failed,
                diagnosticMessage = failure.message,
            )
        }
    }

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
                    feedbackSeverity = SettingsFeedbackSeverity.Success,
                )
                true
            },
            onFailure = { failure ->
                failure.rethrowCancellation()
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = formatUiString(uiStrings.exportFailed, failure.message ?: uiStrings.unknownError),
                    feedbackSeverity = SettingsFeedbackSeverity.Error,
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
        )
        return runCatching {
            dayOneImportRunner.start { summary ->
                importRunning = false
                currentImportSummary = summary
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = summary.message,
                    feedbackSeverity = if (summary.success) {
                        SettingsFeedbackSeverity.Success
                    } else {
                        SettingsFeedbackSeverity.Error
                    },
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
                    feedbackSeverity = SettingsFeedbackSeverity.Error,
                )
                false
            },
        )
    }

    suspend fun prepareWorkspaceRecoveryCode(): Boolean =
        runExclusiveSyncLifecycle(false) { prepareWorkspaceRecoveryCodeLocked() }

    private suspend fun prepareWorkspaceRecoveryCodeLocked(): Boolean {
        val manager = workspaceRecoveryManager ?: return false
        if (state.sync.connection !is SyncConnectionUi.Connected ||
            currentWorkspaceRecovery.availability !in setOf(
                WorkspaceRecoveryUiAvailability.NotConfigured,
                WorkspaceRecoveryUiAvailability.Configured,
            ) ||
            currentSyncOperation != null
        ) {
            return false
        }
        currentSyncOperation = SyncUiOperation.PreparingRecoveryCode
        publishCurrentState()
        return try {
            val result = try {
                withContext(backgroundDispatcher) { manager.prepareCode() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.Failed)
            }
            val prepared = result.recoveryCode?.let(::WorkspaceRecoveryCodeUi)
            if (result.success && prepared != null) {
                currentWorkspaceRecovery = currentWorkspaceRecovery.copy(preparedCode = prepared)
            }
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = workspaceRecoveryMessage(result.reason),
                feedbackSeverity = if (result.success) SettingsFeedbackSeverity.Warning else SettingsFeedbackSeverity.Error,
            )
            result.success && prepared != null
        } finally {
            currentSyncOperation = null
            publishCurrentState()
        }
    }

    suspend fun confirmWorkspaceRecoveryCode(candidate: String): Boolean =
        runExclusiveSyncLifecycle(false) { confirmWorkspaceRecoveryCodeLocked(candidate) }

    private suspend fun confirmWorkspaceRecoveryCodeLocked(candidate: String): Boolean {
        val manager = workspaceRecoveryManager ?: return false
        if (currentWorkspaceRecovery.preparedCode == null || currentSyncOperation != null) return false
        currentSyncOperation = SyncUiOperation.PublishingRecoveryCode
        publishCurrentState()
        return try {
            val result = try {
                withContext(backgroundDispatcher) { manager.confirmPreparedCode(candidate) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.Failed)
            }
            if (result.success) {
                currentWorkspaceRecovery = WorkspaceRecoveryUiState(
                    availability = WorkspaceRecoveryUiAvailability.Configured,
                    syncGate = WorkspaceRecoverySyncGate.Allowed,
                )
            } else if (result.reason in setOf(
                    WorkspaceRecoveryReason.AuthorityMismatch,
                    WorkspaceRecoveryReason.ServerConflict,
                )
            ) {
                currentWorkspaceRecovery = currentWorkspaceRecovery.copy(preparedCode = null)
            }
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = workspaceRecoveryMessage(result.reason),
                feedbackSeverity = if (result.success) SettingsFeedbackSeverity.Success else SettingsFeedbackSeverity.Error,
            )
            result.success
        } finally {
            currentSyncOperation = null
            publishCurrentState()
        }
    }

    suspend fun discardPreparedWorkspaceRecoveryCode() {
        if (workspaceRecoveryManager == null) return
        runExclusiveSyncLifecycle(Unit) {
            if (currentSyncOperation != null) return@runExclusiveSyncLifecycle
            discardPendingWorkspaceRecoveryCodeLocked()
            publishCurrentState()
        }
    }

    suspend fun recoverWorkspaceWithCode(
        recoveryCode: String,
        replaceExistingWorkspace: Boolean,
    ): Boolean {
        val completion = runExclusiveSyncLifecycle(WorkspaceJoinCompletion()) {
            recoverWorkspaceWithCodeLocked(recoveryCode, replaceExistingWorkspace)
        }
        if (completion.refreshProductData) onDataRestored()
        return completion.joined
    }

    private suspend fun recoverWorkspaceWithCodeLocked(
        recoveryCode: String,
        replaceExistingWorkspace: Boolean,
    ): WorkspaceJoinCompletion {
        val manager = workspaceRecoveryManager ?: return WorkspaceJoinCompletion()
        if (recoveryCode.isBlank()) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = uiStrings.recoveryCodeRequired,
                feedbackSeverity = SettingsFeedbackSeverity.Warning,
            )
            return WorkspaceJoinCompletion()
        }
        if (state.sync.connection !is SyncConnectionUi.Connected ||
            currentWorkspaceRecovery.availability != WorkspaceRecoveryUiAvailability.RecoveryAvailable ||
            currentSyncOperation != null
        ) {
            return WorkspaceJoinCompletion()
        }
        currentSyncOperation = SyncUiOperation.RestoringWorkspace
        publishCurrentState()
        return try {
            val result = try {
                withContext(backgroundDispatcher) {
                    manager.recover(recoveryCode, replaceExistingWorkspace)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                WorkspaceRecoveryRestoreResult.failure(WorkspaceRecoveryReason.Failed)
            }
            if (!result.success) {
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = workspaceRecoveryMessage(result.reason),
                    feedbackSeverity = SettingsFeedbackSeverity.Error,
                )
                return WorkspaceJoinCompletion()
            }

            currentWorkspacePairingInvitation = null
            discardPendingWorkspaceRecoveryCodeLocked()
            currentSyncIssue = null
            currentWorkspaceRecovery = WorkspaceRecoveryUiState(
                availability = WorkspaceRecoveryUiAvailability.Configured,
                syncGate = WorkspaceRecoverySyncGate.Allowed,
            )
            val replacementSettings = runCatching {
                withContext(backgroundDispatcher) { loadSettings() }
            }.getOrElse { failure ->
                failure.rethrowCancellation()
                currentSyncIssue = SyncIssueUi(SyncIssueReason.WorkspaceSettingsReloadRequired)
                state = buildState(
                    settings = state.settings.safeWorkspaceReplacementFallback(),
                    exportSummary = state.exportSummary,
                    feedbackMessage = uiStrings.pairingSettingsReloadFailed,
                    feedbackSeverity = SettingsFeedbackSeverity.Error,
                )
                return WorkspaceJoinCompletion(joined = true, refreshProductData = true)
            }
            state = buildState(
                settings = replacementSettings,
                exportSummary = state.exportSummary,
                feedbackMessage = workspaceRecoveryMessage(WorkspaceRecoveryReason.Recovered),
                feedbackSeverity = SettingsFeedbackSeverity.Success,
            )
            refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
            if (currentWorkspaceRecovery.blocksSync) {
                return WorkspaceJoinCompletion(joined = true, refreshProductData = true)
            }
            currentSyncOperation = SyncUiOperation.Syncing
            publishCurrentState()
            val syncResult = executeSyncRunner()
            completeSync(syncResult, showFeedback = !syncResult.success)
            WorkspaceJoinCompletion(joined = true, refreshProductData = true)
        } finally {
            currentSyncOperation = null
            publishCurrentState()
        }
    }

    suspend fun createWorkspacePairingInvitation(): Boolean =
        runExclusiveSyncLifecycle(false) { createWorkspacePairingInvitationLocked() }

    private suspend fun createWorkspacePairingInvitationLocked(): Boolean {
        if (!canUseWorkspacePairing()) return false
        if (currentSyncOperation != null) return false
        currentSyncOperation = SyncUiOperation.CreatingInvitation
        publishCurrentState()
        return try {
            val result = try {
                withContext(backgroundDispatcher) { workspacePairingInvitationCreator.createInvitation() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                WorkspacePairingInvitationResult.failure(WorkspacePairingReason.Failed)
            }
            currentWorkspacePairingInvitation = result.invitation?.let(::WorkspacePairingInvitationUi)
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = workspacePairingMessage(result.reason, invitationOperation = true),
                feedbackSeverity = if (result.success) SettingsFeedbackSeverity.Success else SettingsFeedbackSeverity.Error,
            )
            result.success
        } finally {
            currentSyncOperation = null
            publishCurrentState()
        }
    }

    suspend fun cancelWorkspacePairingInvitation(): Boolean =
        runExclusiveSyncLifecycle(false) { cancelWorkspacePairingInvitationLocked() }

    private suspend fun cancelWorkspacePairingInvitationLocked(): Boolean {
        val invitation = currentWorkspacePairingInvitation?.domainInvitation()
        if (invitation == null) {
            return true
        }
        if (!canUseWorkspacePairing()) return false
        if (currentSyncOperation != null) return false
        currentSyncOperation = SyncUiOperation.CancellingInvitation
        publishCurrentState()
        return try {
            val result = try {
                withContext(backgroundDispatcher) {
                    workspacePairingInvitationCanceller.cancelInvitation(invitation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                WorkspaceJoinResult.failure(WorkspacePairingReason.Failed)
            }
            if (result.success) {
                currentWorkspacePairingInvitation = null
            }
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = workspacePairingMessage(result.reason),
                feedbackSeverity = if (result.success) SettingsFeedbackSeverity.Success else SettingsFeedbackSeverity.Error,
            )
            result.success
        } finally {
            currentSyncOperation = null
            publishCurrentState()
        }
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
            feedbackSeverity = state.feedbackSeverity,
            feedbackEventId = state.feedbackEventId,
        )
    }

    suspend fun joinWorkspaceWithToken(
        tokenInput: String,
        replaceExistingWorkspace: Boolean,
    ): Boolean {
        val completion = runExclusiveSyncLifecycle(WorkspaceJoinCompletion()) {
            joinWorkspaceWithTokenLocked(tokenInput, replaceExistingWorkspace)
        }
        if (completion.refreshProductData) {
            onDataRestored()
        }
        return completion.joined
    }

    private suspend fun joinWorkspaceWithTokenLocked(
        tokenInput: String,
        replaceExistingWorkspace: Boolean,
    ): WorkspaceJoinCompletion {
        if (tokenInput.isBlank()) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = uiStrings.enterPairingToken,
                feedbackSeverity = SettingsFeedbackSeverity.Warning,
            )
            return WorkspaceJoinCompletion()
        }
        if (!canUseWorkspacePairing()) return WorkspaceJoinCompletion()
        if (currentSyncOperation != null) return WorkspaceJoinCompletion()
        currentSyncOperation = SyncUiOperation.JoiningInvitation
        publishCurrentState()
        return try {
            val result = try {
                withContext(backgroundDispatcher) {
                    workspacePairingInvitationJoiner.joinWithToken(
                        tokenInput = tokenInput,
                        replaceExistingWorkspace = replaceExistingWorkspace,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                WorkspaceJoinResult.failure(WorkspacePairingReason.Failed)
            }
            if (result.success) {
                currentWorkspacePairingInvitation = null
                discardPendingWorkspaceRecoveryCodeLocked()
                currentSyncIssue = null
                val replacementSettings = runCatching {
                    withContext(backgroundDispatcher) { loadSettings() }
                }.getOrElse { failure ->
                    failure.rethrowCancellation()
                    currentSyncIssue = SyncIssueUi(SyncIssueReason.WorkspaceSettingsReloadRequired)
                    state = buildState(
                        settings = state.settings.safeWorkspaceReplacementFallback(),
                        exportSummary = state.exportSummary,
                        feedbackMessage = uiStrings.pairingSettingsReloadFailed,
                        feedbackSeverity = SettingsFeedbackSeverity.Error,
                    )
                    return WorkspaceJoinCompletion(
                        joined = true,
                        refreshProductData = true,
                    )
                }
                state = buildState(
                    settings = replacementSettings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = workspacePairingMessage(result.reason),
                    feedbackSeverity = SettingsFeedbackSeverity.Success,
                )
                refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
                if (currentWorkspaceRecovery.blocksSync) {
                    return WorkspaceJoinCompletion(
                        joined = true,
                        refreshProductData = true,
                    )
                }
                currentSyncOperation = SyncUiOperation.Syncing
                publishCurrentState()
                val syncResult = executeSyncRunner()
                completeSync(syncResult, showFeedback = !syncResult.success)
                WorkspaceJoinCompletion(
                    joined = true,
                    refreshProductData = true,
                )
            } else {
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = workspacePairingMessage(result.reason),
                    feedbackSeverity = SettingsFeedbackSeverity.Error,
                )
                WorkspaceJoinCompletion()
            }
        } finally {
            currentSyncOperation = null
            publishCurrentState()
        }
    }

    private fun selfHostedValidationMessage(issue: SelfHostedSetupValidationIssue): String =
        when (issue) {
            SelfHostedSetupValidationIssue.EndpointRequired -> uiStrings.selfHostedEndpointRequired
            SelfHostedSetupValidationIssue.EndpointSchemeRequired -> uiStrings.selfHostedEndpointSchemeRequired
            SelfHostedSetupValidationIssue.HttpsRequired -> uiStrings.selfHostedHttps
            SelfHostedSetupValidationIssue.EmailInvalid -> uiStrings.selfHostedEmailInvalid
            SelfHostedSetupValidationIssue.PasswordTooShort -> uiStrings.selfHostedPasswordTooShort
            SelfHostedSetupValidationIssue.DeviceNameRequired -> uiStrings.selfHostedDeviceNameRequired
            SelfHostedSetupValidationIssue.PlatformRequired -> uiStrings.selfHostedPlatformRequired
        }

    private fun hostDeviceLabel(): String {
        val stableSuffix = state.settings.activeDeviceId
            .trim()
            .takeUnless { it.isBlank() || it == ClientSettings.DefaultActiveDeviceId }
            ?.takeLast(6)
        return stableSuffix?.let { "$selfHostedDeviceName · $it" } ?: selfHostedDeviceName
    }

    private fun selfHostedSetupMessage(reason: SelfHostedSetupReason): String =
        when (reason) {
            SelfHostedSetupReason.Ready -> uiStrings.selfHostedReady
            SelfHostedSetupReason.BoundSessionRenewed -> uiStrings.selfHostedBoundSessionRenewed
            SelfHostedSetupReason.AccountChangeBlocked -> uiStrings.selfHostedAccountChangeBlocked
            SelfHostedSetupReason.AuthorityInvalid -> uiStrings.selfHostedAuthorityInvalid
            SelfHostedSetupReason.EndpointMismatch -> uiStrings.selfHostedEndpointMismatch
            SelfHostedSetupReason.Unavailable -> uiStrings.selfHostedSetupUnavailable
            SelfHostedSetupReason.AuthorityMismatch,
            SelfHostedSetupReason.DeviceRevoked,
            SelfHostedSetupReason.Failed,
            -> uiStrings.selfHostedSetupFailed
        }

    private fun syncResultMessage(result: ManualSyncResult): String =
        when (result.reason) {
            ManualSyncReason.Completed -> uiStrings.syncCompleted
            ManualSyncReason.Initialized -> uiStrings.syncInitialized
            ManualSyncReason.Disabled -> uiStrings.syncDisabled
            ManualSyncReason.Unavailable -> uiStrings.syncUnavailable
            ManualSyncReason.AlreadyRunning -> uiStrings.syncAlreadyRunning
            ManualSyncReason.ProviderChanged -> uiStrings.syncConfigurationChanged
            ManualSyncReason.AuthorityMismatch -> uiStrings.syncAuthorityMismatch
            ManualSyncReason.WorkspaceLocked -> uiStrings.syncWorkspaceLocked
            ManualSyncReason.RemoteHistoryConflict -> uiStrings.syncRemoteHistoryConflict
            ManualSyncReason.RetryRequired -> uiStrings.syncRetryRequired
            ManualSyncReason.Blocked -> uiStrings.syncBlocked
            ManualSyncReason.CheckpointInvalid -> uiStrings.syncCheckpointInvalid
            ManualSyncReason.Failed -> uiStrings.syncFailed
        }

    private fun syncIssueReason(reason: ManualSyncReason): SyncIssueReason =
        when (reason) {
            ManualSyncReason.AuthorityMismatch -> SyncIssueReason.AuthorityMismatch
            ManualSyncReason.WorkspaceLocked -> SyncIssueReason.WorkspaceLocked
            ManualSyncReason.RemoteHistoryConflict -> SyncIssueReason.RemoteHistoryConflict
            ManualSyncReason.CheckpointInvalid -> SyncIssueReason.CheckpointInvalid
            ManualSyncReason.RetryRequired -> SyncIssueReason.RetryRequired
            ManualSyncReason.Blocked -> SyncIssueReason.Blocked
            ManualSyncReason.Disabled,
            ManualSyncReason.Unavailable,
            -> SyncIssueReason.SyncUnavailable
            ManualSyncReason.ProviderChanged -> SyncIssueReason.ConfigurationChanged
            ManualSyncReason.Completed,
            ManualSyncReason.Initialized,
            ManualSyncReason.AlreadyRunning,
            ManualSyncReason.Failed,
            -> SyncIssueReason.SyncFailed
        }

    private fun syncIssueMessage(reason: SyncIssueReason): String =
        when (reason) {
            SyncIssueReason.SignInRequired -> uiStrings.signInBeforeSync
            SyncIssueReason.SecureSessionUnavailable -> uiStrings.secureSessionUnavailable
            SyncIssueReason.SetupFailed -> uiStrings.selfHostedSetupFailed
            SyncIssueReason.ConfigurationChanged -> uiStrings.syncConfigurationChanged
            SyncIssueReason.SyncUnavailable -> uiStrings.syncUnavailable
            SyncIssueReason.AuthorityMismatch -> uiStrings.syncAuthorityMismatch
            SyncIssueReason.WorkspaceLocked -> uiStrings.syncWorkspaceLocked
            SyncIssueReason.RemoteHistoryConflict -> uiStrings.syncRemoteHistoryConflict
            SyncIssueReason.CheckpointInvalid -> uiStrings.syncCheckpointInvalid
            SyncIssueReason.RetryRequired -> uiStrings.syncRetryRequired
            SyncIssueReason.Blocked -> uiStrings.syncBlocked
            SyncIssueReason.SyncFailed -> uiStrings.syncFailed
            SyncIssueReason.WorkspaceSettingsReloadRequired -> uiStrings.pairingSettingsReloadFailed
        }

    private fun workspacePairingMessage(
        reason: WorkspacePairingReason,
        invitationOperation: Boolean = false,
    ): String =
        when (reason) {
            WorkspacePairingReason.PackageCreated,
            WorkspacePairingReason.InvitationCreated,
            -> uiStrings.pairingInvitationCreated
            WorkspacePairingReason.InvitationCancelled -> uiStrings.pairingInvitationCancelled
            WorkspacePairingReason.InvitationUnavailable -> uiStrings.pairingInvitationUnavailable
            WorkspacePairingReason.Joined -> uiStrings.pairingJoined
            WorkspacePairingReason.PublishRequired -> uiStrings.pairingPublishRequired
            WorkspacePairingReason.SessionRequired -> uiStrings.pairingSessionRequired
            WorkspacePairingReason.InvalidToken -> uiStrings.enterPairingToken
            WorkspacePairingReason.InvitationExpired -> uiStrings.pairingExpired
            WorkspacePairingReason.InvitationNotFound,
            WorkspacePairingReason.InvitationAlreadyUsed,
            -> uiStrings.pairingInvitationUnavailable
            WorkspacePairingReason.WorkspaceLocked -> uiStrings.pairingWorkspaceLocked
            WorkspacePairingReason.ReplacementConfirmationRequired ->
                uiStrings.pairingReplacementConfirmationRequired
            WorkspacePairingReason.ReplacementFailed -> uiStrings.pairingReplacementFailed
            WorkspacePairingReason.ServerRequestFailed -> uiStrings.pairingServerRequestFailed
            WorkspacePairingReason.VerificationFailed -> uiStrings.pairingVerificationFailed
            WorkspacePairingReason.AuthorityMismatch -> uiStrings.syncAuthorityMismatch
            WorkspacePairingReason.Unavailable,
            WorkspacePairingReason.Failed,
            -> if (invitationOperation) uiStrings.pairingInvitationFailed else uiStrings.pairingFailed
        }

    private fun workspaceRecoveryMessage(reason: WorkspaceRecoveryReason): String =
        when (reason) {
            WorkspaceRecoveryReason.NotConfigured -> uiStrings.recoveryNotConfigured
            WorkspaceRecoveryReason.Configured -> uiStrings.recoveryConfigured
            WorkspaceRecoveryReason.RecoveryAvailable -> uiStrings.recoveryAvailable
            WorkspaceRecoveryReason.CodePrepared -> uiStrings.recoveryCodePrepared
            WorkspaceRecoveryReason.CodeCreated -> uiStrings.recoveryCodeCreated
            WorkspaceRecoveryReason.Recovered -> uiStrings.recoveryCompleted
            WorkspaceRecoveryReason.PublishRequired -> uiStrings.recoveryPublishRequired
            WorkspaceRecoveryReason.SessionRequired -> uiStrings.pairingSessionRequired
            WorkspaceRecoveryReason.AuthorityMismatch -> uiStrings.syncAuthorityMismatch
            WorkspaceRecoveryReason.WorkspaceLocked -> uiStrings.pairingWorkspaceLocked
            WorkspaceRecoveryReason.InvalidCode -> uiStrings.recoveryCodeInvalid
            WorkspaceRecoveryReason.RecoveryNotRequired -> uiStrings.recoveryNotRequired
            WorkspaceRecoveryReason.ReplacementConfirmationRequired ->
                uiStrings.recoveryReplacementConfirmationRequired
            WorkspaceRecoveryReason.ReplacementFailed -> uiStrings.recoveryReplacementFailed
            WorkspaceRecoveryReason.ServerConflict -> uiStrings.recoveryServerConflict
            WorkspaceRecoveryReason.ServerRequestFailed -> uiStrings.pairingServerRequestFailed
            WorkspaceRecoveryReason.Unavailable -> uiStrings.recoveryUnavailable
            WorkspaceRecoveryReason.Failed -> uiStrings.recoveryFailed
        }

    private fun publishCurrentState() {
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = state.feedbackMessage,
            feedbackSeverity = state.feedbackSeverity,
            feedbackEventId = state.feedbackEventId,
        )
    }

    private suspend fun <T> runExclusiveSyncLifecycle(
        unavailable: T,
        block: suspend () -> T,
    ): T {
        if (!settingsMutationMutex.tryLock()) return unavailable
        return try {
            block()
        } finally {
            settingsMutationMutex.unlock()
        }
    }

    private suspend fun <T> withSettingsMutation(block: suspend () -> T): T {
        settingsMutationMutex.lock()
        return try {
            block()
        } finally {
            settingsMutationMutex.unlock()
        }
    }

    /** Caller must hold [settingsMutationMutex]. */
    private suspend fun persistLocked(
        updated: ClientSettings,
        successMessage: String,
        successSeverity: SettingsFeedbackSeverity = SettingsFeedbackSeverity.Success,
    ): Boolean =
        runCatching {
            withContext(backgroundDispatcher) { persistSettings(updated) }
        }.fold(
            onSuccess = { persisted ->
                state = buildState(
                    settings = persisted,
                    exportSummary = state.exportSummary,
                    feedbackMessage = successMessage,
                    feedbackSeverity = successSeverity,
                )
                true
            },
            onFailure = { failure ->
                failure.rethrowCancellation()
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = formatUiString(uiStrings.settingsSaveFailed, failure.message ?: uiStrings.unknownError),
                    feedbackSeverity = SettingsFeedbackSeverity.Error,
                )
                false
            },
        )

    private fun buildState(
        settings: ClientSettings,
        exportSummary: SettingsExportSummary? = null,
        feedbackMessage: String? = null,
        feedbackSeverity: SettingsFeedbackSeverity = SettingsFeedbackSeverity.Info,
        feedbackEventId: Long? = null,
    ): SettingsUiState {
        val syncConfiguration = settings.syncConfiguration
        val session = syncConfiguration.selfHostedSession
        val visibleInvitation = currentWorkspacePairingInvitation
            ?.takeIf { it.expiresAtEpochMillis > currentEpochMillis() }
        return SettingsUiState(
            settings = settings,
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
            feedbackSeverity = feedbackSeverity,
            feedbackEventId = when {
                feedbackMessage == null -> 0L
                feedbackEventId != null -> feedbackEventId
                else -> ++nextFeedbackEventId
            },
            sync = SyncUiState(
                connection = when {
                    secureSessionAccess == SecureSessionAccess.Unavailable && session.loggedIn -> SyncConnectionUi.Unavailable(
                        configuredEndpoint = syncConfiguration.selfHostedEndpoint,
                        accountEmail = session.userEmail,
                        deviceLabel = session.deviceLabel,
                    )
                    secureSessionAccess != SecureSessionAccess.Missing &&
                        session.loggedIn &&
                        syncConfiguration.mode == SyncMode.SelfHosted -> SyncConnectionUi.Connected(
                        endpoint = syncConfiguration.selfHostedEndpoint,
                        accountEmail = session.userEmail,
                        deviceLabel = session.deviceLabel,
                    )
                    else -> SyncConnectionUi.LocalOnly(syncConfiguration.selfHostedEndpoint)
                },
                operation = currentSyncOperation,
                issue = currentSyncIssue,
                invitation = visibleInvitation,
                recovery = currentWorkspaceRecovery,
            ),
        )
    }
}

private enum class SecureSessionAccess {
    Unknown,
    Available,
    Missing,
    Unavailable,
}

private data class SyncCompletion(
    val success: Boolean = false,
    val refreshProductData: Boolean = false,
)

private data class WorkspaceJoinCompletion(
    val joined: Boolean = false,
    val refreshProductData: Boolean = false,
)

private data class ConnectionSwitchCompletion(
    val switched: Boolean = false,
    val refreshProductData: Boolean = false,
)

/**
 * Prevents a retry from interpreting fallback values as edits to the joined
 * workspace. Real edits still fail closed because this snapshot has no causal
 * token; a successful Sync projects the target workspace before persistence.
 */
private fun ClientSettings.safeWorkspaceReplacementFallback(): ClientSettings {
    val reset = resetWorkspaceStateForReplacement()
    return reset.copy(
        workspacePreferencesState = WorkspacePreferencesSyncState(
            displayedSnapshot = WorkspacePreferencesSnapshot(
                theme = reset.theme,
                previewByDefault = reset.editorPreferences.previewByDefault,
                markdownToolbarVisible = reset.editorPreferences.markdownToolbarVisible,
                defaultNotebookId = reset.defaultNotebookId,
            ),
        ),
    )
}

private val ManualSyncResult.hasVisibleSyncChanges: Boolean
    get() = pushedObjects > 0 || pulledObjects > 0 || conflicts > 0

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}

private fun syncIssueFromLastError(lastError: String?): SyncIssueUi? {
    val marker = lastError?.substringAfter(':', missingDelimiterValue = "")
        ?.takeIf(String::isNotBlank)
        ?: return null
    val reason = if (lastError.startsWith("setup:")) {
        SyncIssueReason.SetupFailed
    } else {
        when (marker) {
            ManualSyncReason.AuthorityMismatch.name -> SyncIssueReason.AuthorityMismatch
            ManualSyncReason.WorkspaceLocked.name -> SyncIssueReason.WorkspaceLocked
            ManualSyncReason.RemoteHistoryConflict.name -> SyncIssueReason.RemoteHistoryConflict
            ManualSyncReason.CheckpointInvalid.name -> SyncIssueReason.CheckpointInvalid
            ManualSyncReason.RetryRequired.name -> SyncIssueReason.RetryRequired
            ManualSyncReason.Blocked.name -> SyncIssueReason.Blocked
            ManualSyncReason.Disabled.name,
            ManualSyncReason.Unavailable.name,
            -> SyncIssueReason.SyncUnavailable
            ManualSyncReason.ProviderChanged.name -> SyncIssueReason.ConfigurationChanged
            ManualSyncReason.AlreadyRunning.name -> return null
            else -> SyncIssueReason.SyncFailed
        }
    }
    return SyncIssueUi(reason)
}

data class SettingsUiState(
    val settings: ClientSettings,
    val defaultNotebookOptions: List<DefaultNotebookOption>,
    val sync: SyncUiState,
    val exportSummary: SettingsExportSummary? = null,
    val importSummary: SettingsImportSummary? = null,
    val importRunning: Boolean = false,
    val feedbackMessage: String? = null,
    val feedbackSeverity: SettingsFeedbackSeverity = SettingsFeedbackSeverity.Info,
    val feedbackEventId: Long = 0L,
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

class WorkspaceRecoveryCodeUi(
    private val recoveryCode: WorkspaceRecoveryCode,
) {
    val value: String get() = recoveryCode.revealForUserConfirmation()

    override fun toString(): String = "WorkspaceRecoveryCodeUi(<redacted>)"
}

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
    val includesMediaBytes: Boolean = false,
    val assetReferencesMayBeUnresolved: Boolean = true,
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
                formatName = "Someday JSON export",
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
    val includesMediaBytes: Boolean = false,
    val assetReferencesMayBeUnresolved: Boolean = true,
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
