package saien.someday.server.persistence

import java.sql.Connection
import java.sql.DriverManager
import saien.someday.server.ServerConfig
import saien.someday.server.ServerDeploymentMode

/** Database checks that must pass before either production entry point mutates schema or data. */
internal object ProductionDatabasePreflight {
    fun verify(config: ServerConfig) {
        if (config.deploymentMode != ServerDeploymentMode.PRODUCTION) return
        DriverManager.getConnection(
            config.databaseConnectionUrl,
            config.databaseUser,
            config.databasePassword,
        ).use(::verifyConnection)
    }

    internal fun verifyConnection(connection: Connection) {
        val facts = connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT
                    current_setting('server_version_num')::integer,
                    rolsuper,
                    rolbypassrls
                FROM pg_roles
                WHERE rolname = current_user
                """.trimIndent(),
            ).use { result ->
                check(result.next()) { "The current PostgreSQL role was not found in pg_roles." }
                ProductionDatabaseFacts(
                    serverMajorVersion = result.getInt(1) / POSTGRES_VERSION_NUMBER_MAJOR_DIVISOR,
                    superuser = result.getBoolean(2),
                    bypassRls = result.getBoolean(3),
                )
            }
        }
        validate(facts)
    }

    internal fun validate(facts: ProductionDatabaseFacts) {
        require(facts.serverMajorVersion == SUPPORTED_POSTGRES_MAJOR_VERSION) {
            "Production requires PostgreSQL $SUPPORTED_POSTGRES_MAJOR_VERSION; " +
                "connected to PostgreSQL ${facts.serverMajorVersion}."
        }
        require(!facts.superuser) {
            "SOMEDAY_DB_USER must not be a PostgreSQL superuser in production."
        }
        require(!facts.bypassRls) {
            "SOMEDAY_DB_USER must not have PostgreSQL BYPASSRLS in production."
        }
    }

    private const val POSTGRES_VERSION_NUMBER_MAJOR_DIVISOR = 10_000
    private const val SUPPORTED_POSTGRES_MAJOR_VERSION = 17
}

internal data class ProductionDatabaseFacts(
    val serverMajorVersion: Int,
    val superuser: Boolean,
    val bypassRls: Boolean,
)
