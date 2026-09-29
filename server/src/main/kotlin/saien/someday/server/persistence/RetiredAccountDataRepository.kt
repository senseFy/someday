package saien.someday.server.persistence

import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import saien.someday.server.ServerConfig
import saien.someday.server.ServerMediaStorage

data class RetiredAccountTarget(
    val userId: UUID,
    val incarnation: UUID,
    val storageLayout: String,
    val retiredAt: Instant,
    val previouslyReclaimedAt: Instant?,
    val verificationContext: UUID,
    val databaseIdentity: String,
    val revalidationRequired: Boolean,
)

data class RetiredAccountAudit(
    val target: RetiredAccountTarget,
    val auditId: UUID,
    val previouslyReclaimedAt: Instant?,
)

enum class ReclamationBackend { FILESYSTEM, S3 }

data class MediaReclamationEvidence(
    val backend: ReclamationBackend,
    val scanStartedAt: Instant,
    val scanCompletedAt: Instant,
    val completeEmpty: Boolean,
)

class RetiredAccountVerificationChanged : IllegalStateException("Retired-account verification context changed.")
class MediaReclamationRevalidationRequired : IllegalStateException("Operator revalidation is required before media certification.")

interface RetiredAccountMaintenance {
    fun databaseIdentity(): String
    fun inspectTarget(userId: UUID, incarnation: UUID): RetiredAccountTarget?
    fun beginAudit(target: RetiredAccountTarget, operatorRevalidated: Boolean = false): RetiredAccountAudit
    fun validateAudit(audit: RetiredAccountAudit)
    fun purgeSqlBatch(audit: RetiredAccountAudit, batchSize: Int = 500): Int
    fun recordAssumptionViolation(audit: RetiredAccountAudit)
    fun certifyMediaReclaimed(audit: RetiredAccountAudit, evidence: MediaReclamationEvidence): Instant
    fun invalidateAfterRestore(expectedDatabaseIdentity: String)
}

/** Operator-only SQL boundary. Blob listing/deletion never runs inside these transactions. */
class RetiredAccountDataRepository(
    private val config: ServerConfig,
    private val connections: DatabaseConnectionProvider = directDatabaseConnectionProvider(config),
    private val clock: Clock = Clock.systemUTC(),
) : RetiredAccountMaintenance {
    override fun databaseIdentity(): String = connections.connection().use(::readDatabaseIdentity)

    override fun inspectTarget(userId: UUID, incarnation: UUID): RetiredAccountTarget? =
        AccountAdmission.transaction(connections, listOf(userId)) { connection ->
            readTarget(connection, userId, incarnation)
        }

    override fun beginAudit(target: RetiredAccountTarget, operatorRevalidated: Boolean): RetiredAccountAudit =
        AccountAdmission.transaction(connections, listOf(target.userId), AccountLockMode.EXCLUSIVE) { connection ->
            val current = requireTarget(connection, target)
            if (current.revalidationRequired && !operatorRevalidated) throw MediaReclamationRevalidationRequired()
            val auditId = UUID.randomUUID()
            connection.prepareStatement(
                """
                UPDATE someday_account_data_incarnations
                SET media_last_certified_at = CASE WHEN ? THEN NULL ELSE COALESCE(media_reclaimed_at, media_last_certified_at) END,
                    media_reclaimed_at = NULL, media_audit_id = ?, media_revalidation_required = FALSE
                WHERE user_id = ? AND incarnation = ? AND state = 'retired'
                """.trimIndent(),
            ).use { statement ->
                statement.setBoolean(1, operatorRevalidated)
                statement.setObject(2, auditId)
                statement.setObject(3, target.userId)
                statement.setObject(4, target.incarnation)
                check(statement.executeUpdate() == 1)
            }
            val previous = if (operatorRevalidated) null else current.previouslyReclaimedAt
            RetiredAccountAudit(current.copy(revalidationRequired = false, previouslyReclaimedAt = previous), auditId, previous)
        }

    override fun validateAudit(audit: RetiredAccountAudit) {
        AccountAdmission.transaction(connections, listOf(audit.target.userId)) { connection ->
            requireAudit(connection, audit)
        }
    }

    /** Deletes or scrubs at most [batchSize] rows, including credential children. */
    override fun purgeSqlBatch(audit: RetiredAccountAudit, batchSize: Int): Int {
        require(batchSize in 1..MAX_BATCH_SIZE)
        return AccountAdmission.transaction(
            connections, listOf(audit.target.userId), scope = { setMaintenanceScope(it, audit.target.userId) },
        ) { connection ->
            requireAudit(connection, audit)
            var remaining = batchSize
            for (table in WORKSPACE_CONTENT_TABLES) {
                if (remaining == 0) break
                remaining -= deleteBatch(connection, table, WORKSPACE_MATCH, audit.target, remaining)
            }
            if (remaining > 0) {
                remaining -= deleteBatch(
                    connection, "workspace_pairing_invites", "candidate.data_incarnation = ?", audit.target, remaining,
                )
            }
            if (remaining > 0) {
                remaining -= deleteBatch(
                    connection, "someday_refresh_tokens", REFRESH_SESSION_MATCH, audit.target, remaining,
                    userPredicate = "EXISTS (SELECT 1 FROM someday_sessions owner WHERE owner.id = candidate.session_id AND owner.user_id = ?)",
                    outerUserPredicate = "EXISTS (SELECT 1 FROM someday_sessions owner WHERE owner.id = target.session_id AND owner.user_id = ?)",
                )
            }
            if (remaining > 0) {
                remaining -= deleteBatch(
                    connection, "someday_sessions",
                    "candidate.data_incarnation = ? AND NOT EXISTS (SELECT 1 FROM someday_refresh_tokens rt WHERE rt.session_id = candidate.id)",
                    audit.target, remaining,
                )
            }
            if (remaining > 0) remaining -= scrubDeviceClaims(connection, audit.target, remaining)
            batchSize - remaining
        }
    }

    override fun recordAssumptionViolation(audit: RetiredAccountAudit) {
        AccountAdmission.transaction(connections, listOf(audit.target.userId), AccountLockMode.EXCLUSIVE) { connection ->
            requireAudit(connection, audit)
            connection.prepareStatement(
                """
                UPDATE someday_account_data_incarnations
                SET media_reclaimed_at = NULL, media_revalidation_required = TRUE
                WHERE user_id = ? AND incarnation = ? AND media_audit_id = ? AND state = 'retired'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, audit.target.userId)
                statement.setObject(2, audit.target.incarnation)
                statement.setObject(3, audit.auditId)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    override fun certifyMediaReclaimed(audit: RetiredAccountAudit, evidence: MediaReclamationEvidence): Instant {
        val backend = when (config.mediaStorage) {
            is ServerMediaStorage.FileSystem -> ReclamationBackend.FILESYSTEM
            is ServerMediaStorage.S3 -> ReclamationBackend.S3
        }
        require(evidence.backend == backend && evidence.completeEmpty) { "A complete empty scan of the configured backend is required." }
        require(!evidence.scanCompletedAt.isBefore(evidence.scanStartedAt)) { "Invalid verification scan interval." }
        val barrier = if (backend == ReclamationBackend.S3) audit.target.retiredAt.plus(S3_SETTLING_PERIOD) else audit.target.retiredAt
        require(!evidence.scanStartedAt.isBefore(barrier)) { "Verification scan began before the backend settlement barrier." }
        return AccountAdmission.transaction(connections, listOf(audit.target.userId), AccountLockMode.EXCLUSIVE) { connection ->
            val current = requireAudit(connection, audit)
            if (current.revalidationRequired) throw MediaReclamationRevalidationRequired()
            val completedAt = clock.instant()
            require(!completedAt.isBefore(evidence.scanCompletedAt)) { "Verification clock moved backwards or scan is from the future." }
            connection.prepareStatement(
                """
                UPDATE someday_account_data_incarnations SET media_reclaimed_at = ?, media_last_certified_at = ?
                WHERE user_id = ? AND incarnation = ? AND media_audit_id = ? AND state = 'retired'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, completedAt.atOffset(ZoneOffset.UTC))
                statement.setObject(2, completedAt.atOffset(ZoneOffset.UTC))
                statement.setObject(3, audit.target.userId)
                statement.setObject(4, audit.target.incarnation)
                statement.setObject(5, audit.auditId)
                check(statement.executeUpdate() == 1)
            }
            completedAt
        }
    }

    /** Run after a coordinated restore while ingress and other maintenance are stopped. */
    override fun invalidateAfterRestore(expectedDatabaseIdentity: String) {
        val users = connections.connection().use { connection ->
            if (readDatabaseIdentity(connection) != expectedDatabaseIdentity) throw RetiredAccountVerificationChanged()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT DISTINCT user_id FROM someday_account_data_incarnations").use { result ->
                    buildList { while (result.next()) add(result.getObject(1, UUID::class.java)) }
                }
            }
        }
        AccountAdmission.transaction(connections, users, AccountLockMode.EXCLUSIVE) { connection ->
            if (readDatabaseIdentity(connection) != expectedDatabaseIdentity) throw RetiredAccountVerificationChanged()
            connection.prepareStatement("UPDATE someday_maintenance_context SET verification_context = ? WHERE singleton").use { statement ->
                statement.setObject(1, UUID.randomUUID())
                check(statement.executeUpdate() == 1)
            }
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE someday_account_data_incarnations SET media_reclaimed_at = NULL, media_audit_id = NULL, media_last_certified_at = NULL, " +
                        "media_revalidation_required = TRUE WHERE state = 'retired'",
                )
            }
        }
    }

    private fun readDatabaseIdentity(connection: Connection): String = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT current_database() || ':' || oid::text || ':' || COALESCE(inet_server_addr()::text, 'local') || ':' || " +
                "COALESCE(inet_server_port()::text, 'local') FROM pg_database WHERE datname = current_database()",
        ).use { result -> check(result.next()); result.getString(1) }
    }

    private fun requireAudit(connection: Connection, audit: RetiredAccountAudit): RetiredAccountTarget {
        val target = requireTarget(connection, audit.target)
        val actualAuditId = connection.prepareStatement(
            "SELECT media_audit_id FROM someday_account_data_incarnations WHERE user_id = ? AND incarnation = ? AND state = 'retired'",
        ).use { statement ->
            statement.setObject(1, target.userId)
            statement.setObject(2, target.incarnation)
            statement.executeQuery().use { result -> if (result.next()) result.getObject(1, UUID::class.java) else null }
        }
        if (actualAuditId != audit.auditId) throw RetiredAccountVerificationChanged()
        return target
    }

    private fun requireTarget(connection: Connection, expected: RetiredAccountTarget): RetiredAccountTarget {
        val current = readTarget(connection, expected.userId, expected.incarnation) ?: throw RetiredAccountVerificationChanged()
        if (current.storageLayout != expected.storageLayout || current.retiredAt != expected.retiredAt ||
            current.verificationContext != expected.verificationContext || current.databaseIdentity != expected.databaseIdentity
        ) throw RetiredAccountVerificationChanged()
        return current
    }

    private fun readTarget(connection: Connection, userId: UUID, incarnation: UUID): RetiredAccountTarget? =
        connection.prepareStatement(
            """
            SELECT i.storage_layout, i.retired_at, COALESCE(i.media_reclaimed_at, i.media_last_certified_at) AS media_reclaimed_at, i.media_revalidation_required,
                   m.verification_context,
                   current_database() || ':' || d.oid::text || ':' || COALESCE(inet_server_addr()::text, 'local') || ':' ||
                       COALESCE(inet_server_port()::text, 'local') AS database_identity
            FROM someday_account_data_incarnations i
            CROSS JOIN someday_maintenance_context m
            JOIN pg_database d ON d.datname = current_database()
            WHERE i.user_id = ? AND i.incarnation = ? AND i.state = 'retired' AND m.singleton
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, incarnation)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    RetiredAccountTarget(
                        userId, incarnation, result.getString("storage_layout"),
                        result.getObject("retired_at", OffsetDateTime::class.java).toInstant(),
                        result.getObject("media_reclaimed_at", OffsetDateTime::class.java)?.toInstant(),
                        result.getObject("verification_context", UUID::class.java), result.getString("database_identity"),
                        result.getBoolean("media_revalidation_required"),
                    )
                } else null
            }
        }

    private fun deleteBatch(
        connection: Connection,
        table: String,
        incarnationPredicate: String,
        target: RetiredAccountTarget,
        limit: Int,
        userPredicate: String = "candidate.user_id = ?",
        outerUserPredicate: String = "target.user_id = ?",
    ): Int = connection.prepareStatement(
        """
        DELETE FROM $table target WHERE $outerUserPredicate AND target.ctid IN (
            SELECT candidate.ctid FROM $table candidate
            WHERE $userPredicate AND $incarnationPredicate ORDER BY candidate.ctid LIMIT ?
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, target.userId)
        statement.setObject(2, target.userId)
        statement.setObject(3, target.incarnation)
        statement.setInt(4, limit)
        statement.executeUpdate()
    }

    private fun scrubDeviceClaims(connection: Connection, target: RetiredAccountTarget, limit: Int): Int =
        connection.prepareStatement(
            """
            UPDATE someday_devices SET name = 'Retired installation', platform = 'retired', last_seen_at = NULL
            WHERE user_id = ? AND data_incarnation = ? AND id IN (
                SELECT id FROM someday_devices
                WHERE user_id = ? AND data_incarnation = ?
                  AND (name <> 'Retired installation' OR platform <> 'retired' OR last_seen_at IS NOT NULL)
                ORDER BY id LIMIT ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, target.userId)
            statement.setObject(2, target.incarnation)
            statement.setObject(3, target.userId)
            statement.setObject(4, target.incarnation)
            statement.setInt(5, limit)
            statement.executeUpdate()
        }

    private fun setMaintenanceScope(connection: Connection, userId: UUID) {
        connection.prepareStatement("SELECT set_config('someday.user_id', ?, true), set_config('someday.workspace_id', '*', true)").use { statement ->
            statement.setString(1, userId.toString())
            statement.executeQuery().close()
        }
    }

    companion object {
        const val MAX_BATCH_SIZE = 1000
        val S3_SETTLING_PERIOD: Duration = Duration.ofHours(24)
        private const val WORKSPACE_MATCH = "EXISTS (SELECT 1 FROM someday_entity_workspaces w WHERE w.user_id = candidate.user_id AND w.workspace_id = candidate.workspace_id AND w.data_incarnation = ?)"
        private const val REFRESH_SESSION_MATCH = "EXISTS (SELECT 1 FROM someday_sessions s WHERE s.id = candidate.session_id AND s.data_incarnation = ?)"
        private val WORKSPACE_CONTENT_TABLES = listOf(
            "someday_sync_v2_changes", "someday_sync_v2_mutations", "someday_sync_v2_objects",
            "someday_sync_v2_checkpoint_chunks", "someday_sync_v2_checkpoint_manifests", "someday_sync_v2_epochs",
            "someday_media_v3_objects", "workspace_recovery_envelopes",
        )
    }
}
