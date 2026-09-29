@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.sync.selfhosted

import saien.someday.domain.settings.isSecureSyncEndpoint
import saien.someday.domain.settings.normalizeSelfHostedEndpoint

import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.sync.causality.v2.normalizeWriterDeviceIdV2
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION

class SelfHostedSyncClient(
    endpoint: String,
    private val transport: SelfHostedSyncTransport,
    private val accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    private val protocol1Known: (String, String) -> Boolean = { _, _ -> false },
    private val onProtocol1: (String, String) -> Unit = { _, _ -> },
) {
    val normalizedEndpoint: String = normalizeSelfHostedEndpoint(endpoint)

    init {
        require(isSecureSyncEndpoint(normalizedEndpoint)) {
            "Self-hosted sync requires HTTPS unless the server is on this device's loopback interface."
        }
    }

    fun registerAndConnect(
        email: String,
        password: String,
        deviceName: String,
        platform: String,
        localDeviceId: String,
    ): SelfHostedSyncSession {
        val auth = transport.register(
            endpoint = normalizedEndpoint,
            request = SelfHostedAuthRequest(email = email.trim().lowercase(), password = password),
            accountContext = accountContext,
        ).verifiedIssuance()
        return connectAuthenticated(auth, deviceName, platform, localDeviceId)
    }

    fun loginAndConnect(
        email: String,
        password: String,
        deviceName: String,
        platform: String,
        localDeviceId: String,
    ): SelfHostedSyncSession {
        val auth = transport.login(
            endpoint = normalizedEndpoint,
            request = SelfHostedAuthRequest(email = email.trim().lowercase(), password = password),
            accountContext = accountContext,
        ).verifiedIssuance()
        return connectAuthenticated(auth, deviceName, platform, localDeviceId)
    }

    /**
     * Obtains fresh credentials for an already-bound workspace without changing its authority.
     * Account identity is verified before the server sees a device-registration request.
     */
    fun loginAndReconnectBound(
        email: String,
        password: String,
        deviceName: String,
        platform: String,
        expectedUserId: String,
        stableDeviceId: String,
        expectedAccountIncarnation: String = accountContext.accountIncarnation,
    ): SelfHostedSyncSession {
        val canonicalExpectedUserId = expectedUserId.trim().also {
            require(it.isNotEmpty()) { "The bound self-hosted user id is missing." }
        }
        val auth = rememberProtocolFor(canonicalExpectedUserId) { transport.login(
            endpoint = normalizedEndpoint,
            request = SelfHostedAuthRequest(email = email.trim().lowercase(), password = password),
            accountContext = accountContext,
        ).verifiedIssuance() }
        require(auth.user.id == canonicalExpectedUserId) {
            "The authenticated self-hosted account does not match the bound workspace authority."
        }
        requireSameIncarnation(auth.accountIncarnation!!, expectedAccountIncarnation)
        return connectAuthenticated(auth, deviceName, platform, stableDeviceId)
    }

    fun refresh(session: SelfHostedSyncSession): SelfHostedSyncSession {
        val auth = rememberProtocolFor(session.userId) { transport.refresh(
            endpoint = normalizedEndpoint,
            request = SelfHostedRefreshRequest(refreshToken = session.refreshToken),
            accountContext = session.accountRequestContext(),
        ).verifiedIssuance(session.accountRequestContext()) }
        require(auth.user.id == session.userId) { "The refreshed account does not match the current session." }
        requireSameIncarnation(auth.accountIncarnation!!, session.accountIncarnation)
        return session.copy(
            userId = auth.user.id,
            userEmail = auth.user.email,
            accessToken = auth.accessToken,
            refreshToken = auth.refreshToken,
            accountIncarnation = auth.accountIncarnation,
            accountProtocolVersion = auth.accountProtocolVersion,
        )
    }

    private fun connectAuthenticated(
        auth: SelfHostedAuthTokensResponse,
        deviceName: String,
        platform: String,
        localDeviceId: String,
    ): SelfHostedSyncSession {
        val stableDeviceId = normalizeWriterDeviceIdV2(localDeviceId)
        val device = rememberProtocolFor(auth.user.id) { transport.registerDevice(
            endpoint = normalizedEndpoint,
            accessToken = auth.accessToken,
            request = SelfHostedDeviceRegistrationRequest(
                deviceId = stableDeviceId,
                name = deviceName.trim(),
                platform = platform.trim().lowercase(),
            ),
            accountContext = SelfHostedAccountRequestContext(auth.accountIncarnation!!, auth.accountProtocolVersion == 1),
        ) }
        val deviceIncarnation = device.accountIncarnation
        if (device.accountProtocolVersion == 1 && deviceIncarnation != null) {
            SelfHostedAccountWire.issuance(listOf(deviceIncarnation), accountContext)
            onProtocol1(normalizedEndpoint, auth.user.id)
            requireSameIncarnation(deviceIncarnation, auth.accountIncarnation!!)
        } else if (deviceIncarnation != null || device.accountProtocolVersion != null || auth.accountProtocolVersion == 1 || protocol1Known(normalizedEndpoint, auth.user.id)) {
            SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.MISSING_ISSUANCE_HEADER)
        }
        require(device.device.id == stableDeviceId) {
            "The server did not claim the requested installation identity."
        }
        require(!device.device.revoked) {
            "The bound self-hosted device has been revoked."
        }
        return SelfHostedSyncSession(
            endpoint = normalizedEndpoint,
            userId = auth.user.id,
            userEmail = auth.user.email,
            deviceId = device.device.id,
            deviceName = device.device.name,
            devicePlatform = device.device.platform,
            accessToken = device.accessToken,
            refreshToken = device.refreshToken,
            accountIncarnation = deviceIncarnation ?: INITIAL_ACCOUNT_INCARNATION,
            accountProtocolVersion = device.accountProtocolVersion,
        )
    }

    private fun SelfHostedAuthTokensResponse.verifiedIssuance(
        context: SelfHostedAccountRequestContext = accountContext,
    ): SelfHostedAuthTokensResponse {
        if (accountProtocolVersion == 1 && accountIncarnation != null) {
            SelfHostedAccountWire.issuance(listOf(accountIncarnation), context)
            onProtocol1(normalizedEndpoint, user.id)
            return this
        }
        if (accountIncarnation != null || accountProtocolVersion != null) {
            SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.INVALID_ISSUANCE_HEADER)
        }
        if (context.protocol1Known || protocol1Known(normalizedEndpoint, user.id)) {
            SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.MISSING_ISSUANCE_HEADER)
        }
        val control = transport as? SelfHostedAccountControlTransport
            ?: SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.UNVERIFIED_LEGACY)
        when (rememberProtocolFor(user.id) { control.discoverAccountData(normalizedEndpoint, accessToken, SelfHostedAccountRequestContext()) }) {
            is SelfHostedAccountDiscoveryResult.Protocol1 -> {
                onProtocol1(normalizedEndpoint, user.id)
                SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.MISSING_ISSUANCE_HEADER)
            }
            SelfHostedAccountDiscoveryResult.LegacyCandidate404 -> {
                val me = SelfHostedAccountWire.validateMe(rememberProtocolFor(user.id) { control.accountMe(normalizedEndpoint, accessToken, SelfHostedAccountRequestContext()) })
                if (me.id != user.id || protocol1Known(normalizedEndpoint, user.id)) {
                    SelfHostedAccountWire.fail(SelfHostedProtocolFailureReason.UNVERIFIED_LEGACY)
                }
                return copy(accountIncarnation = INITIAL_ACCOUNT_INCARNATION)
            }
        }
    }

    /** Failed password login alone cannot supply an authenticated user id. */
    private inline fun <T> rememberProtocolFor(userId: String, block: () -> T): T = try {
        block()
    } catch (failure: SelfHostedSyncHttpException) {
        if (failure.protocol1) onProtocol1(normalizedEndpoint, userId)
        throw failure
    }

    private fun requireSameIncarnation(actual: String, expected: String) {
        if (actual != expected) throw SelfHostedSyncHttpException(
            409, "The account incarnation changed; credentials redacted.",
            SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, protocol1 = true,
        )
    }
}

/** Auth and device registration only; sync itself uses [SelfHostedSyncTransportV2]. */
interface SelfHostedSyncTransport {
    fun register(
        endpoint: String,
        request: SelfHostedAuthRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedAuthTokensResponse

    fun login(
        endpoint: String,
        request: SelfHostedAuthRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedAuthTokensResponse

    fun refresh(
        endpoint: String,
        request: SelfHostedRefreshRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedAuthTokensResponse

    fun registerDevice(
        endpoint: String,
        accessToken: String,
        request: SelfHostedDeviceRegistrationRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedDeviceRegistrationResponse

    fun createPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCreateRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedPairingInviteCreateResponse

    fun claimPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteClaimRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedPairingInviteClaimResponse

    fun completePairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCompleteRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    )

    fun cancelPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    )
}

/** Account-authenticated storage for a client-generated, opaque wrapped-key envelope. */
interface SelfHostedWorkspaceRecoveryTransport {
    fun getWorkspaceRecoveryEnvelope(
        endpoint: String,
        accessToken: String,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedWorkspaceRecoveryEnvelopeResponse?

    fun putWorkspaceRecoveryEnvelope(
        endpoint: String,
        accessToken: String,
        request: SelfHostedWorkspaceRecoveryEnvelopePutRequest,
        accountContext: SelfHostedAccountRequestContext = SelfHostedAccountRequestContext(),
    ): SelfHostedWorkspaceRecoveryEnvelopeResponse
}

class SelfHostedSyncHttpException(
    val status: Int,
    val safeMessage: String,
    val errorCode: SelfHostedErrorCode? = null,
    val protocol1: Boolean = false,
) : RuntimeException(safeMessage)

data class SelfHostedSyncSession(
    val endpoint: String,
    val userId: String,
    val userEmail: String,
    val deviceId: String,
    val deviceName: String,
    val devicePlatform: String,
    val accessToken: String,
    val refreshToken: String,
    val cursor: Long? = null,
    val accountIncarnation: String = INITIAL_ACCOUNT_INCARNATION,
    val accountProtocolVersion: Int? = null,
) {
    fun withCursor(cursor: Long): SelfHostedSyncSession =
        copy(cursor = cursor)

    fun toCredentials(): SelfHostedSessionCredentials =
        SelfHostedSessionCredentials(
            endpoint = endpoint,
            userId = userId,
            userEmail = userEmail,
            deviceId = deviceId,
            deviceName = deviceName,
            devicePlatform = devicePlatform,
            accessToken = accessToken,
            refreshToken = refreshToken,
            accountIncarnation = accountIncarnation,
            accountProtocolVersion = accountProtocolVersion,
        )

    override fun toString(): String = "SelfHostedSyncSession(${redactedDescription()})"

    fun redactedDescription(): String =
        "endpoint=$endpoint user=$userEmail device=$deviceId accessToken=redacted refreshToken=redacted"

    companion object {
        fun fromCredentials(
            credentials: SelfHostedSessionCredentials,
            cursor: Long? = null,
        ): SelfHostedSyncSession =
            SelfHostedSyncSession(
                endpoint = credentials.endpoint,
                userId = credentials.userId,
                userEmail = credentials.userEmail,
                deviceId = credentials.deviceId,
                deviceName = credentials.deviceName,
                devicePlatform = credentials.devicePlatform,
                accessToken = credentials.accessToken,
                refreshToken = credentials.refreshToken,
                cursor = cursor,
                accountIncarnation = credentials.accountIncarnation,
                accountProtocolVersion = credentials.accountProtocolVersion,
            )
    }
}

@Serializable
data class SelfHostedAuthRequest(
    val email: String,
    val password: String,
) {
    override fun toString(): String = "SelfHostedAuthRequest(password=<redacted>)"
}

@Serializable
data class SelfHostedRefreshRequest(
    val refreshToken: String,
) {
    override fun toString(): String = "SelfHostedRefreshRequest(refreshToken=<redacted>)"
}

@Serializable
data class SelfHostedAuthTokensResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: SelfHostedUserResponse,
    @Transient val accountIncarnation: String? = null,
    @Transient val accountProtocolVersion: Int? = null,
) {
    override fun toString(): String = "SelfHostedAuthTokensResponse(accountIncarnation=$accountIncarnation, credentials=<redacted>)"
}

@Serializable
data class SelfHostedUserResponse(
    val id: String,
    val email: String,
)

@Serializable
data class SelfHostedDeviceRegistrationRequest(
    val deviceId: String,
    val name: String,
    val platform: String,
)

@Serializable
data class SelfHostedDeviceRegistrationResponse(
    val device: SelfHostedDeviceResponse,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    @Transient val accountIncarnation: String? = null,
    @Transient val accountProtocolVersion: Int? = null,
) {
    override fun toString(): String = "SelfHostedDeviceRegistrationResponse(accountIncarnation=$accountIncarnation, credentials=<redacted>)"
}

@Serializable
data class SelfHostedDeviceResponse(
    val id: String,
    val name: String,
    val platform: String,
    val revoked: Boolean,
)

@Serializable
data class SelfHostedPairingInviteCreateRequest(
    val envelopeJson: String,
    val envelopeDigest: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class SelfHostedPairingInviteCreateResponse(
    val status: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class SelfHostedPairingInviteClaimRequest(
    val claimId: String,
)

@Serializable
data class SelfHostedPairingInviteClaimResponse(
    val envelopeJson: String,
    val envelopeDigest: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class SelfHostedPairingInviteCompleteRequest(
    val claimId: String,
)

@Serializable
data class SelfHostedWorkspaceRecoveryEnvelopePutRequest(
    val workspaceId: String,
    val keyFingerprint: String,
    val envelopeJson: String,
    val envelopeDigest: String,
    val expectedRevision: Long? = null,
) {
    override fun toString(): String =
        "SelfHostedWorkspaceRecoveryEnvelopePutRequest(workspaceId=$workspaceId, " +
            "keyFingerprint=$keyFingerprint, envelopeDigest=$envelopeDigest, " +
            "expectedRevision=$expectedRevision, envelopeJson=<redacted>)"
}

@Serializable
data class SelfHostedWorkspaceRecoveryEnvelopeResponse(
    val workspaceId: String,
    val keyFingerprint: String,
    val envelopeJson: String,
    val envelopeDigest: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "SelfHostedWorkspaceRecoveryEnvelopeResponse(workspaceId=$workspaceId, " +
            "keyFingerprint=$keyFingerprint, envelopeDigest=$envelopeDigest, revision=$revision, " +
            "updatedAtEpochMillis=$updatedAtEpochMillis, envelopeJson=<redacted>)"
}
