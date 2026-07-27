package saien.someday.server.auth

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixedWindowRateLimiterTest {
    @Test
    fun rejectsAttemptsBeyondTheWindowBudgetAndResetsAfterExpiry() {
        var currentTime = Instant.parse("2026-07-27T00:00:00Z")
        val limiter = FixedWindowRateLimiter(
            maxAttempts = 2,
            window = Duration.ofMinutes(1),
            maxBuckets = 10,
            now = { currentTime },
        )

        assertTrue(limiter.allow("account:a"))
        assertTrue(limiter.allow("account:a"))
        assertFalse(limiter.allow("account:a"))

        currentTime = currentTime.plusSeconds(60)
        assertTrue(limiter.allow("account:a"))
    }

    @Test
    fun attackerControlledKeyChurnCannotGrowTheBucketMapPastItsLimit() {
        var currentTime = Instant.parse("2026-07-27T00:00:00Z")
        val limiter = FixedWindowRateLimiter(
            maxAttempts = 2,
            window = Duration.ofMinutes(1),
            maxBuckets = 2,
            now = { currentTime },
        )

        assertTrue(limiter.allow("client:a"))
        assertTrue(limiter.allow("client:b"))
        assertFalse(limiter.allow("client:c"))
        assertEquals(2, limiter.activeBucketCount())

        currentTime = currentTime.plusSeconds(60)
        assertTrue(limiter.allow("client:c"))
        assertEquals(1, limiter.activeBucketCount())
    }
}
