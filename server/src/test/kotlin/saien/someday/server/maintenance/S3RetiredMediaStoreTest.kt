package saien.someday.server.maintenance

import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.ObjectVersion
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Object
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION

class S3RetiredMediaStoreTest {
    @Test fun followsBothVersionMarkersAndCurrentContinuationAndDeletesExplicitMarkerVersions() {
        val target = target()
        val calls = mutableListOf<Any>()
        val key = target.s3Prefix + "orphan"
        val client = client { name, request ->
            calls += request
            when (name) {
                "listObjectVersions" -> {
                    request as ListObjectVersionsRequest
                    assertEquals(target.s3Prefix, request.prefix())
                    assertEquals(1, request.maxKeys())
                    if (request.keyMarker() == null) ListObjectVersionsResponse.builder().isTruncated(true)
                        .versions(ObjectVersion.builder().key(key).versionId("old-version").build())
                        .nextKeyMarker(key).nextVersionIdMarker("old-version").build()
                    else {
                        assertEquals("old-version", request.versionIdMarker())
                        ListObjectVersionsResponse.builder().isTruncated(false)
                            .deleteMarkers(DeleteMarkerEntry.builder().key(key).versionId("delete-marker").build()).build()
                    }
                }
                "listObjectsV2" -> {
                    request as ListObjectsV2Request
                    assertEquals(target.s3Prefix, request.prefix())
                    if (request.continuationToken() == null) ListObjectsV2Response.builder().isTruncated(true)
                        .contents(S3Object.builder().key(key + "-unversioned").build()).nextContinuationToken("next").build()
                    else ListObjectsV2Response.builder().isTruncated(false).build()
                }
                "deleteObject" -> DeleteObjectResponse.builder().build()
                else -> error("Unexpected method: $name")
            }
        }
        S3RetiredMediaStore(client, "synthetic-bucket", batchSize = 1).use { store ->
            assertEquals(3L, store.scan(target) { store.deleteBatch(target, it) }.entries)
        }
        val deletions = calls.filterIsInstance<DeleteObjectRequest>()
        assertEquals(listOf("old-version", "delete-marker", null), deletions.map { it.versionId() })
        deletions.forEach { assertNull(it.bypassGovernanceRetention()) }
    }

    @Test fun partialOrCyclicListingsCannotReturnCompleteEvidence() {
        val target = target()
        val client = client { _, _ ->
            ListObjectVersionsResponse.builder().isTruncated(true).nextKeyMarker(target.s3Prefix + "same").nextVersionIdMarker("same").build()
        }
        S3RetiredMediaStore(client, "synthetic-bucket").use { store ->
            assertEquals(MaintenanceMediaFailureReason.INCOMPLETE_SCAN, assertFailsWith<MaintenanceMediaFailure> { store.scan(target) {} }.reason)
        }
    }

    @Test fun refusesOutOfPrefixEntriesAndReportsRetentionWithoutBypass() {
        val target = target()
        val client = client { _, _ -> throw S3Exception.builder().statusCode(403)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("ObjectLockedByBucketPolicy").build()).build() }
        S3RetiredMediaStore(client, "synthetic-bucket").use { store ->
            assertEquals(MaintenanceMediaFailureReason.UNSAFE_PATH, assertFailsWith<MaintenanceMediaFailure> {
                store.deleteBatch(target, listOf(RetiredMediaEntry("media/v1/another-account/object", "version")))
            }.reason)
            assertEquals(MaintenanceMediaFailureReason.RETENTION_BLOCKED, assertFailsWith<MaintenanceMediaFailure> {
                store.deleteBatch(target, listOf(RetiredMediaEntry(target.s3Prefix + "retained", "version")))
            }.reason)
        }
    }

    private fun target() = RetiredMediaNamespace(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION, "legacy")
    private fun client(call: (String, Any) -> Any): S3Client = Proxy.newProxyInstance(
        S3Client::class.java.classLoader, arrayOf(S3Client::class.java),
    ) { _, method, args -> if (method.name == "close") null else call(method.name, checkNotNull(args?.singleOrNull())) } as S3Client
}
