package saien.someday.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.someday.domain.workspace.WorkspaceProductChangedException
import saien.someday.domain.workspace.WorkspaceProductReadOnlyException
import saien.someday.domain.workspace.WorkspaceProductSnapshot

class CoordinatedWorkspaceProductAccessTest {
    @Test
    fun queuedMutationChecksFreezeAfterEnteringTheProductBarrier() {
        val lifecycle = WorkspaceLifecycleCoordinator()
        var frozen = false
        val access = CoordinatedWorkspaceProductAccess(lifecycle, { identity() }, { frozen })
        val captured = access.capture()
        val barrierEntered = CountDownLatch(1)
        val releaseBarrier = CountDownLatch(1)
        val mutationQueued = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var writes = 0
        try {
            val freezing = executor.submit {
                lifecycle.productAccess {
                    barrierEntered.countDown()
                    assertTrue(releaseBarrier.await(5, TimeUnit.SECONDS))
                    frozen = true
                }
            }
            assertTrue(barrierEntered.await(5, TimeUnit.SECONDS))
            val mutation = executor.submit {
                mutationQueued.countDown()
                assertFailsWith<WorkspaceProductReadOnlyException> {
                    access.mutate(captured) { writes++ }
                }
            }
            assertTrue(mutationQueued.await(5, TimeUnit.SECONDS))
            releaseBarrier.countDown()
            freezing.get(5, TimeUnit.SECONDS)
            mutation.get(5, TimeUnit.SECONDS)
            assertEquals(0, writes)
            assertEquals("export", access.read(captured) { "export" })
            lifecycle.productAccess { frozen = false }
            access.mutate(captured) { writes++ }
            assertEquals(1, writes)
        } finally {
            releaseBarrier.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun replacementInvalidatesCompletedUiWorkWithoutReadingOrWaitingForTheDatabase() {
        val lifecycle = WorkspaceLifecycleCoordinator()
        var providerReads = 0
        val access = CoordinatedWorkspaceProductAccess(lifecycle, {
            providerReads++
            identity()
        }, { false })
        val captured = access.capture()
        val barrierEntered = CountDownLatch(1)
        val releaseBarrier = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val barrier = executor.submit {
                lifecycle.productAccess {
                    barrierEntered.countDown()
                    assertTrue(releaseBarrier.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(barrierEntered.await(5, TimeUnit.SECONDS))
            assertTrue(executor.submit<Boolean> { access.isCurrent(captured) }.get(1, TimeUnit.SECONDS))
            assertEquals(1, providerReads)
            releaseBarrier.countDown()
            barrier.get(5, TimeUnit.SECONDS)
            access.invalidate()
            assertFalse(access.isCurrent(captured))
            assertFailsWith<WorkspaceProductChangedException> { access.mutate(captured) { error("stale write") } }
            assertFalse(access.isCurrent(captured), "Even an identical identity cannot resurrect an invalidated job.")
        } finally {
            releaseBarrier.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun capturedAccountIncarnationCannotBeRetaggedAtCommit() {
        val lifecycle = WorkspaceLifecycleCoordinator()
        var current = identity()
        val access = CoordinatedWorkspaceProductAccess(lifecycle, { current }, { false })
        val captured = access.capture()
        current = current.copy(accountIncarnation = "new-incarnation")
        assertFailsWith<WorkspaceProductChangedException> { access.mutate(captured) { error("retagged write") } }
        assertFalse(access.isCurrent(captured))
        assertEquals("new-incarnation", access.capture().accountIncarnation)
    }

    private fun identity() = WorkspaceProductSnapshot("workspace", "incarnation", "authority", "writer")
}
