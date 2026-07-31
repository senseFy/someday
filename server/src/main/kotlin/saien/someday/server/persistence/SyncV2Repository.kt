package saien.someday.server.persistence

import saien.someday.server.ServerConfig
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
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
    val rebootstrapRequired: Boolean = false,
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

class SyncV2Repository(private val config: ServerConfig) {
    fun loadEpoch(userId: UUID): SyncV2EpochRecord? = connection().use { connection ->
        loadActiveEpoch(connection, userId, false)
    }

    fun loadRetainedEpoch(userId: UUID, epochId: String): SyncV2EpochRecord? = connection().use { connection ->
        loadEpochById(connection, userId, epochId)
    }

    fun putCheckpointChunk(
        userId: UUID,
        input: SyncV2CheckpointChunkInput,
    ): SyncV2ImmutablePutRepositoryResult = transaction { connection ->
        lockWorkspace(connection, userId)
        val ref = input.ref
        val existing = connection.prepareStatement(
            """
            SELECT chunk_index, chunk_id, chunk_digest, object_count, plaintext_bytes
            FROM someday_sync_v2_checkpoint_chunks
            WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ?
              AND (chunk_index = ? OR chunk_id = ?)
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, input.epochId)
            statement.setString(3, input.checkpointId)
            statement.setInt(4, ref.chunkIndex)
            statement.setString(5, ref.chunkId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else SyncV2CheckpointChunkRefRecord(
                    result.getInt("chunk_index"),
                    result.getString("chunk_id"),
                    result.getString("chunk_digest"),
                    result.getInt("object_count"),
                    result.getInt("plaintext_bytes"),
                )
            }
        }
        when {
            existing != null && existing != ref -> SyncV2ImmutablePutRepositoryResult.Rejected("immutable_object_mismatch")
            existing != null -> {
                connection.prepareStatement(
                    """
                    UPDATE someday_sync_v2_checkpoint_chunks
                    SET encrypted_object_json = ?, updated_at = NOW()
                    WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ? AND chunk_index = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, input.encryptedObjectJson)
                    statement.setObject(2, userId)
                    statement.setString(3, input.epochId)
                    statement.setString(4, input.checkpointId)
                    statement.setInt(5, ref.chunkIndex)
                    statement.executeUpdate()
                }
                SyncV2ImmutablePutRepositoryResult.Stored(true)
            }
            else -> {
                connection.prepareStatement(
                    """
                    INSERT INTO someday_sync_v2_checkpoint_chunks(
                        user_id, epoch_id, checkpoint_id, chunk_index, chunk_id,
                        chunk_digest, object_count, plaintext_bytes, encrypted_object_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, input.epochId)
                    statement.setString(3, input.checkpointId)
                    statement.setInt(4, ref.chunkIndex)
                    statement.setString(5, ref.chunkId)
                    statement.setString(6, ref.chunkDigest)
                    statement.setInt(7, ref.objectCount)
                    statement.setInt(8, ref.plaintextBytes)
                    statement.setString(9, input.encryptedObjectJson)
                    statement.executeUpdate()
                }
                SyncV2ImmutablePutRepositoryResult.Stored(false)
            }
        }
    }

    fun putCheckpointManifest(
        userId: UUID,
        input: SyncV2CheckpointManifestInput,
    ): SyncV2ImmutablePutRepositoryResult = transaction { connection ->
        lockWorkspace(connection, userId)
        val fingerprint = chunkRefsFingerprint(input.chunks)
        val existing = connection.prepareStatement(
            """
            SELECT checkpoint_digest, chunk_count, total_object_count, chunk_refs_fingerprint
            FROM someday_sync_v2_checkpoint_manifests
            WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, input.epochId)
            statement.setString(3, input.checkpointId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else listOf(
                    result.getString("checkpoint_digest"),
                    result.getInt("chunk_count").toString(),
                    result.getInt("total_object_count").toString(),
                    result.getString("chunk_refs_fingerprint"),
                )
            }
        }
        val incoming = listOf(
            input.checkpointDigest,
            input.chunks.size.toString(),
            input.totalObjectCount.toString(),
            fingerprint,
        )
        when {
            existing != null && existing != incoming -> SyncV2ImmutablePutRepositoryResult.Rejected("immutable_object_mismatch")
            existing != null -> {
                connection.prepareStatement(
                    """
                    UPDATE someday_sync_v2_checkpoint_manifests
                    SET encrypted_object_json = ?, updated_at = NOW()
                    WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, input.encryptedObjectJson)
                    statement.setObject(2, userId)
                    statement.setString(3, input.epochId)
                    statement.setString(4, input.checkpointId)
                    statement.executeUpdate()
                }
                SyncV2ImmutablePutRepositoryResult.Stored(true)
            }
            else -> {
                connection.prepareStatement(
                    """
                    INSERT INTO someday_sync_v2_checkpoint_manifests(
                        user_id, epoch_id, checkpoint_id, checkpoint_digest,
                        chunk_count, total_object_count, chunk_refs_fingerprint, encrypted_object_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, input.epochId)
                    statement.setString(3, input.checkpointId)
                    statement.setString(4, input.checkpointDigest)
                    statement.setInt(5, input.chunks.size)
                    statement.setInt(6, input.totalObjectCount)
                    statement.setString(7, fingerprint)
                    statement.setString(8, input.encryptedObjectJson)
                    statement.executeUpdate()
                }
                SyncV2ImmutablePutRepositoryResult.Stored(false)
            }
        }
    }

    fun cleanupCheckpointDraft(
        userId: UUID,
        input: SyncV2CheckpointCleanupInput,
    ): SyncV2CheckpointCleanupRepositoryResult = transaction { connection ->
        lockWorkspace(connection, userId)
        val current = loadActiveEpoch(connection, userId, true)
            ?: return@transaction SyncV2CheckpointCleanupRepositoryResult.Retained(
                "checkpoint_still_publishable",
            )
        if (current.metadata.pointerDigest == input.previousPointerDigest) {
            return@transaction SyncV2CheckpointCleanupRepositoryResult.Retained(
                "checkpoint_still_publishable",
            )
        }
        if (loadEpochById(connection, userId, input.epochId) != null) {
            return@transaction SyncV2CheckpointCleanupRepositoryResult.Retained(
                "checkpoint_referenced",
            )
        }

        val expectedFingerprint = chunkRefsFingerprint(input.chunks)
        val manifest = connection.prepareStatement(
            """
            SELECT checkpoint_digest, chunk_count, chunk_refs_fingerprint
            FROM someday_sync_v2_checkpoint_manifests
            WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, input.epochId)
            statement.setString(3, input.checkpointId)
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
            WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ?
            ORDER BY chunk_index
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, input.epochId)
            statement.setString(3, input.checkpointId)
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
            WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, input.epochId)
            statement.setString(3, input.checkpointId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            DELETE FROM someday_sync_v2_checkpoint_chunks
            WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, input.epochId)
            statement.setString(3, input.checkpointId)
            statement.executeUpdate()
        }
        SyncV2CheckpointCleanupRepositoryResult.Deleted(alreadyAbsent)
    }

    fun compareAndSetEpoch(
        userId: UUID,
        expectedCurrentDigest: String?,
        metadata: SyncV2EpochMetadataRecord,
        pointerObjectJson: String,
    ): SyncV2PointerPublishRepositoryResult = transaction { connection ->
        lockWorkspace(connection, userId)
        val current = loadActiveEpoch(connection, userId, true)
        if (current?.metadata == metadata) {
            connection.prepareStatement(
                """
                UPDATE someday_sync_v2_epochs SET pointer_object_json = ?, updated_at = NOW()
                WHERE user_id = ? AND epoch_id = ? AND active
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, pointerObjectJson)
                statement.setObject(2, userId)
                statement.setString(3, metadata.epochId)
                statement.executeUpdate()
            }
            return@transaction SyncV2PointerPublishRepositoryResult.Published(true)
        }
        if (current?.metadata?.pointerDigest != expectedCurrentDigest) {
            return@transaction SyncV2PointerPublishRepositoryResult.CompareAndSetFailed(current)
        }
        if (current == null && expectedCurrentDigest != null) {
            return@transaction SyncV2PointerPublishRepositoryResult.Rejected("previous_epoch_mismatch")
        }
        // On an already initialized authority this field is the exact local
        // predecessor.  On an empty target it may name an authenticated epoch
        // from another authority as migration provenance; the target cannot
        // and must not pretend that external epoch existed in its own rows.
        if (current != null && metadata.previousEpochId != current.metadata.epochId) {
            return@transaction SyncV2PointerPublishRepositoryResult.Rejected("previous_epoch_mismatch")
        }
        if (current != null && metadata.previousEpochPointerDigest != current.metadata.pointerDigest) {
            return@transaction SyncV2PointerPublishRepositoryResult.Rejected("previous_epoch_pointer_mismatch")
        }
        if (loadEpochById(connection, userId, metadata.epochId) != null) {
            return@transaction SyncV2PointerPublishRepositoryResult.Rejected("epoch_id_reuse")
        }
        if (!checkpointIsComplete(connection, userId, metadata)) {
            return@transaction SyncV2PointerPublishRepositoryResult.Rejected("checkpoint_incomplete")
        }
        if (current != null) {
            connection.prepareStatement(
                """
                UPDATE someday_sync_v2_epochs
                SET active = FALSE, read_only_at = NOW(),
                    retain_until = NOW() + (supported_offline_window_seconds * INTERVAL '1 second'),
                    updated_at = NOW()
                WHERE user_id = ? AND epoch_id = ? AND active
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, current.metadata.epochId)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            """
            INSERT INTO someday_sync_v2_epochs(
                user_id, epoch_id, pointer_digest, pointer_object_json,
                contract_id, schema_set_version, semantic_protocol_version,
                minimum_writer_protocol_version, key_set_version, remote_profile,
                metadata_privacy_mode, supported_offline_window_seconds,
                checkpoint_id, checkpoint_digest, previous_epoch_id,
                previous_epoch_pointer_digest, active
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, metadata.epochId)
            statement.setString(3, metadata.pointerDigest)
            statement.setString(4, pointerObjectJson)
            statement.setString(5, metadata.contractId)
            statement.setString(6, metadata.schemaSetVersion)
            statement.setInt(7, metadata.semanticProtocolVersion)
            statement.setInt(8, metadata.minimumWriterProtocolVersion)
            statement.setString(9, metadata.keySetVersion)
            statement.setString(10, metadata.remoteProfile)
            statement.setString(11, metadata.metadataPrivacyMode)
            statement.setLong(12, metadata.supportedOfflineWindowSeconds)
            statement.setString(13, metadata.checkpointId)
            statement.setString(14, metadata.checkpointDigest)
            statement.setString(15, metadata.previousEpochId)
            statement.setString(16, metadata.previousEpochPointerDigest)
            statement.executeUpdate()
        }
        SyncV2PointerPublishRepositoryResult.Published(false)
    }

    fun loadCheckpointManifest(
        userId: UUID,
        epochId: String,
        checkpointId: String,
    ): String? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT encrypted_object_json FROM someday_sync_v2_checkpoint_manifests
            WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, epochId)
            statement.setString(3, checkpointId)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    fun loadCheckpointChunk(
        userId: UUID,
        epochId: String,
        checkpointId: String,
        chunkIndex: Int,
    ): String? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT encrypted_object_json FROM someday_sync_v2_checkpoint_chunks
            WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ? AND chunk_index = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, epochId)
            statement.setString(3, checkpointId)
            statement.setInt(4, chunkIndex)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    fun push(
        userId: UUID,
        deviceId: UUID,
        epochId: String,
        writerProtocolVersion: Int,
        objects: List<SyncV2ObjectInput>,
    ): SyncV2PushRepositoryResult = transaction { connection ->
        lockWorkspace(connection, userId)
        val epoch = loadActiveEpoch(connection, userId, true)
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
            val mutation = findMutation(connection, userId, epochId, input.mutationId)
            if (mutation != null && (mutation.objectId != input.objectId || mutation.objectDigest != input.objectDigest)) {
                return@transaction SyncV2PushRepositoryResult.Rejected("mutation_reuse_mismatch")
            }
            val objectValue = findObject(connection, userId, epochId, input.objectId, true)
            if (objectValue != null && (
                    objectValue.objectDigest != input.objectDigest ||
                        objectValue.objectType != input.objectType ||
                        objectValue.mutationId != input.mutationId
                    )
            ) {
                return@transaction SyncV2PushRepositoryResult.Rejected("immutable_object_mismatch")
            }
            if ((mutation == null) != (objectValue == null)) {
                return@transaction SyncV2PushRepositoryResult.Rejected("stored_object_invalid")
            }
            if (objectValue != null && !replicaCapacityAvailable(connection, userId, epochId, input)) {
                return@transaction SyncV2PushRepositoryResult.Rejected("repair_replica_set_invalid")
            }
            prepared += PreparedMutation(input, mutation)
        }

        val acknowledgements = prepared.map { value ->
            val input = value.input
            if (value.existingMutation != null) {
                upsertReplica(connection, userId, input, repair = false)
                SyncV2MutationAckRecord(input.mutationId, input.objectId, input.objectDigest, true)
            } else {
                connection.prepareStatement(
                    """
                    INSERT INTO someday_sync_v2_objects(
                        user_id, epoch_id, object_id, object_type, object_digest,
                        mutation_id, first_writer_device_id, cursor
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, epochId)
                    statement.setString(3, input.objectId)
                    statement.setString(4, input.objectType)
                    statement.setString(5, input.objectDigest)
                    statement.setString(6, input.mutationId)
                    statement.setObject(7, input.writerDeviceId)
                    statement.executeUpdate()
                }
                upsertReplica(connection, userId, input, repair = false)
                val cursor = connection.prepareStatement(
                    """
                    INSERT INTO someday_sync_v2_changes(
                        user_id, epoch_id, object_id, object_digest, mutation_id
                    ) VALUES (?, ?, ?, ?, ?) RETURNING cursor
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, epochId)
                    statement.setString(3, input.objectId)
                    statement.setString(4, input.objectDigest)
                    statement.setString(5, input.mutationId)
                    statement.executeQuery().use { result -> result.next(); result.getLong(1) }
                }
                connection.prepareStatement(
                    """
                    UPDATE someday_sync_v2_objects SET cursor = ?
                    WHERE user_id = ? AND epoch_id = ? AND object_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, cursor)
                    statement.setObject(2, userId)
                    statement.setString(3, epochId)
                    statement.setString(4, input.objectId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO someday_sync_v2_mutations(
                        user_id, epoch_id, mutation_id, object_id, object_digest, cursor
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, epochId)
                    statement.setString(3, input.mutationId)
                    statement.setString(4, input.objectId)
                    statement.setString(5, input.objectDigest)
                    statement.setLong(6, cursor)
                    statement.executeUpdate()
                }
                SyncV2MutationAckRecord(input.mutationId, input.objectId, input.objectDigest, false)
            }
        }
        SyncV2PushRepositoryResult.Accepted(acknowledgements)
    }

    fun pull(
        userId: UUID,
        epochId: String,
        afterCursor: Long,
        limit: Int,
    ): SyncV2PullRepositoryResult = connection().use { connection ->
        if (loadEpochById(connection, userId, epochId) == null) {
            return@use SyncV2PullRepositoryResult(emptyList(), true, rebootstrapRequired = true)
        }
        val maximum = maximumCursor(connection, userId, epochId)
        if (afterCursor > maximum) {
            // A cursor ahead of this retained epoch is rollback evidence, not
            // an offline-horizon event.  Rebootstrap may be offered only when
            // the requested read-only epoch has actually been collected.
            return@use SyncV2PullRepositoryResult(
                emptyList(),
                complete = true,
                error = "remote_rollback_detected",
            )
        }
        val values = connection.prepareStatement(
            """
            SELECT c.cursor, r.encrypted_object_json
            FROM someday_sync_v2_changes c
            LEFT JOIN LATERAL (
                SELECT encrypted_object_json
                FROM someday_sync_v2_object_replicas r
                WHERE r.user_id = c.user_id AND r.epoch_id = c.epoch_id AND r.object_id = c.object_id
                ORDER BY r.repair_replica ASC, r.updated_at DESC, r.writer_device_id ASC
                LIMIT 1
            ) r ON TRUE
            WHERE c.user_id = ? AND c.epoch_id = ? AND c.cursor > ?
            ORDER BY c.cursor
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, epochId)
            statement.setLong(3, afterCursor)
            statement.setInt(4, limit + 1)
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
                rebootstrapRequired = true,
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

    fun frontier(userId: UUID, epochId: String): SyncV2EpochFrontierRecord? = connection().use { connection ->
        if (loadEpochById(connection, userId, epochId) == null) return@use null
        val cursor = maximumCursor(connection, userId, epochId)
        val digest = connection.prepareStatement(
            """
            SELECT object_digest FROM someday_sync_v2_changes
            WHERE user_id = ? AND epoch_id = ? ORDER BY cursor DESC LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, epochId)
            statement.executeQuery().use { result ->
                if (result.next()) "self-hosted:$cursor:${result.getString(1)}" else "self-hosted:0:empty"
            }
        }
        SyncV2EpochFrontierRecord(cursor, digest)
    }

    fun fetchReplicas(
        userId: UUID,
        epochId: String,
        objectId: String,
        expectedObjectDigest: String,
    ): List<String> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT r.encrypted_object_json
            FROM someday_sync_v2_object_replicas r
            JOIN someday_sync_v2_objects o
              ON o.user_id = r.user_id AND o.epoch_id = r.epoch_id AND o.object_id = r.object_id
            WHERE r.user_id = ? AND r.epoch_id = ? AND r.object_id = ? AND o.object_digest = ?
            ORDER BY r.writer_device_id
            LIMIT 5
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, epochId)
            statement.setString(3, objectId)
            statement.setString(4, expectedObjectDigest)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } }
        }
    }

    fun publishRepairReplica(
        userId: UUID,
        deviceId: UUID,
        input: SyncV2ObjectInput,
    ): SyncV2ImmutablePutRepositoryResult = transaction { connection ->
        lockWorkspace(connection, userId)
        if (input.writerDeviceId != deviceId || loadEpochById(connection, userId, input.epochId) == null) {
            return@transaction SyncV2ImmutablePutRepositoryResult.Rejected("device_or_epoch_mismatch")
        }
        val stored = findObject(connection, userId, input.epochId, input.objectId, true)
            ?: return@transaction SyncV2ImmutablePutRepositoryResult.Rejected("missing_remote_object")
        if (stored.objectDigest != input.objectDigest || stored.objectType != input.objectType ||
            stored.mutationId != input.mutationId
        ) {
            return@transaction SyncV2ImmutablePutRepositoryResult.Rejected("repair_object_mismatch")
        }
        if (!replicaCapacityAvailable(connection, userId, input.epochId, input)) {
            return@transaction SyncV2ImmutablePutRepositoryResult.Rejected("repair_replica_set_invalid")
        }
        val replay = replicaExists(connection, userId, input)
        upsertReplica(connection, userId, input, repair = true)
        SyncV2ImmutablePutRepositoryResult.Stored(replay)
    }

    fun status(userId: UUID): SyncV2StatusSnapshot = connection().use { connection ->
        val epoch = loadActiveEpoch(connection, userId, false)
            ?: return@use SyncV2StatusSnapshot(null, 0, 0)
        val cursor = maximumCursor(connection, userId, epoch.metadata.epochId)
        val count = connection.prepareStatement(
            "SELECT COUNT(*) FROM someday_sync_v2_objects WHERE user_id = ? AND epoch_id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, epoch.metadata.epochId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
        SyncV2StatusSnapshot(epoch.metadata.epochId, cursor, count)
    }

    private fun checkpointIsComplete(
        connection: Connection,
        userId: UUID,
        metadata: SyncV2EpochMetadataRecord,
    ): Boolean {
        val manifest = connection.prepareStatement(
            """
            SELECT chunk_count, total_object_count, chunk_refs_fingerprint
            FROM someday_sync_v2_checkpoint_manifests
            WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ? AND checkpoint_digest = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, metadata.epochId)
            statement.setString(3, metadata.checkpointId)
            statement.setString(4, metadata.checkpointDigest)
            statement.executeQuery().use { result ->
                if (!result.next()) null else Triple(result.getInt(1), result.getInt(2), result.getString(3))
            }
        } ?: return false
        val refs = connection.prepareStatement(
            """
            SELECT chunk_index, chunk_id, chunk_digest, object_count, plaintext_bytes
            FROM someday_sync_v2_checkpoint_chunks
            WHERE user_id = ? AND epoch_id = ? AND checkpoint_id = ? ORDER BY chunk_index
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, metadata.epochId)
            statement.setString(3, metadata.checkpointId)
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

    private fun loadActiveEpoch(connection: Connection, userId: UUID, forUpdate: Boolean): SyncV2EpochRecord? =
        loadEpoch(connection, userId, null, activeOnly = true, forUpdate = forUpdate)

    private fun loadEpochById(connection: Connection, userId: UUID, epochId: String): SyncV2EpochRecord? =
        loadEpoch(connection, userId, epochId, activeOnly = false, forUpdate = false)

    private fun loadEpoch(
        connection: Connection,
        userId: UUID,
        epochId: String?,
        activeOnly: Boolean,
        forUpdate: Boolean,
    ): SyncV2EpochRecord? {
        val conditions = buildList {
            add("user_id = ?")
            if (epochId != null) add("epoch_id = ?")
            if (activeOnly) add("active")
        }.joinToString(" AND ")
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT contract_id, schema_set_version, epoch_id, pointer_digest, pointer_object_json,
                   semantic_protocol_version, minimum_writer_protocol_version, key_set_version,
                   remote_profile, metadata_privacy_mode, supported_offline_window_seconds,
                   checkpoint_id, checkpoint_digest, previous_epoch_id, previous_epoch_pointer_digest
            FROM someday_sync_v2_epochs WHERE $conditions$lock
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            if (epochId != null) statement.setString(2, epochId)
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
                        result.getString("previous_epoch_id"),
                        result.getString("previous_epoch_pointer_digest"),
                    ),
                    result.getString("pointer_object_json"),
                )
            }
        }
    }

    private fun findMutation(
        connection: Connection,
        userId: UUID,
        epochId: String,
        mutationId: String,
    ): ExistingMutation? = connection.prepareStatement(
        """
        SELECT object_id, object_digest, cursor FROM someday_sync_v2_mutations
        WHERE user_id = ? AND epoch_id = ? AND mutation_id = ? FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setString(2, epochId)
        statement.setString(3, mutationId)
        statement.executeQuery().use { result ->
            if (!result.next()) null else ExistingMutation(result.getString(1), result.getString(2), result.getLong(3))
        }
    }

    private fun findObject(
        connection: Connection,
        userId: UUID,
        epochId: String,
        objectId: String,
        forUpdate: Boolean,
    ): ExistingObject? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT object_type, object_digest, mutation_id, cursor
            FROM someday_sync_v2_objects
            WHERE user_id = ? AND epoch_id = ? AND object_id = ?$lock
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, epochId)
            statement.setString(3, objectId)
            statement.executeQuery().use { result ->
                if (!result.next()) null else ExistingObject(
                    result.getString(1), result.getString(2), result.getString(3), result.getLong(4),
                )
            }
        }
    }

    private fun replicaCapacityAvailable(
        connection: Connection,
        userId: UUID,
        epochId: String,
        input: SyncV2ObjectInput,
    ): Boolean = connection.prepareStatement(
        """
        SELECT COUNT(*) < 4 OR BOOL_OR(writer_device_id = ?)
        FROM someday_sync_v2_object_replicas
        WHERE user_id = ? AND epoch_id = ? AND object_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, input.writerDeviceId)
        statement.setObject(2, userId)
        statement.setString(3, epochId)
        statement.setString(4, input.objectId)
        statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
    }

    private fun replicaExists(connection: Connection, userId: UUID, input: SyncV2ObjectInput): Boolean =
        connection.prepareStatement(
            """
            SELECT ciphertext_digest = ? FROM someday_sync_v2_object_replicas
            WHERE user_id = ? AND epoch_id = ? AND object_id = ? AND writer_device_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, input.ciphertextDigest)
            statement.setObject(2, userId)
            statement.setString(3, input.epochId)
            statement.setString(4, input.objectId)
            statement.setObject(5, input.writerDeviceId)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }

    private fun upsertReplica(connection: Connection, userId: UUID, input: SyncV2ObjectInput, repair: Boolean) {
        connection.prepareStatement(
            """
            INSERT INTO someday_sync_v2_object_replicas(
                user_id, epoch_id, object_id, object_digest, mutation_id,
                writer_device_id, ciphertext_digest, encrypted_object_json, repair_replica
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, epoch_id, object_id, writer_device_id) DO UPDATE SET
                object_digest = EXCLUDED.object_digest,
                mutation_id = EXCLUDED.mutation_id,
                ciphertext_digest = EXCLUDED.ciphertext_digest,
                encrypted_object_json = EXCLUDED.encrypted_object_json,
                repair_replica = someday_sync_v2_object_replicas.repair_replica AND EXCLUDED.repair_replica,
                updated_at = NOW()
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, input.epochId)
            statement.setString(3, input.objectId)
            statement.setString(4, input.objectDigest)
            statement.setString(5, input.mutationId)
            statement.setObject(6, input.writerDeviceId)
            statement.setString(7, input.ciphertextDigest)
            statement.setString(8, input.encodedObjectJson)
            statement.setBoolean(9, repair)
            statement.executeUpdate()
        }
    }

    private fun maximumCursor(connection: Connection, userId: UUID, epochId: String): Long =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(cursor), 0) FROM someday_sync_v2_changes WHERE user_id = ? AND epoch_id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, epochId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }

    private fun lockWorkspace(connection: Connection, userId: UUID) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
            statement.setString(1, userId.toString())
            statement.executeQuery().close()
        }
    }

    private fun chunkRefsFingerprint(refs: List<SyncV2CheckpointChunkRefRecord>): String {
        val bytes = refs.joinToString("\u001f") {
            "${it.chunkIndex}\u001e${it.chunkId}\u001e${it.chunkDigest}\u001e${it.objectCount}\u001e${it.plaintextBytes}"
        }.encodeToByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    }

    private fun <T> transaction(block: (Connection) -> T): T = connection().use { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    private fun connection(): Connection =
        DriverManager.getConnection(config.databaseUrl, config.databaseUser, config.databasePassword)

    private data class ExistingMutation(val objectId: String, val objectDigest: String, val cursor: Long)
    private data class ExistingObject(
        val objectType: String,
        val objectDigest: String,
        val mutationId: String,
        val cursor: Long,
    )
    private data class PreparedMutation(val input: SyncV2ObjectInput, val existingMutation: ExistingMutation?)
}

private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte) }
