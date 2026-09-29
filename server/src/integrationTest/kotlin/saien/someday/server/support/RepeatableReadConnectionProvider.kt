package saien.someday.server.support

import java.sql.Connection
import java.sql.DriverManager
import saien.someday.server.ServerConfig
import saien.someday.server.persistence.DatabaseConnectionProvider

/** Models a deployment whose connection default is stricter than READ COMMITTED. */
internal class RepeatableReadConnectionProvider(
    private val config: ServerConfig,
) : DatabaseConnectionProvider {
    override fun connection(): Connection = DriverManager.getConnection(
        config.databaseConnectionUrl,
        config.databaseUser,
        config.databasePassword,
    ).also { connection ->
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
    }
}
