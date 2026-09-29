package saien.someday.sync.selfhosted

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId

class ActiveWorkspaceSessionGuardTest {
    @Test
    fun incarnationMismatchPersistsGateBeforeAnyNetworkAccessAndNeverRelabelsBinding() {
        val requirement = ActiveWorkspaceSessionRequirement(CREDENTIALS.authorityBindingId, CREDENTIALS.deviceId,
            "workspace-00000000000000000000000000000000")
        var persisted: ActiveWorkspaceSessionRequirement? = null
        var networkChecks = 0
        val guard = ActiveWorkspaceSessionGuard(
            persistIncarnationGate = { persisted = it },
            requireNetworkAccess = { _, _ -> networkChecks++ },
        ) { requirement }
        val replacementCredentials = CREDENTIALS.copy(accountIncarnation = "00000000-0000-4000-8000-000000000001", accountProtocolVersion = 1)
        val failure = assertFailsWith<SelfHostedSyncHttpException> { guard.requireCompatible(replacementCredentials) }
        assertEquals(SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, failure.errorCode)
        assertEquals(requirement, persisted)
        assertEquals(0, networkChecks)
        assertEquals(requirement, guard.currentRequirement())
    }

    @Test
    fun delayedOldIncarnationFailureCannotGateAReplacementWorkspace() {
        val replacement = ActiveWorkspaceSessionRequirement(CREDENTIALS.authorityBindingId, CREDENTIALS.deviceId,
            "workspace-11111111111111111111111111111111", "00000000-0000-4000-8000-000000000001")
        var persisted = 0
        val guard = ActiveWorkspaceSessionGuard(persistIncarnationGate = { persisted++ }) { replacement }
        guard.recordAccountFailure(CREDENTIALS, SelfHostedSyncHttpException(409, "stale", SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, true))
        assertEquals(0, persisted)
    }

    @Test
    fun uiCannotForgetTheOnlySessionForABoundWriter() {
        val delegate = MemoryStore(CREDENTIALS)
        val guarded = WorkspaceBoundSessionCredentialStore(
            delegate,
            ActiveWorkspaceSessionGuard {
                ActiveWorkspaceSessionRequirement(
                    CREDENTIALS.authorityBindingId,
                    CREDENTIALS.deviceId,
                    "workspace-00000000000000000000000000000000",
                )
            },
        )

        assertFailsWith<IllegalStateException> { guarded.clear() }

        assertNotNull(delegate.load())
    }

    @Test
    fun unboundSetupSessionCanStillBeForgotten() {
        val delegate = MemoryStore(CREDENTIALS)
        val guarded = WorkspaceBoundSessionCredentialStore(
            delegate,
            ActiveWorkspaceSessionGuard { null },
        )

        guarded.clear()

        assertNull(delegate.load())
    }

    @Test
    fun boundCredentialViewIgnoresAnotherGloballySelectedAuthority() {
        val otherCredentials = CREDENTIALS.copy(
            endpoint = "https://other.example",
            userId = "user-b",
            userEmail = "other@example.com",
        )
        val delegate = AuthorityMemoryStore(
            current = otherCredentials,
            byAuthority = mapOf(CREDENTIALS.authorityBindingId to CREDENTIALS),
        )
        val guarded = WorkspaceBoundSessionCredentialStore(
            delegate,
            ActiveWorkspaceSessionGuard {
                ActiveWorkspaceSessionRequirement(
                    CREDENTIALS.authorityBindingId,
                    CREDENTIALS.deviceId,
                    "workspace-00000000000000000000000000000000",
                )
            },
        )

        assertEquals(CREDENTIALS, guarded.load())
        assertEquals(otherCredentials, delegate.load())
    }

    @Test
    fun boundCredentialViewFailsClosedWhenItsAuthorityCredentialIsMissing() {
        val otherCredentials = CREDENTIALS.copy(
            endpoint = "https://other.example",
            userId = "user-b",
            userEmail = "other@example.com",
        )
        val guarded = WorkspaceBoundSessionCredentialStore(
            AuthorityMemoryStore(current = otherCredentials, byAuthority = emptyMap()),
            ActiveWorkspaceSessionGuard {
                ActiveWorkspaceSessionRequirement(
                    CREDENTIALS.authorityBindingId,
                    CREDENTIALS.deviceId,
                    "workspace-00000000000000000000000000000000",
                )
            },
        )

        assertNull(guarded.load())
    }

    private class MemoryStore(
        private var credentials: SelfHostedSessionCredentials?,
    ) : SelfHostedSessionCredentialStore {
        override fun load(): SelfHostedSessionCredentials? = credentials

        override fun save(credentials: SelfHostedSessionCredentials) {
            this.credentials = credentials
        }

        override fun clear() {
            credentials = null
        }
    }

    private class AuthorityMemoryStore(
        private var current: SelfHostedSessionCredentials?,
        private val byAuthority: Map<String, SelfHostedSessionCredentials>,
    ) : SelfHostedSessionCredentialStore {
        override fun load(): SelfHostedSessionCredentials? = current

        override fun save(credentials: SelfHostedSessionCredentials) {
            current = credentials
        }

        override fun clear() {
            current = null
        }

        override fun loadForAuthority(authorityBindingId: String): SelfHostedSessionCredentials? =
            byAuthority[authorityBindingId]
    }

    private companion object {
        val CREDENTIALS = SelfHostedSessionCredentials(
            endpoint = "https://sync.example",
            userId = "user-a",
            userEmail = "user@example.com",
            deviceId = "device-a",
            deviceName = "Test device",
            devicePlatform = "test",
            accessToken = "access",
            refreshToken = "refresh",
        )
    }
}
