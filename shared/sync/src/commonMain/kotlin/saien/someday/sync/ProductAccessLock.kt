package saien.someday.sync

/** Synchronous, thread-owned lock: nested SQL state callbacks may reenter on the same thread. */
internal expect class ProductAccessLock() {
    fun <T> withLock(block: () -> T): T
}
