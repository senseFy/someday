package saien.someday.sync

import kotlinx.coroutines.sync.Mutex
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.ManualSyncRunner
import saien.someday.domain.settings.SyncMode

/** One process-local gate around the complete media-then-entity synchronization run. */
internal class SerializedManualSyncRunner(
    private val modeProvider: () -> SyncMode,
    private val delegate: ManualSyncRunner,
) : ManualSyncRunner {
    private val runMutex = Mutex()

    override fun run(): ManualSyncResult {
        if (!runMutex.tryLock()) {
            return ManualSyncResult.failure(
                mode = modeProvider(),
                reason = ManualSyncReason.AlreadyRunning,
            )
        }
        return try {
            delegate.run()
        } finally {
            runMutex.unlock()
        }
    }
}
