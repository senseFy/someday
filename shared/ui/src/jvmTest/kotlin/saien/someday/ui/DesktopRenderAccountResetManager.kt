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
    private val endpoint = "http://127.0.0.1:18080"
    private val email = "reset-review@example.test"
    private val authority = selfHostedAuthorityBindingId(endpoint, "10000000-0000-0000-0000-000000000001")
    private val operation = "20000000-0000-0000-0000-000000000002"
    private val target = "30000000-0000-0000-0000-000000000003"
    private val initialPhase = when (scenario) {
        "ready" -> AccountDataResetPhase.Ready
        "unknown", "offline" -> AccountDataResetPhase.OutcomeUnknown
        "committed", "local-failure" -> AccountDataResetPhase.RemoteCommittedLocalPending
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
        operationId = review.operationId, offlineEditing = scenario == "offline",
        productReadOnly = initialPhase != AccountDataResetPhase.Ready && scenario != "offline",
        resetAvailable = initialPhase == AccountDataResetPhase.Ready, canExport = true,
        canReplaceLocal = initialPhase in listOf(AccountDataResetPhase.RemoteCommittedLocalPending, AccountDataResetPhase.ResetRequired),
        issue = AccountDataResetIssue.LocalFailure.takeIf { scenario == "local-failure" },
    )

    override fun load() = snapshot
    override fun refresh() = result()
    override fun reconcile(review: AccountDataResetReview) = result()
    override fun reauthenticate(review: AccountDataResetReview, password: String) = result()
    override fun submit(review: AccountDataResetReview, password: String): AccountDataResetActionResult {
        snapshot = snapshot.copy(phase = AccountDataResetPhase.OutcomeUnknown, resetAvailable = false,
            productReadOnly = true, operationId = operation, review = review.copy(operationId = operation))
        return result()
    }
    override fun keepOffline(review: AccountDataResetReview): AccountDataResetActionResult {
        snapshot = snapshot.copy(offlineEditing = true, productReadOnly = false)
        return result()
    }
    override fun replaceLocal(review: AccountDataResetReview, mode: AccountDataReplacementMode, discardConfirmed: Boolean,
        secret: String, password: String?): AccountDataResetActionResult =
        AccountDataResetActionResult(snapshot.copy(issue = AccountDataResetIssue.LocalFailure), false)
    override fun cancel() = Unit
    private fun result() = AccountDataResetActionResult(snapshot, true)
}
