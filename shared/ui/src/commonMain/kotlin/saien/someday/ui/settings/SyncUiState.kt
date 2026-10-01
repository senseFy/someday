package saien.someday.ui.settings

import saien.someday.domain.settings.WorkspaceRecoverySyncGate
import saien.someday.domain.settings.WorkspaceAdmissionState
import saien.someday.domain.settings.WorkspaceAdmissionStatus

/** Product-facing connection state. Protocol and transport details stay below this boundary. */
sealed interface SyncConnectionUi {
    data class LocalOnly(
        val configuredEndpoint: String? = null,
    ) : SyncConnectionUi

    data class Connected(
        val endpoint: String?,
        val accountEmail: String?,
        val deviceLabel: String,
    ) : SyncConnectionUi

    /** A persisted session exists, but its secure credentials cannot currently be trusted. */
    data class Unavailable(
        val configuredEndpoint: String?,
        val accountEmail: String?,
        val deviceLabel: String,
    ) : SyncConnectionUi
}

enum class SyncUiOperation {
    Authenticating,
    CreatingAccount,
    SwitchingConnection,
    ReloadingSession,
    CheckingRecovery,
    CheckingWorkspace,
    Syncing,
    CreatingInvitation,
    CancellingInvitation,
    JoiningInvitation,
    PreparingRecoveryCode,
    PublishingRecoveryCode,
    RestoringWorkspace,
}

enum class WorkspaceRecoveryUiAvailability {
    Unknown,
    NotConfigured,
    Configured,
    RecoveryAvailable,
    Unavailable,
}

data class WorkspaceRecoveryUiState(
    val availability: WorkspaceRecoveryUiAvailability = WorkspaceRecoveryUiAvailability.Unknown,
    val syncGate: WorkspaceRecoverySyncGate = WorkspaceRecoverySyncGate.Pending,
    val preparedCode: WorkspaceRecoveryCodeUi? = null,
    val failureMessage: String? = null,
) {
    val blocksSync: Boolean
        get() = syncGate != WorkspaceRecoverySyncGate.Allowed
}

enum class SyncIssueAction {
    RetrySync,
    ReloadSession,
    Reauthenticate,
}

enum class SyncIssueReason {
    SignInRequired,
    SecureSessionUnavailable,
    SetupFailed,
    AccountResetRequired,
    AccountDataChanged,
    ConfigurationChanged,
    SyncUnavailable,
    AuthorityMismatch,
    WorkspaceLocked,
    WorkspaceJoinRequired,
    WorkspaceAdmissionUnavailable,
    RemoteHistoryConflict,
    CheckpointInvalid,
    RetryRequired,
    Blocked,
    MediaTransferPending,
    MediaRateLimited,
    MediaStorageFull,
    MediaUnavailable,
    MediaPublicationFailed,
    SyncFailed,
    WorkspaceSettingsReloadRequired,
}

data class SyncIssueUi(
    val reason: SyncIssueReason,
) {
    val action: SyncIssueAction?
        get() = when (reason) {
            SyncIssueReason.SignInRequired,
            SyncIssueReason.SetupFailed,
            SyncIssueReason.ConfigurationChanged,
            SyncIssueReason.AuthorityMismatch,
            -> SyncIssueAction.Reauthenticate
            SyncIssueReason.SecureSessionUnavailable -> SyncIssueAction.ReloadSession
            SyncIssueReason.WorkspaceLocked,
            SyncIssueReason.RetryRequired,
            SyncIssueReason.Blocked,
            SyncIssueReason.MediaTransferPending,
            SyncIssueReason.MediaRateLimited,
            SyncIssueReason.MediaStorageFull,
            SyncIssueReason.MediaUnavailable,
            SyncIssueReason.MediaPublicationFailed,
            SyncIssueReason.SyncFailed,
            SyncIssueReason.WorkspaceSettingsReloadRequired,
            -> SyncIssueAction.RetrySync
            SyncIssueReason.AccountResetRequired,
            SyncIssueReason.WorkspaceJoinRequired,
            SyncIssueReason.WorkspaceAdmissionUnavailable,
            SyncIssueReason.AccountDataChanged,
            SyncIssueReason.RemoteHistoryConflict,
            SyncIssueReason.CheckpointInvalid,
            SyncIssueReason.SyncUnavailable,
            -> null
        }
}

data class SyncUiState(
    val connection: SyncConnectionUi,
    val operation: SyncUiOperation? = null,
    val issue: SyncIssueUi? = null,
    val invitation: WorkspacePairingInvitationUi? = null,
    val recovery: WorkspaceRecoveryUiState = WorkspaceRecoveryUiState(),
    val admission: WorkspaceAdmissionStatus = WorkspaceAdmissionStatus(WorkspaceAdmissionState.Ready),
    val accountReset: AccountResetUiState = AccountResetUiState(),
) {
    val needsAccountDataResolution: Boolean
        get() = accountReset.blocksSync || issue?.reason in setOf(
            SyncIssueReason.AccountResetRequired, SyncIssueReason.AccountDataChanged,
        )
    val syncing: Boolean get() = operation == SyncUiOperation.Syncing
    val busy: Boolean get() = operation != null || accountReset.busy
    val workspaceReady: Boolean get() = admission.state == WorkspaceAdmissionState.Ready
    val joiningWorkspace: Boolean get() = admission.state == WorkspaceAdmissionState.JoinRequired
    val pairingAvailable: Boolean
        get() = !accountReset.blocksSync && connection is SyncConnectionUi.Connected && when (issue?.reason) {
            null,
            SyncIssueReason.WorkspaceLocked,
            SyncIssueReason.WorkspaceJoinRequired,
            SyncIssueReason.WorkspaceAdmissionUnavailable,
            SyncIssueReason.RemoteHistoryConflict,
            SyncIssueReason.CheckpointInvalid,
            SyncIssueReason.RetryRequired,
            SyncIssueReason.Blocked,
            SyncIssueReason.MediaTransferPending,
            SyncIssueReason.MediaRateLimited,
            SyncIssueReason.MediaStorageFull,
            SyncIssueReason.MediaUnavailable,
            SyncIssueReason.MediaPublicationFailed,
            SyncIssueReason.SyncFailed,
            SyncIssueReason.WorkspaceSettingsReloadRequired,
            -> true
            else -> false
        }
}

internal enum class SyncAccountFormMode(
    val serverReadOnly: Boolean,
    val emailReadOnly: Boolean,
    val allowCreateAccount: Boolean,
    val allowManualReauthentication: Boolean,
    val initiallyVisible: Boolean,
) {
    InitialSetup(
        serverReadOnly = false,
        emailReadOnly = false,
        allowCreateAccount = true,
        allowManualReauthentication = false,
        initiallyVisible = true,
    ),
    BoundSession(
        serverReadOnly = true,
        emailReadOnly = true,
        allowCreateAccount = false,
        allowManualReauthentication = true,
        initiallyVisible = false,
    ),
    AuthorityRecovery(
        serverReadOnly = true,
        emailReadOnly = false,
        allowCreateAccount = false,
        allowManualReauthentication = true,
        initiallyVisible = false,
    ),
    AccountResetPending(
        serverReadOnly = true,
        emailReadOnly = true,
        allowCreateAccount = false,
        allowManualReauthentication = false,
        initiallyVisible = false,
    ),
    SessionUnavailable(
        serverReadOnly = true,
        emailReadOnly = true,
        allowCreateAccount = false,
        allowManualReauthentication = false,
        initiallyVisible = false,
    ),
    MissingCredentials(
        serverReadOnly = true,
        emailReadOnly = false,
        allowCreateAccount = false,
        allowManualReauthentication = false,
        initiallyVisible = true,
    ),
}

internal fun SyncUiState.accountFormMode(): SyncAccountFormMode =
    if (needsAccountDataResolution) {
        SyncAccountFormMode.AccountResetPending
    } else if (issue?.action == SyncIssueAction.ReloadSession) {
        SyncAccountFormMode.SessionUnavailable
    } else {
        when (val currentConnection = connection) {
            is SyncConnectionUi.Connected,
            is SyncConnectionUi.Unavailable,
            -> if (issue?.action == SyncIssueAction.Reauthenticate) {
                SyncAccountFormMode.AuthorityRecovery
            } else {
                SyncAccountFormMode.BoundSession
            }
            is SyncConnectionUi.LocalOnly -> if (!currentConnection.configuredEndpoint.isNullOrBlank()) {
                SyncAccountFormMode.MissingCredentials
            } else {
                SyncAccountFormMode.InitialSetup
            }
        }
    }
