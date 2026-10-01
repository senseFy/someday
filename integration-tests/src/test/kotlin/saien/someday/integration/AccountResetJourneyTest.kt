@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.integration

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.buffer
import saien.someday.data.account.AccountNetworkBlockedException
import saien.someday.data.media.MediaAssetImportRequest
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.settings.AccountDataReplacementMode
import saien.someday.domain.settings.AccountDataResetIssue
import saien.someday.domain.settings.AccountDataResetPhase
import saien.someday.domain.settings.INITIAL_ACCOUNT_INCARNATION
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceAdmissionState
import saien.someday.domain.workspace.WorkspaceProductReadOnlyException
import saien.someday.integration.testkit.AccountResetInstallation
import saien.someday.integration.testkit.AccountResetJourneyTransport
import saien.someday.integration.testkit.TestAccount
import saien.someday.integration.testkit.accountResetServerState
import saien.someday.integration.testkit.requiredEnvironment
import saien.someday.sync.selfhosted.SELF_HOSTED_ERROR_CODE_HEADER
import saien.someday.sync.selfhosted.SelfHostedErrorCode
import saien.someday.sync.selfhosted.SelfHostedSyncHttpException
import saien.someday.sync.selfhosted.accountRequestContext
import saien.someday.ui.i18n.AccountResetUiStrings
import saien.someday.ui.settings.SettingsUiController
import saien.someday.ui.settings.SyncConnectionUi
import saien.someday.ui.settings.WorkspaceRecoveryUiAvailability

/** Requires an isolated real server with SOMEDAY_ACCOUNT_RESET_ENABLED=true. No mocked responses. */
class AccountResetJourneyTest {
    @Test
    fun restartedControllersFinishFreshPairAndRecoveryWithoutCredentialsHintsOrForegroundRefresh() = runBlocking {
        val endpoint = requiredEnvironment("SOMEDAY_E2E_ENDPOINT")
        val uri = URI.create(endpoint)
        require(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]") && uri.port > 0) {
            "The destructive controller journey requires an explicit loopback HTTP test server."
        }
        val account = account("reset-controller")
        AccountResetInstallation(endpoint, account, AccountResetJourneyTransport.jdk(), "controller-leader").use { leader ->
            AccountResetInstallation(endpoint, account, AccountResetJourneyTransport.ktor(), "controller-pair").use { pairPeer ->
                AccountResetInstallation(endpoint, account, AccountResetJourneyTransport.jdk(), "controller-recover").use { recoveryPeer ->
                    leader.process.connect(true)
                    pairPeer.process.connect(false)
                    recoveryPeer.process.connect(false)
                    val oldContent = publishTextAndImage(leader, "before controller reset")
                    for (peer in listOf(pairPeer, recoveryPeer)) {
                        join(peer, leader)
                        sync(peer)
                        assertTextAndImage(peer, oldContent)
                    }
                    val oldWorkspace = assertNotNull(leader.process.keys.workspaceIdOrNull())
                    val oldRecovery = assertNotNull(leader.process.recovery.prepareCode().recoveryCode)
                        .revealForUserConfirmation()
                    assertTrue(leader.process.recovery.confirmPreparedCode(oldRecovery).success)

                    val originalController = leader.process.settingsController()
                    originalController.refresh()
                    assertTrue(originalController.refreshAccountReset())
                    assertTrue(originalController.state.sync.accountReset.snapshot?.resetAvailable == true)
                    assertTrue(originalController.submitAccountReset(
                        assertNotNull(originalController.state.sync.accountReset.snapshot?.review),
                        account.password,
                        AccountResetUiStrings().confirmationPhrase,
                    ))
                    assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending,
                        originalController.state.sync.accountReset.snapshot?.phase)
                    for (peer in listOf(pairPeer, recoveryPeer)) {
                        assertFalse(peer.process.services.manualSyncRunner.run().success)
                        assertEquals(AccountDataResetPhase.ResetRequired, peer.process.reset.load().phase)
                    }
                    for (device in listOf(leader, pairPeer, recoveryPeer)) {
                        device.loseCredentialsAndConnectionHintsThenRestart()
                        assertNull(device.sessionStore.load())
                        val settings = device.process.services.settingsRepository.load().syncConfiguration
                        assertEquals(SyncMode.Off, settings.mode)
                        assertFalse(settings.selfHostedSession.loggedIn)
                        assertTrue(settings.selfHostedSession.userEmail.isNullOrBlank())
                        assertTrue(settings.selfHostedEndpoint.isNullOrBlank())
                    }

                    // There is intentionally no controller.refresh(), foreground event, or
                    // manual sync after reopening. Each replacement must finish its own setup.
                    val leaderController = authenticateBlockedController(leader, oldContent)
                    assertTrue(leaderController.replaceAccountWorkspace(
                        assertNotNull(leaderController.state.sync.accountReset.snapshot?.review),
                        AccountDataReplacementMode.Fresh,
                        discardConfirmed = true,
                    ), leaderController.state.sync.accountReset.snapshot?.issue?.name)
                    assertCompletedControllerReplacement(leader, leaderController, oldContent)
                    assertNotEquals(oldWorkspace, leader.process.keys.workspaceIdOrNull())
                    assertEquals(1, leader.process.controllerSyncResults.size, "Fresh replacement itself performs its first sync.")
                    assertEquals(WorkspaceRecoveryUiAvailability.NotConfigured, leaderController.state.sync.recovery.availability)

                    assertTrue(leaderController.prepareWorkspaceRecoveryCode(), leaderController.state.feedbackMessage)
                    val newRecovery = assertNotNull(leaderController.state.sync.recovery.preparedCode).value
                    assertFalse(newRecovery == oldRecovery, "A fresh workspace must have a new recovery code.")
                    assertTrue(leaderController.confirmWorkspaceRecoveryCode(newRecovery), leaderController.state.feedbackMessage)
                    assertEquals(WorkspaceRecoveryUiAvailability.Configured, leaderController.state.sync.recovery.availability)
                    val newContent = publishTextAndImageAutomatically(leader, leaderController, "after controller reset")
                    assertTrue(leaderController.createWorkspacePairingInvitation(), leaderController.state.feedbackMessage)
                    val invitation = assertNotNull(leaderController.state.sync.invitation).manualToken

                    for ((peer, mode, secret) in listOf(
                        Triple(pairPeer, AccountDataReplacementMode.Pair, invitation),
                        Triple(recoveryPeer, AccountDataReplacementMode.Recover, newRecovery),
                    )) {
                        val controller = authenticateBlockedController(peer, oldContent)
                        val registrations = peer.transport.registrations
                        assertFalse(controller.replaceAccountWorkspace(
                            assertNotNull(controller.state.sync.accountReset.snapshot?.review), mode,
                            discardConfirmed = false, secret = secret,
                        ))
                        assertEquals(registrations, peer.transport.registrations)
                        assertNotNull(peer.process.services.notesRepository.getNoteDetails(oldContent.noteId))
                        assertTrue(controller.replaceAccountWorkspace(
                            assertNotNull(controller.state.sync.accountReset.snapshot?.review), mode,
                            discardConfirmed = true, secret = secret,
                        ), controller.state.sync.accountReset.snapshot?.issue?.name)
                        assertCompletedControllerReplacement(peer, controller, oldContent)
                        assertEquals(1, peer.process.controllerSyncResults.size, "$mode replacement itself downloads the current workspace.")
                        assertEquals(leader.process.keys.workspaceIdOrNull(), peer.process.keys.workspaceIdOrNull())
                        assertEquals(leader.sessionStore.load()?.accountIncarnation, peer.sessionStore.load()?.accountIncarnation)
                        assertTrue(peer.process.services.mediaCoordinator.materialize(newContent.assetId).downloaded,
                            "$mode must retrieve the new image from real HTTP, not reuse pre-reset bytes.")
                        assertTextAndImage(peer, newContent)
                        assertTrue(controller.runAutomaticSync(), controller.state.feedbackMessage)

                        val notebook = peer.process.services.notesRepository.createNotebook("$mode automatic write")
                        assertTrue(controller.runAutomaticSync(), controller.state.feedbackMessage)
                        assertTrue(leaderController.runAutomaticSync(), leaderController.state.feedbackMessage)
                        assertTrue(leader.process.services.notesRepository.listNotebooks().any { it.id == notebook.id })
                    }
                }
            }
        }
    }

    @Test
    fun twoRealTransportsKeepRetiredDataOutUntilSeparateLocalConsentAndPreserveControlAccount() {
        val endpoint = requiredEnvironment("SOMEDAY_E2E_ENDPOINT")
        val account = account("reset-two-device")
        AccountResetInstallation(endpoint, account, AccountResetJourneyTransport.jdk(), "leader").use { leader ->
            AccountResetInstallation(endpoint, account, AccountResetJourneyTransport.ktor(), "peer").use { peer ->
                AccountResetInstallation(endpoint, account("reset-control"), AccountResetJourneyTransport.jdk(), "control").use { control ->
                    leader.process.connect(true)
                    peer.process.connect(false)
                    control.process.connect(true)
                    val original = publishTextAndImage(leader, "retired original")
                    join(peer, leader)
                    sync(peer)
                    assertTextAndImage(peer, original)
                    val untouched = publishTextAndImage(control, "unrelated control")
                    val oldLeader = assertNotNull(leader.sessionStore.load())
                    val oldPeer = assertNotNull(peer.sessionStore.load())
                    val controlCredentials = assertNotNull(control.sessionStore.load())
                    val controlMediaBefore = control.transport.getMediaObject(endpoint, controlCredentials.accessToken,
                        assertNotNull(control.process.keys.workspaceIdOrNull()), untouched.assetId.value,
                        controlCredentials.accountRequestContext()).ciphertextSha256
                    val oldWorkspace = assertNotNull(leader.process.keys.workspaceIdOrNull())
                    val oldFingerprint = assertNotNull(leader.process.keys.unlockedKeyOrNull()).fingerprint
                    val oldPeerFingerprint = assertNotNull(peer.process.keys.unlockedKeyOrNull()).fingerprint
                    val preparedRecovery = leader.process.recovery.prepareCode()
                    val recoveryCode = assertNotNull(preparedRecovery.recoveryCode).revealForUserConfirmation()
                    assertTrue(leader.process.recovery.confirmPreparedCode(recoveryCode).success)
                    val controlBefore = accountResetServerState(controlCredentials.userId)
                    assertTrue(controlBefore.entityRows > 0, "The control fingerprint must cover published entity rows.")
                    assertEquals(1, accountResetServerState(oldLeader.userId).recoveryEnvelopes)

                    val ready = leader.process.reset.refresh()
                    assertTrue(ready.snapshot.resetAvailable, "The isolated server must explicitly enable reset and pass storage readiness.")
                    val reset = leader.process.reset.submit(assertNotNull(ready.snapshot.review), account.password)
                    assertTrue(reset.success, reset.issue?.name)
                    assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, reset.snapshot.phase)
                    val successor = assertNotNull(reset.snapshot.review).targetIncarnation
                    assertNotEquals(INITIAL_ACCOUNT_INCARNATION, successor)
                    assertEquals(oldWorkspace, leader.process.keys.workspaceIdOrNull())
                    assertEquals(oldFingerprint, leader.process.keys.unlockedKeyOrNull()?.fingerprint)
                    assertNotNull(leader.process.services.notesRepository.getNoteDetails(original.noteId))
                    assertEquals(1, leader.transport.registrations, "Remote reset and immediate control login never re-enroll a device.")
                    assertFailsWith<WorkspaceProductReadOnlyException> {
                        leader.process.services.notesRepository.createNotebook("must stay frozen")
                    }
                    val remoteCommitted = accountResetServerState(oldLeader.userId)
                    assertEquals(successor, remoteCommitted.activeIncarnation)
                    assertEquals(1, remoteCommitted.retiredIncarnations)
                    assertEquals(1, remoteCommitted.receipts)
                    assertEquals(0, remoteCommitted.currentDevices)
                    assertEquals(0, remoteCommitted.activeWorkspaces)
                    assertEquals(1, remoteCommitted.retiredWorkspaces)
                    assertEquals(1, remoteCommitted.mediaObjects, "Reset retires media; it does not physically delete it.")
                    assertEquals(0, remoteCommitted.recoveryEnvelopes)

                    // Actual old access tokens fail through both shipped transport implementations.
                    expectError(SelfHostedErrorCode.ACCOUNT_SESSION_STALE) {
                        leader.transport.accountMe(endpoint, oldLeader.accessToken, oldLeader.accountRequestContext())
                    }
                    expectError(SelfHostedErrorCode.ACCOUNT_SESSION_STALE) {
                        peer.transport.headMediaObject(endpoint, oldPeer.accessToken, oldWorkspace,
                            original.assetId.value, oldPeer.accountRequestContext())
                    }
                    expectError(SelfHostedErrorCode.ACCOUNT_SESSION_STALE) { leader.transport.replayOldPush(oldLeader) }
                    val peerSync = peer.process.services.manualSyncRunner.run()
                    assertFalse(peerSync.success)
                    assertEquals(AccountDataResetPhase.ResetRequired, peer.process.reset.load().phase)
                    assertNotNull(peer.process.services.notesRepository.getNoteDetails(original.noteId))
                    assertEquals(oldPeerFingerprint, peer.process.keys.unlockedKeyOrNull()?.fingerprint)

                    val refusedFresh = leader.process.reset.replaceLocal(assertNotNull(reset.snapshot.review), AccountDataReplacementMode.Fresh, false)
                    assertEquals(AccountDataResetIssue.ConfirmationRequired, refusedFresh.issue)
                    assertFalse(refusedFresh.localReplaced)
                    assertEquals(1, leader.transport.registrations)
                    assertEquals(oldWorkspace, leader.process.keys.workspaceIdOrNull())
                    assertEquals(0, accountResetServerState(oldLeader.userId).currentDevices)
                    val fresh = leader.process.reset.replaceLocal(assertNotNull(refusedFresh.snapshot.review), AccountDataReplacementMode.Fresh, true)
                    assertTrue(fresh.success, fresh.issue?.name)
                    assertTrue(fresh.localReplaced)
                    assertNotEquals(oldWorkspace, leader.process.keys.workspaceIdOrNull())
                    assertNotEquals(oldFingerprint, leader.process.keys.unlockedKeyOrNull()?.fingerprint)
                    assertEquals(leader.writerId, leader.sessionStore.load()?.deviceId)
                    assertEquals(leader.writerId, leader.process.services.activeWorkspaceSessionGuard.currentRequirement()?.localWriterDeviceId)
                    assertNull(leader.process.services.notesRepository.getNoteDetails(original.noteId))
                    assertNull(leader.process.services.localMediaAssetStore.getAsset(original.assetId))
                    assertNull(leader.process.services.accountStateRepository.loadIntent())
                    val current = assertNotNull(leader.sessionStore.load())
                    assertEquals(successor, current.accountIncarnation)

                    // A freshly authenticated writer still cannot publish old encrypted objects
                    // or reuse a retired workspace. This exercises typed business-409 routes.
                    expectError(SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED) { leader.transport.replayOldChunk(current) }
                    expectError(SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED) { leader.transport.replayOldPush(current) }
                    expectError(SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED) {
                        peer.transport.headMediaObject(endpoint, current.accessToken, oldWorkspace,
                            original.assetId.value, current.accountRequestContext())
                    }
                    expectError(SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH) {
                        peer.transport.v2Epoch(endpoint, current.accessToken, oldWorkspace, oldPeer.accountRequestContext())
                    }
                    assertHeaderlessClientCannotPublishAfterReset(endpoint, current, oldWorkspace, leader.transport.encodedOldPush())

                    val replacement = publishTextAndImage(leader, "current incarnation")
                    val invitation = assertNotNull(leader.process.pairing.createInvitation().invitation).revealManualToken()
                    val authenticated = peer.process.reset.reauthenticate(assertNotNull(peer.process.reset.load().review), account.password)
                    assertTrue(authenticated.success, authenticated.issue?.name)
                    val peerRegistrations = peer.transport.registrations
                    val refusedPair = peer.process.reset.replaceLocal(assertNotNull(authenticated.snapshot.review),
                        AccountDataReplacementMode.Pair, false, invitation)
                    assertEquals(AccountDataResetIssue.ConfirmationRequired, refusedPair.issue)
                    assertEquals(peerRegistrations, peer.transport.registrations)
                    assertEquals(oldWorkspace, peer.process.keys.workspaceIdOrNull())
                    assertNotNull(peer.process.services.notesRepository.getNoteDetails(original.noteId))
                    assertEquals(1, accountResetServerState(oldLeader.userId).currentDevices)
                    val paired = peer.process.reset.replaceLocal(assertNotNull(refusedPair.snapshot.review),
                        AccountDataReplacementMode.Pair, true, invitation)
                    assertTrue(paired.success, paired.issue?.name)
                    assertTrue(paired.localReplaced)
                    assertEquals(peer.writerId, peer.sessionStore.load()?.deviceId)
                    assertEquals(peer.writerId, peer.process.services.activeWorkspaceSessionGuard.currentRequirement()?.localWriterDeviceId)
                    assertEquals(leader.process.keys.workspaceIdOrNull(), peer.process.keys.workspaceIdOrNull())
                    assertNull(peer.process.services.notesRepository.getNoteDetails(original.noteId))
                    sync(peer)
                    assertTextAndImage(peer, replacement)
                    val peerNotebook = peer.process.services.notesRepository.createNotebook("peer writes with its stable writer")
                    sync(peer)
                    sync(leader)
                    assertTrue(leader.process.services.notesRepository.listNotebooks().any { it.id == peerNotebook.id })
                    assertEquals(2, accountResetServerState(oldLeader.userId).currentDevices)
                    assertEquals(1, accountResetServerState(oldLeader.userId).receipts)

                    assertEquals(controlBefore, accountResetServerState(controlCredentials.userId))
                    assertTextAndImage(control, untouched)
                    assertNotNull(control.transport.headMediaObject(endpoint, controlCredentials.accessToken,
                        assertNotNull(control.process.keys.workspaceIdOrNull()), untouched.assetId.value,
                        controlCredentials.accountRequestContext()))
                    assertEquals(controlMediaBefore, control.transport.getMediaObject(endpoint, controlCredentials.accessToken,
                        assertNotNull(control.process.keys.workspaceIdOrNull()), untouched.assetId.value,
                        controlCredentials.accountRequestContext()).ciphertextSha256)
                    control.process.services.notesRepository.createNotebook("control remains writable")
                    sync(control)
                    assertEquals(INITIAL_ACCOUNT_INCARNATION, control.sessionStore.load()?.accountIncarnation)
                }
            }
        }
    }

    @Test
    fun lostRealResponseSurvivesSqliteReopenAndRetriesSameOperationWhileOfflineEditsStayLocal() {
        val endpoint = requiredEnvironment("SOMEDAY_E2E_ENDPOINT")
        val account = account("reset-response-loss")
        AccountResetInstallation(endpoint, account, AccountResetJourneyTransport.ktor(), "restart").use { device ->
            device.process.connect(true)
            val original = publishTextAndImage(device, "before unknown result")
            val initialCredentials = assertNotNull(device.sessionStore.load())
            val workspace = assertNotNull(device.process.keys.workspaceIdOrNull())
            val fingerprint = device.process.keys.unlockedKeyOrNull()?.fingerprint
            val ready = device.process.reset.refresh()
            assertTrue(ready.snapshot.resetAvailable, "Reset-enabled service environment is required; no skipped acceptance.")
            device.transport.loseNextResetResponse = true
            val lost = device.process.reset.submit(assertNotNull(ready.snapshot.review), account.password)
            assertFalse(lost.success)
            assertEquals(AccountDataResetPhase.OutcomeUnknown, lost.snapshot.phase)
            val operation = assertNotNull(lost.snapshot.operationId)
            val actualReceipt = device.transport.receivedReceipts.single()
            assertEquals(operation, actualReceipt.operationId)
            assertEquals(1, accountResetServerState(initialCredentials.userId).receipts)
            assertTrue(lost.snapshot.productReadOnly)
            assertFailsWith<WorkspaceProductReadOnlyException> {
                device.process.services.notesRepository.createNotebook("unknown is frozen")
            }
            assertTrue(device.process.reset.keepOffline(assertNotNull(lost.snapshot.review)).success)
            val offlineNotebook = device.process.services.notesRepository.createNotebook("offline only after response loss")
            device.restart()
            val reopened = device.process.reset.load()
            assertEquals(AccountDataResetPhase.OutcomeUnknown, reopened.phase)
            assertEquals(operation, reopened.operationId)
            assertTrue(reopened.offlineEditing)
            assertFalse(reopened.productReadOnly)
            assertEquals(workspace, device.process.keys.workspaceIdOrNull())
            assertEquals(fingerprint, device.process.keys.unlockedKeyOrNull()?.fingerprint)
            assertEquals(device.writerId, reopened.review?.writerDeviceId)
            assertTrue(device.process.services.notesRepository.listNotebooks().any { it.id == offlineNotebook.id })
            assertTextAndImage(device, original)
            assertFailsWith<AccountNetworkBlockedException> {
                device.process.services.accountStateRepository.requireNetworkAllowed(endpoint, initialCredentials.userId, workspace)
            }
            val publications = device.transport.publicationRequests
            val serverBeforeOfflineSync = accountResetServerState(initialCredentials.userId)
            assertTrue(serverBeforeOfflineSync.entityRows > 0, "The offline comparison must cover actual retained entity rows.")
            assertFalse(device.process.services.manualSyncRunner.run().success)
            assertEquals(publications, device.transport.publicationRequests)
            assertEquals(serverBeforeOfflineSync, accountResetServerState(initialCredentials.userId))

            // Reopening does not refresh or relabel old credentials. An explicit retry
            // uses the same operation and the real server rejects the old session.
            val staleRetry = device.process.reset.submit(assertNotNull(reopened.review), account.password)
            assertEquals(AccountDataResetPhase.OutcomeUnknown, staleRetry.snapshot.phase)
            assertEquals(listOf(operation, operation), device.transport.resetOperations)
            assertEquals(1, device.transport.registrations)

            // Authenticate without registration, then lose the actual receipt GET.
            // A following POST can now replay the original committed operation.
            device.transport.loseNextReceiptResponse = true
            val lostReceipt = device.process.reset.reauthenticate(assertNotNull(staleRetry.snapshot.review), account.password)
            assertFalse(lostReceipt.success)
            assertEquals(AccountDataResetPhase.OutcomeUnknown, lostReceipt.snapshot.phase)
            assertEquals(1, device.transport.registrations)
            val replay = device.process.reset.submit(assertNotNull(lostReceipt.snapshot.review), account.password)
            assertTrue(replay.success, replay.issue?.name)
            assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, replay.snapshot.phase)
            assertEquals(listOf(operation, operation, operation), device.transport.resetOperations)
            assertEquals(listOf(actualReceipt, actualReceipt), device.transport.receivedReceipts)
            assertEquals(1, accountResetServerState(initialCredentials.userId).receipts)
            assertEquals(1, accountResetServerState(initialCredentials.userId).retiredIncarnations)
            assertEquals(0, accountResetServerState(initialCredentials.userId).currentDevices)
            assertTrue(device.process.services.notesRepository.listNotebooks().any { it.id == offlineNotebook.id })
            assertEquals(workspace, device.process.keys.workspaceIdOrNull())
            val noConsent = device.process.reset.replaceLocal(assertNotNull(replay.snapshot.review), AccountDataReplacementMode.Fresh, false)
            assertEquals(AccountDataResetIssue.ConfirmationRequired, noConsent.issue)
            assertEquals(1, device.transport.registrations)
            assertTrue(device.process.reset.keepOffline(assertNotNull(noConsent.snapshot.review)).success)
            device.restart()
            val offlineCommitted = device.process.reset.load()
            assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, offlineCommitted.phase)
            assertTrue(offlineCommitted.offlineEditing)
            assertEquals(operation, offlineCommitted.operationId)
            assertTrue(device.process.services.notesRepository.listNotebooks().any { it.id == offlineNotebook.id })
            val publicationsAfterReopen = device.transport.publicationRequests
            val serverAfterReopen = accountResetServerState(initialCredentials.userId)
            assertFalse(device.process.services.manualSyncRunner.run().success)
            assertEquals(publicationsAfterReopen, device.transport.publicationRequests)
            assertEquals(serverAfterReopen, accountResetServerState(initialCredentials.userId))
            assertEquals(0, accountResetServerState(initialCredentials.userId).activeWorkspaces)
        }
    }

    private fun publishTextAndImage(device: AccountResetInstallation, title: String): Content {
        val notebook = device.process.services.notesRepository.createNotebook(title)
        sync(device)
        val imported = device.process.services.localMediaAssetStore.importAsset(
            Buffer().write(PNG),
            MediaAssetImportRequest(mediaType = "image/png", originalFileName = "synthetic.png"),
        )
        val asset = imported.asset.metadata.id
        val note = device.process.services.notesRepository.createNote(
            NoteInput(
                notebookId = notebook.id,
                title = title,
                markdownBody = "Private test text\n\n![synthetic](someday-asset://${asset.value})",
                createdAt = CREATED_AT,
                timeZoneId = "UTC",
            ),
        )
        sync(device)
        return Content(note.id, asset, title)
    }

    private suspend fun authenticateBlockedController(
        device: AccountResetInstallation,
        oldContent: Content,
    ): SettingsUiController {
        val controller = device.process.settingsController()
        controller.loadAccountResetState()
        assertTrue(controller.state.sync.accountReset.blocksSync)
        assertTrue(controller.state.sync.accountReset.snapshot?.requiresAuthentication == true)
        val review = assertNotNull(controller.state.sync.accountReset.snapshot?.review)
        assertTrue(review.accountEmail.isBlank(), "The recovery form must work without a remembered email.")
        val registrations = device.transport.registrations
        assertTrue(controller.reauthenticateAccountReset(review, device.account.password, device.account.email),
            controller.state.sync.accountReset.snapshot?.issue?.name)
        assertEquals(registrations, device.transport.registrations, "Control login cannot register or discard a device.")
        assertNull(device.sessionStore.load(), "Control-only credentials must not become a content session.")
        assertTrue(controller.state.sync.accountReset.blocksSync)
        assertFalse(controller.state.sync.accountReset.snapshot?.requiresAuthentication == true)
        assertNotNull(device.process.services.notesRepository.getNoteDetails(oldContent.noteId))
        assertTrue(device.process.controllerSyncResults.isEmpty())
        return controller
    }

    private fun assertCompletedControllerReplacement(
        device: AccountResetInstallation,
        controller: SettingsUiController,
        oldContent: Content,
    ) {
        assertEquals(AccountDataResetPhase.LocalReady, controller.state.sync.accountReset.snapshot?.phase)
        assertNull(controller.state.sync.accountReset.snapshot?.issue)
        assertEquals(AccountDataResetIssue.RetiredMediaPending, controller.state.sync.accountReset.snapshot?.resetUnavailableIssue)
        assertTrue(controller.state.sync.connection is SyncConnectionUi.Connected)
        assertEquals(SyncMode.SelfHosted, controller.state.settings.syncConfiguration.mode)
        assertTrue(controller.state.settings.syncConfiguration.selfHostedSession.loggedIn)
        assertFalse(controller.state.sync.accountReset.blocksSync)
        assertFalse(controller.state.sync.recovery.blocksSync)
        assertEquals(WorkspaceAdmissionState.Ready, controller.state.sync.admission.state)
        assertEquals(1, controller.state.sync.admission.initializedWorkspaceCount)
        assertNull(controller.state.sync.issue)
        assertTrue(device.process.controllerSyncResults.isNotEmpty(), "A local replacement alone is not a completed sync journey.")
        assertTrue(device.process.controllerSyncResults.last().success, device.process.controllerSyncResults.last().reason.name)
        assertTrue(controller.canRunAutomaticSync())
        assertTrue(device.process.services.automaticSyncEligible())
        assertEquals(device.writerId, device.sessionStore.load()?.deviceId)
        assertNull(device.process.services.notesRepository.getNoteDetails(oldContent.noteId))
    }

    private suspend fun publishTextAndImageAutomatically(
        device: AccountResetInstallation,
        controller: SettingsUiController,
        title: String,
    ): Content {
        val notebook = device.process.services.notesRepository.createNotebook(title)
        assertTrue(controller.runAutomaticSync(), controller.state.feedbackMessage)
        val imported = device.process.services.localMediaAssetStore.importAsset(
            Buffer().write(NEW_PNG),
            MediaAssetImportRequest(mediaType = "image/png", originalFileName = "synthetic.png"),
        )
        val asset = imported.asset.metadata.id
        val note = device.process.services.notesRepository.createNote(NoteInput(
            notebookId = notebook.id,
            title = title,
            markdownBody = "New workspace test text\n\n![synthetic](someday-asset://${asset.value})",
            createdAt = CREATED_AT,
            timeZoneId = "UTC",
        ))
        assertTrue(controller.runAutomaticSync(), controller.state.feedbackMessage)
        return Content(note.id, asset, title, NEW_PNG)
    }

    private fun assertTextAndImage(device: AccountResetInstallation, content: Content) {
        val note = assertNotNull(device.process.services.notesRepository.getNoteDetails(content.noteId))
        assertEquals(content.title, note.title)
        assertTrue(note.markdownBody.contains("someday-asset://${content.assetId.value}"))
        if (device.process.services.localMediaAssetStore.getAsset(content.assetId) == null) {
            assertTrue(device.process.services.mediaCoordinator.materialize(content.assetId).downloaded)
        }
        assertContentEquals(
            content.imageBytes,
            device.process.services.localMediaAssetStore.openSource(content.assetId).buffer().use { it.readByteArray() },
        )
    }

    private fun join(receiver: AccountResetInstallation, source: AccountResetInstallation) {
        val invitation = assertNotNull(source.process.pairing.createInvitation().invitation).revealManualToken()
        val result = receiver.process.pairing.joinWithToken(invitation, replaceExistingWorkspace = true)
        assertTrue(result.success, result.reason.name)
    }

    private fun sync(device: AccountResetInstallation) {
        val result = device.process.services.manualSyncRunner.run()
        assertTrue(result.success, result.reason.name)
    }

    private fun expectError(code: SelfHostedErrorCode, request: () -> Any?) {
        val failure = assertFailsWith<SelfHostedSyncHttpException> { request() }
        assertEquals(code, failure.errorCode)
        assertTrue(failure.protocol1)
        assertTrue(failure.status in code.statuses)
    }

    /** Wire emulation proves headerless rejection; it is not an actual old app binary. */
    private fun assertHeaderlessClientCannotPublishAfterReset(
        endpoint: String,
        current: SelfHostedSessionCredentials,
        workspace: String,
        encodedPush: String,
    ) {
        HttpClient.newHttpClient().use { client ->
            val response = client.send(
                HttpRequest.newBuilder(URI.create("$endpoint/sync/v3/workspaces/$workspace/entities/push"))
                    .header("Authorization", "Bearer ${current.accessToken}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(encodedPush))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(426, response.statusCode())
            assertEquals(
                SelfHostedErrorCode.ACCOUNT_PROTOCOL_UPGRADE_REQUIRED.wireCode,
                response.headers().firstValue(SELF_HOSTED_ERROR_CODE_HEADER).orElse(null),
            )
        }
    }

    private fun account(prefix: String): TestAccount {
        val unique = UUID.randomUUID().toString()
        return TestAccount("$prefix-$unique@example.com", "Reset-acceptance-$unique")
    }

    private data class Content(val noteId: String, val assetId: MediaAssetId, val title: String, val imageBytes: ByteArray = PNG)

    private companion object {
        val CREATED_AT = Instant.parse("2026-09-29T04:00:00Z")
        val PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        val NEW_PNG: ByteArray = ByteArrayOutputStream().use { output ->
            val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
            image.setRGB(0, 0, 0x336699)
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }
}
