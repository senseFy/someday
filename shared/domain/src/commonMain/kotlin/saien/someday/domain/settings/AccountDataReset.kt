package saien.someday.domain.settings

/** Account incarnation is independent of local DAG generations. */
const val INITIAL_ACCOUNT_INCARNATION = "00000000-0000-0000-0000-000000000000"

private val canonicalAccountIncarnation = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

fun isCanonicalAccountIncarnation(value: String): Boolean = canonicalAccountIncarnation.matches(value)

/** Local attempt metadata; never part of a pairing or recovery cryptographic envelope. */
data class WorkspaceJoinAuthorityCapture(
    val authorityBindingId: String,
    val deviceId: String,
    val accountIncarnation: String,
    val previousWorkspaceId: String? = null,
    val previousAccountIncarnation: String? = null,
) {
    init {
        require(parseSelfHostedAuthorityBindingId(authorityBindingId) != null)
        require(deviceId.isNotBlank())
        require(isCanonicalAccountIncarnation(accountIncarnation))
        require((previousWorkspaceId == null) == (previousAccountIncarnation == null))
        require(previousWorkspaceId == null || Regex("^workspace-[0-9a-f]{32}$").matches(previousWorkspaceId))
        require(previousAccountIncarnation == null || isCanonicalAccountIncarnation(previousAccountIncarnation))
    }
}

enum class AccountDataResetPhase {
    Unavailable, Ready, ResetRequired, OutcomeUnknown, RemoteCommittedLocalPending, LocalReady,
}

enum class AccountDataResetIssue {
    Unavailable, UnsupportedServer, DeploymentNotReady, RetiredMediaPending,
    SignInRequired, WrongPassword, DeviceRevoked, Busy, RateLimited, Conflict,
    ProtocolError, NetworkError, LocalFailure, ContextChanged, ReplacementFailed,
    ConfirmationRequired, InvalidSecret, NoRecoveryEnvelope,
    AccountMismatch, InvitationUnavailable, InvitationAlreadyUsed,
}

enum class AccountDataReplacementMode { Fresh, Pair, Recover }

/** An immutable local confirmation capture. Contains no password, token or key. */
data class AccountDataResetReview(
    val id: String,
    val authorityBindingId: String,
    val endpoint: String,
    val accountEmail: String,
    val workspaceId: String,
    val writerDeviceId: String,
    val localIncarnation: String,
    val localAuthorityBindingId: String?,
    val targetIncarnation: String,
    val operationId: String?,
)

data class AccountDataResetSnapshot(
    val phase: AccountDataResetPhase = AccountDataResetPhase.Unavailable,
    val review: AccountDataResetReview? = null,
    val endpoint: String? = null,
    val accountEmail: String? = null,
    val operationId: String? = null,
    val offlineEditing: Boolean = false,
    val productReadOnly: Boolean = false,
    val resetAvailable: Boolean = false,
    val canExport: Boolean = false,
    val canReplaceLocal: Boolean = false,
    /** Failure of the current step; availability of a future reset is separate. */
    val issue: AccountDataResetIssue? = null,
    /** Authenticate before offering a new local-discard confirmation. */
    val requiresAuthentication: Boolean = false,
    /** Explains why another remote reset cannot start; never blocks local recovery. */
    val resetUnavailableIssue: AccountDataResetIssue? = null,
)

data class AccountDataResetActionResult(
    val snapshot: AccountDataResetSnapshot,
    val success: Boolean,
    val localReplaced: Boolean = false,
    val issue: AccountDataResetIssue? = snapshot.issue,
)

/**
 * Synchronous IO port, matching setup/recovery. UI actions are suspend and call
 * this port only on their background dispatcher; constructors do no IO.
 * Passwords and pairing/recovery secrets are invocation-only values.
 */
interface AccountDataResetManager {
    /** Local state only, including durable gates after process restart. */
    fun load(): AccountDataResetSnapshot
    fun refresh(): AccountDataResetActionResult
    fun submit(review: AccountDataResetReview, password: String): AccountDataResetActionResult
    fun reauthenticate(review: AccountDataResetReview, password: String, email: String = review.accountEmail): AccountDataResetActionResult
    fun reconcile(review: AccountDataResetReview): AccountDataResetActionResult
    fun keepOffline(review: AccountDataResetReview): AccountDataResetActionResult
    fun replaceLocal(
        review: AccountDataResetReview,
        mode: AccountDataReplacementMode,
        discardConfirmed: Boolean,
        secret: String = "",
        password: String? = null,
    ): AccountDataResetActionResult
    /** Drops control credentials retained only in memory; never clears durable intent. */
    fun cancel()
}

object UnavailableAccountDataResetManager : AccountDataResetManager {
    override fun load() = AccountDataResetSnapshot()
    private fun unavailable() = AccountDataResetActionResult(load(), false, issue = AccountDataResetIssue.Unavailable)
    override fun refresh() = unavailable()
    override fun submit(review: AccountDataResetReview, password: String) = unavailable()
    override fun reauthenticate(review: AccountDataResetReview, password: String, email: String) = unavailable()
    override fun reconcile(review: AccountDataResetReview) = unavailable()
    override fun keepOffline(review: AccountDataResetReview) = unavailable()
    override fun replaceLocal(review: AccountDataResetReview, mode: AccountDataReplacementMode, discardConfirmed: Boolean, secret: String, password: String?) = unavailable()
    override fun cancel() = Unit
}
