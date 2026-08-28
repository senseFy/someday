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
