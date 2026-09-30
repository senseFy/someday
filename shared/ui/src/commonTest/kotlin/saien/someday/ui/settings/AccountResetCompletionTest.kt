package saien.someday.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import saien.someday.domain.settings.*
import saien.someday.domain.workspace.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountResetCompletionTest {
    @Test fun everyReplacementAdoptsTheEnrolledSessionChecksRecoveryAndSynchronizes() = runBlocking {
        for (mode in AccountDataReplacementMode.entries) {
            val fixture = CompletionFixture()
            fixture.controller.refresh()
            assertTrue(fixture.controller.state.sync.connection is SyncConnectionUi.LocalOnly)
            assertTrue(fixture.controller.state.sync.accountReset.blocksSync)

            assertTrue(fixture.controller.replaceAccountWorkspace(REVIEW, mode, true, "synthetic-code"))

            assertEquals(listOf("replace", "recovery", "sync"), fixture.operations)
            assertTrue(fixture.controller.state.sync.connection is SyncConnectionUi.Connected)
            assertFalse(fixture.controller.state.sync.accountReset.blocksSync)
            assertFalse(fixture.controller.state.sync.recovery.blocksSync)
            assertNull(fixture.controller.state.sync.issue)
            assertTrue(fixture.controller.canRunAutomaticSync())
            assertEquals(2, fixture.productRefreshes)
        }
    }

    @Test fun failedFirstSyncRetriesTheNewCopyWithoutRepeatingReplacement() = runBlocking {
        val fixture = CompletionFixture().apply { failSync = true }
        fixture.controller.refresh()

        // True means local replacement committed, even when the following HTTP failed.
        assertTrue(fixture.controller.replaceAccountWorkspace(REVIEW, AccountDataReplacementMode.Fresh, true))
        assertEquals(AccountDataResetPhase.LocalReady, fixture.controller.state.sync.accountReset.snapshot?.phase)
        assertEquals(SyncIssueAction.RetrySync, fixture.controller.state.sync.issue?.action)
        assertEquals(1, fixture.replacements)
        fixture.failSync = false

        assertTrue(fixture.controller.runUserSync())

        assertEquals(listOf("replace", "recovery", "sync", "sync"), fixture.operations)
        assertEquals(1, fixture.replacements)
        assertNull(fixture.controller.state.sync.issue)
    }

    @Test fun recoveryCheckFailureOffersSyncRetryAndNeverClaimsAnotherDiscardIsNeeded() = runBlocking {
        val fixture = CompletionFixture().apply { failRecovery = true }
        fixture.controller.refresh()

        assertTrue(fixture.controller.replaceAccountWorkspace(REVIEW, AccountDataReplacementMode.Recover, true, "synthetic-code"))
        assertEquals(listOf("replace", "recovery"), fixture.operations)
        assertFalse(fixture.controller.state.sync.needsAccountDataResolution)
        assertFalse(fixture.controller.canRunAutomaticSync())
        assertEquals(SyncIssueAction.RetrySync, fixture.controller.state.sync.issue?.action)
        fixture.failRecovery = false

        assertTrue(fixture.controller.runUserSync())

        assertEquals(listOf("replace", "recovery", "recovery", "sync"), fixture.operations)
        assertEquals(1, fixture.replacements)
        assertTrue(fixture.controller.canRunAutomaticSync())
    }

    @Test fun delayedRecoveryCheckCannotStartSyncForADifferentLocalCopy() = runBlocking {
        val fixture = CompletionFixture()
        fixture.controller.refresh()
        fixture.onRecovery = { fixture.identity = fixture.identity.copy(workspaceId = "another-copy", localRevision = 1) }

        assertFailsWith<CancellationException> {
            fixture.controller.replaceAccountWorkspace(REVIEW, AccountDataReplacementMode.Fresh, true)
        }

        assertEquals(listOf("replace", "recovery"), fixture.operations)
        assertNull(fixture.controller.state.sync.operation)
        assertFalse(fixture.controller.state.sync.busy)
    }

    @Test fun systemExportDoesNotReportSavedBeforeTheUserChoosesADestination() = runBlocking {
        for (result in listOf(LocalExportResult.Cancelled, LocalExportResult.Failed,
            LocalExportResult.Saved(SettingsExportSummary("json", 1, 2, emptyList(), destinationLabel = "backup.json")))) {
            val completion = CompletableDeferred<LocalExportResult>()
            val controller = SettingsUiController(loadSettings = { ClientSettings() },
                localExportRunner = LocalExportRunner { completion.await() }, backgroundDispatcher = Dispatchers.Unconfined)
            var succeeded = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) { succeeded = controller.runLocalExport() }
            assertNull(controller.state.exportSummary)
            assertNull(controller.state.feedbackMessage)
            completion.complete(result)
            job.join()
            assertEquals(result is LocalExportResult.Saved, succeeded)
            if (result is LocalExportResult.Saved) {
                assertEquals("backup.json", controller.state.exportSummary?.destinationLabel)
                assertEquals(SettingsFeedbackSeverity.Success, controller.state.feedbackSeverity)
            } else {
                assertNull(controller.state.exportSummary)
                if (result == LocalExportResult.Cancelled) assertNull(controller.state.feedbackMessage)
                else assertEquals(SettingsFeedbackSeverity.Error, controller.state.feedbackSeverity)
            }
        }
    }

    private class CompletionFixture {
        var saved = ClientSettings()
        var credentials: SelfHostedSessionCredentials? = null
        var failSync = false
        var failRecovery = false
        var replacements = 0
        var productRefreshes = 0
        var onRecovery: (() -> Unit)? = null
        var identity = WorkspaceProductSnapshot("workspace", INITIAL_ACCOUNT_INCARNATION, null, "writer")
        val access = object : WorkspaceProductAccess {
            override fun capture() = identity
            override fun isCurrent(snapshot: WorkspaceProductSnapshot) = snapshot == identity
            override fun <T> read(snapshot: WorkspaceProductSnapshot, block: () -> T): T {
                if (!isCurrent(snapshot)) throw WorkspaceProductChangedException()
                return block()
            }
            override fun <T> mutate(snapshot: WorkspaceProductSnapshot?, block: () -> T): T = read(snapshot ?: identity, block)
        }
        val operations = mutableListOf<String>()
        var snapshot = AccountDataResetSnapshot(phase = AccountDataResetPhase.RemoteCommittedLocalPending,
            review = REVIEW, productReadOnly = true, canReplaceLocal = true)
        private val store = object : SelfHostedSessionCredentialStore {
            override fun load() = credentials
            override fun save(credentials: SelfHostedSessionCredentials) { this@CompletionFixture.credentials = credentials }
            override fun clear() { credentials = null }
        }
        private val reset = object : AccountDataResetManager by UnavailableAccountDataResetManager {
            override fun load() = snapshot
            override fun replaceLocal(review: AccountDataResetReview, mode: AccountDataReplacementMode, discardConfirmed: Boolean, secret: String, password: String?): AccountDataResetActionResult {
                assertTrue(discardConfirmed)
                operations += "replace"
                replacements++
                credentials = SelfHostedSessionCredentials(REVIEW.endpoint, "user", REVIEW.accountEmail,
                    REVIEW.writerDeviceId, "device", "test", "access", "refresh")
                saved = saved.copy(syncConfiguration = SyncConfiguration(mode = SyncMode.SelfHosted,
                    selfHostedEndpoint = REVIEW.endpoint, selfHostedSession = SelfHostedSessionSummary(
                        loggedIn = true, userEmail = REVIEW.accountEmail, deviceId = REVIEW.writerDeviceId)))
                snapshot = snapshot.copy(phase = AccountDataResetPhase.LocalReady, productReadOnly = false, canReplaceLocal = false)
                return AccountDataResetActionResult(snapshot, true, localReplaced = true)
            }
        }
        private val recovery = object : WorkspaceRecoveryManager by UnavailableWorkspaceRecoveryManager {
            override fun status(): WorkspaceRecoveryStatusResult {
                operations += "recovery"
                onRecovery?.invoke()
                return if (failRecovery) WorkspaceRecoveryStatusResult.failure(WorkspaceRecoveryReason.ServerRequestFailed)
                else WorkspaceRecoveryStatusResult.ready(WorkspaceRecoveryState.NotConfigured, WorkspaceRecoverySyncGate.Allowed, WorkspaceRecoveryReason.NotConfigured)
            }
        }
        val controller = SettingsUiController(loadSettings = { saved }, persistSettings = { saved = it; it },
            selfHostedSessionCredentialStore = store, accountDataResetManager = reset, workspaceRecoveryManager = recovery,
            workspaceProductAccess = access,
            manualSyncRunner = ManualSyncRunner {
                operations += "sync"
                if (failSync) ManualSyncResult.failure(SyncMode.SelfHosted, ManualSyncReason.Failed)
                else ManualSyncResult.success(SyncMode.SelfHosted, 1, 1, 0)
            }, onDataRestored = { productRefreshes++ }, backgroundDispatcher = Dispatchers.Unconfined)
    }

    private companion object {
        val REVIEW = AccountDataResetReview("capture", "authority", "https://sync.example.invalid", "synthetic@example.invalid",
            "workspace-old", "writer", INITIAL_ACCOUNT_INCARNATION, "authority", INITIAL_ACCOUNT_INCARNATION, null)
    }
}
