package saien.someday.server.support

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import saien.someday.server.ServerConfig
import saien.someday.server.ServerMediaStorage
import saien.someday.server.productionTestDatabaseConnectionUrl
import saien.someday.server.productionTestServerConfig

/** A uniquely named disposable database; never resets the configured shared test database. */
internal class IsolatedPostgresDatabase(mediaRoot: Path) : AutoCloseable {
    private val baseUrl = System.getenv("SOMEDAY_DB_URL") ?: "jdbc:postgresql://127.0.0.1:54329/someday"
    private val applicationUser = System.getenv("SOMEDAY_DB_USER") ?: "someday"
    private val applicationPassword = System.getenv("SOMEDAY_DB_PASSWORD") ?: "someday"
    private val administratorUrl = productionTestDatabaseConnectionUrl(System.getenv("SOMEDAY_DB_ADMIN_URL") ?: baseUrl)
    private val administratorUser = System.getenv("SOMEDAY_DB_ADMIN_USER") ?: applicationUser
    private val administratorPassword = System.getenv("SOMEDAY_DB_ADMIN_PASSWORD") ?: applicationPassword
    private val isolatedDatabaseName = "someday_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
    private var created = false

    val config: ServerConfig = productionTestServerConfig(
        PGSimpleDataSource().apply {
            setURL(baseUrl)
            setDatabaseName(isolatedDatabaseName)
        }.getURL(),
        applicationUser,
        applicationPassword,
    ).copy(mediaStorage = ServerMediaStorage.FileSystem(mediaRoot.toAbsolutePath()))

    init {
        administratorConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE ${quote(isolatedDatabaseName)} OWNER ${quote(applicationUser)}")
            }
        }
        created = true
        // Verify the actual server-selected database before any schema or fixture write.
        // A JDBC query property can override its path, so inspecting the URL is insufficient.
        try {
            connection().close()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun migrateToV9() {
        connection().close()
        Flyway.configure()
            .dataSource(config.databaseConnectionUrl, config.databaseUser, config.databasePassword)
            .locations("classpath:db/migration")
            .target("9")
            .load()
            .migrate()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1").use { result ->
                    check(result.next() && result.getString(1) == "9") { "Upgrade fixture must start at schema V9." }
                }
            }
        }
    }

    fun connection(): Connection = DriverManager.getConnection(
        config.databaseConnectionUrl,
        config.databaseUser,
        config.databasePassword,
    ).also { connection ->
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_database()").use { result ->
                    check(result.next() && result.getString(1) == isolatedDatabaseName) {
                        "Refusing to use a database other than this fixture's isolated database."
                    }
                }
            }
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }

    fun <T> scopedTransaction(block: (Connection) -> T): T = connection().use { connection ->
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.execute("SELECT set_config('someday.user_id', '*', true)")
                statement.execute("SELECT set_config('someday.workspace_id', '*', true)")
            }
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    override fun close() {
        if (!created) return
        administratorConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE ${quote(isolatedDatabaseName)} WITH (FORCE)")
            }
        }
        created = false
    }

    private fun administratorConnection(): Connection = DriverManager.getConnection(
        administratorUrl,
        administratorUser,
        administratorPassword,
    )

    private fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""
}
