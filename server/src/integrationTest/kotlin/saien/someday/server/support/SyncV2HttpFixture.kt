package saien.someday.server.support

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import saien.someday.server.api.AuthRequest
import saien.someday.server.api.AuthTokensResponse
import saien.someday.server.api.DeviceRegistrationRequest
import saien.someday.server.api.DeviceRegistrationResponse
import saien.someday.server.api.SyncV2CheckpointChunkRef
import saien.someday.server.api.SyncV2CheckpointChunkRequest
import saien.someday.server.api.SyncV2CheckpointManifestRequest
import saien.someday.server.api.SyncV2EpochCompareAndSetRequest
import saien.someday.server.api.SyncV2EpochCompareAndSetResponse
import saien.someday.server.api.SyncV2EpochMetadata
import saien.someday.server.api.SyncV2ImmutablePutResponse
import saien.someday.server.productionTestDatabaseConnectionUrl
import saien.someday.sync.causality.v2.PreparedWorkspaceEpochCheckpointV2

internal suspend fun ApplicationTestBuilder.publishEpoch(
    accessToken: String,
    value: PreparedWorkspaceEpochCheckpointV2,
    workspaceId: String = WORKSPACE_ID,
) {
    uploadCheckpointObjects(accessToken, value, workspaceId)
    val cas = compareAndSetEpoch(accessToken, value, workspaceId)
    assertTrue(SYNC_V2_HTTP_JSON.decodeFromString<SyncV2EpochCompareAndSetResponse>(cas.body).published, cas.body)
}

internal suspend fun ApplicationTestBuilder.uploadCheckpointObjects(
    accessToken: String,
    value: PreparedWorkspaceEpochCheckpointV2,
    workspaceId: String = WORKSPACE_ID,
) {
    value.chunks.forEach { chunk ->
        val response = postJson(
            "/sync/v3/workspaces/$workspaceId/entities/checkpoint/chunk",
            accessToken,
            SyncV2CheckpointChunkRequest(
                value.descriptor.syncEpochId,
                value.descriptor.checkpointId,
                chunk.ref.let {
                    SyncV2CheckpointChunkRef(it.chunkIndex, it.chunkId, it.chunkDigest, it.objectCount, it.plaintextBytes)
                },
                chunk.encryptedObject.toServer(),
            ),
        )
        assertTrue(SYNC_V2_HTTP_JSON.decodeFromString<SyncV2ImmutablePutResponse>(response.body).stored, response.body)
    }
    val manifest = postJson(
        "/sync/v3/workspaces/$workspaceId/entities/checkpoint/manifest",
        accessToken,
        SyncV2CheckpointManifestRequest(
            value.descriptor.syncEpochId,
            value.descriptor.checkpointId,
            value.descriptor.checkpointDigest,
            value.chunks.map { chunk ->
                chunk.ref.let {
                    SyncV2CheckpointChunkRef(it.chunkIndex, it.chunkId, it.chunkDigest, it.objectCount, it.plaintextBytes)
                }
            },
            value.manifest.totalObjectCount,
            value.manifestObject.toServer(),
        ),
    )
    assertTrue(SYNC_V2_HTTP_JSON.decodeFromString<SyncV2ImmutablePutResponse>(manifest.body).stored, manifest.body)
}

internal suspend fun ApplicationTestBuilder.compareAndSetEpoch(
    accessToken: String,
    value: PreparedWorkspaceEpochCheckpointV2,
    workspaceId: String = WORKSPACE_ID,
): HttpResult {
    val descriptor = value.descriptor
    return postJson(
        "/sync/v3/workspaces/$workspaceId/entities/epoch/compare-and-set",
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
}

internal suspend fun ApplicationTestBuilder.registerAccountAndDevice(): DeviceRegistrationResponse {
    val registration = client.post("/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(
            SYNC_V2_HTTP_JSON.encodeToString(
                AuthRequest("sync-v2-${System.nanoTime()}@example.com", "valid-password"),
            ),
        )
    }
    return registerDevice(
        SYNC_V2_HTTP_JSON.decodeFromString<AuthTokensResponse>(registration.bodyAsText()).accessToken,
        "Primary V2 device",
        "android",
    )
}

internal suspend fun ApplicationTestBuilder.registerDevice(
    accessToken: String,
    name: String,
    platform: String,
): DeviceRegistrationResponse {
    val response = client.post("/devices/register") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(
            SYNC_V2_HTTP_JSON.encodeToString(
                DeviceRegistrationRequest(java.util.UUID.randomUUID().toString(), name, platform),
            ),
        )
    }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return SYNC_V2_HTTP_JSON.decodeFromString(response.bodyAsText())
}

internal suspend inline fun <reified T> ApplicationTestBuilder.postJson(
    path: String,
    accessToken: String,
    body: T,
): HttpResult {
    val response = client.post(path) {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(SYNC_V2_HTTP_JSON.encodeToString(body))
    }
    return HttpResult(response.status, response.bodyAsText())
}

internal suspend fun ApplicationTestBuilder.postRawJson(
    path: String,
    accessToken: String,
    body: String,
): HttpResult {
    val response = client.post(path) {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    return HttpResult(response.status, response.bodyAsText())
}

internal fun clearServerTables() {
    val databaseUrl = System.getenv("SOMEDAY_DB_URL") ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    val databaseUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    val databasePassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"
    runCatching {
        DriverManager.getConnection(
            productionTestDatabaseConnectionUrl(databaseUrl),
            databaseUser,
            databasePassword,
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    TRUNCATE TABLE
                        someday_sync_v2_mutations, someday_sync_v2_changes,
                        someday_sync_v2_objects,
                        someday_sync_v2_checkpoint_chunks, someday_sync_v2_checkpoint_manifests,
                        someday_sync_v2_epochs,
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

internal data class HttpResult(val status: HttpStatusCode, val body: String)
