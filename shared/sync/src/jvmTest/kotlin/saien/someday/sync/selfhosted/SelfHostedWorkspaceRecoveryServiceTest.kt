package saien.someday.sync.selfhosted

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.domain.settings.WorkspaceJoinPackageProvider
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspaceJoiner
import saien.someday.domain.settings.WorkspacePairingReason
import saien.someday.domain.settings.WorkspaceRecoveryReason
import saien.someday.domain.settings.WorkspaceRecoveryState
import saien.someday.domain.settings.WorkspaceRecoverySyncGate
import saien.someday.domain.settings.authorityBindingId
import saien.someday.sync.WorkspaceLifecycleCoordinator
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.SyncEpochPersistResultV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.ensureWorkspaceLocalDraftV2
import saien.someday.sync.pairing.WorkspacePairingEnvelopeCodec
import saien.someday.sync.resolveActiveWorkspaceSessionRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelfHostedWorkspaceRecoveryServiceTest {
    @Test
    fun preparationRequiresConfirmationBeforePublishingAndNeverUploadsTheCode() {
        val fixture = fixture()

        val prepared = fixture.service.prepareCode()

        val code = assertNotNull(prepared.recoveryCode).revealForUserConfirmation()
        assertTrue(prepared.success)
        assertEquals(WorkspaceRecoveryReason.CodePrepared, prepared.reason)
        assertEquals(0, fixture.transport.putCount)
        assertFalse(prepared.toString().contains(code))

        val wrong = fixture.service.confirmPreparedCode("SOMEDAY-WRONG")
        assertFalse(wrong.success)
        assertEquals(WorkspaceRecoveryReason.InvalidCode, wrong.reason)
        assertEquals(0, fixture.transport.putCount)

        val confirmed = fixture.service.confirmPreparedCode(
            code.lowercase().replace('-', ' '),
        )

        assertTrue(confirmed.success, confirmed.diagnosticMessage)
        assertEquals(WorkspaceRecoveryReason.CodeCreated, confirmed.reason)
        assertEquals(1, fixture.transport.putCount)
        val request = assertNotNull(fixture.transport.lastPut)
        assertNull(request.expectedRevision)
        assertFalse(request.envelopeJson.contains(code))
        assertFalse(request.toString().contains(code))
        assertEquals(WORKSPACE_ID, request.workspaceId)
        assertEquals(KEY_FINGERPRINT, request.keyFingerprint)
        val response = assertNotNull(fixture.transport.current)
        assertFalse(response.toString().contains(response.envelopeJson))
    }

    @Test
    fun cancellingPreparedCodeLeavesTheExistingEnvelopeUntouched() {
        val fixture = fixture()
        val firstCode = assertNotNull(fixture.service.prepareCode().recoveryCode)
            .revealForUserConfirmation()
        assertTrue(fixture.service.confirmPreparedCode(firstCode).success)
        val storedBefore = fixture.transport.current

        assertTrue(fixture.service.prepareCode().success)
        fixture.service.discardPreparedCode()
        val result = fixture.service.confirmPreparedCode(firstCode)

        assertFalse(result.success)
        assertEquals(WorkspaceRecoveryReason.Failed, result.reason)
        assertEquals(1, fixture.transport.putCount)
        assertEquals(storedBefore, fixture.transport.current)
    }

    @Test
    fun rotationUsesTheCurrentRevisionAndConcurrentChangeFailsClosed() {
        val fixture = fixture()
        val firstCode = assertNotNull(fixture.service.prepareCode().recoveryCode)
            .revealForUserConfirmation()
        assertTrue(fixture.service.confirmPreparedCode(firstCode).success)
        assertEquals(1L, fixture.transport.current?.revision)

        val secondCode = assertNotNull(fixture.service.prepareCode().recoveryCode)
            .revealForUserConfirmation()
        fixture.transport.forceRevisionBump()
        val conflict = fixture.service.confirmPreparedCode(secondCode)

        assertFalse(conflict.success)
        assertEquals(WorkspaceRecoveryReason.ServerConflict, conflict.reason)
        assertEquals(1, fixture.transport.putCount)
        assertFalse(conflict.diagnosticMessage.orEmpty().contains(secondCode))
    }

    @Test
    fun confirmationRejectsAWorkspaceKeyChangedAfterPreparation() {
        var fingerprint = KEY_FINGERPRINT
        val fixture = fixture(localKeyFingerprintProvider = { fingerprint })
        val code = assertNotNull(fixture.service.prepareCode().recoveryCode)
            .revealForUserConfirmation()
        fingerprint = "11111111111111111111111111111111"

        val result = fixture.service.confirmPreparedCode(code)

        assertFalse(result.success)
        assertEquals(WorkspaceRecoveryReason.AuthorityMismatch, result.reason)
        assertEquals(0, fixture.transport.putCount)
        assertFalse(fixture.service.confirmPreparedCode(code).success)
    }

    @Test
    fun retryAfterALostPutResponseReplaysTheSameEnvelopeWithoutGeneratingAnotherCode() {
        val fixture = fixture()
        val code = assertNotNull(fixture.service.prepareCode().recoveryCode)
            .revealForUserConfirmation()
        fixture.transport.failAfterNextStore()

        val uncertain = fixture.service.confirmPreparedCode(code)
        val retried = fixture.service.confirmPreparedCode(code)

        assertFalse(uncertain.success)
        assertEquals(WorkspaceRecoveryReason.ServerRequestFailed, uncertain.reason)
        assertTrue(retried.success, retried.diagnosticMessage)
        assertEquals(WorkspaceRecoveryReason.CodeCreated, retried.reason)
        assertEquals(1, fixture.packageRequests)
        assertEquals(1, fixture.transport.putCount)
    }

    @Test
    fun aCommittedEnvelopeRemainsAuthoritativeAfterThePublisherRestartsWithoutThePutResponse() {
        val owner = fixture()
        val code = assertNotNull(owner.service.prepareCode().recoveryCode)
            .revealForUserConfirmation()
        owner.transport.failAfterNextStore()

        val uncertain = owner.service.confirmPreparedCode(code)
        val restartedOwner = fixture(transport = owner.transport)
        val restartedStatus = restartedOwner.service.status()
        var receivedCode: String? = null
        val fresh = fixture(
            transport = owner.transport,
            requirement = null,
            publisherReady = false,
            joiner = WorkspaceJoiner { packageData, replace ->
                assertTrue(replace)
                receivedCode = packageData.recoveryCode
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
        )

        val freshStatus = fresh.service.status()
        val recovered = fresh.service.recover(code, replaceExistingWorkspace = true)

        assertFalse(uncertain.success)
        assertEquals(WorkspaceRecoveryReason.ServerRequestFailed, uncertain.reason)
        assertTrue(restartedStatus.success, restartedStatus.diagnosticMessage)
        assertEquals(WorkspaceRecoveryState.Configured, restartedStatus.state)
        assertTrue(freshStatus.success, freshStatus.diagnosticMessage)
        assertEquals(WorkspaceRecoveryState.RecoveryAvailable, freshStatus.state)
        assertTrue(recovered.success, recovered.diagnosticMessage)
        assertEquals(code.replace("-", ""), receivedCode)
        assertEquals(1, owner.transport.putCount)
    }

    @Test
    fun existingRemoteEnvelopeRequiresRecoveryOnAnUnboundFreshInstallation() {
        val owner = fixture()
        val code = assertNotNull(owner.service.prepareCode().recoveryCode).revealForUserConfirmation()
        assertTrue(owner.service.confirmPreparedCode(code).success)
        val fresh = fixture(
            transport = owner.transport,
            requirement = null,
            publisherReady = false,
        )

        val status = fresh.service.status()

        assertTrue(status.success, status.diagnosticMessage)
        assertEquals(WorkspaceRecoveryState.RecoveryAvailable, status.state)
        assertEquals(WorkspaceRecoverySyncGate.RecoveryRequired, status.syncGate)
        assertEquals(WorkspaceRecoveryReason.RecoveryAvailable, status.reason)
        assertEquals(0, fresh.packageRequests)
    }

    @Test
    fun abandoningARejectedGenesisRemovesItsRealAuthorityBeforeRecoveryStatusRefresh() {
        val owner = fixture()
        val code = assertNotNull(owner.service.prepareCode().recoveryCode).revealForUserConfirmation()
        assertTrue(owner.service.confirmPreparedCode(code).success)
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val database = SomedayDatabase(driver)
            val local = SqlDelightLocalDataRepository(database, LOCAL_WRITER_DEVICE_ID)
            val settings = SqlDelightClientSettingsRepository(local)
            val workspaceKey = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 31).toByte() })
            val draft = ensureWorkspaceLocalDraftV2(local, settings, workspaceKey)
            val protocol = SqlDelightSyncProtocolStoreV2(database)
            assertIs<SyncEpochPersistResultV2.AlreadyStored>(
                protocol.persistPreparingEpoch(
                    remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
                    descriptor = draft.descriptor,
                    descriptorDigest = draft.descriptorDigest,
                    authorityBindingId = credentials().authorityBindingId,
                    localWriterDeviceId = LOCAL_WRITER_DEVICE_ID,
                ),
            )
            val currentRequirement = {
                resolveActiveWorkspaceSessionRequirement(
                    protocol,
                    workspaceIdProvider = { LOCAL_WORKSPACE_ID },
                )
            }
            assertNotNull(currentRequirement())

            protocol.abandonPreparingEpoch(
                remoteProfile = draft.remoteProfile,
                epochId = draft.descriptor.syncEpochId,
                safeErrorCode = "workspace_recovery_required",
                safeErrorMessage = "The account-current workspace must be recovered.",
            )

            assertNull(currentRequirement())
            assertNull(protocol.loadLocalAuthority())
            val fresh = fixture(
                transport = owner.transport,
                requirement = null,
                publisherReady = false,
                activeWorkspaceRequirementProvider = currentRequirement,
            )
            val status = fresh.service.status()
            assertTrue(status.success, status.diagnosticMessage)
            assertEquals(WorkspaceRecoveryState.RecoveryAvailable, status.state)
            assertEquals(WorkspaceRecoverySyncGate.RecoveryRequired, status.syncGate)
        } finally {
            driver.close()
        }
    }

    @Test
    fun recoveryControlPlaneFailureNeverRevokesAnAlreadyVerifiedLocalWorkspace() {
        val owner = fixture()
        val code = assertNotNull(owner.service.prepareCode().recoveryCode).revealForUserConfirmation()
        assertTrue(owner.service.confirmPreparedCode(code).success)
        owner.transport.failLoads()

        val boundStatus = owner.service.status()
        val freshStatus = fixture(
            transport = owner.transport,
            requirement = null,
            publisherReady = false,
        ).service.status()

        assertFalse(boundStatus.success)
        assertEquals(WorkspaceRecoveryState.Unavailable, boundStatus.state)
        assertEquals(WorkspaceRecoverySyncGate.Allowed, boundStatus.syncGate)
        assertFalse(freshStatus.success)
        assertEquals(WorkspaceRecoverySyncGate.VerificationUnavailable, freshStatus.syncGate)
    }

    @Test
    fun unsupportedEnvelopeCannotBeDowngradedButDoesNotBlockTheBoundDataPlane() {
        val owner = fixture()
        val code = assertNotNull(owner.service.prepareCode().recoveryCode).revealForUserConfirmation()
        assertTrue(owner.service.confirmPreparedCode(code).success)
        owner.transport.rewriteEnvelopeJson { value ->
            value.replaceFirst("\"protocolVersion\":1", "\"protocolVersion\":2")
        }
        val putsBefore = owner.transport.putCount

        val boundStatus = owner.service.status()
        val attemptedReplacement = owner.service.prepareCode()
        val freshStatus = fixture(
            transport = owner.transport,
            requirement = null,
            publisherReady = false,
        ).service.status()

        assertFalse(boundStatus.success)
        assertEquals(WorkspaceRecoverySyncGate.Allowed, boundStatus.syncGate)
        assertFalse(attemptedReplacement.success)
        assertEquals(putsBefore, owner.transport.putCount)
        assertFalse(freshStatus.success)
        assertEquals(WorkspaceRecoverySyncGate.VerificationUnavailable, freshStatus.syncGate)
    }

    @Test
    fun aVerifiedMissingEnvelopeDoesNotBecomeARecoveryDataPlaneGate() {
        val bound = fixture().service.status()
        val unbound = fixture(
            requirement = null,
            publisherReady = false,
        ).service.status()

        assertTrue(bound.success)
        assertEquals(WorkspaceRecoveryState.NotConfigured, bound.state)
        assertEquals(WorkspaceRecoverySyncGate.Allowed, bound.syncGate)
        assertTrue(unbound.success)
        assertEquals(WorkspaceRecoveryState.NotConfigured, unbound.state)
        assertEquals(WorkspaceRecoverySyncGate.Allowed, unbound.syncGate)
    }

    @Test
    fun recoveryPassesOnlyTheUserSuppliedCodeToTheAtomicWorkspaceJoiner() {
        val owner = fixture()
        val code = assertNotNull(owner.service.prepareCode().recoveryCode).revealForUserConfirmation()
        assertTrue(owner.service.confirmPreparedCode(code).success)
        var received: WorkspaceJoinPackage? = null
        val fresh = fixture(
            transport = owner.transport,
            requirement = null,
            publisherReady = false,
            joiner = WorkspaceJoiner { packageData, replace ->
                assertTrue(replace)
                received = packageData
                if (packageData.recoveryCode == code.replace("-", "")) {
                    WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
                } else {
                    WorkspaceJoinResult.failure(WorkspacePairingReason.VerificationFailed)
                }
            },
        )

        val wrong = fresh.service.recover("wrong", replaceExistingWorkspace = true)
        val recovered = fresh.service.recover(
            code.lowercase().replace('-', ' '),
            replaceExistingWorkspace = true,
        )

        assertFalse(wrong.success)
        assertEquals(WorkspaceRecoveryReason.InvalidCode, wrong.reason)
        assertTrue(recovered.success, recovered.diagnosticMessage)
        assertEquals(WorkspaceRecoveryReason.Recovered, recovered.reason)
        assertEquals(code.replace("-", ""), received?.recoveryCode)
        assertEquals(WORKSPACE_ID, received?.workspaceId)
    }

    @Test
    fun recoveryRechecksTheLocalWorkspaceInsideTheLifecycleBoundary() {
        val owner = fixture()
        val code = assertNotNull(owner.service.prepareCode().recoveryCode).revealForUserConfirmation()
        assertTrue(owner.service.confirmPreparedCode(code).success)
        var joins = 0
        val joiner = WorkspaceJoiner { _, _ ->
            joins += 1
            WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
        }

        val alreadyConfigured = fixture(
            transport = owner.transport,
            joiner = joiner,
        ).service.recover(code, replaceExistingWorkspace = true)
        val changedWorkspace = fixture(
            transport = owner.transport,
            requirement = requirement("workspace-11111111111111111111111111111111"),
            localKeyFingerprint = null,
            joiner = joiner,
        ).service.recover(code, replaceExistingWorkspace = true)
        val missingLocalKey = fixture(
            transport = owner.transport,
            localKeyFingerprint = null,
            joiner = joiner,
        ).service.recover(code, replaceExistingWorkspace = true)

        assertFalse(alreadyConfigured.success)
        assertEquals(WorkspaceRecoveryReason.RecoveryNotRequired, alreadyConfigured.reason)
        assertFalse(changedWorkspace.success)
        assertEquals(WorkspaceRecoveryReason.AuthorityMismatch, changedWorkspace.reason)
        assertTrue(missingLocalKey.success, missingLocalKey.diagnosticMessage)
        assertEquals(1, joins)
    }

    @Test
    fun malformedOrCrossWorkspaceEnvelopeNeverReachesTheJoiner() {
        val owner = fixture()
        val code = assertNotNull(owner.service.prepareCode().recoveryCode).revealForUserConfirmation()
        assertTrue(owner.service.confirmPreparedCode(code).success)
        val untamperedTransport = MemoryRecoveryTransport(owner.transport.current)
        owner.transport.tamperDigest()
        var joins = 0
        val fresh = fixture(
            transport = owner.transport,
            requirement = null,
            publisherReady = false,
            joiner = WorkspaceJoiner { _, _ ->
                joins += 1
                WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
            },
        )

        val result = fresh.service.recover(code, replaceExistingWorkspace = true)

        assertFalse(result.success)
        assertEquals(WorkspaceRecoveryReason.ServerRequestFailed, result.reason)
        assertEquals(0, joins)

        val boundElsewhere = fixture(
            transport = untamperedTransport,
            requirement = requirement("workspace-11111111111111111111111111111111"),
        ).service.status()
        assertFalse(boundElsewhere.success)
        assertEquals(WorkspaceRecoveryReason.AuthorityMismatch, boundElsewhere.reason)
    }

    @Test
    fun duplicateDefaultedOrNestedDuplicateEnvelopeFieldsNeverReachTheJoiner() {
        val malformedEnvelopes = listOf<(String) -> String>(
            { value ->
                value.replaceFirst(
                    "\"format\":",
                    "\"format\":\"someday.workspace-recovery\",\"format\":",
                )
            },
            { value -> value.replaceFirst("\"protocolVersion\":1,", "") },
            { value ->
                value.replaceFirst(
                    "\\\"version\\\":1,",
                    "\\\"version\\\":1,\\\"version\\\":1,",
                )
            },
        )

        malformedEnvelopes.forEach { mutate ->
            val owner = fixture()
            val code = assertNotNull(owner.service.prepareCode().recoveryCode).revealForUserConfirmation()
            assertTrue(owner.service.confirmPreparedCode(code).success)
            owner.transport.rewriteEnvelopeJson(mutate)
            var joins = 0
            val fresh = fixture(
                transport = owner.transport,
                requirement = null,
                publisherReady = false,
                joiner = WorkspaceJoiner { _, _ ->
                    joins += 1
                    WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
                },
            )

            val result = fresh.service.recover(code, replaceExistingWorkspace = true)

            assertFalse(result.success)
            assertEquals(WorkspaceRecoveryReason.ServerRequestFailed, result.reason)
            assertEquals(0, joins)
        }
    }

    @Test
    fun configuredStatusRequiresTheRemoteEnvelopeToMatchTheLocalWorkspaceKey() {
        val owner = fixture()
        val code = assertNotNull(owner.service.prepareCode().recoveryCode).revealForUserConfirmation()
        assertTrue(owner.service.confirmPreparedCode(code).success)
        val mismatched = fixture(
            transport = owner.transport,
            localKeyFingerprint = "11111111111111111111111111111111",
        )

        val status = mismatched.service.status()

        assertFalse(status.success)
        assertEquals(WorkspaceRecoveryReason.AuthorityMismatch, status.reason)
        assertEquals(WorkspaceRecoveryState.Unavailable, status.state)
        assertEquals(WorkspaceRecoverySyncGate.Allowed, status.syncGate)
    }

    @Test
    fun unpublishedWorkspaceCannotGenerateOrOverwriteRecoveryState() {
        val fixture = fixture(requirement = null, publisherReady = false)

        val result = fixture.service.prepareCode()

        assertFalse(result.success)
        assertEquals(WorkspaceRecoveryReason.PublishRequired, result.reason)
        assertEquals(0, fixture.packageRequests)
        assertEquals(0, fixture.transport.putCount)
    }

    private fun fixture(
        transport: MemoryRecoveryTransport = MemoryRecoveryTransport(),
        requirement: ActiveWorkspaceSessionRequirement? = requirement(WORKSPACE_ID),
        activeWorkspaceRequirementProvider: () -> ActiveWorkspaceSessionRequirement? = { requirement },
        publisherReady: Boolean = true,
        localKeyFingerprint: String? = if (requirement == null) null else KEY_FINGERPRINT,
        localKeyFingerprintProvider: () -> String? = { localKeyFingerprint },
        joiner: WorkspaceJoiner = WorkspaceJoiner { _, _ ->
            WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
        },
    ): RecoveryFixture {
        val credentials = credentials()
        val store = MemorySessionStore(credentials)
        var packageRequests = 0
        var sequence = 0
        val service = SelfHostedWorkspaceRecoveryService(
            settingsProvider = {
                ClientSettings(
                    syncConfiguration = SyncConfiguration(
                        mode = SyncMode.SelfHosted,
                        selfHostedEndpoint = credentials.endpoint,
                        selfHostedSession = credentials.toSummary(),
                    ),
                )
            },
            sessionStore = store,
            transport = transport,
            sessionExecutor = RefreshingSelfHostedSessionExecutor(transport, store),
            workspaceJoinPackageProvider = WorkspaceJoinPackageProvider {
                packageRequests += 1
                sequence += 1
                WorkspaceJoinResult.success(
                    WorkspacePairingReason.PackageCreated,
                    WorkspaceJoinPackage(
                        metadataJson = recoveryMetadataJson(sequence),
                        recoveryCode = recoveryCode(sequence),
                        workspaceId = WORKSPACE_ID,
                        keyFingerprint = KEY_FINGERPRINT,
                    ),
                )
            },
            workspaceJoiner = joiner,
            workspaceLifecycleCoordinator = WorkspaceLifecycleCoordinator(),
            activeWorkspaceSessionGuard = ActiveWorkspaceSessionGuard(activeWorkspaceRequirementProvider),
            workspaceRecoveryPublisherReady = { publisherReady },
            localWorkspaceKeyFingerprint = localKeyFingerprintProvider,
        )
        return RecoveryFixture(service, transport) { packageRequests }
    }

    private data class RecoveryFixture(
        val service: SelfHostedWorkspaceRecoveryService,
        val transport: MemoryRecoveryTransport,
        val packageRequestProvider: () -> Int,
    ) {
        val packageRequests: Int get() = packageRequestProvider()
    }

    private fun credentials(): SelfHostedSessionCredentials = SelfHostedSessionCredentials(
        endpoint = "https://sync.example.com",
        userId = "user-a",
        userEmail = "alice@example.com",
        deviceId = "device-a",
        deviceName = "Phone",
        devicePlatform = "android",
        accessToken = "access",
        refreshToken = "refresh",
    )

    private fun requirement(workspaceId: String): ActiveWorkspaceSessionRequirement {
        val credentials = credentials()
        return ActiveWorkspaceSessionRequirement(
            authorityBindingId = credentials.authorityBindingId,
            localWriterDeviceId = credentials.deviceId,
            workspaceId = workspaceId,
        )
    }

    private fun recoveryCode(sequence: Int): String =
        "SOMEDAY-${sequence.toString().padStart(4, '0')}-1111-2222-3333-4444-5555-6666-7777"

    private fun recoveryMetadataJson(sequence: Int): String =
        """{"format":"someday.workspace-recovery-metadata","version":1,"workspaceId":"$WORKSPACE_ID","createdAt":"2026-08-30T00:00:0${sequence}Z","keyAlgorithm":"XCHACHA20-POLY1305-IETF","recoveryKdf":"ARGON2ID13","keyLengthBytes":32,"keyFingerprint":"$KEY_FINGERPRINT","verifier":{"nonce":"bm9uY2U","ciphertext":"dmVyaWZpZXI"},"recovery":{"salt":"c2FsdA","nonce":"bm9uY2U","ciphertext":"d3JhcHBlZA","opsLimit":"2","memLimit":67108864,"algorithm":2}}"""

    private companion object {
        const val WORKSPACE_ID = "workspace-00000000000000000000000000000000"
        const val LOCAL_WORKSPACE_ID = "workspace-11111111111111111111111111111111"
        const val LOCAL_WRITER_DEVICE_ID = "00000000-0000-4000-8000-0000000000a1"
        const val KEY_FINGERPRINT = "0123456789abcdef0123456789abcdef"
    }
}

private class MemoryRecoveryTransport(
    initial: SelfHostedWorkspaceRecoveryEnvelopeResponse? = null,
) : SelfHostedSyncTransport, SelfHostedWorkspaceRecoveryTransport {
    var current: SelfHostedWorkspaceRecoveryEnvelopeResponse? = initial
        private set
    var lastPut: SelfHostedWorkspaceRecoveryEnvelopePutRequest? = null
        private set
    var putCount: Int = 0
        private set
    private var failAfterStore: Boolean = false
    private var loadFailuresEnabled: Boolean = false

    override fun getWorkspaceRecoveryEnvelope(
        endpoint: String,
        accessToken: String,
    ): SelfHostedWorkspaceRecoveryEnvelopeResponse? {
        if (loadFailuresEnabled) throw SelfHostedSyncHttpException(429, "recovery status rate limited")
        return current
    }

    override fun putWorkspaceRecoveryEnvelope(
        endpoint: String,
        accessToken: String,
        request: SelfHostedWorkspaceRecoveryEnvelopePutRequest,
    ): SelfHostedWorkspaceRecoveryEnvelopeResponse {
        val existing = current
        if (existing != null && existing.matches(request)) return existing
        if (existing == null) {
            if (request.expectedRevision != null) throw SelfHostedSyncHttpException(409, "conflict")
        } else if (request.expectedRevision != existing.revision) {
            throw SelfHostedSyncHttpException(409, "conflict")
        }
        putCount += 1
        lastPut = request
        val stored = SelfHostedWorkspaceRecoveryEnvelopeResponse(
            workspaceId = request.workspaceId,
            keyFingerprint = request.keyFingerprint,
            envelopeJson = request.envelopeJson,
            envelopeDigest = request.envelopeDigest,
            revision = (existing?.revision ?: 0L) + 1L,
            updatedAtEpochMillis = 1_000L + putCount,
        ).also { current = it }
        if (failAfterStore) {
            failAfterStore = false
            throw SelfHostedSyncHttpException(503, "response lost")
        }
        return stored
    }

    fun failAfterNextStore() {
        failAfterStore = true
    }

    fun failLoads() {
        loadFailuresEnabled = true
    }

    fun forceRevisionBump() {
        current = assertNotNull(current).copy(revision = assertNotNull(current).revision + 1L)
    }

    fun tamperDigest() {
        current = assertNotNull(current).copy(envelopeDigest = "tampered")
    }

    fun rewriteEnvelopeJson(transform: (String) -> String) {
        val existing = assertNotNull(current)
        val envelopeJson = transform(existing.envelopeJson)
        current = existing.copy(
            envelopeJson = envelopeJson,
            envelopeDigest = WorkspacePairingEnvelopeCodec.digest(envelopeJson.encodeToByteArray()),
        )
    }

    override fun register(endpoint: String, request: SelfHostedAuthRequest): SelfHostedAuthTokensResponse = error("unused")
    override fun login(endpoint: String, request: SelfHostedAuthRequest): SelfHostedAuthTokensResponse = error("unused")
    override fun refresh(endpoint: String, request: SelfHostedRefreshRequest): SelfHostedAuthTokensResponse =
        error("unused")

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

    override fun cancelPairingInvite(endpoint: String, accessToken: String, inviteId: String) = error("unused")

    private fun SelfHostedWorkspaceRecoveryEnvelopeResponse.matches(
        request: SelfHostedWorkspaceRecoveryEnvelopePutRequest,
    ): Boolean =
        workspaceId == request.workspaceId &&
            keyFingerprint == request.keyFingerprint &&
            envelopeJson == request.envelopeJson &&
            envelopeDigest == request.envelopeDigest
}
