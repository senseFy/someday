@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.server

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.server.api.AuthRequest
import saien.someday.server.api.AuthTokensResponse
import saien.someday.server.api.DeviceRegistrationRequest
import saien.someday.server.api.DeviceRegistrationResponse
import saien.someday.server.api.RefreshRequest
import saien.someday.server.api.SyncV2CheckpointChunkRef
import saien.someday.server.api.SyncV2CheckpointChunkRequest
import saien.someday.server.api.SyncV2CheckpointManifestRequest
import saien.someday.server.api.SyncV2EpochCompareAndSetRequest
import saien.someday.server.api.SyncV2EpochCompareAndSetResponse
import saien.someday.server.api.SyncV2EpochMetadata
import saien.someday.server.api.SyncV2ImmutablePutResponse
import saien.someday.server.api.SyncV2ObjectPayload
import saien.someday.server.api.SyncV2PushRequest
import saien.someday.server.api.SyncV2PushResponse
import saien.someday.sync.causality.v2.CanonicalWorkspaceCausalityMaterializerV2
import saien.someday.sync.causality.v2.NotebookContentV2
import saien.someday.sync.causality.v2.PreparedWorkspaceEpochCheckpointV2
import saien.someday.sync.causality.v2.SyncEpochKeyDerivationV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointBuilderV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointSourceHeadV2
import saien.someday.sync.causality.v2.WorkspaceEntityTypeV2
import saien.someday.sync.causality.v2.WorkspaceEntityValidatorV2
import saien.someday.sync.causality.v2.WorkspaceEntityVersionFactoryV2
import saien.someday.sync.causality.v2.WorkspaceEntityWireCodecV2
import saien.someday.sync.causality.v2.WorkspaceObjectCipherV2
import saien.someday.sync.causality.v2.WorkspacePreferencesV2
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AdminDashboardIntegrationTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = true }
    private val dbUrl = System.getenv("SOMEDAY_DB_URL") ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    private val dbUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    private val dbPassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"
    private val dbConnectionUrl = productionTestDatabaseConnectionUrl(dbUrl)

    @Before
    fun setUp() {
        clearServerTables()
    }

    @After
    fun tearDown() {
        clearServerTables()
    }

    @Test
    fun adminRoutesRejectAnonymousAndNonAdminUsersAndAllowAdmins() = testApplication {
        application { somedayServerModule() }
        clearServerTables()

        val nonAdmin = register("user-gate-${System.nanoTime()}@example.com", "valid-password")
        val admin = register("admin-gate-${System.nanoTime()}@example.com", "valid-password")
        promoteToAdmin(admin.user.email)

        val anonymous = client.get("/admin")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status, anonymous.bodyAsText())

        val forbidden = client.get("/admin") {
            bearerAuth(nonAdmin.accessToken)
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status, forbidden.bodyAsText())

        listOf(
            "/admin",
            "/admin/users",
            "/admin/devices",
            "/admin/storage",
            "/admin/activity",
            "/admin/health",
        ).forEach { path ->
            val response = client.get(path) {
                bearerAuth(admin.accessToken)
            }
            assertEquals(HttpStatusCode.OK, response.status, "$path failed: ${response.bodyAsText()}")
            assertTrue(response.bodyAsText().contains("Someday Admin"), "$path should render the admin shell.")
        }
    }

    @Test
    fun productionAdminBrowserRequiresOriginAndUsesSecureCookieAndHeaders() = testApplication {
        val config = productionTestServerConfig(dbUrl, dbUser, dbPassword)
        val context = ServerContext.create(config)
        val email = "admin-browser-${System.nanoTime()}@example.com"
        val password = "valid-admin-password"
        assertNotNull(
            context.repository.createAdminUser(
                email = email,
                passwordHash = context.credentialHasher.hash(password),
            ),
        )
        application { somedayServerModule(context) }

        val loginPage = client.get("/admin/login")
        assertEquals(HttpStatusCode.OK, loginPage.status, loginPage.bodyAsText())
        assertEquals("no-store", loginPage.headers[HttpHeaders.CacheControl])
        assertEquals("DENY", loginPage.headers["X-Frame-Options"])
        assertEquals("same-origin", loginPage.headers["Referrer-Policy"])
        assertTrue(loginPage.headers["Content-Security-Policy"].orEmpty().contains("frame-ancestors 'none'"))

        val browser = createClient {
            followRedirects = false
        }
        val missingOrigin = browser.post("/admin/login") {
            setBody(adminLoginForm(email, password))
        }
        assertEquals(HttpStatusCode.Forbidden, missingOrigin.status, missingOrigin.bodyAsText())
        assertTrue(missingOrigin.bodyAsText().contains("invalid_origin"))

        val spoofedBearer = browser.post("/admin/login") {
            headers.append(HttpHeaders.Authorization, "Bearer invalid")
            setBody(adminLoginForm(email, password))
        }
        assertEquals(HttpStatusCode.Forbidden, spoofedBearer.status, spoofedBearer.bodyAsText())
        assertTrue(spoofedBearer.bodyAsText().contains("invalid_origin"))

        val success = browser.post("/admin/login") {
            headers.append(HttpHeaders.Origin, config.publicOrigin)
            setBody(adminLoginForm(email, password))
        }
        assertEquals(HttpStatusCode.Found, success.status, success.bodyAsText())
        val cookie = success.headers.getAll(HttpHeaders.SetCookie).orEmpty().joinToString(";")
        assertTrue(cookie.contains("someday_admin_access="), cookie)
        assertTrue(cookie.contains("HttpOnly"), cookie)
        assertTrue(cookie.contains("SameSite=Strict"), cookie)
        assertTrue(cookie.contains("Secure"), cookie)
    }

    @Test
    fun adminPagesShowOperationalStateWithoutSecretsOrPlaintextNotes() = testApplication {
        application { somedayServerModule() }
        clearServerTables()

        val adminPassword = "Admin-Secret-${System.nanoTime()}"
        val userPassword = "User-Secret-${System.nanoTime()}"
        val admin = register("admin-redaction-${System.nanoTime()}@example.com", adminPassword)
        promoteToAdmin(admin.user.email)
        val user = register("user-redaction-${System.nanoTime()}@example.com", userPassword)
        val device = registerDevice(user.accessToken, "Redaction Phone", "android")
        val plaintextSentinel = "SENTINEL_ADMIN_PLAINTEXT_NOTE_${System.nanoTime()}"

        val prepared = checkpoint(device.device.id)
        publishEpoch(device.accessToken, prepared)
        val entity = entityObject(prepared, device.device.id, NOTEBOOK_ID, plaintextSentinel)
        val push = pushV2(device.accessToken, prepared.descriptor.syncEpochId, entity)
        assertTrue(json.decodeFromString<SyncV2PushResponse>(push.body).accepted, push.body)

        val pages = buildString {
            listOf(
                "/admin",
                "/admin/users",
                "/admin/users/${user.user.id}",
                "/admin/devices",
                "/admin/storage",
                "/admin/activity",
                "/admin/health",
            ).forEach { path ->
                val response = client.get(path) {
                    bearerAuth(admin.accessToken)
                }
                assertEquals(HttpStatusCode.OK, response.status, "$path failed: ${response.bodyAsText()}")
                appendLine(response.bodyAsText())
            }
        }

        assertTrue(pages.contains(user.user.email), "Users page should show account identity.")
        assertTrue(pages.contains("Redaction Phone"), "Devices page should show device identity.")
        assertTrue(
            pages.contains("workspace_entity_version_v2"),
            "Storage pages should show V2 object type summaries.",
        )
        assertNoSensitiveAdminContent(
            pages,
            listOf(
                adminPassword,
                userPassword,
                admin.refreshToken,
                user.refreshToken,
                activePasswordHashes().joinToString("\n"),
                activeRefreshTokenHashes().joinToString("\n"),
                entity.ciphertextBase64,
                plaintextSentinel,
                "someday-local-development-secret-change-before-production",
                "password_hash",
                "token_hash",
                "encrypted_object_json",
                "encrypted_payload",
                "refreshToken",
                "accessToken",
            ),
        )
    }

    @Test
    fun adminCanDisableUserRevokeSessionsAndPreserveEncryptedStorage() = testApplication {
        application { somedayServerModule() }
        clearServerTables()

        val admin = register("admin-disable-${System.nanoTime()}@example.com", "valid-password")
        promoteToAdmin(admin.user.email)
        val user = register("user-disable-${System.nanoTime()}@example.com", "valid-password")
        val device = registerDevice(user.accessToken, "Disable Phone", "android")
        val userId = UUID.fromString(user.user.id)

        val prepared = checkpoint(device.device.id)
        publishEpoch(device.accessToken, prepared)
        val entity = entityObject(prepared, device.device.id, NOTEBOOK_ID, "Disable preserve")
        val push = pushV2(device.accessToken, prepared.descriptor.syncEpochId, entity)
        assertTrue(json.decodeFromString<SyncV2PushResponse>(push.body).accepted, push.body)
        assertEquals(1, syncV2ObjectCount())

        val disable = client.post("/admin/users/${user.user.id}/disable") {
            bearerAuth(admin.accessToken)
            headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        assertEquals(HttpStatusCode.OK, disable.status, disable.bodyAsText())
        assertNotNull(disabledAt(userId), "Admin disable action must set disabled_at.")
        assertEquals(1, syncV2ObjectCount(), "Disabling a user must preserve encrypted stored objects.")

        val loginAfterDisable = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AuthRequest(user.user.email, "valid-password")))
        }
        assertEquals(HttpStatusCode.Unauthorized, loginAfterDisable.status, loginAfterDisable.bodyAsText())

        val refreshAfterDisable = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RefreshRequest(user.refreshToken)))
        }
        assertEquals(HttpStatusCode.Unauthorized, refreshAfterDisable.status, refreshAfterDisable.bodyAsText())

        val syncAfterDisable = client.get("/sync/v3/capabilities") {
            bearerAuth(device.accessToken)
        }
        assertEquals(HttpStatusCode.Unauthorized, syncAfterDisable.status, syncAfterDisable.bodyAsText())
    }

    @Test
    fun adminDevicesPageListsStatusAndRevocationBlocksSync() = testApplication {
        application { somedayServerModule() }
        clearServerTables()

        val admin = register("admin-device-${System.nanoTime()}@example.com", "valid-password")
        promoteToAdmin(admin.user.email)
        val user = register("user-device-${System.nanoTime()}@example.com", "valid-password")
        val device = registerDevice(user.accessToken, "Revocation Laptop", "desktop")

        val before = client.get("/admin/devices") {
            bearerAuth(admin.accessToken)
        }
        assertEquals(HttpStatusCode.OK, before.status, before.bodyAsText())
        assertTrue(before.bodyAsText().contains("Revocation Laptop"))
        assertTrue(before.bodyAsText().contains("active"))

        val revoke = client.post("/admin/devices/${device.device.id}/revoke") {
            bearerAuth(admin.accessToken)
            headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        assertEquals(HttpStatusCode.OK, revoke.status, revoke.bodyAsText())

        val after = client.get("/admin/devices") {
            bearerAuth(admin.accessToken)
        }
        assertEquals(HttpStatusCode.OK, after.status, after.bodyAsText())
        assertTrue(after.bodyAsText().contains("revoked"), after.bodyAsText())

        val deniedSync = client.get("/sync/v3/capabilities") {
            bearerAuth(device.accessToken)
        }
        assertTrue(
            deniedSync.status == HttpStatusCode.Unauthorized || deniedSync.status == HttpStatusCode.Forbidden,
            "Revoked device sync must be denied, got ${deniedSync.status}: ${deniedSync.bodyAsText()}",
        )
    }

    @Test
    fun adminStorageActivityAndHealthShowV2StorageAndDatabaseState() = testApplication {
        application { somedayServerModule() }
        clearServerTables()

        val admin = register("admin-health-${System.nanoTime()}@example.com", "valid-password")
        promoteToAdmin(admin.user.email)
        val user = register("user-health-${System.nanoTime()}@example.com", "valid-password")
        val device = registerDevice(user.accessToken, "First Sync Device", "android")

        val prepared = checkpoint(device.device.id)
        publishEpoch(device.accessToken, prepared)
        val entity = entityObject(prepared, device.device.id, NOTEBOOK_ID, "Health notebook")
        val push = pushV2(device.accessToken, prepared.descriptor.syncEpochId, entity)
        assertTrue(json.decodeFromString<SyncV2PushResponse>(push.body).accepted, push.body)

        val storage = client.get("/admin/storage") {
            bearerAuth(admin.accessToken)
        }
        assertEquals(HttpStatusCode.OK, storage.status, storage.bodyAsText())
        assertTrue(storage.bodyAsText().contains("Encrypted objects"))
        assertTrue(storage.bodyAsText().contains("workspace_entity_version_v2"))

        val activity = client.get("/admin/activity") {
            bearerAuth(admin.accessToken)
        }
        assertEquals(HttpStatusCode.OK, activity.status, activity.bodyAsText())
        assertTrue(activity.bodyAsText().contains("Accepted changes"), activity.bodyAsText())
        assertTrue(activity.bodyAsText().contains("Sync activity"), activity.bodyAsText())

        val health = client.get("/admin/health") {
            bearerAuth(admin.accessToken)
        }
        assertEquals(HttpStatusCode.OK, health.status, health.bodyAsText())
        assertTrue(health.bodyAsText().contains("Database"))
        assertTrue(health.bodyAsText().contains("ok"))
        assertTrue(health.bodyAsText().contains("Migration"))
        assertTrue(health.bodyAsText().contains("Uptime"))

        assertNoSensitiveAdminContent(
            storage.bodyAsText() + activity.bodyAsText() + health.bodyAsText(),
            listOf(entity.ciphertextBase64, "encrypted_object_json", "encrypted_payload"),
        )
    }

    private suspend fun ApplicationTestBuilder.register(email: String, password: String): AuthTokensResponse {
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AuthRequest(email, password)))
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
            setBody(json.encodeToString(DeviceRegistrationRequest(UUID.randomUUID().toString(), name, platform)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private fun checkpoint(writerDeviceId: String): PreparedWorkspaceEpochCheckpointV2 =
        WorkspaceCheckpointBuilderV2(WORKSPACE_KEY, writerDeviceId).build(
            remoteProfile = "self-hosted-v2",
            sourceHeads = listOf(
                WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    "workspace-preferences",
                    WorkspacePreferencesV2(),
                    null,
                    "integration-test",
                    null,
                    writerDeviceId,
                    null,
                    "source-genesis",
                    "source-digest-genesis",
                ),
            ),
            createdAt = NOW,
            previousPointerDigest = null,
            previousEpochId = null,
            previousEpochPointerDigest = null,
        )

    private suspend fun ApplicationTestBuilder.publishEpoch(
        accessToken: String,
        value: PreparedWorkspaceEpochCheckpointV2,
    ) {
        value.chunks.forEach { chunk ->
            val response = postJson(
                "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/chunk",
                accessToken,
                SyncV2CheckpointChunkRequest(
                    value.descriptor.syncEpochId,
                    value.descriptor.checkpointId,
                    chunk.ref.let {
                        SyncV2CheckpointChunkRef(
                            it.chunkIndex,
                            it.chunkId,
                            it.chunkDigest,
                            it.objectCount,
                            it.plaintextBytes,
                        )
                    },
                    chunk.encryptedObject.toServer(),
                ),
            )
            assertTrue(json.decodeFromString<SyncV2ImmutablePutResponse>(response.body).stored, response.body)
        }
        val manifest = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/manifest",
            accessToken,
            SyncV2CheckpointManifestRequest(
                value.descriptor.syncEpochId,
                value.descriptor.checkpointId,
                value.descriptor.checkpointDigest,
                value.chunks.map { chunk ->
                    chunk.ref.let {
                        SyncV2CheckpointChunkRef(
                            it.chunkIndex,
                            it.chunkId,
                            it.chunkDigest,
                            it.objectCount,
                            it.plaintextBytes,
                        )
                    }
                },
                value.manifest.totalObjectCount,
                value.manifestObject.toServer(),
            ),
        )
        assertTrue(json.decodeFromString<SyncV2ImmutablePutResponse>(manifest.body).stored, manifest.body)
        val descriptor = value.descriptor
        val cas = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/epoch/compare-and-set",
            accessToken,
            SyncV2EpochCompareAndSetRequest(
                value.pointer.previousPointerDigest,
                SyncV2EpochMetadata(
                    epochId = descriptor.syncEpochId,
                    pointerDigest = value.pointerObject.objectDigest,
                    semanticProtocolVersion = descriptor.semanticProtocolVersion,
                    minimumWriterProtocolVersion = descriptor.minimumWriterProtocolVersion,
                    keySetVersion = descriptor.keySetVersion,
                    remoteProfile = descriptor.remoteProfile,
                    metadataPrivacyMode = descriptor.metadataPrivacyMode,
                    supportedOfflineWindowSeconds = descriptor.supportedOfflineWindowSeconds,
                    checkpointId = descriptor.checkpointId,
                    checkpointDigest = descriptor.checkpointDigest,
                    previousEpochId = descriptor.previousEpochId,
                    previousEpochPointerDigest = descriptor.previousEpochPointerDigest,
                ),
                value.pointerObject.toServer(),
            ),
        )
        assertTrue(json.decodeFromString<SyncV2EpochCompareAndSetResponse>(cas.body).published, cas.body)
    }

    private suspend fun ApplicationTestBuilder.pushV2(
        accessToken: String,
        epochId: String,
        entity: SyncV2ObjectPayload,
    ): HttpResult {
        return postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/push",
            accessToken,
            SyncV2PushRequest(epochId = epochId, writerProtocolVersion = 2, objects = listOf(entity)),
        )
    }

    private fun entityObject(
        checkpoint: PreparedWorkspaceEpochCheckpointV2,
        writerDeviceId: String,
        entityId: String,
        title: String,
    ): SyncV2ObjectPayload {
        val epochId = checkpoint.descriptor.syncEpochId
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(WORKSPACE_KEY, epochId),
        )
        val validator = WorkspaceEntityValidatorV2(materializer)
        val wire = WorkspaceEntityWireCodecV2(materializer, validator)
        val factory = WorkspaceEntityVersionFactoryV2(epochId, materializer)
        val version = factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            entityId,
            NotebookContentV2(title, 1, NOW),
            "device:$writerDeviceId",
            NOW,
        )
        return WorkspaceObjectCipherV2(WORKSPACE_KEY, materializer)
            .encryptEntity(version, factory.newMutationId(), writerDeviceId, wire.encode(version))
            .toServer()
    }

    private fun saien.someday.sync.causality.v2.EncryptedWorkspaceObjectV2.toServer(): SyncV2ObjectPayload =
        json.decodeFromString(json.encodeToString(this))

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

    private fun clearServerTables() {
        runCatching {
            DriverManager.getConnection(dbConnectionUrl, dbUser, dbPassword).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        TRUNCATE TABLE
                            someday_sync_v2_mutations,
                            someday_sync_v2_changes,
                            someday_sync_v2_objects,
                            someday_sync_v2_checkpoint_chunks,
                            someday_sync_v2_checkpoint_manifests,
                            someday_sync_v2_epochs,
                            workspace_pairing_invites,
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

    private fun promoteToAdmin(email: String) {
        DriverManager.getConnection(dbConnectionUrl, dbUser, dbPassword).use { connection ->
            connection.prepareStatement("UPDATE someday_users SET is_admin = TRUE WHERE email = ?").use { statement ->
                statement.setString(1, email)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun disabledAt(userId: UUID): String? =
        DriverManager.getConnection(dbConnectionUrl, dbUser, dbPassword).use { connection ->
            connection.prepareStatement("SELECT disabled_at::TEXT FROM someday_users WHERE id = ?").use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getString(1)
                }
            }
        }

    private fun syncV2ObjectCount(): Int =
        DriverManager.getConnection(dbConnectionUrl, dbUser, dbPassword).use { connection ->
            connection.prepareStatement(
                "SELECT set_config('someday.user_id', '*', false), " +
                    "set_config('someday.workspace_id', '*', false)",
            ).use { statement -> statement.executeQuery().close() }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM someday_sync_v2_objects").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private fun activePasswordHashes(): List<String> =
        DriverManager.getConnection(dbConnectionUrl, dbUser, dbPassword).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT password_hash FROM someday_users ORDER BY email").use { result ->
                    buildList {
                        while (result.next()) add(result.getString(1))
                    }
                }
            }
        }

    private fun activeRefreshTokenHashes(): List<String> =
        DriverManager.getConnection(dbConnectionUrl, dbUser, dbPassword).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT token_hash FROM someday_refresh_tokens ORDER BY created_at").use { result ->
                    buildList {
                        while (result.next()) add(result.getString(1))
                    }
                }
            }
        }

    private fun assertNoSensitiveAdminContent(body: String, forbiddenSubstrings: List<String>) {
        forbiddenSubstrings
            .filter { it.isNotBlank() }
            .forEach { forbidden ->
                assertFalse(
                    body.contains(forbidden, ignoreCase = true),
                    "Admin page leaked forbidden substring '$forbidden': $body",
                )
            }
    }

    private fun adminLoginForm(email: String, password: String): FormDataContent =
        FormDataContent(
            Parameters.build {
                append("email", email)
                append("password", password)
            },
        )

    private data class HttpResult(val status: HttpStatusCode, val body: String)

    private companion object {
        const val WORKSPACE_ID = "workspace-00000000000000000000000000000001"
        const val NOTEBOOK_ID = "00000000-0000-4000-8000-000000000111"
        val NOW = Instant.parse("2026-05-22T00:00:00Z")
        val WORKSPACE_KEY = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 17).toByte() })
    }
}
