package saien.someday.server

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import saien.someday.server.auth.FixedWindowRateLimiter
import saien.someday.server.auth.PasswordHasher
import saien.someday.server.auth.TokenService
import saien.someday.server.media.MediaBlobKey
import saien.someday.server.media.MediaBlobMetadata
import saien.someday.server.media.MediaBlobPutResult
import saien.someday.server.media.MediaBlobStore
import saien.someday.server.media.MediaBlobValue
import saien.someday.server.persistence.AdminRepository
import saien.someday.server.persistence.AuthRepository
import saien.someday.server.persistence.DatabaseConnectionProvider
import saien.someday.server.persistence.SyncV2Repository
import saien.someday.server.persistence.SystemV3MediaRepository

class ServerContextLifecycleTest {
    @Test
    fun closeReleasesInjectedDatabaseAndMediaResourcesExactlyOnce() {
        val config = ServerConfig.fromEnvironment(emptyMap())
        val unavailableConnections = DatabaseConnectionProvider { error("Database access is not expected.") }
        val databaseLifecycle = CountingCloseable()
        val mediaBlobStore = CloseableMediaBlobStore()
        val startedAt = Instant.EPOCH
        val limiter = { FixedWindowRateLimiter(1, Duration.ofSeconds(1), 1) }
        val context = ServerContext(
            config = config,
            repository = AuthRepository(config, unavailableConnections),
            syncV2Repository = SyncV2Repository(config, unavailableConnections),
            systemV3MediaRepository = SystemV3MediaRepository(
                config,
                mediaBlobStore,
                unavailableConnections,
            ),
            adminRepository = AdminRepository(config, startedAt, unavailableConnections),
            credentialHasher = NoOpPasswordHasher,
            dummyPasswordHash = "unused",
            tokenService = TokenService(config),
            rateLimiter = limiter(),
            systemV3RateLimiter = limiter(),
            startedAt = startedAt,
            mediaBlobStoreLifecycle = mediaBlobStore,
            databaseConnectionPool = databaseLifecycle,
        )

        context.close()
        context.close()

        assertEquals(1, databaseLifecycle.closeCount)
        assertEquals(1, mediaBlobStore.closeCount)
    }

    private class CountingCloseable : AutoCloseable {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }

    private class CloseableMediaBlobStore : MediaBlobStore, AutoCloseable {
        var closeCount: Int = 0
            private set

        override fun putImmutable(
            key: MediaBlobKey,
            bytes: ByteArray,
            expectedSha256: String,
        ): MediaBlobPutResult = error("Media access is not expected.")

        override fun head(key: MediaBlobKey): MediaBlobMetadata? = error("Media access is not expected.")

        override fun read(key: MediaBlobKey, maxBytes: Int): MediaBlobValue? =
            error("Media access is not expected.")

        override fun close() {
            closeCount += 1
        }
    }

    private data object NoOpPasswordHasher : PasswordHasher {
        override fun hash(password: String): String = "unused"

        override fun verify(hash: String, password: String): Boolean = false
    }
}
