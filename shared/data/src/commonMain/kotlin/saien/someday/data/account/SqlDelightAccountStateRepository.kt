@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.account

import kotlin.time.Clock
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.isCanonicalAccountIncarnation
import saien.someday.domain.settings.isSecureSyncEndpoint
import saien.someday.domain.settings.normalizeSelfHostedEndpoint

enum class AccountResetIntentState(val storageValue: String) {
    IntentPersisted("intent_persisted"), OutcomeUnknown("outcome_unknown"),
    RemoteCommittedLocalPending("remote_committed_local_pending"),
}

enum class AccountWorkspaceGateReason(val storageValue: String) {
    ResetRequired("reset_required"), ResetPending("reset_pending"),
    OutcomeUnknown("outcome_unknown"), RemoteCommitted("remote_committed"),
}

data class AccountResetIntent(
    val operationId: String,
    val endpoint: String,
    val userId: String,
    val expectedIncarnation: String,
    val originalWorkspaceId: String,
    val originalWriterId: String,
    val originalIncarnation: String = expectedIncarnation,
    val consentTargetIncarnation: String? = null,
    val state: AccountResetIntentState = AccountResetIntentState.IntentPersisted,
    val earlierOutcomeUnknown: Boolean = false,
    val submissionNumber: Long = 0,
    val receiptIncarnation: String? = null,
    val receiptCommittedAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long = 0,
) {
    init {
        require(RESET_OPERATION.matches(operationId))
        requireCanonicalAuthority(endpoint, userId)
        require(isCanonicalAccountIncarnation(expectedIncarnation) && isCanonicalAccountIncarnation(originalIncarnation))
        require(WORKSPACE_ID.matches(originalWorkspaceId) && originalWriterId.isNotBlank())
        require(consentTargetIncarnation == null || isCanonicalAccountIncarnation(consentTargetIncarnation))
        require(submissionNumber >= 0)
        require((receiptIncarnation == null) == (receiptCommittedAtEpochMillis == null))
        require(receiptIncarnation == null || isCanonicalAccountIncarnation(receiptIncarnation))
        require(state != AccountResetIntentState.RemoteCommittedLocalPending || receiptIncarnation != null)
    }
}

data class AccountResetSubmission(
    val operationId: String,
    val submissionNumber: Long,
    val hadEarlierUncertainty: Boolean,
)

data class AccountWorkspaceGate(
    val endpoint: String,
    val userId: String,
    val workspaceId: String,
    val expectedIncarnation: String,
    val reason: AccountWorkspaceGateReason,
    val offlineEditing: Boolean,
    val updatedAtEpochMillis: Long,
    val discardTargetIncarnation: String? = null,
) {
    init {
        requireCanonicalAuthority(endpoint, userId)
        require(WORKSPACE_ID.matches(workspaceId) && isCanonicalAccountIncarnation(expectedIncarnation))
        require(discardTargetIncarnation == null || isCanonicalAccountIncarnation(discardTargetIncarnation))
    }
    val productReadOnly: Boolean get() = !offlineEditing
}

class AccountNetworkBlockedException(val gate: AccountWorkspaceGate) :
    IllegalStateException("Account data access requires local reconciliation.")

/** Installation state: deliberately independent of every workspace-cleanup table and FK. */
class SqlDelightAccountStateRepository(
    private val database: SomedayDatabase,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val mutationBoundary: AccountStateMutationBoundary = AccountStateMutationBoundary.Direct,
) {
    private val queries = database.somedayQueries

    fun loadIntent(): AccountResetIntent? = queries.selectAccountResetIntent().executeAsOneOrNull()?.let { row ->
        AccountResetIntent(
            operationId = row.operation_id, endpoint = row.endpoint, userId = row.user_id,
            expectedIncarnation = row.expected_incarnation, originalWorkspaceId = row.original_workspace_id,
            originalWriterId = row.original_writer_id, originalIncarnation = row.original_incarnation,
            consentTargetIncarnation = row.consent_target_incarnation,
            state = AccountResetIntentState.entries.single { it.storageValue == row.state },
            earlierOutcomeUnknown = row.earlier_outcome_unknown != 0L, submissionNumber = row.submission_number,
            receiptIncarnation = row.receipt_incarnation, receiptCommittedAtEpochMillis = row.receipt_committed_at,
            updatedAtEpochMillis = row.updated_at,
        )
    }

    fun persistIntent(intent: AccountResetIntent) {
        mutationBoundary.mutate {
            require(intent.state == AccountResetIntentState.IntentPersisted && !intent.earlierOutcomeUnknown &&
                intent.submissionNumber == 0L && intent.receiptIncarnation == null)
            database.transaction {
                val existing = loadIntent()
                check(existing == null) { "A reset intent already requires reconciliation." }
                save(intent)
                gateForIntent(intent, AccountWorkspaceGateReason.ResetPending)
            }
        }
    }

    /** Persist before POST: process loss at any later instruction is an unknown outcome. */
    fun markSubmitted(operationId: String): AccountResetSubmission = mutationBoundary.mutate { database.transactionWithResult {
        val intent = requireIntent(operationId)
        check(intent.receiptIncarnation == null) { "A committed reset must not be submitted again." }
        val submission = AccountResetSubmission(operationId, intent.submissionNumber + 1, intent.earlierOutcomeUnknown)
        check(submission.submissionNumber > 0) { "Reset submission counter exhausted." }
        val next = intent.copy(state = AccountResetIntentState.OutcomeUnknown, earlierOutcomeUnknown = true, submissionNumber = submission.submissionNumber)
        save(next)
        gateForIntent(next, AccountWorkspaceGateReason.OutcomeUnknown)
        submission
    } }

    fun markOutcomeUnknown(operationId: String) {
        mutationBoundary.mutate {
            database.transaction {
                val intent = requireIntent(operationId)
                if (intent.receiptIncarnation == null) {
                    val next = intent.copy(state = AccountResetIntentState.OutcomeUnknown, earlierOutcomeUnknown = true)
                    save(next)
                    gateForIntent(next, AccountWorkspaceGateReason.OutcomeUnknown)
                }
            }
        }
    }

    fun recordCommittedReceipt(operationId: String, newIncarnation: String, committedAtEpochMillis: Long) {
        mutationBoundary.mutate {
            require(isCanonicalAccountIncarnation(newIncarnation))
            database.transaction {
                val intent = requireIntent(operationId)
                require(newIncarnation != intent.expectedIncarnation)
                check(intent.receiptIncarnation == null ||
                    (intent.receiptIncarnation == newIncarnation && intent.receiptCommittedAtEpochMillis == committedAtEpochMillis)) {
                    "Reset receipt identity changed."
                }
                val next = intent.copy(
                    state = AccountResetIntentState.RemoteCommittedLocalPending,
                    earlierOutcomeUnknown = false,
                    receiptIncarnation = newIncarnation,
                    receiptCommittedAtEpochMillis = committedAtEpochMillis,
                )
                save(next)
                gateForIntent(next, AccountWorkspaceGateReason.RemoteCommitted)
            }
        }
    }

    fun clearDefinitiveRejection(submission: AccountResetSubmission, observedCurrentIncarnation: String): Boolean =
        mutationBoundary.mutate { database.transactionWithResult {
            val intent = loadIntent() ?: return@transactionWithResult false
            if (submission.hadEarlierUncertainty || intent.operationId != submission.operationId ||
                intent.submissionNumber != submission.submissionNumber || intent.receiptIncarnation != null ||
                intent.originalIncarnation != observedCurrentIncarnation
            ) return@transactionWithResult false
            queries.deleteAccountResetIntent(intent.operationId)
            val gate = loadGate(intent.endpoint, intent.userId, intent.originalWorkspaceId)
            if (gate?.reason != AccountWorkspaceGateReason.ResetRequired) {
                clearGateAfterReplacement(intent.endpoint, intent.userId, intent.originalWorkspaceId, intent.originalIncarnation)
            }
            true
        } }

    fun loadGate(endpoint: String, userId: String, workspaceId: String): AccountWorkspaceGate? {
        val canonical = canonicalAuthority(endpoint, userId)
        return queries.selectAccountWorkspaceGate(canonical.first, canonical.second, workspaceId).executeAsOneOrNull()?.let { row ->
            AccountWorkspaceGate(
                row.endpoint, row.user_id, row.workspace_id, row.expected_incarnation,
                AccountWorkspaceGateReason.entries.single { it.storageValue == row.reason }, row.offline_editing != 0L, row.updated_at,
                row.discard_target_incarnation,
            )
        }
    }

    fun requireNetworkAllowed(endpoint: String, userId: String, workspaceId: String) {
        loadGate(endpoint, userId, workspaceId)?.let { throw AccountNetworkBlockedException(it) }
        val canonical = canonicalAuthority(endpoint, userId)
        loadIntent()?.takeIf { it.endpoint == canonical.first && it.userId == canonical.second }?.let { intent ->
            val gate = loadGate(intent.endpoint, intent.userId, intent.originalWorkspaceId) ?: AccountWorkspaceGate(
                intent.endpoint, intent.userId, intent.originalWorkspaceId, intent.originalIncarnation,
                AccountWorkspaceGateReason.OutcomeUnknown, false, intent.updatedAtEpochMillis,
            )
            throw AccountNetworkBlockedException(gate)
        }
    }

    fun markResetRequired(endpoint: String, userId: String, workspaceId: String, expectedIncarnation: String) {
        mutationBoundary.mutate {
            val canonical = canonicalAuthority(endpoint, userId)
            database.transaction {
                val existing = loadGate(canonical.first, canonical.second, workspaceId)
                require(existing == null || existing.expectedIncarnation == expectedIncarnation)
                saveGate(AccountWorkspaceGate(canonical.first, canonical.second, workspaceId, expectedIncarnation,
                    AccountWorkspaceGateReason.ResetRequired, existing?.offlineEditing ?: false, now(), existing?.discardTargetIncarnation))
            }
        }
    }

    fun enterOfflineMode(endpoint: String, userId: String, workspaceId: String) {
        mutationBoundary.mutate {
            database.transaction {
                val gate = requireNotNull(loadGate(endpoint, userId, workspaceId)) { "No account gate requires an offline decision." }
                saveGate(gate.copy(offlineEditing = true, discardTargetIncarnation = null))
                loadIntent()?.takeIf { it.matches(gate) }?.let { save(it.copy(consentTargetIncarnation = null)) }
            }
        }
    }

    fun recordLocalDiscardConsent(operationId: String, originalWorkspaceId: String, targetIncarnation: String) {
        mutationBoundary.mutate {
            require(isCanonicalAccountIncarnation(targetIncarnation))
            database.transaction {
                val intent = requireIntent(operationId)
                require(intent.originalWorkspaceId == originalWorkspaceId)
                val gate = checkNotNull(loadGate(intent.endpoint, intent.userId, originalWorkspaceId)) {
                    "The frozen local copy is no longer available for this confirmation."
                }
                check(gate.expectedIncarnation == intent.originalIncarnation && !gate.offlineEditing) {
                    "A newer offline decision invalidated this local discard confirmation."
                }
                save(intent.copy(consentTargetIncarnation = targetIncarnation))
                saveGate(gate.copy(discardTargetIncarnation = targetIncarnation))
            }
        }
    }

    /** A new explicit confirmation freezes the current copy before remote checks. */
    fun freezeForRejoin(endpoint: String, userId: String, workspaceId: String, expectedIncarnation: String) {
        mutationBoundary.mutate {
            database.transaction {
                val gate = requireNotNull(loadGate(endpoint, userId, workspaceId))
                require(gate.expectedIncarnation == expectedIncarnation)
                saveGate(gate.copy(offlineEditing = false, discardTargetIncarnation = null))
                loadIntent()?.takeIf { it.matches(gate) }?.let { save(it.copy(consentTargetIncarnation = null)) }
            }
        }
    }

    /** Durable target for consented re-enrollment, including a non-initiating device. */
    fun recordRejoinConsent(endpoint: String, userId: String, workspaceId: String, expectedIncarnation: String, targetIncarnation: String) {
        mutationBoundary.mutate {
            require(isCanonicalAccountIncarnation(targetIncarnation))
            database.transaction {
                val gate = requireNotNull(loadGate(endpoint, userId, workspaceId))
                require(gate.expectedIncarnation == expectedIncarnation && !gate.offlineEditing)
                loadIntent()?.takeIf { it.matches(gate) }?.let {
                    check(it.receiptIncarnation != null) { "The reset outcome must be reconciled before local replacement." }
                    save(it.copy(consentTargetIncarnation = targetIncarnation))
                }
                saveGate(gate.copy(discardTargetIncarnation = targetIncarnation))
            }
        }
    }

    /** Freeze the confirmed copy before discovery; failure never restores stale discard consent. */
    fun freezeForLocalDiscardConfirmation(operationId: String) {
        mutationBoundary.mutate {
            database.transaction {
                val intent = requireIntent(operationId)
                check(intent.receiptIncarnation != null) { "A committed receipt is required before local discard confirmation." }
                save(intent.copy(consentTargetIncarnation = null))
                val gate = loadGate(intent.endpoint, intent.userId, intent.originalWorkspaceId)
                require(gate == null || gate.expectedIncarnation == intent.originalIncarnation)
                saveGate(AccountWorkspaceGate(
                    intent.endpoint, intent.userId, intent.originalWorkspaceId, intent.originalIncarnation,
                    gate?.reason ?: AccountWorkspaceGateReason.RemoteCommitted, false, now(),
                ))
            }
        }
    }

    /** Caller holds the shared workspace lifecycle and the replacement database transaction. */
    fun clearGateAfterReplacement(endpoint: String, userId: String, originalWorkspaceId: String, expectedIncarnation: String): Boolean =
        mutationBoundary.mutate { database.transactionWithResult {
            val gate = loadGate(endpoint, userId, originalWorkspaceId) ?: return@transactionWithResult false
            if (gate.expectedIncarnation != expectedIncarnation) return@transactionWithResult false
            if (loadIntent()?.matches(gate) == true) return@transactionWithResult false
            queries.deleteMatchingAccountWorkspaceGate(gate.endpoint, gate.userId, gate.workspaceId, expectedIncarnation)
            true
        } }

    fun completeLocalReconciliation(operationId: String, capturedIncarnation: String): Boolean = mutationBoundary.mutate { database.transactionWithResult {
        val intent = loadIntent() ?: return@transactionWithResult false
        if (intent.operationId != operationId || intent.receiptIncarnation == null || intent.consentTargetIncarnation != capturedIncarnation) {
            return@transactionWithResult false
        }
        queries.deleteAccountResetIntent(operationId)
        clearGateAfterReplacement(intent.endpoint, intent.userId, intent.originalWorkspaceId, intent.originalIncarnation)
        true
    } }

    fun markProtocol1(endpoint: String, userId: String) {
        mutationBoundary.mutate {
            val canonical = canonicalAuthority(endpoint, userId)
            queries.rememberAccountProtocolOne(canonical.first, canonical.second, now())
        }
    }

    fun hasProtocol1(endpoint: String, userId: String): Boolean {
        val canonical = canonicalAuthority(endpoint, userId)
        return queries.selectAccountProtocolCapability(canonical.first, canonical.second).executeAsOneOrNull() != null
    }

    private fun requireIntent(operationId: String): AccountResetIntent =
        checkNotNull(loadIntent()?.takeIf { it.operationId == operationId }) { "The matching reset intent is unavailable." }

    private fun gateForIntent(intent: AccountResetIntent, reason: AccountWorkspaceGateReason) {
        val existing = loadGate(intent.endpoint, intent.userId, intent.originalWorkspaceId)
        require(existing == null || existing.expectedIncarnation == intent.originalIncarnation)
        saveGate(AccountWorkspaceGate(intent.endpoint, intent.userId, intent.originalWorkspaceId, intent.originalIncarnation,
            if (existing?.reason == AccountWorkspaceGateReason.ResetRequired) existing.reason else reason,
            existing?.offlineEditing ?: false, now()))
    }

    private fun save(intent: AccountResetIntent) = queries.upsertAccountResetIntent(
        intent.operationId, intent.endpoint, intent.userId, intent.expectedIncarnation, intent.originalIncarnation,
        intent.originalWorkspaceId, intent.originalWriterId, intent.consentTargetIncarnation, intent.state.storageValue,
        if (intent.earlierOutcomeUnknown) 1 else 0, intent.submissionNumber, intent.receiptIncarnation, intent.receiptCommittedAtEpochMillis, now(),
    )

    private fun saveGate(gate: AccountWorkspaceGate) = queries.upsertAccountWorkspaceGate(
        gate.endpoint, gate.userId, gate.workspaceId, gate.expectedIncarnation, gate.reason.storageValue,
        if (gate.offlineEditing) 1 else 0, now(), gate.discardTargetIncarnation,
    )

    private fun AccountResetIntent.matches(gate: AccountWorkspaceGate): Boolean =
        endpoint == gate.endpoint && userId == gate.userId && originalWorkspaceId == gate.workspaceId
}

private val RESET_OPERATION = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val WORKSPACE_ID = Regex("^workspace-[0-9a-f]{32}$")
private fun requireCanonicalAuthority(endpoint: String, userId: String) {
    require(endpoint == normalizeSelfHostedEndpoint(endpoint) && endpoint.length in 1..2048 && isSecureSyncEndpoint(endpoint))
    require(userId == userId.trim() && userId.length in 1..255)
}
private fun canonicalAuthority(endpoint: String, userId: String): Pair<String, String> =
    (normalizeSelfHostedEndpoint(endpoint) to userId.trim()).also { requireCanonicalAuthority(it.first, it.second) }
