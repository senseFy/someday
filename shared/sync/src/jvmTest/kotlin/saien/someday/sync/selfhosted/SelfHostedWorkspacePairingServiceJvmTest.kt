@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.selfhosted

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.domain.settings.WorkspaceJoinPackageProvider
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspaceJoiner
import saien.someday.domain.settings.WorkspacePairingReason
import saien.someday.sync.WorkspaceLifecycleCoordinator

class SelfHostedWorkspacePairingServiceJvmTest {
    @Test
    fun invitationIsEncryptedClaimedCompletedAndOneUse() {
        val transport = MemorySelfHostedPairingTransport()
        val store = MemorySessionStore(testCredentials())
        val packageData = testSelfHostedPackage()
        var joinedPackage: WorkspaceJoinPackage? = null
        var replacementAuthorized: Boolean? = null
        var joinCount = 0
        val service = pairingService(
            transport = transport,
            store = store,
            packageData = packageData,
            joiner = WorkspaceJoiner { receivedPackage, replaceExistingWorkspace ->
                joinCount += 1
                joinedPackage = receivedPackage
                replacementAuthorized = replaceExistingWorkspace
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
        )

        val invitation = assertNotNull(service.createInvitation().invitation)
        assertFalse(transport.envelopesText().contains(packageData.recoveryCode))
        assertFalse(transport.envelopesText().contains(packageData.metadataJson))

        val joined = service.joinWithToken(
            tokenInput = invitation.revealQrPayload(),
            replaceExistingWorkspace = true,
        )

        assertTrue(joined.success, joined.diagnosticMessage)
        assertEquals(WorkspacePairingReason.Joined, joined.reason)
        assertEquals(packageData, joinedPackage)
        assertEquals(true, replacementAuthorized)
        assertEquals(1, joinCount)
        assertEquals(1, transport.claimCount)
        assertEquals(1, transport.completeCount)
        assertTrue(transport.allCompleted())

        val replay = service.joinWithToken(
            tokenInput = invitation.revealManualToken(),
            replaceExistingWorkspace = true,
        )
        assertFalse(replay.success)
        assertEquals(
            WorkspacePairingReason.InvitationAlreadyUsed,
            replay.reason,
            replay.diagnosticMessage,
        )
        assertEquals(1, joinCount)
    }

    @Test
    fun replacementWithoutExplicitConfirmationIsRejectedBeforeClaim() {
        val transport = MemorySelfHostedPairingTransport()
        val store = MemorySessionStore(testCredentials())
        val invitation = assertNotNull(pairingService(transport, store).createInvitation().invitation)
        var joinCount = 0

        val blocked = pairingService(
            transport = transport,
            store = store,
            joiner = WorkspaceJoiner { _, _ ->
                joinCount += 1
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
        ).joinWithToken(
            tokenInput = invitation.revealManualToken(),
            replaceExistingWorkspace = false,
        )

        assertFalse(blocked.success)
        assertEquals(0, transport.claimCount)
        assertEquals(0, transport.completeCount)
        assertEquals(0, joinCount)
        assertEquals(
            WorkspacePairingReason.ReplacementConfirmationRequired,
            blocked.reason,
            blocked.diagnosticMessage,
        )
    }

    @Test
    fun claimTransportFailureIsClassifiedWithoutConsumingTheInvitation() {
        val transport = MemorySelfHostedPairingTransport()
        val store = MemorySessionStore(testCredentials())
        var joinCount = 0
        val service = pairingService(
            transport = transport,
            store = store,
            joiner = WorkspaceJoiner { _, _ ->
                joinCount += 1
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
        )
        val invitation = assertNotNull(service.createInvitation().invitation)
        transport.failNextClaim = IllegalStateException("token=must-not-leak connection refused")

        val result = service.joinWithToken(
            tokenInput = invitation.revealManualToken(),
            replaceExistingWorkspace = true,
        )

        assertFalse(result.success)
        assertEquals(WorkspacePairingReason.ServerRequestFailed, result.reason)
        assertFalse(result.diagnosticMessage.orEmpty().contains("must-not-leak"))
        assertEquals(0, joinCount)
        assertEquals(0, transport.completeCount)
        assertTrue(transport.allAvailable())
    }

    @Test
    fun confirmedReplacementWaitsForActiveWorkspaceLifecycleBeforeClaimOrCommit() {
        val transport = MemorySelfHostedPairingTransport()
        val store = MemorySessionStore(testCredentials())
        val workspaceLifecycleCoordinator = WorkspaceLifecycleCoordinator()
        val replacementAttempting = CountDownLatch(1)
        val replacementCommits = AtomicInteger()
        val service = pairingService(
            transport = transport,
            store = store,
            workspaceLifecycleCoordinator = workspaceLifecycleCoordinator,
            joiner = WorkspaceJoiner { _, _ ->
                replacementCommits.incrementAndGet()
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
        )
        val invitation = assertNotNull(service.createInvitation().invitation)
        val lifecycleEntered = CountDownLatch(1)
        val releaseLifecycle = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val activeOperation = executor.submit {
                workspaceLifecycleCoordinator.exclusive {
                    lifecycleEntered.countDown()
                    assertTrue(releaseLifecycle.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(lifecycleEntered.await(5, TimeUnit.SECONDS))

            val replacement = executor.submit(java.util.concurrent.Callable {
                replacementAttempting.countDown()
                service.joinWithToken(
                    tokenInput = invitation.revealManualToken(),
                    replaceExistingWorkspace = true,
                )
            })
            assertTrue(replacementAttempting.await(5, TimeUnit.SECONDS))
            assertEquals(0, transport.claimCount)
            assertEquals(0, transport.completeCount)
            assertEquals(0, replacementCommits.get())

            releaseLifecycle.countDown()
            activeOperation.get(5, TimeUnit.SECONDS)
            val result = replacement.get(5, TimeUnit.SECONDS)
            assertTrue(result.success, result.diagnosticMessage)
            assertEquals(1, transport.claimCount)
            assertEquals(1, transport.completeCount)
            assertEquals(1, replacementCommits.get())
        } finally {
            releaseLifecycle.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun digestFailureNeverReachesJoinerButConsumesClaim() {
        val transport = MemorySelfHostedPairingTransport()
        val store = MemorySessionStore(testCredentials())
        var joinCalled = false
        val service = pairingService(
            transport = transport,
            store = store,
            joiner = WorkspaceJoiner { _, _ ->
                joinCalled = true
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
        )
        val invitation = assertNotNull(service.createInvitation().invitation)
        transport.tamperNextClaimDigest = true

        val result = service.joinWithToken(
            tokenInput = invitation.revealManualToken(),
            replaceExistingWorkspace = true,
        )

        assertFalse(result.success)
        assertEquals(
            WorkspacePairingReason.VerificationFailed,
            result.reason,
            result.diagnosticMessage,
        )
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

        val cancelled = service.cancelInvitation(invitation)
        assertTrue(cancelled.success, cancelled.diagnosticMessage)
        assertEquals(WorkspacePairingReason.InvitationCancelled, cancelled.reason)
        val rejectedJoin = service.joinWithToken(
            tokenInput = invitation.revealManualToken(),
            replaceExistingWorkspace = true,
        )
        assertFalse(rejectedJoin.success)
        assertEquals(
            WorkspacePairingReason.InvitationAlreadyUsed,
            rejectedJoin.reason,
            rejectedJoin.diagnosticMessage,
        )
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

        assertTrue(created.success, created.diagnosticMessage)
        assertEquals(WorkspacePairingReason.InvitationCreated, created.reason)
        assertEquals(1, transport.refreshCount)
        assertEquals("fresh-access", store.load()?.accessToken)
        assertEquals("fresh-refresh", store.load()?.refreshToken)
        assertEquals(listOf("expired-access", "fresh-access"), transport.createTokens)
    }

    @Test
    fun refreshCannotChangeTheAuthenticatedAccountBinding() {
        val transport = MemorySelfHostedPairingTransport().apply {
            refreshUserId = "another-user"
        }
        val original = testCredentials(accessToken = "expired-access")
        val store = MemorySessionStore(original)
        val executor = RefreshingSelfHostedSessionExecutor(transport, store)

        assertFailsWith<SelfHostedSyncHttpException> {
            executor.authorized(original.endpoint, original.userId, original.accessToken) {
                throw SelfHostedSyncHttpException(401, "unauthorized")
            }
        }

        assertEquals(original, store.load())
    }

    @Test
    fun mismatchedBoundSessionIsRejectedBeforeWorkspaceKeyPackageOrInvitePost() {
        val credentials = testCredentials()
        val transport = MemorySelfHostedPairingTransport()
        var packageRequests = 0
        val service = pairingService(
            transport = transport,
            store = MemorySessionStore(credentials),
            activeWorkspaceSessionGuard = ActiveWorkspaceSessionGuard {
                ActiveWorkspaceSessionRequirement(
                    credentials.copy(userId = "another-user").authorityBindingId,
                    credentials.deviceId,
                    "workspace-00000000000000000000000000000000",
                )
            },
            onPackageRequest = { packageRequests++ },
        )

        val result = service.createInvitation()

        assertFalse(result.success)
        assertEquals(
            WorkspacePairingReason.AuthorityMismatch,
            result.reason,
            result.diagnosticMessage,
        )
        assertEquals(0, packageRequests)
        assertTrue(transport.createTokens.isEmpty())
    }

    @Test
    fun unpublishedPreparingWorkspaceCannotCreateInvitation() {
        val transport = MemorySelfHostedPairingTransport()
        var packageRequests = 0
        val service = pairingService(
            transport = transport,
            store = MemorySessionStore(testCredentials()),
            workspacePairingInviterReady = { false },
            onPackageRequest = { packageRequests++ },
        )

        val result = service.createInvitation()

        assertFalse(result.success)
        assertEquals(
            WorkspacePairingReason.PublishRequired,
            result.reason,
            result.diagnosticMessage,
        )
        assertEquals(0, packageRequests)
        assertTrue(transport.createTokens.isEmpty())
    }
}

private fun pairingService(
    transport: MemorySelfHostedPairingTransport,
    store: MemorySessionStore,
    packageData: WorkspaceJoinPackage = testSelfHostedPackage(),
    joiner: WorkspaceJoiner = WorkspaceJoiner { _, _ ->
        WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
    },
    activeWorkspaceSessionGuard: ActiveWorkspaceSessionGuard = ActiveWorkspaceSessionGuard { null },
    workspacePairingInviterReady: () -> Boolean = { true },
    onPackageRequest: () -> Unit = {},
    workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator =
        WorkspaceLifecycleCoordinator(),
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
            onPackageRequest()
            WorkspaceJoinResult.success(
                reason = WorkspacePairingReason.PackageCreated,
                packageData = packageData,
            )
        },
        workspaceJoiner = joiner,
        workspaceLifecycleCoordinator = workspaceLifecycleCoordinator,
        activeWorkspaceSessionGuard = activeWorkspaceSessionGuard,
        workspacePairingInviterReady = workspacePairingInviterReady,
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

internal class MemorySessionStore(
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

internal class MemorySelfHostedPairingTransport : SelfHostedSyncTransport {
    private val invitations = mutableMapOf<String, Invite>()

    var rejectFirstCreateToken: String? = null
    var tamperNextClaimDigest: Boolean = false
    var failNextClaim: Throwable? = null
    var refreshCount: Int = 0
        private set
    var refreshUserId: String = "user-a"
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
            user = SelfHostedUserResponse(refreshUserId, "alice@example.com"),
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
        failNextClaim?.let { failure ->
            failNextClaim = null
            throw failure
        }
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

    fun allAvailable(): Boolean = invitations.values.all { it.state == "available" }

    private data class Invite(
        val request: SelfHostedPairingInviteCreateRequest,
        var state: String = "available",
        var claimId: String? = null,
    )
}
