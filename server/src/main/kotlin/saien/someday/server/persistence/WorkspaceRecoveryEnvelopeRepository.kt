package saien.someday.server.persistence

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import saien.someday.server.ServerConfig

data class WorkspaceRecoveryEnvelopeRecord(
    val userId: UUID,
    val workspaceId: String,
    val keyFingerprint: String,
    val envelopeJson: String,
    val envelopeDigest: String,
    val revision: Long,
    val updatedAt: Instant,
) {
    override fun toString(): String =
        "WorkspaceRecoveryEnvelopeRecord(userId=$userId, workspaceId=$workspaceId, " +
            "keyFingerprint=$keyFingerprint, envelopeDigest=$envelopeDigest, revision=$revision, " +
            "updatedAt=$updatedAt, envelopeJson=<redacted>)"
}

data class WorkspaceRecoveryEnvelopeInput(
    val workspaceId: String,
    val keyFingerprint: String,
    val envelopeJson: String,
    val envelopeDigest: String,
    val expectedRevision: Long?,
) {
    override fun toString(): String =
        "WorkspaceRecoveryEnvelopeInput(workspaceId=$workspaceId, keyFingerprint=$keyFingerprint, " +
            "envelopeDigest=$envelopeDigest, expectedRevision=$expectedRevision, envelopeJson=<redacted>)"
}

sealed interface WorkspaceRecoveryEnvelopePutResult {
    data class Stored(
        val record: WorkspaceRecoveryEnvelopeRecord,
        val created: Boolean,
        val idempotentReplay: Boolean,
    ) : WorkspaceRecoveryEnvelopePutResult

    data object Conflict : WorkspaceRecoveryEnvelopePutResult
    data object WorkspaceNotInitialized : WorkspaceRecoveryEnvelopePutResult
}

class WorkspaceRecoveryEnvelopeRepository(
    config: ServerConfig,
    private val connections: DatabaseConnectionProvider = directDatabaseConnectionProvider(config),
) {
    fun load(userId: UUID): WorkspaceRecoveryEnvelopeRecord? =
        scopedConnection(userId).use { connection ->
            select(connection, userId, forUpdate = false)
        }

    fun put(
        userId: UUID,
        deviceId: UUID,
        input: WorkspaceRecoveryEnvelopeInput,
    ): WorkspaceRecoveryEnvelopePutResult = transaction(userId) { connection ->
        lockWorkspaceRecoveryAccount(connection, userId)
        if (!workspaceHasActiveEpoch(connection, userId, input.workspaceId)) {
            return@transaction WorkspaceRecoveryEnvelopePutResult.WorkspaceNotInitialized
        }
        val existing = select(connection, userId, forUpdate = true)
        if (existing != null) {
            return@transaction updateOrReplay(connection, userId, deviceId, input, existing)
        }
        if (input.expectedRevision != null) {
            return@transaction WorkspaceRecoveryEnvelopePutResult.Conflict
        }

        val inserted = connection.prepareStatement(
            """
            INSERT INTO workspace_recovery_envelopes (
                user_id, workspace_id, key_fingerprint, envelope_json, envelope_digest,
                revision, created_by_device_id, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, 1, ?, ?)
            ON CONFLICT (user_id) DO NOTHING
            RETURNING user_id, workspace_id, key_fingerprint, envelope_json,
                      envelope_digest, revision, updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, input.workspaceId)
            statement.setString(3, input.keyFingerprint)
            statement.setString(4, input.envelopeJson)
            statement.setString(5, input.envelopeDigest)
            statement.setObject(6, deviceId)
            statement.setObject(7, deviceId)
            statement.executeQuery().use { result ->
                if (result.next()) result.toWorkspaceRecoveryEnvelopeRecord() else null
            }
        }
        if (inserted != null) {
            return@transaction WorkspaceRecoveryEnvelopePutResult.Stored(
                record = inserted,
                created = true,
                idempotentReplay = false,
            )
        }

        // An absent-row race is resolved by the unique user key. Re-read the
        // winner under lock and apply the same replay/conflict rules.
        val concurrent = checkNotNull(select(connection, userId, forUpdate = true))
        updateOrReplay(connection, userId, deviceId, input, concurrent)
    }

    private fun workspaceHasActiveEpoch(connection: Connection, userId: UUID, workspaceId: String): Boolean =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM someday_sync_v2_epochs WHERE user_id = ? AND workspace_id = ?)",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, workspaceId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getBoolean(1)
            }
        }

    private fun updateOrReplay(
        connection: Connection,
        userId: UUID,
        deviceId: UUID,
        input: WorkspaceRecoveryEnvelopeInput,
        existing: WorkspaceRecoveryEnvelopeRecord,
    ): WorkspaceRecoveryEnvelopePutResult {
        if (existing.matches(input)) {
            return WorkspaceRecoveryEnvelopePutResult.Stored(
                record = existing,
                created = false,
                idempotentReplay = true,
            )
        }
        if (input.expectedRevision != existing.revision) {
            return WorkspaceRecoveryEnvelopePutResult.Conflict
        }

        val updated = connection.prepareStatement(
            """
            UPDATE workspace_recovery_envelopes
            SET workspace_id = ?,
                key_fingerprint = ?,
                envelope_json = ?,
                envelope_digest = ?,
                revision = revision + 1,
                updated_by_device_id = ?,
                updated_at = NOW()
            WHERE user_id = ? AND revision = ?
            RETURNING user_id, workspace_id, key_fingerprint, envelope_json,
                      envelope_digest, revision, updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, input.workspaceId)
            statement.setString(2, input.keyFingerprint)
            statement.setString(3, input.envelopeJson)
            statement.setString(4, input.envelopeDigest)
            statement.setObject(5, deviceId)
            statement.setObject(6, userId)
            statement.setLong(7, existing.revision)
            statement.executeQuery().use { result ->
                check(result.next()) { "Locked recovery envelope changed before its revision update." }
                result.toWorkspaceRecoveryEnvelopeRecord()
            }
        }
        return WorkspaceRecoveryEnvelopePutResult.Stored(
            record = updated,
            created = false,
            idempotentReplay = false,
        )
    }

    private fun WorkspaceRecoveryEnvelopeRecord.matches(input: WorkspaceRecoveryEnvelopeInput): Boolean =
        workspaceId == input.workspaceId &&
            keyFingerprint == input.keyFingerprint &&
            envelopeJson == input.envelopeJson &&
            envelopeDigest == input.envelopeDigest

    private fun select(
        connection: Connection,
        userId: UUID,
        forUpdate: Boolean,
    ): WorkspaceRecoveryEnvelopeRecord? {
        val lockClause = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT user_id, workspace_id, key_fingerprint, envelope_json,
                   envelope_digest, revision, updated_at
            FROM workspace_recovery_envelopes
            WHERE user_id = ?$lockClause
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result ->
                if (result.next()) result.toWorkspaceRecoveryEnvelopeRecord() else null
            }
        }
    }

    private fun <T> transaction(userId: UUID, block: (Connection) -> T): T =
        connection().use { connection ->
            // selectAccountScope executes SQL before the account advisory
            // lock. READ COMMITTED guarantees that a waiter sees the lock
            // holder's committed epoch or recovery pointer afterward even if
            // the database default is REPEATABLE READ.
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            try {
                selectAccountScope(connection, userId, local = true)
                val result = block(connection)
                connection.commit()
                result
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }

    private fun connection(): Connection = connections.connection()

    private fun scopedConnection(userId: UUID): Connection =
        connection().also { connection ->
            try {
                selectAccountScope(connection, userId, local = false)
            } catch (failure: Throwable) {
                runCatching { connection.close() }
                throw failure
            }
        }

    /**
     * Selects the account's single current recovery pointer. Workspace is
     * intentionally wildcarded so a CAS rotation can move that pointer from
     * one initialized workspace to another without losing sight of the old row.
     */
    private fun selectAccountScope(connection: Connection, userId: UUID, local: Boolean) {
        connection.prepareStatement("SELECT set_config('someday.user_id', ?, ?)").use { statement ->
            statement.setString(1, userId.toString())
            statement.setBoolean(2, local)
            statement.executeQuery().close()
        }
        connection.prepareStatement("SELECT set_config('someday.workspace_id', '*', ?)").use { statement ->
            statement.setBoolean(1, local)
            statement.executeQuery().close()
        }
    }

    private fun ResultSet.toWorkspaceRecoveryEnvelopeRecord(): WorkspaceRecoveryEnvelopeRecord =
        WorkspaceRecoveryEnvelopeRecord(
            userId = getObject("user_id", UUID::class.java),
            workspaceId = getString("workspace_id"),
            keyFingerprint = getString("key_fingerprint"),
            envelopeJson = getString("envelope_json"),
            envelopeDigest = getString("envelope_digest"),
            revision = getLong("revision"),
            updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )
}
