package saien.someday.server.support

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import saien.someday.server.ServerConfig
import saien.someday.server.persistence.DatabaseMigrator
import saien.someday.server.productionTestServerConfig

/**
 * Small PostgreSQL lifecycle fixture for repository contract tests.
 *
 * It deliberately owns no protocol behavior: tests still construct their
 * inputs and assert their invariants directly.
 */
internal class PostgresContractFixture(
    mediaRoot: Path,
    mediaQuotaBytes: Long = 5L * 1024L * 1024L * 1024L,
) {
    private val databaseUrl = System.getenv("SOMEDAY_DB_URL")
        ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    private val databaseUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    private val databasePassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"

    val config: ServerConfig = productionTestServerConfig(
        databaseUrl,
        databaseUser,
        databasePassword,
    ).copy(
        mediaBlobDirectory = mediaRoot.toAbsolutePath().toString(),
        mediaQuotaBytes = mediaQuotaBytes,
    )

    init {
        DatabaseMigrator.migrate(config)
    }

    fun reset() {
        connection().use { connection ->
            selectWildcardScope(connection)
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE TABLE someday_users RESTART IDENTITY CASCADE")
                // The cursor sequence is intentionally global and is not owned
                // by a table column, so TRUNCATE does not restart it.
                statement.execute("ALTER SEQUENCE someday_sync_v2_global_cursor RESTART WITH 1")
            }
        }
    }

    fun seedIdentity(label: String = UUID.randomUUID().toString()): TestServerIdentity {
        val userId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        connection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO someday_users(id, email, password_hash) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, "contract-$label@example.com")
                statement.setString(3, "test-only")
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO someday_devices(id, user_id, name, platform) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, deviceId)
                statement.setObject(2, userId)
                statement.setString(3, "Contract device")
                statement.setString(4, "integration")
                statement.executeUpdate()
            }
        }
        return TestServerIdentity(userId, deviceId)
    }

    fun countRows(table: String, userId: UUID, workspaceId: String): Long {
        require(table in SCOPED_TABLES) { "Unsupported test table: $table" }
        return connection().use { connection ->
            selectWildcardScope(connection)
            connection.prepareStatement(
                "SELECT COUNT(*) FROM $table WHERE user_id = ? AND workspace_id = ?",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, workspaceId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }
    }

    fun mediaBytes(userId: UUID): Long = connection().use { connection ->
        selectWildcardScope(connection)
        connection.prepareStatement(
            "SELECT COALESCE(SUM(ciphertext_bytes), 0) FROM someday_media_v3_objects WHERE user_id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getLong(1)
            }
        }
    }

    fun configWithApplicationName(applicationName: String): ServerConfig {
        require(APPLICATION_NAME.matches(applicationName))
        val separator = if ('?' in config.databaseUrl) '&' else '?'
        return config.copy(databaseUrl = "${config.databaseUrl}${separator}ApplicationName=$applicationName")
    }

    fun holdWorkspaceAdvisoryLock(userId: UUID, workspaceId: String): HeldPostgresAdvisoryLock {
        val connection = connection()
        connection.autoCommit = false
        try {
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
                statement.setString(1, "$userId\u001f$workspaceId")
                statement.executeQuery().close()
            }
            return HeldPostgresAdvisoryLock(connection)
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            runCatching { connection.close() }
            throw failure
        }
    }

    fun awaitAdvisoryLockWait(
        applicationName: String,
        operationCompleted: () -> Boolean,
        timeout: Duration = Duration.ofSeconds(30),
    ) {
        require(APPLICATION_NAME.matches(applicationName))
        val deadline = System.nanoTime() + timeout.toNanos()
        connection().use { observer ->
            observer.prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_locks waiting
                    JOIN pg_stat_activity activity ON activity.pid = waiting.pid
                    JOIN pg_locks granted
                      ON granted.locktype = waiting.locktype
                     AND granted.database IS NOT DISTINCT FROM waiting.database
                     AND granted.classid IS NOT DISTINCT FROM waiting.classid
                     AND granted.objid IS NOT DISTINCT FROM waiting.objid
                     AND granted.objsubid IS NOT DISTINCT FROM waiting.objsubid
                    WHERE waiting.locktype = 'advisory'
                      AND NOT waiting.granted
                      AND granted.granted
                      AND granted.pid <> waiting.pid
                      AND activity.application_name = ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, applicationName)
                while (System.nanoTime() < deadline) {
                    statement.executeQuery().use { result ->
                        check(result.next())
                        if (result.getBoolean(1)) return
                    }
                    check(!operationCompleted()) {
                        "Operation completed without waiting on its PostgreSQL advisory lock."
                    }
                    Thread.yield()
                }
            }
        }
        error("Timed out observing PostgreSQL advisory-lock contention for $applicationName.")
    }

    private fun connection(): Connection =
        DriverManager.getConnection(databaseUrl, databaseUser, databasePassword)

    private fun selectWildcardScope(connection: Connection) {
        setConfig(connection, "someday.user_id", "*")
        setConfig(connection, "someday.workspace_id", "*")
    }

    private fun setConfig(connection: Connection, name: String, value: String) {
        connection.prepareStatement("SELECT set_config(?, ?, false)").use { statement ->
            statement.setString(1, name)
            statement.setString(2, value)
            statement.executeQuery().close()
        }
    }

    private companion object {
        val APPLICATION_NAME = Regex("^[a-z0-9_-]{1,48}$")
        val SCOPED_TABLES = setOf(
            "someday_entity_workspaces",
            "someday_sync_v2_epochs",
            "someday_sync_v2_checkpoint_chunks",
            "someday_sync_v2_checkpoint_manifests",
            "someday_sync_v2_objects",
            "someday_sync_v2_changes",
            "someday_sync_v2_mutations",
            "someday_media_v3_objects",
        )
    }
}

internal class HeldPostgresAdvisoryLock(private val connection: Connection) : AutoCloseable {
    override fun close() {
        try {
            connection.rollback()
        } finally {
            connection.close()
        }
    }
}

internal data class TestServerIdentity(
    val userId: UUID,
    val deviceId: UUID,
)
