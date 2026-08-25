package saien.someday.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import saien.someday.server.api.AuthRequest
import saien.someday.server.api.AuthTokensResponse
import saien.someday.server.api.DeviceRegistrationRequest
import saien.someday.server.api.DeviceRegistrationResponse
import saien.someday.server.api.PairingInviteClaimRequest
import saien.someday.server.api.PairingInviteClaimResponse
import saien.someday.server.api.PairingInviteCompleteRequest
import saien.someday.server.api.PairingInviteCreateRequest
import saien.someday.server.api.PairingInviteCreateResponse

class PairingApiIntegrationTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
    private val dbUrl = System.getenv("SOMEDAY_DB_URL") ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    private val dbUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    private val dbPassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"

    @BeforeTest fun setUp() = clearServerTables()
    @AfterTest fun tearDown() = clearServerTables()

    @Test
    fun inviteLifecycleIsAccountScopedDeviceBoundAtomicAndReplaySafe() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccount()
        val creator = registerDevice(account.accessToken, "Creator", "android")
        val follower = registerDevice(account.accessToken, "Follower", "ios")
        val outsiderAccount = registerAccount()
        val outsider = registerDevice(outsiderAccount.accessToken, "Outsider", "desktop")
        val inviteId = identifier('a')
        val envelope = """{"format":"opaque","ciphertext":"not-readable-by-server"}"""
        val createRequest = createRequest(envelope)

        val created = putInvite(creator.accessToken, inviteId, createRequest)
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("created", json.decodeFromString<PairingInviteCreateResponse>(created.body).status)

        val replay = putInvite(creator.accessToken, inviteId, createRequest)
        assertEquals(HttpStatusCode.OK, replay.status, replay.body)
        assertEquals("replay", json.decodeFromString<PairingInviteCreateResponse>(replay.body).status)

        val conflictingEnvelope = """{"format":"opaque","ciphertext":"different"}"""
        val conflict = putInvite(creator.accessToken, inviteId, createRequest(conflictingEnvelope))
        assertEquals(HttpStatusCode.Conflict, conflict.status, conflict.body)

        val outsiderClaim = postJson(
            "/pairing/invites/$inviteId/claim",
            outsider.accessToken,
            PairingInviteClaimRequest(identifier('x')),
        )
        assertEquals(HttpStatusCode.NotFound, outsiderClaim.status, outsiderClaim.body)

        val claimId = identifier('c')
        val claimed = postJson(
            "/pairing/invites/$inviteId/claim",
            follower.accessToken,
            PairingInviteClaimRequest(claimId),
        )
        assertEquals(HttpStatusCode.OK, claimed.status, claimed.body)
        assertEquals(envelope, json.decodeFromString<PairingInviteClaimResponse>(claimed.body).envelopeJson)

        val idempotentClaim = postJson(
            "/pairing/invites/$inviteId/claim",
            follower.accessToken,
            PairingInviteClaimRequest(claimId),
        )
        assertEquals(HttpStatusCode.OK, idempotentClaim.status, idempotentClaim.body)

        val competingClaim = postJson(
            "/pairing/invites/$inviteId/claim",
            creator.accessToken,
            PairingInviteClaimRequest(identifier('d')),
        )
        assertEquals(HttpStatusCode.Conflict, competingClaim.status, competingClaim.body)

        val completed = postJson(
            "/pairing/invites/$inviteId/complete",
            follower.accessToken,
            PairingInviteCompleteRequest(claimId),
        )
        assertEquals(HttpStatusCode.NoContent, completed.status, completed.body)
        val completionReplay = postJson(
            "/pairing/invites/$inviteId/complete",
            follower.accessToken,
            PairingInviteCompleteRequest(claimId),
        )
        assertEquals(HttpStatusCode.NoContent, completionReplay.status, completionReplay.body)
        assertNull(envelopeJson(account.user.id, inviteId))
        assertEquals("completed", inviteState(account.user.id, inviteId))
    }

    @Test
    fun creationRequiresDeviceTokenAndThereIsNoReadOrDeleteRoute() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccount()
        val inviteId = identifier('n')

        val createWithoutDevice = putInvite(account.accessToken, inviteId, createRequest("{}"))
        assertEquals(HttpStatusCode.Forbidden, createWithoutDevice.status, createWithoutDevice.body)

        val device = registerDevice(account.accessToken, "Creator", "android")
        val nonCanonicalIdentifier = "é".repeat(22)
        assertEquals(
            HttpStatusCode.BadRequest,
            putInvite(device.accessToken, nonCanonicalIdentifier, createRequest("{}")).status,
        )
        assertEquals(HttpStatusCode.Created, putInvite(device.accessToken, inviteId, createRequest("{}")).status)
        val read = client.get("/pairing/invites/$inviteId") {
            bearerAuth(device.accessToken)
        }
        val deleted = client.delete("/pairing/invites/$inviteId") {
            bearerAuth(device.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, read.status, read.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, deleted.status, deleted.bodyAsText())
        assertEquals("available", inviteState(account.user.id, inviteId))
    }

    @Test
    fun onlyCreatorCanCancelAndCancellationIsIdempotent() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccount()
        val creator = registerDevice(account.accessToken, "Creator", "android")
        val other = registerDevice(account.accessToken, "Other", "ios")
        val inviteId = identifier('z')
        assertEquals(HttpStatusCode.Created, putInvite(creator.accessToken, inviteId, createRequest("{}")).status)

        val denied = postEmpty("/pairing/invites/$inviteId/cancel", other.accessToken)
        assertEquals(HttpStatusCode.Conflict, denied.status, denied.body)
        assertEquals("available", inviteState(account.user.id, inviteId))

        assertEquals(HttpStatusCode.NoContent, postEmpty("/pairing/invites/$inviteId/cancel", creator.accessToken).status)
        assertEquals(HttpStatusCode.NoContent, postEmpty("/pairing/invites/$inviteId/cancel", creator.accessToken).status)
        assertEquals("cancelled", inviteState(account.user.id, inviteId))
        assertNull(envelopeJson(account.user.id, inviteId))

        val claim = postJson(
            "/pairing/invites/$inviteId/claim",
            other.accessToken,
            PairingInviteClaimRequest(identifier('q')),
        )
        assertEquals(HttpStatusCode.Conflict, claim.status, claim.body)
    }

    @Test
    fun expiredInvitationReturnsGoneAndPurgesCiphertext() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccount()
        val creator = registerDevice(account.accessToken, "Creator", "android")
        val inviteId = identifier('e')
        assertEquals(HttpStatusCode.Created, putInvite(creator.accessToken, inviteId, createRequest("{}")).status)
        expireInvite(account.user.id, inviteId)

        val claim = postJson(
            "/pairing/invites/$inviteId/claim",
            creator.accessToken,
            PairingInviteClaimRequest(identifier('r')),
        )
        assertEquals(HttpStatusCode.Gone, claim.status, claim.body)
        assertNull(inviteState(account.user.id, inviteId))
    }

    private suspend fun ApplicationTestBuilder.registerAccount(): AuthTokensResponse {
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    AuthRequest("pairing-${System.nanoTime()}@example.com", "valid-password"),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.registerDevice(
        accessToken: String,
        name: String,
        platform: String,
    ): DeviceRegistrationResponse {
        val response = client.post("/devices/register") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DeviceRegistrationRequest(java.util.UUID.randomUUID().toString(), name, platform)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.putInvite(
        accessToken: String,
        inviteId: String,
        request: PairingInviteCreateRequest,
    ): HttpResult {
        val response = client.put("/pairing/invites/$inviteId") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }
        return HttpResult(response.status, response.bodyAsText())
    }

    private suspend inline fun <reified T> ApplicationTestBuilder.postJson(
        path: String,
        accessToken: String,
        body: T,
    ): HttpResult {
        val response = client.post(path) {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        }
        return HttpResult(response.status, response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.postEmpty(path: String, accessToken: String): HttpResult {
        val response = client.post(path) {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        return HttpResult(response.status, response.bodyAsText())
    }

    private fun createRequest(envelope: String): PairingInviteCreateRequest =
        PairingInviteCreateRequest(
            envelopeJson = envelope,
            envelopeDigest = digest(envelope.encodeToByteArray()),
            expiresAtEpochMillis = Instant.now().plusSeconds(600).toEpochMilli(),
        )

    private fun inviteState(userId: String, inviteId: String): String? =
        queryInvite(userId, inviteId) { it.getString("state") }

    private fun envelopeJson(userId: String, inviteId: String): String? =
        queryInvite(userId, inviteId) { it.getString("envelope_json") }

    private fun <T> queryInvite(
        userId: String,
        inviteId: String,
        read: (java.sql.ResultSet) -> T,
    ): T? =
        DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
            connection.prepareStatement(
                "SELECT state, envelope_json FROM workspace_pairing_invites WHERE user_id = ?::uuid AND invite_id = ?",
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, inviteId)
                statement.executeQuery().use { result ->
                    if (result.next()) read(result) else null
                }
            }
        }

    private fun expireInvite(userId: String, inviteId: String) {
        DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
            connection.prepareStatement(
                "UPDATE workspace_pairing_invites SET expires_at = NOW() - INTERVAL '1 second' " +
                    "WHERE user_id = ?::uuid AND invite_id = ?",
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, inviteId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun clearServerTables() {
        runCatching {
            DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        TRUNCATE TABLE
                            workspace_pairing_invites,
                            someday_refresh_tokens, someday_sessions,
                            someday_devices, someday_users
                        CASCADE
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    private data class HttpResult(val status: HttpStatusCode, val body: String)
}

private fun identifier(character: Char): String = character.toString().repeat(22)

private fun digest(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )
