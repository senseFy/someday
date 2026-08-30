package saien.someday.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import saien.someday.server.api.AuthRequest
import saien.someday.server.api.AuthTokensResponse
import saien.someday.server.api.DeviceRegistrationRequest
import saien.someday.server.api.DeviceRegistrationResponse
import saien.someday.server.api.WorkspaceRecoveryEnvelopePutRequest
import saien.someday.server.api.WorkspaceRecoveryEnvelopeResponse

class WorkspaceRecoveryEnvelopeApiIntegrationTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
    private val databaseUrl = System.getenv("SOMEDAY_DB_URL") ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    private val databaseUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    private val databasePassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"
    private val databaseConnectionUrl = productionTestDatabaseConnectionUrl(databaseUrl)

    @BeforeTest fun setUp() = clearServerTables()
    @AfterTest fun tearDown() = clearServerTables()

    @Test
    fun envelopeLifecycleIsAccountScopedDeviceBoundOpaqueAndReplaySafe() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val firstAccount = registerAccount()
        val firstDevice = registerDevice(firstAccount.accessToken, "First", "android")
        val secondAccount = registerAccount()
        val secondDevice = registerDevice(secondAccount.accessToken, "Second", "desktop")

        getEnvelope(firstDevice.accessToken).also {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("no-store", it.cacheControl)
        }
        assertEquals(
            HttpStatusCode.Forbidden,
            getEnvelope(firstAccount.accessToken).status,
        )

        val initialEnvelope = """{"format":"someday-workspace-recovery-v1","wrappedKey":"opaque-a"}"""
        val initial = request(initialEnvelope, expectedRevision = null)
        assertFalse(initial.toString().contains(initialEnvelope))
        assertEquals(
            HttpStatusCode.Conflict,
            putEnvelope(firstDevice.accessToken, initial).status,
            "A recovery envelope cannot select an uninitialized workspace.",
        )

        seedActiveWorkspace(firstAccount.user.id, WORKSPACE_ID, "first")
        val createdResult = putEnvelope(firstDevice.accessToken, initial)
        assertEquals(HttpStatusCode.Created, createdResult.status, createdResult.body)
        assertEquals("no-store", createdResult.cacheControl)
        val created = json.decodeFromString<WorkspaceRecoveryEnvelopeResponse>(createdResult.body)
        assertEquals(1L, created.revision)
        assertEquals(initialEnvelope, created.envelopeJson)
        assertFalse(created.toString().contains(initialEnvelope))

        val readResult = getEnvelope(firstDevice.accessToken).also {
            assertEquals(HttpStatusCode.OK, it.status, it.body)
            assertEquals("no-store", it.cacheControl)
        }
        val read = json.decodeFromString<WorkspaceRecoveryEnvelopeResponse>(readResult.body)
        assertEquals(created, read)
        assertEquals(initialEnvelope, storedEnvelope(firstAccount.user.id))

        // The same workspace identifier under another account is a separate
        // recovery namespace, and absence is not disclosed across accounts.
        assertEquals(HttpStatusCode.NotFound, getEnvelope(secondDevice.accessToken).status)
        seedActiveWorkspace(secondAccount.user.id, WORKSPACE_ID, "second")
        val secondEnvelope = """{"format":"someday-workspace-recovery-v1","wrappedKey":"opaque-b"}"""
        assertEquals(
            HttpStatusCode.Created,
            putEnvelope(secondDevice.accessToken, request(secondEnvelope, null)).status,
        )
        assertEquals(secondEnvelope, storedEnvelope(secondAccount.user.id))
        assertEquals(initialEnvelope, storedEnvelope(firstAccount.user.id))

        val updatedEnvelope = """{"format":"someday-workspace-recovery-v1","wrappedKey":"opaque-rotated"}"""
        val update = request(updatedEnvelope, expectedRevision = 1)
        val updatedResult = putEnvelope(firstDevice.accessToken, update)
        assertEquals(HttpStatusCode.OK, updatedResult.status, updatedResult.body)
        val updated = json.decodeFromString<WorkspaceRecoveryEnvelopeResponse>(updatedResult.body)
        assertEquals(2L, updated.revision)

        // A retry with the old expected revision is safe only when every
        // opaque identity field still matches the stored value.
        val replayResult = putEnvelope(firstDevice.accessToken, update)
        assertEquals(HttpStatusCode.OK, replayResult.status, replayResult.body)
        val replay = json.decodeFromString<WorkspaceRecoveryEnvelopeResponse>(replayResult.body)
        assertEquals(2L, replay.revision)
        assertEquals(updated, replay)

        val stale = request("""{"wrappedKey":"stale-different"}""", expectedRevision = 1)
        assertEquals(HttpStatusCode.Conflict, putEnvelope(firstDevice.accessToken, stale).status)
        assertEquals(updatedEnvelope, storedEnvelope(firstAccount.user.id))

        deleteAccount(firstAccount.user.id)
        assertNull(storedEnvelope(firstAccount.user.id))
    }

    @Test
    fun requestValidationBoundsOpaqueBytesAndCanonicalIdentityFields() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccount()
        val device = registerDevice(account.accessToken, "Validator", "android")
        seedActiveWorkspace(account.user.id, WORKSPACE_ID, "validation")

        val valid = request("{}", null)
        assertEquals(
            HttpStatusCode.BadRequest,
            putEnvelope(device.accessToken, valid.copy(workspaceId = "workspace-invalid")).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            putEnvelope(device.accessToken, valid.copy(keyFingerprint = "not-a-fingerprint")).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            putEnvelope(device.accessToken, valid.copy(envelopeDigest = "A".repeat(43))).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            putEnvelope(device.accessToken, valid.copy(expectedRevision = 0)).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            putEnvelope(device.accessToken, request(" ", null)).status,
        )

        // Validation failures consume the same abuse-prevention budget as
        // accepted writes. Use another authenticated device for byte-bound tests.
        val sizeDevice = registerDevice(account.accessToken, "Size validator", "desktop")
        val overEnvelopeLimit = "x".repeat(64 * 1_024 + 1)
        assertEquals(
            HttpStatusCode.BadRequest,
            putEnvelope(sizeDevice.accessToken, request(overEnvelopeLimit, null)).status,
        )
        val boundaryDevice = registerDevice(account.accessToken, "Boundary validator", "ios")
        val escapedBoundaryEnvelope = "\"".repeat(64 * 1_024)
        assertEquals(
            HttpStatusCode.Created,
            putEnvelope(boundaryDevice.accessToken, request(escapedBoundaryEnvelope, null)).status,
        )
        val overRequestLimit = "x".repeat(400 * 1_024)
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            putEnvelope(sizeDevice.accessToken, request(overRequestLimit, null)).status,
        )
    }

    @Test
    fun concurrentCompareAndSetAllowsExactlyOneDifferentRotation() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccount()
        val firstDevice = registerDevice(account.accessToken, "Writer A", "android")
        val secondDevice = registerDevice(account.accessToken, "Writer B", "ios")
        seedActiveWorkspace(account.user.id, WORKSPACE_ID, "concurrent")
        assertEquals(
            HttpStatusCode.Created,
            putEnvelope(firstDevice.accessToken, request("""{"wrappedKey":"initial"}""", null)).status,
        )

        val results = coroutineScope {
            listOf(
                async {
                    putEnvelope(
                        firstDevice.accessToken,
                        request("""{"wrappedKey":"candidate-a"}""", expectedRevision = 1),
                    )
                },
                async {
                    putEnvelope(
                        secondDevice.accessToken,
                        request("""{"wrappedKey":"candidate-b"}""", expectedRevision = 1),
                    )
                },
            ).map { it.await() }
        }
        assertEquals(
            listOf(HttpStatusCode.OK, HttpStatusCode.Conflict),
            results.map(HttpResult::status).sortedBy(HttpStatusCode::value),
        )
        val current = json.decodeFromString<WorkspaceRecoveryEnvelopeResponse>(
            getEnvelope(firstDevice.accessToken).body,
        )
        assertEquals(2L, current.revision)
        assertTrue(current.envelopeJson.contains("candidate-a") || current.envelopeJson.contains("candidate-b"))
    }

    private suspend fun ApplicationTestBuilder.registerAccount(): AuthTokensResponse {
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    AuthRequest("recovery-${UUID.randomUUID()}@example.com", "valid-password"),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.registerDevice(
        accountAccessToken: String,
        name: String,
        platform: String,
    ): DeviceRegistrationResponse {
        val response = client.post("/devices/register") {
            bearerAuth(accountAccessToken)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    DeviceRegistrationRequest(UUID.randomUUID().toString(), name, platform),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.getEnvelope(accessToken: String): HttpResult {
        val response = client.get("/workspace/recovery-envelope") {
            bearerAuth(accessToken)
        }
        return HttpResult(response.status, response.bodyAsText(), response.headers[HttpHeaders.CacheControl])
    }

    private suspend fun ApplicationTestBuilder.putEnvelope(
        accessToken: String,
        request: WorkspaceRecoveryEnvelopePutRequest,
    ): HttpResult = putEnvelopeJson(accessToken, json.encodeToString(request))

    private suspend fun ApplicationTestBuilder.putEnvelopeJson(
        accessToken: String,
        encodedRequest: String,
    ): HttpResult {
        val response = client.put("/workspace/recovery-envelope") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(encodedRequest)
        }
        return HttpResult(response.status, response.bodyAsText(), response.headers[HttpHeaders.CacheControl])
    }

    private fun request(
        envelopeJson: String,
        expectedRevision: Long?,
    ): WorkspaceRecoveryEnvelopePutRequest =
        WorkspaceRecoveryEnvelopePutRequest(
            workspaceId = WORKSPACE_ID,
            keyFingerprint = KEY_FINGERPRINT,
            envelopeJson = envelopeJson,
            envelopeDigest = digest(envelopeJson.encodeToByteArray()),
            expectedRevision = expectedRevision,
        )

    private fun seedActiveWorkspace(userId: String, workspaceId: String, label: String) {
        DriverManager.getConnection(databaseConnectionUrl, databaseUser, databasePassword).use { connection ->
            setWildcardScope(connection)
            connection.prepareStatement(
                "INSERT INTO someday_entity_workspaces(user_id, workspace_id) VALUES (?::uuid, ?)",
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, workspaceId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO someday_sync_v2_epochs(
                    user_id, workspace_id, epoch_id, pointer_digest, pointer_object_json,
                    contract_id, schema_set_version, semantic_protocol_version,
                    minimum_writer_protocol_version, key_set_version, remote_profile,
                    metadata_privacy_mode, supported_offline_window_seconds,
                    checkpoint_id, checkpoint_digest
                ) VALUES (?::uuid, ?, ?, ?, '{}', 'someday-system-v2',
                          'workspace-entity-schema-set-v2', 2, 2, 'sync-key-set-v2',
                          'self-hosted-v2', 'opaque', 15552000, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, workspaceId)
                statement.setString(3, "00000000-0000-4000-8000-${label.hashCode().toUInt().toString(16).padStart(12, '0')}")
                statement.setString(4, "pointer-$label")
                statement.setString(5, "10000000-0000-4000-8000-${label.hashCode().toUInt().toString(16).padStart(12, '0')}")
                statement.setString(6, "checkpoint-$label")
                statement.executeUpdate()
            }
        }
    }

    private fun storedEnvelope(userId: String): String? =
        DriverManager.getConnection(databaseConnectionUrl, databaseUser, databasePassword).use { connection ->
            setWildcardScope(connection)
            connection.prepareStatement(
                "SELECT envelope_json FROM workspace_recovery_envelopes WHERE user_id = ?::uuid",
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
            }
        }

    private fun deleteAccount(userId: String) {
        DriverManager.getConnection(databaseConnectionUrl, databaseUser, databasePassword).use { connection ->
            connection.prepareStatement("DELETE FROM someday_users WHERE id = ?::uuid").use { statement ->
                statement.setString(1, userId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun setWildcardScope(connection: java.sql.Connection) {
        connection.prepareStatement(
            "SELECT set_config('someday.user_id', '*', false), set_config('someday.workspace_id', '*', false)",
        ).use { statement -> statement.executeQuery().close() }
    }

    private fun clearServerTables() {
        runCatching {
            DriverManager.getConnection(databaseConnectionUrl, databaseUser, databasePassword).use { connection ->
                setWildcardScope(connection)
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE TABLE someday_users CASCADE")
                }
            }
        }
    }

    private data class HttpResult(
        val status: HttpStatusCode,
        val body: String,
        val cacheControl: String?,
    )

    private companion object {
        const val WORKSPACE_ID = "workspace-0123456789abcdef0123456789abcdef"
        const val KEY_FINGERPRINT = "0123456789abcdef0123456789abcdef"
    }
}

private fun digest(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )
