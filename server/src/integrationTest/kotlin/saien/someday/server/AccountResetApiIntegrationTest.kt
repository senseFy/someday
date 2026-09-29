package saien.someday.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.api.AccountDataResetReceiptResponse
import saien.someday.server.api.AccountDataResetRequest
import saien.someday.server.api.AccountDataStateResponse
import saien.someday.server.api.ErrorResponse
import saien.someday.server.auth.CredentialWorkUnavailableException
import saien.someday.server.auth.PasswordHasher
import saien.someday.server.auth.scopesForDevice
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountDataRepository
import saien.someday.server.persistence.VerifiedPasswordAccount
import saien.someday.server.routes.ACCOUNT_ERROR_HEADER
import saien.someday.server.routes.ACCOUNT_INCARNATION_HEADER
import saien.someday.server.routes.ACCOUNT_PROTOCOL_HEADER
import saien.someday.server.support.ControllableMediaBlobStore
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.TestServerIdentity

class AccountResetApiIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: PostgresContractFixture
    private lateinit var identity: TestServerIdentity

    @BeforeTest fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("reset-http").toPath())
        database.reset()
        identity = database.seedIdentity()
    }

    @AfterTest fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test fun commitStalesCredentialsAndAuthenticatedExactReplayPrecedesPasswordAndReadiness() = testApplication {
        val hasher = CountingHasher()
        val ready = AtomicBoolean(true)
        val context = context(hasher, ready)
        application { somedayServerModule(context) }
        val oldToken = token(context)
        val operation = UUID.randomUUID()
        val submitted = request(operation)
        val committed = client.submit(oldToken, submitted)
        assertEquals(HttpStatusCode.OK, committed.status, committed.bodyAsText())
        assertEquals("no-store", committed.headers[HttpHeaders.CacheControl])
        val receipt = Json.decodeFromString<AccountDataResetReceiptResponse>(committed.bodyAsText())
        assertEquals(operation.toString(), receipt.operationId)
        assertEquals(INITIAL, receipt.previousIncarnation)
        assertNotEquals(INITIAL, receipt.newIncarnation)
        assertEquals(4, UUID.fromString(receipt.newIncarnation).version())
        assertEquals(1, hasher.calls)
        assertEquals(1L, receiptCount())

        assertError(client.submit(oldToken, submitted), HttpStatusCode.Unauthorized, "account_session_stale")
        assertError(
            client.submitRaw(oldToken, Json.encodeToString(submitted), headerIncarnation = null),
            HttpStatusCode.Unauthorized, "account_session_stale",
        )
        assertError(
            client.submitRaw(oldToken, Json.encodeToString(submitted), headerIncarnation = "not-an-incarnation"),
            HttpStatusCode.Unauthorized, "account_session_stale",
        )
        val freshToken = freshLoginToken(context)
        ready.set(false)
        val discovery = client.get("/account/data-state") { bearerAuth(freshToken) }
        val state = Json.decodeFromString<AccountDataStateResponse>(discovery.bodyAsText())
        assertEquals(receipt.newIncarnation, state.accountIncarnation)
        assertFalse(state.resetAvailable)
        assertEquals("deployment_not_ready", state.resetUnavailableReason)
        val replay = client.submit(freshToken, request(operation, password = ""))
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertEquals(receipt, Json.decodeFromString<AccountDataResetReceiptResponse>(replay.bodyAsText()))
        assertEquals(1, hasher.calls, "Exact receipt replay must not verify a password.")
        val lookup = client.get("/account/data-resets/$operation") { bearerAuth(freshToken) }
        assertEquals(receipt, Json.decodeFromString<AccountDataResetReceiptResponse>(lookup.bodyAsText()))
        assertEquals("no-store", lookup.headers[HttpHeaders.CacheControl])

        assertError(
            client.submit(freshToken, request(operation, receipt.newIncarnation)),
            HttpStatusCode.Conflict, "reset_request_conflict",
        )
        assertEquals(1, hasher.calls, "Identity conflict also precedes password work.")
        assertError(
            client.submit(freshToken, request(expected = receipt.newIncarnation)),
            HttpStatusCode.ServiceUnavailable, "account_reset_unavailable",
        )
        ready.set(true)
        assertError(
            client.submit(freshToken, request(expected = receipt.newIncarnation)),
            HttpStatusCode.Conflict, "retired_media_pending",
        )
        assertEquals(1L, receiptCount())
        val other = database.seedIdentity()
        assertError(
            client.get("/account/data-resets/$operation") { bearerAuth(token(context, other)) },
            HttpStatusCode.NotFound, "not_found",
        )
    }

    @Test fun rejectsMalformedBoundedRequestsBeforePasswordWork() = testApplication {
        val hasher = CountingHasher()
        val context = context(hasher)
        application { somedayServerModule(context) }
        val token = token(context)
        val valid = Json.encodeToString(request())
        val malformed = listOf(
            "{}",
            valid.dropLast(1) + ",\"userId\":\"${identity.userId}\"}",
            valid.dropLast(1) + ",\"password\":\"another-password\"}",
            Json.encodeToString(request(operation = UUID.fromString("11111111-1111-4111-0111-111111111111"))),
            valid.replace("reset-all-account-data-v1", "reset-some-data"),
            valid.replace(INITIAL, "not-an-incarnation"),
        )
        for (body in malformed) {
            assertError(client.submitRaw(token, body), HttpStatusCode.BadRequest, "invalid_request")
        }
        assertError(
            client.submitRaw(token, byteArrayOf(0xc3.toByte(), 0x28)),
            HttpStatusCode.BadRequest, "invalid_request",
        )
        assertError(
            client.submitRaw(token, " ".repeat(4097)),
            HttpStatusCode.PayloadTooLarge, "request_body_too_large",
        )
        assertError(
            client.submitRaw(token, valid.replace("\"protocolVersion\":1", "\"protocolVersion\":2")),
            HttpStatusCode.UpgradeRequired, "account_protocol_upgrade_required",
        )
        assertError(
            client.submitRaw(token, valid, headerIncarnation = UUID.randomUUID().toString()),
            HttpStatusCode.BadRequest, "invalid_request",
        )
        assertError(
            client.submitRaw(token, valid, headerIncarnation = null),
            HttpStatusCode.UpgradeRequired, "account_protocol_upgrade_required",
        )
        assertEquals(0, hasher.calls)
        assertEquals(0L, receiptCount())
        assertEquals(INITIAL, context.accountDataRepository.discover(identity.request).accountIncarnation)
        assertFalse(request(password = PASSWORD).toString().contains(PASSWORD))
    }

    @Test fun wrongPasswordAndVerifierSaturationLeaveNoReceipt() = testApplication {
        val hasher = CountingHasher()
        val context = context(hasher)
        application { somedayServerModule(context) }
        val token = token(context)
        assertError(client.submit(token, request(password = "incorrect-password")), HttpStatusCode.Forbidden, "invalid_credentials")
        assertEquals(1, hasher.calls)
        assertError(client.submit(token, request(password = "short")), HttpStatusCode.Forbidden, "invalid_credentials")
        assertEquals(1, hasher.calls, "Invalid password bounds must not reach the verifier.")
        hasher.unavailable = true
        assertError(client.submit(token, request()), HttpStatusCode.ServiceUnavailable, "authentication_busy")
        assertEquals(0L, receiptCount())
        assertEquals(INITIAL, context.accountDataRepository.discover(identity.request).accountIncarnation)
    }

    @Test fun passwordVerificationHoldsNoAccountLockAndChangedHashCannotAuthorizeReset() = testApplication {
        val hasher = CountingHasher()
        val context = context(hasher)
        application { somedayServerModule(context) }
        hasher.onVerify = {
            connection().use { connection ->
                connection.autoCommit = false
                connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?, ?)").use { statement ->
                    statement.setInt(1, AccountAdmission.LOCK_NAMESPACE)
                    statement.setInt(2, AccountAdmission.lockKey(identity.userId))
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertTrue(result.getBoolean(1), "Password verification must happen outside account admission.")
                    }
                }
                connection.prepareStatement("UPDATE someday_users SET password_hash = ? WHERE id = ?").use { statement ->
                    statement.setString(1, "changed-test-hash")
                    statement.setObject(2, identity.userId)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.commit()
            }
        }
        assertError(client.submit(token(context), request()), HttpStatusCode.Unauthorized, "unauthorized")
        assertEquals(1, hasher.calls)
        assertEquals(0L, receiptCount())
        assertEquals(INITIAL, context.accountDataRepository.discover(identity.request).accountIncarnation)
    }

    private fun context(hasher: PasswordHasher, ready: AtomicBoolean = AtomicBoolean(true)): ServerContext {
        val config = database.config.copy(accountResetEnabled = true, rateLimitMaxAttempts = 100)
        val base = ServerContext.create(config, ControllableMediaBlobStore())
        return ServerContext(
            config = config,
            repository = base.repository,
            syncV2Repository = base.syncV2Repository,
            systemV3MediaRepository = base.systemV3MediaRepository,
            workspaceRecoveryEnvelopeRepository = base.workspaceRecoveryEnvelopeRepository,
            adminRepository = base.adminRepository,
            credentialHasher = hasher,
            dummyPasswordHash = base.dummyPasswordHash,
            tokenService = base.tokenService,
            rateLimiter = base.rateLimiter,
            systemV3RateLimiter = base.systemV3RateLimiter,
            startedAt = base.startedAt,
            databaseConnectionPool = base,
            accountDataRepository = AccountDataRepository(config, readiness = ready::get),
        )
    }

    private fun token(context: ServerContext, value: TestServerIdentity = identity): String = context.tokenService.issueTokens(
        value.userId, value.sessionId, value.deviceId, false, value.request.tokenScopes,
    ).accessToken

    private fun freshLoginToken(context: ServerContext): String {
        val issued = context.repository.issuePasswordSession(
            VerifiedPasswordAccount(identity.userId, "test-only"),
            refreshTokenHash = UUID.randomUUID().toString(),
            sessionExpiresAt = Instant.now().plusSeconds(3600),
            refreshExpiresAt = Instant.now().plusSeconds(3600),
        )
        return context.tokenService.issueTokens(
            issued.userId, issued.sessionId, null, issued.isAdmin, scopesForDevice(null),
        ).accessToken
    }

    private fun request(
        operation: UUID = UUID.randomUUID(),
        expected: String = INITIAL,
        password: String = PASSWORD,
    ) = AccountDataResetRequest(1, operation.toString(), expected, "reset-all-account-data-v1", password)

    private suspend fun HttpClient.submit(token: String, request: AccountDataResetRequest): HttpResponse =
        submitRaw(token, Json.encodeToString(request), request.expectedIncarnation)

    private suspend fun HttpClient.submitRaw(token: String, body: Any, headerIncarnation: String? = INITIAL): HttpResponse =
        post("/account/data-resets") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            if (headerIncarnation != null) {
                header(ACCOUNT_PROTOCOL_HEADER, "1")
                header(ACCOUNT_INCARNATION_HEADER, headerIncarnation)
            }
            setBody(body)
        }

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, code: String) {
        assertEquals(status, response.status, response.bodyAsText())
        assertEquals(code, response.headers[ACCOUNT_ERROR_HEADER])
        assertEquals(code, Json.decodeFromString<ErrorResponse>(response.bodyAsText()).error)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertFalse(response.bodyAsText().contains(PASSWORD))
    }

    private fun connection(): Connection = DriverManager.getConnection(
        database.config.databaseConnectionUrl, database.config.databaseUser, database.config.databasePassword,
    )

    private fun receiptCount(): Long = connection().use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM someday_account_data_resets WHERE user_id = ?").use { statement ->
            statement.setObject(1, identity.userId)
            statement.executeQuery().use { result -> check(result.next()); result.getLong(1) }
        }
    }

    private class CountingHasher : PasswordHasher {
        var calls = 0
        var unavailable = false
        var onVerify: () -> Unit = {}
        override fun hash(password: String): String = error("Reset API does not hash new passwords.")
        override fun verify(hash: String, password: String): Boolean {
            calls++
            if (unavailable) throw CredentialWorkUnavailableException()
            onVerify()
            return hash == "test-only" && password == PASSWORD
        }
    }

    private companion object {
        const val INITIAL = "00000000-0000-0000-0000-000000000000"
        const val PASSWORD = "valid-reset-password"
    }
}
