@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.server

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.server.api.AuthRequest
import saien.someday.server.api.AuthTokensResponse
import saien.someday.server.api.DeviceRegistrationRequest
import saien.someday.server.api.DeviceRegistrationResponse
import saien.someday.server.api.SyncV2CheckpointChunkRef
import saien.someday.server.api.SyncV2CheckpointChunkRequest
import saien.someday.server.api.SyncV2CheckpointCleanupRequest
import saien.someday.server.api.SyncV2CheckpointCleanupResponse
import saien.someday.server.api.SyncV2CheckpointFetchRequest
import saien.someday.server.api.SyncV2CheckpointFetchResponse
import saien.someday.server.api.SyncV2CheckpointManifestRequest
import saien.someday.server.api.SyncV2EpochCompareAndSetRequest
import saien.someday.server.api.SyncV2EpochCompareAndSetResponse
import saien.someday.server.api.SyncV2EpochHistoryRequest
import saien.someday.server.api.SyncV2EpochResponse
import saien.someday.server.api.SyncV2EpochMetadata
import saien.someday.server.api.SyncV2FrontierRequest
import saien.someday.server.api.SyncV2FrontierResponse
import saien.someday.server.api.SyncV2ImmutablePutResponse
import saien.someday.server.api.SyncV2ObjectPayload
import saien.someday.server.api.SyncV2PullRequest
import saien.someday.server.api.SyncV2PullResponse
import saien.someday.server.api.SyncV2PushRequest
import saien.someday.server.api.SyncV2PushResponse
import saien.someday.server.api.SyncV2RepairObjectRequest
import saien.someday.server.api.SyncV2RepairObjectResponse
import saien.someday.server.api.SyncV2RepairReplicaRequest
import saien.someday.sync.causality.v2.CanonicalWorkspaceCausalityMaterializerV2
import saien.someday.sync.causality.v2.NotebookContentV2
import saien.someday.sync.causality.v2.PreparedWorkspaceEpochCheckpointV2
import saien.someday.sync.causality.v2.SyncEpochKeyDerivationV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointBuilderV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointSourceHeadV2
import saien.someday.sync.causality.v2.WorkspaceEntityTypeV2
import saien.someday.sync.causality.v2.WorkspaceEntityVersionFactoryV2
import saien.someday.sync.causality.v2.WorkspaceObjectCipherV2
import saien.someday.sync.causality.v2.WorkspacePreferencesV2
import saien.someday.sync.causality.v2.WorkspaceSyncControlCodecV2
import saien.someday.sync.causality.v2.WorkspaceEntityValidatorV2
import saien.someday.sync.causality.v2.WorkspaceEntityWireCodecV2
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
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SyncV2ApiIntegrationTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private val dbUrl = System.getenv("SOMEDAY_DB_URL") ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    private val dbUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    private val dbPassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"

    @BeforeTest fun setUp() = clearServerTables()
    @AfterTest fun tearDown() = clearServerTables()

    @Test
    fun checkpointPushReplayPullRepairRolloverAndEpochGuardShareExactOpaqueContract() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val first = registerAccountAndDevice()
        val second = registerDevice(first.accessToken, "Second V2 device", "ios")
        val old = checkpoint(first.device.id, previous = null)
        publishEpoch(first.accessToken, old)

        val firstObject = entityObject(old, first.device.id, NOTEBOOK_ID, "First")
        val firstPush = postJson(
            "/sync/v2/push",
            first.accessToken,
            SyncV2PushRequest(old.descriptor.syncEpochId, 2, listOf(firstObject)),
        )
        assertEquals(HttpStatusCode.OK, firstPush.status, firstPush.body)
        assertFalse(json.decodeFromString<SyncV2PushResponse>(firstPush.body).acknowledgements.single().idempotentReplay)

        val replay = postJson(
            "/sync/v2/push",
            first.accessToken,
            SyncV2PushRequest(old.descriptor.syncEpochId, 2, listOf(firstObject)),
        )
        assertTrue(json.decodeFromString<SyncV2PushResponse>(replay.body).acknowledgements.single().idempotentReplay)

        val collision = firstObject.copy(
            objectId = OTHER_OBJECT_ID,
            objectDigest = "od2:hmac-sha256:${"1".repeat(64)}",
            writerDeviceId = second.device.id,
        )
        val rejectedCollision = postJson(
            "/sync/v2/push",
            second.accessToken,
            SyncV2PushRequest(old.descriptor.syncEpochId, 2, listOf(collision)),
        )
        assertEquals(HttpStatusCode.Conflict, rejectedCollision.status, rejectedCollision.body)
        assertEquals("mutation_reuse_mismatch", json.decodeFromString<SyncV2PushResponse>(rejectedCollision.body).error)

        val pull = postJson(
            "/sync/v2/pull",
            second.accessToken,
            SyncV2PullRequest(old.descriptor.syncEpochId),
        )
        assertEquals(listOf(firstObject), json.decodeFromString<SyncV2PullResponse>(pull.body).units.flatMap { it.objects })

        val repairFetch = postJson(
            "/sync/v2/repair/object",
            second.accessToken,
            SyncV2RepairObjectRequest(old.descriptor.syncEpochId, firstObject.objectId, firstObject.objectDigest),
        )
        assertEquals(1, json.decodeFromString<SyncV2RepairObjectResponse>(repairFetch.body).replicas.size)
        val repairReplica = reencrypt(firstObject, second.device.id, old)
        val repairPublish = postJson(
            "/sync/v2/repair/replica",
            second.accessToken,
            SyncV2RepairReplicaRequest(repairReplica),
        )
        assertTrue(json.decodeFromString<SyncV2ImmutablePutResponse>(repairPublish.body).stored)
        val replicas = postJson(
            "/sync/v2/repair/object",
            second.accessToken,
            SyncV2RepairObjectRequest(old.descriptor.syncEpochId, firstObject.objectId, firstObject.objectDigest),
        )
        assertEquals(2, json.decodeFromString<SyncV2RepairObjectResponse>(replicas.body).replicas.size)

        val newer = checkpoint(first.device.id, old)
        publishEpoch(first.accessToken, newer)
        val retainedPointer = postJson(
            "/sync/v2/epoch/history",
            second.accessToken,
            SyncV2EpochHistoryRequest(old.descriptor.syncEpochId),
        )
        assertEquals(
            old.pointerObject.toServer(),
            json.decodeFromString<SyncV2EpochResponse>(retainedPointer.body).pointer,
        )
        val oldPush = postJson(
            "/sync/v2/push",
            first.accessToken,
            SyncV2PushRequest(old.descriptor.syncEpochId, 2, listOf(
                entityObject(old, first.device.id, LATE_OBJECT_ID, "Late"),
            )),
        )
        assertEquals(HttpStatusCode.Conflict, oldPush.status, oldPush.body)
        assertEquals("incompatible_epoch", json.decodeFromString<SyncV2PushResponse>(oldPush.body).error)

        val oldFrontier = postJson(
            "/sync/v2/frontiers",
            second.accessToken,
            SyncV2FrontierRequest(old.descriptor.syncEpochId),
        )
        val frontier = json.decodeFromString<SyncV2FrontierResponse>(oldFrontier.body).frontiers.single()
        assertEquals("global", frontier.streamId)
        assertTrue(frontier.cursorValue?.toLongOrNull()?.let { it > 0 } == true)
    }

    @Test
    fun checkpointFetchIsObjectPagedAndEveryV2RequestUsesStrictBoundedJson() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccountAndDevice()
        val prepared = checkpoint(account.device.id, previous = null)
        publishEpoch(account.accessToken, prepared)

        val manifestResult = postJson(
            "/sync/v2/checkpoint/fetch",
            account.accessToken,
            SyncV2CheckpointFetchRequest(prepared.descriptor.syncEpochId, prepared.descriptor.checkpointId),
        )
        assertEquals(HttpStatusCode.OK, manifestResult.status, manifestResult.body)
        val manifestPage = json.decodeFromString<SyncV2CheckpointFetchResponse>(manifestResult.body)
        assertNotNull(manifestPage.manifest)
        assertNull(manifestPage.chunk)

        val expectedChunk = prepared.chunks.single()
        val chunkResult = postJson(
            "/sync/v2/checkpoint/fetch",
            account.accessToken,
            SyncV2CheckpointFetchRequest(
                prepared.descriptor.syncEpochId,
                prepared.descriptor.checkpointId,
                expectedChunk.ref.chunkIndex,
            ),
        )
        assertEquals(HttpStatusCode.OK, chunkResult.status, chunkResult.body)
        val chunkPage = json.decodeFromString<SyncV2CheckpointFetchResponse>(chunkResult.body)
        assertNull(chunkPage.manifest)
        assertEquals(expectedChunk.encryptedObject.toServer(), chunkPage.chunk)

        val unknownField = postRawJson(
            "/sync/v2/pull",
            account.accessToken,
            """{"epochId":"${prepared.descriptor.syncEpochId}","limit":1,"unexpected":true}""",
        )
        assertEquals(HttpStatusCode.BadRequest, unknownField.status, unknownField.body)

        val escapedDuplicate = postRawJson(
            "/sync/v2/pull",
            account.accessToken,
            """{"epochId":"${prepared.descriptor.syncEpochId}","\u0065pochId":"${prepared.descriptor.syncEpochId}","limit":1}""",
        )
        assertEquals(HttpStatusCode.BadRequest, escapedDuplicate.status, escapedDuplicate.body)

        val oversized = postRawJson(
            "/sync/v2/pull",
            account.accessToken,
            """{"padding":"${"x".repeat(16 * 1024 * 1024)}"}""",
        )
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status, oversized.body)
    }

    @Test
    fun checkpointCleanupRetainsReferencedEpochAndExactlyDeletesObsoleteDraft() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccountAndDevice()
        val obsolete = checkpoint(account.device.id, previous = null)
        uploadCheckpointObjects(account.accessToken, obsolete)
        val winner = checkpoint(account.device.id, previous = null)
        publishEpoch(account.accessToken, winner)

        val referenced = postJson(
            "/sync/v2/checkpoint/cleanup",
            account.accessToken,
            winner.toCleanupRequest(),
        )
        assertEquals(HttpStatusCode.Conflict, referenced.status, referenced.body)
        assertEquals(
            "checkpoint_referenced",
            json.decodeFromString<SyncV2CheckpointCleanupResponse>(referenced.body).error,
        )

        val deleted = postJson(
            "/sync/v2/checkpoint/cleanup",
            account.accessToken,
            obsolete.toCleanupRequest(),
        )
        assertEquals(HttpStatusCode.OK, deleted.status, deleted.body)
        val deletedBody = json.decodeFromString<SyncV2CheckpointCleanupResponse>(deleted.body)
        assertTrue(deletedBody.deleted)
        assertFalse(deletedBody.alreadyAbsent)

        val missingManifest = postJson(
            "/sync/v2/checkpoint/fetch",
            account.accessToken,
            SyncV2CheckpointFetchRequest(
                obsolete.descriptor.syncEpochId,
                obsolete.descriptor.checkpointId,
            ),
        )
        assertEquals(HttpStatusCode.NotFound, missingManifest.status, missingManifest.body)
        val missingChunk = postJson(
            "/sync/v2/checkpoint/fetch",
            account.accessToken,
            SyncV2CheckpointFetchRequest(
                obsolete.descriptor.syncEpochId,
                obsolete.descriptor.checkpointId,
                obsolete.chunks.single().ref.chunkIndex,
            ),
        )
        assertEquals(HttpStatusCode.NotFound, missingChunk.status, missingChunk.body)

        val replay = postJson(
            "/sync/v2/checkpoint/cleanup",
            account.accessToken,
            obsolete.toCleanupRequest(),
        )
        assertEquals(HttpStatusCode.OK, replay.status, replay.body)
        assertTrue(json.decodeFromString<SyncV2CheckpointCleanupResponse>(replay.body).alreadyAbsent)
    }

    @Test
    fun authenticatedV2BudgetAllowsOneCompleteBoundedCoordinatorBurst() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccountAndDevice()
        val prepared = checkpoint(account.device.id, previous = null)
        publishEpoch(account.accessToken, prepared)

        // One coordinator pass can make two sets of eight paged pulls. Include
        // the checkpoint publication requests above in the same device budget.
        repeat(16) { requestIndex ->
            val response = postJson(
                "/sync/v2/frontiers",
                account.accessToken,
                SyncV2FrontierRequest(prepared.descriptor.syncEpochId),
            )
            assertEquals(HttpStatusCode.OK, response.status, "request=$requestIndex ${response.body}")
        }
    }

    @Test
    fun emptyTargetAcceptsAnExternalPreviousEpochAsMigrationProvenance() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccountAndDevice()
        val migrated = checkpoint(
            account.device.id,
            previous = null,
            previousEpochId = EXTERNAL_SOURCE_EPOCH_ID,
            previousEpochPointerDigest = EXTERNAL_SOURCE_POINTER_DIGEST,
        )

        uploadCheckpointObjects(account.accessToken, migrated)
        val cas = compareAndSetEpoch(account.accessToken, migrated)

        assertEquals(HttpStatusCode.OK, cas.status, cas.body)
        assertTrue(json.decodeFromString<SyncV2EpochCompareAndSetResponse>(cas.body).published, cas.body)
    }

    @Test
    fun missingCursorObjectFailsClosedAndRequiresRebootstrap() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val first = registerAccountAndDevice()
        val second = registerDevice(first.accessToken, "Missing-object follower", "ios")
        val prepared = checkpoint(first.device.id, previous = null)
        publishEpoch(first.accessToken, prepared)
        val objectValue = entityObject(prepared, first.device.id, NOTEBOOK_ID, "Must not be skipped")
        val pushed = postJson(
            "/sync/v2/push",
            first.accessToken,
            SyncV2PushRequest(prepared.descriptor.syncEpochId, 2, listOf(objectValue)),
        )
        assertEquals(HttpStatusCode.OK, pushed.status, pushed.body)
        val frontierBeforeFailure = postJson(
            "/sync/v2/frontiers",
            first.accessToken,
            SyncV2FrontierRequest(prepared.descriptor.syncEpochId),
        )
        assertEquals(HttpStatusCode.OK, frontierBeforeFailure.status, frontierBeforeFailure.body)
        val cursorBeforeFailure =
            json.decodeFromString<SyncV2FrontierResponse>(frontierBeforeFailure.body)
                .frontiers
                .single()
                .cursorValue

        deleteV2ObjectReplicas(prepared.descriptor.syncEpochId, objectValue.objectId)

        val pull = postJson(
            "/sync/v2/pull",
            second.accessToken,
            SyncV2PullRequest(prepared.descriptor.syncEpochId),
        )
        assertEquals(HttpStatusCode.OK, pull.status, pull.body)
        val response = json.decodeFromString<SyncV2PullResponse>(pull.body)
        assertEquals("missing_remote_object", response.error)
        assertTrue(response.rebootstrapRequired)
        assertFalse(response.complete)
        assertTrue(response.units.isEmpty())
        val frontier = postJson(
            "/sync/v2/frontiers",
            first.accessToken,
            SyncV2FrontierRequest(prepared.descriptor.syncEpochId),
        )
        assertEquals(
            cursorBeforeFailure,
            json.decodeFromString<SyncV2FrontierResponse>(frontier.body).frontiers.single().cursorValue,
        )
    }

    @Test
    fun revokedDeviceCannotUseV2WhileOtherDevicesContinue() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val first = registerAccountAndDevice()
        val revoked = registerDevice(first.accessToken, "Revoked V2 device", "ios")
        val prepared = checkpoint(first.device.id, previous = null)
        publishEpoch(first.accessToken, prepared)

        val revoke = client.delete("/devices/${revoked.device.id}") {
            bearerAuth(first.accessToken)
        }
        assertEquals(HttpStatusCode.OK, revoke.status, revoke.bodyAsText())

        val revokedPull = postJson(
            "/sync/v2/pull",
            revoked.accessToken,
            SyncV2PullRequest(prepared.descriptor.syncEpochId),
        )
        assertTrue(revokedPull.status in setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden))
        val revokedPush = postJson(
            "/sync/v2/push",
            revoked.accessToken,
            SyncV2PushRequest(
                prepared.descriptor.syncEpochId,
                2,
                listOf(entityObject(prepared, revoked.device.id, OTHER_OBJECT_ID, "Rejected")),
            ),
        )
        assertTrue(revokedPush.status in setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden))

        val healthyPull = postJson(
            "/sync/v2/pull",
            first.accessToken,
            SyncV2PullRequest(prepared.descriptor.syncEpochId),
        )
        assertEquals(HttpStatusCode.OK, healthyPull.status, healthyPull.body)
    }

    private fun checkpoint(
        writerDeviceId: String,
        previous: PreparedWorkspaceEpochCheckpointV2?,
        previousEpochId: String? = previous?.descriptor?.syncEpochId,
        previousEpochPointerDigest: String? = previous?.pointerObject?.objectDigest,
    ): PreparedWorkspaceEpochCheckpointV2 = WorkspaceCheckpointBuilderV2(
        WORKSPACE_KEY,
        writerDeviceId,
    ).build(
        remoteProfile = "self-hosted-v2",
        sourceHeads = listOf(
            WorkspaceCheckpointSourceHeadV2(
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                "workspace-preferences",
                WorkspacePreferencesV2(),
                null,
                "integration-test",
                previous?.descriptor?.syncEpochId,
                writerDeviceId,
                null,
                "source-${previous?.descriptor?.syncEpochId ?: "genesis"}",
                "source-digest-${previous?.descriptor?.syncEpochId ?: "genesis"}",
            ),
        ),
        createdAt = NOW,
        previousPointerDigest = previous?.pointerObject?.objectDigest,
        previousEpochId = previousEpochId,
        previousEpochPointerDigest = previousEpochPointerDigest,
    )

    private suspend fun ApplicationTestBuilder.publishEpoch(
        accessToken: String,
        value: PreparedWorkspaceEpochCheckpointV2,
    ) {
        uploadCheckpointObjects(accessToken, value)
        val cas = compareAndSetEpoch(accessToken, value)
        assertTrue(json.decodeFromString<SyncV2EpochCompareAndSetResponse>(cas.body).published, cas.body)
    }

    private suspend fun ApplicationTestBuilder.uploadCheckpointObjects(
        accessToken: String,
        value: PreparedWorkspaceEpochCheckpointV2,
    ) {
        value.chunks.forEach { chunk ->
            val response = postJson(
                "/sync/v2/checkpoint/chunk",
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
            assertTrue(json.decodeFromString<SyncV2ImmutablePutResponse>(response.body).stored, response.body)
        }
        val manifest = postJson(
            "/sync/v2/checkpoint/manifest",
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
        assertTrue(json.decodeFromString<SyncV2ImmutablePutResponse>(manifest.body).stored, manifest.body)
    }

    private suspend fun ApplicationTestBuilder.compareAndSetEpoch(
        accessToken: String,
        value: PreparedWorkspaceEpochCheckpointV2,
    ): HttpResult {
        val descriptor = value.descriptor
        return postJson(
            "/sync/v2/epoch/compare-and-set",
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

    private fun entityObject(
        checkpoint: PreparedWorkspaceEpochCheckpointV2,
        writerDeviceId: String,
        entityId: String,
        title: String,
    ): SyncV2ObjectPayload {
        val epochId = checkpoint.descriptor.syncEpochId
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(SyncEpochKeyDerivationV2().derive(WORKSPACE_KEY, epochId))
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

    private fun reencrypt(
        original: SyncV2ObjectPayload,
        writerDeviceId: String,
        checkpoint: PreparedWorkspaceEpochCheckpointV2,
    ): SyncV2ObjectPayload {
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeyDerivationV2().derive(WORKSPACE_KEY, checkpoint.descriptor.syncEpochId),
        )
        val cipher = WorkspaceObjectCipherV2(WORKSPACE_KEY, materializer)
        val shared = json.decodeFromString<saien.someday.sync.causality.v2.EncryptedWorkspaceObjectV2>(
            json.encodeToString(original),
        )
        val plaintext = when (val decoded = cipher.decrypt(shared)) {
            is saien.someday.sync.causality.v2.EncryptedWorkspaceObjectDecodeResultV2.Decoded -> decoded.plaintext
            is saien.someday.sync.causality.v2.EncryptedWorkspaceObjectDecodeResultV2.Rejected -> error(decoded.error.safeMessage)
        }
        return cipher.reencryptReplica(shared, writerDeviceId, plaintext).toServer()
    }

    private fun saien.someday.sync.causality.v2.EncryptedWorkspaceObjectV2.toServer(): SyncV2ObjectPayload =
        json.decodeFromString(json.encodeToString(this))

    private fun PreparedWorkspaceEpochCheckpointV2.toCleanupRequest() =
        SyncV2CheckpointCleanupRequest(
            epochId = descriptor.syncEpochId,
            checkpointId = descriptor.checkpointId,
            checkpointDigest = descriptor.checkpointDigest,
            previousPointerDigest = pointer.previousPointerDigest,
            chunks = chunks.map { chunk ->
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
        )

    private suspend fun ApplicationTestBuilder.registerAccountAndDevice(): DeviceRegistrationResponse {
        val registration = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AuthRequest("sync-v2-${System.nanoTime()}@example.com", "valid-password")))
        }
        return registerDevice(
            json.decodeFromString<AuthTokensResponse>(registration.bodyAsText()).accessToken,
            "Primary V2 device",
            "android",
        )
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

    private suspend fun ApplicationTestBuilder.postRawJson(
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

    private fun clearServerTables() {
        runCatching {
            DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        TRUNCATE TABLE
                            someday_sync_v2_mutations, someday_sync_v2_changes,
                            someday_sync_v2_object_replicas, someday_sync_v2_objects,
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

    private fun deleteV2ObjectReplicas(epochId: String, objectId: String) {
        DriverManager.getConnection(dbUrl, dbUser, dbPassword).use { connection ->
            connection.prepareStatement(
                "DELETE FROM someday_sync_v2_object_replicas WHERE epoch_id = ? AND object_id = ?",
            ).use { statement ->
                statement.setString(1, epochId)
                statement.setString(2, objectId)
                assertTrue(statement.executeUpdate() > 0)
            }
        }
    }

    private data class HttpResult(val status: HttpStatusCode, val body: String)

    private companion object {
        const val NOTEBOOK_ID = "00000000-0000-4000-8000-000000000111"
        const val OTHER_OBJECT_ID = "00000000-0000-4000-8000-000000000222"
        const val LATE_OBJECT_ID = "00000000-0000-4000-8000-000000000333"
        const val EXTERNAL_SOURCE_EPOCH_ID = "00000000-0000-4000-8000-000000000999"
        const val EXTERNAL_SOURCE_POINTER_DIGEST =
            "cd2:hmac-sha256:abababababababababababababababababababababababababababababababab"
        val NOW = Instant.parse("2026-07-19T00:00:00Z")
        val WORKSPACE_KEY = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 41).toByte() })
    }
}
