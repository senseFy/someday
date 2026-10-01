package saien.someday.sync.selfhosted

import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceAdmissionManager
import saien.someday.domain.settings.WorkspaceAdmissionState
import saien.someday.domain.settings.WorkspaceAdmissionStatus
import saien.someday.sync.WorkspaceLifecycleCoordinator

interface SelfHostedWorkspaceAdmissionTransport {
    /** Null means that the server has no admission endpoint, never an empty account. */
    fun workspaceAdmission(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedWorkspaceAdmissionResponse?
}

@Serializable
data class SelfHostedWorkspaceAdmissionResponse(
    val protocolVersion: Int,
    val initializedWorkspaceCount: Int,
    val localWorkspaceInitialized: Boolean,
    val recoveryAvailable: Boolean,
) {
    internal fun validated(): SelfHostedWorkspaceAdmissionResponse {
        if (protocolVersion != 1 || initializedWorkspaceCount < 0 ||
            (localWorkspaceInitialized && initializedWorkspaceCount == 0) ||
            (recoveryAvailable && initializedWorkspaceCount == 0)
        ) SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.MALFORMED_BODY)
        return this
    }
}

/** Read-only discovery, shared by onboarding and the actual synchronization boundary. */
class SelfHostedWorkspaceAdmissionService(
    private val transport: SelfHostedWorkspaceAdmissionTransport?,
    private val sessionStore: SelfHostedSessionCredentialStore,
    private val sessionExecutor: RefreshingSelfHostedSessionExecutor,
    private val workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator,
    private val activeWorkspaceSessionGuard: ActiveWorkspaceSessionGuard,
    private val workspaceIdProvider: () -> String?,
    private val workspaceKeyAvailable: () -> Boolean,
    /** Must prove an ACTIVE epoch and its matching local authority, never merely PREPARING. */
    private val activeWorkspaceEstablished: () -> Boolean,
    /** A persisted first-publication checkpoint records an earlier explicit sync attempt. */
    private val initialPublicationPrepared: () -> Boolean,
) : WorkspaceAdmissionManager {
    override fun status(): WorkspaceAdmissionStatus = workspaceLifecycleCoordinator.exclusive {
        statusWithinWorkspaceLifecycle()
    }

    internal fun syncFailureWithinWorkspaceLifecycle(): ManualSyncResult? =
        when (statusWithinWorkspaceLifecycle().state) {
            WorkspaceAdmissionState.Ready, WorkspaceAdmissionState.FirstWorkspace -> null
            WorkspaceAdmissionState.JoinRequired ->
                ManualSyncResult.failure(SyncMode.SelfHosted, ManualSyncReason.WorkspaceJoinRequired)
            else -> ManualSyncResult.failure(SyncMode.SelfHosted, ManualSyncReason.WorkspaceAdmissionUnavailable)
        }

    private fun statusWithinWorkspaceLifecycle(): WorkspaceAdmissionStatus = try {
        discoverWithinWorkspaceLifecycle()
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        WorkspaceAdmissionStatus(WorkspaceAdmissionState.Unavailable)
    }

    private fun discoverWithinWorkspaceLifecycle(): WorkspaceAdmissionStatus {
        val unavailable = WorkspaceAdmissionStatus(WorkspaceAdmissionState.Unavailable)
        val credentials = sessionStore.load() ?: return unavailable
        val workspaceId = workspaceIdProvider() ?: return unavailable
        // Account and workspace authority still gate discovery when the local key is unavailable.
        try {
            activeWorkspaceSessionGuard.requireCompatible(credentials, workspaceId)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            activeWorkspaceSessionGuard.recordAccountFailure(credentials, failure)
            return unavailable
        }
        val keyAvailable = workspaceKeyAvailable()
        fun existingWorkspaceFallback(): WorkspaceAdmissionStatus =
            if (keyAvailable && activeWorkspaceEstablished()) WorkspaceAdmissionStatus(WorkspaceAdmissionState.Ready) else unavailable
        val remote = try {
            val delegate = transport ?: return existingWorkspaceFallback()
            sessionExecutor.authorized(
                credentials.endpoint, credentials.userId, credentials.accessToken, credentials.accountRequestContext(),
            ) { token, context ->
                delegate.workspaceAdmission(credentials.endpoint, token, workspaceId, context)?.validated()
            } ?: return existingWorkspaceFallback()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (activeWorkspaceSessionGuard.recordAccountFailure(credentials, failure)) return unavailable
            return when (failure) {
                is IOException, is HttpRequestTimeoutException -> existingWorkspaceFallback()
                is SelfHostedSyncHttpException -> if (failure.status == 429 || failure.status in 500..599) {
                    existingWorkspaceFallback()
                } else unavailable
                else -> unavailable
            }
        }
        val state = when {
            remote.localWorkspaceInitialized -> {
                // Missing key or binding requires an explicit join, even for this workspace id.
                if (keyAvailable && activeWorkspaceSessionGuard.currentRequirement()?.workspaceId == workspaceId) {
                    WorkspaceAdmissionState.Ready
                } else WorkspaceAdmissionState.JoinRequired
            }
            remote.initializedWorkspaceCount == 0 -> when {
                !keyAvailable -> WorkspaceAdmissionState.Unavailable
                initialPublicationPrepared() -> WorkspaceAdmissionState.Ready
                else -> WorkspaceAdmissionState.FirstWorkspace
            }
            else -> WorkspaceAdmissionState.JoinRequired
        }
        return WorkspaceAdmissionStatus(state, remote.initializedWorkspaceCount, remote.recoveryAvailable)
    }
}
