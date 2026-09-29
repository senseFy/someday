package saien.someday.ui.settings

import saien.someday.domain.settings.AccountDataResetIssue
import saien.someday.domain.settings.AccountDataResetPhase
import saien.someday.domain.settings.AccountDataResetSnapshot
import saien.someday.ui.i18n.AccountResetUiStrings

enum class AccountResetUiOperation { Loading, Refreshing, Submitting, Authenticating, Reconciling, KeepingOffline, Replacing }

data class AccountResetUiState(
    val supported: Boolean = false,
    val snapshot: AccountDataResetSnapshot? = null,
    val operation: AccountResetUiOperation? = null,
) {
    val busy: Boolean get() = operation != null
    val blocksSync: Boolean get() = snapshot?.let {
        it.productReadOnly || it.offlineEditing || it.phase in setOf(
            AccountDataResetPhase.ResetRequired, AccountDataResetPhase.OutcomeUnknown,
            AccountDataResetPhase.RemoteCommittedLocalPending,
        )
    } == true
}

internal fun AccountDataResetIssue.message(strings: AccountResetUiStrings): String = when (this) {
    AccountDataResetIssue.Unavailable, AccountDataResetIssue.UnsupportedServer,
    AccountDataResetIssue.DeploymentNotReady -> strings.unavailable
    AccountDataResetIssue.RetiredMediaPending -> strings.retainedMedia
    AccountDataResetIssue.SignInRequired, AccountDataResetIssue.DeviceRevoked -> strings.signInRequired
    AccountDataResetIssue.WrongPassword -> strings.wrongPassword
    AccountDataResetIssue.Busy, AccountDataResetIssue.RateLimited -> strings.busy
    AccountDataResetIssue.LocalFailure -> strings.failed
    AccountDataResetIssue.ReplacementFailed -> strings.localFailure
    AccountDataResetIssue.ConfirmationRequired -> strings.localConsentRequired
    AccountDataResetIssue.InvalidSecret -> strings.failed
    AccountDataResetIssue.NoRecoveryEnvelope, AccountDataResetIssue.Conflict,
    AccountDataResetIssue.ProtocolError, AccountDataResetIssue.NetworkError,
    AccountDataResetIssue.ContextChanged -> strings.failed
}
