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
    private val productAccessMutex = Mutex()

    fun <T> exclusive(block: () -> T): T =
        runBlocking {
            workspaceLifecycleMutex.withLock { block() }
        }

    /**
     * Serializes product routing with authority activation and replacement.
     * A product operation that arrives during either commit window waits and
     * re-evaluates its route after the workspace transition.
     */
    fun <T> productAccess(block: () -> T): T =
        runBlocking {
            productAccessMutex.withLock { block() }
        }
}
