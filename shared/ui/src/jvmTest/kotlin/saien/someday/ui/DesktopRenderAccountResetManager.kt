package saien.someday.ui

import saien.someday.domain.settings.AccountDataReplacementMode
import saien.someday.domain.settings.AccountDataResetActionResult
import saien.someday.domain.settings.AccountDataResetIssue
import saien.someday.domain.settings.AccountDataResetManager
import saien.someday.domain.settings.AccountDataResetPhase
import saien.someday.domain.settings.AccountDataResetReview
import saien.someday.domain.settings.AccountDataResetSnapshot
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.selfHostedAuthorityBindingId

/** Render-only test data. This class never performs network or workspace mutations. */
internal class DesktopRenderAccountResetManager(scenario: String) : AccountDataResetManager {
    var refreshCalls = 0
        private set
    var submitCalls = 0
        private set
    var reconcileCalls = 0
        private set
    var authenticateCalls = 0
        private set
    var lastAuthenticationEmail: String? = null
        private set
    var lastAuthenticationReview: AccountDataResetReview? = null
        private set
    var replacementCalls = 0
        private set
    var commitOnSubmit = false
    var nextAuthenticationIssue: AccountDataResetIssue? = null
    var nextReplacementIssue: AccountDataResetIssue? = AccountDataResetIssue.LocalFailure
    private val scenarioName = scenario.removeSuffix("-auth-required")
    private val discoverOnRefresh = scenarioName == "undiscovered"
    private val endpoint = "http://127.0.0.1:18080"
    private val email = if (scenarioName == "committed-missing-email") "" else "reset-review@example.test"
    private val authority = selfHostedAuthorityBindingId(endpoint, "10000000-0000-0000-0000-000000000001")
    private val operation = "20000000-0000-0000-0000-000000000002"
    private val target = "30000000-0000-0000-0000-000000000003"
    private val initialPhase = when (scenarioName) {
        "ready", "undiscovered" -> AccountDataResetPhase.Ready
        "unavailable" -> AccountDataResetPhase.Unavailable
        "unknown", "offline" -> AccountDataResetPhase.OutcomeUnknown
        "committed", "committed-missing-email", "local-failure" -> AccountDataResetPhase.RemoteCommittedLocalPending
        "reset-required" -> AccountDataResetPhase.ResetRequired
        else -> error("Unknown isolated reset render scenario: $scenario")
    }
    private val review = AccountDataResetReview(
        id = "isolated-review", authorityBindingId = authority, endpoint = endpoint, accountEmail = email,
        workspaceId = "workspace-11111111111111111111111111111111",
        writerDeviceId = "40000000-0000-0000-0000-000000000004",
        localIncarnation = INITIAL_ACCOUNT_INCARNATION, localAuthorityBindingId = authority,
        targetIncarnation = if (initialPhase == AccountDataResetPhase.Ready) INITIAL_ACCOUNT_INCARNATION else target,
        operationId = if (initialPhase in listOf(AccountDataResetPhase.OutcomeUnknown, AccountDataResetPhase.RemoteCommittedLocalPending)) operation else null,
    )
    private var snapshot = AccountDataResetSnapshot(
        phase = initialPhase, review = review, endpoint = endpoint, accountEmail = email,
        operationId = review.operationId, offlineEditing = scenarioName == "offline",
        productReadOnly = initialPhase !in listOf(AccountDataResetPhase.Ready, AccountDataResetPhase.Unavailable) && scenarioName != "offline",
        resetAvailable = initialPhase == AccountDataResetPhase.Ready && !discoverOnRefresh, canExport = true,
        canReplaceLocal = scenarioName != "committed-missing-email" && initialPhase in listOf(AccountDataResetPhase.RemoteCommittedLocalPending, AccountDataResetPhase.ResetRequired),
        issue = AccountDataResetIssue.LocalFailure.takeIf { scenarioName == "local-failure" },
        requiresAuthentication = scenario.endsWith("-auth-required") || scenarioName == "committed-missing-email",
    )

    override fun load() = snapshot
    override fun refresh(): AccountDataResetActionResult {
        refreshCalls++
        if (discoverOnRefresh) {
            snapshot = snapshot.copy(resetAvailable = true, review = review.copy(id = "refreshed-review"))
        }
        return result()
    }
    override fun reconcile(review: AccountDataResetReview): AccountDataResetActionResult {
        reconcileCalls++
        return result()
    }
    override fun reauthenticate(review: AccountDataResetReview, password: String, email: String): AccountDataResetActionResult {
        authenticateCalls++
        lastAuthenticationEmail = email
        lastAuthenticationReview = review
        nextAuthenticationIssue?.let {
            snapshot = snapshot.copy(issue = it, requiresAuthentication = true)
            return AccountDataResetActionResult(snapshot, false)
        }
        snapshot = snapshot.copy(issue = null, requiresAuthentication = false, accountEmail = email,
            review = review.copy(id = "authenticated-$authenticateCalls", accountEmail = email),
            canReplaceLocal = snapshot.phase in listOf(AccountDataResetPhase.RemoteCommittedLocalPending, AccountDataResetPhase.ResetRequired))
        return result()
    }
    override fun submit(review: AccountDataResetReview, password: String): AccountDataResetActionResult {
        submitCalls++
        snapshot = snapshot.copy(phase = if (commitOnSubmit) AccountDataResetPhase.RemoteCommittedLocalPending else AccountDataResetPhase.OutcomeUnknown,
            resetAvailable = false, productReadOnly = true, operationId = operation,
            review = review.copy(id = "submitted-review", operationId = operation, targetIncarnation = target),
            canReplaceLocal = commitOnSubmit, requiresAuthentication = false,
            resetUnavailableIssue = AccountDataResetIssue.RetiredMediaPending.takeIf { commitOnSubmit })
        return AccountDataResetActionResult(snapshot, commitOnSubmit)
    }
    override fun keepOffline(review: AccountDataResetReview): AccountDataResetActionResult {
        snapshot = snapshot.copy(offlineEditing = true, productReadOnly = false)
        return result()
    }
    override fun replaceLocal(review: AccountDataResetReview, mode: AccountDataReplacementMode, discardConfirmed: Boolean,
        secret: String, password: String?): AccountDataResetActionResult {
        check(discardConfirmed)
        replacementCalls++
        nextReplacementIssue?.let {
            snapshot = snapshot.copy(issue = it)
            return AccountDataResetActionResult(snapshot, false)
        }
        snapshot = snapshot.copy(phase = AccountDataResetPhase.LocalReady, productReadOnly = false, offlineEditing = false,
            operationId = null, canReplaceLocal = false, issue = null,
            review = review.copy(id = "replaced-review", operationId = null, workspaceId = "workspace-22222222222222222222222222222222"))
        return AccountDataResetActionResult(snapshot, true, localReplaced = true)
    }
    override fun cancel() {
        if (snapshot.phase in listOf(AccountDataResetPhase.RemoteCommittedLocalPending, AccountDataResetPhase.OutcomeUnknown, AccountDataResetPhase.ResetRequired)) {
            snapshot = snapshot.copy(requiresAuthentication = true, canReplaceLocal = false,
                review = snapshot.review?.copy(id = "cancelled-review"))
        }
    }
    private fun result() = AccountDataResetActionResult(snapshot, true)
}
