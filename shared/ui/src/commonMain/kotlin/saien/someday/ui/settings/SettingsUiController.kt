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
import saien.someday.domain.workspace.WorkspaceProductAccess
import saien.someday.domain.workspace.UnrestrictedWorkspaceProductAccess
import saien.someday.domain.workspace.WorkspaceProductSnapshot
import saien.someday.domain.workspace.WorkspaceProductChangedException
import saien.someday.domain.notifications.OnThisDayNotificationScheduler
import saien.someday.domain.notifications.UnavailableOnThisDayNotificationScheduler
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.AccountDataResetManager
import saien.someday.domain.settings.AccountDataResetSnapshot
import saien.someday.domain.settings.AccountDataResetReview
import saien.someday.domain.settings.AccountDataResetActionResult
import saien.someday.domain.settings.AccountDataResetIssue
import saien.someday.domain.settings.AccountDataReplacementMode
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
import saien.someday.domain.settings.WorkspaceAdmissionManager
import saien.someday.domain.settings.WorkspaceAdmissionState
import saien.someday.domain.settings.WorkspaceAdmissionStatus
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
    private val localExportRunner: LocalExportRunner? = null,
    private val dayOneImportRunner: DayOneImportRunner = DayOneImportRunner { onResult ->
        onResult(SettingsImportSummary(outcome = SettingsImportOutcome.Unavailable))
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
    private val workspaceAdmissionManager: WorkspaceAdmissionManager? = null,
    private val accountDataResetManager: AccountDataResetManager? = null,
    private val workspaceProductAccess: WorkspaceProductAccess = UnrestrictedWorkspaceProductAccess,
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
    private var currentWorkspaceAdmission = initialWorkspaceAdmission()

    private fun initialWorkspaceAdmission() = WorkspaceAdmissionStatus(
        if (workspaceAdmissionManager == null) WorkspaceAdmissionState.Ready else WorkspaceAdmissionState.Pending,
    )

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
    private var currentAccountResetSnapshot: AccountDataResetSnapshot? = null
    private var currentAccountResetOperation: AccountResetUiOperation? = null
    private var activeAccountResetAction: Any? = null
    private var accountResetRevision = 0L
    private var latestAccountResetLoad: Any? = null
    private var keepingAccountCopyOffline = false
    private var currentSettingsWorkspace: WorkspaceProductSnapshot? = null
    private var mutationWorkspace: WorkspaceProductSnapshot? = null
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

    suspend fun refresh() = withSettingsMutation(adoptCurrentWorkspace = true) { refreshLocked() }

    /** Local startup state only; no HTTP or database access occurs in construction. */
    suspend fun loadAccountResetState() {
        val manager = accountDataResetManager ?: return
        if (currentAccountResetOperation != null) return
        val revision = accountResetRevision
        val loadIdentity = Any().also { latestAccountResetLoad = it }
        val snapshot = try {
            withContext(backgroundDispatcher) { manager.load() }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
            currentAccountResetSnapshot?.copy(issue = AccountDataResetIssue.LocalFailure)
                ?: AccountDataResetSnapshot(issue = AccountDataResetIssue.LocalFailure)
        }
        if (revision != accountResetRevision || latestAccountResetLoad !== loadIdentity || currentAccountResetOperation != null) return
        currentAccountResetSnapshot = snapshot
        publishCurrentState()
    }

    suspend fun refreshAccountReset(): Boolean = runAccountResetAction(AccountResetUiOperation.Refreshing) { it.refresh() }

    suspend fun submitAccountReset(review: AccountDataResetReview, password: String, phrase: String): Boolean {
        if (password.isBlank() || phrase != uiStrings.accountReset.confirmationPhrase) {
            state = buildState(state.settings, state.exportSummary, uiStrings.accountReset.confirmationRequired, SettingsFeedbackSeverity.Warning)
            return false
        }
        return runAccountResetAction(AccountResetUiOperation.Submitting) { it.submit(review, password) }
    }

    suspend fun reauthenticateAccountReset(review: AccountDataResetReview, password: String, email: String = review.accountEmail): Boolean {
        if (password.isBlank() || email.isBlank()) return false
        return runAccountResetAction(AccountResetUiOperation.Authenticating) { it.reauthenticate(review, password, email.trim()) }
    }

    suspend fun reconcileAccountReset(review: AccountDataResetReview): Boolean =
        runAccountResetAction(AccountResetUiOperation.Reconciling) { it.reconcile(review) }

    suspend fun keepAccountCopyOffline(review: AccountDataResetReview): Boolean =
        runAccountResetAction(AccountResetUiOperation.KeepingOffline) { it.keepOffline(review) }

    suspend fun replaceAccountWorkspace(review: AccountDataResetReview, mode: AccountDataReplacementMode, discardConfirmed: Boolean, secret: String = "", password: String? = null): Boolean {
        if (!discardConfirmed) {
            state = buildState(state.settings, state.exportSummary, uiStrings.accountReset.localConsentRequired, SettingsFeedbackSeverity.Warning)
            return false
        }
        val replaced = runAccountResetAction(AccountResetUiOperation.Replacing) { it.replaceLocal(review, mode, true, secret, password) }
        loadAccountResetState()
        return replaced
    }

    suspend fun cancelAccountResetReview() {
        val manager = accountDataResetManager ?: return
        val revision = accountResetRevision
        if (currentAccountResetOperation == null) {
            currentAccountResetSnapshot = currentAccountResetSnapshot?.copy(review = null)
            publishCurrentState()
        }
        val loadIdentity = Any().also { latestAccountResetLoad = it }
        val snapshot = try {
            withContext(backgroundDispatcher) {
                manager.cancel()
                manager.load()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentAccountResetSnapshot?.copy(issue = AccountDataResetIssue.LocalFailure)
                ?: AccountDataResetSnapshot(issue = AccountDataResetIssue.LocalFailure)
        }
        if (revision == accountResetRevision && latestAccountResetLoad === loadIdentity && currentAccountResetOperation == null) {
            currentAccountResetSnapshot = snapshot
            publishCurrentState()
        }
    }

    /** Never hold the settings coroutine mutex across an account-control request. */
    private suspend fun runAccountResetAction(operation: AccountResetUiOperation, action: (AccountDataResetManager) -> AccountDataResetActionResult): Boolean {
        val manager = accountDataResetManager ?: return false
        val keepsOffline = operation == AccountResetUiOperation.KeepingOffline
        if (currentSyncOperation != null || importRunning || settingsMutationMutex.isLocked || keepingAccountCopyOffline) return false
        if (keepsOffline) {
            if (currentAccountResetOperation == AccountResetUiOperation.Replacing) return false
            keepingAccountCopyOffline = true
        } else if (activeAccountResetAction != null) {
            return false
        }
        val actionIdentity = Any()
        if (!keepsOffline) activeAccountResetAction = actionIdentity
        val revision = ++accountResetRevision
        currentAccountResetOperation = operation
        publishCurrentState()
        val previousSnapshot = currentAccountResetSnapshot
        try {
            val completion = withContext(backgroundDispatcher) {
                val capturedWorkspace = if (operation == AccountResetUiOperation.Authenticating || operation == AccountResetUiOperation.Replacing) {
                    workspaceProductAccess.capture()
                } else null
                val result = try { action(manager) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                    val snapshot = runCatching { manager.load() }.getOrNull() ?: previousSnapshot ?: AccountDataResetSnapshot()
                    AccountDataResetActionResult(snapshot, false, issue = AccountDataResetIssue.LocalFailure)
                }
                val workspace = if (result.localReplaced) workspaceProductAccess.capture() else capturedWorkspace
                val settings = workspace?.let {
                    runCatching { workspaceProductAccess.read(it) { loadSettings() } }.getOrNull()
                }
                val sessionAccess = if (operation == AccountResetUiOperation.Replacing && workspace != null) {
                    runCatching {
                        workspaceProductAccess.read(workspace) {
                            if (selfHostedSessionCredentialStore.load() == null) SecureSessionAccess.Missing else SecureSessionAccess.Available
                        }
                    }.getOrDefault(SecureSessionAccess.Unavailable)
                } else null
                AccountResetCompletion(result, settings, workspace, sessionAccess)
            }
            // An offline exit supersedes every older HTTP completion, including its
            // loading state. The durable manager independently invalidates its attempt.
            if (revision != accountResetRevision) {
                // The primary request may have committed after an offline decision,
                // including an exit that failed before its pre-POST gate existed.
                // Reload durable state without replaying the superseded result.
                loadAccountResetState()
                return false
            }
            if (completion.workspace?.let { !workspaceProductAccess.isCurrent(it) } == true) {
                loadAccountResetState()
                return false
            }
            val result = completion.result
            currentAccountResetSnapshot = result.snapshot.copy(issue = result.issue)
            completion.sessionAccess?.let { secureSessionAccess = it }
            if (result.localReplaced) {
                currentSettingsWorkspace = completion.workspace
                currentWorkspacePairingInvitation = null
                currentWorkspaceAdmission = initialWorkspaceAdmission()
                currentWorkspaceRecovery = WorkspaceRecoveryUiState(
                    availability = if (workspaceRecoveryManager == null) WorkspaceRecoveryUiAvailability.NotConfigured else WorkspaceRecoveryUiAvailability.Unknown,
                    syncGate = if (workspaceRecoveryManager == null) WorkspaceRecoverySyncGate.Allowed else WorkspaceRecoverySyncGate.Pending,
                )
                currentSyncIssue = when {
                    completion.settings == null -> SyncIssueUi(SyncIssueReason.WorkspaceSettingsReloadRequired)
                    secureSessionAccess == SecureSessionAccess.Unavailable -> SyncIssueUi(SyncIssueReason.SecureSessionUnavailable)
                    secureSessionAccess == SecureSessionAccess.Missing -> SyncIssueUi(SyncIssueReason.SignInRequired)
                    else -> null
                }
            }
            state = buildState(
                settings = completion.settings ?: if (result.localReplaced) state.settings.safeWorkspaceReplacementFallback() else state.settings,
                exportSummary = if (result.localReplaced) null else state.exportSummary,
                feedbackMessage = result.issue?.message(uiStrings.accountReset),
                feedbackSeverity = if (result.success) SettingsFeedbackSeverity.Success else SettingsFeedbackSeverity.Warning,
            )
            if (result.localReplaced) {
                onDataRestored()
                if (completion.settings != null && currentSyncIssue == null) finishAccountWorkspaceReplacement()
            }
            return result.success
        } finally {
            if (activeAccountResetAction === actionIdentity) activeAccountResetAction = null
            if (keepsOffline) keepingAccountCopyOffline = false
            if (revision == accountResetRevision) {
                currentAccountResetOperation = null
                publishCurrentState()
            }
        }
    }

    /** Local replacement is committed; a later network failure must only offer sync retry. */
    private suspend fun finishAccountWorkspaceReplacement() = withSettingsMutation {
        if (state.sync.connection !is SyncConnectionUi.Connected) return@withSettingsMutation
        val replacementWorkspace = currentSettingsWorkspace
        currentSyncOperation = SyncUiOperation.CheckingRecovery
        publishCurrentState()
        try {
            refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
            refreshWorkspaceAdmissionLocked()
            if (replacementWorkspace != null && !workspaceProductAccess.isCurrent(replacementWorkspace)) {
                throw CancellationException("The local workspace changed.")
            }
            if (currentWorkspaceRecovery.blocksSync || currentWorkspaceAdmission.state !in setOf(
                    WorkspaceAdmissionState.Ready, WorkspaceAdmissionState.FirstWorkspace,
                )) {
                currentSyncIssue = SyncIssueUi(SyncIssueReason.RetryRequired)
                state = buildState(state.settings, state.exportSummary,
                    admissionBlockingMessage() ?: uiStrings.recoveryStatusUnavailable, SettingsFeedbackSeverity.Warning)
                return@withSettingsMutation
            }
            currentSyncOperation = SyncUiOperation.Syncing
            publishCurrentState()
            val syncResult = executeSyncRunner()
            completeSync(syncResult, showFeedback = true)
            if (syncResult.success || syncResult.hasVisibleSyncChanges) onDataRestored()
        } finally {
            currentSyncOperation = null
            publishCurrentState()
        }
    }

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

    suspend fun retryWorkspaceAdmission(): Boolean = runExclusiveSyncLifecycle(false) {
        if (workspaceAdmissionManager == null || currentSyncOperation != null ||
            state.sync.connection !is SyncConnectionUi.Connected || state.sync.accountReset.blocksSync
        ) return@runExclusiveSyncLifecycle false
        currentSyncOperation = SyncUiOperation.CheckingWorkspace
        publishCurrentState()
        try {
            refreshWorkspaceAdmissionLocked()
            refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
            currentWorkspaceAdmission.state !in setOf(WorkspaceAdmissionState.Pending, WorkspaceAdmissionState.Unavailable)
        } finally {
            currentSyncOperation = null
            publishCurrentState()
        }
    }

    private suspend fun refreshWorkspaceAdmissionLocked() {
        val manager = workspaceAdmissionManager ?: return
        val expectedWorkspace = currentSettingsWorkspace
        val status = try {
            withContext(backgroundDispatcher) { manager.status() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            WorkspaceAdmissionStatus(WorkspaceAdmissionState.Unavailable)
        }
        if (expectedWorkspace != null && !workspaceProductAccess.isCurrent(expectedWorkspace)) {
            throw CancellationException("The local workspace changed.")
        }
        currentWorkspaceAdmission = status
        // Discovery can persist an account-incarnation gate without throwing to the UI.
        loadAccountResetState()
        if (status.state == WorkspaceAdmissionState.JoinRequired && !state.sync.accountReset.blocksSync) {
            // A rejected first publication may have just discovered another workspace.
            // Re-read recovery so a code configured by that device is usable here.
            refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
        }
        if (currentSyncIssue?.reason in setOf(SyncIssueReason.WorkspaceJoinRequired, SyncIssueReason.WorkspaceAdmissionUnavailable)) {
            currentSyncIssue = null
        }
        publishCurrentState()
    }

    private fun admissionBlockingMessage(allowFirstWorkspace: Boolean = false): String? = when (currentWorkspaceAdmission.state) {
        WorkspaceAdmissionState.Ready -> null
        WorkspaceAdmissionState.FirstWorkspace -> if (allowFirstWorkspace) null else uiStrings.workspaceFirstRequired
        WorkspaceAdmissionState.JoinRequired -> uiStrings.workspaceJoinRequired
        WorkspaceAdmissionState.Pending, WorkspaceAdmissionState.Unavailable -> uiStrings.workspaceAdmissionUnavailable
    }

    private suspend fun refreshLocked() {
        val previousIdentity = currentSettingsWorkspace
        val currentIdentity = withContext(backgroundDispatcher) { workspaceProductAccess.capture() }
        if (previousIdentity != null && previousIdentity != currentIdentity) {
            state = buildState(loadCurrentWorkspaceSettings())
        }
        currentSettingsWorkspace = currentIdentity
        mutationWorkspace = currentIdentity
        // A durable account gate needs the last account hint for control-only
        // sign-in even when secure credentials disappeared between launches.
        loadAccountResetState()
        val preserveResetAccountHint = state.sync.needsAccountDataResolution
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
                        selfHostedSession = credentials?.toSummary() ?: if (preserveResetAccountHint) {
                            previousConfiguration.selfHostedSession.copy(loggedIn = false)
                        } else SelfHostedSessionSummary(),
                    ),
                )
                val persistenceResult = if (recovered == previousSettings) {
                    Result.success(recovered)
                } else {
                    runCatching {
                        withContext(backgroundDispatcher) { persistCurrentWorkspaceSettings(recovered) }
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
                                currentSyncIssue?.reason !in setOf(SyncIssueReason.AccountResetRequired, SyncIssueReason.AccountDataChanged) &&
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
        loadAccountResetState()
        if (state.sync.connection is SyncConnectionUi.Connected && !state.sync.accountReset.blocksSync) {
            refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
            refreshWorkspaceAdmissionLocked()
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
            currentWorkspaceAdmission = initialWorkspaceAdmission()
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
                loadCurrentWorkspaceSettings()
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
        if (state.sync.needsAccountDataResolution) {
            state = buildState(
                settings = state.settings,
                exportSummary = state.exportSummary,
                feedbackMessage = uiStrings.accountReset.resumeRequired,
                feedbackSeverity = SettingsFeedbackSeverity.Warning,
            )
            return false
        }
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
                    currentSyncIssue = SyncIssueUi(when (result.status.reason) {
                        SelfHostedSetupReason.AccountResetRequired -> SyncIssueReason.AccountResetRequired
                        SelfHostedSetupReason.AccountIncarnationMismatch -> SyncIssueReason.AccountDataChanged
                        else -> SyncIssueReason.SetupFailed
                    })
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
                if (result.status.reason in setOf(
                        SelfHostedSetupReason.AccountResetRequired, SelfHostedSetupReason.AccountIncarnationMismatch,
                    )) {
                    loadAccountResetState()
                }
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
                    refreshWorkspaceAdmissionLocked()
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
        currentSyncOperation == null && currentAccountResetOperation == null && !state.sync.accountReset.blocksSync &&
            state.sync.connection is SyncConnectionUi.Connected &&
            currentWorkspaceAdmission.state == WorkspaceAdmissionState.Ready &&
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
            failureMessage = if (result.success) null else workspaceRecoveryMessage(result.reason),
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
        currentWorkspaceRecovery = currentWorkspaceRecovery.copy(preparedCode = null, failureMessage = null)
    }

    private fun beginSync(showFeedback: Boolean, allowFirstWorkspace: Boolean = false): Boolean {
        if (currentSyncOperation != null) return false
        val connected = state.sync.connection is SyncConnectionUi.Connected
        val blockingIssue = currentSyncIssue
            ?.takeUnless { it.action == SyncIssueAction.RetrySync }
        val admissionMessage = admissionBlockingMessage(allowFirstWorkspace)
        val blockingMessage = when {
            state.sync.accountReset.blocksSync -> uiStrings.accountReset.changedBody
            !connected -> uiStrings.signInBeforeSync
            admissionMessage != null -> admissionMessage
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
        loadAccountResetState()
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
            withContext(backgroundDispatcher) { persistSyncCompletionSettings(updatedSettings) }
        }
        persistenceResult.exceptionOrNull()?.rethrowCancellation()
        val persisted = persistenceResult.getOrNull()
        if (persisted != null) {
            if (!workspaceProductAccess.isCurrent(persisted.first)) throw CancellationException("The local workspace changed.")
            currentSettingsWorkspace = persisted.first
            mutationWorkspace = persisted.first
        }
        val persistenceFailure = persistenceResult.exceptionOrNull()
        if (persistenceFailure != null && result.success) {
            currentSyncIssue = SyncIssueUi(SyncIssueReason.SyncFailed)
        }
        state = buildState(
            settings = persisted?.second ?: state.settings,
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
        refreshWorkspaceAdmissionLocked()
        return result.success && persistenceFailure == null
    }

    suspend fun runUserSync(): Boolean = runSync(userInitiated = true)

    suspend fun startFirstWorkspaceSync(): Boolean = runSync(userInitiated = true, allowFirstWorkspace = true)

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

    private suspend fun runSync(userInitiated: Boolean, allowFirstWorkspace: Boolean = false): Boolean {
        val completion = runExclusiveSyncLifecycle(SyncCompletion()) {
            runSyncLocked(userInitiated, allowFirstWorkspace)
        }
        if (completion.refreshProductData) {
            onDataRestored()
        }
        return completion.success
    }

    private suspend fun runSyncLocked(userInitiated: Boolean, allowFirstWorkspace: Boolean): SyncCompletion {
        if (!userInitiated && !canStartSync()) return SyncCompletion()
        if (!userInitiated && !preflightAutomaticSync()) return SyncCompletion()
        if (state.sync.connection is SyncConnectionUi.Connected && !state.sync.accountReset.blocksSync) {
            refreshWorkspaceAdmissionLocked()
        }
        if (userInitiated &&
            state.sync.connection is SyncConnectionUi.Connected &&
            currentWorkspaceRecovery.blocksSync &&
            workspaceRecoveryManager != null
        ) {
            refreshWorkspaceRecoveryStatusLocked(showFeedback = false)
        }
        if (!beginSync(showFeedback = userInitiated, allowFirstWorkspace = allowFirstWorkspace)) return SyncCompletion()
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

    suspend fun runLocalExport(): Boolean = runExclusiveSyncLifecycle(false) {
        val captured = currentSettingsWorkspace ?: withContext(backgroundDispatcher) { workspaceProductAccess.capture() }
        if (!workspaceProductAccess.isCurrent(captured)) throw CancellationException("The local workspace changed.")
        val result = runCatching {
            localExportRunner?.export() ?: withContext(backgroundDispatcher) {
                if (!workspaceProductAccess.isCurrent(captured)) throw CancellationException("The local workspace changed.")
                // The provider owns lifecycle/read locking; never invert those boundaries.
                LocalExportResult.Saved(exportProvider())
            }
        }.getOrElse { failure ->
            failure.rethrowCancellation()
            LocalExportResult.Failed
        }
        if (!workspaceProductAccess.isCurrent(captured)) throw CancellationException("The local workspace changed.")
        when (result) {
            is LocalExportResult.Saved -> {
                val summary = result.summary
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
            }
            LocalExportResult.Cancelled -> {
                state = buildState(state.settings, state.exportSummary)
                false
            }
            LocalExportResult.Failed -> {
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = formatUiString(uiStrings.exportFailed, uiStrings.unknownError),
                    feedbackSeverity = SettingsFeedbackSeverity.Error,
                )
                false
            }
        }
    }

    fun startDayOneImport(): Boolean {
        if (importRunning || currentAccountResetOperation != null || currentAccountResetSnapshot?.productReadOnly == true) {
            return false
        }
        val capturedWorkspace = currentSettingsWorkspace
        importRunning = true
        currentImportSummary = null
        state = buildState(
            settings = state.settings,
            exportSummary = state.exportSummary,
            feedbackMessage = uiStrings.chooseDayOne,
        )
        return runCatching {
            dayOneImportRunner.start { summary ->
                importRunning = false
                if (capturedWorkspace != null && !workspaceProductAccess.isCurrent(capturedWorkspace)) return@start
                currentImportSummary = summary
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = summary.message(uiStrings),
                    feedbackSeverity = when (summary.outcome) {
                        SettingsImportOutcome.Completed -> SettingsFeedbackSeverity.Success
                        SettingsImportOutcome.Partial -> SettingsFeedbackSeverity.Warning
                        SettingsImportOutcome.Cancelled -> SettingsFeedbackSeverity.Info
                        else -> SettingsFeedbackSeverity.Error
                    },
                )
                if (summary.hasPersistenceResult) {
                    onDataRestored()
                }
            }
        }.fold(
            onSuccess = { true },
            onFailure = { failure ->
                importRunning = false
                failure.rethrowCancellation()
                val summary = SettingsImportSummary(outcome = SettingsImportOutcome.Failed)
                currentImportSummary = summary
                state = buildState(
                    settings = state.settings,
                    exportSummary = state.exportSummary,
                    feedbackMessage = summary.message(uiStrings),
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
            currentWorkspaceAdmission.state != WorkspaceAdmissionState.Ready ||
            currentWorkspaceRecovery.availability !in setOf(
                WorkspaceRecoveryUiAvailability.NotConfigured,
                WorkspaceRecoveryUiAvailability.Configured,
            ) ||
            currentSyncOperation != null
        ) {
            return false
        }
        currentSyncOperation = SyncUiOperation.PreparingRecoveryCode
        currentWorkspaceRecovery = currentWorkspaceRecovery.copy(failureMessage = null)
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
            currentWorkspaceRecovery = currentWorkspaceRecovery.copy(
                failureMessage = if (result.success) null else workspaceRecoveryMessage(result.reason),
            )
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
        currentWorkspaceRecovery = currentWorkspaceRecovery.copy(failureMessage = null)
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
            currentWorkspaceRecovery = currentWorkspaceRecovery.copy(
                failureMessage = if (result.success) null else workspaceRecoveryMessage(result.reason),
            )
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
            currentWorkspaceRecovery = currentWorkspaceRecovery.copy(failureMessage = uiStrings.recoveryCodeRequired)
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
        currentWorkspaceRecovery = currentWorkspaceRecovery.copy(failureMessage = null)
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
                currentWorkspaceRecovery = currentWorkspaceRecovery.copy(
                    failureMessage = workspaceRecoveryMessage(result.reason),
                )
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
                loadCurrentWorkspaceSettings(adoptCommittedReplacement = true)
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
            refreshWorkspaceAdmissionLocked()
            if (currentWorkspaceRecovery.blocksSync || currentWorkspaceAdmission.state != WorkspaceAdmissionState.Ready) {
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
        if (!canUseWorkspacePairing() || currentWorkspaceAdmission.state != WorkspaceAdmissionState.Ready) return false
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
                    loadCurrentWorkspaceSettings(adoptCommittedReplacement = true)
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
                refreshWorkspaceAdmissionLocked()
                if (currentWorkspaceRecovery.blocksSync || currentWorkspaceAdmission.state != WorkspaceAdmissionState.Ready) {
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
            SelfHostedSetupReason.AccountResetRequired -> uiStrings.accountReset.resumeRequired
            SelfHostedSetupReason.AccountIncarnationMismatch -> uiStrings.selfHostedAccountDataChanged
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
            ManualSyncReason.WorkspaceJoinRequired -> uiStrings.workspaceJoinRequired
            ManualSyncReason.WorkspaceAdmissionUnavailable -> uiStrings.workspaceAdmissionUnavailable
            ManualSyncReason.RemoteHistoryConflict -> uiStrings.syncRemoteHistoryConflict
            ManualSyncReason.RetryRequired -> uiStrings.syncRetryRequired
            ManualSyncReason.Blocked -> uiStrings.syncBlocked
            ManualSyncReason.MediaTransferPending -> uiStrings.syncMediaTransferPending
            ManualSyncReason.MediaRateLimited -> uiStrings.syncMediaRateLimited
            ManualSyncReason.MediaStorageFull -> uiStrings.syncMediaStorageFull
            ManualSyncReason.MediaUnavailable -> uiStrings.syncMediaUnavailable
            ManualSyncReason.MediaPublicationFailed -> uiStrings.syncMediaPublicationFailed
            ManualSyncReason.CheckpointInvalid -> uiStrings.syncCheckpointInvalid
            ManualSyncReason.Failed -> uiStrings.syncFailed
        }

    private fun syncIssueReason(reason: ManualSyncReason): SyncIssueReason =
        when (reason) {
            ManualSyncReason.AuthorityMismatch -> SyncIssueReason.AuthorityMismatch
            ManualSyncReason.WorkspaceLocked -> SyncIssueReason.WorkspaceLocked
            ManualSyncReason.WorkspaceJoinRequired -> SyncIssueReason.WorkspaceJoinRequired
            ManualSyncReason.WorkspaceAdmissionUnavailable -> SyncIssueReason.WorkspaceAdmissionUnavailable
            ManualSyncReason.RemoteHistoryConflict -> SyncIssueReason.RemoteHistoryConflict
            ManualSyncReason.CheckpointInvalid -> SyncIssueReason.CheckpointInvalid
            ManualSyncReason.RetryRequired -> SyncIssueReason.RetryRequired
            ManualSyncReason.Blocked -> SyncIssueReason.Blocked
            ManualSyncReason.MediaTransferPending -> SyncIssueReason.MediaTransferPending
            ManualSyncReason.MediaRateLimited -> SyncIssueReason.MediaRateLimited
            ManualSyncReason.MediaStorageFull -> SyncIssueReason.MediaStorageFull
            ManualSyncReason.MediaUnavailable -> SyncIssueReason.MediaUnavailable
            ManualSyncReason.MediaPublicationFailed -> SyncIssueReason.MediaPublicationFailed
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
            SyncIssueReason.AccountResetRequired -> uiStrings.accountReset.resumeRequired
            SyncIssueReason.AccountDataChanged -> uiStrings.selfHostedAccountDataChanged
            SyncIssueReason.ConfigurationChanged -> uiStrings.syncConfigurationChanged
            SyncIssueReason.SyncUnavailable -> uiStrings.syncUnavailable
            SyncIssueReason.AuthorityMismatch -> uiStrings.syncAuthorityMismatch
            SyncIssueReason.WorkspaceLocked -> uiStrings.syncWorkspaceLocked
            SyncIssueReason.WorkspaceJoinRequired -> uiStrings.workspaceJoinRequired
            SyncIssueReason.WorkspaceAdmissionUnavailable -> uiStrings.workspaceAdmissionUnavailable
            SyncIssueReason.RemoteHistoryConflict -> uiStrings.syncRemoteHistoryConflict
            SyncIssueReason.CheckpointInvalid -> uiStrings.syncCheckpointInvalid
            SyncIssueReason.RetryRequired -> uiStrings.syncRetryRequired
            SyncIssueReason.Blocked -> uiStrings.syncBlocked
            SyncIssueReason.MediaTransferPending -> uiStrings.syncMediaTransferPending
            SyncIssueReason.MediaRateLimited -> uiStrings.syncMediaRateLimited
            SyncIssueReason.MediaStorageFull -> uiStrings.syncMediaStorageFull
            SyncIssueReason.MediaUnavailable -> uiStrings.syncMediaUnavailable
            SyncIssueReason.MediaPublicationFailed -> uiStrings.syncMediaPublicationFailed
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
            WorkspacePairingReason.VerificationFailed,
            WorkspacePairingReason.InvalidMetadata,
            WorkspacePairingReason.DecryptionFailed,
            WorkspacePairingReason.KeyVerificationFailed,
            -> uiStrings.pairingVerificationFailed
            WorkspacePairingReason.AuthorityMismatch -> uiStrings.syncAuthorityMismatch
            WorkspacePairingReason.CryptoOperationFailed,
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
            WorkspaceRecoveryReason.InvalidCodeFormat -> uiStrings.recoveryCodeFormatInvalid
            WorkspaceRecoveryReason.InvalidCode -> uiStrings.recoveryCodeInvalid
            WorkspaceRecoveryReason.InvalidRecoveryData -> uiStrings.recoveryDataInvalid
            WorkspaceRecoveryReason.DecryptionFailed -> uiStrings.recoveryDecryptionFailed
            WorkspaceRecoveryReason.KeyVerificationFailed -> uiStrings.recoveryKeyVerificationFailed
            WorkspaceRecoveryReason.CryptoOperationFailed -> uiStrings.recoveryCryptoOperationFailed
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
        if (currentAccountResetOperation != null || !settingsMutationMutex.tryLock()) return unavailable
        return try {
            block()
        } finally {
            mutationWorkspace = null
            settingsMutationMutex.unlock()
        }
    }

    private suspend fun <T> withSettingsMutation(adoptCurrentWorkspace: Boolean = false, block: suspend () -> T): T {
        val captured = if (adoptCurrentWorkspace) null else currentSettingsWorkspace
        settingsMutationMutex.lock()
        return try {
            if (captured != null && !workspaceProductAccess.isCurrent(captured)) throw CancellationException("The local workspace changed.")
            mutationWorkspace = captured
            block()
        } finally {
            mutationWorkspace = null
            settingsMutationMutex.unlock()
        }
    }

    private suspend fun loadCurrentWorkspaceSettings(adoptCommittedReplacement: Boolean = false): ClientSettings {
        val loaded = withContext(backgroundDispatcher) {
            val identity = workspaceProductAccess.capture()
            identity to runCatching { workspaceProductAccess.read(identity) { loadSettings() } }
        }
        if (!workspaceProductAccess.isCurrent(loaded.first)) throw CancellationException("The local workspace changed.")
        loaded.second.exceptionOrNull()?.rethrowCancellation()
        if (adoptCommittedReplacement) {
            // Pair/Recover has already committed this identity. A settings read failure
            // must leave retries scoped to the joined workspace, never the discarded one.
            currentSettingsWorkspace = loaded.first
            mutationWorkspace = loaded.first
        }
        val settings = loaded.second.getOrThrow()
        currentSettingsWorkspace = loaded.first
        mutationWorkspace = loaded.first
        return settings
    }

    /** Called on the IO dispatcher; queued settings cannot overwrite a replacement workspace. */
    private fun persistCurrentWorkspaceSettings(settings: ClientSettings): ClientSettings {
        val expected = mutationWorkspace ?: currentSettingsWorkspace ?: workspaceProductAccess.capture()
        return workspaceProductAccess.read(expected) { persistSettings(settings) }
    }

    /** Sync may bind this same local copy for the first time, but cannot replace its identity. */
    private fun persistSyncCompletionSettings(settings: ClientSettings): Pair<WorkspaceProductSnapshot, ClientSettings> {
        val expected = mutationWorkspace ?: currentSettingsWorkspace ?: workspaceProductAccess.capture()
        val current = workspaceProductAccess.capture()
        val firstBinding = expected.authorityBindingId == null && current.authorityBindingId != null &&
            current.workspaceId == expected.workspaceId && current.accountIncarnation == expected.accountIncarnation &&
            current.writerDeviceId == expected.writerDeviceId && current.localRevision == expected.localRevision + 1
        if (current != expected && !firstBinding) throw WorkspaceProductChangedException()
        val persisted = workspaceProductAccess.read(current) {
            // A first sync can pull settings. Save only its result onto that freshly loaded configuration.
            val updated = if (firstBinding) {
                val loaded = loadSettings()
                loaded.copy(syncConfiguration = loaded.syncConfiguration.copy(lastError = settings.syncConfiguration.lastError))
            } else {
                settings
            }
            persistSettings(updated)
        }
        return current to persisted
    }

    /** Caller must hold [settingsMutationMutex]. */
    private suspend fun persistLocked(
        updated: ClientSettings,
        successMessage: String,
        successSeverity: SettingsFeedbackSeverity = SettingsFeedbackSeverity.Success,
    ): Boolean =
        runCatching {
            withContext(backgroundDispatcher) { persistCurrentWorkspaceSettings(updated) }
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
                admission = currentWorkspaceAdmission,
                accountReset = AccountResetUiState(accountDataResetManager != null, currentAccountResetSnapshot, currentAccountResetOperation),
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

private data class AccountResetCompletion(
    val result: AccountDataResetActionResult,
    val settings: ClientSettings?,
    val workspace: WorkspaceProductSnapshot?,
    val sessionAccess: SecureSessionAccess?,
)

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
    if (this is WorkspaceProductChangedException) throw CancellationException("The local workspace changed.")
}

private fun syncIssueFromLastError(lastError: String?): SyncIssueUi? {
    val marker = lastError?.substringAfter(':', missingDelimiterValue = "")
        ?.takeIf(String::isNotBlank)
        ?: return null
    val reason = if (lastError.startsWith("setup:")) {
        when (marker) {
            SelfHostedSetupReason.AccountResetRequired.name -> SyncIssueReason.AccountResetRequired
            SelfHostedSetupReason.AccountIncarnationMismatch.name -> SyncIssueReason.AccountDataChanged
            else -> SyncIssueReason.SetupFailed
        }
    } else {
        when (marker) {
            ManualSyncReason.AuthorityMismatch.name -> SyncIssueReason.AuthorityMismatch
            ManualSyncReason.WorkspaceLocked.name -> SyncIssueReason.WorkspaceLocked
            ManualSyncReason.WorkspaceJoinRequired.name -> SyncIssueReason.WorkspaceJoinRequired
            ManualSyncReason.WorkspaceAdmissionUnavailable.name -> SyncIssueReason.WorkspaceAdmissionUnavailable
            ManualSyncReason.RemoteHistoryConflict.name -> SyncIssueReason.RemoteHistoryConflict
            ManualSyncReason.CheckpointInvalid.name -> SyncIssueReason.CheckpointInvalid
            ManualSyncReason.RetryRequired.name -> SyncIssueReason.RetryRequired
            ManualSyncReason.Blocked.name -> SyncIssueReason.Blocked
            ManualSyncReason.MediaTransferPending.name -> SyncIssueReason.MediaTransferPending
            ManualSyncReason.MediaRateLimited.name -> SyncIssueReason.MediaRateLimited
            ManualSyncReason.MediaStorageFull.name -> SyncIssueReason.MediaStorageFull
            ManualSyncReason.MediaUnavailable.name -> SyncIssueReason.MediaUnavailable
            ManualSyncReason.MediaPublicationFailed.name -> SyncIssueReason.MediaPublicationFailed
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

enum class SettingsImportOutcome { Completed, Partial, Failed, Cancelled, Unavailable }

data class SettingsImportSummary(
    val outcome: SettingsImportOutcome,
    val journalsImported: Int = 0,
    val notebooksCreated: Int = 0,
    val notebooksReused: Int = 0,
    val notesCreated: Int = 0,
    val notesUpdated: Int = 0,
    val notesSkipped: Int = 0,
    val richTextConverted: Int = 0,
    val photosImported: Int = 0,
    val photosMissing: Int = 0,
    val photosUnresolved: Int = 0,
    val photosRejected: Int = 0,
    val otherMedia: Int = 0,
    val unsupportedItems: Int = 0,
) {
    val notesImported: Int = notesCreated + notesUpdated
    val hasPersistenceResult: Boolean
        get() = outcome == SettingsImportOutcome.Completed || outcome == SettingsImportOutcome.Partial

    fun message(strings: SettingsUiStrings): String = when (outcome) {
        SettingsImportOutcome.Completed -> strings.dayOneImportCompleted
        SettingsImportOutcome.Partial -> strings.dayOneImportPartial
        SettingsImportOutcome.Failed -> strings.dayOneImportFailed
        SettingsImportOutcome.Cancelled -> strings.dayOneImportCancelled
        SettingsImportOutcome.Unavailable -> strings.dayOneImportUnavailable
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
