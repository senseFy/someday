package saien.someday.sync.selfhosted

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import okio.Path.Companion.toPath
import saien.someday.data.account.AccountNetworkBlockedException
import saien.someday.data.crypto.InMemorySecureWorkspaceKeyStore
import saien.someday.data.crypto.WorkspaceKeyRepository
import saien.someday.data.crypto.workspaceJoinPackageProvider
import saien.someday.data.crypto.workspaceRecoveryPackageProvider
import saien.someday.data.crypto.workspaceJoiner
import saien.someday.sync.WorkspaceLifecycleCoordinator
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.media.DecodedMediaAsset
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.media.MediaAssetDecodeValidator
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.settings.*
import saien.someday.domain.workspace.WorkspaceProductReadOnlyException
import saien.someday.sync.createSystemV3ClientServices

class SelfHostedAccountResetManagerTest {
    @Test fun verifiedMismatchPersistsItsGateEvenWhenTheBoundCredentialIsMissing() = fixture { f ->
        f.store.clear()
        f.services.activeWorkspaceSessionGuard.markCurrentIncarnationMismatch()
        val gate = assertNotNull(f.services.accountStateRepository.loadGate(ENDPOINT, USER, f.keys.workspaceIdOrNull()!!))
        assertTrue(gate.productReadOnly)
        assertEquals(G0, gate.expectedIncarnation)
        assertFailsWith<WorkspaceProductReadOnlyException> { f.services.notesRepository.createNotebook("missing credential") }
    }

    @Test fun missingSecureSessionCanReauthenticateFromNonSecretAccountHintWithoutRegistering() = fixture { f ->
        f.transport.loseResetResponse = true
        f.manager.submit(f.review(), PASSWORD)
        val authority = assertNotNull(f.store.load()).authorityBindingId
        f.store.clearAuthority(authority)
        f.store.clear()
        f.manager = f.newManager()
        val review = assertNotNull(f.manager.load().review)
        assertEquals(EMAIL, review.accountEmail)
        val result = f.manager.reauthenticate(review, PASSWORD)
        assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, result.snapshot.phase)
        assertEquals(0, f.transport.registrations)
        assertNull(f.store.load(), "Control-only authentication does not repopulate the content session.")
    }

    @Test fun typedControlLoginFailureRemembersProtocolForKnownAccountWithoutRegistration() = fixture { f ->
        val review = assertNotNull(f.manager.load().review)
        assertFalse(f.services.accountStateRepository.hasProtocol1(ENDPOINT, USER))
        val failed = f.manager.reauthenticate(review, "wrong")
        assertEquals(AccountDataResetIssue.WrongPassword, failed.issue)
        assertTrue(f.services.accountStateRepository.hasProtocol1(ENDPOINT, USER))
        assertEquals(0, f.transport.registrations)
    }

    @Test fun unrelatedPendingCopyCannotOfferAnotherResetOrLocalDiscard() = fixture { f ->
        f.manager.submit(f.review(), PASSWORD)
        f.driver.execute(null, "UPDATE account_reset_intents SET original_workspace_id = 'workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'", 0)
        val snapshot = f.manager.load()
        assertEquals(AccountDataResetIssue.LocalFailure, snapshot.issue)
        assertTrue(snapshot.productReadOnly)
        assertNull(snapshot.review)
        assertFalse(snapshot.resetAvailable)
        assertFalse(snapshot.canReplaceLocal)
        assertFailsWith<WorkspaceProductReadOnlyException> { f.services.notesRepository.createNotebook("unrelated") }
    }

    @Test fun missingPairOrRecoverySecretDoesNotRegisterOrChangeTheLocalDecision() = fixture { f ->
        f.manager.submit(f.review(), PASSWORD)
        for (mode in listOf(AccountDataReplacementMode.Pair, AccountDataReplacementMode.Recover)) {
            val result = f.manager.replaceLocal(f.review(), mode, true, secret = " ")
            assertEquals(AccountDataResetIssue.InvalidSecret, result.issue)
            assertEquals(0, f.transport.registrations)
            assertNull(f.services.accountStateRepository.loadIntent()?.consentTargetIncarnation)
        }
    }

    @Test fun remoteCommitRequiresSeparateConsentAndFreshReplacementKeepsWriter() = fixture { f ->
        val old = f.keys.workspaceIdOrNull()
        val oldKey = f.keys.unlockedKeyOrNull()!!.fingerprint
        val before = f.services.workspaceProductAccess.capture()
        val committed = f.manager.submit(f.review(), PASSWORD)
        assertTrue(committed.success)
        assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, committed.snapshot.phase)
        assertEquals(old, f.keys.workspaceIdOrNull())
        assertEquals(0, f.transport.registrations)
        assertEquals(1, f.transport.logins, "Only immediate ordinary login reuses the confirmation password.")
        assertFailsWith<WorkspaceProductReadOnlyException> { f.services.notesRepository.createNotebook("frozen") }
        val refused = f.manager.replaceLocal(assertNotNull(committed.snapshot.review), AccountDataReplacementMode.Fresh, false)
        assertFalse(refused.success)
        assertEquals(old, f.keys.workspaceIdOrNull())
        val done = f.manager.replaceLocal(assertNotNull(refused.snapshot.review), AccountDataReplacementMode.Fresh, true)
        assertTrue(done.success, done.issue.toString())
        assertTrue(done.localReplaced)
        assertNotEquals(old, f.keys.workspaceIdOrNull())
        assertNotEquals(oldKey, f.keys.unlockedKeyOrNull()!!.fingerprint)
        assertEquals(DEVICE, f.services.activeWorkspaceSessionGuard.currentRequirement()?.localWriterDeviceId)
        assertEquals(NEXT, f.services.activeWorkspaceSessionGuard.currentRequirement()?.accountIncarnation)
        assertNull(f.services.accountStateRepository.loadIntent())
        assertFalse(f.services.workspaceProductAccess.isCurrent(before))
        assertTrue(f.services.notesRepository.listNotebooks().isEmpty())
        f.services.notesRepository.createNotebook("new copy")
    }

    @Test fun unknownOutcomeSurvivesNewManagerAndOfflineEditingUntilReauthenticatedReceipt() = fixture { f ->
        f.transport.loseResetResponse = true
        val unknown = f.manager.submit(f.review(), PASSWORD)
        assertEquals(AccountDataResetPhase.OutcomeUnknown, unknown.snapshot.phase)
        assertTrue(f.manager.keepOffline(assertNotNull(unknown.snapshot.review)).success)
        f.services.notesRepository.createNotebook("offline survivor")
        f.manager = f.newManager()
        val reopened = f.manager.load()
        assertTrue(reopened.offlineEditing)
        assertEquals(AccountDataResetPhase.OutcomeUnknown, reopened.phase)
        assertFailsWith<AccountNetworkBlockedException> {
            f.services.accountStateRepository.requireNetworkAllowed(ENDPOINT, USER, f.keys.workspaceIdOrNull()!!)
        }
        val badPassword = f.manager.reauthenticate(assertNotNull(reopened.review), "wrong")
        assertEquals(AccountDataResetIssue.WrongPassword, badPassword.issue)
        assertEquals(AccountDataResetPhase.OutcomeUnknown, badPassword.snapshot.phase)
        val verified = f.manager.reauthenticate(assertNotNull(badPassword.snapshot.review), PASSWORD)
        assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, verified.snapshot.phase)
        assertTrue(verified.snapshot.offlineEditing)
        assertNull(f.services.accountStateRepository.loadIntent()?.consentTargetIncarnation)
        assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "offline survivor" })
        assertEquals(0, f.transport.registrations)
    }

    @Test fun localReplacementFailureRollsBackOldCopyAndRetryRequiresFreshConsent() = fixture { f ->
        f.manager.submit(f.review(), PASSWORD)
        val old = f.keys.workspaceIdOrNull()
        f.failReplacement = true
        val failure = f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Fresh, true)
        assertFalse(failure.success)
        assertEquals(old, f.keys.workspaceIdOrNull())
        assertNotNull(f.services.accountStateRepository.loadIntent())
        assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
        f.failReplacement = false
        f.manager = f.newManager()
        val ready = f.manager.refresh()
        assertFalse(f.manager.replaceLocal(assertNotNull(ready.snapshot.review), AccountDataReplacementMode.Fresh, false).success)
        val retried = f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Fresh, true, password = PASSWORD)
        assertTrue(retried.success, retried.issue.toString())
        assertNotEquals(old, f.keys.workspaceIdOrNull())
    }

    @Test fun anotherDeviceMismatchCanRejoinOnlyAfterPersistedConsentAndKeepsOldDataOnCancel() = fixture { f ->
        val old = f.keys.workspaceIdOrNull()
        f.transport.current = NEXT
        val failed = f.manager.refresh()
        assertEquals(AccountDataResetPhase.ResetRequired, failed.snapshot.phase)
        val authenticated = f.manager.reauthenticate(assertNotNull(failed.snapshot.review), PASSWORD)
        assertEquals(0, f.transport.registrations)
        f.manager.cancel()
        assertEquals(old, f.keys.workspaceIdOrNull())
        assertFalse(f.manager.replaceLocal(assertNotNull(authenticated.snapshot.review), AccountDataReplacementMode.Fresh, true).success)
        val done = f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Fresh, true, password = PASSWORD)
        assertTrue(done.success, done.issue.toString())
        assertEquals(1, f.transport.registrations)
        assertNull(f.services.accountStateRepository.loadIntent())
    }

    @Test fun lateDiscoveryFailureCannotFreezeTheWorkspaceInstalledByAnotherWorkflow() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstDiscovery = AtomicBoolean(true)
        f.transport.beforeDiscovery = {
            if (firstDiscovery.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val oldRefresh = worker.submit<AccountDataResetActionResult> { f.manager.refresh() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            f.transport.current = NEXT
            // A second workflow shares the actual lifecycle and repositories, but
            // does not share the first manager's per-instance busy flag.
            val replacing = f.newManager()
            val changed = replacing.refresh()
            val authenticated = replacing.reauthenticate(assertNotNull(changed.snapshot.review), PASSWORD)
            withTargetWorkspace(f) { workspace, _, invitation, _ ->
                val joined = replacing.replaceLocal(assertNotNull(authenticated.snapshot.review),
                    AccountDataReplacementMode.Pair, true, invitation)
                assertTrue(joined.success, joined.issue.toString())
                assertEquals(workspace, f.keys.workspaceIdOrNull())
                assertNull(f.services.accountStateRepository.loadGate(ENDPOINT, USER, workspace))
                release.countDown()
                val delayed = oldRefresh.get(10, TimeUnit.SECONDS)
                assertFalse(delayed.success)
                assertEquals(AccountDataResetIssue.ContextChanged, delayed.issue)
                assertNull(f.services.accountStateRepository.loadGate(ENDPOINT, USER, workspace),
                    "A delayed old-credential failure must not freeze the newly paired copy.")
                f.services.notesRepository.createNotebook("current workspace remains writable")
            }
        } finally {
            release.countDown()
            worker.shutdownNow()
            check(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun offlineExitDuringReplacementDiscoveryInvalidatesConsentAndPreventsRegistration() = fixture { f ->
        f.manager.submit(f.review(), PASSWORD)
        val review = f.review()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.transport.beforeDiscovery = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val replacing = worker.submit<AccountDataResetActionResult> {
                f.manager.replaceLocal(review, AccountDataReplacementMode.Fresh, true)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(f.manager.keepOffline(review).success)
            f.services.notesRepository.createNotebook("new offline edit")
            release.countDown()
            assertFalse(replacing.get(10, TimeUnit.SECONDS).success)
            assertEquals(0, f.transport.registrations)
            assertNull(f.services.accountStateRepository.loadIntent()?.consentTargetIncarnation)
            assertTrue(f.manager.load().offlineEditing)
        } finally { release.countDown(); worker.shutdownNow(); check(worker.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun lockedOldKeyStillAllowsRemoteResetAndKeepsTheLocalCopy() = fixture { f ->
        f.keys.lock()
        val review = f.review()
        val old = f.keys.workspaceIdOrNull()
        val result = f.manager.submit(review, PASSWORD)
        assertTrue(result.success, result.issue.toString())
        assertFalse(result.snapshot.canExport)
        assertEquals(old, f.keys.workspaceIdOrNull())
        assertNotNull(f.services.accountStateRepository.loadIntent())
    }

    @Test fun pairRejoinUsesTheRealKeyInstallerOnlyAfterDurableConsent() = fixture { f ->
        f.manager.submit(f.review(), PASSWORD)
        val oldWorkspace = f.keys.workspaceIdOrNull()!!
        withTargetWorkspace(f) { targetWorkspace, targetFingerprint, invitation, _ ->
            assertFalse(f.pairing.joinWithToken(invitation, true).success)
            assertEquals(0, f.transport.invitations.claimCount)
            assertEquals(oldWorkspace, f.keys.workspaceIdOrNull())
            assertFalse(f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Pair, false, invitation).success)
            val replaced = f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Pair, true, invitation)
            assertTrue(replaced.success, replaced.issue.toString())
            assertTrue(replaced.localReplaced)
            assertEquals(targetWorkspace, f.keys.workspaceIdOrNull())
            assertEquals(targetFingerprint, f.keys.unlockedKeyOrNull()?.fingerprint)
            assertEquals(DEVICE, f.services.activeWorkspaceSessionGuard.currentRequirement()?.localWriterDeviceId)
            assertEquals(NEXT, f.services.activeWorkspaceSessionGuard.currentRequirement()?.accountIncarnation)
            assertEquals(1, f.transport.invitations.claimCount)
            assertNull(f.services.accountStateRepository.loadIntent())
            assertNull(f.services.accountStateRepository.loadGate(ENDPOINT, USER, oldWorkspace))
            assertTrue(f.services.notesRepository.listNotebooks().isEmpty())
        }
    }

    @Test fun recoveryRejoinRollsBackFailedKeyInstallationAndRetriesWithNewConsent() = fixture { f ->
        f.manager.submit(f.review(), PASSWORD)
        val oldWorkspace = f.keys.workspaceIdOrNull()!!
        val oldFingerprint = f.keys.unlockedKeyOrNull()!!.fingerprint
        withTargetWorkspace(f) { targetWorkspace, targetFingerprint, _, recoveryCode ->
            assertFalse(f.recovery.recover(recoveryCode, true).success)
            assertEquals(oldWorkspace, f.keys.workspaceIdOrNull())
            f.failReplacement = true
            val failed = f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Recover, true, recoveryCode)
            assertFalse(failed.success)
            assertEquals(oldWorkspace, f.keys.workspaceIdOrNull())
            assertEquals(oldFingerprint, f.keys.unlockedKeyOrNull()?.fingerprint)
            assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
            assertNotNull(f.services.accountStateRepository.loadIntent())
            assertTrue(assertNotNull(f.services.accountStateRepository.loadGate(ENDPOINT, USER, oldWorkspace)).productReadOnly)
            f.failReplacement = false
            val review = f.review()
            assertFalse(f.manager.replaceLocal(review, AccountDataReplacementMode.Recover, false, recoveryCode).success)
            val replaced = f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Recover, true, recoveryCode)
            assertTrue(replaced.success, replaced.issue.toString())
            assertEquals(targetWorkspace, f.keys.workspaceIdOrNull())
            assertEquals(targetFingerprint, f.keys.unlockedKeyOrNull()?.fingerprint)
            assertEquals(DEVICE, f.services.activeWorkspaceSessionGuard.currentRequirement()?.localWriterDeviceId)
            assertEquals(NEXT, f.services.activeWorkspaceSessionGuard.currentRequirement()?.accountIncarnation)
            assertNull(f.services.accountStateRepository.loadIntent())
            assertNull(f.services.accountStateRepository.loadGate(ENDPOINT, USER, oldWorkspace))
        }
    }

    @Test fun startupReconstructsAMissingUnknownGateWithoutRestoringOfflinePermission() = fixture { f ->
        f.transport.loseResetResponse = true
        val unknown = f.manager.submit(f.review(), PASSWORD)
        f.manager.keepOffline(assertNotNull(unknown.snapshot.review))
        f.services.notesRepository.createNotebook("explicit offline edit")
        val pending = assertNotNull(f.services.accountStateRepository.loadIntent())
        f.database.somedayQueries.deleteMatchingAccountWorkspaceGate(ENDPOINT, USER, pending.originalWorkspaceId, G0)

        val restarted = createSystemV3ClientServices(f.local, f.settings, f.keys::unlockedKeyOrNull, f.keys::workspaceIdOrNull,
            LocalMediaAssetStore(f.database, f.root.resolve("media").toString().toPath(),
                decodeValidator = MediaAssetDecodeValidator { DecodedMediaAsset(1, 1) }),
            f.transport, f.transport, f.transport, f.store)
        val restoredGate = assertNotNull(restarted.accountStateRepository.loadGate(ENDPOINT, USER, pending.originalWorkspaceId))
        assertTrue(restoredGate.productReadOnly)
        assertNull(restoredGate.discardTargetIncarnation)
        assertEquals(pending.operationId, restarted.accountStateRepository.loadIntent()?.operationId)
        assertFailsWith<WorkspaceProductReadOnlyException> { restarted.notesRepository.createNotebook("must remain frozen") }
        assertTrue(f.manager.load().productReadOnly, "The UI and product admission must observe the same repaired gate.")
        assertTrue(restarted.notesRepository.listNotebooks().any { it.title == "explicit offline edit" })
        assertFailsWith<AccountNetworkBlockedException> {
            restarted.accountStateRepository.requireNetworkAllowed(ENDPOINT, USER, pending.originalWorkspaceId)
        }
    }

    @Test fun knownNewIncarnationCredentialsFreezeWritesBeforeAnyResetScreenIsLoaded() = fixture { f ->
        val workspace = f.keys.workspaceIdOrNull()!!
        assertNull(f.services.accountStateRepository.loadGate(ENDPOINT, USER, workspace))
        val newCredentials = f.store.load()!!.copy(accessToken = NEXT, accountIncarnation = NEXT)
        f.store.save(newCredentials)
        f.store.saveForAuthority(newCredentials.authorityBindingId, newCredentials)
        assertFailsWith<WorkspaceProductReadOnlyException> { f.services.notesRepository.createNotebook("late old-copy edit") }
        assertTrue(assertNotNull(f.services.accountStateRepository.loadGate(ENDPOINT, USER, workspace)).productReadOnly)
        assertEquals(listOf("original"), f.services.notesRepository.listNotebooks().map { it.title })
        assertEquals(0, f.transport.logins)
        assertEquals(0, f.transport.registrations)

        // A deliberate offline choice belongs to this exact old copy and remains valid on later admissions.
        f.services.accountStateRepository.enterOfflineMode(ENDPOINT, USER, workspace)
        f.services.notesRepository.createNotebook("allowed offline edit")
        assertFalse(assertNotNull(f.services.accountStateRepository.loadGate(ENDPOINT, USER, workspace)).productReadOnly)
        assertFailsWith<AccountNetworkBlockedException> {
            f.services.accountStateRepository.requireNetworkAllowed(ENDPOINT, USER, workspace)
        }
    }

    @Test fun startupDoesNotTrustDiscardConsentWhenACommittedIntentLostItsGate() = fixture { f ->
        f.manager.submit(f.review(), PASSWORD)
        val pending = assertNotNull(f.services.accountStateRepository.loadIntent())
        f.services.accountStateRepository.recordLocalDiscardConsent(pending.operationId, pending.originalWorkspaceId, NEXT)
        f.database.somedayQueries.deleteMatchingAccountWorkspaceGate(ENDPOINT, USER, pending.originalWorkspaceId, G0)
        val restarted = createSystemV3ClientServices(f.local, f.settings, f.keys::unlockedKeyOrNull, f.keys::workspaceIdOrNull,
            LocalMediaAssetStore(f.database, f.root.resolve("media").toString().toPath(),
                decodeValidator = MediaAssetDecodeValidator { DecodedMediaAsset(1, 1) }),
            f.transport, f.transport, f.transport, f.store)
        val restored = assertNotNull(restarted.accountStateRepository.loadIntent())
        assertEquals(NEXT, restored.receiptIncarnation)
        assertNull(restored.consentTargetIncarnation)
        assertTrue(assertNotNull(restarted.accountStateRepository.loadGate(ENDPOINT, USER, pending.originalWorkspaceId)).productReadOnly)
        assertFalse(restarted.accountStateRepository.completeLocalReconciliation(pending.operationId, NEXT))
        assertFailsWith<WorkspaceProductReadOnlyException> { restarted.notesRepository.createNotebook("stale consent") }
    }

    private fun withTargetWorkspace(fixture: Fixture, block: (String, String, String, String) -> Unit) {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val local = SqlDelightLocalDataRepository(SomedayDatabase(driver), "44444444-4444-4444-8444-444444444444")
            val keys = WorkspaceKeyRepository(local, InMemorySecureWorkspaceKeyStore())
            keys.createFirstRunWorkspace("Published target", "desktop")
            val workspace = keys.workspaceIdOrNull()!!
            val fingerprint = keys.unlockedKeyOrNull()!!.fingerprint
            val credentials = fixture.store.load()!!.copy(deviceId = local.localDeviceId, accessToken = NEXT, accountIncarnation = NEXT)
            val session = MemorySessionStore(credentials)
            val lifecycle = WorkspaceLifecycleCoordinator()
            val guard = ActiveWorkspaceSessionGuard {
                ActiveWorkspaceSessionRequirement(credentials.authorityBindingId, credentials.deviceId, workspace, NEXT)
            }
            val settings = { ClientSettings(activeDeviceId = credentials.deviceId,
                syncConfiguration = SyncConfiguration(mode = SyncMode.SelfHosted, selfHostedEndpoint = ENDPOINT,
                    selfHostedSession = credentials.toSummary())) }
            val executor = RefreshingSelfHostedSessionExecutor(fixture.transport, session)
            val unusedJoiner = WorkspaceJoiner { _, _ -> error("Target fixture only publishes wrapped keys") }
            val pairing = SelfHostedWorkspacePairingService(settings, session, fixture.transport, executor,
                keys.workspaceJoinPackageProvider(), unusedJoiner, lifecycle, guard, { true })
            val recovery = SelfHostedWorkspaceRecoveryService(settings, session, fixture.transport, executor,
                keys.workspaceRecoveryPackageProvider(), unusedJoiner, lifecycle, guard, { true }, { fingerprint })
            val invitation = assertNotNull(pairing.createInvitation().invitation).revealManualToken()
            val recoveryCode = assertNotNull(recovery.prepareCode().recoveryCode).revealForUserConfirmation()
            assertTrue(recovery.confirmPreparedCode(recoveryCode).success)
            block(workspace, fingerprint, invitation, recoveryCode)
        } finally { driver.close() }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.transport.close(); f.driver.close(); f.root.toFile().deleteRecursively() }
    }

    private class Fixture {
        val root = Files.createTempDirectory("someday-reset-workflow-")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${root.resolve("local.db")}")
        val database = SomedayDatabase(driver)
        val local = SqlDelightLocalDataRepository(database, DEVICE)
        val settings = SqlDelightClientSettingsRepository(local)
        val keys = WorkspaceKeyRepository(local, InMemorySecureWorkspaceKeyStore())
        val store = MemorySessionStore(SelfHostedSessionCredentials(ENDPOINT, USER, EMAIL, DEVICE, "Test", "desktop", G0, "refresh", G0, 1))
        val transport = WorkflowTransport()
        var failReplacement = false
        init {
            settings.saveLocalSnapshot(ClientSettings(activeDeviceId = DEVICE,
                syncConfiguration = SyncConfiguration(mode = SyncMode.SelfHosted, selfHostedEndpoint = ENDPOINT,
                    selfHostedSession = store.load()!!.toSummary())))
            keys.createFirstRunWorkspace("Test", "desktop")
        }
        val services = createSystemV3ClientServices(local, settings, keys::unlockedKeyOrNull, keys::workspaceIdOrNull,
            LocalMediaAssetStore(database, root.resolve("media").toString().toPath(), decodeValidator = MediaAssetDecodeValidator { DecodedMediaAsset(1, 1) }),
            transport, transport, transport, store)
        init {
            services.workspaceLifecycleCoordinator.productAccess {
                check(services.bindFreshWorkspaceAfterAccountReset(keys.unlockedKeyOrNull()!!, keys.workspaceIdOrNull()!!,
                    WorkspaceJoinAuthorityCapture(store.load()!!.authorityBindingId, DEVICE, G0)))
            }
            services.notesRepository.createNotebook("original")
        }
        private val joiner = keys.workspaceJoiner("Test", "desktop",
            services.discardLocalWorkspaceForReplacement,
            { packageData, key, workspace ->
                check(services.bindReplacementWorkspaceToCurrentSession(packageData, key, workspace))
                check(!failReplacement) { "Synthetic local commit failure" }
                true
            }, services.finalizeLocalWorkspaceReplacement)
        val pairing = SelfHostedWorkspacePairingService(settings::load, store, transport,
            services.selfHostedSessionExecutor, keys.workspaceJoinPackageProvider(), joiner,
            services.workspaceLifecycleCoordinator, services.activeWorkspaceSessionGuard,
            services.workspacePairingInviterReady)
        val recovery = SelfHostedWorkspaceRecoveryService(settings::load, store, transport,
            services.selfHostedSessionExecutor, keys.workspaceRecoveryPackageProvider(), joiner,
            services.workspaceLifecycleCoordinator, services.activeWorkspaceSessionGuard,
            services.workspacePairingInviterReady, { keys.unlockedKeyOrNull()?.fingerprint })
        var manager = newManager()
        fun newManager() = SelfHostedAccountResetManager(services, store, transport, settings, keys::workspaceIdOrNull,
            { DEVICE }, "Test", "desktop", { before, after ->
                keys.replaceWithFreshWorkspace("Test", "desktop", before) { key, workspace ->
                    after(key, workspace)
                    check(!failReplacement) { "Synthetic local commit failure" }
                }
            }, pairing, recovery, canExportProvider = { keys.unlockedKeyOrNull() != null })
        fun review(): AccountDataResetReview {
            val refreshed = manager.refresh()
            return assertNotNull(refreshed.snapshot.review)
        }
    }

    private class WorkflowTransport(private val delegate: JdkSelfHostedSyncTransport = JdkSelfHostedSyncTransport()) :
        SelfHostedSyncTransport by delegate, SelfHostedSyncTransportV2 by delegate, SelfHostedMediaTransportV3 by delegate,
        SelfHostedAccountControlTransport, SelfHostedWorkspaceRecoveryTransport {
        val invitations = MemorySelfHostedPairingTransport()
        private var recoveryEnvelope: SelfHostedWorkspaceRecoveryEnvelopeResponse? = null
        var current = G0
        var registrations = 0
        var logins = 0
        var loseResetResponse = false
        var committed: SelfHostedAccountResetReceiptResponse? = null
        var beforeDiscovery: () -> Unit = {}
        fun close() = delegate.close()
        override fun login(endpoint: String, request: SelfHostedAuthRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedAuthTokensResponse {
            logins++
            if (request.password != PASSWORD) throw SelfHostedSyncHttpException(401, "Rejected", SelfHostedErrorCode.INVALID_CREDENTIALS, true)
            return SelfHostedAuthTokensResponse(current, "refresh", 60, SelfHostedUserResponse(USER, EMAIL), current, 1)
        }
        override fun registerDevice(endpoint: String, accessToken: String, request: SelfHostedDeviceRegistrationRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedDeviceRegistrationResponse {
            requireCurrent(accessToken)
            check(accountContext.accountIncarnation == current && request.deviceId == DEVICE)
            registrations++
            return SelfHostedDeviceRegistrationResponse(SelfHostedDeviceResponse(DEVICE, "Test", "desktop", false), current, "refresh", 60, current, 1)
        }
        override fun discoverAccountData(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountDiscoveryResult {
            beforeDiscovery()
            requireCurrent(accessToken)
            return SelfHostedAccountDiscoveryResult.Protocol1(SelfHostedAccountDataStateResponse(1, current, current == G0,
                if (current == G0) null else "retired_media_pending"))
        }
        override fun accountMe(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext) = SelfHostedAccountMeResponse(USER, EMAIL, null, listOf("auth"))
        override fun getAccountResetReceipt(endpoint: String, accessToken: String, operationId: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountResetReceiptResponse? {
            requireCurrent(accessToken)
            return committed?.takeIf { it.operationId == operationId }
        }
        override fun resetAccountData(endpoint: String, accessToken: String, request: SelfHostedAccountResetRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountResetReceiptResponse {
            requireCurrent(accessToken)
            check(request.expectedIncarnation == current)
            val receipt = SelfHostedAccountResetReceiptResponse(1, request.operationId, current, NEXT, 123)
            current = NEXT
            committed = receipt
            if (loseResetResponse) throw IOException("Synthetic response loss")
            return receipt
        }
        override fun createPairingInvite(endpoint: String, accessToken: String, inviteId: String,
            request: SelfHostedPairingInviteCreateRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedPairingInviteCreateResponse {
            requireCurrent(accessToken)
            check(accountContext.accountIncarnation == current)
            return invitations.createPairingInvite(endpoint, accessToken, inviteId, request, accountContext)
        }
        override fun claimPairingInvite(endpoint: String, accessToken: String, inviteId: String,
            request: SelfHostedPairingInviteClaimRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedPairingInviteClaimResponse {
            requireCurrent(accessToken)
            check(accountContext.accountIncarnation == current)
            return invitations.claimPairingInvite(endpoint, accessToken, inviteId, request, accountContext)
        }
        override fun completePairingInvite(endpoint: String, accessToken: String, inviteId: String,
            request: SelfHostedPairingInviteCompleteRequest, accountContext: SelfHostedAccountRequestContext) {
            requireCurrent(accessToken)
            invitations.completePairingInvite(endpoint, accessToken, inviteId, request, accountContext)
        }
        override fun getWorkspaceRecoveryEnvelope(endpoint: String, accessToken: String,
            accountContext: SelfHostedAccountRequestContext): SelfHostedWorkspaceRecoveryEnvelopeResponse? {
            requireCurrent(accessToken)
            check(accountContext.accountIncarnation == current)
            return recoveryEnvelope
        }
        override fun putWorkspaceRecoveryEnvelope(endpoint: String, accessToken: String,
            request: SelfHostedWorkspaceRecoveryEnvelopePutRequest,
            accountContext: SelfHostedAccountRequestContext): SelfHostedWorkspaceRecoveryEnvelopeResponse {
            requireCurrent(accessToken)
            check(accountContext.accountIncarnation == current)
            check(request.expectedRevision == recoveryEnvelope?.revision)
            return SelfHostedWorkspaceRecoveryEnvelopeResponse(request.workspaceId, request.keyFingerprint,
                request.envelopeJson, request.envelopeDigest, (recoveryEnvelope?.revision ?: 0) + 1, 1234)
                .also { recoveryEnvelope = it }
        }
        private fun requireCurrent(token: String) {
            if (token != current) throw SelfHostedSyncHttpException(401, "Stale", SelfHostedErrorCode.ACCOUNT_SESSION_STALE, true)
        }
    }

    private companion object {
        const val G0 = INITIAL_ACCOUNT_INCARNATION
        const val ENDPOINT = "https://reset.example.invalid"
        const val USER = "11111111-1111-4111-8111-111111111111"
        const val DEVICE = "22222222-2222-4222-8222-222222222222"
        const val NEXT = "33333333-3333-4333-8333-333333333333"
        const val EMAIL = "synthetic@example.invalid"
        const val PASSWORD = "synthetic-password"
    }
}
