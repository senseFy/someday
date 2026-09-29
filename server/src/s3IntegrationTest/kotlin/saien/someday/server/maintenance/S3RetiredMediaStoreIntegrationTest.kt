package saien.someday.server.maintenance

import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION
import saien.someday.server.media.S3MediaBlobStoreConfig

/** Disposable versioned bucket and a separate fixture/maintenance identity are mandatory. */
class S3RetiredMediaStoreIntegrationTest {
    private val environment = System.getenv()
    private val endpoint = URI(required("SOMEDAY_S3_TEST_ENDPOINT"))
    private val bucket = required("SOMEDAY_S3_TEST_BUCKET")
    private val region = environment["SOMEDAY_S3_TEST_REGION"] ?: "us-east-1"
    private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
        required("SOMEDAY_S3_MAINTENANCE_TEST_ACCESS_KEY_ID"), required("SOMEDAY_S3_MAINTENANCE_TEST_SECRET_ACCESS_KEY"),
    ))

    @Test fun paginatedVersionsDeleteMarkersAndOrphansAreRemovedWithoutTouchingCurrentOrOtherAccounts() {
        val retired = RetiredMediaNamespace(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION, "legacy")
        val current = RetiredMediaNamespace(retired.userId, UUID.randomUUID(), "incarnation-v1")
        val other = RetiredMediaNamespace(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION, "legacy")
        val versionedKey = retired.s3Prefix + "unindexed-orphan"
        val survivors = listOf(current.s3Prefix + "active-image", other.s3Prefix + "other-image")
        fixtureClient().use { client ->
            val versions = mutableSetOf<String>()
            // More than the maintenance adapter's 250-entry page, including many versions of one key.
            repeat(255) { index ->
                val response = client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(versionedKey).build(),
                    RequestBody.fromString("synthetic-version-$index"),
                )
                versions += checkNotNull(response.versionId()) { "Maintenance contract requires a versioned disposable bucket." }
            }
            assertEquals(255, versions.size)
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(versionedKey).build())
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(retired.s3Prefix + ".media-upload-orphan").build(), RequestBody.fromString("temporary"))
            survivors.forEach { client.putObject(PutObjectRequest.builder().bucket(bucket).key(it).build(), RequestBody.fromString("survivor")) }

            S3RetiredMediaStore(S3MediaBlobStoreConfig(bucket, region, endpoint, true, 1024), credentials).use { store ->
                val observed = store.scan(retired) {}
                assertTrue(observed.entries >= 257, "Full listing must include every version and the delete marker beyond its first page")
                val deleting = store.scan(retired) { store.deleteBatch(retired, it) }
                assertTrue(deleting.entries >= 257)
                assertEquals(0L, store.scan(retired) {}.entries)
                survivors.forEach {
                    assertEquals("survivor", client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(it).build()).asUtf8String())
                }
            }
        }
    }

    private fun fixtureClient(): S3Client = S3Client.builder().region(Region.of(region)).endpointOverride(endpoint)
        .forcePathStyle(true).credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build()
    private fun required(name: String): String = requireNotNull(environment[name]?.takeIf(String::isNotBlank)) { "$name is required for disposable maintenance tests." }
}
