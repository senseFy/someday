package saien.someday.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerConfigTest {
    @Test
    fun localDefaultsStayLoopbackFriendlyButUseAnEphemeralJwtSecret() {
        val first = ServerConfig.fromEnvironment(emptyMap())
        val second = ServerConfig.fromEnvironment(emptyMap())

        assertEquals(ServerDeploymentMode.LOCAL, first.deploymentMode)
        assertEquals("127.0.0.1", first.bindHost)
        assertEquals(3180, first.port)
        assertEquals("http://127.0.0.1:3180", first.publicBaseUrl)
        assertTrue(first.registrationEnabled)
        assertFalse(first.trustProxyHeaders)
        assertFalse(first.secureAdminCookies)
        assertTrue(first.jwtSecret.encodeToByteArray().size >= 32)
        assertNotEquals(first.jwtSecret, second.jwtSecret)
    }

    @Test
    fun productionRequiresExplicitSecretsAndDefaultsRegistrationClosed() {
        val config = ServerConfig.fromEnvironment(productionEnvironment())

        assertEquals(ServerDeploymentMode.PRODUCTION, config.deploymentMode)
        assertEquals("https://notes.example.com", config.publicBaseUrl)
        assertEquals("https://notes.example.com", config.publicOrigin)
        assertFalse(config.registrationEnabled)
        assertFalse(config.trustProxyHeaders)
        assertTrue(config.secureAdminCookies)
    }

    @Test
    fun publicOriginNormalizesDefaultPorts() {
        val config = ServerConfig.fromEnvironment(
            productionEnvironment() + ("SOMEDAY_PUBLIC_BASE_URL" to "HTTPS://Notes.Example.com:443/"),
        )

        assertEquals("https://notes.example.com", config.publicBaseUrl)
        assertEquals("https://notes.example.com", config.publicOrigin)
    }

    @Test
    fun productionRejectsMissingOrWeakSecurityInputs() {
        assertFailsWith<IllegalStateException> {
            ServerConfig.fromEnvironment(productionEnvironment() - "SOMEDAY_JWT_SECRET")
        }
        assertFailsWith<IllegalStateException> {
            ServerConfig.fromEnvironment(productionEnvironment() - "SOMEDAY_DB_PASSWORD")
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(
                productionEnvironment() + ("SOMEDAY_PUBLIC_BASE_URL" to "http://notes.example.com"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(
                productionEnvironment() + ("SOMEDAY_JWT_SECRET" to "too-short"),
            )
        }
    }

    @Test
    fun booleanAndNumericEnvironmentValuesAreStrict() {
        assertFailsWith<IllegalStateException> {
            ServerConfig.fromEnvironment(mapOf("SOMEDAY_REGISTRATION_ENABLED" to "yes"))
        }
        assertFailsWith<IllegalStateException> {
            ServerConfig.fromEnvironment(mapOf("SOMEDAY_PORT" to "not-a-port"))
        }
    }

    private fun productionEnvironment(): Map<String, String> = mapOf(
        "SOMEDAY_DEPLOYMENT_MODE" to "production",
        "SOMEDAY_PUBLIC_BASE_URL" to "https://notes.example.com/",
        "SOMEDAY_DB_URL" to "jdbc:postgresql://database.internal:5432/someday",
        "SOMEDAY_DB_USER" to "someday_app",
        "SOMEDAY_DB_PASSWORD" to "database-test-secret",
        "SOMEDAY_JWT_SECRET" to "0123456789abcdef0123456789abcdef",
    )
}
