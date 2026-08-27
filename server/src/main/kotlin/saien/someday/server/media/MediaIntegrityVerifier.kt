package saien.someday.server.media

import java.security.MessageDigest

data class MediaIntegrityRecord(
    val key: MediaBlobKey,
    val expected: MediaBlobMetadata,
)

enum class MediaIntegrityIssueReason {
    UNAVAILABLE,
    OUT_OF_BOUNDS,
    CONTENT_MISMATCH,
}

data class MediaIntegrityIssue(
    val record: MediaIntegrityRecord,
    val reason: MediaIntegrityIssueReason,
    val actual: MediaBlobMetadata? = null,
)

data class MediaIntegrityReport(
    val checkedObjects: Long,
    val invalidObjects: Long,
) {
    val isValid: Boolean
        get() = invalidObjects == 0L
}

fun interface MediaIntegrityRecordSource {
    fun forEachRecord(consumer: (MediaIntegrityRecord) -> Unit)
}

class MediaIntegrityVerifier(
    private val records: MediaIntegrityRecordSource,
    private val blobStore: MediaBlobStore,
    private val maxObjectBytes: Int,
) {
    init {
        require(maxObjectBytes > 0)
    }

    fun verify(onIssue: (MediaIntegrityIssue) -> Unit = {}): MediaIntegrityReport {
        var checkedObjects = 0L
        var invalidObjects = 0L
        records.forEachRecord { record ->
            checkedObjects += 1
            val value = try {
                blobStore.read(record.key, maxObjectBytes)
            } catch (_: IllegalArgumentException) {
                invalidObjects += 1
                onIssue(MediaIntegrityIssue(record, MediaIntegrityIssueReason.OUT_OF_BOUNDS))
                return@forEachRecord
            }
            if (value == null) {
                invalidObjects += 1
                onIssue(MediaIntegrityIssue(record, MediaIntegrityIssueReason.UNAVAILABLE))
                return@forEachRecord
            }

            val actual = MediaBlobMetadata(
                bytes = value.bytes.size.toLong(),
                sha256 = sha256(value.bytes),
            )
            val withinBound = actual.bytes in 1..maxObjectBytes.toLong()
            if (!withinBound || value.metadata != actual || record.expected != actual) {
                invalidObjects += 1
                onIssue(
                    MediaIntegrityIssue(
                        record = record,
                        reason = if (withinBound) {
                            MediaIntegrityIssueReason.CONTENT_MISMATCH
                        } else {
                            MediaIntegrityIssueReason.OUT_OF_BOUNDS
                        },
                        actual = actual,
                    ),
                )
            }
        }
        return MediaIntegrityReport(checkedObjects, invalidObjects)
    }
}

private fun sha256(bytes: ByteArray): String =
    "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"
