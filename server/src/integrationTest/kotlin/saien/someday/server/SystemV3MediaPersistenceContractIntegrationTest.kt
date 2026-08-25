package saien.someday.server

import java.security.MessageDigest
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.media.MediaBlobKey
import saien.someday.server.persistence.SystemV3MediaObjectRecord
import saien.someday.server.persistence.SystemV3MediaObjectValue
import saien.someday.server.persistence.SystemV3MediaPutResult
import saien.someday.server.persistence.SystemV3MediaReadResult
import saien.someday.server.persistence.SystemV3MediaRepository
import saien.someday.server.support.BlockingFirstPutMediaBlobStore
import saien.someday.server.support.ConcurrentStartGate
import saien.someday.server.support.ControllableMediaBlobStore
import saien.someday.server.support.InjectedBlobWriteFailure
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.TestServerIdentity

class SystemV3MediaPersistenceContractIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: PostgresContractFixture
    private lateinit var blobStore: ControllableMediaBlobStore
    private lateinit var repository: SystemV3MediaRepository
    private lateinit var identity: TestServerIdentity

    @BeforeTest
    fun setUp() {
        database = PostgresContractFixture(
            temporaryFolder.newFolder("media-contract").toPath(),
            mediaQuotaBytes = QUOTA_BYTES,
        )
        database.reset()
        blobStore = ControllableMediaBlobStore()
        repository = SystemV3MediaRepository(database.config, blobStore)
        identity = database.seedIdentity("media-persistence-${System.nanoTime()}")
    }

    @AfterTest
    fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test
    fun exactPutRepairsMissingAndCorruptBlobWhenMetadataAlreadyExists() {
        val missingBytes = ByteArray(64) { index -> index.toByte() }
        val missingKey = key(MEDIA_MISSING)
        assertIs<SystemV3MediaPutResult.Stored>(put(MEDIA_MISSING, missingBytes))
        blobStore.drop(missingKey)
        assertEquals(SystemV3MediaReadResult.Corrupt, repository.headObject(identity.userId, WORKSPACE_ID, MEDIA_MISSING))

        val missingRepair = assertIs<SystemV3MediaPutResult.Stored>(put(MEDIA_MISSING, missingBytes))
        assertTrue(missingRepair.idempotentReplay)
        assertReadEquals(MEDIA_MISSING, missingBytes)

        val corruptBytes = ByteArray(65) { index -> (index * 3).toByte() }
        val corruptKey = key(MEDIA_CORRUPT)
        assertIs<SystemV3MediaPutResult.Stored>(put(MEDIA_CORRUPT, corruptBytes))
        blobStore.replaceWithCorruption(corruptKey, ByteArray(corruptBytes.size) { 99 })
        assertEquals(SystemV3MediaReadResult.Corrupt, repository.headObject(identity.userId, WORKSPACE_ID, MEDIA_CORRUPT))

        val corruptRepair = assertIs<SystemV3MediaPutResult.Stored>(put(MEDIA_CORRUPT, corruptBytes))
        assertTrue(corruptRepair.idempotentReplay)
        assertReadEquals(MEDIA_CORRUPT, corruptBytes)
        assertEquals(2L, database.countRows("someday_media_v3_objects", identity.userId, WORKSPACE_ID))
    }

    @Test
    fun blobPublishedBeforeDatabaseFailureIsRemovedBeforeTheAuthoritativeRetry() {
        val expected = ByteArray(67) { 7 }
        val mediaKey = key(MEDIA_ORPHAN)

        assertFailsWith<SQLException> {
            repository.putObject(
                identity.userId,
                WORKSPACE_ID,
                UUID.randomUUID(),
                MEDIA_ORPHAN,
                sha256(expected),
                expected,
            )
        }
        assertContentEquals(expected, blobStore.bytes(mediaKey))
        assertEquals(0L, database.countRows("someday_media_v3_objects", identity.userId, WORKSPACE_ID))
        assertEquals(0L, database.mediaBytes(identity.userId))
        val cleanupCallsBeforeRetry = blobStore.removeUntrackedCalls

        val stored = assertIs<SystemV3MediaPutResult.Stored>(put(MEDIA_ORPHAN, expected))

        assertTrue(!stored.idempotentReplay)
        assertEquals(cleanupCallsBeforeRetry + 1, blobStore.removeUntrackedCalls)
        assertContentEquals(expected, blobStore.bytes(mediaKey))
        assertReadEquals(MEDIA_ORPHAN, expected)
        assertEquals(1L, database.countRows("someday_media_v3_objects", identity.userId, WORKSPACE_ID))
    }

    @Test
    fun blobWriteFailureDoesNotPublishMetadataOrConsumeAccountQuota() {
        val failedBytes = ByteArray(64) { 4 }
        blobStore.failNextPut()

        assertFailsWith<InjectedBlobWriteFailure> {
            put(MEDIA_WRITE_FAILURE, failedBytes)
        }
        assertEquals(0L, database.countRows("someday_media_v3_objects", identity.userId, WORKSPACE_ID))
        assertEquals(0L, database.mediaBytes(identity.userId))
        assertEquals(null, blobStore.bytes(key(MEDIA_WRITE_FAILURE)))

        val quotaSizedObject = ByteArray(QUOTA_BYTES.toInt()) { 5 }
        assertIs<SystemV3MediaPutResult.Stored>(put(MEDIA_AFTER_FAILURE, quotaSizedObject))
        assertEquals(QUOTA_BYTES, database.mediaBytes(identity.userId))
    }

    @Test
    fun sameKeyConcurrentExactAndMismatchedPutsHaveOneImmutableOutcome() = runBlocking {
        val exactBytes = ByteArray(70) { 1 }
        val exactStartGate = ConcurrentStartGate(2)
        val exactResults = List(2) {
            async(Dispatchers.IO) {
                exactStartGate.awaitRelease()
                put(MEDIA_CONCURRENT_EXACT, exactBytes)
            }
        }.awaitAll().map { assertIs<SystemV3MediaPutResult.Stored>(it) }
        assertEquals(listOf(false, true), exactResults.map { it.idempotentReplay }.sorted())
        assertReadEquals(MEDIA_CONCURRENT_EXACT, exactBytes)

        val candidates = listOf(ByteArray(71) { 2 }, ByteArray(71) { 3 })
        val mismatchStartGate = ConcurrentStartGate(candidates.size)
        val mismatchResults = candidates.map { bytes ->
            async(Dispatchers.IO) {
                mismatchStartGate.awaitRelease()
                put(MEDIA_CONCURRENT_MISMATCH, bytes)
            }
        }.awaitAll()
        assertEquals(1, mismatchResults.filterIsInstance<SystemV3MediaPutResult.Stored>().size)
        val rejection = assertIs<SystemV3MediaPutResult.Rejected>(
            mismatchResults.single { it is SystemV3MediaPutResult.Rejected },
        )
        assertEquals("immutable_media_mismatch", rejection.error)

        val found = assertIs<SystemV3MediaReadResult.Found<SystemV3MediaObjectValue<SystemV3MediaObjectRecord>>>(
            repository.readObject(identity.userId, WORKSPACE_ID, MEDIA_CONCURRENT_MISMATCH),
        )
        assertTrue(candidates.any { it.contentEquals(found.value.bytes) })
        assertEquals(2L, database.countRows("someday_media_v3_objects", identity.userId, WORKSPACE_ID))
    }

    @Test
    fun secondAccountUploadObservablyWaitsForTheQuotaAdvisoryLock() {
        val applicationName = "quota_lock_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val blockingBlobStore = BlockingFirstPutMediaBlobStore()
        val lockRepository = SystemV3MediaRepository(
            database.configWithApplicationName(applicationName),
            blockingBlobStore,
        )
        val executor = Executors.newFixedThreadPool(2)
        val firstBytes = ByteArray(72) { 8 }
        val secondBytes = ByteArray(73) { 9 }
        val first = executor.submit<SystemV3MediaPutResult> {
            lockRepository.putObject(
                identity.userId,
                WORKSPACE_ID,
                identity.deviceId,
                MEDIA_QUOTA_LOCK_FIRST,
                sha256(firstBytes),
                firstBytes,
            )
        }
        try {
            blockingBlobStore.awaitFirstPut()
            val second = executor.submit<SystemV3MediaPutResult> {
                lockRepository.putObject(
                    identity.userId,
                    OTHER_WORKSPACE_ID,
                    identity.deviceId,
                    MEDIA_QUOTA_LOCK_SECOND,
                    sha256(secondBytes),
                    secondBytes,
                )
            }

            database.awaitAdvisoryLockWait(applicationName, second::isDone)
            blockingBlobStore.releaseFirstPut()
            assertIs<SystemV3MediaPutResult.Stored>(first.get(30, TimeUnit.SECONDS))
            assertIs<SystemV3MediaPutResult.Stored>(second.get(30, TimeUnit.SECONDS))
        } finally {
            blockingBlobStore.releaseFirstPut()
            executor.shutdownNow()
        }
    }

    private fun put(mediaId: String, bytes: ByteArray) = repository.putObject(
        identity.userId,
        WORKSPACE_ID,
        identity.deviceId,
        mediaId,
        sha256(bytes),
        bytes,
    )

    private fun key(mediaId: String) = MediaBlobKey(identity.userId, WORKSPACE_ID, mediaId)

    private fun assertReadEquals(mediaId: String, expected: ByteArray) {
        val found = assertIs<SystemV3MediaReadResult.Found<SystemV3MediaObjectValue<SystemV3MediaObjectRecord>>>(
            repository.readObject(identity.userId, WORKSPACE_ID, mediaId),
        )
        assertContentEquals(expected, found.value.bytes)
        assertEquals(sha256(expected), found.value.record.ciphertextSha256)
    }

    private fun sha256(bytes: ByteArray): String =
        "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"

    private companion object {
        const val WORKSPACE_ID = "workspace-dddddddddddddddddddddddddddddddd"
        const val OTHER_WORKSPACE_ID = "workspace-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val QUOTA_BYTES = 1024L
        val MEDIA_MISSING = "01".repeat(32)
        val MEDIA_CORRUPT = "02".repeat(32)
        val MEDIA_ORPHAN = "03".repeat(32)
        val MEDIA_WRITE_FAILURE = "04".repeat(32)
        val MEDIA_AFTER_FAILURE = "05".repeat(32)
        val MEDIA_CONCURRENT_EXACT = "06".repeat(32)
        val MEDIA_CONCURRENT_MISMATCH = "07".repeat(32)
        val MEDIA_QUOTA_LOCK_FIRST = "08".repeat(32)
        val MEDIA_QUOTA_LOCK_SECOND = "09".repeat(32)
    }
}
