package saien.someday.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncSchedulerTest {
    private class SchedulerHarness(scope: CoroutineScope) {
        var nowMillis: Long = 0L
        var syncReady: Boolean = true
        var syncRunning: Boolean = false
        var syncRuns: Int = 0

        val scheduler = AutoSyncScheduler(
            scope = scope,
            syncReady = { syncReady },
            syncRunning = { syncRunning },
            runSync = {
                syncRuns += 1
                true
            },
            nowMillis = { nowMillis },
            minIntervalMillis = 120_000L,
            pullMinIntervalMillis = 5_000L,
        )
    }

    @Test
    fun pullToRefreshSyncRunsImmediatelyWhenReadyAndIdle() = runBlocking {
        val harness = SchedulerHarness(this)

        assertTrue(harness.scheduler.requestPullToRefreshSync())
        assertEquals(1, harness.syncRuns)
    }

    @Test
    fun pullToRefreshSyncIsSkippedWhenSyncNotReadyOrAlreadyRunning() = runBlocking {
        val harness = SchedulerHarness(this)

        harness.syncReady = false
        assertFalse(harness.scheduler.requestPullToRefreshSync())

        harness.syncReady = true
        harness.syncRunning = true
        assertFalse(harness.scheduler.requestPullToRefreshSync())

        assertEquals(0, harness.syncRuns)
    }

    @Test
    fun pullToRefreshSyncIsDebouncedByPullInterval() = runBlocking {
        val harness = SchedulerHarness(this)

        harness.nowMillis = 10_000L
        assertTrue(harness.scheduler.requestPullToRefreshSync())

        harness.nowMillis = 13_000L
        assertFalse(harness.scheduler.requestPullToRefreshSync())

        harness.nowMillis = 15_000L
        assertTrue(harness.scheduler.requestPullToRefreshSync())

        assertEquals(2, harness.syncRuns)
    }

    @Test
    fun pullToRefreshSyncBypassesAutomaticThrottle() = runBlocking {
        val harness = SchedulerHarness(this)

        harness.nowMillis = 10_000L
        assertTrue(harness.scheduler.requestPullToRefreshSync())

        // Well inside the 2-minute automatic throttle, but past the pull debounce.
        harness.nowMillis = 40_000L
        assertTrue(harness.scheduler.requestPullToRefreshSync())

        assertEquals(2, harness.syncRuns)
    }
}
