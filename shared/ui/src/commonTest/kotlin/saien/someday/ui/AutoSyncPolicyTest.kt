package saien.someday.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncPolicyTest {
    @Test
    fun canStartOnlyWhenSyncIsReadyIdleAndOutsideMinimumInterval() {
        val policy = AutoSyncPolicy(minIntervalMillis = 120_000L)

        assertFalse(policy.canStart(nowMillis = 1_000L, syncReady = false, syncRunning = false))
        assertFalse(policy.canStart(nowMillis = 1_000L, syncReady = true, syncRunning = true))
        assertTrue(policy.canStart(nowMillis = 1_000L, syncReady = true, syncRunning = false))

        policy.markStarted(nowMillis = 1_000L)

        assertEquals(120_000L, policy.remainingThrottleMillis(nowMillis = 1_000L))
        assertEquals(60_000L, policy.remainingThrottleMillis(nowMillis = 61_000L))
        assertFalse(policy.canStart(nowMillis = 61_000L, syncReady = true, syncRunning = false))
        assertTrue(policy.canStart(nowMillis = 121_000L, syncReady = true, syncRunning = false))
    }

    @Test
    fun remainingIntervalMillisSupportsShorterPullDebounceWindows() {
        val policy = AutoSyncPolicy(minIntervalMillis = 120_000L)

        assertEquals(0L, policy.remainingIntervalMillis(nowMillis = 1_000L, intervalMillis = 5_000L))

        policy.markStarted(nowMillis = 1_000L)

        assertEquals(5_000L, policy.remainingIntervalMillis(nowMillis = 1_000L, intervalMillis = 5_000L))
        assertEquals(2_000L, policy.remainingIntervalMillis(nowMillis = 4_000L, intervalMillis = 5_000L))
        assertEquals(0L, policy.remainingIntervalMillis(nowMillis = 6_000L, intervalMillis = 5_000L))
        assertEquals(115_000L, policy.remainingThrottleMillis(nowMillis = 6_000L))
    }
}
