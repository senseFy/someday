@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

internal enum class AutoSyncTrigger {
    Launch,
    Foreground,
    LocalChange,
}

internal class AutoSyncScheduler(
    private val scope: CoroutineScope,
    private val syncReady: () -> Boolean,
    private val syncRunning: () -> Boolean,
    private val runSync: suspend () -> Boolean,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    minIntervalMillis: Long = DefaultMinIntervalMillis,
    private val launchDelayMillis: Long = DefaultLaunchDelayMillis,
    private val foregroundDelayMillis: Long = DefaultForegroundDelayMillis,
    private val localChangeDebounceMillis: Long = DefaultLocalChangeDebounceMillis,
    private val pullMinIntervalMillis: Long = DefaultPullMinIntervalMillis,
) {
    private val policy = AutoSyncPolicy(minIntervalMillis)
    private var launchJob: Job? = null
    private var foregroundJob: Job? = null
    private var localChangeJob: Job? = null

    fun request(trigger: AutoSyncTrigger) {
        when (trigger) {
            AutoSyncTrigger.Launch -> {
                launchJob?.cancel()
                launchJob = schedule(delayMillis = launchDelayMillis, waitForThrottle = false)
            }
            AutoSyncTrigger.Foreground -> {
                foregroundJob?.cancel()
                foregroundJob = schedule(delayMillis = foregroundDelayMillis, waitForThrottle = false)
            }
            AutoSyncTrigger.LocalChange -> {
                localChangeJob?.cancel()
                localChangeJob = schedule(delayMillis = localChangeDebounceMillis, waitForThrottle = true)
            }
        }
    }

    /**
     * Runs a sync for an explicit pull-to-refresh gesture and suspends until it completes.
     *
     * Pulls bypass the automatic-sync throttle but are debounced by [pullMinIntervalMillis],
     * and a pull counts as a sync start for the automatic throttle window.
     * Returns false when the sync was skipped (not ready, already running, or debounced).
     */
    suspend fun requestPullToRefreshSync(): Boolean {
        if (!syncReady() || syncRunning()) {
            return false
        }
        if (policy.remainingIntervalMillis(nowMillis(), pullMinIntervalMillis) > 0L) {
            return false
        }
        policy.markStarted(nowMillis())
        return runSync()
    }

    fun cancel() {
        launchJob?.cancel()
        foregroundJob?.cancel()
        localChangeJob?.cancel()
    }

    private fun schedule(
        delayMillis: Long,
        waitForThrottle: Boolean,
    ): Job =
        scope.launch {
            delay(delayMillis)
            startIfAllowed(waitForThrottle)
        }

    private suspend fun startIfAllowed(waitForThrottle: Boolean): Boolean {
        if (!syncReady() || syncRunning()) {
            return false
        }

        // Re-check after waiting: another trigger (for example pull-to-refresh) may have
        // started a sync meanwhile, which moves the throttle window forward.
        var remainingMillis = policy.remainingThrottleMillis(nowMillis())
        while (remainingMillis > 0L) {
            if (!waitForThrottle) {
                return false
            }
            delay(remainingMillis)
            if (!syncReady() || syncRunning()) {
                return false
            }
            remainingMillis = policy.remainingThrottleMillis(nowMillis())
        }

        policy.markStarted(nowMillis())
        return runSync()
    }

    companion object {
        const val DefaultMinIntervalMillis: Long = 2 * 60 * 1000L
        const val DefaultLaunchDelayMillis: Long = 2 * 1000L
        const val DefaultForegroundDelayMillis: Long = 1500L
        const val DefaultLocalChangeDebounceMillis: Long = 20 * 1000L
        const val DefaultPullMinIntervalMillis: Long = 5 * 1000L
    }
}

internal class AutoSyncPolicy(
    private val minIntervalMillis: Long,
) {
    private var lastStartedAtMillis: Long? = null

    fun canStart(
        nowMillis: Long,
        syncReady: Boolean,
        syncRunning: Boolean,
    ): Boolean =
        syncReady && !syncRunning && remainingThrottleMillis(nowMillis) == 0L

    fun remainingThrottleMillis(nowMillis: Long): Long =
        remainingIntervalMillis(nowMillis, minIntervalMillis)

    fun remainingIntervalMillis(
        nowMillis: Long,
        intervalMillis: Long,
    ): Long {
        val lastStartedAt = lastStartedAtMillis ?: return 0L
        return (intervalMillis - (nowMillis - lastStartedAt)).coerceAtLeast(0L)
    }

    fun markStarted(nowMillis: Long) {
        lastStartedAtMillis = nowMillis
    }
}
