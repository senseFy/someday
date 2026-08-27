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
    ): MediaBlobPutResult {
        require(bytes.size in 1..config.maxObjectBytes) { "Media blob exceeds its protocol bound." }
        require(expectedSha256 == sha256(bytes)) { "Media blob digest does not match its bytes." }

        val request = PutObjectRequest.builder()
            .bucket(config.bucket)
            .key(objectKey(key))
            .contentLength(bytes.size.toLong())
            .contentType(BINARY_CONTENT_TYPE)
            .metadata(mapOf(SHA256_METADATA_KEY to expectedSha256))
            .ifNoneMatch("*")
            .build()
        try {
            client.putObject(request, RequestBody.fromBytes(bytes))
            return MediaBlobPutResult.Stored(idempotentReplay = false)
        } catch (exception: S3Exception) {
            if (exception.statusCode() != PRECONDITION_FAILED) throw exception
        }

        val existing = getBounded(key, config.maxObjectBytes)
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

    override fun head(key: MediaBlobKey): MediaBlobMetadata? {
        val response = try {
            client.headObject(
                HeadObjectRequest.builder()
                    .bucket(config.bucket)
                    .key(objectKey(key))
                    .build(),
            )
        } catch (exception: S3Exception) {
            // S3 deliberately returns 403 for a missing object when the caller cannot
            // list its prefix. The deployment policy therefore grants ListBucket only
            // for media/v1/*; never reinterpret 403 as absence here.
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

    override fun read(key: MediaBlobKey, maxBytes: Int): MediaBlobValue? {
        require(maxBytes > 0)
        val stored = getBounded(key, min(maxBytes, config.maxObjectBytes)) ?: return null
        if (stored.storedSha256 != stored.actualSha256) return null
        return MediaBlobValue(
            metadata = MediaBlobMetadata(stored.bytes.size.toLong(), stored.actualSha256),
            bytes = stored.bytes,
        )
    }

    override fun close() {
        client.close()
    }

    private fun getBounded(key: MediaBlobKey, maxBytes: Int): StoredObject? {
        val response = try {
            client.getObject(
                GetObjectRequest.builder()
                    .bucket(config.bucket)
                    .key(objectKey(key))
                    .build(),
            )
        } catch (exception: S3Exception) {
            // Keep authorization failures distinct from a proven missing object. See
            // the matching prefix-scoped ListBucket requirement in the runtime policy.
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
        const val NOT_FOUND = 404
        const val PRECONDITION_FAILED = 412
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
