package saien.someday.server

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.Assume.assumeTrue
import saien.someday.server.persistence.DatabaseMigrator

class ProductionDatabaseBoundaryIntegrationTest {
    private val databaseUrl = System.getenv("SOMEDAY_DB_URL")
        ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    private val databaseUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    private val databasePassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"
    private val config = productionTestServerConfig(databaseUrl, databaseUser, databasePassword)

    @Test
    fun sharedMigrationBoundaryRejectsANewerSchemaBeforeApplyingAnything() {
        assumeSupportedRestrictedRole(config)
        DatabaseMigrator.migrate(config)
        connection(config).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO flyway_schema_history(
                        installed_rank,
                        version,
                        description,
                        type,
                        script,
                        checksum,
                        installed_by,
                        execution_time,
                        success
                    ) VALUES (
                        (SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history),
                        '999',
                        'future integration guard',
                        'SQL',
                        'V999__future_integration_guard.sql',
                        0,
                        current_user,
                        0,
                        true
                    )
                    """.trimIndent(),
                )
            }
        }
        try {
            assertFailsWith<IllegalStateException> { DatabaseMigrator.migrate(config) }
        } finally {
            connection(config).use { connection ->
                connection.prepareStatement("DELETE FROM flyway_schema_history WHERE version = ?").use { statement ->
                    statement.setString(1, "999")
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun sharedMigrationBoundaryRejectsTheProductionAdministratorRole() {
        val adminConfig = productionTestServerConfig(
            System.getenv("SOMEDAY_DB_ADMIN_URL") ?: databaseUrl,
            System.getenv("SOMEDAY_DB_ADMIN_USER") ?: databaseUser,
            System.getenv("SOMEDAY_DB_ADMIN_PASSWORD") ?: databasePassword,
        )
        assumeTrue("A PostgreSQL 17 superuser fixture is required.", databaseFacts(adminConfig) == (17 to true))

        assertFailsWith<IllegalArgumentException> { DatabaseMigrator.migrate(adminConfig) }
    }

    @Test
    fun rlsCatalogRejectsPolicyExpressionTamperingAndPassesAfterRollback() {
        assumeSupportedRestrictedRole(config)
        DatabaseMigrator.migrate(config)

        connection(config).use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER POLICY media_account_workspace_scope
                        ON someday_media_v3_objects
                        USING (true)
                        WITH CHECK (true)
                        """.trimIndent(),
                    )
                }
                assertFailsWith<IllegalStateException> {
                    DatabaseMigrator.verifyRlsCatalog(connection)
                }
                connection.rollback()
                DatabaseMigrator.verifyRlsCatalog(connection)
            } finally {
                connection.rollback()
            }
        }
    }

    private fun assumeSupportedRestrictedRole(serverConfig: ServerConfig) {
        assumeTrue(
            "A PostgreSQL 17 NOSUPERUSER NOBYPASSRLS fixture is required.",
            connection(serverConfig).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT
                            current_setting('server_version_num')::integer / 10000 = 17
                            AND NOT rolsuper
                            AND NOT rolbypassrls
                        FROM pg_roles
                        WHERE rolname = current_user
                        """.trimIndent(),
                    ).use { result -> result.next() && result.getBoolean(1) }
                }
            },
        )
    }

    private fun databaseFacts(serverConfig: ServerConfig): Pair<Int, Boolean> =
        connection(serverConfig).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT current_setting('server_version_num')::integer / 10000, rolsuper
                    FROM pg_roles
                    WHERE rolname = current_user
                    """.trimIndent(),
                ).use { result ->
                    check(result.next())
                    result.getInt(1) to result.getBoolean(2)
                }
            }
        }

    private fun connection(serverConfig: ServerConfig) = DriverManager.getConnection(
        serverConfig.databaseConnectionUrl,
        serverConfig.databaseUser,
        serverConfig.databasePassword,
    )
}
