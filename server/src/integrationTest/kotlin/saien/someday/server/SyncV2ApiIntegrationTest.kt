@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.server

import saien.someday.server.api.SyncV2CheckpointChunkRef
import saien.someday.server.api.SyncV2CheckpointChunkRequest
import saien.someday.server.api.SyncV2CheckpointCleanupResponse
import saien.someday.server.api.SyncV2CheckpointFetchRequest
import saien.someday.server.api.SyncV2CheckpointFetchResponse
import saien.someday.server.api.SyncV2CheckpointManifestRequest
import saien.someday.server.api.SyncV2EpochCompareAndSetResponse
import saien.someday.server.api.SyncV2EpochResponse
import saien.someday.server.api.SyncV2FrontierRequest
import saien.someday.server.api.SyncV2ImmutablePutResponse
import saien.someday.server.api.SyncV2PullRequest
import saien.someday.server.api.SyncV2PullResponse
import saien.someday.server.api.SyncV2PushRequest
import saien.someday.server.api.SyncV2PushResponse
import saien.someday.server.support.EXTERNAL_SOURCE_EPOCH_ID
import saien.someday.server.support.EXTERNAL_SOURCE_POINTER_DIGEST
import saien.someday.server.support.NOTEBOOK_ID
import saien.someday.server.support.OTHER_OBJECT_ID
import saien.someday.server.support.OTHER_WORKSPACE_ID
import saien.someday.server.support.SYNC_V2_HTTP_JSON
import saien.someday.server.support.WORKSPACE_ID
import saien.someday.server.support.checkpoint
import saien.someday.server.support.clearServerTables
import saien.someday.server.support.compareAndSetEpoch
import saien.someday.server.support.entityObject
import saien.someday.server.support.postJson
import saien.someday.server.support.postRawJson
import saien.someday.server.support.publishEpoch
import saien.someday.server.support.reencrypt
import saien.someday.server.support.registerAccountAndDevice
import saien.someday.server.support.registerDevice
import saien.someday.server.support.toCleanupRequest
import saien.someday.server.support.toServer
import saien.someday.server.support.uploadCheckpointObjects
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncV2ApiIntegrationTest {
    private val json = SYNC_V2_HTTP_JSON

    @BeforeTest fun setUp() = clearServerTables()
    @AfterTest fun tearDown() = clearServerTables()

    @Test
    fun checkpointPushExactReplayPullAndSingleEpochGuardShareExactOpaqueContract() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val first = registerAccountAndDevice()
        val second = registerDevice(first.accessToken, "Second V2 device", "ios")
        val old = checkpoint(first.device.id, previous = null)
        publishEpoch(first.accessToken, old)

        val firstObject = entityObject(old, first.device.id, NOTEBOOK_ID, "First")
        val firstPush = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/push",
            first.accessToken,
            SyncV2PushRequest(old.descriptor.syncEpochId, 2, listOf(firstObject)),
        )
        assertEquals(HttpStatusCode.OK, firstPush.status, firstPush.body)
        assertFalse(json.decodeFromString<SyncV2PushResponse>(firstPush.body).acknowledgements.single().idempotentReplay)

        val replay = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/push",
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
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/push",
            second.accessToken,
            SyncV2PushRequest(old.descriptor.syncEpochId, 2, listOf(collision)),
        )
        assertEquals(HttpStatusCode.Conflict, rejectedCollision.status, rejectedCollision.body)
        assertEquals("mutation_reuse_mismatch", json.decodeFromString<SyncV2PushResponse>(rejectedCollision.body).error)

        val pull = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/pull",
            second.accessToken,
            SyncV2PullRequest(old.descriptor.syncEpochId),
        )
        assertEquals(listOf(firstObject), json.decodeFromString<SyncV2PullResponse>(pull.body).units.flatMap { it.objects })

        val differentCiphertext = reencrypt(firstObject, first.device.id, old)
        val rejectedNonExactReplay = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/push",
            first.accessToken,
            SyncV2PushRequest(old.descriptor.syncEpochId, 2, listOf(differentCiphertext)),
        )
        assertEquals(HttpStatusCode.Conflict, rejectedNonExactReplay.status, rejectedNonExactReplay.body)
        assertEquals(
            "immutable_object_mismatch",
            json.decodeFromString<SyncV2PushResponse>(rejectedNonExactReplay.body).error,
        )

        val secondGeneration = checkpoint(first.device.id, previous = null)
        uploadCheckpointObjects(first.accessToken, secondGeneration)
        val secondCas = compareAndSetEpoch(first.accessToken, secondGeneration)
        assertEquals(HttpStatusCode.Conflict, secondCas.status, secondCas.body)
        assertFalse(json.decodeFromString<SyncV2EpochCompareAndSetResponse>(secondCas.body).published)
    }

    @Test
    fun checkpointFetchIsObjectPagedAndEveryV2RequestUsesStrictBoundedJson() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccountAndDevice()
        val prepared = checkpoint(account.device.id, previous = null)
        publishEpoch(account.accessToken, prepared)

        val manifestResult = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/fetch",
            account.accessToken,
            SyncV2CheckpointFetchRequest(prepared.descriptor.syncEpochId, prepared.descriptor.checkpointId),
        )
        assertEquals(HttpStatusCode.OK, manifestResult.status, manifestResult.body)
        val manifestPage = json.decodeFromString<SyncV2CheckpointFetchResponse>(manifestResult.body)
        assertNotNull(manifestPage.manifest)
        assertNull(manifestPage.chunk)

        val expectedChunk = prepared.chunks.single()
        val chunkResult = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/fetch",
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
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/pull",
            account.accessToken,
            """{"epochId":"${prepared.descriptor.syncEpochId}","limit":1,"unexpected":true}""",
        )
        assertEquals(HttpStatusCode.BadRequest, unknownField.status, unknownField.body)

        val escapedDuplicate = postRawJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/pull",
            account.accessToken,
            """{"epochId":"${prepared.descriptor.syncEpochId}","\u0065pochId":"${prepared.descriptor.syncEpochId}","limit":1}""",
        )
        assertEquals(HttpStatusCode.BadRequest, escapedDuplicate.status, escapedDuplicate.body)

        val oversized = postRawJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/pull",
            account.accessToken,
            """{"padding":"${"x".repeat(16 * 1024 * 1024)}"}""",
        )
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status, oversized.body)
    }

    @Test
    fun checkpointObjectsAcceptOnlyByteExactImmutableReplay() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccountAndDevice()
        val prepared = checkpoint(account.device.id, previous = null)
        uploadCheckpointObjects(account.accessToken, prepared)

        val chunk = prepared.chunks.single()
        val differentChunkCiphertext = reencrypt(chunk.encryptedObject.toServer(), account.device.id, prepared)
        val chunkReplay = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/chunk",
            account.accessToken,
            SyncV2CheckpointChunkRequest(
                prepared.descriptor.syncEpochId,
                prepared.descriptor.checkpointId,
                chunk.ref.let {
                    SyncV2CheckpointChunkRef(
                        it.chunkIndex,
                        it.chunkId,
                        it.chunkDigest,
                        it.objectCount,
                        it.plaintextBytes,
                    )
                },
                differentChunkCiphertext,
            ),
        )
        val chunkResult = json.decodeFromString<SyncV2ImmutablePutResponse>(chunkReplay.body)
        assertFalse(chunkResult.stored)
        assertEquals("immutable_object_mismatch", chunkResult.error)

        val crossCheckpointChunkReuse = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/chunk",
            account.accessToken,
            SyncV2CheckpointChunkRequest(
                prepared.descriptor.syncEpochId,
                OTHER_OBJECT_ID,
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
        assertEquals(HttpStatusCode.Conflict, crossCheckpointChunkReuse.status, crossCheckpointChunkReuse.body)
        assertEquals(
            "immutable_object_mismatch",
            json.decodeFromString<SyncV2ImmutablePutResponse>(crossCheckpointChunkReuse.body).error,
        )

        val differentManifestCiphertext = reencrypt(
            prepared.manifestObject.toServer(),
            account.device.id,
            prepared,
        )
        val manifestReplay = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/manifest",
            account.accessToken,
            SyncV2CheckpointManifestRequest(
                prepared.descriptor.syncEpochId,
                prepared.descriptor.checkpointId,
                prepared.descriptor.checkpointDigest,
                prepared.chunks.map { value ->
                    value.ref.let {
                        SyncV2CheckpointChunkRef(
                            it.chunkIndex,
                            it.chunkId,
                            it.chunkDigest,
                            it.objectCount,
                            it.plaintextBytes,
                        )
                    }
                },
                prepared.manifest.totalObjectCount,
                differentManifestCiphertext,
            ),
        )
        val manifestResult = json.decodeFromString<SyncV2ImmutablePutResponse>(manifestReplay.body)
        assertFalse(manifestResult.stored)
        assertEquals("immutable_object_mismatch", manifestResult.error)

        val fetchedChunk = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/fetch",
            account.accessToken,
            SyncV2CheckpointFetchRequest(
                prepared.descriptor.syncEpochId,
                prepared.descriptor.checkpointId,
                chunk.ref.chunkIndex,
            ),
        )
        assertEquals(
            chunk.encryptedObject.toServer(),
            json.decodeFromString<SyncV2CheckpointFetchResponse>(fetchedChunk.body).chunk,
        )
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
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/cleanup",
            account.accessToken,
            winner.toCleanupRequest(),
        )
        assertEquals(HttpStatusCode.Conflict, referenced.status, referenced.body)
        assertEquals(
            "checkpoint_referenced",
            json.decodeFromString<SyncV2CheckpointCleanupResponse>(referenced.body).error,
        )

        val deleted = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/cleanup",
            account.accessToken,
            obsolete.toCleanupRequest(),
        )
        assertEquals(HttpStatusCode.OK, deleted.status, deleted.body)
        val deletedBody = json.decodeFromString<SyncV2CheckpointCleanupResponse>(deleted.body)
        assertTrue(deletedBody.deleted)
        assertFalse(deletedBody.alreadyAbsent)

        val missingManifest = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/fetch",
            account.accessToken,
            SyncV2CheckpointFetchRequest(
                obsolete.descriptor.syncEpochId,
                obsolete.descriptor.checkpointId,
            ),
        )
        assertEquals(HttpStatusCode.NotFound, missingManifest.status, missingManifest.body)
        val missingChunk = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/fetch",
            account.accessToken,
            SyncV2CheckpointFetchRequest(
                obsolete.descriptor.syncEpochId,
                obsolete.descriptor.checkpointId,
                obsolete.chunks.single().ref.chunkIndex,
            ),
        )
        assertEquals(HttpStatusCode.NotFound, missingChunk.status, missingChunk.body)

        val replay = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/checkpoint/cleanup",
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
                "/sync/v3/workspaces/$WORKSPACE_ID/entities/frontiers",
                account.accessToken,
                SyncV2FrontierRequest(prepared.descriptor.syncEpochId),
            )
            assertEquals(HttpStatusCode.OK, response.status, "request=$requestIndex ${response.body}")
        }
    }

    @Test
    fun entityAuthorityIsIsolatedByWorkspaceForTheSameAccount() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccountAndDevice()
        val sharedIdentityEpoch = checkpoint(account.device.id, previous = null)

        // Deliberately reuse every immutable identity in both workspaces. This
        // test runs with the configured PostgreSQL superuser, so RLS cannot
        // hide a missing explicit workspace predicate in repository SQL.
        publishEpoch(account.accessToken, sharedIdentityEpoch, WORKSPACE_ID)
        publishEpoch(account.accessToken, sharedIdentityEpoch, OTHER_WORKSPACE_ID)

        val first = client.get("/sync/v3/workspaces/$WORKSPACE_ID/entities/epoch") {
            bearerAuth(account.accessToken)
        }
        val second = client.get("/sync/v3/workspaces/$OTHER_WORKSPACE_ID/entities/epoch") {
            bearerAuth(account.accessToken)
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        val firstMetadata = json.decodeFromString<SyncV2EpochResponse>(first.bodyAsText()).metadata
        val secondMetadata = json.decodeFromString<SyncV2EpochResponse>(second.bodyAsText()).metadata
        assertEquals(sharedIdentityEpoch.pointerObject.objectDigest, firstMetadata?.pointerDigest)
        assertEquals(sharedIdentityEpoch.pointerObject.objectDigest, secondMetadata?.pointerDigest)

        val firstWorkspaceObject = entityObject(
            sharedIdentityEpoch,
            account.device.id,
            NOTEBOOK_ID,
            "Only in first workspace",
        )
        val pushed = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/push",
            account.accessToken,
            SyncV2PushRequest(sharedIdentityEpoch.descriptor.syncEpochId, 2, listOf(firstWorkspaceObject)),
        )
        assertEquals(HttpStatusCode.OK, pushed.status, pushed.body)
        val isolatedPull = postJson(
            "/sync/v3/workspaces/$OTHER_WORKSPACE_ID/entities/pull",
            account.accessToken,
            SyncV2PullRequest(sharedIdentityEpoch.descriptor.syncEpochId),
        )
        assertEquals(HttpStatusCode.OK, isolatedPull.status, isolatedPull.body)
        assertTrue(json.decodeFromString<SyncV2PullResponse>(isolatedPull.body).units.isEmpty())
    }

    @Test
    fun emptyAuthorityRejectsExternalPreviousEpochLineage() = testApplication {
        application { somedayServerModule() }
        clearServerTables()
        val account = registerAccountAndDevice()
        val externalLineage = checkpoint(
            account.device.id,
            previous = null,
            previousEpochId = EXTERNAL_SOURCE_EPOCH_ID,
            previousEpochPointerDigest = EXTERNAL_SOURCE_POINTER_DIGEST,
        )

        uploadCheckpointObjects(account.accessToken, externalLineage)
        val cas = compareAndSetEpoch(account.accessToken, externalLineage)

        assertEquals(HttpStatusCode.BadRequest, cas.status, cas.body)
        assertEquals(
            "invalid_epoch_pointer",
            json.decodeFromString<SyncV2EpochCompareAndSetResponse>(cas.body).error,
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
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/pull",
            revoked.accessToken,
            SyncV2PullRequest(prepared.descriptor.syncEpochId),
        )
        assertTrue(revokedPull.status in setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden))
        val revokedPush = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/push",
            revoked.accessToken,
            SyncV2PushRequest(
                prepared.descriptor.syncEpochId,
                2,
                listOf(entityObject(prepared, revoked.device.id, OTHER_OBJECT_ID, "Rejected")),
            ),
        )
        assertTrue(revokedPush.status in setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden))

        val healthyPull = postJson(
            "/sync/v3/workspaces/$WORKSPACE_ID/entities/pull",
            first.accessToken,
            SyncV2PullRequest(prepared.descriptor.syncEpochId),
        )
        assertEquals(HttpStatusCode.OK, healthyPull.status, healthyPull.body)
    }

}
