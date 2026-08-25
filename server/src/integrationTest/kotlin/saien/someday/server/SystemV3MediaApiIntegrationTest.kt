package saien.someday.server

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.api.AuthRequest
import saien.someday.server.api.AuthTokensResponse
import saien.someday.server.api.DeviceRegistrationRequest
import saien.someday.server.api.DeviceRegistrationResponse
import saien.someday.server.api.SystemV3CapabilitiesResponse
import saien.someday.server.api.SystemV3MediaPutResponse
import saien.someday.server.support.ConcurrentStartGate

class SystemV3MediaApiIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }
    private val dbUrl = System.getenv("SOMEDAY_DB_URL") ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    private val dbUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    private val dbPassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"

    @BeforeTest fun setUp() = clearServerTables()
    @AfterTest fun tearDown() = clearServerTables()

    @Test
    fun singleObjectApiIsWorkspaceScopedImmutableAndDownloadable() = testApplication {
        application { somedayServerModule(ServerContext.create(localConfig())) }
        clearServerTables()
        val account = registerAccount("media-${System.nanoTime()}@example.com")
        val device = registerDevice(account.accessToken)
        val other = registerDevice(registerAccount("other-${System.nanoTime()}@example.com").accessToken)

        val capabilities = client.get("/sync/v3/capabilities") { bearerAuth(device.accessToken) }
        assertEquals(HttpStatusCode.OK, capabilities.status)
        val advertised = json.decodeFromString<SystemV3CapabilitiesResponse>(capabilities.bodyAsText())
        assertEquals("/sync/v3/workspaces/{workspaceId}/media", advertised.media.apiBasePath)
        assertEquals(4 * 1024 * 1024, advertised.media.maxPlaintextBytes)

        val bytes = ByteArray(127) { (it * 3).toByte() }
        val digest = sha256(bytes)
        val path = mediaPath(WORKSPACE_A, MEDIA_ID)
        val created = putObject(device.accessToken, path, bytes, digest)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals(false, created.body<SystemV3MediaPutResponse>().idempotentReplay)
        assertEquals(HttpStatusCode.OK, putObject(device.accessToken, path, bytes, digest).status)

        val head = client.head(path) { bearerAuth(device.accessToken) }
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals(digest, head.headers[DIGEST_HEADER])
        assertEquals(bytes.size.toString(), head.headers[BYTES_HEADER])
        val downloaded = client.get(path) { bearerAuth(device.accessToken) }
        assertEquals(HttpStatusCode.OK, downloaded.status)
        assertContentEquals(bytes, downloaded.body<ByteArray>())

        val mismatch = ByteArray(127) { 9 }
        assertEquals(
            HttpStatusCode.Conflict,
            putObject(device.accessToken, path, mismatch, sha256(mismatch)).status,
        )
        assertEquals(HttpStatusCode.NotFound, client.head(mediaPath(WORKSPACE_B, MEDIA_ID)) {
            bearerAuth(device.accessToken)
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.head(path) { bearerAuth(other.accessToken) }.status)
        assertEquals(
            HttpStatusCode.Created,
            putObject(device.accessToken, mediaPath(WORKSPACE_B, MEDIA_ID), bytes, digest).status,
        )
    }

    @Test
    fun mediaRouteRejectsBadScopeTypeDigestAndOversize() = testApplication {
        application { somedayServerModule(ServerContext.create(localConfig())) }
        clearServerTables()
        val account = registerAccount("bounds-${System.nanoTime()}@example.com")
        val device = registerDevice(account.accessToken)
        val bytes = ByteArray(45) { 1 }
        val path = mediaPath(WORKSPACE_A, MEDIA_ID)

        assertEquals(HttpStatusCode.Forbidden, putObject(account.accessToken, path, bytes, sha256(bytes)).status)
        assertEquals(HttpStatusCode.BadRequest, putObject(device.accessToken, mediaPath("bad", MEDIA_ID), bytes, sha256(bytes)).status)
        val wrongType = client.put(path) {
            bearerAuth(device.accessToken)
            contentType(ContentType.Application.Json)
            header(DIGEST_HEADER, sha256(bytes))
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongType.status)
        assertEquals(HttpStatusCode.BadRequest, putObject(device.accessToken, path, bytes, "sha256:${"0".repeat(64)}").status)
        val oversized = ByteArray(4 * 1024 * 1024 + 4 * 1024 + 4 + 41)
        assertEquals(HttpStatusCode.PayloadTooLarge, putObject(device.accessToken, path, oversized, sha256(oversized)).status)
    }

    @Test
    fun accountQuotaIsAtomicAcrossWorkspacesAndStillAllowsExactReplay() = testApplication {
        application { somedayServerModule(ServerContext.create(localConfig(mediaQuotaBytes = 100))) }
        clearServerTables()
        val account = registerAccount("quota-${System.nanoTime()}@example.com")
        val device = registerDevice(account.accessToken)
        val attempts = listOf(
            UploadAttempt(mediaPath(WORKSPACE_A, MEDIA_ID), ByteArray(60) { 1 }),
            UploadAttempt(mediaPath(WORKSPACE_B, MEDIA_ID_B), ByteArray(60) { 2 }),
        )
        val startGate = ConcurrentStartGate(attempts.size)

        val responses = coroutineScope {
            attempts.map { attempt ->
                async(Dispatchers.IO) {
                    startGate.awaitRelease()
                    putObject(
                        device.accessToken,
                        attempt.path,
                        attempt.bytes,
                        sha256(attempt.bytes),
                    )
                }
            }.awaitAll()
        }

        assertEquals(
            listOf(HttpStatusCode.Created, HttpStatusCode.Conflict),
            responses.map { it.status }.sortedBy { it.value },
        )
        val winnerIndex = responses.indexOfFirst { it.status == HttpStatusCode.Created }
        assertTrue(winnerIndex >= 0)
        val winner = attempts[winnerIndex]
        assertEquals(
            HttpStatusCode.OK,
            putObject(device.accessToken, winner.path, winner.bytes, sha256(winner.bytes)).status,
        )
        val rejected = responses.single { it.status == HttpStatusCode.Conflict }
        assertEquals("media_quota_exceeded", rejected.body<SystemV3MediaPutResponse>().error)
    }

    private fun localConfig(mediaQuotaBytes: Long? = null): ServerConfig = ServerConfig.fromEnvironment(
        buildMap {
            put("SOMEDAY_DB_URL", dbUrl)
            put("SOMEDAY_DB_USER", dbUser)
            put("SOMEDAY_DB_PASSWORD", dbPassword)
            put(
                "SOMEDAY_MEDIA_BLOB_DIR",
                temporaryFolder.newFolder("media-${System.nanoTime()}").absolutePath,
            )
            put(
                "SOMEDAY_JWT_SECRET",
                "media-integration-test-jwt-secret-at-least-thirty-two-bytes",
            )
            mediaQuotaBytes?.let { put("SOMEDAY_MEDIA_QUOTA_BYTES", it.toString()) }
        },
    )

    private suspend fun ApplicationTestBuilder.registerAccount(email: String): AuthTokensResponse {
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AuthRequest(email, "valid-password")))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.registerDevice(accessToken: String): DeviceRegistrationResponse {
        val response = client.post("/devices/register") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    DeviceRegistrationRequest(
                        "device-${java.util.UUID.randomUUID()}",
                        "Media device",
                        "desktop",
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.putObject(
        token: String,
        path: String,
        bytes: ByteArray,
        digest: String,
    ) = client.put(path) {
        bearerAuth(token)
        contentType(ContentType.parse(OBJECT_CONTENT_TYPE))
        header(DIGEST_HEADER, digest)
        setBody(bytes)
    }

    private fun clearServerTables() {
        runCatching {
            DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        TRUNCATE TABLE someday_media_v3_objects, someday_entity_workspaces,
                            someday_sync_v2_mutations, someday_sync_v2_changes,
                            someday_sync_v2_objects,
                            someday_sync_v2_checkpoint_chunks, someday_sync_v2_checkpoint_manifests,
                            someday_sync_v2_epochs, workspace_pairing_invites,
                            someday_refresh_tokens, someday_sessions, someday_devices, someday_users CASCADE
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    private fun mediaPath(workspace: String, mediaId: String) =
        "/sync/v3/workspaces/$workspace/media/$mediaId"

    private fun sha256(bytes: ByteArray): String =
        "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"

    private data class UploadAttempt(val path: String, val bytes: ByteArray)

    private companion object {
        const val WORKSPACE_A = "workspace-0123456789abcdef0123456789abcdef"
        const val WORKSPACE_B = "workspace-fedcba9876543210fedcba9876543210"
        val MEDIA_ID = "0123456789abcdef".repeat(4)
        val MEDIA_ID_B = "fedcba9876543210".repeat(4)
        const val OBJECT_CONTENT_TYPE = "application/vnd.someday.media-object.v1"
        const val DIGEST_HEADER = "X-Someday-Media-Ciphertext-Sha256"
        const val BYTES_HEADER = "X-Someday-Media-Ciphertext-Bytes"
    }
}
