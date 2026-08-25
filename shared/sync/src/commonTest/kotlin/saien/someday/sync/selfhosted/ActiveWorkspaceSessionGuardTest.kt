package saien.someday.sync.selfhosted

import kotlin.test.Test
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
