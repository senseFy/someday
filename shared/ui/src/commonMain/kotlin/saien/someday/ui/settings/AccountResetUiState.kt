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
    AccountDataResetIssue.SignInRequired -> strings.signInRequired
    AccountDataResetIssue.DeviceRevoked -> strings.deviceRevoked
    AccountDataResetIssue.WrongPassword -> strings.wrongPassword
    AccountDataResetIssue.Busy -> strings.busy
    AccountDataResetIssue.RateLimited -> strings.rateLimited
    AccountDataResetIssue.LocalFailure -> strings.failed
    AccountDataResetIssue.ReplacementFailed -> strings.localFailure
    AccountDataResetIssue.ConfirmationRequired -> strings.localConsentRequired
    AccountDataResetIssue.AccountMismatch -> strings.accountMismatch
    AccountDataResetIssue.InvalidSecret -> strings.invalidSecret
    AccountDataResetIssue.InvitationUnavailable -> strings.invitationUnavailable
    AccountDataResetIssue.InvitationAlreadyUsed -> strings.invitationUsed
    AccountDataResetIssue.NoRecoveryEnvelope -> strings.noRecovery
    AccountDataResetIssue.NetworkError -> strings.networkError
    AccountDataResetIssue.ContextChanged -> strings.contextChanged
    AccountDataResetIssue.Conflict -> strings.contextChanged
    AccountDataResetIssue.ProtocolError -> strings.protocolError
}
