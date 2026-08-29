package saien.someday.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class WorkspaceLifecycleCoordinatorTest {
    @Test
    fun workspaceReplacementAndFirstActivationCannotOverlap() {
        val coordinator = WorkspaceLifecycleCoordinator()
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
                        "A second workspace lifecycle operation entered before the first transaction exited.",
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

    @Test
    fun productMutationAndWorkspaceReplacementCommitCannotOverlap() {
        val coordinator = WorkspaceLifecycleCoordinator()
        val productMutationEntered = CountDownLatch(1)
        val replacementAttempting = CountDownLatch(1)
        val releaseProductMutation = CountDownLatch(1)
        val productMutationExited = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val productMutation = executor.submit {
                coordinator.productAccess {
                    productMutationEntered.countDown()
                    assertTrue(releaseProductMutation.await(5, TimeUnit.SECONDS))
                    productMutationExited.set(true)
                }
            }
            assertTrue(productMutationEntered.await(5, TimeUnit.SECONDS))

            val replacementCommit = executor.submit {
                replacementAttempting.countDown()
                coordinator.productAccess {
                    assertTrue(
                        productMutationExited.get(),
                        "Workspace replacement overlapped an in-flight product mutation.",
                    )
                }
            }
            assertTrue(replacementAttempting.await(5, TimeUnit.SECONDS))
            releaseProductMutation.countDown()

            productMutation.get(5, TimeUnit.SECONDS)
            replacementCommit.get(5, TimeUnit.SECONDS)
        } finally {
            releaseProductMutation.countDown()
            executor.shutdownNow()
        }
    }
}
