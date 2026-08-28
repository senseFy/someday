package saien.someday.server

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.persistence.DatabaseConnectionPool
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.TestServerIdentity

class DatabaseConnectionPoolRlsIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: PostgresContractFixture

    @BeforeTest
    fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("pool-rls-media").toPath())
        database.reset()
    }

    @AfterTest
    fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test
    fun checkoutClearsSessionWildcardBeforeThePhysicalConnectionIsReused() {
        val first = database.seedIdentity("pool-rls-first-${System.nanoTime()}")
        val second = database.seedIdentity("pool-rls-second-${System.nanoTime()}")
        val poolConfig = database.config.copy(databaseMaxPoolSize = 1)

        DatabaseConnectionPool.create(poolConfig).use { pool ->
            val firstBackendPid = pool.connection().use { connection ->
                assumeTrue(
                    "Pool RLS verification requires the NOBYPASSRLS application role used by the reliability gate.",
                    isRestrictedApplicationRole(connection),
                )
                selectScope(connection, userId = "*", workspaceId = "*")
                insertWorkspace(connection, first, WORKSPACE_A)
                insertWorkspace(connection, second, WORKSPACE_B)
                assertEquals(
                    setOf(
                        Scope(first.userId, WORKSPACE_A),
                        Scope(second.userId, WORKSPACE_B),
                    ),
                    visibleScopes(connection).toSet(),
                )
                backendPid(connection)
            }

            pool.connection().use { connection ->
                assertEquals(firstBackendPid, backendPid(connection))
                assertEquals("" to "", currentScope(connection))
                assertEquals(emptyList(), visibleScopes(connection))

                selectScope(connection, first.userId.toString(), WORKSPACE_A)
                assertEquals(
                    listOf(Scope(first.userId, WORKSPACE_A)),
                    visibleScopes(connection),
                )
            }
        }
    }

    @Test
    fun checkoutOverridesDangerousRoleAndDatabaseDefaultsWithEmptyScope() {
        val identity = database.seedIdentity("pool-rls-defaults-${System.nanoTime()}")
        val config = database.config.copy(databaseMaxPoolSize = 1)
        val names = databaseConnection(config).use { connection ->
            assumeTrue(
                "Database-default RLS verification requires the NOBYPASSRLS application role.",
                isRestrictedApplicationRole(connection),
            )
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_user, current_database()").use { result ->
                    check(result.next())
                    result.getString(1) to result.getString(2)
                }
            }
        }

        try {
            administratorConnection(config).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "ALTER ROLE ${quoteIdentifier(names.first)} SET \"someday.user_id\" TO '*'",
                    )
                    statement.execute(
                        "ALTER DATABASE ${quoteIdentifier(names.second)} SET \"someday.workspace_id\" TO '*'",
                    )
                }
            }
            databaseConnection(config).use { connection ->
                assertEquals("*" to "*", currentScope(connection))
                insertWorkspace(connection, identity, WORKSPACE_A)
            }

            DatabaseConnectionPool.create(config).use { pool ->
                pool.connection().use { connection ->
                    assertEquals("" to "", currentScope(connection))
                    assertEquals(emptyList(), visibleScopes(connection))
                }
            }
        } finally {
            administratorConnection(config).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "ALTER ROLE ${quoteIdentifier(names.first)} RESET \"someday.user_id\"",
                    )
                    statement.execute(
                        "ALTER DATABASE ${quoteIdentifier(names.second)} RESET \"someday.workspace_id\"",
                    )
                }
            }
        }
    }

    private fun insertWorkspace(
        connection: Connection,
        identity: TestServerIdentity,
        workspaceId: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO someday_entity_workspaces(user_id, workspace_id) VALUES (?, ?)",
        ).use { statement ->
            statement.setObject(1, identity.userId)
            statement.setString(2, workspaceId)
            statement.executeUpdate()
        }
    }

    private fun visibleScopes(connection: Connection): List<Scope> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT user_id, workspace_id FROM someday_entity_workspaces ORDER BY user_id, workspace_id",
            ).use { result ->
                buildList {
                    while (result.next()) {
                        add(Scope(result.getObject(1, UUID::class.java), result.getString(2)))
                    }
                }
            }
        }

    private fun currentScope(connection: Connection): Pair<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT current_setting('someday.user_id', true), " +
                    "current_setting('someday.workspace_id', true)",
            ).use { result ->
                check(result.next())
                result.getString(1) to result.getString(2)
            }
        }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { result ->
                check(result.next())
                result.getInt(1)
            }
        }

    private fun isRestrictedApplicationRole(connection: Connection): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname = current_user",
            ).use { result ->
                check(result.next())
                result.getBoolean(1)
            }
        }

    private fun selectScope(connection: Connection, userId: String, workspaceId: String) {
        setConfig(connection, "someday.user_id", userId)
        setConfig(connection, "someday.workspace_id", workspaceId)
    }

    private fun setConfig(connection: Connection, name: String, value: String) {
        require(name == "someday.user_id" || name == "someday.workspace_id")
        connection.prepareStatement("SELECT set_config(?, ?, false)").use { statement ->
            statement.setString(1, name)
            statement.setString(2, value)
            statement.executeQuery().close()
        }
    }

    private fun databaseConnection(config: ServerConfig): Connection = DriverManager.getConnection(
        config.databaseConnectionUrl,
        config.databaseUser,
        config.databasePassword,
    )

    private fun administratorConnection(config: ServerConfig): Connection = DriverManager.getConnection(
        System.getenv("SOMEDAY_DB_ADMIN_URL")
            ?.let(::productionTestDatabaseConnectionUrl)
            ?: config.databaseConnectionUrl,
        System.getenv("SOMEDAY_DB_ADMIN_USER") ?: config.databaseUser,
        System.getenv("SOMEDAY_DB_ADMIN_PASSWORD") ?: config.databasePassword,
    )

    private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private data class Scope(val userId: UUID, val workspaceId: String)

    private companion object {
        const val WORKSPACE_A = "workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val WORKSPACE_B = "workspace-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
