package saien.someday.sync.selfhosted

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.WorkspaceAdmissionState
import saien.someday.domain.settings.authorityBindingId
import saien.someday.sync.WorkspaceLifecycleCoordinator

class SelfHostedWorkspaceAdmissionServiceTest {
    @Test fun existingWorkspaceRequiresJoiningEvenWithoutARecoveryCode() = Fixture().use { f ->
        f.response = SelfHostedWorkspaceAdmissionResponse(1, 1, false, false)
        assertEquals(WorkspaceAdmissionState.JoinRequired, f.service.status().state)
        assertEquals(ManualSyncReason.WorkspaceJoinRequired, f.syncFailure()?.reason)
        assertEquals(1, f.service.status().initializedWorkspaceCount)
        assertEquals(false, f.service.status().recoveryAvailable)
    }

    @Test fun firstWorkspaceAndHistoricalMultipleWorkspacesHaveDistinctAdmission() = Fixture().use { f ->
        f.response = SelfHostedWorkspaceAdmissionResponse(1, 0, false, false)
        assertEquals(WorkspaceAdmissionState.FirstWorkspace, f.service.status().state)
        assertNull(f.syncFailure())
        f.bound = true
        f.response = SelfHostedWorkspaceAdmissionResponse(1, 2, true, true)
        val joined = f.service.status()
        assertEquals(WorkspaceAdmissionState.Ready, joined.state)
        assertEquals(2, joined.initializedWorkspaceCount)
        assertEquals(true, joined.recoveryAvailable)
        assertNull(f.syncFailure())
    }

    @Test fun workspaceIdAloneOrMissingKeyRequiresAnExplicitJoin() = Fixture().use { f ->
        f.response = SelfHostedWorkspaceAdmissionResponse(1, 1, true, true)
        assertEquals(WorkspaceAdmissionState.JoinRequired, f.service.status().state)
        assertEquals(ManualSyncReason.WorkspaceJoinRequired, f.syncFailure()?.reason)
        f.bound = true
        f.active = true
        f.keyAvailable = false
        val requests = f.requests
        val status = f.service.status()
        assertEquals(WorkspaceAdmissionState.JoinRequired, status.state)
        assertEquals(true, status.recoveryAvailable)
        assertEquals(1, status.initializedWorkspaceCount)
        assertEquals(requests + 1, f.requests)
        assertEquals(ManualSyncReason.WorkspaceJoinRequired, f.syncFailure()?.reason)
        f.response = SelfHostedWorkspaceAdmissionResponse(1, 1, false, false)
        assertEquals(WorkspaceAdmissionState.JoinRequired, f.service.status().state)
        assertEquals(ManualSyncReason.WorkspaceJoinRequired, f.syncFailure()?.reason)
    }

    @Test fun missingKeyCannotStartOrResumePublicationForAnEmptyAccount() = Fixture().use { f ->
        f.keyAvailable = false
        assertEquals(WorkspaceAdmissionState.Unavailable, f.service.status().state)
        assertEquals(ManualSyncReason.WorkspaceAdmissionUnavailable, f.syncFailure()?.reason)
        f.bound = true
        f.prepared = true
        assertEquals(WorkspaceAdmissionState.Unavailable, f.service.status().state)
        assertEquals(ManualSyncReason.WorkspaceAdmissionUnavailable, f.syncFailure()?.reason)
    }

    @Test fun missingKeyDoesNotBypassAccountGuardsBeforeDiscovery() = Fixture().use { f ->
        f.bound = true
        f.keyAvailable = false
        f.response = SelfHostedWorkspaceAdmissionResponse(1, 1, true, true)
        for (credentials in listOf(
            CREDENTIALS.copy(userId = "another-account"),
            CREDENTIALS.copy(deviceId = "00000000-0000-4000-8000-000000000002"),
            CREDENTIALS.copy(accountIncarnation = "00000000-0000-4000-8000-000000000003"),
        )) {
            f.credentials = credentials
            assertEquals(WorkspaceAdmissionState.Unavailable, f.service.status().state)
        }
        assertEquals(0, f.requests)
        assertEquals(1, f.recordedIncarnationFailures)
    }

    @Test fun aPreparedFirstPublicationCanResumeOnlyAfterFreshEmptyAccountDiscovery() = Fixture().use { f ->
        f.bound = true
        assertEquals(WorkspaceAdmissionState.FirstWorkspace, f.service.status().state)
        f.prepared = true
        assertEquals(WorkspaceAdmissionState.Ready, f.service.status().state)
        f.response = SelfHostedWorkspaceAdmissionResponse(1, 1, false, false)
        assertEquals(WorkspaceAdmissionState.JoinRequired, f.service.status().state)
        f.response = null
        assertEquals(WorkspaceAdmissionState.Unavailable, f.service.status().state)
    }

    @Test fun unavailableDiscoveryOnlyPreservesAnAlreadyActiveWorkspace() = Fixture().use { f ->
        for (failure in listOf(null, IOException("private transport detail"),
            SelfHostedSyncHttpException(503, "busy", SelfHostedErrorCode.ACCOUNT_BUSY, true))) {
            f.response = null
            f.failure = failure
            f.keyAvailable = true
            f.bound = true // A prepared draft is bound, but is not an active workspace.
            f.active = false
            assertEquals(ManualSyncReason.WorkspaceAdmissionUnavailable, f.syncFailure()?.reason)
            f.active = true
            assertEquals(WorkspaceAdmissionState.Ready, f.service.status().state)
            assertNull(f.service.status().initializedWorkspaceCount)
            f.keyAvailable = false
            assertEquals(ManualSyncReason.WorkspaceAdmissionUnavailable, f.syncFailure()?.reason)
        }
    }

    @Test fun accountFailuresAndMalformedResponsesNeverUseTheActiveFallback() = Fixture().use { f ->
        f.bound = true
        f.active = true
        f.failure = SelfHostedSyncHttpException(409, "retired", SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED, true)
        assertEquals(WorkspaceAdmissionState.Unavailable, f.service.status().state)
        assertEquals(1, f.recordedIncarnationFailures)
        f.failure = SelfHostedSyncHttpException(403, "revoked", SelfHostedErrorCode.FORBIDDEN, true)
        assertEquals(WorkspaceAdmissionState.Unavailable, f.service.status().state)
        f.failure = null
        f.response = SelfHostedWorkspaceAdmissionResponse(1, 0, true, false)
        assertEquals(WorkspaceAdmissionState.Unavailable, f.service.status().state)
    }

    @Test fun changedLocalAccountIsRejectedBeforeDiscoveryAndCancellationPropagates(): Unit = Fixture().use { f ->
        f.bound = true
        f.credentials = f.credentials.copy(userId = "another-account")
        assertEquals(WorkspaceAdmissionState.Unavailable, f.service.status().state)
        assertEquals(0, f.requests)
        f.credentials = CREDENTIALS
        f.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { f.service.status() }
    }

    private class Fixture : AutoCloseable {
        var credentials = CREDENTIALS
        var response: SelfHostedWorkspaceAdmissionResponse? = SelfHostedWorkspaceAdmissionResponse(1, 0, false, false)
        var failure: Exception? = null
        var bound = false
        var active = false
        var prepared = false
        var keyAvailable = true
        var requests = 0
        var recordedIncarnationFailures = 0
        private val lifecycle = WorkspaceLifecycleCoordinator()
        private val authentication = JdkSelfHostedSyncTransport()
        private val store = object : SelfHostedSessionCredentialStore {
            override fun load() = credentials
            override fun save(credentials: SelfHostedSessionCredentials) { error("Unexpected credential write") }
            override fun clear() { error("Unexpected credential clear") }
        }
        private val guard = ActiveWorkspaceSessionGuard(
            persistIncarnationGate = { recordedIncarnationFailures++ },
        ) {
            if (bound) ActiveWorkspaceSessionRequirement(CREDENTIALS.authorityBindingId, DEVICE, WORKSPACE) else null
        }
        val service = SelfHostedWorkspaceAdmissionService(
            transport = object : SelfHostedWorkspaceAdmissionTransport {
                override fun workspaceAdmission(endpoint: String, accessToken: String, workspaceId: String, accountContext: SelfHostedAccountRequestContext): SelfHostedWorkspaceAdmissionResponse? {
                    requests++
                    assertEquals(WORKSPACE, workspaceId)
                    assertEquals(INITIAL_ACCOUNT_INCARNATION, accountContext.accountIncarnation)
                    failure?.let { throw it }
                    return response
                }
            },
            sessionStore = store,
            sessionExecutor = RefreshingSelfHostedSessionExecutor(authentication, store),
            workspaceLifecycleCoordinator = lifecycle,
            activeWorkspaceSessionGuard = guard,
            workspaceIdProvider = { WORKSPACE },
            workspaceKeyAvailable = { keyAvailable },
            activeWorkspaceEstablished = { active },
            initialPublicationPrepared = { prepared },
        )
        fun syncFailure() = lifecycle.exclusive { service.syncFailureWithinWorkspaceLifecycle() }
        override fun close() { authentication.close() }
    }

    private companion object {
        const val DEVICE = "00000000-0000-4000-8000-000000000001"
        const val WORKSPACE = "workspace-00000000000000000000000000000001"
        val CREDENTIALS = SelfHostedSessionCredentials(
            "https://sync.example.test", "user-1", "user@example.test", DEVICE,
            "Test device", "desktop", "access-secret", "refresh-secret", accountProtocolVersion = 1,
        )
    }
}
