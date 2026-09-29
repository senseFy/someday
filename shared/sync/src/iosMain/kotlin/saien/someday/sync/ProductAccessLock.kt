@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package saien.someday.sync

import platform.Foundation.NSRecursiveLock

internal actual class ProductAccessLock actual constructor() {
    private val delegate = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        delegate.lock()
        return try {
            block()
        } finally {
            delegate.unlock()
        }
    }
}
