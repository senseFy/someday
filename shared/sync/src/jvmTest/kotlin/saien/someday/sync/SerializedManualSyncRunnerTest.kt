package saien.someday.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.SyncMode

class SerializedManualSyncRunnerTest {
    @Test
    fun concurrentRunIsRejectedBeforeItCanReadTheSameOutbox() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = SerializedManualSyncRunner(
            modeProvider = { SyncMode.SelfHosted },
            delegate = ManualSyncRunner {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                ManualSyncResult.success(SyncMode.SelfHosted, 1, 0, 0)
            },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<ManualSyncResult> { runner.run() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val concurrent = runner.run()

            assertFalse(concurrent.success)
            assertEquals(
                ManualSyncReason.AlreadyRunning,
                concurrent.reason,
                concurrent.diagnosticMessage,
            )
            release.countDown()
            val completed = first.get(5, TimeUnit.SECONDS)
            assertTrue(completed.success, completed.diagnosticMessage)
            assertEquals(ManualSyncReason.Completed, completed.reason)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun delegateFailureReleasesTheGateForTheNextRun() {
        var calls = 0
        val runner = SerializedManualSyncRunner(
            modeProvider = { SyncMode.SelfHosted },
            delegate = ManualSyncRunner {
                calls += 1
                if (calls == 1) error("injected delegate failure")
                ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
            },
        )

        assertFailsWith<IllegalStateException> { runner.run() }
        val retried = runner.run()

        assertTrue(retried.success, retried.diagnosticMessage)
        assertEquals(ManualSyncReason.Completed, retried.reason)
        assertEquals(2, calls)
    }
}
