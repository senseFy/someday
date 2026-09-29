package saien.someday.sync.selfhosted

import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.isCanonicalAccountIncarnation

/**
 * The durable identity a local workspace is allowed to publish as.
 *
 * Account identity alone is insufficient: entity outbox objects are signed for
 * one stable writer device, and the server binds every access token to one
 * device. Re-registering the same installation would therefore strand its
 * durable outbox even when the user account did not change.
 */
data class ActiveWorkspaceSessionRequirement(
    val authorityBindingId: String,
    val localWriterDeviceId: String,
    val workspaceId: String,
    val accountIncarnation: String = INITIAL_ACCOUNT_INCARNATION,
) {
    init {
        require(authorityBindingId.isNotBlank())
        require(localWriterDeviceId.isNotBlank())
        requireSystemV3WorkspaceId(workspaceId)
        require(isCanonicalAccountIncarnation(accountIncarnation))
    }
}

/** Single fail-closed guard shared by setup, entity sync, media, and pairing. */
class ActiveWorkspaceSessionGuard(
    private val requireNetworkAccess: (SelfHostedSessionCredentials, String) -> Unit = { _, _ -> },
    private val persistIncarnationGate: (ActiveWorkspaceSessionRequirement) -> Unit = {},
    val protocol1Known: (String, String) -> Boolean = { _, _ -> false },
    val onProtocol1: (String, String) -> Unit = { _, _ -> },
    private val revalidateReplacement: (SelfHostedSessionCredentials) -> Unit = {},
    private val requireReplacementAllowed: (SelfHostedSessionCredentials) -> Unit = {},
    private val replacementWorkspaceProvider: () -> Pair<String, String>? = { null },
    private val requirementProvider: () -> ActiveWorkspaceSessionRequirement?,
) {
    fun currentRequirement(): ActiveWorkspaceSessionRequirement? = requirementProvider()

    fun capturePreviousWorkspace(): Pair<String, String>? = currentRequirement()?.let {
        it.workspaceId to it.accountIncarnation
    } ?: replacementWorkspaceProvider()

    fun requireCompatible(credentials: SelfHostedSessionCredentials) {
        val required = currentRequirement() ?: run {
            capturePreviousWorkspace()?.let { requireNetworkAccess(credentials, it.first) }
            return
        }
        requireIdentity(credentials, required)
        if (credentials.accountIncarnation != required.accountIncarnation) {
            persistIncarnationGate(required)
            throw SelfHostedSyncHttpException(409, "The workspace account incarnation differs.", SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, true)
        }
        requireNetworkAccess(credentials, required.workspaceId)
    }

    private fun requireIdentity(credentials: SelfHostedSessionCredentials, required: ActiveWorkspaceSessionRequirement) {
        require(credentials.authorityBindingId == required.authorityBindingId) {
            "The authenticated self-hosted account does not match the bound workspace authority."
        }
        require(credentials.deviceId == required.localWriterDeviceId) {
            "The authenticated self-hosted device does not match the bound workspace writer."
        }
    }

    fun requireCompatible(credentials: SelfHostedSessionCredentials, workspaceId: String) {
        requireCompatible(credentials)
        requireNetworkAccess(credentials, workspaceId)
        val required = currentRequirement() ?: return
        require(requireSystemV3WorkspaceId(workspaceId) == required.workspaceId) {
            "The active workspace does not match the bound workspace authority."
        }
    }

    /** Only an explicit per-attempt replacement may use a fresh incarnation. */
    fun requireReplacementCompatible(credentials: SelfHostedSessionCredentials) {
        requireReplacementAllowed(credentials)
        val required = currentRequirement() ?: return
        requireIdentity(credentials, required)
        if (credentials.accountIncarnation != required.accountIncarnation) persistIncarnationGate(required)
    }

    fun requireCapturedReplacement(
        credentials: SelfHostedSessionCredentials,
        capturedRequirement: ActiveWorkspaceSessionRequirement?,
        capturedWorkspace: Pair<String, String>? = capturedRequirement?.let { it.workspaceId to it.accountIncarnation },
    ) {
        require(capturePreviousWorkspace() == capturedWorkspace) { "The local workspace changed during replacement." }
        require(currentRequirement() == capturedRequirement) { "The local workspace changed during replacement." }
        requireReplacementCompatible(credentials)
        try {
            revalidateReplacement(credentials)
        } catch (failure: Throwable) {
            recordAccountFailure(credentials, failure)
            throw failure
        }
    }

    fun recordAccountFailure(credentials: SelfHostedSessionCredentials, failure: Throwable): Boolean {
        val typed = failure as? SelfHostedSyncHttpException ?: return false
        if (typed.errorCode !in INCARNATION_FAILURES) return false
        currentRequirement()?.takeIf { it.authorityBindingId == credentials.authorityBindingId &&
            it.localWriterDeviceId == credentials.deviceId && it.accountIncarnation == credentials.accountIncarnation }?.let(persistIncarnationGate)
        return true
    }

    fun markCurrentIncarnationMismatch() { currentRequirement()?.let(persistIncarnationGate) }

    fun isCompatible(credentials: SelfHostedSessionCredentials): Boolean =
        runCatching { requireCompatible(credentials) }.isSuccess

    fun isCompatible(credentials: SelfHostedSessionCredentials, workspaceId: String): Boolean =
        runCatching { requireCompatible(credentials, workspaceId) }.isSuccess
}

private val INCARNATION_FAILURES = setOf(
    SelfHostedErrorCode.ACCOUNT_SESSION_STALE,
    SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH,
    SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED,
)

/**
 * UI-facing credential view that prevents an established workspace from deleting the only
 * session capable of refreshing its pinned writer device. Protocol maintenance and setup keep
 * the underlying authority-scoped store; this wrapper protects only a user-requested full clear.
 */
class WorkspaceBoundSessionCredentialStore(
    private val delegate: SelfHostedSessionCredentialStore,
    private val activeWorkspaceSessionGuard: ActiveWorkspaceSessionGuard,
) : SelfHostedSessionCredentialStore {
    override fun load(): SelfHostedSessionCredentials? {
        val requirement = activeWorkspaceSessionGuard.currentRequirement() ?: return delegate.load()
        return delegate.loadForAuthority(requirement.authorityBindingId)
    }

    override fun save(credentials: SelfHostedSessionCredentials) = delegate.save(credentials)

    override fun clear() {
        check(activeWorkspaceSessionGuard.currentRequirement() == null) {
            "This workspace is bound to its current account and writer device. " +
                "Reset the local workspace before forgetting that session."
        }
        delegate.clear()
    }

    override fun loadForAuthority(authorityBindingId: String): SelfHostedSessionCredentials? =
        delegate.loadForAuthority(authorityBindingId)

    override fun saveForAuthority(
        authorityBindingId: String,
        credentials: SelfHostedSessionCredentials,
    ) = delegate.saveForAuthority(authorityBindingId, credentials)

    override fun clearAuthority(authorityBindingId: String) = delegate.clearAuthority(authorityBindingId)
}
