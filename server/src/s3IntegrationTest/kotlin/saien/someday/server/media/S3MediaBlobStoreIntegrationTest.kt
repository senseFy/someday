package saien.someday.server.media

import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

class S3MediaBlobStoreIntegrationTest {
    private val environment = System.getenv()
    private val endpoint = URI(requiredEnvironment("SOMEDAY_S3_TEST_ENDPOINT"))
    private val bucket = requiredEnvironment("SOMEDAY_S3_TEST_BUCKET")
    private val region = environment["SOMEDAY_S3_TEST_REGION"]?.trim().orEmpty().ifBlank { "us-east-1" }

    @Test
    fun realServicePreservesConditionalImmutableObjectsAndDistinguishesMissingKeys() {
        val key = uniqueKey("11")
        val missingKey = uniqueKey("00")
        val bytes = ByteArray(257) { index -> (index * 7).toByte() }
        val digest = sha256(bytes)

        createStore().use { store ->
            assertNull(store.head(missingKey))
            assertNull(store.read(missingKey, MAX_TEST_OBJECT_BYTES))
            assertEquals(MediaBlobPutResult.Stored(false), store.putImmutable(key, bytes, digest))
            assertEquals(MediaBlobPutResult.Stored(true), store.putImmutable(key, bytes, digest))
            assertEquals(MediaBlobMetadata(bytes.size.toLong(), digest), store.head(key))
            assertContentEquals(bytes, checkNotNull(store.read(key, bytes.size)).bytes)

            val mismatch = ByteArray(bytes.size) { 99 }
            assertEquals(MediaBlobPutResult.ImmutableMismatch, store.putImmutable(key, mismatch, sha256(mismatch)))
            assertContentEquals(bytes, checkNotNull(store.read(key, bytes.size)).bytes)
        }

        createStore().use { store ->
            assertContentEquals(bytes, checkNotNull(store.read(key, bytes.size)).bytes)
        }
    }

    @Test
    fun existingMetadataCannotHideDivergentPayloadBytes() {
        val key = uniqueKey("22")
        val expected = ByteArray(193) { 23 }
        val divergent = ByteArray(expected.size) { 47 }
        val expectedDigest = sha256(expected)

        restrictedClient().use { client ->
            client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey(key))
                    .metadata(mapOf(SHA256_METADATA_KEY to expectedDigest))
                    .build(),
                RequestBody.fromBytes(divergent),
            )
        }

        createStore().use { store ->
            assertEquals(
                MediaBlobPutResult.ImmutableMismatch,
                store.putImmutable(key, expected, expectedDigest),
            )
            assertEquals(MediaBlobMetadata(divergent.size.toLong(), expectedDigest), store.head(key))
            assertNull(store.read(key, expected.size))
        }
    }

    private fun createStore(): S3MediaBlobStore = S3MediaBlobStore(
        S3MediaBlobStoreConfig(
            bucket = bucket,
            region = region,
            endpoint = endpoint,
            pathStyleAccess = true,
            maxObjectBytes = MAX_TEST_OBJECT_BYTES,
        ),
    )

    private fun restrictedClient(): S3Client = S3Client.builder()
        .region(Region.of(region))
        .endpointOverride(endpoint)
        .forcePathStyle(true)
        .credentialsProvider(DefaultCredentialsProvider.builder().build())
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build()

    private fun uniqueKey(prefix: String): MediaBlobKey = MediaBlobKey(
        userId = UUID.randomUUID(),
        workspaceId = "workspace-${UUID.randomUUID().toString().replace("-", "").take(32)}",
        mediaId = prefix + UUID.randomUUID().toString().replace("-", "").repeat(2).take(62),
    )

    private fun objectKey(key: MediaBlobKey): String =
        "media/v1/${key.userId}/${key.workspaceId}/${key.mediaId}.bin"

    private fun requiredEnvironment(name: String): String =
        environment[name]?.trim()?.takeIf(String::isNotEmpty)
            ?: error("$name is required for the real S3-compatible integration test.")

    private fun sha256(bytes: ByteArray): String =
        "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"

    private companion object {
        const val MAX_TEST_OBJECT_BYTES = 1024
        const val SHA256_METADATA_KEY = "someday-ciphertext-sha256"
    }
}
