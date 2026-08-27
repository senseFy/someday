package saien.someday.server.persistence

import saien.someday.server.ServerConfig
import saien.someday.server.media.MediaBlobKey
import saien.someday.server.media.MediaBlobPutResult
import saien.someday.server.media.MediaBlobStore
import java.sql.Connection
import java.util.UUID

const val MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES: Int = 4 * 1024 * 1024 + 4 * 1024 + 4 + 40

data class SystemV3MediaObjectRecord(
    val workspaceId: String,
    val mediaId: String,
    val ciphertextBytes: Int,
    val ciphertextSha256: String,
)

data class SystemV3MediaObjectValue<T>(val record: T, val bytes: ByteArray)

sealed interface SystemV3MediaPutResult {
    data class Stored(val idempotentReplay: Boolean) : SystemV3MediaPutResult
    data class Rejected(val error: String) : SystemV3MediaPutResult
}

sealed interface SystemV3MediaReadResult<out T> {
    data class Found<T>(val value: T) : SystemV3MediaReadResult<T>
    data object Missing : SystemV3MediaReadResult<Nothing>
    data object Corrupt : SystemV3MediaReadResult<Nothing>
}

class SystemV3MediaRepository(
    private val config: ServerConfig,
    private val blobStore: MediaBlobStore,
    private val connections: DatabaseConnectionProvider = directDatabaseConnectionProvider(config),
) {
    fun putObject(
        userId: UUID,
        workspaceId: String,
        deviceId: UUID,
        mediaId: String,
        ciphertextSha256: String,
        bytes: ByteArray,
    ): SystemV3MediaPutResult = transaction(userId, workspaceId) { connection ->
        lockAccountQuota(connection, userId)
        ensureWorkspace(connection, userId, workspaceId)
        val requested = SystemV3MediaObjectRecord(workspaceId, mediaId, bytes.size, ciphertextSha256)
        val existing = loadObject(connection, userId, workspaceId, mediaId)
        if (existing != null && existing != requested) {
            return@transaction SystemV3MediaPutResult.Rejected("immutable_media_mismatch")
        }
        val key = MediaBlobKey(userId, workspaceId, mediaId)
        if (existing == null) {
            if (!canIncreaseAccountQuota(connection, userId, workspaceId, bytes.size.toLong())) {
                return@transaction SystemV3MediaPutResult.Rejected("media_quota_exceeded")
            }
        }
        when (blobStore.putImmutable(key, bytes, ciphertextSha256)) {
            MediaBlobPutResult.ImmutableMismatch ->
                return@transaction SystemV3MediaPutResult.Rejected("immutable_media_mismatch")
            is MediaBlobPutResult.Stored -> Unit
        }
        if (existing == null) {
            connection.prepareStatement(
                """
                INSERT INTO someday_media_v3_objects(
                    user_id, workspace_id, media_id, ciphertext_bytes,
                    ciphertext_sha256, uploaded_by_device_id
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, workspaceId)
                statement.setString(3, mediaId)
                statement.setInt(4, bytes.size)
                statement.setString(5, ciphertextSha256)
                statement.setObject(6, deviceId)
                statement.executeUpdate()
            }
        }
        SystemV3MediaPutResult.Stored(idempotentReplay = existing != null)
    }

    fun headObject(
        userId: UUID,
        workspaceId: String,
        mediaId: String,
    ): SystemV3MediaReadResult<SystemV3MediaObjectRecord> {
        val record = scopedConnection(userId, workspaceId).use { connection ->
            loadObject(connection, userId, workspaceId, mediaId)
        } ?: return SystemV3MediaReadResult.Missing
        val blob = blobStore.head(MediaBlobKey(userId, workspaceId, mediaId))
            ?: return SystemV3MediaReadResult.Corrupt
        return if (blob.bytes != record.ciphertextBytes.toLong() || blob.sha256 != record.ciphertextSha256) {
            SystemV3MediaReadResult.Corrupt
        } else {
            SystemV3MediaReadResult.Found(record)
        }
    }

    fun readObject(
        userId: UUID,
        workspaceId: String,
        mediaId: String,
    ): SystemV3MediaReadResult<SystemV3MediaObjectValue<SystemV3MediaObjectRecord>> =
        when (val head = headObject(userId, workspaceId, mediaId)) {
            is SystemV3MediaReadResult.Found -> {
                val blob = blobStore.read(
                    MediaBlobKey(userId, workspaceId, mediaId),
                    MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES,
                ) ?: return SystemV3MediaReadResult.Corrupt
                if (blob.metadata.bytes != head.value.ciphertextBytes.toLong() ||
                    blob.metadata.sha256 != head.value.ciphertextSha256
                ) SystemV3MediaReadResult.Corrupt
                else SystemV3MediaReadResult.Found(SystemV3MediaObjectValue(head.value, blob.bytes))
            }
            SystemV3MediaReadResult.Missing -> SystemV3MediaReadResult.Missing
            SystemV3MediaReadResult.Corrupt -> SystemV3MediaReadResult.Corrupt
        }

    private fun loadObject(
        connection: Connection,
        userId: UUID,
        workspaceId: String,
        mediaId: String,
    ): SystemV3MediaObjectRecord? = connection.prepareStatement(
        """
        SELECT ciphertext_bytes, ciphertext_sha256
        FROM someday_media_v3_objects
        WHERE user_id = ? AND workspace_id = ? AND media_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, userId)
        statement.setString(2, workspaceId)
        statement.setString(3, mediaId)
        statement.executeQuery().use { result ->
            if (!result.next()) null else SystemV3MediaObjectRecord(
                workspaceId,
                mediaId,
                result.getInt("ciphertext_bytes"),
                result.getString("ciphertext_sha256"),
            )
        }
    }

    private fun canIncreaseAccountQuota(
        connection: Connection,
        userId: UUID,
        workspaceId: String,
        deltaBytes: Long,
    ): Boolean {
        selectScope(connection, userId, "*", local = true)
        val current = try {
            connection.prepareStatement(
                "SELECT COALESCE(SUM(ciphertext_bytes), 0) FROM someday_media_v3_objects WHERE user_id = ?",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { result -> check(result.next()); result.getLong(1) }
            }
        } finally {
            selectScope(connection, userId, workspaceId, local = true)
        }
        return current <= config.mediaQuotaBytes && deltaBytes <= config.mediaQuotaBytes - current
    }

    private fun ensureWorkspace(connection: Connection, userId: UUID, workspaceId: String) {
        require(workspaceId.matches(Regex("^workspace-[0-9a-f]{32}$")))
        connection.prepareStatement(
            "INSERT INTO someday_entity_workspaces(user_id, workspace_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.executeUpdate()
        }
    }

    private fun lockAccountQuota(connection: Connection, userId: UUID) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
            statement.setString(1, userId.toString())
            statement.executeQuery().close()
        }
    }

    private fun <T> transaction(
        userId: UUID,
        workspaceId: String,
        block: (Connection) -> T,
    ): T = connection().use { connection ->
        connection.autoCommit = false
        try {
            selectScope(connection, userId, workspaceId, local = true)
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    private fun connection(): Connection =
        connections.connection()

    private fun scopedConnection(userId: UUID, workspaceId: String): Connection =
        connection().also { connection ->
            try {
                selectScope(connection, userId, workspaceId, local = false)
            } catch (failure: Throwable) {
                runCatching { connection.close() }
                throw failure
            }
        }

    private fun selectScope(
        connection: Connection,
        userId: UUID,
        workspaceId: String,
        local: Boolean,
    ) {
        require(workspaceId == "*" || workspaceId.matches(Regex("^workspace-[0-9a-f]{32}$")))
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
    }
}
