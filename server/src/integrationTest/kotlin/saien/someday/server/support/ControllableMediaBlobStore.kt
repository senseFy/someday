package saien.someday.server.support

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import saien.someday.server.media.MediaBlobKey
import saien.someday.server.media.MediaBlobMetadata
import saien.someday.server.media.MediaBlobPutResult
import saien.someday.server.media.MediaBlobStore
import saien.someday.server.media.MediaBlobValue

/** Narrow fault seam for the PostgreSQL/blob publication contract. */
internal class ControllableMediaBlobStore : MediaBlobStore {
    private val values = ConcurrentHashMap<MediaBlobKey, ByteArray>()
    private var failNextWrite = false

    @Synchronized
    override fun putImmutable(
        key: MediaBlobKey,
        bytes: ByteArray,
        expectedSha256: String,
    ): MediaBlobPutResult {
        check(expectedSha256 == sha256(bytes))
        if (failNextWrite) {
            failNextWrite = false
            throw InjectedBlobWriteFailure()
        }
        val existing = values[key]
        if (existing != null) {
            return if (existing.contentEquals(bytes)) {
                MediaBlobPutResult.Stored(idempotentReplay = true)
            } else {
                MediaBlobPutResult.ImmutableMismatch
            }
        }
        values[key] = bytes.copyOf()
        return MediaBlobPutResult.Stored(idempotentReplay = false)
    }

    override fun head(key: MediaBlobKey): MediaBlobMetadata? = values[key]?.let { bytes ->
        MediaBlobMetadata(bytes.size.toLong(), sha256(bytes))
    }

    override fun read(key: MediaBlobKey, maxBytes: Int): MediaBlobValue? = values[key]?.let { bytes ->
        require(bytes.size <= maxBytes)
        val copy = bytes.copyOf()
        MediaBlobValue(MediaBlobMetadata(copy.size.toLong(), sha256(copy)), copy)
    }

    fun drop(key: MediaBlobKey) {
        values.remove(key)
    }

    fun replaceWithCorruption(key: MediaBlobKey, bytes: ByteArray) {
        values[key] = bytes.copyOf()
    }

    @Synchronized
    fun failNextPut() {
        failNextWrite = true
    }

    fun bytes(key: MediaBlobKey): ByteArray? = values[key]?.copyOf()

    private fun sha256(bytes: ByteArray): String =
        "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"
}

internal class InjectedBlobWriteFailure : RuntimeException("Injected blob write failure")
