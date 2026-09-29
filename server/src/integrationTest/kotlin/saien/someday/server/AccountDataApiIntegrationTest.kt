package saien.someday.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.Json
import saien.someday.server.api.AccountDataStateResponse
import saien.someday.server.api.ErrorResponse
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.persistence.AccountAdmission
import saien.someday.server.persistence.AccountLockMode
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.routes.ACCOUNT_ERROR_HEADER
import saien.someday.server.routes.ACCOUNT_INCARNATION_HEADER
import saien.someday.server.routes.ACCOUNT_PROTOCOL_HEADER
import saien.someday.server.support.ControllableMediaBlobStore
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.TestServerIdentity

class AccountDataApiIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: PostgresContractFixture
    private lateinit var identity: TestServerIdentity

    @BeforeTest fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("account-http").toPath())
        database.reset()
        identity = database.seedIdentity()
    }

    @AfterTest fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test fun disabledServerStillOffersAuthenticatedNoStoreDiscoveryAndReceiptLookup() = testApplication {
        val context = ServerContext.create(database.config, ControllableMediaBlobStore())
        application { somedayServerModule(context) }
        val tokens = context.tokenService.issueTokens(
            identity.userId, identity.sessionId, identity.deviceId, false, identity.request.tokenScopes,
        )
        val discovery = client.get("/account/data-state") { bearerAuth(tokens.accessToken) }
        assertEquals(HttpStatusCode.OK, discovery.status, discovery.bodyAsText())
        assertEquals("no-store", discovery.headers[HttpHeaders.CacheControl])
        val state = Json.decodeFromString<AccountDataStateResponse>(discovery.bodyAsText())
        assertEquals(1, state.protocolVersion)
        assertEquals(ACCOUNT_INITIAL_INCARNATION.toString(), state.accountIncarnation)
        assertFalse(state.resetAvailable)
        assertEquals("deployment_not_ready", state.resetUnavailableReason)

        val receipt = client.get("/account/data-resets/${UUID.randomUUID()}") { bearerAuth(tokens.accessToken) }
        assertEquals(HttpStatusCode.NotFound, receipt.status)
        assertEquals("not_found", receipt.headers[ACCOUNT_ERROR_HEADER])
        assertEquals("no-store", receipt.headers[HttpHeaders.CacheControl])
        val anonymous = client.get("/account/data-state")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals("unauthorized", anonymous.headers[ACCOUNT_ERROR_HEADER])

        // A version nibble alone does not make an RFC UUIDv4 operation ID.
        val invalidVariant = client.get("/account/data-resets/11111111-1111-4111-0111-111111111111") {
            bearerAuth(tokens.accessToken)
        }
        assertEquals(HttpStatusCode.BadRequest, invalidVariant.status)
        assertEquals("invalid_request", invalidVariant.headers[ACCOUNT_ERROR_HEADER])
        assertEquals("invalid_request", Json.decodeFromString<ErrorResponse>(invalidVariant.bodyAsText()).error)
        assertEquals("no-store", invalidVariant.headers[HttpHeaders.CacheControl])
    }

    @Test fun genericIncarnationErrorsCarryTheSameHeaderOnJsonAndBodylessHead() = testApplication {
        val context = ServerContext.create(database.config, ControllableMediaBlobStore())
        application { somedayServerModule(context) }
        val tokens = context.tokenService.issueTokens(
            identity.userId, identity.sessionId, identity.deviceId, false, identity.request.tokenScopes,
        )
        val different = UUID.randomUUID().toString()
        val capabilities = client.get("/sync/v3/capabilities") {
            bearerAuth(tokens.accessToken)
            header(ACCOUNT_PROTOCOL_HEADER, "1")
            header(ACCOUNT_INCARNATION_HEADER, different)
        }
        assertEquals(HttpStatusCode.Conflict, capabilities.status, capabilities.bodyAsText())
        assertEquals("account_incarnation_mismatch", capabilities.headers[ACCOUNT_ERROR_HEADER])
        assertEquals("account_incarnation_mismatch", Json.decodeFromString<ErrorResponse>(capabilities.bodyAsText()).error)
        val head = client.head("/sync/v3/workspaces/workspace-${"a".repeat(32)}/media/${"b".repeat(64)}") {
            bearerAuth(tokens.accessToken)
            header(ACCOUNT_PROTOCOL_HEADER, "1")
            header(ACCOUNT_INCARNATION_HEADER, different)
        }
        assertEquals(HttpStatusCode.Conflict, head.status)
        assertEquals("account_incarnation_mismatch", head.headers[ACCOUNT_ERROR_HEADER])
        assertEquals("", head.bodyAsText())
    }

    @Test fun currentSessionsRejectPartialDuplicateMalformedAndUnsupportedProtocolHeaders() = testApplication {
        val context = ServerContext.create(database.config, ControllableMediaBlobStore())
        application { somedayServerModule(context) }
        val tokens = context.tokenService.issueTokens(
            identity.userId, identity.sessionId, identity.deviceId, false, identity.request.tokenScopes,
        )
        val current = ACCOUNT_INITIAL_INCARNATION.toString()
        val malformedHeaders = listOf(
            listOf(ACCOUNT_PROTOCOL_HEADER to "1"),
            listOf(ACCOUNT_INCARNATION_HEADER to current),
            listOf(ACCOUNT_PROTOCOL_HEADER to "1", ACCOUNT_INCARNATION_HEADER to "not-a-uuid"),
            listOf(ACCOUNT_PROTOCOL_HEADER to "1", ACCOUNT_INCARNATION_HEADER to "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
            listOf(ACCOUNT_PROTOCOL_HEADER to "1", ACCOUNT_PROTOCOL_HEADER to "1", ACCOUNT_INCARNATION_HEADER to current),
            listOf(ACCOUNT_PROTOCOL_HEADER to "1", ACCOUNT_INCARNATION_HEADER to current, ACCOUNT_INCARNATION_HEADER to current),
        )
        for (values in malformedHeaders) {
            val response = client.get("/sync/v3/capabilities") {
                bearerAuth(tokens.accessToken)
                values.forEach { (name, value) -> header(name, value) }
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "Headers: $values; body: ${response.bodyAsText()}")
            assertEquals("invalid_request", response.headers[ACCOUNT_ERROR_HEADER])
            assertEquals("invalid_request", Json.decodeFromString<ErrorResponse>(response.bodyAsText()).error)
        }
        val unsupported = client.get("/sync/v3/capabilities") {
            bearerAuth(tokens.accessToken)
            header(ACCOUNT_PROTOCOL_HEADER, "2")
            header(ACCOUNT_INCARNATION_HEADER, current)
        }
        assertEquals(HttpStatusCode.UpgradeRequired, unsupported.status, unsupported.bodyAsText())
        assertEquals("account_protocol_upgrade_required", unsupported.headers[ACCOUNT_ERROR_HEADER])
        assertEquals("account_protocol_upgrade_required", Json.decodeFromString<ErrorResponse>(unsupported.bodyAsText()).error)
    }

    @Test fun retiredSessionIsRejectedBeforeMissingMalformedOrUnsupportedProtocolHeaders() = testApplication {
        val context = ServerContext.create(database.config, ControllableMediaBlobStore())
        application { somedayServerModule(context) }
        val tokens = context.tokenService.issueTokens(
            identity.userId, identity.sessionId, identity.deviceId, false, identity.request.tokenScopes,
        )
        val connections = DatabaseConnectionProvider {
            DriverManager.getConnection(database.config.databaseConnectionUrl, database.config.databaseUser, database.config.databasePassword)
        }
        AccountAdmission.transaction(connections, listOf(identity.userId), AccountLockMode.EXCLUSIVE) { connection ->
            connection.prepareStatement(
                "UPDATE someday_account_data_incarnations SET state = 'retired', retired_at = clock_timestamp() WHERE user_id = ? AND state = 'active'",
            ).use { statement ->
                statement.setObject(1, identity.userId)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "INSERT INTO someday_account_data_incarnations(user_id, incarnation, state, storage_layout, created_at) VALUES (?, ?, 'active', 'incarnation-v1', clock_timestamp())",
            ).use { statement ->
                statement.setObject(1, identity.userId)
                statement.setObject(2, UUID.randomUUID())
                statement.executeUpdate()
            }
        }
        val protocolHeaders = listOf(
            emptyList(),
            listOf(ACCOUNT_PROTOCOL_HEADER to "1", ACCOUNT_INCARNATION_HEADER to "not-a-uuid"),
            listOf(ACCOUNT_PROTOCOL_HEADER to "2", ACCOUNT_INCARNATION_HEADER to ACCOUNT_INITIAL_INCARNATION.toString()),
        )
        for (values in protocolHeaders) {
            val response = client.get("/sync/v3/capabilities") {
                bearerAuth(tokens.accessToken)
                values.forEach { (name, value) -> header(name, value) }
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status, "Headers: $values; body: ${response.bodyAsText()}")
            assertEquals("account_session_stale", response.headers[ACCOUNT_ERROR_HEADER])
            assertEquals("account_session_stale", Json.decodeFromString<ErrorResponse>(response.bodyAsText()).error)
        }
    }
}
