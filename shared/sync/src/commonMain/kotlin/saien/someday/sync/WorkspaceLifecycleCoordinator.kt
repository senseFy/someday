package saien.someday.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes remote and local operations that must stay within one workspace
 * identity: synchronization, media transfer, and pairing replacement. Product
 * mutations use the narrower product lock so replacement can take one final,
 * atomic snapshot after any in-flight local write finishes.
 */
class WorkspaceLifecycleCoordinator {
    private val workspaceLifecycleMutex = Mutex()
    private val productAccessLock = ProductAccessLock()

    fun <T> exclusive(block: () -> T): T =
        runBlocking {
            workspaceLifecycleMutex.withLock { block() }
        }

    /**
     * Serializes product routing with authority activation and replacement.
     * A product operation that arrives during either commit window waits and
     * re-evaluates its route after the workspace transition. The block is
     * synchronous and may reenter on the same thread (for example an account
     * gate write from pointer CAS). It must never acquire exclusive from inside
     * this block: the lock order remains workspace lifecycle, then product.
     */
    fun <T> productAccess(block: () -> T): T = productAccessLock.withLock(block)
}
