package saien.someday.server

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.someday.server.media.MediaBlobKey
import saien.someday.server.media.MediaBlobMetadata
import saien.someday.server.media.MediaBlobPutResult
import saien.someday.server.media.MediaBlobStore
import saien.someday.server.media.MediaBlobValue
import saien.someday.server.media.MediaIntegrityIssue
import saien.someday.server.media.MediaIntegrityIssueReason
import saien.someday.server.media.MediaIntegrityRecord
import saien.someday.server.media.MediaIntegrityRecordSource
import saien.someday.server.media.MediaIntegrityReport
import saien.someday.server.media.MediaIntegrityVerifier
import saien.someday.server.persistence.MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES

class MediaIntegrityVerifierTest {
    @Test
    fun hashesActualBytesFromBoundedReadsWithoutUsingHeadOrStorageEnumeration() {
        val validBytes = ByteArray(64) { it.toByte() }
        val divergentBytes = ByteArray(64) { (it + 1).toByte() }
        val valid = record(mediaId = "1".repeat(64), bytes = validBytes)
        val unavailable = record(mediaId = "2".repeat(64), bytes = ByteArray(65) { 2 })
        val divergent = record(mediaId = "3".repeat(64), bytes = validBytes)
        val store = TrackingMediaBlobStore(
            mapOf(
                valid.key to MediaBlobValue(valid.expected, validBytes),
                // Deliberately forge matching metadata; the verifier must hash bytes itself.
                divergent.key to MediaBlobValue(divergent.expected, divergentBytes),
            ),
        )
        val issues = mutableListOf<MediaIntegrityIssue>()

        val report = MediaIntegrityVerifier(
            records = records(valid, unavailable, divergent),
            blobStore = store,
            maxObjectBytes = MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES,
        ).verify(issues::add)

        assertEquals(MediaIntegrityReport(checkedObjects = 3, invalidObjects = 2), report)
        assertFalse(report.isValid)
        assertEquals(
            listOf(MediaIntegrityIssueReason.UNAVAILABLE, MediaIntegrityIssueReason.CONTENT_MISMATCH),
            issues.map(MediaIntegrityIssue::reason),
        )
        assertEquals(sha256(divergentBytes), issues.last().actual?.sha256)
        assertEquals(listOf(valid.key, unavailable.key, divergent.key), store.readKeys)
        assertTrue(store.readBounds.all { it == MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES })
    }

    @Test
    fun reportsAStoredObjectThatRejectsTheProtocolReadBoundAndContinues() {
        val boundedFailure = record(mediaId = "4".repeat(64), bytes = ByteArray(45) { 4 })
        val validBytes = ByteArray(46) { 5 }
        val valid = record(mediaId = "5".repeat(64), bytes = validBytes)
        val store = TrackingMediaBlobStore(
            values = mapOf(valid.key to MediaBlobValue(valid.expected, validBytes)),
            outOfBounds = setOf(boundedFailure.key),
        )
        val issues = mutableListOf<MediaIntegrityIssue>()

        val report = MediaIntegrityVerifier(
            records = records(boundedFailure, valid),
            blobStore = store,
            maxObjectBytes = MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES,
        ).verify(issues::add)

        assertEquals(MediaIntegrityReport(checkedObjects = 2, invalidObjects = 1), report)
        assertEquals(listOf(MediaIntegrityIssueReason.OUT_OF_BOUNDS), issues.map(MediaIntegrityIssue::reason))
        assertEquals(listOf(boundedFailure.key, valid.key), store.readKeys)
    }

    @Test
    fun cliExitCodesDistinguishValidInvalidAndIncompleteVerification() {
        val validOutput = ByteArrayOutputStream()
        val validError = ByteArrayOutputStream()
        val validExit = runMediaIntegrityVerifierCli(
            environment = emptyMap(),
            standardOutput = PrintStream(validOutput),
            standardError = PrintStream(validError),
            verify = { _, _ -> MediaIntegrityReport(checkedObjects = 7, invalidObjects = 0) },
        )
        assertEquals(0, validExit)
        assertTrue(validOutput.toString().contains("7 referenced object(s) are valid"))
        assertTrue(validError.toString().isEmpty())

        val invalidError = ByteArrayOutputStream()
        val invalidRecord = record(mediaId = "6".repeat(64), bytes = ByteArray(47) { 6 })
        val invalidExit = runMediaIntegrityVerifierCli(
            environment = emptyMap(),
            standardOutput = PrintStream(ByteArrayOutputStream()),
            standardError = PrintStream(invalidError),
            verify = { _, onIssue ->
                onIssue(MediaIntegrityIssue(invalidRecord, MediaIntegrityIssueReason.UNAVAILABLE))
                MediaIntegrityReport(checkedObjects = 7, invalidObjects = 1)
            },
        )
        assertEquals(MEDIA_INTEGRITY_EXIT_INVALID_RECOVERY_SET, invalidExit)
        assertTrue(invalidError.toString().contains("reason=unavailable"))
        assertTrue(invalidError.toString().contains("1 of 7 referenced object(s) are invalid"))

        val incompleteError = ByteArrayOutputStream()
        val incompleteExit = runMediaIntegrityVerifierCli(
            environment = emptyMap(),
            standardOutput = PrintStream(ByteArrayOutputStream()),
            standardError = PrintStream(incompleteError),
            verify = { _, _ -> error("object store unavailable") },
        )
        assertEquals(MEDIA_INTEGRITY_EXIT_OPERATIONAL_FAILURE, incompleteExit)
        assertTrue(incompleteError.toString().contains("did not complete: object store unavailable"))
    }

    private fun record(mediaId: String, bytes: ByteArray): MediaIntegrityRecord = MediaIntegrityRecord(
        key = MediaBlobKey(USER_ID, WORKSPACE, mediaId),
        expected = MediaBlobMetadata(bytes.size.toLong(), sha256(bytes)),
    )

    private fun records(vararg records: MediaIntegrityRecord): MediaIntegrityRecordSource =
        MediaIntegrityRecordSource { consumer -> records.forEach(consumer) }

    private companion object {
        val USER_ID: UUID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000")
        const val WORKSPACE = "workspace-0123456789abcdef0123456789abcdef"
    }
}

private class TrackingMediaBlobStore(
    private val values: Map<MediaBlobKey, MediaBlobValue>,
    private val outOfBounds: Set<MediaBlobKey> = emptySet(),
) : MediaBlobStore {
    val readKeys = mutableListOf<MediaBlobKey>()
    val readBounds = mutableListOf<Int>()

    override fun putImmutable(
        key: MediaBlobKey,
        bytes: ByteArray,
        expectedSha256: String,
    ): MediaBlobPutResult = error("Integrity verification must not write objects.")

    override fun head(key: MediaBlobKey): MediaBlobMetadata =
        error("Integrity verification must hash a bounded read instead of trusting HEAD.")

    override fun read(key: MediaBlobKey, maxBytes: Int): MediaBlobValue? {
        readKeys += key
        readBounds += maxBytes
        require(key !in outOfBounds) { "Stored media blob exceeds its protocol bound." }
        return values[key]
    }
}

private fun sha256(bytes: ByteArray): String =
    "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"
