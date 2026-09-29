package saien.someday.sync.selfhosted

import kotlinx.coroutines.CancellationException
import saien.someday.data.account.AccountResetIntent
import saien.someday.data.account.AccountResetIntentState
import saien.someday.data.account.AccountResetSubmission
import saien.someday.data.account.SqlDelightAccountStateRepository
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId
import saien.someday.domain.settings.parseSelfHostedAuthorityBindingId
import saien.someday.domain.settings.selfHostedAuthorityBindingId
import saien.someday.sync.WorkspaceLifecycleCoordinator

/** The exact local copy presented for confirmation, independently of account login. */
data class AccountResetLocalWorkspace(
    val workspaceId: String,
    val writerDeviceId: String,
    val authorityBindingId: String?,
    val accountIncarnation: String = INITIAL_ACCOUNT_INCARNATION,
)

sealed interface SelfHostedAccountResetResult {
    data class Committed(val receipt: SelfHostedAccountResetReceiptResponse, val locallyRecorded: Boolean) : SelfHostedAccountResetResult
    data class OutcomeUnknown(val operationId: String) : SelfHostedAccountResetResult
    data class Rejected(val errorCode: SelfHostedErrorCode, val localGateRetained: Boolean) : SelfHostedAccountResetResult
}

/**
 * Off-main use case. No password, request body or token enters SQL state, and no
 * lifecycle lock spans HTTP. A receipt alone never authorizes local replacement.
 */
class SelfHostedAccountResetService(
    private val states: SqlDelightAccountStateRepository,
    private val sessionStore: SelfHostedSessionCredentialStore,
    private val transport: SelfHostedAccountControlTransport,
    private val discovery: SelfHostedAccountDiscoveryService,
    private val lifecycle: WorkspaceLifecycleCoordinator,
    private val captureWorkspace: () -> AccountResetLocalWorkspace,
) {
    private var inFlightOperation: String? = null
    // Accessed only under lifecycle. A later offline decision or confirmation
    // invalidates an earlier discovery response, even for the same workspace.
    private var localDiscardAttempt: Any? = null

    fun pendingIntent(): AccountResetIntent? = states.loadIntent()

    fun localWorkspace(): AccountResetLocalWorkspace = lifecycle.productAccess(captureWorkspace)

    fun submit(
        operationId: String,
        password: String,
        expectedIncarnation: String,
        confirmedAuthorityBindingId: String,
        confirmedWorkspace: AccountResetLocalWorkspace,
        authenticatedCredentials: SelfHostedSessionCredentials? = null,
    ): SelfHostedAccountResetResult {
        val authority = requireNotNull(parseSelfHostedAuthorityBindingId(confirmedAuthorityBindingId))
        val selected = checkNotNull((authenticatedCredentials ?: sessionStore.load())?.takeIf { it.authorityBindingId == confirmedAuthorityBindingId }) {
            "The confirmed account selection changed."
        }
        discovery.recordIssuance(selected)
        if (!states.hasProtocol1(authority.endpoint, authority.authenticatedUserId) &&
            discovery.discover(selected) !is SelfHostedAccountDiscoveryResult.Protocol1
        ) return SelfHostedAccountResetResult.Rejected(SelfHostedErrorCode.ACCOUNT_PROTOCOL_UPGRADE_REQUIRED, localGateRetained = false)
        val attempt = lifecycle.exclusive {
            lifecycle.productAccess {
                check(inFlightOperation == null) { "A reset request is already in flight." }
                check(captureWorkspace() == confirmedWorkspace) { "The confirmed local workspace changed." }
                check(sessionStore.load()?.authorityBindingId?.let { it == confirmedAuthorityBindingId } ?: (authenticatedCredentials != null)) { "The confirmed account selection changed." }
                val credentials = authenticatedCredentials ?: credentialsFor(confirmedAuthorityBindingId)
                require(confirmedWorkspace.authorityBindingId == null || confirmedWorkspace.authorityBindingId == confirmedAuthorityBindingId)
                val intent = states.loadIntent()
                if (intent == null) {
                    states.persistIntent(AccountResetIntent(
                        operationId = operationId,
                        endpoint = authority.endpoint,
                        userId = authority.authenticatedUserId,
                        expectedIncarnation = expectedIncarnation,
                        originalWorkspaceId = confirmedWorkspace.workspaceId,
                        originalWriterId = confirmedWorkspace.writerDeviceId,
                        originalIncarnation = confirmedWorkspace.accountIncarnation,
                    ))
                } else {
                    require(intent.operationId == operationId && intent.expectedIncarnation == expectedIncarnation &&
                        selfHostedAuthorityBindingId(intent.endpoint, intent.userId) == confirmedAuthorityBindingId &&
                        intent.originalWorkspaceId == confirmedWorkspace.workspaceId && intent.originalWriterId == confirmedWorkspace.writerDeviceId &&
                        intent.originalIncarnation == confirmedWorkspace.accountIncarnation)
                    check(intent.state != AccountResetIntentState.RemoteCommittedLocalPending) { "The reset is already committed." }
                }
                val submission = states.markSubmitted(operationId)
                val prepared = ResetAttempt(credentials, requireNotNull(states.loadIntent()), submission)
                inFlightOperation = operationId
                prepared
            }
        }
        try {
            val receipt = try {
                // An explicit retry is the same operation. Never refresh/replay a
                // password-bearing POST automatically after any authentication error.
                transport.resetAccountData(
                    attempt.credentials.endpoint, attempt.credentials.accessToken,
                    SelfHostedAccountResetRequest(operationId = operationId, expectedIncarnation = expectedIncarnation, password = password),
                    SelfHostedAccountRequestContext(expectedIncarnation, protocol1Known = true),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled // The pre-POST intent already records uncertainty.
            } catch (failure: SelfHostedSyncHttpException) {
                return handleRejection(attempt, failure)
            } catch (_: Exception) {
                return SelfHostedAccountResetResult.OutcomeUnknown(operationId)
            }
            return recordReceipt(attempt.intent, receipt)
        } finally {
            lifecycle.exclusive { if (inFlightOperation == operationId) inFlightOperation = null }
        }
    }

    /** A missing receipt, even together with unchanged discovery, never proves rollback. */
    fun reconcile(operationId: String, authenticatedCredentials: SelfHostedSessionCredentials? = null): SelfHostedAccountResetResult {
        val intent = checkNotNull(states.loadIntent()?.takeIf { it.operationId == operationId })
        val authority = selfHostedAuthorityBindingId(intent.endpoint, intent.userId)
        val credentials = authenticatedCredentials?.also { require(it.authorityBindingId == authority) } ?: credentialsFor(authority)
        val receipt = try {
            transport.getAccountResetReceipt(
                intent.endpoint, credentials.accessToken, operationId,
                credentials.accountRequestContext().copy(protocol1Known = true),
            )
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.protocol1) states.markProtocol1(intent.endpoint, intent.userId)
            throw failure
        } ?: return SelfHostedAccountResetResult.OutcomeUnknown(operationId)
        return recordReceipt(intent, receipt)
    }

    fun keepCopyAndEditOffline(operationId: String) = lifecycle.exclusive {
        lifecycle.productAccess {
            val intent = checkNotNull(states.loadIntent()?.takeIf { it.operationId == operationId })
            val workspace = captureWorkspace()
            check(workspace.workspaceId == intent.originalWorkspaceId && workspace.writerDeviceId == intent.originalWriterId)
            states.enterOfflineMode(intent.endpoint, intent.userId, intent.originalWorkspaceId)
            localDiscardAttempt = null
        }
    }

    /** Fresh consent after reconciliation; offline editing invalidates any earlier consent. */
    fun confirmLocalReplacement(operationId: String, confirmedWorkspace: AccountResetLocalWorkspace, targetIncarnation: String) {
        val (intent, confirmationAttempt) = lifecycle.exclusive {
            lifecycle.productAccess {
                val pending = checkNotNull(states.loadIntent()?.takeIf { it.operationId == operationId && it.receiptIncarnation != null })
                check(captureWorkspace() == confirmedWorkspace)
                check(confirmedWorkspace.workspaceId == pending.originalWorkspaceId &&
                    confirmedWorkspace.writerDeviceId == pending.originalWriterId &&
                    confirmedWorkspace.accountIncarnation == pending.originalIncarnation &&
                    (confirmedWorkspace.authorityBindingId == null || confirmedWorkspace.authorityBindingId == selfHostedAuthorityBindingId(pending.endpoint, pending.userId)))
                // The product mutation boundary observes this durable read-only
                // state before HTTP. Offline edits cannot race accepted consent.
                states.freezeForLocalDiscardConfirmation(operationId)
                val attempt = Any()
                localDiscardAttempt = attempt
                pending to attempt
            }
        }
        val credentials = credentialsFor(selfHostedAuthorityBindingId(intent.endpoint, intent.userId))
        val current = discovery.discover(credentials) as? SelfHostedAccountDiscoveryResult.Protocol1
            ?: throw SelfHostedProtocolException(SelfHostedProtocolFailureReason.UNVERIFIED_LEGACY)
        check(current.state.accountIncarnation == targetIncarnation) { "The proposed replacement incarnation changed." }
        lifecycle.exclusive {
            lifecycle.productAccess {
                check(localDiscardAttempt === confirmationAttempt) { "The local discard decision changed." }
                check(captureWorkspace() == confirmedWorkspace)
                states.recordLocalDiscardConsent(operationId, confirmedWorkspace.workspaceId, targetIncarnation)
                localDiscardAttempt = null
            }
        }
    }

    private fun credentialsFor(authority: String): SelfHostedSessionCredentials =
        (sessionStore.load()?.takeIf { it.authorityBindingId == authority } ?: sessionStore.loadForAuthority(authority))
            ?.also { check(it.authorityBindingId == authority) }
            ?: error("Current account authentication is required; credentials redacted.")

    private fun recordReceipt(intent: AccountResetIntent, receipt: SelfHostedAccountResetReceiptResponse): SelfHostedAccountResetResult.Committed {
        SelfHostedAccountWire.validateReceipt(receipt, intent.operationId)
        if (receipt.previousIncarnation != intent.expectedIncarnation) {
            throw SelfHostedProtocolException(SelfHostedProtocolFailureReason.INVALID_CONTROL_RESPONSE)
        }
        val recorded = try {
            lifecycle.exclusive {
                states.markProtocol1(intent.endpoint, intent.userId)
                states.recordCommittedReceipt(intent.operationId, receipt.newIncarnation, receipt.committedAtEpochMillis)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false // Remote completion survives a failed local write; old data stays gated.
        }
        return SelfHostedAccountResetResult.Committed(receipt, recorded)
    }

    private fun handleRejection(attempt: ResetAttempt, failure: SelfHostedSyncHttpException): SelfHostedAccountResetResult {
        val intent = attempt.intent
        if (failure.protocol1) states.markProtocol1(intent.endpoint, intent.userId)
        if (failure.errorCode in INCARNATION_FAILURES) {
            lifecycle.exclusive { states.markResetRequired(intent.endpoint, intent.userId, intent.originalWorkspaceId, intent.originalIncarnation) }
        }
        if (failure.errorCode !in DEFINITE_REJECTIONS) return SelfHostedAccountResetResult.OutcomeUnknown(intent.operationId)
        val current = try {
            discovery.discover(attempt.credentials) as? SelfHostedAccountDiscoveryResult.Protocol1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val cleared = current != null && lifecycle.exclusive {
            lifecycle.productAccess {
                if (current.state.accountIncarnation != intent.originalIncarnation) {
                    states.markResetRequired(intent.endpoint, intent.userId, intent.originalWorkspaceId, intent.originalIncarnation)
                }
                states.clearDefinitiveRejection(attempt.submission, current.state.accountIncarnation)
            }
        }
        return SelfHostedAccountResetResult.Rejected(requireNotNull(failure.errorCode), localGateRetained = !cleared ||
            states.loadGate(intent.endpoint, intent.userId, intent.originalWorkspaceId) != null)
    }

    private class ResetAttempt(val credentials: SelfHostedSessionCredentials, val intent: AccountResetIntent, val submission: AccountResetSubmission)

    private companion object {
        val INCARNATION_FAILURES = setOf(SelfHostedErrorCode.ACCOUNT_SESSION_STALE, SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED)
        val DEFINITE_REJECTIONS = setOf(
            SelfHostedErrorCode.UNAUTHORIZED, SelfHostedErrorCode.FORBIDDEN, SelfHostedErrorCode.INVALID_CREDENTIALS,
            SelfHostedErrorCode.INVALID_REQUEST, SelfHostedErrorCode.REQUEST_BODY_TOO_LARGE,
            SelfHostedErrorCode.DEVICE_REVOKED, SelfHostedErrorCode.ACCOUNT_SESSION_STALE,
            SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, SelfHostedErrorCode.ACCOUNT_PROTOCOL_UPGRADE_REQUIRED,
            SelfHostedErrorCode.RESET_REQUEST_CONFLICT, SelfHostedErrorCode.RETIRED_MEDIA_PENDING,
            SelfHostedErrorCode.ACCOUNT_RESET_UNAVAILABLE, SelfHostedErrorCode.ACCOUNT_BUSY,
            SelfHostedErrorCode.AUTHENTICATION_BUSY, SelfHostedErrorCode.RATE_LIMITED, SelfHostedErrorCode.RESET_RATE_LIMITED,
        )
    }
}
