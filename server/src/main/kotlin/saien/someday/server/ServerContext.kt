package saien.someday.server

import saien.someday.server.auth.Argon2idPasswordHasher
import saien.someday.server.auth.FixedWindowRateLimiter
import saien.someday.server.auth.PasswordHasher
import saien.someday.server.auth.TokenService
import saien.someday.server.persistence.AdminRepository
import saien.someday.server.persistence.AuthRepository
import saien.someday.server.persistence.DatabaseMigrator
import saien.someday.server.persistence.SyncV2Repository
import java.time.Instant

class ServerContext(
    val config: ServerConfig,
    val repository: AuthRepository,
    val syncV2Repository: SyncV2Repository,
    val adminRepository: AdminRepository,
    val credentialHasher: PasswordHasher,
    val dummyPasswordHash: String,
    val tokenService: TokenService,
    val rateLimiter: FixedWindowRateLimiter,
    val syncV2RateLimiter: FixedWindowRateLimiter,
    val startedAt: Instant,
) {
    companion object {
        fun create(config: ServerConfig = ServerConfig.fromEnvironment()): ServerContext {
            DatabaseMigrator.migrate(config)
            val startedAt = Instant.now()
            val credentialHasher = Argon2idPasswordHasher(
                maxConcurrent = config.argon2MaxConcurrent,
            )
            return ServerContext(
                config = config,
                repository = AuthRepository(config),
                syncV2Repository = SyncV2Repository(config),
                adminRepository = AdminRepository(config, startedAt),
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
                syncV2RateLimiter = FixedWindowRateLimiter(
                    maxAttempts = config.syncV2RateLimitMaxAttempts,
                    window = config.rateLimitWindow,
                    maxBuckets = config.rateLimitMaxBuckets,
                ),
                startedAt = startedAt,
            )
        }

        private const val DUMMY_PASSWORD_INPUT =
            "someday-authentication-timing-equalization-input"
    }
}
