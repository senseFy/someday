package saien.someday.server

import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.media.MediaBlobKey
import saien.someday.server.media.MediaBlobPutResult
import saien.someday.server.media.MediaIntegrityIssue
import saien.someday.server.media.MediaIntegrityIssueReason
import saien.someday.server.media.MediaIntegrityReport
import saien.someday.server.media.MediaIntegrityVerifier
import saien.someday.server.persistence.DatabaseConnectionPool
import saien.someday.server.persistence.MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES
import saien.someday.server.persistence.PostgresMediaIntegrityRecordSource
import saien.someday.server.persistence.SystemV3MediaPutResult
import saien.someday.server.persistence.SystemV3MediaRepository
import saien.someday.server.support.ControllableMediaBlobStore
import saien.someday.server.support.PostgresContractFixture
import saien.someday.server.support.TestServerIdentity

class MediaIntegrityVerifierIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: PostgresContractFixture
    private lateinit var identity: TestServerIdentity

    @BeforeTest
    fun setUp() {
        database = PostgresContractFixture(temporaryFolder.newFolder("integrity-media").toPath())
        database.reset()
        identity = database.seedIdentity("media-integrity-${System.nanoTime()}")
    }

    @AfterTest
    fun tearDown() {
        if (::database.isInitialized) database.reset()
    }

    @Test
    fun appRoleEnumeratesAuthoritativeRowsWhileIgnoringAValidObjectStoreSuperset() {
        val store = ControllableMediaBlobStore()
        DatabaseConnectionPool.create(database.config).use { connections ->
            val repository = SystemV3MediaRepository(database.config, store, connections)
            val validBytes = ByteArray(64) { it.toByte() }
            put(repository, VALID_MEDIA, validBytes)

            val orphanKey = MediaBlobKey(identity.userId, WORKSPACE, ORPHAN_MEDIA)
            val orphanBytes = ByteArray(65) { 7 }
            assertEquals(
                MediaBlobPutResult.Stored(idempotentReplay = false),
                store.putImmutable(orphanKey, orphanBytes, sha256(orphanBytes)),
            )

            val validReport = verifier(connections, store).verify()
            assertEquals(MediaIntegrityReport(checkedObjects = 1, invalidObjects = 0), validReport)
            assertTrue(validReport.isValid)

            val missingBytes = ByteArray(66) { 8 }
            put(repository, MISSING_MEDIA, missingBytes)
            store.drop(MediaBlobKey(identity.userId, WORKSPACE, MISSING_MEDIA))

            val divergentBytes = ByteArray(67) { 9 }
            put(repository, DIVERGENT_MEDIA, divergentBytes)
            store.replaceWithCorruption(
                MediaBlobKey(identity.userId, WORKSPACE, DIVERGENT_MEDIA),
                ByteArray(divergentBytes.size) { 10 },
            )
            val issues = mutableListOf<MediaIntegrityIssue>()

            val invalidReport = verifier(connections, store).verify(issues::add)

            assertEquals(MediaIntegrityReport(checkedObjects = 3, invalidObjects = 2), invalidReport)
            assertEquals(
                setOf(MediaIntegrityIssueReason.UNAVAILABLE, MediaIntegrityIssueReason.CONTENT_MISMATCH),
                issues.map(MediaIntegrityIssue::reason).toSet(),
            )
            assertEquals(setOf(MISSING_MEDIA, DIVERGENT_MEDIA), issues.map { it.record.key.mediaId }.toSet())
        }
    }

    private fun verifier(
        connections: DatabaseConnectionPool,
        store: ControllableMediaBlobStore,
    ): MediaIntegrityVerifier = MediaIntegrityVerifier(
        records = PostgresMediaIntegrityRecordSource(connections),
        blobStore = store,
        maxObjectBytes = MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES,
    )

    private fun put(repository: SystemV3MediaRepository, mediaId: String, bytes: ByteArray) {
        assertIs<SystemV3MediaPutResult.Stored>(
            repository.putObject(
                userId = identity.userId,
                workspaceId = WORKSPACE,
                deviceId = identity.deviceId,
                mediaId = mediaId,
                ciphertextSha256 = sha256(bytes),
                bytes = bytes,
            ),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"

    private companion object {
        const val WORKSPACE = "workspace-abcdefabcdefabcdefabcdefabcdefab"
        val VALID_MEDIA = "11".repeat(32)
        val ORPHAN_MEDIA = "22".repeat(32)
        val MISSING_MEDIA = "33".repeat(32)
        val DIVERGENT_MEDIA = "44".repeat(32)
    }
}
