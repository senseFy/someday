package saien.someday.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes operations that can bind the local database to a workspace key
 * or publish its first remote epoch. Pre-authority import/export routing also
 * takes this lock so a local fallback cannot race genesis activation and
 * become invisible immediately after it is written. Ordinary sync on an
 * existing authority does not take this lock.
 */
class WorkspaceAuthorityMutationCoordinator {
    private val mutex = Mutex()

    fun <T> exclusive(block: () -> T): T =
        runBlocking {
            mutex.withLock { block() }
        }
}
