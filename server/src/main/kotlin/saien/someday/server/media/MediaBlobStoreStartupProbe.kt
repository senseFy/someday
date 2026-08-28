package saien.someday.server.media

import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Exercises the configured durable store before the server starts listening.
 * One versioned system marker is retained and reused; startups never accumulate
 * probe objects and do not need list or delete capabilities.
 */
internal fun verifyMediaBlobStoreStartup(
    store: MediaBlobStore,
    timeout: Duration = STARTUP_PROBE_TIMEOUT,
) {
    require(timeout.isPositive()) { "Media startup probe timeout must be positive." }
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "someday-media-startup-probe").apply { isDaemon = true }
    }
    val future = executor.submit { verifyMediaBlobStoreContract(store) }
    try {
        future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (failure: TimeoutException) {
        future.cancel(true)
        throw IllegalStateException("Media storage startup probe exceeded ${timeout.toSeconds()} seconds.", failure)
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    } finally {
        executor.shutdownNow()
    }
}

private fun verifyMediaBlobStoreContract(store: MediaBlobStore) {
    val access = when (store) {
        is FileSystemMediaBlobStore -> StartupProbeAccess(
            put = store::putStartupProbe,
            head = store::headStartupProbe,
            read = store::readStartupProbe,
            missingByMetadata = store::isStartupProbeMissingByMetadata,
            missingByRead = store::isStartupProbeMissingByRead,
        )
        is S3MediaBlobStore -> StartupProbeAccess(
            put = store::putStartupProbe,
            head = store::headStartupProbe,
            read = store::readStartupProbe,
            missingByMetadata = store::isStartupProbeMissingByMetadata,
            missingByRead = store::isStartupProbeMissingByRead,
        )
        else -> error("Configured media store does not support the startup probe: ${store::class.qualifiedName}")
    }

    check(access.missingByMetadata()) {
        "Media storage metadata lookup did not prove the reserved missing key is absent."
    }
    check(access.missingByRead()) {
        "Media storage bounded read did not prove the reserved missing key is absent."
    }

    val expectedDigest = startupProbeSha256(STARTUP_PROBE_BYTES)
    check(access.put(STARTUP_PROBE_BYTES, expectedDigest) is MediaBlobPutResult.Stored) {
        "Media startup probe key contains a different value."
    }
    check(access.put(STARTUP_PROBE_BYTES, expectedDigest) == MediaBlobPutResult.Stored(true)) {
        "Media storage did not accept an exact immutable replay."
    }
    check(
        access.put(STARTUP_PROBE_MISMATCH_BYTES, startupProbeSha256(STARTUP_PROBE_MISMATCH_BYTES)) ==
            MediaBlobPutResult.ImmutableMismatch,
    ) {
        "Media storage replaced an immutable startup probe value."
    }

    val expectedMetadata = MediaBlobMetadata(STARTUP_PROBE_BYTES.size.toLong(), expectedDigest)
    check(access.head() == expectedMetadata) { "Media startup probe metadata did not round-trip." }
    val stored = checkNotNull(access.read(STARTUP_PROBE_BYTES.size)) {
        "Media startup probe bytes were not readable."
    }
    check(stored.metadata == expectedMetadata && stored.bytes.contentEquals(STARTUP_PROBE_BYTES)) {
        "Media startup probe bytes did not round-trip exactly."
    }
}

private data class StartupProbeAccess(
    val put: (ByteArray, String) -> MediaBlobPutResult,
    val head: () -> MediaBlobMetadata?,
    val read: (Int) -> MediaBlobValue?,
    val missingByMetadata: () -> Boolean,
    val missingByRead: () -> Boolean,
)

private fun startupProbeSha256(bytes: ByteArray): String =
    "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"

private val STARTUP_PROBE_BYTES = "someday-media-startup-probe-v1".encodeToByteArray()
private val STARTUP_PROBE_MISMATCH_BYTES = "someday-media-startup-probe-v2".encodeToByteArray()
private val STARTUP_PROBE_TIMEOUT: Duration = Duration.ofSeconds(45)
