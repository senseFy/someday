package saien.someday.sync.selfhosted

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId
import saien.someday.sync.WorkspaceLifecycleCoordinator

class SelfHostedSetupServiceTest {
    @Test
    fun nonzeroBoundWorkspaceCanRenewMissingCredentialsWithoutLosingItsIncarnation() {
        val credentials = setupCredentials()
        val incarnation = "00000000-0000-4000-8000-000000000001"
        val store = MemorySessionStore(null)
        val transport = RecordingSetupTransport().apply { loginIncarnation = incarnation }
        val result = service(transport, store,
            ActiveWorkspaceSessionRequirement(credentials.authorityBindingId, credentials.deviceId,
                "workspace-00000000000000000000000000000000", incarnation)).setup(input())
        assertTrue(result.success, result.status.diagnosticMessage)
        assertEquals(1, transport.deviceRegistrationCalls)
        assertEquals(incarnation, store.load()?.accountIncarnation)
    }

    @Test
    fun passwordRenewalWithNewIssuanceIncarnationNeverRegistersOrReplacesExistingCredentials() {
        val credentials = setupCredentials()
        val store = MemorySessionStore(credentials)
        val transport = RecordingSetupTransport().apply { loginIncarnation = "00000000-0000-4000-8000-000000000001" }
        val requirement = ActiveWorkspaceSessionRequirement(credentials.authorityBindingId, credentials.deviceId,
            "workspace-00000000000000000000000000000000")
        var persisted: ActiveWorkspaceSessionRequirement? = null
        val service = SelfHostedSetupService(transport, store,
            ActiveWorkspaceSessionGuard(persistIncarnationGate = { persisted = it }) { requirement },
            WorkspaceLifecycleCoordinator(), { LOCAL_DEVICE_ID })
        val result = service.setup(input())
        assertFalse(result.success)
        assertEquals(saien.someday.domain.settings.SelfHostedSetupReason.AccountIncarnationMismatch, result.status.reason)
        assertEquals(1, transport.loginCalls)
        assertEquals(0, transport.deviceRegistrationCalls)
        assertEquals(credentials, store.load())
        assertEquals(requirement, persisted)
    }

    @Test
    fun passwordRenewalPersistsMismatchBeforeReleasingTheReplacementBoundary() {
        val credentials = setupCredentials()
        val store = MemorySessionStore(credentials)
        val transport = RecordingSetupTransport().apply { loginIncarnation = "00000000-0000-4000-8000-000000000001" }
        val requirement = ActiveWorkspaceSessionRequirement(credentials.authorityBindingId, credentials.deviceId,
            "workspace-00000000000000000000000000000000")
        val lifecycle = WorkspaceLifecycleCoordinator()
        val started = CountDownLatch(1)
        val replacementEntered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var replacementRacedGate = false
        val guard = ActiveWorkspaceSessionGuard(persistIncarnationGate = {
            executor.submit {
                started.countDown()
                lifecycle.exclusive { replacementEntered.countDown() }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            replacementRacedGate = replacementEntered.await(250, TimeUnit.MILLISECONDS)
        }) { requirement }
        try {
            val result = SelfHostedSetupService(transport, store, guard, lifecycle, { LOCAL_DEVICE_ID }).setup(input())
            assertFalse(result.success)
            assertFalse(replacementRacedGate, "A replacement must wait until the original workspace's mismatch gate is persisted.")
            assertTrue(replacementEntered.await(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun resetBetweenPasswordLoginAndDeviceRegistrationFreezesTheBoundCopy() {
        val credentials = setupCredentials()
        val store = MemorySessionStore(credentials)
        val transport = RecordingSetupTransport().apply {
            registrationFailure = SelfHostedErrorCode.ACCOUNT_SESSION_STALE
        }
        val requirement = ActiveWorkspaceSessionRequirement(
            credentials.authorityBindingId,
            credentials.deviceId,
            "workspace-00000000000000000000000000000000",
        )
        var persisted: ActiveWorkspaceSessionRequirement? = null
        val service = SelfHostedSetupService(
            transport,
            store,
            ActiveWorkspaceSessionGuard(persistIncarnationGate = { persisted = it }) { requirement },
            WorkspaceLifecycleCoordinator(),
            { LOCAL_DEVICE_ID },
        )

        val result = service.setup(input())

        assertFalse(result.success)
        assertEquals(saien.someday.domain.settings.SelfHostedSetupReason.AccountIncarnationMismatch, result.status.reason)
        assertEquals(1, transport.loginCalls)
        assertEquals(1, transport.deviceRegistrationCalls)
        assertEquals(credentials, store.load())
        assertEquals(requirement, persisted)
    }

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
        workspaceLifecycleCoordinator = WorkspaceLifecycleCoordinator(),
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
    var loginIncarnation: String = saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
    var registeredDeviceRevoked: Boolean = false
    var registrationFailure: SelfHostedErrorCode? = null
    var loginCalls: Int = 0
        private set
    var deviceRegistrationCalls: Int = 0
        private set

    override fun register(endpoint: String, request: SelfHostedAuthRequest,
            accountContext: saien.someday.sync.selfhosted.SelfHostedAccountRequestContext,
        ): SelfHostedAuthTokensResponse =
        error("unused")

    override fun login(endpoint: String, request: SelfHostedAuthRequest,
            accountContext: saien.someday.sync.selfhosted.SelfHostedAccountRequestContext,
        ): SelfHostedAuthTokensResponse {
        loginCalls++
        return SelfHostedAuthTokensResponse(
            accessToken = "account-access",
            refreshToken = "account-refresh",
            expiresInSeconds = 900,
            user = SelfHostedUserResponse(loginUserId, request.email),
            accountIncarnation = loginIncarnation,
            accountProtocolVersion = 1,
        )
    }

    override fun refresh(endpoint: String, request: SelfHostedRefreshRequest,
            accountContext: saien.someday.sync.selfhosted.SelfHostedAccountRequestContext,
        ): SelfHostedAuthTokensResponse =
        error("unused")

    override fun registerDevice(
        endpoint: String,
        accessToken: String,
        request: SelfHostedDeviceRegistrationRequest,
            accountContext: saien.someday.sync.selfhosted.SelfHostedAccountRequestContext,
        ): SelfHostedDeviceRegistrationResponse {
        deviceRegistrationCalls++
        registrationFailure?.let { code ->
            throw SelfHostedSyncHttpException(401, "Device registration rejected the retired session.", code, true)
        }
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
            accountIncarnation = loginIncarnation,
            accountProtocolVersion = 1,
        )
    }

    override fun createPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCreateRequest,
            accountContext: saien.someday.sync.selfhosted.SelfHostedAccountRequestContext,
        ): SelfHostedPairingInviteCreateResponse = error("unused")

    override fun claimPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteClaimRequest,
            accountContext: saien.someday.sync.selfhosted.SelfHostedAccountRequestContext,
        ): SelfHostedPairingInviteClaimResponse = error("unused")

    override fun completePairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCompleteRequest,
            accountContext: saien.someday.sync.selfhosted.SelfHostedAccountRequestContext,
        ) = error("unused")

    override fun cancelPairingInvite(endpoint: String, accessToken: String, inviteId: String,
            accountContext: saien.someday.sync.selfhosted.SelfHostedAccountRequestContext,
        ) =
        error("unused")
}

private const val LOCAL_DEVICE_ID = "00000000-0000-4000-8000-000000000001"
