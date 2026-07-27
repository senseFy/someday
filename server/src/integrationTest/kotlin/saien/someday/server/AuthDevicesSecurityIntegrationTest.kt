package saien.someday.server

import com.auth0.jwt.JWT
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthDevicesSecurityIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dbUrl = System.getenv("SOMEDAY_DB_URL") ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    private val dbUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    private val dbPassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"

    @Before
    fun setUp() {
        clearServerTables()
    }

    @After
    fun tearDown() {
        clearServerTables()
    }

    @Test
    fun registerCreatesArgon2HashAndAllowsMe() = testApplication {
        application { somedayServerModule() }

        val password = "Correct-Horse-${System.nanoTime()}"
        val normalizedEmail = "register-${System.nanoTime()}@example.com"
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    AuthRequest(email = "  ${normalizedEmail.uppercase()}  ", password = password),
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val tokens = json.decodeFromString<AuthTokensResponse>(response.bodyAsText())
        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.length >= 43, "Refresh token must be high-entropy and opaque.")
        assertEquals(normalizedEmail, tokens.user.email)

        val me = client.get("/me") {
            bearerAuth(tokens.accessToken)
        }

        assertEquals(HttpStatusCode.OK, me.status, me.bodyAsText())
        assertEquals(normalizedEmail, json.decodeFromString<MeResponse>(me.bodyAsText()).email)
        assertFalse(me.bodyAsText().contains(password), "Responses must not echo passwords.")

        val storedHash = passwordHashFor(normalizedEmail)
        assertNotNull(storedHash)
        assertTrue(storedHash.startsWith("\$argon2id\$"), "Password hash must use Argon2id PHC format.")
        assertFalse(storedHash.contains(password), "Stored password hash must not contain raw password.")
    }

    @Test
    fun productionRegistrationIsClosedByDefault() = testApplication {
        val config = productionTestServerConfig(dbUrl, dbUser, dbPassword)
        application { somedayServerModule(ServerContext.create(config)) }

        val email = "closed-registration-${System.nanoTime()}@example.com"
        val response = register(email = email, password = "valid-password")

        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        assertEquals("registration_disabled", json.decodeFromString<ErrorResponse>(response.bodyAsText()).error)
        assertEquals(0, userCountFor(email), "A closed registration endpoint must not create an account.")
    }

    @Test
    fun duplicateNormalizedRegistrationIsRejectedSafely() = testApplication {
        application { somedayServerModule() }

        val email = "duplicate-${System.nanoTime()}@example.com"
        val first = register(email = email, password = "first-password")
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AuthRequest(email = " ${email.uppercase()} ", password = "second-password")))
        }

        assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
        assertEquals(1, userCountFor(email))
        assertSafeError(second.bodyAsText(), listOf("second-password", "argon2", "hash"))
    }

    @Test
    fun loginAcceptsValidCredentialsAndRejectsInvalidCredentialsGenerically() = testApplication {
        application { somedayServerModule() }

        val email = "login-${System.nanoTime()}@example.com"
        register(email = email, password = "valid-password")

        val valid = login(email = email, password = "valid-password")
        assertEquals(HttpStatusCode.OK, valid.status, valid.bodyAsText())
        assertTrue(json.decodeFromString<AuthTokensResponse>(valid.bodyAsText()).accessToken.isNotBlank())

        val wrongPassword = login(email = email, password = "wrong-password")
        val unknownUser = login(email = "missing-${System.nanoTime()}@example.com", password = "wrong-password")
        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status, wrongPassword.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, unknownUser.status, unknownUser.bodyAsText())

        val wrongError = json.decodeFromString<ErrorResponse>(wrongPassword.bodyAsText())
        val unknownError = json.decodeFromString<ErrorResponse>(unknownUser.bodyAsText())
        assertEquals(wrongError.error, unknownError.error, "Invalid login failures must not leak user existence.")
        assertSafeError(wrongPassword.bodyAsText(), listOf(email, "wrong-password", "valid-password"))
        assertSafeError(unknownUser.bodyAsText(), listOf("missing-", "wrong-password"))
    }

    @Test
    fun refreshRotatesTokensAndLogoutRevokesOnlyCallerSession() = testApplication {
        application { somedayServerModule() }

        val email = "refresh-${System.nanoTime()}@example.com"
        val firstSession = json.decodeFromString<AuthTokensResponse>(
            register(email = email, password = "valid-password").bodyAsText(),
        )
        val secondSession = json.decodeFromString<AuthTokensResponse>(
            login(email = email, password = "valid-password").bodyAsText(),
        )

        val rotated = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RefreshRequest(firstSession.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, rotated.status, rotated.bodyAsText())
        val rotatedTokens = json.decodeFromString<AuthTokensResponse>(rotated.bodyAsText())
        assertNotEquals(firstSession.refreshToken, rotatedTokens.refreshToken)

        val replayOld = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RefreshRequest(firstSession.refreshToken)))
        }
        assertEquals(HttpStatusCode.Unauthorized, replayOld.status, replayOld.bodyAsText())

        val logout = client.post("/auth/logout") {
            bearerAuth(rotatedTokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(LogoutRequest(rotatedTokens.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, logout.status, logout.bodyAsText())

        val callerRefreshAfterLogout = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RefreshRequest(rotatedTokens.refreshToken)))
        }
        assertEquals(HttpStatusCode.Unauthorized, callerRefreshAfterLogout.status, callerRefreshAfterLogout.bodyAsText())

        val unrelatedSessionStillWorks = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RefreshRequest(secondSession.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, unrelatedSessionStillWorks.status, unrelatedSessionStillWorks.bodyAsText())
    }

    @Test
    fun deviceRegistrationListAndRevocationAreAccountScoped() = testApplication {
        application { somedayServerModule() }

        val userOne = json.decodeFromString<AuthTokensResponse>(
            register(email = "device-one-${System.nanoTime()}@example.com", password = "password-one").bodyAsText(),
        )
        val userTwo = json.decodeFromString<AuthTokensResponse>(
            register(email = "device-two-${System.nanoTime()}@example.com", password = "password-two").bodyAsText(),
        )

        val userOneDevice = registerDevice(userOne.accessToken, "Phone One", "android")
        val userTwoDevice = registerDevice(userTwo.accessToken, "Phone Two", "ios")

        val userOneDevices = client.get("/devices") {
            bearerAuth(userOneDevice.accessToken)
        }
        assertEquals(HttpStatusCode.OK, userOneDevices.status, userOneDevices.bodyAsText())
        val listedDevices = json.decodeFromString<DevicesResponse>(userOneDevices.bodyAsText()).devices
        assertTrue(listedDevices.any { it.id == userOneDevice.device.id })
        assertFalse(listedDevices.any { it.id == userTwoDevice.device.id }, "Device list must be account-scoped.")

        val crossAccountRevoke = client.delete("/devices/${userTwoDevice.device.id}") {
            bearerAuth(userOneDevice.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, crossAccountRevoke.status, crossAccountRevoke.bodyAsText())

        val revoke = client.delete("/devices/${userOneDevice.device.id}") {
            bearerAuth(userOneDevice.accessToken)
        }
        assertEquals(HttpStatusCode.OK, revoke.status, revoke.bodyAsText())

        val revokedSync = client.get("/sync/v2/capabilities") {
            bearerAuth(userOneDevice.accessToken)
        }
        assertTrue(
            revokedSync.status == HttpStatusCode.Unauthorized || revokedSync.status == HttpStatusCode.Forbidden,
            "Revoked device sync must be denied, got ${revokedSync.status}: ${revokedSync.bodyAsText()}",
        )

        val unaffected = client.get("/sync/v2/capabilities") {
            bearerAuth(userTwoDevice.accessToken)
        }
        assertEquals(HttpStatusCode.OK, unaffected.status, unaffected.bodyAsText())
    }

    @Test
    fun refreshTokensAreOpaqueHashedAndAccessTokensAreScopedShortLived() = testApplication {
        application { somedayServerModule() }

        val user = json.decodeFromString<AuthTokensResponse>(
            register(email = "claims-${System.nanoTime()}@example.com", password = "valid-password").bodyAsText(),
        )

        assertFalse(user.refreshToken.contains('.'), "Refresh token must be opaque rather than a JWT.")
        val storedRefreshHash = activeRefreshTokenHashes().single()
        assertNotEquals(user.refreshToken, storedRefreshHash)
        assertFalse(storedRefreshHash.contains(user.refreshToken), "Database must not persist raw refresh tokens.")

        val loginClaims = JWT.decode(user.accessToken)
        assertTrue(loginClaims.subject.isNotBlank())
        assertTrue(loginClaims.getClaim("sid").asString().isNotBlank())
        assertEquals(false, loginClaims.getClaim("admin").asBoolean())
        assertTrue(loginClaims.expiresAt.toInstant().isBefore(Instant.now().plus(Duration.ofMinutes(16))))
        val loginScopes = loginClaims.getClaim("scope").asList(String::class.java)
        assertTrue("devices" in loginScopes)
        assertFalse("sync" in loginScopes, "Unregistered sessions must not receive sync scope.")

        val device = registerDevice(user.accessToken, "Security Phone", "android")
        val deviceClaims = JWT.decode(device.accessToken)
        val deviceScopes = deviceClaims.getClaim("scope").asList(String::class.java)
        assertEquals(device.device.id, deviceClaims.getClaim("device_id").asString())
        assertTrue("sync" in deviceScopes)
    }

    @Test
    fun sensitiveEndpointsAreRateLimitedAndReturnSafeErrors() = testApplication {
        application { somedayServerModule() }

        val missingEmail = "rate-missing-${System.nanoTime()}@example.com"
        val loginStatuses = (1..6).map {
            login(email = missingEmail, password = "bad-password").status
        }
        assertEquals(HttpStatusCode.TooManyRequests, loginStatuses.last(), loginStatuses.toString())

        val unknownRefreshStatuses = (1..6).map {
            client.post("/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RefreshRequest("invalid-refresh-token-$it")))
            }.status
        }
        assertEquals(HttpStatusCode.TooManyRequests, unknownRefreshStatuses.last(), unknownRefreshStatuses.toString())

        val user = json.decodeFromString<AuthTokensResponse>(
            register(email = "rate-device-${System.nanoTime()}@example.com", password = "valid-password").bodyAsText(),
        )
        val deviceStatuses = (1..6).map {
            client.post("/devices/register") {
                bearerAuth(user.accessToken)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(DeviceRegistrationRequest("Rate Device $it", "android")))
            }.status
        }
        assertEquals(HttpStatusCode.TooManyRequests, deviceStatuses.last(), deviceStatuses.toString())

        // The retired /sync/push route is intentionally absent.
        // V2 uses a separate high-budget limiter for coordinator passes; auth and
        // device registration remain the brute-force sensitive surfaces covered above.
        val syncUser = json.decodeFromString<AuthTokensResponse>(
            register(email = "rate-sync-${System.nanoTime()}@example.com", password = "valid-password").bodyAsText(),
        )
        val syncToken = registerDevice(syncUser.accessToken, "Sync Rate Device", "desktop").accessToken
        val syncOk = client.get("/sync/v2/capabilities") {
            bearerAuth(syncToken)
        }
        assertEquals(HttpStatusCode.OK, syncOk.status, syncOk.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.register(email: String, password: String) =
        client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AuthRequest(email, password)))
        }

    private suspend fun ApplicationTestBuilder.login(email: String, password: String) =
        client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AuthRequest(email, password)))
        }

    private suspend fun ApplicationTestBuilder.registerDevice(
        accessToken: String,
        name: String,
        platform: String,
    ): DeviceRegistrationResponse {
        val response = client.post("/devices/register") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DeviceRegistrationRequest(name, platform)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private fun clearServerTables() {
        runCatching {
            DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        TRUNCATE TABLE
                            someday_refresh_tokens,
                            someday_sessions,
                            someday_devices,
                            someday_users
                        CASCADE
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    private fun passwordHashFor(email: String): String? =
        DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
            connection.prepareStatement("SELECT password_hash FROM someday_users WHERE email = ?").use { statement ->
                statement.setString(1, email)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getString(1) else null
                }
            }
        }

    private fun userCountFor(email: String): Int =
        DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM someday_users WHERE email = ?").use { statement ->
                statement.setString(1, email)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private fun activeRefreshTokenHashes(): List<String> =
        DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT token_hash
                    FROM someday_refresh_tokens
                    WHERE revoked_at IS NULL
                    ORDER BY created_at
                    """.trimIndent(),
                ).use { result ->
                    buildList {
                        while (result.next()) {
                            add(result.getString(1))
                        }
                    }
                }
            }
        }

    private fun assertSafeError(body: String, forbiddenSubstrings: List<String>) {
        forbiddenSubstrings.forEach { forbidden ->
            assertFalse(
                body.contains(forbidden, ignoreCase = true),
                "Response leaked forbidden substring '$forbidden': $body",
            )
        }
    }
}

@Serializable
private data class AuthRequest(
    val email: String,
    val password: String,
)

@Serializable
private data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
private data class LogoutRequest(
    val refreshToken: String? = null,
)

@Serializable
private data class DeviceRegistrationRequest(
    val name: String,
    val platform: String,
)

@Serializable
private data class AuthTokensResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: UserResponse,
)

@Serializable
private data class DeviceRegistrationResponse(
    val device: DeviceResponse,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

@Serializable
private data class UserResponse(
    val id: String,
    val email: String,
)

@Serializable
private data class MeResponse(
    val id: String,
    val email: String,
    val deviceId: String? = null,
    val scopes: List<String>,
)

@Serializable
private data class DeviceResponse(
    val id: String,
    val name: String,
    val platform: String,
    val revoked: Boolean,
)

@Serializable
private data class DevicesResponse(
    val devices: List<DeviceResponse>,
)

@Serializable
private data class ErrorResponse(
    val error: String,
)
