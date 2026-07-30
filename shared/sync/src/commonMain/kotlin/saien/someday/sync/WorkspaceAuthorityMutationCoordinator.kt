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
    private val authorityMutex = Mutex()
    private val productAccessMutex = Mutex()

    fun <T> exclusive(block: () -> T): T =
        runBlocking {
            authorityMutex.withLock { block() }
        }

    /**
     * Serializes product routing with the short pointer-commit window.
     *
     * Checkpoint construction and upload deliberately do not take this lock.
     * The publisher takes it only for its final snapshot check, remote CAS, and
     * local authority activation. A product operation that arrives in that
     * window therefore waits and re-evaluates its route after activation.
     */
    fun <T> productAccess(block: () -> T): T =
        runBlocking {
            productAccessMutex.withLock { block() }
        }
}
