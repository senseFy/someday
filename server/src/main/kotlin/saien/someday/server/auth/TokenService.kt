package saien.someday.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import saien.someday.server.ServerConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

data class AccessTokenClaims(
    val userId: UUID,
    val sessionId: UUID,
    val deviceId: UUID?,
    val isAdmin: Boolean,
    val scopes: Set<String>,
)

data class IssuedTokens(
    val accessToken: String,
    val refreshToken: String,
    val refreshTokenHash: String,
    val expiresInSeconds: Long,
)

data class IssuedRefreshToken(
    val refreshToken: String,
    val refreshTokenHash: String,
)

class TokenService(
    private val config: ServerConfig,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val algorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)
    private val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.jwtIssuer)
        .withAudience(config.jwtAudience)
        .build()

    fun issueTokens(
        userId: UUID,
        sessionId: UUID,
        deviceId: UUID?,
        isAdmin: Boolean,
        scopes: Set<String>,
        now: Instant = Instant.now(),
    ): IssuedTokens {
        val expiresAt = now.plus(config.accessTokenTtl)
        val accessToken = JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(userId.toString())
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))
            .withClaim("sid", sessionId.toString())
            .withClaim("admin", isAdmin)
            .withArrayClaim("scope", scopes.sorted().toTypedArray())
            .apply {
                if (deviceId != null) {
                    withClaim("device_id", deviceId.toString())
                }
            }
            .sign(algorithm)
        val refreshToken = issueRefreshToken()
        return IssuedTokens(
            accessToken = accessToken,
            refreshToken = refreshToken.refreshToken,
            refreshTokenHash = refreshToken.refreshTokenHash,
            expiresInSeconds = config.accessTokenTtl.seconds,
        )
    }

    fun verifyAccessToken(token: String): AccessTokenClaims? =
        try {
            val decoded = verifier.verify(token)
            val userId = UUID.fromString(decoded.subject ?: throw IllegalArgumentException("Missing subject"))
            val sessionId = UUID.fromString(
                decoded.getClaim("sid").asString() ?: throw IllegalArgumentException("Missing session id"),
            )
            val deviceId = decoded.getClaim("device_id").asString()?.let { UUID.fromString(it) }
            AccessTokenClaims(
                userId = userId,
                sessionId = sessionId,
                deviceId = deviceId,
                isAdmin = decoded.getClaim("admin").asBoolean() ?: false,
                scopes = decoded.getClaim("scope").asList(String::class.java)?.toSet().orEmpty(),
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JWTVerificationException) {
            null
        }

    fun hashRefreshToken(refreshToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.encodeToByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun issueRefreshToken(): IssuedRefreshToken {
        val refreshToken = generateOpaqueRefreshToken()
        return IssuedRefreshToken(
            refreshToken = refreshToken,
            refreshTokenHash = hashRefreshToken(refreshToken),
        )
    }

    private fun generateOpaqueRefreshToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

fun scopesForDevice(deviceId: UUID?): Set<String> =
    if (deviceId == null) {
        setOf("auth", "devices")
    } else {
        setOf("auth", "devices", "sync")
    }
