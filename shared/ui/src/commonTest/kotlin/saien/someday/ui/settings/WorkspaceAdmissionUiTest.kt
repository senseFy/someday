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
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.AccountDataResetManager
import saien.someday.domain.settings.AccountDataResetPhase
import saien.someday.domain.settings.AccountDataResetSnapshot
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.UnavailableAccountDataResetManager
import saien.someday.domain.settings.UnavailableWorkspaceRecoveryManager
import saien.someday.domain.settings.WorkspaceAdmissionManager
import saien.someday.domain.settings.WorkspaceAdmissionState
import saien.someday.domain.settings.WorkspaceAdmissionStatus
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.domain.settings.WorkspacePairingReason
import saien.someday.domain.settings.WorkspaceRecoveryManager
import saien.someday.domain.settings.WorkspaceRecoveryReason
import saien.someday.domain.settings.WorkspaceRecoveryRestoreResult
import saien.someday.domain.settings.WorkspaceRecoveryState
import saien.someday.domain.settings.WorkspaceRecoveryStatusResult
import saien.someday.domain.settings.WorkspaceRecoverySyncGate
import saien.someday.domain.workspace.WorkspaceProductAccess
import saien.someday.domain.workspace.WorkspaceProductChangedException
import saien.someday.domain.workspace.WorkspaceProductSnapshot

class WorkspaceAdmissionUiTest {
    @Test
    fun existingWorkspaceRequiresJoiningEvenWithoutRecoveryCode() = runBlocking {
        val fixture = Fixture(WorkspaceAdmissionState.JoinRequired)
        fixture.controller.refresh()

        assertFalse(fixture.controller.canRunAutomaticSync())
        assertFalse(fixture.controller.runAutomaticSync())
        assertFalse(fixture.controller.runUserSync())
        assertFalse(fixture.controller.startFirstWorkspaceSync())
        assertTrue(fixture.controller.state.sync.pairingAvailable)
        assertFalse(fixture.controller.state.sync.admission.recoveryAvailable)
        assertEquals(0, fixture.syncCalls)
        assertEquals(WorkspaceAdmissionState.JoinRequired, fixture.controller.state.sync.admission.state)
    }

    @Test
    fun firstWorkspaceRequiresTheDedicatedStartActionAndThenAllowsNormalSync() = runBlocking {
        val fixture = Fixture(WorkspaceAdmissionState.FirstWorkspace)
        fixture.controller.refresh()

        assertFalse(fixture.controller.canRunAutomaticSync())
        assertFalse(fixture.controller.runAutomaticSync())
        assertFalse(fixture.controller.runUserSync())
        assertEquals(0, fixture.syncCalls)
        assertTrue(fixture.controller.startFirstWorkspaceSync())
        assertEquals(1, fixture.syncCalls)
        assertEquals(WorkspaceAdmissionState.Ready, fixture.controller.state.sync.admission.state)
        assertTrue(fixture.controller.canRunAutomaticSync())
        assertTrue(fixture.controller.runUserSync())
    }

    @Test
    fun anotherDevicePublishingBeforeStartReturnsToJoinFlowWithoutRunningSync() = runBlocking {
        val fixture = Fixture(WorkspaceAdmissionState.FirstWorkspace)
        fixture.controller.refresh()
        fixture.status = WorkspaceAdmissionStatus(WorkspaceAdmissionState.JoinRequired, 1)

        assertFalse(fixture.controller.startFirstWorkspaceSync())

        assertEquals(0, fixture.syncCalls)
        assertTrue(fixture.controller.state.sync.joiningWorkspace)
    }

    @Test
    fun unavailableCheckPreservesLocalStateAndCanBeRetried() = runBlocking {
        val fixture = Fixture(WorkspaceAdmissionState.Unavailable)
        fixture.controller.refresh()
        val settings = fixture.controller.state.settings

        assertFalse(fixture.controller.runUserSync())
        assertFalse(fixture.controller.startFirstWorkspaceSync())
        assertEquals(0, fixture.syncCalls)
        assertEquals(settings, fixture.controller.state.settings)
        fixture.status = WorkspaceAdmissionStatus(WorkspaceAdmissionState.Ready, 2)
        assertTrue(fixture.controller.retryWorkspaceAdmission())
        assertEquals(2, fixture.controller.state.sync.admission.initializedWorkspaceCount)
        assertTrue(fixture.controller.runAutomaticSync())
    }

    @Test
    fun restartRestoresAdmissionFailureAndFreshStatusClearsOnlyThatIssue() = runBlocking {
        for (reason in listOf(ManualSyncReason.WorkspaceJoinRequired, ManualSyncReason.WorkspaceAdmissionUnavailable)) {
            val fixture = Fixture(WorkspaceAdmissionState.Ready, reason)
            assertFalse(fixture.controller.canRunAutomaticSync())
            assertFalse(fixture.controller.state.sync.workspaceReady)
            fixture.controller.refresh()
            assertNull(fixture.controller.state.sync.issue)
            assertTrue(fixture.controller.canRunAutomaticSync())
        }
    }

    @Test
    fun verifiedFirstPublicationAttemptAfterRestartKeepsOrdinaryRetryAndAutomaticSync() = runBlocking {
        // Admission has verified a prior publication attempt and that the server is still empty.
        // A mere account login or provisional binding never produces this Ready status.
        val fixture = Fixture(WorkspaceAdmissionState.Ready, ManualSyncReason.Failed)
        fixture.status = WorkspaceAdmissionStatus(WorkspaceAdmissionState.Ready, 0)
        fixture.controller.refresh()

        assertEquals(SyncIssueAction.RetrySync, fixture.controller.state.sync.issue?.action)
        assertTrue(fixture.controller.canRunAutomaticSync())
        assertTrue(fixture.controller.recoverSyncIssue())
        assertEquals(1, fixture.syncCalls)
        assertNull(fixture.controller.state.sync.issue)
        assertTrue(fixture.controller.runAutomaticSync())
        assertEquals(2, fixture.syncCalls)
    }

    @Test
    fun pairingStillPassesExplicitReplacementConsentAndRefreshesAdmissionAfterJoining() = runBlocking {
        val fixture = Fixture(WorkspaceAdmissionState.JoinRequired)
        fixture.controller.refresh()

        assertFalse(fixture.controller.joinWorkspaceWithToken("token", replaceExistingWorkspace = false))
        assertEquals(listOf(false), fixture.replacementConfirmations)
        assertEquals(0, fixture.syncCalls)
        assertTrue(fixture.controller.joinWorkspaceWithToken("token", replaceExistingWorkspace = true))
        assertEquals(listOf(false, true), fixture.replacementConfirmations)
        assertEquals(1, fixture.syncCalls)
        assertTrue(fixture.controller.state.sync.workspaceReady)
    }

    @Test
    fun pairingSettingsReadFailureCanRetryAgainstTheJoinedWorkspace() = runBlocking {
        val fixture = committedReplacementWithSettingsReadFailure(useRecovery = false)

        assertTrue(fixture.controller.recoverSyncIssue())

        assertEquals(1, fixture.syncCalls)
        assertTrue(fixture.controller.state.sync.workspaceReady)
        assertNull(fixture.controller.state.sync.issue)
        assertEquals(listOf("joined-workspace"), fixture.persistedWorkspaces)
    }

    @Test
    fun recoverySettingsReadFailureCanRetryAgainstTheJoinedWorkspace() = runBlocking {
        val fixture = committedReplacementWithSettingsReadFailure(useRecovery = true)

        assertTrue(fixture.controller.recoverSyncIssue())

        assertEquals(1, fixture.syncCalls)
        assertTrue(fixture.controller.state.sync.workspaceReady)
        assertNull(fixture.controller.state.sync.issue)
        assertEquals(listOf("joined-workspace"), fixture.persistedWorkspaces)
    }

    @Test
    fun replacementSettingsRetryStillRejectsASubsequentWorkspaceChange() = runBlocking {
        val fixture = committedReplacementWithSettingsReadFailure(useRecovery = false)
        fixture.identity = fixture.identity.copy(workspaceId = "another-workspace", localRevision = 2)

        assertFailsWith<CancellationException> { fixture.controller.recoverSyncIssue() }

        assertEquals(0, fixture.syncCalls)
        assertTrue(fixture.persistedWorkspaces.isEmpty())
    }

    private suspend fun committedReplacementWithSettingsReadFailure(useRecovery: Boolean): Fixture {
        val fixture = Fixture(WorkspaceAdmissionState.JoinRequired)
        if (useRecovery) {
            fixture.status = WorkspaceAdmissionStatus(WorkspaceAdmissionState.JoinRequired, 1, recoveryAvailable = true)
            fixture.recoveryStatus = WorkspaceRecoveryStatusResult.ready(WorkspaceRecoveryState.RecoveryAvailable,
                WorkspaceRecoverySyncGate.RecoveryRequired, WorkspaceRecoveryReason.RecoveryAvailable)
        }
        fixture.controller.refresh()
        fixture.persistedWorkspaces.clear()
        fixture.replacementSideEffect = {
            fixture.identity = fixture.identity.copy(workspaceId = "joined-workspace", authorityBindingId = "joined-authority", localRevision = 1)
            fixture.failNextSettingsRead = true
            fixture.recoveryStatus = WorkspaceRecoveryStatusResult.ready(WorkspaceRecoveryState.Configured,
                WorkspaceRecoverySyncGate.Allowed, WorkspaceRecoveryReason.Configured)
        }

        val joined = if (useRecovery) {
            fixture.controller.recoverWorkspaceWithCode("recovery-code", replaceExistingWorkspace = true)
        } else {
            fixture.controller.joinWorkspaceWithToken("token", replaceExistingWorkspace = true)
        }

        assertTrue(joined)
        assertFalse(fixture.failNextSettingsRead)
        assertEquals(0, fixture.syncCalls)
        assertEquals(SyncIssueReason.WorkspaceSettingsReloadRequired, fixture.controller.state.sync.issue?.reason)
        assertTrue(fixture.controller.state.sync.joiningWorkspace)
        assertTrue(fixture.persistedWorkspaces.isEmpty())
        return fixture
    }

    @Test
    fun accountResetDiscoveredDuringAdmissionImmediatelyReplacesWorkspaceOnboarding() = runBlocking {
        val fixture = Fixture(WorkspaceAdmissionState.Ready)
        fixture.controller.refresh()
        fixture.admissionSideEffect = {
            fixture.resetSnapshot = AccountDataResetSnapshot(AccountDataResetPhase.ResetRequired, productReadOnly = true)
            fixture.status = WorkspaceAdmissionStatus(WorkspaceAdmissionState.Unavailable)
        }

        assertFalse(fixture.controller.runUserSync())

        assertTrue(fixture.controller.state.sync.needsAccountDataResolution)
        assertEquals(AccountDataResetPhase.ResetRequired, fixture.controller.state.sync.accountReset.snapshot?.phase)
        assertFalse(fixture.controller.state.sync.pairingAvailable)
        assertEquals(0, fixture.syncCalls)
    }

    @Test
    fun rejectedFirstPublicationRefreshesPreviouslyMissingRecovery() = runBlocking {
        val fixture = Fixture(WorkspaceAdmissionState.FirstWorkspace)
        fixture.controller.refresh()
        assertEquals(WorkspaceRecoveryUiAvailability.NotConfigured, fixture.controller.state.sync.recovery.availability)
        fixture.syncOverride = {
            fixture.status = WorkspaceAdmissionStatus(WorkspaceAdmissionState.JoinRequired, 1, recoveryAvailable = true)
            fixture.recoveryStatus = WorkspaceRecoveryStatusResult.ready(WorkspaceRecoveryState.RecoveryAvailable,
                WorkspaceRecoverySyncGate.RecoveryRequired, WorkspaceRecoveryReason.RecoveryAvailable)
            ManualSyncResult.failure(SyncMode.SelfHosted, ManualSyncReason.WorkspaceJoinRequired)
        }

        assertFalse(fixture.controller.startFirstWorkspaceSync())

        assertTrue(fixture.controller.state.sync.joiningWorkspace)
        assertEquals(WorkspaceRecoveryUiAvailability.RecoveryAvailable, fixture.controller.state.sync.recovery.availability)
        assertTrue(fixture.controller.state.sync.recovery.blocksSync)
        assertEquals(1, fixture.syncCalls)
    }

    private class Fixture(initialState: WorkspaceAdmissionState, lastFailure: ManualSyncReason? = null) {
        var status = WorkspaceAdmissionStatus(initialState)
        var admissionSideEffect: () -> Unit = {}
        var resetSnapshot = AccountDataResetSnapshot()
        var recoveryStatus = WorkspaceRecoveryStatusResult.ready(WorkspaceRecoveryState.NotConfigured,
            WorkspaceRecoverySyncGate.Allowed, WorkspaceRecoveryReason.NotConfigured)
        var syncCalls = 0
        var syncOverride: (() -> ManualSyncResult)? = null
        var identity = WorkspaceProductSnapshot("draft-workspace", "account", null, "device")
        var failNextSettingsRead = false
        var replacementSideEffect: () -> Unit = {}
        val persistedWorkspaces = mutableListOf<String>()
        val access = object : WorkspaceProductAccess {
            override fun capture() = identity
            override fun isCurrent(snapshot: WorkspaceProductSnapshot) = snapshot == identity
            override fun <T> read(snapshot: WorkspaceProductSnapshot, block: () -> T): T {
                if (!isCurrent(snapshot)) throw WorkspaceProductChangedException()
                return block()
            }
            override fun <T> mutate(snapshot: WorkspaceProductSnapshot?, block: () -> T): T = read(snapshot ?: identity, block)
        }
        val replacementConfirmations = mutableListOf<Boolean>()
        var stored = ClientSettings(
            activeDeviceId = "device",
            syncConfiguration = SyncConfiguration(
                mode = SyncMode.SelfHosted,
                selfHostedEndpoint = "https://sync.example.test",
                selfHostedSession = SelfHostedSessionSummary(true, "owner@example.test", "device", "Test device", "desktop"),
                lastError = lastFailure?.let { "sync:${it.name}" },
            ),
        )
        val controller = SettingsUiController(
            initialSettings = stored,
            loadSettings = {
                if (failNextSettingsRead) {
                    failNextSettingsRead = false
                    error("The replacement settings could not be read")
                }
                stored
            },
            persistSettings = { persistedWorkspaces += identity.workspaceId; stored = it; it },
            workspaceProductAccess = access,
            workspaceAdmissionManager = WorkspaceAdmissionManager { admissionSideEffect(); status },
            accountDataResetManager = object : AccountDataResetManager by UnavailableAccountDataResetManager {
                override fun load() = resetSnapshot
            },
            workspaceRecoveryManager = object : WorkspaceRecoveryManager by UnavailableWorkspaceRecoveryManager {
                override fun status() = recoveryStatus
                override fun recover(recoveryCode: String, replaceExistingWorkspace: Boolean): WorkspaceRecoveryRestoreResult {
                    if (!replaceExistingWorkspace) return WorkspaceRecoveryRestoreResult.failure(WorkspaceRecoveryReason.ReplacementConfirmationRequired)
                    replacementSideEffect()
                    status = WorkspaceAdmissionStatus(WorkspaceAdmissionState.Ready, 1)
                    return WorkspaceRecoveryRestoreResult.recovered()
                }
            },
            automaticSyncEligible = { true },
            manualSyncRunner = {
                syncCalls++
                syncOverride?.invoke() ?: run {
                    status = WorkspaceAdmissionStatus(WorkspaceAdmissionState.Ready, status.initializedWorkspaceCount ?: 1)
                    ManualSyncResult.success(SyncMode.SelfHosted, 0, 0, 0)
                }
            },
            workspacePairingInvitationJoiner = WorkspacePairingInvitationJoiner { _, confirmed ->
                replacementConfirmations += confirmed
                if (confirmed) {
                    replacementSideEffect()
                    status = WorkspaceAdmissionStatus(WorkspaceAdmissionState.Ready, 1)
                    WorkspaceJoinResult.success(WorkspacePairingReason.Joined)
                } else {
                    WorkspaceJoinResult.failure(WorkspacePairingReason.ReplacementConfirmationRequired)
                }
            },
            selfHostedSessionCredentialStore = object : SelfHostedSessionCredentialStore {
                override fun load() = SelfHostedSessionCredentials("https://sync.example.test", "user", "owner@example.test",
                    "device", "Test device", "desktop", "synthetic-access", "synthetic-refresh")
                override fun save(credentials: SelfHostedSessionCredentials) = Unit
                override fun clear() = Unit
            },
            backgroundDispatcher = Dispatchers.Unconfined,
        )
    }
}
