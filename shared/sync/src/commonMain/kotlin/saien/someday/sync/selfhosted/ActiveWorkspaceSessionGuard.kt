package saien.someday.sync.selfhosted

import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId

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
) {
    init {
        require(authorityBindingId.isNotBlank())
        require(localWriterDeviceId.isNotBlank())
        requireSystemV3WorkspaceId(workspaceId)
    }
}

/** Single fail-closed guard shared by setup, entity sync, media, and pairing. */
class ActiveWorkspaceSessionGuard(
    private val requirementProvider: () -> ActiveWorkspaceSessionRequirement?,
) {
    fun currentRequirement(): ActiveWorkspaceSessionRequirement? = requirementProvider()

    fun requireCompatible(credentials: SelfHostedSessionCredentials) {
        val required = currentRequirement() ?: return
        require(credentials.authorityBindingId == required.authorityBindingId) {
            "The authenticated self-hosted account does not match the bound workspace authority."
        }
        require(credentials.deviceId == required.localWriterDeviceId) {
            "The authenticated self-hosted device does not match the bound workspace writer."
        }
    }

    fun requireCompatible(credentials: SelfHostedSessionCredentials, workspaceId: String) {
        requireCompatible(credentials)
        val required = currentRequirement() ?: return
        require(requireSystemV3WorkspaceId(workspaceId) == required.workspaceId) {
            "The active workspace does not match the bound workspace authority."
        }
    }

    fun isCompatible(credentials: SelfHostedSessionCredentials): Boolean =
        runCatching { requireCompatible(credentials) }.isSuccess

    fun isCompatible(credentials: SelfHostedSessionCredentials, workspaceId: String): Boolean =
        runCatching { requireCompatible(credentials, workspaceId) }.isSuccess
}

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
