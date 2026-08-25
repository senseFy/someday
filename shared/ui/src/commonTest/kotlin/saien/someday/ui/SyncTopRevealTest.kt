package saien.someday.ui

import saien.someday.domain.settings.ManualSyncProgress
import saien.someday.domain.settings.SyncMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncTopRevealTest {
    private fun completed(
        pulledObjects: Int = 0,
        pushedObjects: Int = 0,
        conflicts: Int = 0,
    ): ManualSyncProgress =
        ManualSyncProgress(
            running = false,
            mode = SyncMode.SelfHosted,
            message = "Sync finished.",
            pushedObjects = pushedObjects,
            pulledObjects = pulledObjects,
            conflicts = conflicts,
        )

    @Test
    fun completionRevealsRemoteChangesOnlyForPulledObjectsOrConflicts() {
        assertTrue(syncCompletionRevealsRemoteChanges(wasRunning = true, progress = completed(pulledObjects = 2)))
        assertTrue(syncCompletionRevealsRemoteChanges(wasRunning = true, progress = completed(conflicts = 1)))

        assertFalse(syncCompletionRevealsRemoteChanges(wasRunning = true, progress = completed()))
        assertFalse(syncCompletionRevealsRemoteChanges(wasRunning = true, progress = completed(pushedObjects = 3)))
    }

    @Test
    fun completionRequiresARunningToIdleTransition() {
        assertFalse(syncCompletionRevealsRemoteChanges(wasRunning = false, progress = completed(pulledObjects = 2)))
        assertFalse(
            syncCompletionRevealsRemoteChanges(
                wasRunning = true,
                progress = ManualSyncProgress.inProgress(SyncMode.SelfHosted, "Syncing"),
            ),
        )
    }

    @Test
    fun scrollToTopOnlyWhenNearTopAndNotScrolling() {
        assertTrue(shouldScrollToRevealTop(firstVisibleItemIndex = 0, scrollInProgress = false))
        assertTrue(shouldScrollToRevealTop(firstVisibleItemIndex = 10, scrollInProgress = false))

        assertFalse(shouldScrollToRevealTop(firstVisibleItemIndex = 11, scrollInProgress = false))
        assertFalse(shouldScrollToRevealTop(firstVisibleItemIndex = 120, scrollInProgress = false))
        assertFalse(shouldScrollToRevealTop(firstVisibleItemIndex = 0, scrollInProgress = true))
    }
}
