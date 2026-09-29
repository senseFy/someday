package saien.someday.data.account

/**
 * Shares the synchronous local writer boundary without depending on sync.
 * Callers that already opened a database transaction must hold this same
 * boundary before opening it. Blocks stay on one thread and contain no HTTP.
 */
interface AccountStateMutationBoundary {
    fun <T> mutate(block: () -> T): T

    object Direct : AccountStateMutationBoundary {
        override fun <T> mutate(block: () -> T): T = block()
    }
}
