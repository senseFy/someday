package saien.someday.server.maintenance

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.S3Exception
import saien.someday.server.media.S3MediaBlobStoreConfig

/** Complete enumeration is an operator-certified provider capability, not inferred from PUT/GET. */
class S3RetiredMediaStore internal constructor(
    private val client: S3Client,
    private val bucket: String,
    private val batchSize: Int = 250,
    private val maxPagesPerScan: Int = Int.MAX_VALUE,
    override val storageIdentity: String = "s3:" + storageIdentityDigest(bucket),
) : RetiredMediaStore {
    constructor(config: S3MediaBlobStoreConfig, maintenanceCredentials: AwsCredentialsProvider) : this(
        buildMaintenanceClient(config, maintenanceCredentials), config.bucket,
        storageIdentity = "s3:" + storageIdentityDigest(config.bucket, config.region, config.endpoint?.normalize()?.toString().orEmpty(), config.pathStyleAccess.toString()),
    )

    override val backend = MaintenanceMediaBackend.S3

    init {
        require(batchSize in 1..1000)
        require(maxPagesPerScan > 0)
    }

    override fun scan(namespace: RetiredMediaNamespace, consume: (List<RetiredMediaEntry>) -> Unit): RetiredMediaScan = guarded {
        var entries = 0L
        var pages = 0
        val versionMarkers = mutableSetOf<Pair<String, String?>>()
        var keyMarker: String? = null
        var versionMarker: String? = null
        do {
            if (++pages > maxPagesPerScan) incomplete()
            val page = client.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).prefix(namespace.s3Prefix)
                    .maxKeys(batchSize).keyMarker(keyMarker).versionIdMarker(versionMarker).build(),
            )
            val found = page.versions().map { RetiredMediaEntry(it.key(), checkNotNull(it.versionId())) } +
                page.deleteMarkers().map { RetiredMediaEntry(it.key(), checkNotNull(it.versionId())) }
            if (page.commonPrefixes().isNotEmpty()) incomplete()
            if (found.size > batchSize) incomplete()
            found.forEach { validateEntry(namespace, it) }
            entries += found.size
            if (found.isNotEmpty()) consume(found)
            if (!page.isTruncated) break
            keyMarker = page.nextKeyMarker()?.takeIf(String::isNotEmpty) ?: incomplete()
            versionMarker = page.nextVersionIdMarker()?.takeIf(String::isNotEmpty) ?: incomplete()
            if (!keyMarker.startsWith(namespace.s3Prefix)) incomplete()
            if (!versionMarkers.add(keyMarker to versionMarker)) incomplete()
        } while (true)

        // Some unversioned implementations omit live keys from ListObjectVersions.
        // Both APIs must succeed; neither empty listing alone certifies a namespace.
        val continuationTokens = mutableSetOf<String>()
        var continuation: String? = null
        do {
            if (++pages > maxPagesPerScan) incomplete()
            val page = client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).prefix(namespace.s3Prefix)
                    .maxKeys(batchSize).continuationToken(continuation).build(),
            )
            val found = page.contents().map { RetiredMediaEntry(it.key()) }
            if (page.commonPrefixes().isNotEmpty()) incomplete()
            if (found.size > batchSize) incomplete()
            found.forEach { validateEntry(namespace, it) }
            entries += found.size
            if (found.isNotEmpty()) consume(found)
            if (!page.isTruncated) break
            continuation = page.nextContinuationToken()?.takeIf(String::isNotEmpty) ?: incomplete()
            if (!continuationTokens.add(continuation)) incomplete()
        } while (true)
        RetiredMediaScan(entries)
    }

    override fun deleteBatch(namespace: RetiredMediaNamespace, entries: List<RetiredMediaEntry>) = guarded {
        require(entries.size <= batchSize)
        for (entry in entries) {
            validateEntry(namespace, entry)
            val request = DeleteObjectRequest.builder().bucket(bucket).key(entry.key)
            entry.versionId?.let(request::versionId)
            // No governance bypass, retention changes, bucket policy changes or lifecycle writes.
            client.deleteObject(request.build())
        }
    }

    override fun close() { client.close() }

    private fun validateEntry(namespace: RetiredMediaNamespace, entry: RetiredMediaEntry) {
        if (!entry.key.startsWith(namespace.s3Prefix) || entry.versionId?.isEmpty() == true) {
            throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.UNSAFE_PATH)
        }
    }

    private fun incomplete(): Nothing = throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.INCOMPLETE_SCAN)

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (failure: MaintenanceMediaFailure) {
        throw failure
    } catch (failure: S3Exception) {
        val reason = when (failure.awsErrorDetails()?.errorCode()) {
            "ObjectLockedByBucketPolicy", "ObjectLockRetention", "ObjectLockLegalHold" -> MaintenanceMediaFailureReason.RETENTION_BLOCKED
            "InvalidAccessKeyId", "SignatureDoesNotMatch" -> MaintenanceMediaFailureReason.PERMISSION_DENIED
            "AccessDenied" -> MaintenanceMediaFailureReason.PERMISSION_OR_RETENTION_DENIED
            else -> if (failure.statusCode() == 403) MaintenanceMediaFailureReason.PERMISSION_OR_RETENTION_DENIED else MaintenanceMediaFailureReason.STORAGE_FAILURE
        }
        throw MaintenanceMediaFailure(reason, failure)
    } catch (failure: Exception) {
        throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.STORAGE_FAILURE, failure)
    }
}

private fun buildMaintenanceClient(config: S3MediaBlobStoreConfig, credentials: AwsCredentialsProvider): S3Client {
    val builder = S3Client.builder()
        .region(Region.of(config.region))
        .forcePathStyle(config.pathStyleAccess)
        .credentialsProvider(credentials)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .overrideConfiguration {
            it.apiCallTimeout(config.apiCallTimeout).apiCallAttemptTimeout(config.apiCallAttemptTimeout)
        }
    config.endpoint?.let(builder::endpointOverride)
    return builder.build()
}
