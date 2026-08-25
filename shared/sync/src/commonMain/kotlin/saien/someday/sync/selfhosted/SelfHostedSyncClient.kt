@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.sync.selfhosted

import saien.someday.domain.settings.isSecureSyncEndpoint
import saien.someday.domain.settings.normalizeSelfHostedEndpoint

import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.sync.causality.v2.normalizeWriterDeviceIdV2
import kotlinx.serialization.Serializable

class SelfHostedSyncClient(
    endpoint: String,
    private val transport: SelfHostedSyncTransport,
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
        )
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
        )
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
    ): SelfHostedSyncSession {
        val canonicalExpectedUserId = expectedUserId.trim().also {
            require(it.isNotEmpty()) { "The bound self-hosted user id is missing." }
        }
        val auth = transport.login(
            endpoint = normalizedEndpoint,
            request = SelfHostedAuthRequest(email = email.trim().lowercase(), password = password),
        )
        require(auth.user.id == canonicalExpectedUserId) {
            "The authenticated self-hosted account does not match the bound workspace authority."
        }
        return connectAuthenticated(auth, deviceName, platform, stableDeviceId)
    }

    fun refresh(session: SelfHostedSyncSession): SelfHostedSyncSession {
        val auth = transport.refresh(
            endpoint = normalizedEndpoint,
            request = SelfHostedRefreshRequest(refreshToken = session.refreshToken),
        )
        return session.copy(
            userId = auth.user.id,
            userEmail = auth.user.email,
            accessToken = auth.accessToken,
            refreshToken = auth.refreshToken,
        )
    }

    private fun connectAuthenticated(
        auth: SelfHostedAuthTokensResponse,
        deviceName: String,
        platform: String,
        localDeviceId: String,
    ): SelfHostedSyncSession {
        val stableDeviceId = normalizeWriterDeviceIdV2(localDeviceId)
        val device = transport.registerDevice(
            endpoint = normalizedEndpoint,
            accessToken = auth.accessToken,
            request = SelfHostedDeviceRegistrationRequest(
                deviceId = stableDeviceId,
                name = deviceName.trim(),
                platform = platform.trim().lowercase(),
            ),
        )
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
        )
    }

}

/** Auth and device registration only; sync itself uses [SelfHostedSyncTransportV2]. */
interface SelfHostedSyncTransport {
    fun register(
        endpoint: String,
        request: SelfHostedAuthRequest,
    ): SelfHostedAuthTokensResponse

    fun login(
        endpoint: String,
        request: SelfHostedAuthRequest,
    ): SelfHostedAuthTokensResponse

    fun refresh(
        endpoint: String,
        request: SelfHostedRefreshRequest,
    ): SelfHostedAuthTokensResponse

    fun registerDevice(
        endpoint: String,
        accessToken: String,
        request: SelfHostedDeviceRegistrationRequest,
    ): SelfHostedDeviceRegistrationResponse

    fun createPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCreateRequest,
    ): SelfHostedPairingInviteCreateResponse

    fun claimPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteClaimRequest,
    ): SelfHostedPairingInviteClaimResponse

    fun completePairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
        request: SelfHostedPairingInviteCompleteRequest,
    )

    fun cancelPairingInvite(
        endpoint: String,
        accessToken: String,
        inviteId: String,
    )
}

class SelfHostedSyncHttpException(
    val status: Int,
    val safeMessage: String,
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
        )

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
            )
    }
}

@Serializable
data class SelfHostedAuthRequest(
    val email: String,
    val password: String,
)

@Serializable
data class SelfHostedRefreshRequest(
    val refreshToken: String,
)

@Serializable
data class SelfHostedAuthTokensResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: SelfHostedUserResponse,
)

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
)

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
