package saien.someday.server.media

import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import kotlin.math.min
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

data class S3MediaBlobStoreConfig(
    val bucket: String,
    val region: String,
    val endpoint: URI? = null,
    val pathStyleAccess: Boolean = false,
    val maxObjectBytes: Int,
    val apiCallTimeout: Duration = Duration.ofSeconds(30),
    val apiCallAttemptTimeout: Duration = Duration.ofSeconds(10),
) {
    init {
        require(bucket.isNotBlank() && bucket == bucket.trim()) { "S3 bucket must not be blank or padded." }
        require(bucket.none { it == '/' || it.isISOControl() }) { "S3 bucket contains invalid characters." }
        require(region.isNotBlank() && region == region.trim()) { "S3 region must not be blank or padded." }
        require(maxObjectBytes > 0) { "S3 media object bound must be positive." }
        require(apiCallTimeout.isPositive()) { "S3 API call timeout must be positive." }
        require(apiCallAttemptTimeout.isPositive()) { "S3 API attempt timeout must be positive." }
        require(apiCallAttemptTimeout <= apiCallTimeout) {
            "S3 API attempt timeout must not exceed the whole-call timeout."
        }
        endpoint?.let(::validateEndpoint)
    }
}

/**
 * Private, append-only S3-compatible storage for encrypted System V3 media.
 *
 * Every publication is a conditional single-object PUT. An existing key is
 * accepted only when a bounded GET proves that both its stored metadata and
 * its actual bytes match the requested immutable value.
 */
class S3MediaBlobStore private constructor(
    private val client: S3Client,
    private val config: S3MediaBlobStoreConfig,
) : MediaBlobStore, AutoCloseable {
    constructor(config: S3MediaBlobStoreConfig) : this(
        buildClient(config, DefaultCredentialsProvider.builder().build()),
        config,
    )

    internal constructor(
        config: S3MediaBlobStoreConfig,
        credentialsProvider: AwsCredentialsProvider,
    ) : this(buildClient(config, credentialsProvider), config)

    internal constructor(
        config: S3MediaBlobStoreConfig,
        client: S3Client,
    ) : this(client, config)

    override fun putImmutable(
        key: MediaBlobKey,
        bytes: ByteArray,
        expectedSha256: String,
    ): MediaBlobPutResult = putObjectKeyImmutable(objectKey(key), bytes, expectedSha256)

    override fun head(key: MediaBlobKey): MediaBlobMetadata? = headObjectKey(objectKey(key))

    override fun read(key: MediaBlobKey, maxBytes: Int): MediaBlobValue? =
        readObjectKey(objectKey(key), maxBytes)

    internal fun putStartupProbe(bytes: ByteArray, expectedSha256: String): MediaBlobPutResult =
        putObjectKeyImmutable(STARTUP_PROBE_OBJECT_KEY, bytes, expectedSha256)

    internal fun headStartupProbe(): MediaBlobMetadata? = headObjectKey(STARTUP_PROBE_OBJECT_KEY)

    internal fun readStartupProbe(maxBytes: Int): MediaBlobValue? =
        readObjectKey(STARTUP_PROBE_OBJECT_KEY, maxBytes)

    internal fun isStartupProbeMissingByMetadata(): Boolean =
        isObjectAbsentByHead(MISSING_STARTUP_PROBE_OBJECT_KEY)

    internal fun isStartupProbeMissingByRead(): Boolean =
        isObjectAbsentByBoundedGet(MISSING_STARTUP_PROBE_OBJECT_KEY)

    override fun close() {
        client.close()
    }

    private fun putObjectKeyImmutable(
        objectKey: String,
        bytes: ByteArray,
        expectedSha256: String,
    ): MediaBlobPutResult {
        require(bytes.size in 1..config.maxObjectBytes) { "Media blob exceeds its protocol bound." }
        require(expectedSha256 == sha256(bytes)) { "Media blob digest does not match its bytes." }

        val request = PutObjectRequest.builder()
            .bucket(config.bucket)
            .key(objectKey)
            .contentLength(bytes.size.toLong())
            .contentType(BINARY_CONTENT_TYPE)
            .metadata(mapOf(SHA256_METADATA_KEY to expectedSha256))
            .ifNoneMatch("*")
            .build()
        try {
            client.putObject(request, RequestBody.fromBytes(bytes))
            return MediaBlobPutResult.Stored(idempotentReplay = false)
        } catch (exception: S3Exception) {
            // R2 Bucket Lock can reject an existing key before If-None-Match is
            // evaluated. Accept only its exact error code in addition to S3's 412;
            // the bounded GET below still verifies the retained metadata and bytes.
            val immutableCollision = exception.statusCode() == PRECONDITION_FAILED ||
                exception.awsErrorDetails()?.errorCode() == R2_OBJECT_LOCKED_ERROR_CODE
            if (!immutableCollision) throw exception
        }

        val existing = getBounded(objectKey, config.maxObjectBytes)
            ?: error("S3 reported an immutable-key collision but the object was not readable.")
        return if (
            existing.bytes.size == bytes.size &&
            existing.storedSha256 == expectedSha256 &&
            existing.actualSha256 == expectedSha256
        ) {
            MediaBlobPutResult.Stored(idempotentReplay = true)
        } else {
            MediaBlobPutResult.ImmutableMismatch
        }
    }

    private fun headObjectKey(objectKey: String): MediaBlobMetadata? {
        val response = try {
            client.headObject(
                HeadObjectRequest.builder()
                    .bucket(config.bucket)
                    .key(objectKey)
                    .build(),
            )
        } catch (exception: S3Exception) {
            // A compatible provider must make absence distinguishable as 404.
            // Never reinterpret an authorization failure as a missing object.
            if (exception.statusCode() == NOT_FOUND) return null
            throw exception
        }
        val bytes = response.contentLength()
        if (bytes !in 1..config.maxObjectBytes.toLong()) return null
        val digest = response.metadata()[SHA256_METADATA_KEY]
            ?.takeIf(CANONICAL_SHA256::matches)
            ?: return null
        return MediaBlobMetadata(bytes, digest)
    }

    private fun isObjectAbsentByHead(objectKey: String): Boolean = try {
        client.headObject(
            HeadObjectRequest.builder()
                .bucket(config.bucket)
                .key(objectKey)
                .build(),
        )
        false
    } catch (exception: S3Exception) {
        if (exception.statusCode() == NOT_FOUND) true else throw exception
    }

    private fun isObjectAbsentByBoundedGet(objectKey: String): Boolean {
        val response = try {
            client.getObject(
                GetObjectRequest.builder()
                    .bucket(config.bucket)
                    .key(objectKey)
                    .range(MISSING_PROBE_READ_RANGE)
                    .build(),
            )
        } catch (exception: S3Exception) {
            if (exception.statusCode() == NOT_FOUND) return true
            throw exception
        }
        response.use { }
        return false
    }

    private fun readObjectKey(objectKey: String, maxBytes: Int): MediaBlobValue? {
        require(maxBytes > 0)
        val stored = getBounded(objectKey, min(maxBytes, config.maxObjectBytes)) ?: return null
        if (stored.storedSha256 != stored.actualSha256) return null
        return MediaBlobValue(
            metadata = MediaBlobMetadata(stored.bytes.size.toLong(), stored.actualSha256),
            bytes = stored.bytes,
        )
    }

    private fun getBounded(objectKey: String, maxBytes: Int): StoredObject? {
        val response = try {
            client.getObject(
                GetObjectRequest.builder()
                    .bucket(config.bucket)
                    .key(objectKey)
                    .build(),
            )
        } catch (exception: S3Exception) {
            // Keep authorization failures distinct from a proven missing object.
            if (exception.statusCode() == NOT_FOUND) return null
            throw exception
        }
        response.use { input ->
            val declaredBytes = input.response().contentLength()
            require(declaredBytes in 1..maxBytes.toLong()) { "Stored media blob exceeds its protocol bound." }
            val bytes = ByteArray(declaredBytes.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                check(read >= 0) { "S3 media response ended before its declared content length." }
                if (read > 0) offset += read
            }
            check(input.read() == -1) { "S3 media response exceeded its declared content length." }
            return StoredObject(
                storedSha256 = input.response().metadata()[SHA256_METADATA_KEY]
                    ?.takeIf(CANONICAL_SHA256::matches),
                actualSha256 = sha256(bytes),
                bytes = bytes,
            )
        }
    }

    private fun objectKey(key: MediaBlobKey): String =
        "media/v1/${key.userId}/${key.workspaceId}/${key.mediaId}.bin"

    private data class StoredObject(
        val storedSha256: String?,
        val actualSha256: String,
        val bytes: ByteArray,
    )

    private companion object {
        const val BINARY_CONTENT_TYPE = "application/octet-stream"
        const val SHA256_METADATA_KEY = "someday-ciphertext-sha256"
        const val STARTUP_PROBE_OBJECT_KEY = "media/v1/.someday-system/startup-probe-v1.bin"
        const val MISSING_STARTUP_PROBE_OBJECT_KEY =
            "media/v1/.someday-system/startup-probe-missing-v1.bin"
        const val MISSING_PROBE_READ_RANGE = "bytes=0-0"
        const val NOT_FOUND = 404
        const val PRECONDITION_FAILED = 412
        const val R2_OBJECT_LOCKED_ERROR_CODE = "ObjectLockedByBucketPolicy"
        val CANONICAL_SHA256 = Regex("^sha256:[0-9a-f]{64}$")

        fun buildClient(
            config: S3MediaBlobStoreConfig,
            credentialsProvider: AwsCredentialsProvider,
        ): S3Client {
            val builder = S3Client.builder()
                .region(Region.of(config.region))
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(config.pathStyleAccess)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration { override ->
                    override.apiCallTimeout(config.apiCallTimeout)
                    override.apiCallAttemptTimeout(config.apiCallAttemptTimeout)
                }
                .httpClientBuilder(
                    UrlConnectionHttpClient.builder()
                        .connectionTimeout(minOf(Duration.ofSeconds(5), config.apiCallAttemptTimeout))
                        .socketTimeout(config.apiCallAttemptTimeout),
                )
            config.endpoint?.let(builder::endpointOverride)
            return builder.build()
        }
    }
}

private fun validateEndpoint(endpoint: URI) {
    require(endpoint.isAbsolute && endpoint.scheme.lowercase() in setOf("http", "https")) {
        "S3 endpoint must be an absolute HTTP(S) URL."
    }
    require(endpoint.host != null && endpoint.userInfo == null) { "S3 endpoint must have a host and no credentials." }
    require(endpoint.query == null && endpoint.fragment == null) { "S3 endpoint must not contain a query or fragment." }
}

private fun sha256(bytes: ByteArray): String =
    "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"
