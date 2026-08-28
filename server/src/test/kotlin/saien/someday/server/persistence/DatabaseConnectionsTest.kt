package saien.someday.server.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import saien.someday.server.ServerConfig

class DatabaseConnectionsTest {
    @Test
    fun hikariConfigurationUsesTheBoundedServerBudget() {
        val config = ServerConfig.fromEnvironment(
            mapOf("SOMEDAY_DB_MAX_POOL_SIZE" to "7"),
        )

        val hikari = hikariConfig(config)

        assertEquals(7, hikari.maximumPoolSize)
        assertEquals(2, hikari.minimumIdle)
        assertEquals("someday-postgres", hikari.poolName)
        assertEquals("true", hikari.dataSourceProperties.getProperty("tcpKeepAlive"))
    }

    @Test
    fun oneConnectionBudgetAlsoBoundsMinimumIdle() {
        val config = ServerConfig.fromEnvironment(
            mapOf("SOMEDAY_DB_MAX_POOL_SIZE" to "1"),
        )

        val hikari = hikariConfig(config)

        assertEquals(1, hikari.maximumPoolSize)
        assertEquals(1, hikari.minimumIdle)
    }

    @Test
    fun hikariUsesTheCertificateAndHostnameVerifyingJdbcUrl() {
        val config = ServerConfig.fromEnvironment(
            mapOf(
                "SOMEDAY_DB_URL" to "jdbc:postgresql://database.example.com/someday",
                "SOMEDAY_DB_TLS_MODE" to "verify-full",
            ),
        )

        val hikari = hikariConfig(config)

        assertEquals(
            "jdbc:postgresql://database.example.com/someday" +
                "?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory",
            hikari.jdbcUrl,
        )
    }
}
