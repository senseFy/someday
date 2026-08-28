package saien.someday.server.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import saien.someday.server.ServerConfig

/** Checkout boundary used by repositories; callers own and close each returned connection. */
fun interface DatabaseConnectionProvider {
    fun connection(): Connection
}

/** Process-owned, bounded PostgreSQL pool shared by all server repositories. */
class DatabaseConnectionPool private constructor(
    private val dataSource: HikariDataSource,
) : DatabaseConnectionProvider, AutoCloseable {
    override fun connection(): Connection {
        val connection = dataSource.connection
        try {
            // RLS scopes are PostgreSQL session settings and are not reset by JDBC's
            // standard connection-state cleanup. Clear them on every pool checkout.
            connection.prepareStatement(
                "SELECT set_config('someday.user_id', '', false), " +
                    "set_config('someday.workspace_id', '', false)",
            ).use { statement ->
                statement.executeQuery().close()
            }
            return connection
        } catch (failure: Throwable) {
            runCatching { connection.close() }
            throw failure
        }
    }

    val maximumPoolSize: Int
        get() = dataSource.maximumPoolSize

    val isClosed: Boolean
        get() = dataSource.isClosed

    override fun close() {
        dataSource.close()
    }

    companion object {
        fun create(config: ServerConfig): DatabaseConnectionPool =
            DatabaseConnectionPool(HikariDataSource(hikariConfig(config)))
    }
}

/** Backwards-compatible unpooled provider for focused repository construction in tests. */
internal fun directDatabaseConnectionProvider(config: ServerConfig): DatabaseConnectionProvider =
    DatabaseConnectionProvider {
        DriverManager.getConnection(config.databaseConnectionUrl, config.databaseUser, config.databasePassword)
    }

internal fun hikariConfig(config: ServerConfig): HikariConfig =
    HikariConfig().apply {
        jdbcUrl = config.databaseConnectionUrl
        username = config.databaseUser
        password = config.databasePassword
        poolName = "someday-postgres"
        maximumPoolSize = config.databaseMaxPoolSize
        minimumIdle = minOf(2, config.databaseMaxPoolSize)
        connectionTimeout = Duration.ofSeconds(10).toMillis()
        validationTimeout = Duration.ofSeconds(5).toMillis()
        idleTimeout = Duration.ofMinutes(10).toMillis()
        keepaliveTime = Duration.ofMinutes(2).toMillis()
        maxLifetime = Duration.ofMinutes(30).toMillis()
        addDataSourceProperty("tcpKeepAlive", "true")
    }
