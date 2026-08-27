package saien.someday.server.persistence

import saien.someday.server.ServerConfig
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class SyncV2ObjectInput(
    val epochId: String,
    val objectId: String,
    val objectType: String,
    val objectDigest: String,
    val mutationId: String,
    val writerDeviceId: UUID,
    val ciphertextDigest: String,
    val encodedObjectJson: String,
)

data class SyncV2EpochMetadataRecord(
    val contractId: String,
    val schemaSetVersion: String,
    val epochId: String,
    val pointerDigest: String,
    val semanticProtocolVersion: Int,
    val minimumWriterProtocolVersion: Int,
    val keySetVersion: String,
    val remoteProfile: String,
    val metadataPrivacyMode: String,
    val supportedOfflineWindowSeconds: Long,
    val checkpointId: String,
    val checkpointDigest: String,
    val previousEpochId: String?,
    val previousEpochPointerDigest: String?,
)

data class SyncV2EpochRecord(
    val metadata: SyncV2EpochMetadataRecord,
    val pointerObjectJson: String,
)

data class SyncV2CheckpointChunkRefRecord(
    val chunkIndex: Int,
    val chunkId: String,
    val chunkDigest: String,
    val objectCount: Int,
    val plaintextBytes: Int,
)

data class SyncV2CheckpointChunkInput(
    val epochId: String,
    val checkpointId: String,
    val ref: SyncV2CheckpointChunkRefRecord,
    val encryptedObjectJson: String,
)

data class SyncV2CheckpointManifestInput(
    val epochId: String,
    val checkpointId: String,
    val checkpointDigest: String,
    val chunks: List<SyncV2CheckpointChunkRefRecord>,
    val totalObjectCount: Int,
    val encryptedObjectJson: String,
)

data class SyncV2CheckpointCleanupInput(
    val epochId: String,
    val checkpointId: String,
    val checkpointDigest: String,
    val previousPointerDigest: String?,
    val chunks: List<SyncV2CheckpointChunkRefRecord>,
)

private data class StoredSyncV2CheckpointChunk(
    val checkpointId: String,
    val ref: SyncV2CheckpointChunkRefRecord,
    val encryptedObjectJson: String,
)

private data class StoredSyncV2CheckpointManifestIdentity(
    val checkpointDigest: String,
    val chunkCount: Int,
    val totalObjectCount: Int,
    val chunkRefsFingerprint: String,
    val encryptedObjectJson: String,
)

data class SyncV2MutationAckRecord(
    val mutationId: String,
    val objectId: String,
    val objectDigest: String,
    val idempotentReplay: Boolean,
)

sealed interface SyncV2PushRepositoryResult {
    data class Accepted(val acknowledgements: List<SyncV2MutationAckRecord>) : SyncV2PushRepositoryResult
    data class Rejected(val error: String) : SyncV2PushRepositoryResult
}

data class SyncV2ChangeRecord(val cursor: Long, val encodedObjectJson: String)

data class SyncV2PullRepositoryResult(
    val changes: List<SyncV2ChangeRecord>,
    val complete: Boolean,
    val error: String? = null,
)

data class SyncV2EpochFrontierRecord(val cursor: Long, val streamDigest: String)

data class SyncV2StatusSnapshot(
    val activeEpochId: String?,
    val cursor: Long,
    val immutableObjects: Long,
)

sealed interface SyncV2PointerPublishRepositoryResult {
    data class Published(val idempotentReplay: Boolean) : SyncV2PointerPublishRepositoryResult
    data class CompareAndSetFailed(val current: SyncV2EpochRecord?) : SyncV2PointerPublishRepositoryResult
    data class Rejected(val error: String) : SyncV2PointerPublishRepositoryResult
}

sealed interface SyncV2ImmutablePutRepositoryResult {
    data class Stored(val idempotentReplay: Boolean) : SyncV2ImmutablePutRepositoryResult
    data class Rejected(val error: String) : SyncV2ImmutablePutRepositoryResult
}

sealed interface SyncV2CheckpointCleanupRepositoryResult {
    data class Deleted(val alreadyAbsent: Boolean) : SyncV2CheckpointCleanupRepositoryResult
    data class Retained(val error: String) : SyncV2CheckpointCleanupRepositoryResult
}

class SyncV2Repository(
    config: ServerConfig,
    private val connections: DatabaseConnectionProvider = directDatabaseConnectionProvider(config),
) {
    fun loadEpoch(userId: UUID, workspaceId: String): SyncV2EpochRecord? =
        scopedConnection(userId, workspaceId).use { connection ->
        loadActiveEpoch(connection, userId, workspaceId, false)
    }

    fun putCheckpointChunk(
        userId: UUID,
        workspaceId: String,
        input: SyncV2CheckpointChunkInput,
    ): SyncV2ImmutablePutRepositoryResult = transaction(userId, workspaceId) { connection ->
        lockWorkspace(connection, userId, workspaceId)
        val ref = input.ref
        val existing = connection.prepareStatement(
            """
            SELECT checkpoint_id, chunk_index, chunk_id, chunk_digest, object_count, plaintext_bytes,
                   encrypted_object_json
            FROM someday_sync_v2_checkpoint_chunks
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ?
              AND ((checkpoint_id = ? AND chunk_index = ?) OR chunk_id = ?)
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, input.epochId)
            statement.setString(4, input.checkpointId)
            statement.setInt(5, ref.chunkIndex)
            statement.setString(6, ref.chunkId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else StoredSyncV2CheckpointChunk(
                    checkpointId = result.getString("checkpoint_id"),
                    ref = SyncV2CheckpointChunkRefRecord(
                        result.getInt("chunk_index"),
                        result.getString("chunk_id"),
                        result.getString("chunk_digest"),
                        result.getInt("object_count"),
                        result.getInt("plaintext_bytes"),
                    ),
                    encryptedObjectJson = result.getString("encrypted_object_json"),
                )
            }
        }
        when {
            existing != null &&
                (existing.checkpointId != input.checkpointId || existing.ref != ref ||
                    existing.encryptedObjectJson != input.encryptedObjectJson) ->
                SyncV2ImmutablePutRepositoryResult.Rejected("immutable_object_mismatch")
            existing != null -> SyncV2ImmutablePutRepositoryResult.Stored(true)
            else -> {
                connection.prepareStatement(
                    """
                    INSERT INTO someday_sync_v2_checkpoint_chunks(
                        user_id, workspace_id, epoch_id, checkpoint_id, chunk_index, chunk_id,
                        chunk_digest, object_count, plaintext_bytes, encrypted_object_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, workspaceId)
                    statement.setString(3, input.epochId)
                    statement.setString(4, input.checkpointId)
                    statement.setInt(5, ref.chunkIndex)
                    statement.setString(6, ref.chunkId)
                    statement.setString(7, ref.chunkDigest)
                    statement.setInt(8, ref.objectCount)
                    statement.setInt(9, ref.plaintextBytes)
                    statement.setString(10, input.encryptedObjectJson)
                    statement.executeUpdate()
                }
                SyncV2ImmutablePutRepositoryResult.Stored(false)
            }
        }
    }

    fun putCheckpointManifest(
        userId: UUID,
        workspaceId: String,
        input: SyncV2CheckpointManifestInput,
    ): SyncV2ImmutablePutRepositoryResult = transaction(userId, workspaceId) { connection ->
        lockWorkspace(connection, userId, workspaceId)
        val fingerprint = chunkRefsFingerprint(input.chunks)
        val existing = connection.prepareStatement(
            """
            SELECT checkpoint_digest, chunk_count, total_object_count, chunk_refs_fingerprint,
                   encrypted_object_json
            FROM someday_sync_v2_checkpoint_manifests
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND checkpoint_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, input.epochId)
            statement.setString(4, input.checkpointId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else StoredSyncV2CheckpointManifestIdentity(
                    checkpointDigest = result.getString("checkpoint_digest"),
                    chunkCount = result.getInt("chunk_count"),
                    totalObjectCount = result.getInt("total_object_count"),
                    chunkRefsFingerprint = result.getString("chunk_refs_fingerprint"),
                    encryptedObjectJson = result.getString("encrypted_object_json"),
                )
            }
        }
        val incoming = StoredSyncV2CheckpointManifestIdentity(
            checkpointDigest = input.checkpointDigest,
            chunkCount = input.chunks.size,
            totalObjectCount = input.totalObjectCount,
            chunkRefsFingerprint = fingerprint,
            encryptedObjectJson = input.encryptedObjectJson,
        )
        when {
            existing != null && existing != incoming -> SyncV2ImmutablePutRepositoryResult.Rejected("immutable_object_mismatch")
            existing != null -> SyncV2ImmutablePutRepositoryResult.Stored(true)
            else -> {
                connection.prepareStatement(
                    """
                    INSERT INTO someday_sync_v2_checkpoint_manifests(
                        user_id, workspace_id, epoch_id, checkpoint_id, checkpoint_digest,
                        chunk_count, total_object_count, chunk_refs_fingerprint, encrypted_object_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, workspaceId)
                    statement.setString(3, input.epochId)
                    statement.setString(4, input.checkpointId)
                    statement.setString(5, input.checkpointDigest)
                    statement.setInt(6, input.chunks.size)
                    statement.setInt(7, input.totalObjectCount)
                    statement.setString(8, fingerprint)
                    statement.setString(9, input.encryptedObjectJson)
                    statement.executeUpdate()
                }
                SyncV2ImmutablePutRepositoryResult.Stored(false)
            }
        }
    }

    fun cleanupCheckpointDraft(
        userId: UUID,
        workspaceId: String,
        input: SyncV2CheckpointCleanupInput,
    ): SyncV2CheckpointCleanupRepositoryResult = transaction(userId, workspaceId) { connection ->
        lockWorkspace(connection, userId, workspaceId)
        val current = loadActiveEpoch(connection, userId, workspaceId, true)
            ?: return@transaction SyncV2CheckpointCleanupRepositoryResult.Retained(
                "checkpoint_still_publishable",
            )
        if (current.metadata.pointerDigest == input.previousPointerDigest) {
            return@transaction SyncV2CheckpointCleanupRepositoryResult.Retained(
                "checkpoint_still_publishable",
            )
        }
        if (loadEpochById(connection, userId, workspaceId, input.epochId) != null) {
            return@transaction SyncV2CheckpointCleanupRepositoryResult.Retained(
                "checkpoint_referenced",
            )
        }

        val expectedFingerprint = chunkRefsFingerprint(input.chunks)
        val manifest = connection.prepareStatement(
            """
            SELECT checkpoint_digest, chunk_count, chunk_refs_fingerprint
            FROM someday_sync_v2_checkpoint_manifests
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND checkpoint_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, input.epochId)
            statement.setString(4, input.checkpointId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else Triple(
                    result.getString(1),
                    result.getInt(2),
                    result.getString(3),
                )
            }
        }
        if (
            manifest != null &&
            (
                manifest.first != input.checkpointDigest ||
                    manifest.second != input.chunks.size ||
                    manifest.third != expectedFingerprint
            )
        ) {
            return@transaction SyncV2CheckpointCleanupRepositoryResult.Retained(
                "immutable_object_mismatch",
            )
        }
        val storedChunks = connection.prepareStatement(
            """
            SELECT chunk_index, chunk_id, chunk_digest, object_count, plaintext_bytes
            FROM someday_sync_v2_checkpoint_chunks
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND checkpoint_id = ?
            ORDER BY chunk_index
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, input.epochId)
            statement.setString(4, input.checkpointId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            SyncV2CheckpointChunkRefRecord(
                                result.getInt(1),
                                result.getString(2),
                                result.getString(3),
                                result.getInt(4),
                                result.getInt(5),
                            ),
                        )
                    }
                }
            }
        }
        val expectedByIndex = input.chunks.associateBy { it.chunkIndex }
        if (storedChunks.any { expectedByIndex[it.chunkIndex] != it }) {
            return@transaction SyncV2CheckpointCleanupRepositoryResult.Retained(
                "immutable_object_mismatch",
            )
        }

        val alreadyAbsent = manifest == null && storedChunks.isEmpty()
        connection.prepareStatement(
            """
            DELETE FROM someday_sync_v2_checkpoint_manifests
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND checkpoint_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, input.epochId)
            statement.setString(4, input.checkpointId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            DELETE FROM someday_sync_v2_checkpoint_chunks
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND checkpoint_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, input.epochId)
            statement.setString(4, input.checkpointId)
            statement.executeUpdate()
        }
        SyncV2CheckpointCleanupRepositoryResult.Deleted(alreadyAbsent)
    }

    fun compareAndSetEpoch(
        userId: UUID,
        workspaceId: String,
        expectedCurrentDigest: String?,
        metadata: SyncV2EpochMetadataRecord,
        pointerObjectJson: String,
    ): SyncV2PointerPublishRepositoryResult = transaction(userId, workspaceId) { connection ->
        lockWorkspace(connection, userId, workspaceId)
        val current = loadActiveEpoch(connection, userId, workspaceId, true)
        if (current?.metadata == metadata && current.pointerObjectJson == pointerObjectJson) {
            return@transaction SyncV2PointerPublishRepositoryResult.Published(true)
        }
        if (current != null) {
            return@transaction SyncV2PointerPublishRepositoryResult.CompareAndSetFailed(current)
        }
        if (expectedCurrentDigest != null ||
            metadata.previousEpochId != null || metadata.previousEpochPointerDigest != null
        ) {
            return@transaction SyncV2PointerPublishRepositoryResult.Rejected("previous_epoch_mismatch")
        }
        if (!checkpointIsComplete(connection, userId, workspaceId, metadata)) {
            return@transaction SyncV2PointerPublishRepositoryResult.Rejected("checkpoint_incomplete")
        }
        connection.prepareStatement(
            """
            INSERT INTO someday_sync_v2_epochs(
                user_id, workspace_id, epoch_id, pointer_digest, pointer_object_json,
                contract_id, schema_set_version, semantic_protocol_version,
                minimum_writer_protocol_version, key_set_version, remote_profile,
                metadata_privacy_mode, supported_offline_window_seconds,
                checkpoint_id, checkpoint_digest
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, metadata.epochId)
            statement.setString(4, metadata.pointerDigest)
            statement.setString(5, pointerObjectJson)
            statement.setString(6, metadata.contractId)
            statement.setString(7, metadata.schemaSetVersion)
            statement.setInt(8, metadata.semanticProtocolVersion)
            statement.setInt(9, metadata.minimumWriterProtocolVersion)
            statement.setString(10, metadata.keySetVersion)
            statement.setString(11, metadata.remoteProfile)
            statement.setString(12, metadata.metadataPrivacyMode)
            statement.setLong(13, metadata.supportedOfflineWindowSeconds)
            statement.setString(14, metadata.checkpointId)
            statement.setString(15, metadata.checkpointDigest)
            statement.executeUpdate()
        }
        SyncV2PointerPublishRepositoryResult.Published(false)
    }

    fun loadCheckpointManifest(
        userId: UUID,
        workspaceId: String,
        epochId: String,
        checkpointId: String,
    ): String? = scopedConnection(userId, workspaceId).use { connection ->
        connection.prepareStatement(
            """
            SELECT encrypted_object_json FROM someday_sync_v2_checkpoint_manifests
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND checkpoint_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, epochId)
            statement.setString(4, checkpointId)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    fun loadCheckpointChunk(
        userId: UUID,
        workspaceId: String,
        epochId: String,
        checkpointId: String,
        chunkIndex: Int,
    ): String? = scopedConnection(userId, workspaceId).use { connection ->
        connection.prepareStatement(
            """
            SELECT encrypted_object_json FROM someday_sync_v2_checkpoint_chunks
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND checkpoint_id = ? AND chunk_index = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, epochId)
            statement.setString(4, checkpointId)
            statement.setInt(5, chunkIndex)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    fun push(
        userId: UUID,
        workspaceId: String,
        deviceId: UUID,
        epochId: String,
        writerProtocolVersion: Int,
        objects: List<SyncV2ObjectInput>,
    ): SyncV2PushRepositoryResult = transaction(userId, workspaceId) { connection ->
        lockWorkspace(connection, userId, workspaceId)
        val epoch = loadActiveEpoch(connection, userId, workspaceId, true)
            ?: return@transaction SyncV2PushRepositoryResult.Rejected("v2_epoch_not_initialized")
        if (epoch.metadata.epochId != epochId) {
            return@transaction SyncV2PushRepositoryResult.Rejected("incompatible_epoch")
        }
        if (writerProtocolVersion < epoch.metadata.minimumWriterProtocolVersion) {
            return@transaction SyncV2PushRepositoryResult.Rejected("writer_upgrade_required")
        }
        if (objects.any { it.writerDeviceId != deviceId || it.epochId != epochId }) {
            return@transaction SyncV2PushRepositoryResult.Rejected("device_mismatch")
        }

        val prepared = mutableListOf<PreparedMutation>()
        for (input in objects) {
            val mutation = findMutation(connection, userId, workspaceId, epochId, input.mutationId)
            if (mutation != null && (mutation.objectId != input.objectId || mutation.objectDigest != input.objectDigest)) {
                return@transaction SyncV2PushRepositoryResult.Rejected("mutation_reuse_mismatch")
            }
            val objectValue = findObject(connection, userId, workspaceId, epochId, input.objectId, true)
            if (objectValue != null && !objectValue.exactlyMatches(input)
            ) {
                return@transaction SyncV2PushRepositoryResult.Rejected("immutable_object_mismatch")
            }
            if ((mutation == null) != (objectValue == null)) {
                return@transaction SyncV2PushRepositoryResult.Rejected("stored_object_invalid")
            }
            prepared += PreparedMutation(input, mutation)
        }

        val acknowledgements = prepared.map { value ->
            val input = value.input
            if (value.existingMutation != null) {
                SyncV2MutationAckRecord(input.mutationId, input.objectId, input.objectDigest, true)
            } else {
                connection.prepareStatement(
                    """
                    INSERT INTO someday_sync_v2_objects(
                        user_id, workspace_id, epoch_id, object_id, object_type, object_digest,
                        mutation_id, first_writer_device_id, ciphertext_digest,
                        encrypted_object_json, cursor
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, workspaceId)
                    statement.setString(3, epochId)
                    statement.setString(4, input.objectId)
                    statement.setString(5, input.objectType)
                    statement.setString(6, input.objectDigest)
                    statement.setString(7, input.mutationId)
                    statement.setObject(8, input.writerDeviceId)
                    statement.setString(9, input.ciphertextDigest)
                    statement.setString(10, input.encodedObjectJson)
                    statement.executeUpdate()
                }
                val cursor = connection.prepareStatement(
                    """
                    INSERT INTO someday_sync_v2_changes(
                        user_id, workspace_id, epoch_id, object_id, object_digest, mutation_id
                    ) VALUES (?, ?, ?, ?, ?, ?) RETURNING cursor
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, workspaceId)
                    statement.setString(3, epochId)
                    statement.setString(4, input.objectId)
                    statement.setString(5, input.objectDigest)
                    statement.setString(6, input.mutationId)
                    statement.executeQuery().use { result -> result.next(); result.getLong(1) }
                }
                connection.prepareStatement(
                    """
                    UPDATE someday_sync_v2_objects SET cursor = ?
                    WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND object_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, cursor)
                    statement.setObject(2, userId)
                    statement.setString(3, workspaceId)
                    statement.setString(4, epochId)
                    statement.setString(5, input.objectId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO someday_sync_v2_mutations(
                        user_id, workspace_id, epoch_id, mutation_id, object_id, object_digest, cursor
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, workspaceId)
                    statement.setString(3, epochId)
                    statement.setString(4, input.mutationId)
                    statement.setString(5, input.objectId)
                    statement.setString(6, input.objectDigest)
                    statement.setLong(7, cursor)
                    statement.executeUpdate()
                }
                SyncV2MutationAckRecord(input.mutationId, input.objectId, input.objectDigest, false)
            }
        }
        SyncV2PushRepositoryResult.Accepted(acknowledgements)
    }

    fun pull(
        userId: UUID,
        workspaceId: String,
        epochId: String,
        afterCursor: Long,
        limit: Int,
    ): SyncV2PullRepositoryResult = scopedConnection(userId, workspaceId).use { connection ->
        if (loadEpochById(connection, userId, workspaceId, epochId) == null) {
            return@use SyncV2PullRepositoryResult(emptyList(), true, error = "epoch_not_found")
        }
        val maximum = maximumCursor(connection, userId, workspaceId, epochId)
        if (afterCursor > maximum) {
            // An authenticated client cursor ahead of the server is rollback
            // evidence. The first-release protocol never discards history and
            // therefore has no rebootstrap/horizon escape hatch.
            return@use SyncV2PullRepositoryResult(
                emptyList(),
                complete = true,
                error = "remote_rollback_detected",
            )
        }
        val values = connection.prepareStatement(
            """
            SELECT c.cursor, o.encrypted_object_json
            FROM someday_sync_v2_changes c
            LEFT JOIN someday_sync_v2_objects o
              ON o.user_id = c.user_id AND o.workspace_id = c.workspace_id
             AND o.epoch_id = c.epoch_id AND o.object_id = c.object_id
            WHERE c.user_id = ? AND c.workspace_id = ? AND c.epoch_id = ? AND c.cursor > ?
            ORDER BY c.cursor
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, epochId)
            statement.setLong(4, afterCursor)
            statement.setInt(5, limit + 1)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getLong(1) to result.getString(2))
                }
            }
        }
        // A change row is the authenticated cursor authority. If its named
        // ciphertext object disappeared, an inner join would silently skip
        // the cursor and could let a client upload on top of incomplete
        // history. Preserve the gap as a blocking recovery condition.
        if (values.any { (_, encodedObject) -> encodedObject == null }) {
            return@use SyncV2PullRepositoryResult(
                changes = emptyList(),
                complete = false,
                error = "missing_remote_object",
            )
        }
        SyncV2PullRepositoryResult(
            values.take(limit).map { (cursor, encodedObject) ->
                SyncV2ChangeRecord(cursor, checkNotNull(encodedObject))
            },
            complete = values.size <= limit,
        )
    }

    fun frontier(userId: UUID, workspaceId: String, epochId: String): SyncV2EpochFrontierRecord? =
        scopedConnection(userId, workspaceId).use { connection ->
            if (loadEpochById(connection, userId, workspaceId, epochId) == null) return@use null
            connection.prepareStatement(
                """
                SELECT cursor, object_digest FROM someday_sync_v2_changes
                WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? ORDER BY cursor DESC LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, workspaceId)
                statement.setString(3, epochId)
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        SyncV2EpochFrontierRecord(0, "self-hosted:0:empty")
                    } else {
                        val cursor = result.getLong(1)
                        SyncV2EpochFrontierRecord(cursor, "self-hosted:$cursor:${result.getString(2)}")
                    }
                }
            }
        }

    fun status(userId: UUID, workspaceId: String): SyncV2StatusSnapshot =
        scopedConnection(userId, workspaceId).use { connection ->
            val epoch = loadActiveEpoch(connection, userId, workspaceId, false)
                ?: return@use SyncV2StatusSnapshot(null, 0, 0)
            val cursor = maximumCursor(connection, userId, workspaceId, epoch.metadata.epochId)
            val count = connection.prepareStatement(
                "SELECT COUNT(*) FROM someday_sync_v2_objects " +
                    "WHERE user_id = ? AND workspace_id = ? AND epoch_id = ?",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, workspaceId)
                statement.setString(3, epoch.metadata.epochId)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
            SyncV2StatusSnapshot(epoch.metadata.epochId, cursor, count)
        }

    private fun checkpointIsComplete(
        connection: Connection,
        userId: UUID,
        workspaceId: String,
        metadata: SyncV2EpochMetadataRecord,
    ): Boolean {
        val manifest = connection.prepareStatement(
            """
            SELECT chunk_count, total_object_count, chunk_refs_fingerprint
            FROM someday_sync_v2_checkpoint_manifests
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND checkpoint_id = ? AND checkpoint_digest = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, metadata.epochId)
            statement.setString(4, metadata.checkpointId)
            statement.setString(5, metadata.checkpointDigest)
            statement.executeQuery().use { result ->
                if (!result.next()) null else Triple(result.getInt(1), result.getInt(2), result.getString(3))
            }
        } ?: return false
        val refs = connection.prepareStatement(
            """
            SELECT chunk_index, chunk_id, chunk_digest, object_count, plaintext_bytes
            FROM someday_sync_v2_checkpoint_chunks
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND checkpoint_id = ? ORDER BY chunk_index
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, metadata.epochId)
            statement.setString(4, metadata.checkpointId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(SyncV2CheckpointChunkRefRecord(
                        result.getInt(1), result.getString(2), result.getString(3), result.getInt(4), result.getInt(5),
                    ))
                }
            }
        }
        return refs.indices.all { refs[it].chunkIndex == it } &&
            refs.size == manifest.first &&
            refs.sumOf { it.objectCount } == manifest.second &&
            chunkRefsFingerprint(refs) == manifest.third
    }

    private fun loadActiveEpoch(
        connection: Connection,
        userId: UUID,
        workspaceId: String,
        forUpdate: Boolean,
    ): SyncV2EpochRecord? = loadEpoch(connection, userId, workspaceId, null, forUpdate = forUpdate)

    private fun loadEpochById(
        connection: Connection,
        userId: UUID,
        workspaceId: String,
        epochId: String,
    ): SyncV2EpochRecord? = loadEpoch(connection, userId, workspaceId, epochId, forUpdate = false)

    private fun loadEpoch(
        connection: Connection,
        userId: UUID,
        workspaceId: String,
        epochId: String?,
        forUpdate: Boolean,
    ): SyncV2EpochRecord? {
        val conditions = buildList {
            add("user_id = ?")
            add("workspace_id = ?")
            if (epochId != null) add("epoch_id = ?")
        }.joinToString(" AND ")
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT contract_id, schema_set_version, epoch_id, pointer_digest, pointer_object_json,
                   semantic_protocol_version, minimum_writer_protocol_version, key_set_version,
                   remote_profile, metadata_privacy_mode, supported_offline_window_seconds,
                   checkpoint_id, checkpoint_digest
            FROM someday_sync_v2_epochs WHERE $conditions$lock
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            if (epochId != null) statement.setString(3, epochId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else SyncV2EpochRecord(
                    SyncV2EpochMetadataRecord(
                        result.getString("contract_id"),
                        result.getString("schema_set_version"),
                        result.getString("epoch_id"),
                        result.getString("pointer_digest"),
                        result.getInt("semantic_protocol_version"),
                        result.getInt("minimum_writer_protocol_version"),
                        result.getString("key_set_version"),
                        result.getString("remote_profile"),
                        result.getString("metadata_privacy_mode"),
                        result.getLong("supported_offline_window_seconds"),
                        result.getString("checkpoint_id"),
                        result.getString("checkpoint_digest"),
                        null,
                        null,
                    ),
                    result.getString("pointer_object_json"),
                )
            }
        }
    }

    private fun findMutation(
        connection: Connection,
        userId: UUID,
        workspaceId: String,
        epochId: String,
        mutationId: String,
    ): ExistingMutation? = connection.prepareStatement(
        """
        SELECT object_id, object_digest, cursor FROM someday_sync_v2_mutations
        WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND mutation_id = ? FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setString(2, workspaceId)
        statement.setString(3, epochId)
        statement.setString(4, mutationId)
        statement.executeQuery().use { result ->
            if (!result.next()) null else ExistingMutation(result.getString(1), result.getString(2), result.getLong(3))
        }
    }

    private fun findObject(
        connection: Connection,
        userId: UUID,
        workspaceId: String,
        epochId: String,
        objectId: String,
        forUpdate: Boolean,
    ): ExistingObject? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT object_type, object_digest, mutation_id, first_writer_device_id,
                   ciphertext_digest, encrypted_object_json, cursor
            FROM someday_sync_v2_objects
            WHERE user_id = ? AND workspace_id = ? AND epoch_id = ? AND object_id = ?$lock
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, epochId)
            statement.setString(4, objectId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else ExistingObject(
                    result.getString(1), result.getString(2), result.getString(3),
                    result.getObject(4, UUID::class.java), result.getString(5), result.getString(6), result.getLong(7),
                )
            }
        }
    }

    private fun maximumCursor(connection: Connection, userId: UUID, workspaceId: String, epochId: String): Long =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(cursor), 0) FROM someday_sync_v2_changes WHERE user_id = ? AND workspace_id = ? AND epoch_id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.setString(3, epochId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }

    private fun lockWorkspace(connection: Connection, userId: UUID, workspaceId: String) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
            statement.setString(1, "$userId\u001f$workspaceId")
            statement.executeQuery().close()
        }
    }

    private fun chunkRefsFingerprint(refs: List<SyncV2CheckpointChunkRefRecord>): String {
        val bytes = refs.joinToString("\u001f") {
            "${it.chunkIndex}\u001e${it.chunkId}\u001e${it.chunkDigest}\u001e${it.objectCount}\u001e${it.plaintextBytes}"
        }.encodeToByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    }

    private fun <T> transaction(
        userId: UUID,
        workspaceId: String,
        block: (Connection) -> T,
    ): T = connection().use { connection ->
        connection.autoCommit = false
        try {
            selectWorkspaceScope(connection, userId, workspaceId, local = true, ensureWorkspace = true)
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    private fun connection(): Connection =
        connections.connection()

    private fun scopedConnection(userId: UUID, workspaceId: String): Connection {
        val connection = connection()
        try {
            connection.autoCommit = false
            selectWorkspaceScope(connection, userId, workspaceId, local = false, ensureWorkspace = false)
            connection.commit()
            connection.autoCommit = true
            return connection
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            runCatching { connection.close() }
            throw failure
        }
    }

    /** Selects one fail-closed RLS namespace; only write transactions create its registry row. */
    private fun selectWorkspaceScope(
        connection: Connection,
        userId: UUID,
        workspaceId: String,
        local: Boolean,
        ensureWorkspace: Boolean,
    ) {
        require(WORKSPACE_ID.matches(workspaceId)) { "Invalid workspace scope." }
        connection.prepareStatement("SELECT set_config('someday.user_id', ?, ?)").use { statement ->
            statement.setString(1, userId.toString())
            statement.setBoolean(2, local)
            statement.executeQuery().close()
        }
        connection.prepareStatement("SELECT set_config('someday.workspace_id', ?, ?)").use { statement ->
            statement.setString(1, workspaceId)
            statement.setBoolean(2, local)
            statement.executeQuery().close()
        }
        if (ensureWorkspace) {
            connection.prepareStatement(
                "INSERT INTO someday_entity_workspaces(user_id, workspace_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, workspaceId)
                statement.executeUpdate()
            }
        }
    }

    private data class ExistingMutation(val objectId: String, val objectDigest: String, val cursor: Long)
    private data class ExistingObject(
        val objectType: String,
        val objectDigest: String,
        val mutationId: String,
        val writerDeviceId: UUID,
        val ciphertextDigest: String,
        val encodedObjectJson: String,
        val cursor: Long,
    ) {
        fun exactlyMatches(input: SyncV2ObjectInput): Boolean =
            objectType == input.objectType && objectDigest == input.objectDigest &&
                mutationId == input.mutationId && writerDeviceId == input.writerDeviceId &&
                ciphertextDigest == input.ciphertextDigest && encodedObjectJson == input.encodedObjectJson
    }
    private data class PreparedMutation(val input: SyncV2ObjectInput, val existingMutation: ExistingMutation?)

    private companion object {
        val WORKSPACE_ID = Regex("^workspace-[0-9a-f]{32}$")
    }
}

private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte) }
