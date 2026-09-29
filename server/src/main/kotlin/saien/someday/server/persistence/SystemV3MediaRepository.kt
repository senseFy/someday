package saien.someday.server.persistence

import saien.someday.server.ServerConfig
import saien.someday.server.auth.AccountAccess
import saien.someday.server.auth.AccountRequestContext
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
        request: AccountRequestContext,
        workspaceId: String,
        mediaId: String,
        ciphertextSha256: String,
        bytes: ByteArray,
    ): SystemV3MediaPutResult = transaction(request, workspaceId) { connection, account ->
        val userId = account.userId
        val deviceId = checkNotNull(account.deviceId)
        lockAccountQuota(connection, userId)
        ensureWorkspace(connection, account, workspaceId)
        val requested = SystemV3MediaObjectRecord(workspaceId, mediaId, bytes.size, ciphertextSha256)
        val existing = loadObject(connection, userId, workspaceId, mediaId)
        if (existing != null && existing != requested) {
            return@transaction SystemV3MediaPutResult.Rejected("immutable_media_mismatch")
        }
        val key = MediaBlobKey(userId, workspaceId, mediaId, account.incarnation)
        if (existing == null) {
            if (!canIncreaseAccountQuota(connection, account, workspaceId, bytes.size.toLong())) {
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
        request: AccountRequestContext,
        workspaceId: String,
        mediaId: String,
    ): SystemV3MediaReadResult<SystemV3MediaObjectRecord> {
        val captured = captureObject(request, workspaceId, mediaId) ?: return SystemV3MediaReadResult.Missing
        val blob = blobStore.head(captured.key)
        return if (blob == null || blob.bytes != captured.record.ciphertextBytes.toLong() ||
            blob.sha256 != captured.record.ciphertextSha256
        ) {
            recheckAdmission(request, workspaceId)
            SystemV3MediaReadResult.Corrupt
        } else {
            SystemV3MediaReadResult.Found(captured.record)
        }
    }

    fun readObject(
        request: AccountRequestContext,
        workspaceId: String,
        mediaId: String,
    ): SystemV3MediaReadResult<SystemV3MediaObjectValue<SystemV3MediaObjectRecord>> {
        val captured = captureObject(request, workspaceId, mediaId) ?: return SystemV3MediaReadResult.Missing
        val head = blobStore.head(captured.key)
        if (head == null || head.bytes != captured.record.ciphertextBytes.toLong() ||
            head.sha256 != captured.record.ciphertextSha256
        ) {
            recheckAdmission(request, workspaceId)
            return SystemV3MediaReadResult.Corrupt
        }
        val blob = blobStore.read(captured.key, MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES)
        return if (blob == null || blob.metadata.bytes != captured.record.ciphertextBytes.toLong() ||
            blob.metadata.sha256 != captured.record.ciphertextSha256
        ) {
            recheckAdmission(request, workspaceId)
            SystemV3MediaReadResult.Corrupt
        } else {
            SystemV3MediaReadResult.Found(SystemV3MediaObjectValue(captured.record, blob.bytes))
        }
    }

    /** Admission and metadata capture finish before any HEAD/GET blob IO. */
    private fun captureObject(
        request: AccountRequestContext,
        workspaceId: String,
        mediaId: String,
    ): CapturedMedia? = transaction(request, workspaceId) { connection, account ->
        loadObject(connection, account.userId, workspaceId, mediaId)?.let { record ->
            CapturedMedia(account, record, MediaBlobKey(account.userId, workspaceId, mediaId, account.incarnation))
        }
    }

    private fun recheckAdmission(request: AccountRequestContext, workspaceId: String) {
        transaction(request, workspaceId) { _, _ -> Unit }
    }

    private data class CapturedMedia(
        val account: AdmittedAccount,
        val record: SystemV3MediaObjectRecord,
        val key: MediaBlobKey,
    )

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
        account: AdmittedAccount,
        workspaceId: String,
        deltaBytes: Long,
    ): Boolean {
        val userId = account.userId
        selectScope(connection, userId, "*", local = true)
        val current = try {
            connection.prepareStatement(
                """
                SELECT COALESCE(SUM(media.ciphertext_bytes), 0)
                FROM someday_media_v3_objects media
                JOIN someday_entity_workspaces workspace
                  ON workspace.user_id = media.user_id AND workspace.workspace_id = media.workspace_id
                WHERE media.user_id = ? AND workspace.data_incarnation = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, account.incarnation)
                statement.executeQuery().use { result -> check(result.next()); result.getLong(1) }
            }
        } finally {
            selectScope(connection, userId, workspaceId, local = true)
        }
        return current <= config.mediaQuotaBytes && deltaBytes <= config.mediaQuotaBytes - current
    }

    private fun ensureWorkspace(connection: Connection, account: AdmittedAccount, workspaceId: String) {
        require(workspaceId.matches(Regex("^workspace-[0-9a-f]{32}$")))
        connection.prepareStatement(
            "INSERT INTO someday_entity_workspaces(user_id, workspace_id, data_incarnation) " +
                "VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        ).use { statement ->
            statement.setObject(1, account.userId)
            statement.setString(2, workspaceId)
            statement.setObject(3, account.incarnation)
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
        request: AccountRequestContext,
        workspaceId: String,
        block: (Connection, AdmittedAccount) -> T,
    ): T = AccountAdmission.transaction(
        connections,
        listOf(request.userId),
        scope = { connection -> selectScope(connection, request.userId, workspaceId, local = true) },
    ) { connection ->
        val account = AccountAdmission.admit(connection, request, AccountAccess.SYNC, workspaceId)
        block(connection, account)
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
