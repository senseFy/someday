package saien.someday.server.auth

import java.time.Duration
import java.time.Instant

class FixedWindowRateLimiter(
    private val maxAttempts: Int,
    private val window: Duration,
    private val maxBuckets: Int,
    private val now: () -> Instant = { Instant.now() },
) {
    init {
        require(maxAttempts > 0) { "Rate-limit attempt budget must be positive." }
        require(!window.isZero && !window.isNegative) { "Rate-limit window must be positive." }
        require(maxBuckets > 0) { "Rate-limit bucket budget must be positive." }
    }

    private data class Bucket(
        val windowStartedAt: Instant,
        val attempts: Int,
    )

    private val buckets = mutableMapOf<String, Bucket>()
    private var operations: Long = 0

    /**
     * The synchronized map is deliberate: authentication requests are low
     * volume, and a strict bucket ceiling is more important than approximate
     * lock-free accounting under adversarial key churn.
     */
    @Synchronized
    fun allow(key: String): Boolean {
        require(key.isNotBlank() && key.length <= MAX_RATE_LIMIT_KEY_LENGTH) {
            "Rate-limit keys must contain 1..$MAX_RATE_LIMIT_KEY_LENGTH characters."
        }
        val currentTime = now()
        operations++
        if (operations % CLEANUP_INTERVAL == 0L || buckets.size >= maxBuckets) {
            buckets.entries.removeIf { (_, bucket) -> bucket.isExpiredAt(currentTime) }
        }

        val existing = buckets[key]
        if (existing == null || existing.isExpiredAt(currentTime)) {
            if (existing == null && buckets.size >= maxBuckets) {
                // Never evict an active bucket to admit attacker-controlled key
                // churn. A full limiter fails closed until a window expires.
                return false
            }
            buckets[key] = Bucket(currentTime, 1)
            return true
        }

        if (existing.attempts >= maxAttempts) return false
        val attempts = existing.attempts + 1
        buckets[key] = existing.copy(attempts = attempts)
        return true
    }

    @Synchronized
    internal fun activeBucketCount(): Int = buckets.size

    private fun Bucket.isExpiredAt(currentTime: Instant): Boolean =
        Duration.between(windowStartedAt, currentTime) >= window

    private companion object {
        const val MAX_RATE_LIMIT_KEY_LENGTH = 512
        const val CLEANUP_INTERVAL = 64L
    }
}
