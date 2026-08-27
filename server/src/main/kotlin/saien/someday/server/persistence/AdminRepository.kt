package saien.someday.server.persistence

import saien.someday.server.ServerConfig
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class AdminDashboardSnapshot(
    val totalUsers: Long,
    val activeUsers: Long,
    val disabledUsers: Long,
    val totalDevices: Long,
    val revokedDevices: Long,
    val encryptedObjects: Long,
    val acceptedChanges: Long,
)

data class AdminUserSummary(
    val id: UUID,
    val email: String,
    val isAdmin: Boolean,
    val disabledAt: Instant?,
    val createdAt: Instant,
    val deviceCount: Long,
    val revokedDeviceCount: Long,
    val objectCount: Long,
    val latestCursor: Long?,
)

data class AdminUserDetail(
    val user: AdminUserSummary,
    val devices: List<AdminDeviceSummary>,
    val sessions: List<AdminSessionSummary>,
)

data class AdminSessionSummary(
    val id: UUID,
    val userId: UUID,
    val deviceId: UUID?,
    val deviceName: String?,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val createdAt: Instant,
    val activeRefreshTokens: Long,
)

data class AdminDeviceSummary(
    val id: UUID,
    val userId: UUID,
    val ownerEmail: String,
    val name: String,
    val platform: String,
    val revokedAt: Instant?,
    val lastSeenAt: Instant?,
    val createdAt: Instant,
    val objectCount: Long,
    val lastSyncCursor: Long?,
)

data class AdminStorageSummary(
    val encryptedObjects: Long,
    val encryptedBytes: Long,
    val changes: Long,
    val byType: List<AdminStorageByType>,
    val byUser: List<AdminStorageByUser>,
)

data class AdminStorageByType(
    val objectType: String,
    val objects: Long,
    val encryptedBytes: Long,
    val latestCursor: Long?,
)

data class AdminStorageByUser(
    val userId: UUID,
    val email: String,
    val objects: Long,
    val encryptedBytes: Long,
)

data class AdminSyncActivitySummary(
    val acceptedChanges: Long,
    val entries: List<AdminSyncActivityEntry>,
)

data class AdminSyncActivityEntry(
    val userEmail: String?,
    val deviceName: String?,
    val objectId: String?,
    val objectType: String?,
    val mutationId: String?,
    val cursor: Long?,
    val createdAt: Instant,
)

data class AdminHealthSnapshot(
    val databaseStatus: String,
    val migrationVersion: String,
    val migrationDescription: String,
    val uptimeSeconds: Long,
    val checkedAt: Instant,
)

class AdminRepository(
    config: ServerConfig,
    private val startedAt: Instant,
    private val connections: DatabaseConnectionProvider = directDatabaseConnectionProvider(config),
) {
    fun dashboard(): AdminDashboardSnapshot =
        connection().use { connection ->
            val userCounts = connection.prepareStatement(
                """
                SELECT COUNT(*) AS total_users,
                       COUNT(*) FILTER (WHERE disabled_at IS NULL) AS active_users,
                       COUNT(*) FILTER (WHERE disabled_at IS NOT NULL) AS disabled_users
                FROM someday_users
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    result.next()
                    Triple(
                        result.getLong("total_users"),
                        result.getLong("active_users"),
                        result.getLong("disabled_users"),
                    )
                }
            }
            val deviceCounts = connection.prepareStatement(
                """
                SELECT COUNT(*) AS total_devices,
                       COUNT(*) FILTER (WHERE revoked_at IS NOT NULL) AS revoked_devices
                FROM someday_devices
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    result.next()
                    result.getLong("total_devices") to result.getLong("revoked_devices")
                }
            }
            val encryptedObjects = connection.count("SELECT COUNT(*) FROM someday_sync_v2_objects")
            AdminDashboardSnapshot(
                totalUsers = userCounts.first,
                activeUsers = userCounts.second,
                disabledUsers = userCounts.third,
                totalDevices = deviceCounts.first,
                revokedDevices = deviceCounts.second,
                encryptedObjects = encryptedObjects,
                acceptedChanges = connection.count("SELECT COUNT(*) FROM someday_sync_v2_changes"),
            )
        }

    fun listUsers(): List<AdminUserSummary> =
        connection().use { connection ->
            connection.prepareStatement(USER_SUMMARY_SQL + "\nORDER BY u.created_at, u.email").use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(result.toAdminUserSummary())
                        }
                    }
                }
            }
        }

    fun userDetail(userId: UUID): AdminUserDetail? =
        connection().use { connection ->
            val user = connection.prepareStatement(
                USER_SUMMARY_SQL + "\nWHERE u.id = ?",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.toAdminUserSummary() else null
                }
            } ?: return@use null
            AdminUserDetail(
                user = user,
                devices = listDevices(connection, userId),
                sessions = listSessions(connection, userId),
            )
        }

    fun listDevices(): List<AdminDeviceSummary> =
        connection().use { connection ->
            listDevices(connection, userId = null)
        }

    fun storage(): AdminStorageSummary =
        connection().use { connection ->
            val totals = connection.prepareStatement(
                """
                SELECT COUNT(*) AS encrypted_objects,
                       COALESCE(SUM(octet_length(o.encrypted_object_json)), 0) AS encrypted_bytes
                FROM someday_sync_v2_objects o
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    result.next()
                    result.getLong("encrypted_objects") to result.getLong("encrypted_bytes")
                }
            }
            AdminStorageSummary(
                encryptedObjects = totals.first,
                encryptedBytes = totals.second,
                changes = connection.count("SELECT COUNT(*) FROM someday_sync_v2_changes"),
                byType = listStorageByType(connection),
                byUser = listStorageByUser(connection),
            )
        }

    fun syncActivity(limit: Int = 100): AdminSyncActivitySummary =
        connection().use { connection ->
            val entries = connection.prepareStatement(
                """
                SELECT c.cursor,
                       u.email AS user_email,
                       d.name AS device_name,
                       o.object_id,
                       o.object_type,
                       c.mutation_id,
                       c.changed_at
                FROM someday_sync_v2_changes c
                JOIN someday_sync_v2_objects o
                  ON o.user_id = c.user_id
                 AND o.epoch_id = c.epoch_id
                 AND o.object_id = c.object_id
                LEFT JOIN someday_users u ON u.id = c.user_id
                LEFT JOIN someday_devices d ON d.id = o.first_writer_device_id
                ORDER BY c.cursor DESC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, limit.coerceIn(1, 500))
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                AdminSyncActivityEntry(
                                    userEmail = result.getString("user_email"),
                                    deviceName = result.getString("device_name"),
                                    objectId = result.getString("object_id"),
                                    objectType = result.getString("object_type"),
                                    mutationId = result.getString("mutation_id"),
                                    cursor = result.optionalLong("cursor"),
                                    createdAt = result.requiredInstant("changed_at"),
                                ),
                            )
                        }
                    }
                }
            }
            AdminSyncActivitySummary(
                acceptedChanges = connection.count("SELECT COUNT(*) FROM someday_sync_v2_changes"),
                entries = entries,
            )
        }

    fun health(): AdminHealthSnapshot =
        connection().use { connection ->
            connection.prepareStatement("SELECT 1").use { statement ->
                statement.executeQuery().use { result ->
                    result.next()
                }
            }
            val migration = connection.prepareStatement(
                """
                SELECT COALESCE(version, 'baseline') AS version,
                       COALESCE(description, 'baseline') AS description
                FROM flyway_schema_history
                WHERE success = TRUE
                ORDER BY installed_rank DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        result.getString("version") to result.getString("description")
                    } else {
                        "none" to "none"
                    }
                }
            }
            val checkedAt = Instant.now()
            AdminHealthSnapshot(
                databaseStatus = "ok",
                migrationVersion = migration.first,
                migrationDescription = migration.second,
                uptimeSeconds = checkedAt.epochSecond - startedAt.epochSecond,
                checkedAt = checkedAt,
            )
        }

    fun disableUser(userId: UUID): Boolean =
        transaction { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE someday_users
                SET disabled_at = COALESCE(disabled_at, NOW())
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                return@transaction false
            }
            connection.prepareStatement(
                """
                UPDATE someday_sessions
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE user_id = ? AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                UPDATE someday_refresh_tokens
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE session_id IN (
                    SELECT id FROM someday_sessions WHERE user_id = ?
                ) AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeUpdate()
            }
            true
        }

    fun revokeSession(sessionId: UUID): Boolean =
        transaction { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE someday_sessions
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                return@transaction false
            }
            connection.prepareStatement(
                """
                UPDATE someday_refresh_tokens
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE session_id = ? AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.executeUpdate()
            }
            true
        }

    fun revokeDevice(deviceId: UUID): Boolean =
        transaction { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE someday_devices
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, deviceId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                return@transaction false
            }
            connection.prepareStatement(
                """
                UPDATE someday_sessions
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE device_id = ? AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, deviceId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                UPDATE someday_refresh_tokens
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE session_id IN (
                    SELECT id FROM someday_sessions WHERE device_id = ?
                ) AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, deviceId)
                statement.executeUpdate()
            }
            true
        }

    private fun listDevices(connection: Connection, userId: UUID?): List<AdminDeviceSummary> {
        val sql = DEVICE_SUMMARY_SQL + if (userId == null) {
            "\nORDER BY d.created_at, d.name"
        } else {
            "\nWHERE d.user_id = ?\nORDER BY d.created_at, d.name"
        }
        return connection.prepareStatement(sql).use { statement ->
            if (userId != null) {
                statement.setObject(1, userId)
            }
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(result.toAdminDeviceSummary())
                    }
                }
            }
        }
    }

    private fun listSessions(connection: Connection, userId: UUID): List<AdminSessionSummary> =
        connection.prepareStatement(
            """
            SELECT s.id,
                   s.user_id,
                   s.device_id,
                   d.name AS device_name,
                   s.expires_at,
                   s.revoked_at,
                   s.created_at,
                   COUNT(rt.id) FILTER (WHERE rt.revoked_at IS NULL AND rt.expires_at > NOW()) AS active_refresh_tokens
            FROM someday_sessions s
            LEFT JOIN someday_devices d ON d.id = s.device_id
            LEFT JOIN someday_refresh_tokens rt ON rt.session_id = s.id
            WHERE s.user_id = ?
            GROUP BY s.id, d.name
            ORDER BY s.created_at DESC, s.id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            AdminSessionSummary(
                                id = result.getObject("id", UUID::class.java),
                                userId = result.getObject("user_id", UUID::class.java),
                                deviceId = result.optionalUuid("device_id"),
                                deviceName = result.getString("device_name"),
                                expiresAt = result.requiredInstant("expires_at"),
                                revokedAt = result.optionalInstant("revoked_at"),
                                createdAt = result.requiredInstant("created_at"),
                                activeRefreshTokens = result.getLong("active_refresh_tokens"),
                            ),
                        )
                    }
                }
            }
        }

    private fun listStorageByType(connection: Connection): List<AdminStorageByType> =
        connection.prepareStatement(
            """
            SELECT o.object_type,
                   COUNT(*) AS objects,
                   COALESCE(SUM(octet_length(o.encrypted_object_json)), 0) AS encrypted_bytes,
                   MAX(o.cursor) AS latest_cursor
            FROM someday_sync_v2_objects o
            GROUP BY o.object_type
            ORDER BY o.object_type
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            AdminStorageByType(
                                objectType = result.getString("object_type"),
                                objects = result.getLong("objects"),
                                encryptedBytes = result.getLong("encrypted_bytes"),
                                latestCursor = result.optionalLong("latest_cursor"),
                            ),
                        )
                    }
                }
            }
        }

    private fun listStorageByUser(connection: Connection): List<AdminStorageByUser> =
        connection.prepareStatement(
            """
            SELECT u.id AS user_id,
                   u.email,
                   COUNT(DISTINCT (o.workspace_id, o.epoch_id, o.object_id)) AS objects,
                   COALESCE(SUM(octet_length(o.encrypted_object_json)), 0) AS encrypted_bytes
            FROM someday_users u
            LEFT JOIN someday_sync_v2_objects o ON o.user_id = u.id
            GROUP BY u.id
            ORDER BY objects DESC, u.email
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            AdminStorageByUser(
                                userId = result.getObject("user_id", UUID::class.java),
                                email = result.getString("email"),
                                objects = result.getLong("objects"),
                                encryptedBytes = result.getLong("encrypted_bytes"),
                            ),
                        )
                    }
                }
            }
        }

    private fun Connection.count(sql: String): Long =
        prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result ->
                result.next()
                result.getLong(1)
            }
        }

    private fun <T> transaction(block: (Connection) -> T): T =
        connection().use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }

    private fun connection(): Connection {
        val connection = connections.connection()
        try {
            connection.prepareStatement("SELECT set_config('someday.user_id', '*', false)").use { statement ->
                statement.execute()
            }
            connection.prepareStatement("SELECT set_config('someday.workspace_id', '*', false)").use { statement ->
                statement.execute()
            }
            return connection
        } catch (failure: Throwable) {
            runCatching { connection.close() }
            throw failure
        }
    }

    private fun ResultSet.toAdminUserSummary(): AdminUserSummary =
        AdminUserSummary(
            id = getObject("id", UUID::class.java),
            email = getString("email"),
            isAdmin = getBoolean("is_admin"),
            disabledAt = optionalInstant("disabled_at"),
            createdAt = requiredInstant("created_at"),
            deviceCount = getLong("device_count"),
            revokedDeviceCount = getLong("revoked_device_count"),
            objectCount = getLong("object_count"),
            latestCursor = optionalLong("latest_cursor"),
        )

    private fun ResultSet.toAdminDeviceSummary(): AdminDeviceSummary =
        AdminDeviceSummary(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            ownerEmail = getString("owner_email"),
            name = getString("name"),
            platform = getString("platform"),
            revokedAt = optionalInstant("revoked_at"),
            lastSeenAt = optionalInstant("last_seen_at"),
            createdAt = requiredInstant("created_at"),
            objectCount = getLong("object_count"),
            lastSyncCursor = optionalLong("last_sync_cursor"),
        )

    private fun ResultSet.requiredInstant(column: String): Instant =
        getObject(column, OffsetDateTime::class.java).toInstant()

    private fun ResultSet.optionalInstant(column: String): Instant? =
        getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun ResultSet.optionalLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun ResultSet.optionalUuid(column: String): UUID? =
        getObject(column)?.let { it as UUID }

    private companion object {
        val USER_SUMMARY_SQL = """
            SELECT u.id,
                   u.email,
                   u.is_admin,
                   u.disabled_at,
                   u.created_at,
                   COALESCE(d.device_count, 0) AS device_count,
                   COALESCE(d.revoked_device_count, 0) AS revoked_device_count,
                   COALESCE(o.object_count, 0) AS object_count,
                   o.latest_cursor
            FROM someday_users u
            LEFT JOIN (
                SELECT user_id,
                       COUNT(*) AS device_count,
                       COUNT(*) FILTER (WHERE revoked_at IS NOT NULL) AS revoked_device_count
                FROM someday_devices
                GROUP BY user_id
            ) d ON d.user_id = u.id
            LEFT JOIN (
                SELECT user_id,
                       COUNT(*) AS object_count,
                       MAX(cursor) AS latest_cursor
                FROM someday_sync_v2_objects
                GROUP BY user_id
            ) o ON o.user_id = u.id
        """.trimIndent()

        val DEVICE_SUMMARY_SQL = """
            SELECT d.id,
                   d.user_id,
                   u.email AS owner_email,
                   d.name,
                   d.platform,
                   d.revoked_at,
                   d.last_seen_at,
                   d.created_at,
                   COALESCE(o.object_count, 0) AS object_count,
                   o.last_sync_cursor
            FROM someday_devices d
            JOIN someday_users u ON u.id = d.user_id
            LEFT JOIN (
                SELECT first_writer_device_id AS device_id,
                       COUNT(*) AS object_count,
                       MAX(cursor) AS last_sync_cursor
                FROM someday_sync_v2_objects
                GROUP BY first_writer_device_id
            ) o ON o.device_id = d.id
        """.trimIndent()
    }
}
