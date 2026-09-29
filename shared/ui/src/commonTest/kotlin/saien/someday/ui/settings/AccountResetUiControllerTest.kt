package saien.someday.ui.settings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.someday.domain.settings.*
import saien.someday.domain.workspace.*
import saien.someday.ui.i18n.AccountResetUiStrings
import saien.someday.ui.i18n.SettingsUiStrings

class AccountResetUiControllerTest {
    @Test fun constructionDoesNoIoAndLocalReloadRestoresUnknownGate() = runBlocking {
        val manager = FakeResetManager().apply { snapshot = snapshot.copy(phase = AccountDataResetPhase.OutcomeUnknown, operationId = "operation", productReadOnly = true) }
        val controller = controller(manager)
        assertEquals(0, manager.calls)
        assertNull(controller.state.sync.accountReset.snapshot)
        controller.loadAccountResetState()
        assertEquals(1, manager.calls)
        assertTrue(controller.state.sync.accountReset.blocksSync)
        assertEquals(AccountDataResetPhase.OutcomeUnknown, controller.state.sync.accountReset.snapshot?.phase)
    }

    @Test fun missingSecureCredentialsPreserveThePendingAccountHintAndControlSignIn() = runBlocking {
        for (phase in listOf(AccountDataResetPhase.OutcomeUnknown, AccountDataResetPhase.ResetRequired)) {
            var saved = ClientSettings(syncConfiguration = SyncConfiguration(mode = SyncMode.SelfHosted,
                selfHostedEndpoint = REVIEW.endpoint,
                selfHostedSession = SelfHostedSessionSummary(loggedIn = true, userEmail = REVIEW.accountEmail, deviceId = REVIEW.writerDeviceId)))
            val manager = FakeResetManager().apply {
                snapshot = snapshot.copy(phase = phase, productReadOnly = true)
                onLoad = {
                    snapshot = if (saved.syncConfiguration.selfHostedSession.userEmail == null) {
                        AccountDataResetSnapshot(issue = AccountDataResetIssue.SignInRequired)
                    } else snapshot
                }
            }
            val absentCredentials = object : SelfHostedSessionCredentialStore {
                override fun load(): SelfHostedSessionCredentials? = null
                override fun save(credentials: SelfHostedSessionCredentials) = error("No ordinary login is expected")
                override fun clear() = Unit
            }
            val controller = SettingsUiController(initialSettings = saved, loadSettings = { saved },
                persistSettings = { saved = it; it }, selfHostedSessionCredentialStore = absentCredentials,
                accountDataResetManager = manager, backgroundDispatcher = Dispatchers.Unconfined)
            controller.refresh()
            assertEquals(REVIEW.accountEmail, saved.syncConfiguration.selfHostedSession.userEmail)
            assertFalse(saved.syncConfiguration.selfHostedSession.loggedIn)
            assertTrue(controller.state.sync.accountReset.blocksSync)
            assertEquals(phase, controller.state.sync.accountReset.snapshot!!.phase)
            assertEquals(REVIEW, controller.state.sync.accountReset.snapshot!!.review)
            assertTrue(controller.reauthenticateAccountReset(controller.state.sync.accountReset.snapshot!!.review!!, "new-password"))
        }
    }

    @Test fun exactLocalizedPhraseAndPasswordAreRequiredBeforeSubmit() = runBlocking {
        val manager = FakeResetManager()
        val controller = controller(manager, strings = SettingsUiStrings(accountReset = AccountResetUiStrings(confirmationPhrase = "重置全部账号数据")))
        assertFalse(controller.submitAccountReset(REVIEW, "password-secret", "RESET ALL ACCOUNT DATA"))
        assertFalse(controller.submitAccountReset(REVIEW, "", "重置全部账号数据"))
        assertEquals(0, manager.calls)
        assertTrue(controller.submitAccountReset(REVIEW, "password-secret", "重置全部账号数据"))
        assertEquals(REVIEW, manager.submittedReview)
        assertEquals(1, manager.submits)
        assertFalse(controller.state.toString().contains("password-secret"))
    }

    @Test fun unknownOfflineAndLaterRejectionKeepTheDurableOutcome() = runBlocking {
        val manager = FakeResetManager().apply { snapshot = snapshot.copy(phase = AccountDataResetPhase.OutcomeUnknown, productReadOnly = true, operationId = "operation") }
        val controller = controller(manager)
        controller.loadAccountResetState()
        assertTrue(controller.keepAccountCopyOffline(REVIEW))
        assertTrue(controller.state.sync.accountReset.snapshot!!.offlineEditing)
        assertFalse(controller.state.sync.accountReset.snapshot!!.productReadOnly)
        assertTrue(controller.state.sync.accountReset.blocksSync)
        manager.nextIssue = AccountDataResetIssue.WrongPassword
        assertFalse(controller.submitAccountReset(REVIEW, "wrong-password", AccountResetUiStrings().confirmationPhrase))
        assertEquals(AccountDataResetPhase.OutcomeUnknown, controller.state.sync.accountReset.snapshot!!.phase)
        assertEquals("operation", controller.state.sync.accountReset.snapshot!!.operationId)
    }

    @Test fun localReplacementAlwaysNeedsSeparateConsentAndRefreshesProductOnlyAfterCommit() = runBlocking {
        val manager = FakeResetManager().apply { snapshot = snapshot.copy(phase = AccountDataResetPhase.RemoteCommittedLocalPending, canReplaceLocal = true, productReadOnly = true) }
        var restored = 0
        val controller = controller(manager, restored = { restored++ })
        assertFalse(controller.replaceAccountWorkspace(REVIEW, AccountDataReplacementMode.Fresh, false))
        assertEquals(0, manager.replacements)
        assertEquals(0, restored)
        assertTrue(controller.replaceAccountWorkspace(REVIEW, AccountDataReplacementMode.Fresh, true))
        assertEquals(1, manager.replacements)
        assertEquals(1, restored)
        assertEquals(AccountDataResetPhase.LocalReady, controller.state.sync.accountReset.snapshot!!.phase)
        assertNull(controller.state.sync.invitation)
    }

    @Test fun managerIoUsesDispatcherAndDuplicateSubmissionDoesNotEnterManagerAgain() = runBlocking {
        val manager = FakeResetManager()
        val dispatcher = QueuedDispatcher()
        val controller = controller(manager, dispatcher = dispatcher)
        var completed = false
        val job = kotlinx.coroutines.CoroutineScope(coroutineContext).let { scope ->
            scope.launchReset { completed = controller.submitAccountReset(REVIEW, "password", AccountResetUiStrings().confirmationPhrase) }
        }
        kotlinx.coroutines.yield()
        assertEquals(AccountResetUiOperation.Submitting, controller.state.sync.accountReset.operation)
        assertEquals(0, manager.submits)
        assertFalse(controller.submitAccountReset(REVIEW, "password", AccountResetUiStrings().confirmationPhrase))
        assertFalse(completed)
        dispatcher.runNext()
        job.join()
        assertTrue(completed)
        assertEquals(1, manager.submits)
        assertNull(controller.state.sync.accountReset.operation)
    }

    @Test fun offlineExitSupersedesEveryPendingControlRequestWithoutAllowingADuplicatePost() = runBlocking {
        for (operation in listOf(AccountResetUiOperation.Submitting, AccountResetUiOperation.Refreshing, AccountResetUiOperation.Reconciling, AccountResetUiOperation.Authenticating)) {
            val manager = FakeResetManager().apply {
                snapshot = snapshot.copy(phase = AccountDataResetPhase.OutcomeUnknown, productReadOnly = true, operationId = "operation")
                staleControlResult = AccountDataResetActionResult(snapshot, true)
                completeRemoteDuringControl = true
            }
            val dispatcher = QueuedDispatcher()
            val controller = controller(manager, dispatcher = dispatcher)
            var oldRequestSucceeded = true
            val oldRequest = launch {
                oldRequestSucceeded = when (operation) {
                    AccountResetUiOperation.Submitting -> controller.submitAccountReset(REVIEW, "password", AccountResetUiStrings().confirmationPhrase)
                    AccountResetUiOperation.Refreshing -> controller.refreshAccountReset()
                    AccountResetUiOperation.Reconciling -> controller.reconcileAccountReset(REVIEW)
                    else -> controller.reauthenticateAccountReset(REVIEW, "password")
                }
            }
            kotlinx.coroutines.yield()
            val offline = launch { assertTrue(controller.keepAccountCopyOffline(REVIEW)) }
            kotlinx.coroutines.yield()
            dispatcher.runLast()
            offline.join()
            assertTrue(controller.state.sync.accountReset.snapshot!!.offlineEditing)
            assertFalse(controller.state.sync.accountReset.snapshot!!.productReadOnly)
            assertNull(controller.state.sync.accountReset.operation)
            assertFalse(controller.submitAccountReset(REVIEW, "password", AccountResetUiStrings().confirmationPhrase))
            dispatcher.runNext()
            kotlinx.coroutines.yield()
            dispatcher.runNext() // Superseded HTTP completion reloads durable state.
            oldRequest.join()
            assertFalse(oldRequestSucceeded)
            assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, controller.state.sync.accountReset.snapshot!!.phase)
            assertTrue(controller.state.sync.accountReset.snapshot!!.offlineEditing)
            assertFalse(controller.state.sync.accountReset.snapshot!!.productReadOnly)
            assertEquals("operation", controller.state.sync.accountReset.snapshot!!.operationId)
            assertNull(controller.state.sync.accountReset.operation)
        }
    }

    @Test fun failedEarlyOfflineExitDoesNotHideTheLaterCommittedReset() = runBlocking {
        val manager = FakeResetManager().apply {
            failOffline = true
            completeRemoteDuringControl = true
        }
        val dispatcher = QueuedDispatcher()
        val controller = controller(manager, dispatcher = dispatcher)
        val primary = launch { controller.submitAccountReset(REVIEW, "password", AccountResetUiStrings().confirmationPhrase) }
        kotlinx.coroutines.yield()
        val offline = launch { assertFalse(controller.keepAccountCopyOffline(REVIEW)) }
        kotlinx.coroutines.yield()
        dispatcher.runLast()
        offline.join()
        dispatcher.runNext()
        kotlinx.coroutines.yield()
        dispatcher.runNext()
        primary.join()
        assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, controller.state.sync.accountReset.snapshot!!.phase)
        assertTrue(controller.state.sync.accountReset.snapshot!!.productReadOnly)
        assertFalse(controller.state.sync.accountReset.snapshot!!.offlineEditing)
        assertNull(controller.state.sync.accountReset.operation)
    }

    @Test fun frozenWorkspaceStillAllowsInstallationPreferencesUnderItsCapturedIdentity() = runBlocking {
        val manager = FakeResetManager().apply { snapshot = snapshot.copy(phase = AccountDataResetPhase.OutcomeUnknown, productReadOnly = true) }
        val identity = WorkspaceProductSnapshot("workspace", INITIAL_ACCOUNT_INCARNATION, "authority", "writer")
        var reads = 0
        val access = object : WorkspaceProductAccess {
            override fun capture() = identity
            override fun isCurrent(snapshot: WorkspaceProductSnapshot) = snapshot == identity
            override fun <T> read(snapshot: WorkspaceProductSnapshot, block: () -> T): T {
                assertEquals(identity, snapshot); reads++; return block()
            }
            override fun <T> mutate(snapshot: WorkspaceProductSnapshot?, block: () -> T): T = throw WorkspaceProductReadOnlyException()
        }
        var saved = ClientSettings()
        val controller = SettingsUiController(loadSettings = { saved }, persistSettings = { saved = it; it },
            accountDataResetManager = manager, workspaceProductAccess = access, backgroundDispatcher = Dispatchers.Unconfined)
        controller.loadAccountResetState()
        assertTrue(controller.selectLanguage(AppLanguage.Chinese))
        assertEquals(AppLanguage.Chinese, saved.appLanguage)
        assertEquals(1, reads)
        assertTrue(controller.state.sync.accountReset.snapshot!!.productReadOnly)
    }

    @Test fun dismissingAReviewReloadsTheNewNonceWithoutClearingThePendingOperation() = runBlocking {
        val manager = FakeResetManager().apply { snapshot = snapshot.copy(phase = AccountDataResetPhase.OutcomeUnknown, operationId = "operation", productReadOnly = true) }
        val controller = controller(manager)
        controller.loadAccountResetState()
        val originalReview = controller.state.sync.accountReset.snapshot!!.review!!
        controller.cancelAccountResetReview()
        val nextReview = controller.state.sync.accountReset.snapshot!!.review!!
        assertFalse(originalReview.id == nextReview.id)
        assertEquals("operation", controller.state.sync.accountReset.snapshot!!.operationId)
        assertTrue(controller.submitAccountReset(nextReview, "password", AccountResetUiStrings().confirmationPhrase))
        assertEquals(nextReview, manager.submittedReview)
    }

    @Test fun rawFailuresNeverExposePasswordOrRemotePayloadAndCancelDoesNotClearIntent() = runBlocking {
        val manager = FakeResetManager().apply { throwOnRefresh = true; snapshot = snapshot.copy(phase = AccountDataResetPhase.OutcomeUnknown, operationId = "operation", productReadOnly = true) }
        val controller = controller(manager)
        assertFalse(controller.refreshAccountReset())
        assertFalse(controller.state.toString().contains("secret-payload"))
        assertEquals("operation", controller.state.sync.accountReset.snapshot?.operationId)
        controller.cancelAccountResetReview()
        assertEquals(1, manager.cancels)
        assertEquals("operation", manager.snapshot.operationId)
    }

    @Test fun queuedExportPreventsLocalReplacementUntilItsCapturedCopyIsSaved() = runBlocking {
        val manager = FakeResetManager()
        val dispatcher = SwitchableDispatcher()
        var exports = 0
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() }, accountDataResetManager = manager,
            exportProvider = { exports++; SettingsExportSummary.unavailable() }, backgroundDispatcher = dispatcher,
        )
        controller.refresh()
        dispatcher.queueing = true
        var exportSucceeded = false
        var replacementSucceeded = true
        val export = launch(start = CoroutineStart.UNDISPATCHED) { exportSucceeded = controller.runLocalExport() }
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            replacementSucceeded = controller.replaceAccountWorkspace(REVIEW, AccountDataReplacementMode.Fresh, true)
        }
        dispatcher.queueing = false
        dispatcher.runAll()
        export.join()
        replacement.join()

        assertTrue(exportSucceeded)
        assertFalse(replacementSucceeded)
        assertEquals(1, exports)
        assertEquals(0, manager.replacements)
        assertTrue(controller.replaceAccountWorkspace(REVIEW, AccountDataReplacementMode.Fresh, true))
    }

    @Test fun pendingLocalReplacementDoesNotStartAnExportOfItsNewCopy() = runBlocking {
        val manager = FakeResetManager()
        val dispatcher = SwitchableDispatcher()
        var exports = 0
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() }, accountDataResetManager = manager,
            exportProvider = { exports++; SettingsExportSummary.unavailable() }, backgroundDispatcher = dispatcher,
        )
        controller.refresh()
        dispatcher.queueing = true
        var exportSucceeded = true
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            assertTrue(controller.replaceAccountWorkspace(REVIEW, AccountDataReplacementMode.Fresh, true))
        }
        val export = launch(start = CoroutineStart.UNDISPATCHED) { exportSucceeded = controller.runLocalExport() }
        dispatcher.queueing = false
        dispatcher.runAll()
        replacement.join()
        export.join()

        assertFalse(exportSucceeded)
        assertEquals(0, exports)
        assertEquals(1, manager.replacements)
    }

    @Test fun exportCompletedAfterExternalWorkspaceReplacementDoesNotReportBackupSuccess() = runBlocking {
        var identity = WorkspaceProductSnapshot("old", INITIAL_ACCOUNT_INCARNATION, "authority", "writer")
        val access = object : WorkspaceProductAccess {
            override fun capture() = identity
            override fun isCurrent(snapshot: WorkspaceProductSnapshot) = snapshot == identity
            override fun <T> read(snapshot: WorkspaceProductSnapshot, block: () -> T): T {
                if (!isCurrent(snapshot)) throw WorkspaceProductChangedException()
                return block()
            }
            override fun <T> mutate(snapshot: WorkspaceProductSnapshot?, block: () -> T): T = error("Export must remain read-only")
        }
        val controller = SettingsUiController(
            loadSettings = { ClientSettings() }, workspaceProductAccess = access,
            exportProvider = {
                identity = identity.copy(workspaceId = "new", localRevision = identity.localRevision + 1)
                SettingsExportSummary.unavailable()
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
        controller.refresh()
        val priorFeedback = controller.state.feedbackMessage

        assertFailsWith<CancellationException> { controller.runLocalExport() }

        assertNull(controller.state.exportSummary)
        assertEquals(priorFeedback, controller.state.feedbackMessage)
    }

    private fun controller(manager: FakeResetManager, strings: SettingsUiStrings = SettingsUiStrings(), restored: () -> Unit = {}, dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) = SettingsUiController(
        loadSettings = { ClientSettings() }, accountDataResetManager = manager,
        uiStrings = strings, onDataRestored = restored, backgroundDispatcher = dispatcher,
    )

    private class FakeResetManager : AccountDataResetManager {
        var snapshot = AccountDataResetSnapshot(AccountDataResetPhase.Ready, REVIEW, REVIEW.endpoint, REVIEW.accountEmail, resetAvailable = true)
        var calls = 0; var submits = 0; var replacements = 0; var cancels = 0
        var submittedReview: AccountDataResetReview? = null
        var nextIssue: AccountDataResetIssue? = null
        var throwOnRefresh = false
        var staleControlResult: AccountDataResetActionResult? = null
        var completeRemoteDuringControl = false
        var failOffline = false
        var onLoad: (() -> Unit)? = null
        private fun controlResult(): AccountDataResetActionResult {
            if (completeRemoteDuringControl) snapshot = snapshot.copy(phase = AccountDataResetPhase.RemoteCommittedLocalPending,
                productReadOnly = !snapshot.offlineEditing, resetAvailable = false)
            return staleControlResult ?: result()
        }
        override fun load(): AccountDataResetSnapshot { calls++; onLoad?.invoke(); return snapshot }
        private fun result() = AccountDataResetActionResult(snapshot, nextIssue == null, issue = nextIssue)
        override fun refresh(): AccountDataResetActionResult { calls++; if (throwOnRefresh) error("secret-payload"); return controlResult() }
        override fun submit(review: AccountDataResetReview, password: String): AccountDataResetActionResult { calls++; submits++; submittedReview = review; return controlResult() }
        override fun reauthenticate(review: AccountDataResetReview, password: String): AccountDataResetActionResult { calls++; return controlResult() }
        override fun reconcile(review: AccountDataResetReview): AccountDataResetActionResult { calls++; return controlResult() }
        override fun keepOffline(review: AccountDataResetReview): AccountDataResetActionResult {
            calls++
            if (failOffline) return AccountDataResetActionResult(snapshot, false, issue = AccountDataResetIssue.LocalFailure)
            snapshot = snapshot.copy(offlineEditing = true, productReadOnly = false)
            return result()
        }
        override fun replaceLocal(review: AccountDataResetReview, mode: AccountDataReplacementMode, discardConfirmed: Boolean, secret: String, password: String?): AccountDataResetActionResult {
            calls++; replacements++; snapshot = snapshot.copy(phase = AccountDataResetPhase.LocalReady, productReadOnly = false, canReplaceLocal = false)
            return AccountDataResetActionResult(snapshot, true, localReplaced = true)
        }
        override fun cancel() { calls++; cancels++; snapshot = snapshot.copy(review = snapshot.review?.copy(id = "capture-$cancels")) }
    }
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() { tasks.removeFirst().run() }
        fun runLast() { tasks.removeLast().run() }
    }
    private class SwitchableDispatcher : CoroutineDispatcher() {
        var queueing = false
        private val tasks = ArrayDeque<Runnable>()
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = queueing
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private companion object {
        val REVIEW = AccountDataResetReview("capture", "authority", "https://sync.example.invalid", "synthetic@example.invalid", "workspace-old", "writer", INITIAL_ACCOUNT_INCARNATION, "authority", INITIAL_ACCOUNT_INCARNATION, null)
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchReset(block: suspend () -> Unit) = launch { block() }
