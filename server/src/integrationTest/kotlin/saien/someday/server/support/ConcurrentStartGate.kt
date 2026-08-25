package saien.someday.server.support

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/** Ensures a concurrency contract cannot pass because every task ran serially. */
internal class ConcurrentStartGate(participants: Int) {
    init {
        require(participants >= 2)
    }

    private val barrier = CyclicBarrier(participants)

    fun awaitRelease() {
        barrier.await(30, TimeUnit.SECONDS)
    }
}
