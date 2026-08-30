package saien.someday.server

import saien.someday.server.auth.Argon2idPasswordHasher
import saien.someday.server.auth.FixedWindowRateLimiter
import saien.someday.server.auth.PasswordHasher
import saien.someday.server.auth.TokenService
import saien.someday.server.media.MediaBlobStore
import saien.someday.server.media.verifyMediaBlobStoreStartup
import saien.someday.server.persistence.AdminRepository
import saien.someday.server.persistence.AuthRepository
import saien.someday.server.persistence.DatabaseConnectionPool
import saien.someday.server.persistence.DatabaseMigrator
import saien.someday.server.persistence.SyncV2Repository
import saien.someday.server.persistence.SystemV3MediaRepository
import saien.someday.server.persistence.WorkspaceRecoveryEnvelopeRepository
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class ServerContext(
    val config: ServerConfig,
    val repository: AuthRepository,
    val syncV2Repository: SyncV2Repository,
    val systemV3MediaRepository: SystemV3MediaRepository,
    val workspaceRecoveryEnvelopeRepository: WorkspaceRecoveryEnvelopeRepository,
    val adminRepository: AdminRepository,
    val credentialHasher: PasswordHasher,
    val dummyPasswordHash: String,
    val tokenService: TokenService,
    val rateLimiter: FixedWindowRateLimiter,
    val systemV3RateLimiter: FixedWindowRateLimiter,
    val startedAt: Instant,
    private val mediaBlobStoreLifecycle: AutoCloseable? = null,
    private val databaseConnectionPool: AutoCloseable = AutoCloseable {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeResources(mediaBlobStoreLifecycle, databaseConnectionPool)
        }
    }

    companion object {
        fun create(
            config: ServerConfig = ServerConfig.fromEnvironment(),
            mediaBlobStore: MediaBlobStore? = null,
        ): ServerContext {
            val resolvedMediaBlobStore = mediaBlobStore ?: createConfiguredMediaBlobStore(config)
            var databaseConnectionPool: DatabaseConnectionPool? = null
            try {
                DatabaseMigrator.migrate(config)
                if (mediaBlobStore == null) verifyMediaBlobStoreStartup(resolvedMediaBlobStore)
                val activeDatabaseConnectionPool = DatabaseConnectionPool.create(config)
                databaseConnectionPool = activeDatabaseConnectionPool
                val startedAt = Instant.now()
                val credentialHasher = Argon2idPasswordHasher(
                    maxConcurrent = config.argon2MaxConcurrent,
                )
                return ServerContext(
                    config = config,
                    repository = AuthRepository(config, activeDatabaseConnectionPool),
                    syncV2Repository = SyncV2Repository(config, activeDatabaseConnectionPool),
                    systemV3MediaRepository = SystemV3MediaRepository(
                        config,
                        resolvedMediaBlobStore,
                        activeDatabaseConnectionPool,
                    ),
                    workspaceRecoveryEnvelopeRepository = WorkspaceRecoveryEnvelopeRepository(
                        config,
                        activeDatabaseConnectionPool,
                    ),
                    adminRepository = AdminRepository(config, startedAt, activeDatabaseConnectionPool),
                    credentialHasher = credentialHasher,
                    // Unknown accounts verify against the same Argon2 parameters as
                    // real accounts so login timing does not disclose registration.
                    dummyPasswordHash = credentialHasher.hash(DUMMY_PASSWORD_INPUT),
                    tokenService = TokenService(config),
                    rateLimiter = FixedWindowRateLimiter(
                        maxAttempts = config.rateLimitMaxAttempts,
                        window = config.rateLimitWindow,
                        maxBuckets = config.rateLimitMaxBuckets,
                    ),
                    systemV3RateLimiter = FixedWindowRateLimiter(
                        maxAttempts = config.systemV3RateLimitMaxAttempts,
                        window = config.rateLimitWindow,
                        maxBuckets = config.rateLimitMaxBuckets,
                    ),
                    startedAt = startedAt,
                    mediaBlobStoreLifecycle = resolvedMediaBlobStore as? AutoCloseable,
                    databaseConnectionPool = activeDatabaseConnectionPool,
                )
            } catch (failure: Throwable) {
                runCatching { databaseConnectionPool?.close() }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                runCatching { (resolvedMediaBlobStore as? AutoCloseable)?.close() }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
        }

        private const val DUMMY_PASSWORD_INPUT =
            "someday-authentication-timing-equalization-input"
    }
}

private fun closeResources(vararg resources: AutoCloseable?) {
    var failure: Throwable? = null
    resources.forEach { resource ->
        if (resource != null) {
            try {
                resource.close()
            } catch (closeFailure: Throwable) {
                failure?.addSuppressed(closeFailure) ?: run { failure = closeFailure }
            }
        }
    }
    failure?.let { throw it }
}
