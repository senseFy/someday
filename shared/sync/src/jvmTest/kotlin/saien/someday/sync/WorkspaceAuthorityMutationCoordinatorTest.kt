package saien.someday.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class WorkspaceAuthorityMutationCoordinatorTest {
    @Test
    fun workspaceAdoptionAndFirstActivationCannotOverlap() {
        val coordinator = WorkspaceAuthorityMutationCoordinator()
        val firstEntered = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstExited = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                coordinator.exclusive {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                    firstExited.set(true)
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

            val second = executor.submit {
                secondAttempting.countDown()
                coordinator.exclusive {
                    assertTrue(
                        firstExited.get(),
                        "A second authority mutation entered before the first transaction exited.",
                    )
                }
            }
            assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
            releaseFirst.countDown()

            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }
}
