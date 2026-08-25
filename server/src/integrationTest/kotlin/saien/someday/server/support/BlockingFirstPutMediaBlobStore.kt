package saien.someday.server.support

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import saien.someday.server.media.MediaBlobKey
import saien.someday.server.media.MediaBlobPutResult
import saien.someday.server.media.MediaBlobStore

/** Holds the first blob publication while its caller still owns the DB transaction. */
internal class BlockingFirstPutMediaBlobStore(
    private val delegate: MediaBlobStore = ControllableMediaBlobStore(),
) : MediaBlobStore by delegate {
    private val first = AtomicBoolean(true)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    override fun putImmutable(
        key: MediaBlobKey,
        bytes: ByteArray,
        expectedSha256: String,
    ): MediaBlobPutResult {
        if (first.compareAndSet(true, false)) {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) {
                "Timed out waiting to release the first blob publication."
            }
        }
        return delegate.putImmutable(key, bytes, expectedSha256)
    }

    fun awaitFirstPut() {
        check(entered.await(30, TimeUnit.SECONDS)) {
            "The first transaction never reached the blocking blob boundary."
        }
    }

    fun releaseFirstPut() {
        release.countDown()
    }
}
