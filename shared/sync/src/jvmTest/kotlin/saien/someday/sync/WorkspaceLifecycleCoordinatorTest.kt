package saien.someday.sync

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.someday.data.account.AccountResetIntent
import saien.someday.data.account.AccountStateMutationBoundary
import saien.someday.data.account.SqlDelightAccountStateRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION

class WorkspaceLifecycleCoordinatorTest {
    @Test
    fun productAccessReentersOnTheSameThreadAndReleasesAfterFailure() {
        val coordinator = WorkspaceLifecycleCoordinator()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val nested = executor.submit(Callable {
                coordinator.exclusive {
                    coordinator.productAccess {
                        assertFailsWith<IllegalStateException> {
                            coordinator.productAccess { error("synthetic inner failure") }
                        }
                        coordinator.productAccess { "nested result" }
                    }
                }
            })
            assertEquals("nested result", nested.get(5, TimeUnit.SECONDS))
            assertEquals("released", coordinator.productAccess { "released" })
        } finally { executor.shutdownNow() }
    }

    @Test
    fun nestedAccountReconciliationRemainsInsideTheOuterReplacementTransaction() = withDatabase { database ->
        val coordinator = WorkspaceLifecycleCoordinator()
        val states = states(database, coordinator)
        coordinator.productAccess {
            states.persistIntent(intent())
            states.markSubmitted(OPERATION)
            states.recordCommittedReceipt(OPERATION, NEXT, 2)
            states.recordLocalDiscardConsent(OPERATION, WORKSPACE, NEXT)
        }
        val originalIntent = assertNotNull(states.loadIntent())
        val originalGate = assertNotNull(states.loadGate(ENDPOINT, USER, WORKSPACE))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val replacement = executor.submit(Callable {
                coordinator.exclusive {
                    coordinator.productAccess {
                        assertFailsWith<IllegalStateException> {
                            database.transaction {
                                assertTrue(states.completeLocalReconciliation(OPERATION, NEXT))
                                states.markProtocol1(ENDPOINT, USER)
                                error("synthetic replacement rollback")
                            }
                        }
                    }
                }
            })
            replacement.get(5, TimeUnit.SECONDS)
            assertEquals(originalIntent, states.loadIntent())
            assertEquals(originalGate, states.loadGate(ENDPOINT, USER, WORKSPACE))
            assertFalse(states.hasProtocol1(ENDPOINT, USER))
        } finally { executor.shutdownNow() }
    }

    @Test
    fun accountCallbackWaitsForProductReadWriteTransactionAndCannotReleaseItsOuterLock() = withDatabase { database ->
        val coordinator = WorkspaceLifecycleCoordinator()
        val states = states(database, coordinator)
        val productReading = CountDownLatch(1)
        val releaseProduct = CountDownLatch(1)
        val callbackAttempting = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val product = executor.submit(Callable {
                coordinator.productAccess {
                    // This inner call returning must not release the outer lock.
                    coordinator.productAccess { assertNull(states.loadIntent()) }
                    database.transaction {
                        assertNull(states.loadIntent()) // SQLite deferred read snapshot.
                        productReading.countDown()
                        check(releaseProduct.await(5, TimeUnit.SECONDS))
                        states.markProtocol1(ENDPOINT, USER) // Nested same-thread write.
                    }
                }
            })
            assertTrue(productReading.await(5, TimeUnit.SECONDS))
            val callback = executor.submit(Callable {
                callbackAttempting.countDown()
                try {
                    states.markResetRequired(ENDPOINT, USER, WORKSPACE, INITIAL_ACCOUNT_INCARNATION)
                } finally { callbackFinished.countDown() }
            })
            assertTrue(callbackAttempting.await(5, TimeUnit.SECONDS))
            assertFalse(callbackFinished.await(150, TimeUnit.MILLISECONDS), "Account state bypassed the product transaction boundary.")
            releaseProduct.countDown()
            product.get(5, TimeUnit.SECONDS)
            callback.get(5, TimeUnit.SECONDS)
            assertTrue(states.hasProtocol1(ENDPOINT, USER))
            assertNotNull(states.loadGate(ENDPOINT, USER, WORKSPACE))
        } finally {
            releaseProduct.countDown()
            executor.shutdownNow()
        }
    }

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

    private fun states(database: SomedayDatabase, coordinator: WorkspaceLifecycleCoordinator) =
        SqlDelightAccountStateRepository(database, mutationBoundary = object : AccountStateMutationBoundary {
            override fun <T> mutate(block: () -> T): T = coordinator.productAccess(block)
        })

    private fun withDatabase(block: (SomedayDatabase) -> Unit) {
        val directory = Files.createTempDirectory("someday-product-boundary-")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${directory.resolve("fixture.db").toAbsolutePath()}")
        try { block(SomedayDatabase(driver)) } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun intent() = AccountResetIntent(OPERATION, ENDPOINT, USER, INITIAL_ACCOUNT_INCARNATION,
        WORKSPACE, "00000000-0000-4000-8000-000000000002")

    private companion object {
        const val ENDPOINT = "https://sync.example"
        const val USER = "synthetic-user"
        const val WORKSPACE = "workspace-00000000000000000000000000000000"
        const val OPERATION = "00000000-0000-4000-8000-000000000001"
        const val NEXT = "00000000-0000-4000-8000-000000000003"
    }
}
