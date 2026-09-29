package saien.someday.sync.selfhosted

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.someday.data.account.AccountNetworkBlockedException
import saien.someday.data.account.AccountResetIntentState
import saien.someday.data.account.SqlDelightAccountStateRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId
import saien.someday.sync.WorkspaceLifecycleCoordinator

class SelfHostedAccountResetServiceTest {
    @Test fun submittedIntentSurvivesReopenAnd404NeverReleasesOfflineNetworkGate() = fixture { f ->
        f.transport.post = {
            assertEquals(AccountResetIntentState.OutcomeUnknown, f.states.loadIntent()?.state)
            assertTrue(f.states.loadIntent()!!.earlierOutcomeUnknown)
            throw IOException("lost response")
        }
        assertIs<SelfHostedAccountResetResult.OutcomeUnknown>(f.submit())
        f.reopen()
        assertNotNull(f.states.loadIntent())
        f.service.keepCopyAndEditOffline(OPERATION)
        f.database.somedayQueries.insertOrReplaceSetting("local-copy", "offline edit", 2)
        assertTrue(f.states.loadGate(ENDPOINT, USER, WORKSPACE)!!.offlineEditing)
        assertNull(f.states.loadIntent()?.consentTargetIncarnation)
        assertIs<SelfHostedAccountResetResult.OutcomeUnknown>(f.service.reconcile(OPERATION))
        assertFailsWith<AccountNetworkBlockedException> { f.states.requireNetworkAllowed(ENDPOINT, USER, WORKSPACE) }
        assertEquals("offline edit", f.database.somedayQueries.selectSetting("local-copy").executeAsOne().value_)
        assertFalse(f.states.loadIntent().toString().contains(PASSWORD))
        assertEquals(1, f.transport.resetCalls)
    }

    @Test fun laterWrongPasswordCannotEraseAnEarlierUnknownSubmission() = fixture { f ->
        f.transport.post = { throw IOException("lost response") }
        f.submit()
        f.transport.post = { throw error(SelfHostedErrorCode.INVALID_CREDENTIALS, 403) }
        val rejected = assertIs<SelfHostedAccountResetResult.Rejected>(f.submit())
        assertTrue(rejected.localGateRetained)
        assertEquals(2L, f.states.loadIntent()?.submissionNumber)
        assertTrue(f.states.loadIntent()!!.earlierOutcomeUnknown)
        assertEquals(2, f.transport.resetCalls, "Each explicit submission reaches password verification once.")
        assertFailsWith<IllegalArgumentException> { f.submit("22222222-2222-4222-8222-222222222222") }
        assertEquals(2, f.transport.resetCalls)
    }

    @Test fun firstDefiniteBusyRejectionClearsOnlyItsIntentWhenOriginalIncarnationIsCurrent() = fixture { f ->
        f.transport.post = { throw error(SelfHostedErrorCode.ACCOUNT_BUSY, 503) }
        val rejected = assertIs<SelfHostedAccountResetResult.Rejected>(f.submit())
        assertFalse(rejected.localGateRetained)
        assertNull(f.states.loadIntent())
        assertNull(f.states.loadGate(ENDPOINT, USER, WORKSPACE))
        assertEquals(1, f.transport.resetCalls)
    }

    @Test fun failedIntentPersistencePreventsPostAndLeavesLocalContent() = fixture { f ->
        f.driver.execute(null, "CREATE TRIGGER reject_reset_intent BEFORE INSERT ON account_reset_intents BEGIN SELECT RAISE(ABORT, 'injected'); END", 0)
        assertFailsWith<Exception> { f.submit() }
        assertEquals(0, f.transport.resetCalls)
        assertNull(f.states.loadIntent())
        assertNull(f.states.loadGate(ENDPOINT, USER, WORKSPACE))
        assertEquals("original", f.database.somedayQueries.selectSetting("local-copy").executeAsOne().value_)
    }

    @Test fun receiptPreservesLocalCopyAndOfflineEditingRequiresFreshDiscardConsent() = fixture { f ->
        f.transport.post = { receipt() }
        val result = assertIs<SelfHostedAccountResetResult.Committed>(f.submit())
        assertTrue(result.locallyRecorded)
        assertEquals(AccountResetIntentState.RemoteCommittedLocalPending, f.states.loadIntent()?.state)
        assertEquals("original", f.database.somedayQueries.selectSetting("local-copy").executeAsOne().value_)
        assertFailsWith<AccountNetworkBlockedException> { f.states.requireNetworkAllowed(ENDPOINT, USER, WORKSPACE) }
        f.store.save(credentials().copy(accountIncarnation = SUCCESSOR))
        f.transport.currentIncarnation = SUCCESSOR
        f.service.confirmLocalReplacement(OPERATION, f.workspace, SUCCESSOR)
        assertEquals(SUCCESSOR, f.states.loadIntent()?.consentTargetIncarnation)
        f.service.keepCopyAndEditOffline(OPERATION)
        assertNull(f.states.loadIntent()?.consentTargetIncarnation)
        assertFalse(f.states.completeLocalReconciliation(OPERATION, SUCCESSOR))
        f.reopen()
        f.service.confirmLocalReplacement(OPERATION, f.workspace, SUCCESSOR)
        assertTrue(f.states.completeLocalReconciliation(OPERATION, SUCCESSOR))
        assertNull(f.states.loadIntent())
    }

    @Test fun networkWaitHoldsNoLifecycleLockAndConcurrentDuplicateIsRejected() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newFixedThreadPool(2)
        f.transport.post = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            throw IOException("lost response")
        }
        try {
            val first = worker.submit<SelfHostedAccountResetResult> { f.submit() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals("available", worker.submit<String> { f.lifecycle.exclusive { "available" } }.get(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { f.submit() }
            assertEquals(1, f.transport.resetCalls)
            release.countDown()
            assertIs<SelfHostedAccountResetResult.OutcomeUnknown>(first.get(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            worker.shutdownNow()
            check(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun offlineExitInvalidatesDiscardConfirmationWaitingForDiscovery() = fixture { f ->
        f.submit()
        f.store.save(credentials().copy(accountIncarnation = SUCCESSOR))
        f.transport.currentIncarnation = SUCCESSOR
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        f.transport.beforeDiscovery = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        try {
            val confirmation = worker.submit<Throwable?> {
                runCatching { f.service.confirmLocalReplacement(OPERATION, f.workspace, SUCCESSOR) }.exceptionOrNull()
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            f.service.keepCopyAndEditOffline(OPERATION)
            f.lifecycle.productAccess {
                f.database.somedayQueries.insertOrReplaceSetting("local-copy", "new offline edit", 2)
            }
            release.countDown()
            assertIs<IllegalStateException>(confirmation.get(10, TimeUnit.SECONDS))
            assertNull(f.states.loadIntent()?.consentTargetIncarnation)
            assertTrue(f.states.loadGate(ENDPOINT, USER, WORKSPACE)!!.offlineEditing)
            assertFalse(f.states.completeLocalReconciliation(OPERATION, SUCCESSOR))
            assertEquals("new offline edit", f.database.somedayQueries.selectSetting("local-copy").executeAsOne().value_)
        } finally {
            release.countDown()
            worker.shutdownNow()
            check(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val file = Files.createTempFile("someday-reset-client-", ".db")
        val fixture = Fixture("jdbc:sqlite:$file")
        try { block(fixture) } finally { fixture.driver.close(); Files.deleteIfExists(file) }
    }

    private class Fixture(private val url: String) {
        var driver = createSomedayJdbcDriver(url)
        var database = SomedayDatabase(driver)
        var states = SqlDelightAccountStateRepository(database)
        val store = MemorySessionStore(credentials())
        val transport = ResetControlFixture()
        val lifecycle = WorkspaceLifecycleCoordinator()
        val workspace = AccountResetLocalWorkspace(WORKSPACE, "writer-a", credentials().authorityBindingId)
        var service = newService()
        init { database.somedayQueries.insertOrReplaceSetting("local-copy", "original", 1) }
        fun submit(operationId: String = OPERATION) = service.submit(operationId, PASSWORD, INITIAL_ACCOUNT_INCARNATION, credentials().authorityBindingId, workspace)
        fun reopen() {
            driver.close()
            driver = createSomedayJdbcDriver(url)
            database = SomedayDatabase(driver)
            states = SqlDelightAccountStateRepository(database)
            service = newService()
        }
        private fun newService() = SelfHostedAccountResetService(states, store, transport,
            SelfHostedAccountDiscoveryService(transport, states), lifecycle) { workspace }
    }

    private class ResetControlFixture : SelfHostedAccountControlTransport {
        var resetCalls = 0
        var currentIncarnation = INITIAL_ACCOUNT_INCARNATION
        var beforeDiscovery: () -> Unit = {}
        var post: (SelfHostedAccountResetRequest) -> SelfHostedAccountResetReceiptResponse = { receipt() }
        override fun discoverAccountData(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountDiscoveryResult {
            beforeDiscovery()
            return SelfHostedAccountDiscoveryResult.Protocol1(SelfHostedAccountDataStateResponse(1, currentIncarnation, true, null))
        }
        override fun accountMe(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext) =
            SelfHostedAccountMeResponse(USER, "synthetic@example.invalid", "writer-a", listOf("auth"))
        override fun getAccountResetReceipt(endpoint: String, accessToken: String, operationId: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountResetReceiptResponse? = null
        override fun resetAccountData(endpoint: String, accessToken: String, request: SelfHostedAccountResetRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountResetReceiptResponse {
            resetCalls++
            assertEquals(INITIAL_ACCOUNT_INCARNATION, accountContext.accountIncarnation)
            assertTrue(accountContext.protocol1Known)
            return post(request)
        }
    }

    private companion object {
        const val ENDPOINT = "https://reset.example.invalid"
        const val USER = "11111111-1111-4111-8111-111111111111"
        const val WORKSPACE = "workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OPERATION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SUCCESSOR = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val PASSWORD = "synthetic-reset-password"
        fun credentials() = SelfHostedSessionCredentials(ENDPOINT, USER, "synthetic@example.invalid", "writer-a", "Synthetic", "desktop", "access", "refresh", accountProtocolVersion = 1)
        fun receipt() = SelfHostedAccountResetReceiptResponse(1, OPERATION, INITIAL_ACCOUNT_INCARNATION, SUCCESSOR, 123)
        fun error(code: SelfHostedErrorCode, status: Int) = SelfHostedSyncHttpException(status, "Synthetic failure", code, true)
    }
}
