package saien.someday.server

import java.net.URI
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

enum class ServerDeploymentMode {
    LOCAL,
    PRODUCTION,
}

const val DEFAULT_DATABASE_MAX_POOL_SIZE: Int = 10
const val MAX_DATABASE_MAX_POOL_SIZE: Int = 32

sealed interface ServerMediaStorage {
    data class FileSystem(val directory: Path) : ServerMediaStorage

    data class S3(
        val bucket: String,
        val region: String,
        val endpoint: URI?,
        val pathStyle: Boolean,
    ) : ServerMediaStorage {
        init {
            require(bucket.isNotBlank() && bucket.none { it.isWhitespace() || it.isISOControl() || it == '/' }) {
                "SOMEDAY_MEDIA_S3_BUCKET must be a non-blank bucket name without whitespace or slashes."
            }
            require(region.isNotBlank() && region.none { it.isWhitespace() }) {
                "SOMEDAY_MEDIA_S3_REGION must be a non-blank region without whitespace."
            }
        }
    }
}

data class ServerConfig(
    val deploymentMode: ServerDeploymentMode,
    val bindHost: String,
    val port: Int,
    val publicBaseUrl: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val databaseMaxPoolSize: Int,
    val mediaStorage: ServerMediaStorage,
    val mediaQuotaBytes: Long,
    val jwtIssuer: String,
    val jwtAudience: String,
    val jwtSecret: String,
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
    val registrationEnabled: Boolean,
    val trustProxyHeaders: Boolean,
    val rateLimitMaxAttempts: Int,
    val rateLimitWindow: Duration,
    val rateLimitMaxBuckets: Int,
    val systemV3RateLimitMaxAttempts: Int,
    val argon2MaxConcurrent: Int,
) {
    val secureAdminCookies: Boolean
        get() = deploymentMode == ServerDeploymentMode.PRODUCTION

    val publicOrigin: String
        get() = normalizedOrigin(publicBaseUrl)

    init {
        require(bindHost.isNotBlank()) { "SOMEDAY_HOST must not be blank." }
        require(port in 1..65535) { "SOMEDAY_PORT must be between 1 and 65535." }
        require(databaseUrl.isNotBlank()) { "SOMEDAY_DB_URL must not be blank." }
        require(databaseUser.isNotBlank()) { "SOMEDAY_DB_USER must not be blank." }
        require(databasePassword.isNotBlank()) { "SOMEDAY_DB_PASSWORD must not be blank." }
        require(databaseMaxPoolSize in 1..MAX_DATABASE_MAX_POOL_SIZE) {
            "SOMEDAY_DB_MAX_POOL_SIZE must be between 1 and $MAX_DATABASE_MAX_POOL_SIZE."
        }
        require(mediaQuotaBytes > 0L) { "SOMEDAY_MEDIA_QUOTA_BYTES must be positive." }
        require(jwtIssuer.isNotBlank()) { "SOMEDAY_JWT_ISSUER must not be blank." }
        require(jwtAudience.isNotBlank()) { "SOMEDAY_JWT_AUDIENCE must not be blank." }
        require(jwtSecret.encodeToByteArray().size >= MINIMUM_JWT_SECRET_BYTES) {
            "SOMEDAY_JWT_SECRET must contain at least $MINIMUM_JWT_SECRET_BYTES UTF-8 bytes."
        }
        require(!accessTokenTtl.isZero && !accessTokenTtl.isNegative) {
            "SOMEDAY_ACCESS_TOKEN_SECONDS must be positive."
        }
        require(!refreshTokenTtl.isZero && !refreshTokenTtl.isNegative) {
            "SOMEDAY_REFRESH_TOKEN_DAYS must be positive."
        }
        require(rateLimitMaxAttempts > 0) { "SOMEDAY_RATE_LIMIT_MAX_ATTEMPTS must be positive." }
        require(!rateLimitWindow.isZero && !rateLimitWindow.isNegative) {
            "SOMEDAY_RATE_LIMIT_WINDOW_SECONDS must be positive."
        }
        require(rateLimitMaxBuckets > 0) { "SOMEDAY_RATE_LIMIT_MAX_BUCKETS must be positive." }
        require(systemV3RateLimitMaxAttempts > 0) {
            "SOMEDAY_SYSTEM_V3_RATE_LIMIT_MAX_ATTEMPTS must be positive."
        }
        require(argon2MaxConcurrent > 0) { "SOMEDAY_ARGON2_MAX_CONCURRENT must be positive." }

        val publicUri = validatedPublicBaseUri(publicBaseUrl)
        if (deploymentMode == ServerDeploymentMode.PRODUCTION) {
            require(publicUri.scheme.equals("https", ignoreCase = true)) {
                "SOMEDAY_PUBLIC_BASE_URL must use HTTPS in production mode."
            }
            require(jwtSecret != LOCAL_DEVELOPMENT_JWT_SECRET) {
                "The local development JWT secret cannot be used in production mode."
            }
            require(mediaStorage !is ServerMediaStorage.FileSystem || mediaStorage.directory.isAbsolute) {
                "SOMEDAY_MEDIA_BLOB_DIR must be an absolute path for filesystem storage in production mode."
            }
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfig {
            val deploymentMode = environment["SOMEDAY_DEPLOYMENT_MODE"]
                ?.trim()
                ?.lowercase()
                ?.let { value ->
                    when (value) {
                        "local" -> ServerDeploymentMode.LOCAL
                        "production" -> ServerDeploymentMode.PRODUCTION
                        else -> error("SOMEDAY_DEPLOYMENT_MODE must be local or production.")
                    }
                }
                ?: ServerDeploymentMode.LOCAL
            val production = deploymentMode == ServerDeploymentMode.PRODUCTION
            val port = environment.intValue("SOMEDAY_PORT", 3180)
            val bindHost = environment.nonBlank("SOMEDAY_HOST") ?: "127.0.0.1"
            val publicBaseUrl = environment.nonBlank("SOMEDAY_PUBLIC_BASE_URL")
                ?: if (production) {
                    error("SOMEDAY_PUBLIC_BASE_URL is required in production mode.")
                } else {
                    "http://127.0.0.1:$port"
                }

            return ServerConfig(
                deploymentMode = deploymentMode,
                bindHost = bindHost,
                port = port,
                publicBaseUrl = normalizedPublicBaseUrl(publicBaseUrl),
                databaseUrl = environment.productionValue(
                    name = "SOMEDAY_DB_URL",
                    production = production,
                    localDefault = "jdbc:postgresql://127.0.0.1:54329/someday",
                ),
                databaseUser = environment.productionValue(
                    name = "SOMEDAY_DB_USER",
                    production = production,
                    localDefault = "someday",
                ),
                databasePassword = environment.productionValue(
                    name = "SOMEDAY_DB_PASSWORD",
                    production = production,
                    localDefault = "someday",
                ),
                databaseMaxPoolSize = environment.intValue(
                    "SOMEDAY_DB_MAX_POOL_SIZE",
                    DEFAULT_DATABASE_MAX_POOL_SIZE,
                ),
                mediaStorage = environment.mediaStorage(production),
                mediaQuotaBytes = environment.longValue(
                    "SOMEDAY_MEDIA_QUOTA_BYTES",
                    DEFAULT_MEDIA_QUOTA_BYTES,
                ),
                jwtIssuer = environment.nonBlank("SOMEDAY_JWT_ISSUER") ?: "someday-local",
                jwtAudience = environment.nonBlank("SOMEDAY_JWT_AUDIENCE") ?: "someday-clients",
                jwtSecret = environment.nonBlank("SOMEDAY_JWT_SECRET")
                    ?: if (production) {
                        error("SOMEDAY_JWT_SECRET is required in production mode.")
                    } else {
                        randomLocalJwtSecret()
                    },
                accessTokenTtl = Duration.ofSeconds(
                    environment.longValue("SOMEDAY_ACCESS_TOKEN_SECONDS", 900),
                ),
                refreshTokenTtl = Duration.ofDays(
                    environment.longValue("SOMEDAY_REFRESH_TOKEN_DAYS", 30),
                ),
                registrationEnabled = environment.booleanValue(
                    "SOMEDAY_REGISTRATION_ENABLED",
                    default = !production,
                ),
                trustProxyHeaders = environment.booleanValue(
                    "SOMEDAY_TRUST_PROXY_HEADERS",
                    default = false,
                ),
                rateLimitMaxAttempts = environment.intValue("SOMEDAY_RATE_LIMIT_MAX_ATTEMPTS", 5),
                rateLimitWindow = Duration.ofSeconds(
                    environment.longValue("SOMEDAY_RATE_LIMIT_WINDOW_SECONDS", 60),
                ),
                rateLimitMaxBuckets = environment.intValue("SOMEDAY_RATE_LIMIT_MAX_BUCKETS", 10_000),
                // A single bounded System V3 coordinator pass can issue two sets of up
                // to eight paged pulls, in addition to checkpoint/outbox work.
                // Keep this budget independent from the deliberately tight
                // authentication brute-force budget above.
                systemV3RateLimitMaxAttempts =
                    environment.intValue("SOMEDAY_SYSTEM_V3_RATE_LIMIT_MAX_ATTEMPTS", 256),
                argon2MaxConcurrent = environment.intValue("SOMEDAY_ARGON2_MAX_CONCURRENT", 2),
            )
        }
    }
}

private fun Map<String, String>.nonBlank(name: String): String? =
    get(name)?.trim()?.takeIf { it.isNotEmpty() }

private fun Map<String, String>.productionValue(
    name: String,
    production: Boolean,
    localDefault: String,
): String =
    nonBlank(name) ?: if (production) {
        error("$name is required in production mode.")
    } else {
        localDefault
    }

private fun Map<String, String>.mediaStorage(production: Boolean): ServerMediaStorage {
    val backend = nonBlank("SOMEDAY_MEDIA_BACKEND")
        ?: if (containsKey("SOMEDAY_MEDIA_BACKEND")) {
            error("SOMEDAY_MEDIA_BACKEND must not be blank.")
        } else if (production) {
            error("SOMEDAY_MEDIA_BACKEND is required in production mode.")
        } else {
            "filesystem"
        }
    return when (backend.lowercase()) {
        "filesystem" -> ServerMediaStorage.FileSystem(
            Path.of(
                productionValue(
                    name = "SOMEDAY_MEDIA_BLOB_DIR",
                    production = production,
                    localDefault = "build/local-media-blobs",
                ),
            ),
        )
        "s3" -> ServerMediaStorage.S3(
            bucket = requiredValue("SOMEDAY_MEDIA_S3_BUCKET"),
            region = requiredValue("SOMEDAY_MEDIA_S3_REGION"),
            endpoint = nonBlank("SOMEDAY_MEDIA_S3_ENDPOINT")?.let(::validatedS3Endpoint),
            pathStyle = booleanValue("SOMEDAY_MEDIA_S3_PATH_STYLE", default = false),
        )
        else -> error("SOMEDAY_MEDIA_BACKEND must be filesystem or s3.")
    }
}

private fun Map<String, String>.requiredValue(name: String): String =
    nonBlank(name) ?: error("$name is required for the selected media backend.")

private fun validatedS3Endpoint(value: String): URI {
    val uri = runCatching { URI(value) }.getOrElse {
        throw IllegalArgumentException("SOMEDAY_MEDIA_S3_ENDPOINT must be a valid URL.", it)
    }
    require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
        "SOMEDAY_MEDIA_S3_ENDPOINT must use HTTP or HTTPS."
    }
    require(
        !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/"),
    ) {
        "SOMEDAY_MEDIA_S3_ENDPOINT must be an origin without credentials, path, query, or fragment."
    }
    return uri
}

private fun Map<String, String>.booleanValue(name: String, default: Boolean): Boolean =
    nonBlank(name)?.toBooleanStrictOrNull()
        ?: if (containsKey(name)) {
            error("$name must be true or false.")
        } else {
            default
        }

private fun Map<String, String>.intValue(name: String, default: Int): Int =
    nonBlank(name)?.toIntOrNull()
        ?: if (containsKey(name)) {
            error("$name must be an integer.")
        } else {
            default
        }

private fun Map<String, String>.longValue(name: String, default: Long): Long =
    nonBlank(name)?.toLongOrNull()
        ?: if (containsKey(name)) {
            error("$name must be an integer.")
        } else {
            default
        }

private fun normalizedPublicBaseUrl(value: String): String {
    val uri = validatedPublicBaseUri(value)
    val scheme = uri.scheme.lowercase()
    val port = when {
        scheme == "http" && uri.port == 80 -> -1
        scheme == "https" && uri.port == 443 -> -1
        else -> uri.port
    }
    return URI(scheme, null, uri.host.lowercase(), port, null, null, null)
        .toString()
        .removeSuffix("/")
}

private fun normalizedOrigin(value: String): String = normalizedPublicBaseUrl(value)

private fun validatedPublicBaseUri(value: String): URI {
    val uri = runCatching { URI(value) }
        .getOrElse { error("SOMEDAY_PUBLIC_BASE_URL must be an absolute HTTP(S) URL.") }
    require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
        "SOMEDAY_PUBLIC_BASE_URL must use HTTP or HTTPS."
    }
    require(!uri.host.isNullOrBlank() && uri.isAbsolute) {
        "SOMEDAY_PUBLIC_BASE_URL must include a host."
    }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "SOMEDAY_PUBLIC_BASE_URL must not include credentials, a query, or a fragment."
    }
    require(uri.path.isNullOrBlank() || uri.path == "/") {
        "SOMEDAY_PUBLIC_BASE_URL must not include a path."
    }
    return uri
}

private fun randomLocalJwtSecret(): String {
    val bytes = ByteArray(MINIMUM_JWT_SECRET_BYTES)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private const val MINIMUM_JWT_SECRET_BYTES = 32
private const val LOCAL_DEVELOPMENT_JWT_SECRET = "someday-local-development-secret-change-before-production"
private const val DEFAULT_MEDIA_QUOTA_BYTES = 5L * 1024L * 1024L * 1024L
