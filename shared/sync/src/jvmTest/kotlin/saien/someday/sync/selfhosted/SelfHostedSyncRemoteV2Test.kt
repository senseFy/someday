@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.selfhosted

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.sync.causality.v2.PreparedWorkspaceEpochCheckpointV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.WORKSPACE_PREFERENCES_ENTITY_ID_V2
import saien.someday.sync.causality.v2.WorkspaceCheckpointBuilderV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointSourceHeadV2
import saien.someday.sync.causality.v2.WorkspaceEntityTypeV2
import saien.someday.sync.causality.v2.WorkspacePreferencesV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class SelfHostedSyncRemoteV2Test {
    @Test
    fun checkpointBootstrapFetchesManifestThenEachImmutableChunk() {
        val crypto = SodiumWorkspaceCrypto()
        val workspaceKey = crypto.workspaceKeyFromBytes(ByteArray(32) { (it + 9).toByte() })
        val prepared = WorkspaceCheckpointBuilderV2(workspaceKey, WRITER_ID).build(
            remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
            sourceHeads = listOf(
                WorkspaceCheckpointSourceHeadV2(
                    entityType = WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    entityId = WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    content = WorkspacePreferencesV2(),
                    deletion = null,
                    sourceProfile = "self-hosted-test",
                    sourceEpoch = null,
                    sourceWriterId = WRITER_ID,
                    sourceMutationId = null,
                    sourceObjectId = "preferences-source",
                    sourceObjectDigest = "preferences-source-digest",
                ),
            ),
            createdAt = Instant.parse("2026-07-19T00:00:00Z"),
        )
        val transport = CheckpointPagingTransport(prepared)
        val remote = SelfHostedSyncRemoteV2(
            endpoint = "https://sync.example.test",
            workspaceKey = workspaceKey,
            accessTokenProvider = { "opaque-test-token" },
            transport = transport,
        )

        val fetched = remote.fetchCheckpoint(prepared.pointerObject, prepared.descriptor)

        assertEquals(prepared.manifestObject, fetched.manifest)
        assertEquals(prepared.chunks.map { it.encryptedObject }, fetched.chunks)
        assertEquals(listOf<Int?>(null) + prepared.chunks.map { it.ref.chunkIndex }, transport.requestedIndexes)
    }

    @Test
    fun missingReferencedCheckpointChunkFailsClosed() {
        val crypto = SodiumWorkspaceCrypto()
        val workspaceKey = crypto.workspaceKeyFromBytes(ByteArray(32) { (it + 19).toByte() })
        val prepared = WorkspaceCheckpointBuilderV2(workspaceKey, WRITER_ID).build(
            remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
            sourceHeads = listOf(WorkspaceCheckpointSourceHeadV2(
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                WorkspacePreferencesV2(),
                null,
                "missing-chunk-test",
                null,
                WRITER_ID,
                null,
                "preferences-source",
                "preferences-source-digest",
            )),
            createdAt = Instant.parse("2026-07-19T00:00:00Z"),
        )
        val missingIndex = prepared.chunks.single().ref.chunkIndex
        val transport = CheckpointPagingTransport(prepared, missingChunkIndex = missingIndex)
        val remote = SelfHostedSyncRemoteV2(
            endpoint = "https://sync.example.test",
            workspaceKey = workspaceKey,
            accessTokenProvider = { "opaque-test-token" },
            transport = transport,
        )

        assertFailsWith<IllegalArgumentException> {
            remote.fetchCheckpoint(prepared.pointerObject, prepared.descriptor)
        }
        assertEquals(listOf<Int?>(null, missingIndex), transport.requestedIndexes)
    }

    private class CheckpointPagingTransport(
        private val prepared: PreparedWorkspaceEpochCheckpointV2,
        private val missingChunkIndex: Int? = null,
    ) : SelfHostedSyncTransportV2 {
        val requestedIndexes = mutableListOf<Int?>()

        override fun v2FetchCheckpoint(
            endpoint: String,
            accessToken: String,
            request: SelfHostedV2CheckpointFetchRequest,
        ): SelfHostedV2CheckpointFetchResponse {
            requestedIndexes += request.chunkIndex
            return if (request.chunkIndex == null) {
                SelfHostedV2CheckpointFetchResponse(manifest = prepared.manifestObject)
            } else if (request.chunkIndex == missingChunkIndex) {
                SelfHostedV2CheckpointFetchResponse()
            } else {
                SelfHostedV2CheckpointFetchResponse(
                    chunk = prepared.chunks.single { it.ref.chunkIndex == request.chunkIndex }.encryptedObject,
                )
            }
        }

        override fun v2Capabilities(endpoint: String, accessToken: String): SelfHostedV2CapabilitiesResponse = unused()
        override fun v2Epoch(endpoint: String, accessToken: String): SelfHostedV2EpochResponse = unused()
        override fun v2EpochHistory(
            endpoint: String,
            accessToken: String,
            request: SelfHostedV2EpochHistoryRequest,
        ): SelfHostedV2EpochResponse = unused()
        override fun v2PutCheckpointChunk(
            endpoint: String,
            accessToken: String,
            request: SelfHostedV2CheckpointChunkRequest,
        ): SelfHostedV2ImmutablePutResponse = unused()
        override fun v2PutCheckpointManifest(
            endpoint: String,
            accessToken: String,
            request: SelfHostedV2CheckpointManifestRequest,
        ): SelfHostedV2ImmutablePutResponse = unused()
        override fun v2CompareAndSetEpoch(
            endpoint: String,
            accessToken: String,
            request: SelfHostedV2EpochCompareAndSetRequest,
        ): SelfHostedV2EpochCompareAndSetResponse = unused()
        override fun v2Push(
            endpoint: String,
            accessToken: String,
            request: SelfHostedV2PushRequest,
        ): SelfHostedV2PushResponse = unused()
        override fun v2Pull(
            endpoint: String,
            accessToken: String,
            request: SelfHostedV2PullRequest,
        ): SelfHostedV2PullResponse = unused()
        override fun v2Frontiers(
            endpoint: String,
            accessToken: String,
            request: SelfHostedV2FrontierRequest,
        ): SelfHostedV2FrontierResponse = unused()
        override fun v2RepairObject(
            endpoint: String,
            accessToken: String,
            request: SelfHostedV2RepairObjectRequest,
        ): SelfHostedV2RepairObjectResponse = unused()
        override fun v2PublishRepairReplica(
            endpoint: String,
            accessToken: String,
            request: SelfHostedV2RepairReplicaRequest,
        ): SelfHostedV2ImmutablePutResponse = unused()

        private fun <T> unused(): T = error("Unexpected transport operation")
    }

    private companion object {
        const val WRITER_ID = "00000000-0000-4000-8000-0000000000a1"
    }
}
