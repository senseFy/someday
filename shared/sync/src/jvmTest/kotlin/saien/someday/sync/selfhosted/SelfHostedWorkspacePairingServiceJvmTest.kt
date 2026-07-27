@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.selfhosted

import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.domain.settings.WorkspaceJoinPackageProvider
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspaceJoiner
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator

class SelfHostedWorkspacePairingServiceJvmTest {
    @Test
    fun invitationIsEncryptedClaimedCompletedAndOneUse() {
        val transport = MemorySelfHostedPairingTransport()
        val store = MemorySessionStore(testCredentials())
        val packageData = testSelfHostedPackage()
        var joinedPackage: WorkspaceJoinPackage? = null
        var joinCount = 0
        val service = pairingService(
            transport = transport,
            store = store,
            packageData = packageData,
            joiner = WorkspaceJoiner {
                joinCount += 1
                joinedPackage = it
                WorkspaceJoinResult.success("Joined.")
            },
        )

        val invitation = assertNotNull(service.createInvitation().invitation)
        assertFalse(transport.envelopesText().contains(packageData.recoveryCode))
        assertFalse(transport.envelopesText().contains(packageData.metadataJson))

        val joined = service.joinWithToken(invitation.revealQrPayload())

        assertTrue(joined.success)
        assertEquals(packageData, joinedPackage)
        assertEquals(1, joinCount)
        assertEquals(1, transport.completeCount)
        assertTrue(transport.allCompleted())

        val replay = service.joinWithToken(invitation.revealManualToken())
        assertFalse(replay.success)
        assertEquals(1, joinCount)
    }

    @Test
    fun localHistoryBlocksBeforeClaim() {
        val transport = MemorySelfHostedPairingTransport()
        val store = MemorySessionStore(testCredentials())
        val invitation = assertNotNull(pairingService(transport, store).createInvitation().invitation)

        val blocked = pairingService(
            transport = transport,
            store = store,
            localV2KeyBoundStatePresent = { true },
        ).joinWithToken(invitation.revealManualToken())

        assertFalse(blocked.success)
        assertEquals(0, transport.claimCount)
        assertTrue(blocked.message.contains("local Sync V2 history"))
    }

    @Test
    fun digestFailureNeverReachesJoinerButConsumesClaim() {
        val transport = MemorySelfHostedPairingTransport()
        val store = MemorySessionStore(testCredentials())
        var joinCalled = false
        val service = pairingService(
            transport = transport,
            store = store,
            joiner = WorkspaceJoiner {
                joinCalled = true
                WorkspaceJoinResult.success("Unexpected.")
            },
        )
        val invitation = assertNotNull(service.createInvitation().invitation)
        transport.tamperNextClaimDigest = true

        val result = service.joinWithToken(invitation.revealManualToken())

        assertFalse(result.success)
        assertFalse(joinCalled)
        assertEquals(1, transport.completeCount)
        assertTrue(transport.allCompleted())
    }

    @Test
    fun cancellationMakesInvitationUnclaimable() {
        val transport = MemorySelfHostedPairingTransport()
        val store = MemorySessionStore(testCredentials())
        val service = pairingService(transport, store)
        val invitation = assertNotNull(service.createInvitation().invitation)

        assertTrue(service.cancelInvitation(invitation).success)
        assertFalse(service.joinWithToken(invitation.revealManualToken()).success)
        assertEquals(1, transport.cancelCount)
        assertEquals(0, transport.completeCount)
    }

    @Test
    fun pairingUsesSharedRefreshExecutorAfterUnauthorizedResponse() {
        val transport = MemorySelfHostedPairingTransport().apply {
            rejectFirstCreateToken = "expired-access"
        }
        val store = MemorySessionStore(testCredentials(accessToken = "expired-access"))
        val service = pairingService(transport, store)

        val created = service.createInvitation()

        assertTrue(created.success)
        assertEquals(1, transport.refreshCount)
        assertEquals("fresh-access", store.load()?.accessToken)
        assertEquals("fresh-refresh", store.load()?.refreshToken)
        assertEquals(listOf("expired-access", "fresh-access"), transport.createTokens)
    }
}

private fun pairingService(
    transport: MemorySelfHostedPairingTransport,
    store: MemorySessionStore,
    packageData: WorkspaceJoinPackage = testSelfHostedPackage(),
    joiner: WorkspaceJoiner = WorkspaceJoiner { WorkspaceJoinResult.success("Joined.") },
    localV2KeyBoundStatePresent: () -> Boolean = { false },
): SelfHostedWorkspacePairingService =
    SelfHostedWorkspacePairingService(
        settingsProvider = {
            ClientSettings(
                syncConfiguration = SyncConfiguration(
                    mode = SyncMode.SelfHosted,
                    selfHostedEndpoint = store.load()?.endpoint,
                    selfHostedSession = store.load()?.toSummary()
                        ?: error("test session missing"),
                ),
            )
        },
        sessionStore = store,
        transport = transport,
        sessionExecutor = RefreshingSelfHostedSessionExecutor(transport, store),
        workspaceJoinPackageProvider = WorkspaceJoinPackageProvider {
            WorkspaceJoinResult.success("Created.", packageData)
        },
        workspaceJoiner = joiner,
        localV2KeyBoundStatePresent = localV2KeyBoundStatePresent,
        authorityMutationCoordinator = WorkspaceAuthorityMutationCoordinator(),
        clock = { Instant.fromEpochMilliseconds(1_000) },
    )

private fun testCredentials(accessToken: String = "access"): SelfHostedSessionCredentials =
    SelfHostedSessionCredentials(
        endpoint = "https://sync.example.com",
        userId = "user-a",
        userEmail = "alice@example.com",
        deviceId = "device-a",
        deviceName = "Phone",
        devicePlatform = "android",
        accessToken = accessToken,
        refreshToken = "refresh",
    )

private fun testSelfHostedPackage(): WorkspaceJoinPackage =
    WorkspaceJoinPackage(
        metadataJson = """{"workspaceId":"workspace-a"}""",
        recoveryCode = "SOMEDAY-SECRET-RECOVERY",
        workspaceId = "workspace-a",
        keyFingerprint = "fingerprint-a",
    )

private class MemorySessionStore(
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

private class MemorySelfHostedPairingTransport : SelfHostedSyncTransport {
    private val invitations = mutableMapOf<String, Invite>()

    var rejectFirstCreateToken: String? = null
    var tamperNextClaimDigest: Boolean = false
    var refreshCount: Int = 0
        private set
    var claimCount: Int = 0
        private set
    var completeCount: Int = 0
        private set
    var cancelCount: Int = 0
        private set
    val createTokens = mutableListOf<String>()

    override fun register(endpoint: String, request: SelfHostedAuthRequest): SelfHostedAuthTokensResponse =
        error("unused")

    override fun login(endpoint: String, request: SelfHostedAuthRequest): SelfHostedAuthTokensResponse =
        error("unused")

    override fun refresh(endpoint: String, request: SelfHostedRefreshRequest): SelfHostedAuthTokensResponse {
        refreshCount += 1
        return SelfHostedAuthTokensResponse(
            accessToken = "fresh-access",
            refreshToken = "fresh-refresh",
            expiresInSeconds = 900,
            user = SelfHostedUserResponse("user-a", "alice@example.com"),
        )
    }

    override fun registerDevice(
        endpoint: String,
        accessToken: String,
        request: SelfHostedDeviceRegistrationRequest,
    ): SelfHostedDeviceRegistrationResponse = error("unused")

    override fun createPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCreateRequest,
    ): SelfHostedPairingInviteCreateResponse {
        createTokens += accessToken
        if (rejectFirstCreateToken == accessToken) {
            rejectFirstCreateToken = null
            throw SelfHostedSyncHttpException(401, "unauthorized")
        }
        if (invitations.putIfAbsent(inviteId, Invite(request)) != null) {
            throw SelfHostedSyncHttpException(409, "conflict")
        }
        return SelfHostedPairingInviteCreateResponse("created", request.expiresAtEpochMillis)
    }

    override fun claimPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteClaimRequest,
    ): SelfHostedPairingInviteClaimResponse {
        claimCount += 1
        val invite = invitations[inviteId] ?: throw SelfHostedSyncHttpException(404, "missing")
        if (invite.state != "available") throw SelfHostedSyncHttpException(409, "claimed")
        invite.state = "claimed"
        invite.claimId = request.claimId
        val digest = if (tamperNextClaimDigest) {
            tamperNextClaimDigest = false
            "tampered"
        } else {
            invite.request.envelopeDigest
        }
        return SelfHostedPairingInviteClaimResponse(
            envelopeJson = invite.request.envelopeJson,
            envelopeDigest = digest,
            expiresAtEpochMillis = invite.request.expiresAtEpochMillis,
        )
    }

    override fun completePairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCompleteRequest,
    ) {
        val invite = invitations[inviteId] ?: throw SelfHostedSyncHttpException(404, "missing")
        if (invite.state != "claimed" || invite.claimId != request.claimId) {
            throw SelfHostedSyncHttpException(409, "conflict")
        }
        completeCount += 1
        invite.state = "completed"
    }

    override fun cancelPairingInvite(endpoint: String, accessToken: String, inviteId: String) {
        val invite = invitations[inviteId] ?: throw SelfHostedSyncHttpException(404, "missing")
        if (invite.state != "available") throw SelfHostedSyncHttpException(409, "conflict")
        cancelCount += 1
        invite.state = "cancelled"
    }

    fun envelopesText(): String = invitations.values.joinToString("\n") { it.request.envelopeJson }

    fun allCompleted(): Boolean = invitations.values.all { it.state == "completed" }

    private data class Invite(
        val request: SelfHostedPairingInviteCreateRequest,
        var state: String = "available",
        var claimId: String? = null,
    )
}
