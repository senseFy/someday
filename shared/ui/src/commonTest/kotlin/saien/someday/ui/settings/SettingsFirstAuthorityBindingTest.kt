package saien.someday.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.someday.domain.settings.AccountDataReplacementMode
import saien.someday.domain.settings.AccountDataResetActionResult
import saien.someday.domain.settings.AccountDataResetManager
import saien.someday.domain.settings.AccountDataResetPhase
import saien.someday.domain.settings.AccountDataResetReview
import saien.someday.domain.settings.AccountDataResetSnapshot
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.UnavailableWorkspaceRecoveryManager
import saien.someday.domain.settings.WorkspaceRecoveryManager
import saien.someday.domain.settings.WorkspaceRecoveryReason
import saien.someday.domain.settings.WorkspaceRecoveryState
import saien.someday.domain.settings.WorkspaceRecoveryStatusResult
import saien.someday.domain.settings.WorkspaceRecoverySyncGate
import saien.someday.domain.workspace.WorkspaceProductAccess
import saien.someday.domain.workspace.WorkspaceProductChangedException
import saien.someday.domain.workspace.WorkspaceProductSnapshot

class SettingsFirstAuthorityBindingTest {
    @Test
    fun firstManualPublicationAdoptsBindingAndKeepsSettingsLoadedBySync() = runBlocking {
        val fixture = Fixture()
        fixture.controller.refresh()
        fixture.runner = {
            fixture.bind()
            fixture.stored = fixture.stored.copy(theme = ClientTheme.Dark)
            completed()
        }

        assertTrue(fixture.controller.runUserSync())

        assertEquals(1, fixture.restored)
        assertEquals("Sync complete.", fixture.controller.state.feedbackMessage)
        assertEquals(ClientTheme.Dark, fixture.stored.theme)
        assertEquals(ClientTheme.Dark, fixture.controller.state.settings.theme)
        assertNull(fixture.stored.syncConfiguration.lastError)
        assertNull(fixture.controller.state.sync.operation)
        assertTrue(fixture.controller.selectTheme(ClientTheme.Light), "Later settings writes use the accepted binding.")
    }

    @Test
    fun firstAutomaticPublicationStillRefreshesProductData() = runBlocking {
        val fixture = Fixture()
        fixture.controller.refresh()
        fixture.runner = { fixture.bind(); completed() }

        assertTrue(fixture.controller.runAutomaticSync())

        assertEquals(1, fixture.restored)
        assertNull(fixture.controller.state.sync.issue)
        assertNull(fixture.controller.state.sync.operation)
    }

    @Test
    fun firstSyncThenFreshReplacementCanSyncAgainWithoutRefreshingTheController() = runBlocking {
        val fixture = Fixture()
        fixture.controller.refresh()
        fixture.runner = { fixture.bind(); completed() }
        assertTrue(fixture.controller.runUserSync())
        val review = AccountDataResetReview("review", "authority", "https://sync.example.test",
            "synthetic@example.test", "workspace", "writer", INITIAL_ACCOUNT_INCARNATION,
            "authority", "11111111-1111-4111-8111-111111111111", "operation")
        fixture.resetSnapshot = AccountDataResetSnapshot(AccountDataResetPhase.RemoteCommittedLocalPending,
            review = review, productReadOnly = true, canReplaceLocal = true)
        fixture.controller.loadAccountResetState()

        assertTrue(fixture.controller.replaceAccountWorkspace(review, AccountDataReplacementMode.Fresh, true))
        fixture.runner = { completed() }
        assertTrue(fixture.controller.runUserSync())

        assertEquals("replacement", fixture.access.identity.workspaceId)
        assertEquals(3, fixture.restored)
        assertEquals("Sync complete.", fixture.controller.state.feedbackMessage)
        assertNull(fixture.controller.state.sync.operation)
        assertNull(fixture.stored.syncConfiguration.lastError)
    }

    @Test
    fun partialFailureAfterInitialBindingPersistsFailureAndRefreshesPulledData() = runBlocking {
        val fixture = Fixture()
        fixture.controller.refresh()
        fixture.runner = {
            fixture.bind()
            fixture.stored = fixture.stored.copy(theme = ClientTheme.Dark)
            ManualSyncResult.failure(SyncMode.SelfHosted, ManualSyncReason.Failed, pulledObjects = 1)
        }

        assertFalse(fixture.controller.runUserSync())

        assertEquals(1, fixture.restored)
        assertEquals("sync:Failed", fixture.stored.syncConfiguration.lastError)
        assertEquals(ClientTheme.Dark, fixture.stored.theme)
        assertEquals(SyncIssueReason.SyncFailed, fixture.controller.state.sync.issue?.reason)
        assertNull(fixture.controller.state.sync.operation)
    }

    @Test
    fun syncCompletionCannotAdoptReplacementIncarnationWriterOrSkippedRevision() = runBlocking {
        val transforms: List<(WorkspaceProductSnapshot) -> WorkspaceProductSnapshot> = listOf(
            { it.copy(authorityBindingId = "authority", workspaceId = "replacement", localRevision = 1) },
            { it.copy(authorityBindingId = "authority", accountIncarnation = "new-incarnation", localRevision = 1) },
            { it.copy(authorityBindingId = "authority", writerDeviceId = "different-writer", localRevision = 1) },
            { it.copy(authorityBindingId = "authority", localRevision = 2) },
            { it.copy(authorityBindingId = "authority") },
        )
        for (transform in transforms) {
            val fixture = Fixture()
            fixture.controller.refresh()
            fixture.writes = 0
            fixture.runner = { fixture.access.identity = transform(fixture.access.identity); completed() }

            assertFailsWith<CancellationException> { fixture.controller.runUserSync() }

            assertEquals(0, fixture.writes)
            assertEquals(0, fixture.restored)
            assertNull(fixture.controller.state.sync.operation)
        }
    }

    @Test
    fun existingAuthorityCannotBeReboundBySyncCompletion() = runBlocking {
        val fixture = Fixture(initialAuthority = "original-authority")
        fixture.controller.refresh()
        fixture.writes = 0
        fixture.runner = { fixture.bind(); completed() }

        assertFailsWith<CancellationException> { fixture.controller.runUserSync() }

        assertEquals(0, fixture.writes)
        assertEquals(0, fixture.restored)
    }

    @Test
    fun replacementBetweenBindingCaptureAndPersistenceStillRefusesTheWrite() = runBlocking {
        val fixture = Fixture()
        fixture.controller.refresh()
        fixture.writes = 0
        fixture.runner = {
            fixture.bind()
            fixture.access.afterCapture = {
                fixture.access.identity = fixture.access.identity.copy(workspaceId = "replacement", localRevision = 2)
            }
            completed()
        }

        assertFailsWith<CancellationException> { fixture.controller.runUserSync() }

        assertEquals(0, fixture.writes)
        assertEquals(0, fixture.restored)
    }

    private class Fixture(initialAuthority: String? = null) {
        var stored = ClientSettings(activeDeviceId = "writer", syncConfiguration = SyncConfiguration(
            SyncMode.SelfHosted, "https://sync.example.test",
            SelfHostedSessionSummary(true, "synthetic@example.test", "writer", "Test device", "desktop"),
        ))
        val access = CapturedAccess(WorkspaceProductSnapshot("workspace", INITIAL_ACCOUNT_INCARNATION, initialAuthority, "writer"))
        var writes = 0
        var restored = 0
        var runner: () -> ManualSyncResult = { completed() }
        var resetSnapshot = AccountDataResetSnapshot()
        val resetManager = object : AccountDataResetManager {
            override fun load() = resetSnapshot
            override fun refresh() = error("Unexpected refresh")
            override fun submit(review: AccountDataResetReview, password: String) = error("Unexpected submit")
            override fun reauthenticate(review: AccountDataResetReview, password: String) = error("Unexpected login")
            override fun reconcile(review: AccountDataResetReview) = error("Unexpected reconcile")
            override fun keepOffline(review: AccountDataResetReview) = error("Unexpected offline action")
            override fun replaceLocal(review: AccountDataResetReview, mode: AccountDataReplacementMode,
                discardConfirmed: Boolean, secret: String, password: String?): AccountDataResetActionResult {
                assertEquals(AccountDataReplacementMode.Fresh, mode)
                assertTrue(discardConfirmed)
                access.identity = access.identity.copy(workspaceId = "replacement",
                    accountIncarnation = review.targetIncarnation, localRevision = access.identity.localRevision + 1)
                resetSnapshot = AccountDataResetSnapshot(AccountDataResetPhase.LocalReady)
                return AccountDataResetActionResult(resetSnapshot, true, localReplaced = true)
            }
            override fun cancel() = Unit
        }
        val controller = SettingsUiController(
            initialSettings = stored,
            loadSettings = { stored },
            persistSettings = { stored = it; writes++; it },
            manualSyncRunner = { runner() },
            automaticSyncEligible = { true },
            onDataRestored = { restored++ },
            workspaceProductAccess = access,
            accountDataResetManager = resetManager,
            workspaceRecoveryManager = object : WorkspaceRecoveryManager by UnavailableWorkspaceRecoveryManager {
                override fun status() = WorkspaceRecoveryStatusResult.ready(WorkspaceRecoveryState.NotConfigured,
                    WorkspaceRecoverySyncGate.Allowed, WorkspaceRecoveryReason.NotConfigured)
            },
            selfHostedSessionCredentialStore = object : SelfHostedSessionCredentialStore {
                override fun load() = SelfHostedSessionCredentials("https://sync.example.test", "user", "synthetic@example.test",
                    "writer", "Test device", "desktop", "synthetic-access", "synthetic-refresh")
                override fun save(credentials: SelfHostedSessionCredentials) = Unit
                override fun clear() = Unit
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )

        fun bind() { access.identity = access.identity.copy(authorityBindingId = "authority", localRevision = access.identity.localRevision + 1) }
    }

    private class CapturedAccess(var identity: WorkspaceProductSnapshot) : WorkspaceProductAccess {
        var afterCapture: (() -> Unit)? = null
        override fun capture() = identity.also { afterCapture?.also { afterCapture = null }?.invoke() }
        override fun isCurrent(snapshot: WorkspaceProductSnapshot) = snapshot == identity
        override fun <T> read(snapshot: WorkspaceProductSnapshot, block: () -> T): T {
            if (!isCurrent(snapshot)) throw WorkspaceProductChangedException()
            return block()
        }
        override fun <T> mutate(snapshot: WorkspaceProductSnapshot?, block: () -> T): T = read(snapshot ?: identity, block)
    }

    private companion object {
        fun completed() = ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
    }
}
