package saien.someday.ui

import saien.someday.domain.settings.ManualSyncProgress

/**
 * Scroll-to-top is only offered while the user is still near the top of the list;
 * deeper positions are treated as deliberate reading positions and left alone.
 */
internal const val SyncTopRevealMaxFirstVisibleItemIndex = 10

internal fun syncCompletionRevealsRemoteChanges(
    wasRunning: Boolean,
    progress: ManualSyncProgress,
): Boolean =
    wasRunning &&
        !progress.running &&
        (progress.pulledObjects > 0 || progress.conflicts > 0)

internal fun shouldScrollToRevealTop(
    firstVisibleItemIndex: Int,
    scrollInProgress: Boolean,
    maxFirstVisibleItemIndex: Int = SyncTopRevealMaxFirstVisibleItemIndex,
): Boolean =
    !scrollInProgress && firstVisibleItemIndex <= maxFirstVisibleItemIndex
