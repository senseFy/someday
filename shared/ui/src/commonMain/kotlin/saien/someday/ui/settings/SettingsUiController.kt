@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import saien.someday.domain.notes.NotebookSummary
import saien.someday.domain.notifications.OnThisDayNotificationScheduler
import saien.someday.domain.notifications.UnavailableOnThisDayNotificationScheduler
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.ManualSyncPhase
import saien.someday.domain.settings.ManualSyncProgress
import saien.someday.domain.settings.ManualSyncProgressListener
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SelfHostedSetupReason
import saien.someday.domain.settings.SelfHostedSetupResult
import saien.someday.domain.settings.SelfHostedSetupValidationIssue
import saien.someday.domain.settings.SyncConfiguration
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
import saien.someday.domain.settings.isSecureSyncEndpoint
import saien.someday.domain.settings.normalizeSelfHostedEndpoint
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

class SettingsUiController(
    initialSettings: ClientSettings = ClientSettings(),
    private val notebooksProvider: () -> List<NotebookSummary> = { emptyList() },
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
    private val manualSyncRunner: ManualSyncRunner = ManualSyncRunner {
        ManualSyncResult.failure(
            mode = SyncMode.Off,
            reason = ManualSyncReason.Unavailable,
        )
    },
    private val bindManualSyncProgressListener: (ManualSyncProgressListener?) -> Unit = {},
    private val workspacePairingInvitationCreator: WorkspacePairingInvitationCreator =
        WorkspacePairingInvitationCreator {
            WorkspacePairingInvitationResult.failure(WorkspacePairingReason.Unavailable)
        },
    private val workspacePairingInvitationJoiner: WorkspacePairingInvitationJoiner =
        WorkspacePairingInvitationJoiner {
            WorkspaceJoinResult.failure(WorkspacePairingReason.Unavailable)
        },
    private val workspacePairingInvitationCanceller: WorkspacePairingInvitationCanceller =
        WorkspacePairingInvitationCanceller {
            WorkspaceJoinResult.failure(WorkspacePairingReason.Unavailable)
        },
    private val onThisDayNotificationScheduler: OnThisDayNotificationScheduler =
        UnavailableOnThisDayNotificationScheduler,
    private val onThisDayNotificationStrings: OnThisDayNotificationStrings =
        OnThisDayNotificationStrings(),
    private val uiStrings: SettingsUiStrings = SettingsUiStrings(),
    private val currentEpochMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    val onThisDayNotificationsSupported: Boolean = onThisDayNotificationScheduler.isSupported

    private var currentWorkspacePairingInvitation: WorkspacePairingInvitationUi? = null
    private var currentImportSummary: SettingsImportSummary? = null
    private var importRunning: Boolean = false
    private var nextFeedbackEventId = 0L

    var state: SettingsUiState by mutableStateOf(
        buildState(
            settings = initialSettings,
            manualSyncProgress = ManualSyncProgress.idle(
                initialSettings.syncConfiguration.mode,
                uiStrings.syncReady,
            ),
        ),
    )
        private set

    suspend fun refresh() {
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = state.feedbackMessage,
            feedbackEventId = state.feedbackEventId,
            manualSyncProgress = state.manualSyncProgress,
        )
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
                        ManualSyncProgress.idle(persisted.syncConfiguration.mode, uiStrings.syncReady)
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
                feedbackMessage = validationErrors.joinToString(separator = " ", transform = ::selfHostedValidationMessage),
                manualSyncProgress = state.manualSyncProgress,
            )
            return false
        }

        val result = runCatching {
            withContext(backgroundDispatcher) { selfHostedSetupClient.setup(sanitized) }
        }.getOrElse { failure ->
            SelfHostedSetupResult.failure(
                reason = SelfHostedSetupReason.Failed,
                diagnosticMessage = failure.message,
            )
        }
        val displayMessage = selfHostedSetupMessage(result.status.reason)
        if (!result.success || result.session == null) {
            // Failed account/device replacement must not damage the previously
            // bound endpoint or its usable session summary.
            val preserved = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    lastError = "setup:${result.status.reason.name}",
                ),
            )
            persist(updated = preserved, successMessage = displayMessage)
            return false
        }
        val session = checkNotNull(result.session)
        val updatedSettings = state.settings.copy(
            activeDeviceId = session.deviceId ?: state.settings.activeDeviceId,
            syncConfiguration = state.settings.syncConfiguration.copy(
                mode = SyncMode.SelfHosted,
                selfHostedEndpoint = sanitized.endpoint,
                selfHostedSession = session,
                lastError = null,
            ),
        )
        return persist(
            updated = updatedSettings,
            successMessage = displayMessage,
        )
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
        if (state.manualSyncProgress.running) {
            return false
        }
        val mode = state.settings.syncConfiguration.mode
        val blockingMessage = syncBlockingMessage(mode)
        if (blockingMessage != null) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = if (showFeedback) blockingMessage else state.feedbackMessage,
                feedbackEventId = if (showFeedback) null else state.feedbackEventId,
                manualSyncProgress = ManualSyncProgress.idle(mode, uiStrings.syncReady),
            )
            return false
        }
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = if (showFeedback) uiStrings.syncStarted else state.feedbackMessage,
            feedbackEventId = if (showFeedback) null else state.feedbackEventId,
            manualSyncProgress = ManualSyncProgress.inProgress(mode, uiStrings.syncInProgress),
        )
        return true
    }

    suspend fun completeManualSync(result: ManualSyncResult): Boolean =
        completeManualSync(result, showFeedback = true)

    private suspend fun completeManualSync(
        result: ManualSyncResult,
        showFeedback: Boolean,
    ): Boolean {
        val displayMessage = manualSyncMessage(result)
        val updatedSettings = state.settings.copy(
            syncConfiguration = state.settings.syncConfiguration.copy(
                lastError = if (result.success) null else "sync:${result.reason.name}",
            ),
        )
        val persisted = runCatching {
            withContext(backgroundDispatcher) { persistSettings(updatedSettings) }
        }.getOrElse { state.settings }
        state = buildState(
            settings = persisted,
            exportSummary = state.exportSummary,
            feedbackMessage = if (showFeedback) displayMessage else state.feedbackMessage,
            feedbackEventId = if (showFeedback) null else state.feedbackEventId,
            manualSyncProgress = ManualSyncProgress.fromResult(result, displayMessage),
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
        return completeManualSync(executeManualSyncRunner())
    }

    private suspend fun executeManualSyncRunner(): ManualSyncResult {
        val mode = state.settings.syncConfiguration.mode
        return runCatching {
            coroutineScope {
                bindManualSyncProgressListener(
                    ManualSyncProgressListener { phase ->
                        val message = formatManualSyncPhase(phase)
                        launch(uiDispatcher) {
                            if (state.manualSyncProgress.running &&
                                state.manualSyncProgress.mode == mode
                            ) {
                                state = buildState(
                                    settings = state.settings,
                                    exportSummary = state.exportSummary,
                                    feedbackMessage = state.feedbackMessage,
                                    feedbackEventId = state.feedbackEventId,
                                    manualSyncProgress = ManualSyncProgress.inProgress(mode, message),
                                )
                            }
                        }
                    },
                )
                try {
                    withContext(backgroundDispatcher) { manualSyncRunner.run() }
                } finally {
                    bindManualSyncProgressListener(null)
                }
            }
        }.getOrElse { failure ->
            bindManualSyncProgressListener(null)
            ManualSyncResult.failure(
                mode = mode,
                reason = ManualSyncReason.Failed,
                diagnosticMessage = failure.message,
            )
        }
    }

    suspend fun runAutomaticSync(): Boolean {
        if (!canRunAutomaticSync() || !beginManualSync(showFeedback = false)) {
            return false
        }
        val result = executeManualSyncRunner()
        val shouldShowFeedback = !result.success || result.pulledObjects > 0 || result.conflicts > 0
        return completeManualSync(result, showFeedback = shouldShowFeedback)
    }

    suspend fun recordSyncError(error: String?): Boolean =
        persist(
            updated = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    lastError = error?.takeIf { it.isNotBlank() },
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
            WorkspacePairingInvitationResult.failure(WorkspacePairingReason.Failed)
        }
        currentWorkspacePairingInvitation = result.invitation?.let(::WorkspacePairingInvitationUi)
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = workspacePairingMessage(result.reason, invitationOperation = true),
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
            WorkspaceJoinResult.failure(WorkspacePairingReason.Failed)
        }
        if (result.success) {
            currentWorkspacePairingInvitation = null
        }
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = workspacePairingMessage(result.reason),
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
            WorkspaceJoinResult.failure(WorkspacePairingReason.Failed)
        }
        if (result.success) {
            currentWorkspacePairingInvitation = null
            // Prior sync failures (wrong first-run key against a remote epoch)
            // are stale after a successful join package restore.
            val cleared = state.settings.copy(
                syncConfiguration = state.settings.syncConfiguration.copy(
                    lastError = null,
                ),
            )
            val persisted = runCatching {
                withContext(backgroundDispatcher) { persistSettings(cleared) }
            }.getOrElse { cleared }
            state = buildState(
                settings = persisted,
                exportSummary = state.exportSummary,
                feedbackMessage = workspacePairingMessage(result.reason),
                manualSyncProgress = state.manualSyncProgress,
            )
            return true
        }
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = workspacePairingMessage(result.reason),
            manualSyncProgress = state.manualSyncProgress,
        )
        return false
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

    private fun manualSyncMessage(result: ManualSyncResult): String =
        when (result.reason) {
            ManualSyncReason.Completed -> formatUiString(
                uiStrings.syncCompleted,
                result.pushedObjects,
                result.pulledObjects,
                result.conflicts,
            )
            ManualSyncReason.Initialized -> formatUiString(
                uiStrings.syncInitialized,
                result.pushedObjects,
                result.pulledObjects,
                result.conflicts,
            )
            ManualSyncReason.Disabled -> uiStrings.syncDisabled
            ManualSyncReason.Unavailable -> uiStrings.syncUnavailable
            ManualSyncReason.AlreadyRunning -> uiStrings.syncAlreadyRunning
            ManualSyncReason.ProviderChanged -> uiStrings.syncConfigurationChanged
            ManualSyncReason.AuthorityMismatch -> uiStrings.syncAuthorityMismatch
            ManualSyncReason.WorkspaceLocked -> uiStrings.syncWorkspaceLocked
            ManualSyncReason.RemoteHistoryConflict -> uiStrings.syncRemoteHistoryConflict
            ManualSyncReason.RetryRequired -> uiStrings.syncRetryRequired
            ManualSyncReason.Blocked -> uiStrings.syncBlocked
            ManualSyncReason.CheckpointInvalid,
            ManualSyncReason.Failed,
            -> uiStrings.syncFailed
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
            WorkspacePairingReason.LocalWorkspaceNotReplaceable -> uiStrings.pairingLocalWorkspaceNotReplaceable
            WorkspacePairingReason.LocalContentPresent -> uiStrings.pairingLocalContentPresent
            WorkspacePairingReason.VerificationFailed,
            WorkspacePairingReason.AuthorityMismatch,
            WorkspacePairingReason.AdoptionFailed,
            WorkspacePairingReason.Unavailable,
            WorkspacePairingReason.Failed,
            -> if (invitationOperation) uiStrings.pairingInvitationFailed else uiStrings.pairingFailed
        }

    private fun syncBlockingMessage(mode: SyncMode): String? =
        when (mode) {
            SyncMode.SelfHosted -> if (state.settings.syncConfiguration.selfHostedSession.loggedIn) {
                null
            } else {
                uiStrings.signInBeforeSync
            }
            SyncMode.Off -> uiStrings.signInBeforeSync
        }

    private fun formatManualSyncPhase(phase: ManualSyncPhase): String =
        when (phase) {
            is ManualSyncPhase.UploadingChunks ->
                if (phase.total <= 0) {
                    uiStrings.syncUploadingCheckpoint
                } else {
                    formatUiString(
                        uiStrings.syncUploadingCheckpointChunks,
                        phase.completed.toString(),
                        phase.total.toString(),
                    )
                }
            ManualSyncPhase.UploadingManifest -> uiStrings.syncUploadingManifest
            ManualSyncPhase.VerifyingRemote -> uiStrings.syncVerifyingRemote
            ManualSyncPhase.CommittingPointer -> uiStrings.syncCommittingEpoch
        }

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
                        ManualSyncProgress.idle(persisted.syncConfiguration.mode, uiStrings.syncReady)
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
        manualSyncProgress: ManualSyncProgress = ManualSyncProgress.idle(
            settings.syncConfiguration.mode,
            uiStrings.syncReady,
        ),
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
            workspacePairingInvitation = currentWorkspacePairingInvitation
                ?.takeIf { it.expiresAtEpochMillis > currentEpochMillis() },
        )
}

private val ManualSyncResult.hasVisibleSyncChanges: Boolean
    get() = pushedObjects > 0 || pulledObjects > 0 || conflicts > 0

data class SettingsUiState(
    val settings: ClientSettings,
    val sections: List<SettingsSectionUi>,
    val defaultNotebookOptions: List<DefaultNotebookOption>,
    val manualSyncProgress: ManualSyncProgress,
    val exportSummary: SettingsExportSummary? = null,
    val importSummary: SettingsImportSummary? = null,
    val importRunning: Boolean = false,
    val feedbackMessage: String? = null,
    val feedbackEventId: Long = 0L,
    val workspacePairingInvitation: WorkspacePairingInvitationUi? = null,
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

fun settingsCapabilityLog(): String =
    "settings-sections=sync-mode-account|self-hosted-device-management|device-pairing|" +
        "editor-preferences|theme-default-notebook|sync-status-last-error|import-export-entry-points " +
        "self-hosted=endpoint|login-register|device-session|manual-sync-progress|tokens-redacted " +
        "workspace-pairing=one-use-invitation|qr-or-token|redacted-logs " +
        "theme=system|light|dark default-notebook=add-target-unless-overridden " +
        "import=day-one-json-zip|dag-only export=notes-notebooks|dag-only|excludes-media-bytes|" +
        "asset-references-may-be-unresolved|excludes-raw-keys-tokens-passwords-recovery-material"

private fun requiredSettingsSections(settings: ClientSettings): List<SettingsSectionUi> {
    val selfHostedSession = settings.syncConfiguration.selfHostedSession
    return listOf(
        SettingsSectionUi(
            title = "Sync mode/account",
            description = "Enable or disable self-hosted sync without blocking local-first note editing.",
            entryPoints = listOf(
                "Current mode: ${settings.syncConfiguration.mode.name}",
                "Account/session entry point",
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
            description = "Pair through the configured self-hosted service without exposing workspace secrets to the remote.",
            entryPoints = listOf(
                "Create a one-use workspace pairing invitation",
                "Join a workspace with a QR scan or high-entropy token before syncing",
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
                "Manual sync trigger available for self-hosted mode",
                if (settings.syncConfiguration.lastError == null) {
                    "Last error: none"
                } else {
                    "Last error: recorded without exposing diagnostics"
                },
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
            description =
                "Export local note Markdown and notebooks as Someday JSON. " +
                    "Image bytes and secrets are excluded, so this is not a complete media backup.",
            entryPoints = listOf(
                "Export local data",
                "Image bytes and secrets excluded by default",
            ),
        ),
    )
}
