package saien.someday.sync.selfhosted

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator

class SelfHostedSetupServiceTest {
    @Test
    fun boundWorkspaceRenewsMissingSessionForItsExactStableDevice() {
        val credentials = setupCredentials()
        val store = MemorySessionStore(null)
        val transport = RecordingSetupTransport()
        val service = service(
            transport,
            store,
            ActiveWorkspaceSessionRequirement(
                credentials.authorityBindingId,
                credentials.deviceId,
                "workspace-00000000000000000000000000000000",
            ),
        )

        val result = service.setup(input())

        assertTrue(result.success)
        assertEquals(1, transport.loginCalls)
        assertEquals(1, transport.deviceRegistrationCalls)
        assertEquals(credentials.authorityBindingId, store.load()?.authorityBindingId)
        assertEquals(credentials.deviceId, store.load()?.deviceId)
        assertEquals("device-access", store.load()?.accessToken)
    }

    @Test
    fun boundWorkspaceRejectsAnotherEndpointBeforeAnyRemoteRequestAndPreservesSession() {
        val credentials = setupCredentials()
        val store = MemorySessionStore(credentials)
        val transport = RecordingSetupTransport()
        val service = service(
            transport,
            store,
            ActiveWorkspaceSessionRequirement(
                credentials.authorityBindingId,
                credentials.deviceId,
                "workspace-00000000000000000000000000000000",
            ),
        )

        val result = service.setup(input(endpoint = "https://other.example.com"))

        assertFalse(result.success)
        assertEquals(0, transport.loginCalls)
        assertEquals(0, transport.deviceRegistrationCalls)
        assertEquals(credentials, store.load())
    }

    @Test
    fun boundWorkspaceRejectsAnotherAuthenticatedAccountBeforeDeviceRegistration() {
        val credentials = setupCredentials()
        val store = MemorySessionStore(credentials)
        val transport = RecordingSetupTransport().apply { loginUserId = "user-b" }
        val service = service(
            transport,
            store,
            ActiveWorkspaceSessionRequirement(
                credentials.authorityBindingId,
                credentials.deviceId,
                "workspace-00000000000000000000000000000000",
            ),
        )

        val result = service.setup(input(email = "bob@example.com"))

        assertFalse(result.success)
        assertEquals(1, transport.loginCalls)
        assertEquals(0, transport.deviceRegistrationCalls)
        assertEquals(credentials, store.load())
    }

    @Test
    fun revokedBoundDeviceCannotBeResurrected() {
        val credentials = setupCredentials()
        val store = MemorySessionStore(credentials)
        val transport = RecordingSetupTransport().apply { registeredDeviceRevoked = true }
        val service = service(
            transport,
            store,
            ActiveWorkspaceSessionRequirement(
                credentials.authorityBindingId,
                credentials.deviceId,
                "workspace-00000000000000000000000000000000",
            ),
        )

        val result = service.setup(input())

        assertFalse(result.success)
        assertEquals(1, transport.loginCalls)
        assertEquals(1, transport.deviceRegistrationCalls)
        assertEquals(credentials, store.load())
    }

    @Test
    fun unboundWorkspaceAuthenticatesAndRegistersItsFirstStableDevice() {
        val store = MemorySessionStore(null)
        val transport = RecordingSetupTransport()

        val result = service(transport, store, null).setup(input())

        assertTrue(result.success)
        assertEquals(1, transport.loginCalls)
        assertEquals(1, transport.deviceRegistrationCalls)
        assertEquals(LOCAL_DEVICE_ID, store.load()?.deviceId)
    }

    private fun service(
        transport: RecordingSetupTransport,
        store: MemorySessionStore,
        requirement: ActiveWorkspaceSessionRequirement?,
    ) = SelfHostedSetupService(
        transport = transport,
        sessionStore = store,
        activeWorkspaceSessionGuard = ActiveWorkspaceSessionGuard { requirement },
        authorityMutationCoordinator = WorkspaceAuthorityMutationCoordinator(),
        localDeviceIdProvider = { LOCAL_DEVICE_ID },
    )

    private fun input(
        endpoint: String = "https://sync.example.com",
        email: String = "alice@example.com",
    ) = SelfHostedSetupInput(
        endpoint = endpoint,
        email = email,
        password = "password-redacted",
        deviceName = "Phone",
        platform = "android",
        createAccount = false,
    )
}

private fun setupCredentials(): SelfHostedSessionCredentials = SelfHostedSessionCredentials(
    endpoint = "https://sync.example.com",
    userId = "user-a",
    userEmail = "alice@example.com",
    deviceId = LOCAL_DEVICE_ID,
    deviceName = "Phone",
    devicePlatform = "android",
    accessToken = "access",
    refreshToken = "refresh",
)

private class RecordingSetupTransport : SelfHostedSyncTransport {
    var loginUserId: String = "user-a"
    var registeredDeviceRevoked: Boolean = false
    var loginCalls: Int = 0
        private set
    var deviceRegistrationCalls: Int = 0
        private set

    override fun register(endpoint: String, request: SelfHostedAuthRequest): SelfHostedAuthTokensResponse =
        error("unused")

    override fun login(endpoint: String, request: SelfHostedAuthRequest): SelfHostedAuthTokensResponse {
        loginCalls++
        return SelfHostedAuthTokensResponse(
            accessToken = "account-access",
            refreshToken = "account-refresh",
            expiresInSeconds = 900,
            user = SelfHostedUserResponse(loginUserId, request.email),
        )
    }

    override fun refresh(endpoint: String, request: SelfHostedRefreshRequest): SelfHostedAuthTokensResponse =
        error("unused")

    override fun registerDevice(
        endpoint: String,
        accessToken: String,
        request: SelfHostedDeviceRegistrationRequest,
    ): SelfHostedDeviceRegistrationResponse {
        deviceRegistrationCalls++
        return SelfHostedDeviceRegistrationResponse(
            device = SelfHostedDeviceResponse(
                request.deviceId,
                request.name,
                request.platform,
                revoked = registeredDeviceRevoked,
            ),
            accessToken = "device-access",
            refreshToken = "device-refresh",
            expiresInSeconds = 900,
        )
    }

    override fun createPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCreateRequest,
    ): SelfHostedPairingInviteCreateResponse = error("unused")

    override fun claimPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteClaimRequest,
    ): SelfHostedPairingInviteClaimResponse = error("unused")

    override fun completePairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCompleteRequest,
    ) = error("unused")

    override fun cancelPairingInvite(endpoint: String, accessToken: String, inviteId: String) =
        error("unused")
}

private const val LOCAL_DEVICE_ID = "00000000-0000-4000-8000-000000000001"
