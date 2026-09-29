package saien.someday.sync

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual class ProductAccessLock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T = delegate.withLock(block)
}
