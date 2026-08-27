package saien.someday.server

import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
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
        assertEquals(DEFAULT_DATABASE_MAX_POOL_SIZE, first.databaseMaxPoolSize)
        assertTrue(first.registrationEnabled)
        assertFalse(first.trustProxyHeaders)
        assertFalse(first.secureAdminCookies)
        assertEquals(
            Path.of("build/local-media-blobs"),
            assertIs<ServerMediaStorage.FileSystem>(first.mediaStorage).directory,
        )
        assertEquals(5L * 1024L * 1024L * 1024L, first.mediaQuotaBytes)
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
            ServerConfig.fromEnvironment(productionEnvironment() - "SOMEDAY_MEDIA_BACKEND")
        }
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
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(mapOf("SOMEDAY_DB_MAX_POOL_SIZE" to "0"))
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(
                mapOf("SOMEDAY_DB_MAX_POOL_SIZE" to (MAX_DATABASE_MAX_POOL_SIZE + 1).toString()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(mapOf("SOMEDAY_MEDIA_QUOTA_BYTES" to "0"))
        }
        assertFailsWith<IllegalStateException> {
            ServerConfig.fromEnvironment(mapOf("SOMEDAY_MEDIA_BACKEND" to "other"))
        }
        assertFailsWith<IllegalStateException> {
            ServerConfig.fromEnvironment(
                mapOf(
                    "SOMEDAY_MEDIA_BACKEND" to "s3",
                    "SOMEDAY_MEDIA_S3_BUCKET" to "test",
                    "SOMEDAY_MEDIA_S3_REGION" to "test",
                    "SOMEDAY_MEDIA_S3_PATH_STYLE" to "yes",
                ),
            )
        }
    }

    @Test
    fun s3BackendIsExplicitAndDoesNotRequireAFilesystemDirectory() {
        val storage = assertIs<ServerMediaStorage.S3>(
            ServerConfig.fromEnvironment(
                productionEnvironment() - "SOMEDAY_MEDIA_BLOB_DIR" + mapOf(
                    "SOMEDAY_MEDIA_BACKEND" to "s3",
                    "SOMEDAY_MEDIA_S3_BUCKET" to "someday-private",
                    "SOMEDAY_MEDIA_S3_REGION" to "us-east-1",
                    "SOMEDAY_MEDIA_S3_ENDPOINT" to "https://objects.example.com/",
                    "SOMEDAY_MEDIA_S3_PATH_STYLE" to "true",
                ),
            ).mediaStorage,
        )

        assertEquals("someday-private", storage.bucket)
        assertEquals("us-east-1", storage.region)
        assertEquals(URI("https://objects.example.com/"), storage.endpoint)
        assertTrue(storage.pathStyle)
    }

    @Test
    fun selectedBackendFailsClosedWithoutReadingOtherBackendSettings() {
        assertFailsWith<IllegalStateException> {
            ServerConfig.fromEnvironment(mapOf("SOMEDAY_MEDIA_BACKEND" to "s3"))
        }
        val filesystem = ServerConfig.fromEnvironment(
            mapOf(
                "SOMEDAY_MEDIA_BACKEND" to "filesystem",
                "SOMEDAY_MEDIA_S3_PATH_STYLE" to "not-a-boolean",
            ),
        )
        assertIs<ServerMediaStorage.FileSystem>(filesystem.mediaStorage)
    }

    private fun productionEnvironment(): Map<String, String> = mapOf(
        "SOMEDAY_DEPLOYMENT_MODE" to "production",
        "SOMEDAY_PUBLIC_BASE_URL" to "https://notes.example.com/",
        "SOMEDAY_DB_URL" to "jdbc:postgresql://database.internal:5432/someday",
        "SOMEDAY_DB_USER" to "someday_app",
        "SOMEDAY_DB_PASSWORD" to "database-test-secret",
        "SOMEDAY_MEDIA_BACKEND" to "filesystem",
        "SOMEDAY_MEDIA_BLOB_DIR" to "/tmp/someday-server-config-test-media",
        "SOMEDAY_JWT_SECRET" to "0123456789abcdef0123456789abcdef",
    )
}
