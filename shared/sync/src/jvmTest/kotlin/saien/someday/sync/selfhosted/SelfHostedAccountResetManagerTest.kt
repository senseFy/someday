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
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.*
import saien.someday.domain.workspace.WorkspaceProductReadOnlyException
import saien.someday.sync.createSystemV3ClientServices
import saien.someday.sync.SystemV3ClientServices

class SelfHostedAccountResetManagerTest {
    @Test fun everyReplacementModePromotesTheEnrolledSessionAfterControlOnlyRecovery() {
        for (mode in AccountDataReplacementMode.entries) fixture(bound = false) { f ->
            val committed = f.manager.submit(f.review(), PASSWORD)
            assertNull(committed.issue)
            assertEquals(AccountDataResetIssue.RetiredMediaPending, committed.snapshot.resetUnavailableIssue)
            val original = f.keys.workspaceIdOrNull()
            withTargetWorkspace(f) { targetWorkspace, _, invitation, recoveryCode ->
                f.forgetSessionAndHints()
                f.manager = f.newManager()
                if (mode == AccountDataReplacementMode.Fresh) f.transport.deploymentReady = false
                val authenticated = f.manager.reauthenticate(assertNotNull(f.manager.load().review), PASSWORD, EMAIL)
                assertTrue(authenticated.success, authenticated.issue.toString())
                assertNull(authenticated.issue)
                assertEquals(if (mode == AccountDataReplacementMode.Fresh) AccountDataResetIssue.DeploymentNotReady else AccountDataResetIssue.RetiredMediaPending,
                    authenticated.snapshot.resetUnavailableIssue)
                assertFalse(authenticated.snapshot.requiresAuthentication)
                assertEquals(SyncMode.Off, f.settings.load().syncConfiguration.mode)
                assertFalse(f.settings.load().syncConfiguration.selfHostedSession.loggedIn)
                assertNull(f.store.load())
                val secret = when (mode) {
                    AccountDataReplacementMode.Fresh -> ""
                    AccountDataReplacementMode.Pair -> invitation
                    AccountDataReplacementMode.Recover -> recoveryCode
                }

                val replaced = f.manager.replaceLocal(assertNotNull(authenticated.snapshot.review), mode, true, secret)

                assertTrue(replaced.success, "$mode: ${replaced.issue}")
                assertTrue(replaced.localReplaced)
                assertNull(replaced.issue)
                assertEquals(SyncMode.SelfHosted, f.settings.load().syncConfiguration.mode)
                assertEquals(assertNotNull(f.store.load()).toSummary(), f.settings.load().syncConfiguration.selfHostedSession)
                assertNotEquals(original, f.keys.workspaceIdOrNull())
                if (mode != AccountDataReplacementMode.Fresh) assertEquals(targetWorkspace, f.keys.workspaceIdOrNull())
                assertEquals(NEXT, f.services.activeWorkspaceSessionGuard.currentRequirement()?.accountIncarnation)
                assertNull(f.services.accountStateRepository.loadIntent())
                assertFalse(replaced.snapshot.requiresAuthentication)
            }
        }
    }

    @Test fun closingOrExpiringControlAuthenticationCannotOfferReplacementOrFreezeAnOfflineCopy() = fixture { f ->
        f.manager.submit(f.review(), PASSWORD)
        f.manager.keepOffline(f.review())
        f.manager.cancel()
        val closed = f.manager.load()
        assertTrue(closed.requiresAuthentication)
        assertFalse(closed.canReplaceLocal)
        assertTrue(closed.offlineEditing)
        val rejected = f.manager.replaceLocal(assertNotNull(closed.review), AccountDataReplacementMode.Fresh, true)
        assertEquals(AccountDataResetIssue.SignInRequired, rejected.issue)
        assertTrue(rejected.snapshot.offlineEditing)
        assertEquals(0, f.transport.registrations)
        assertNull(f.services.accountStateRepository.loadIntent()?.consentTargetIncarnation)

        val authenticated = f.manager.reauthenticate(assertNotNull(rejected.snapshot.review), PASSWORD)
        assertFalse(authenticated.snapshot.requiresAuthentication)
        assertTrue(authenticated.snapshot.canReplaceLocal)
        f.transport.beforeDiscovery = { throw SelfHostedSyncHttpException(401, "Expired", SelfHostedErrorCode.UNAUTHORIZED, true) }
        val expired = f.manager.refresh()
        assertEquals(AccountDataResetIssue.SignInRequired, expired.issue)
        assertTrue(expired.snapshot.requiresAuthentication)
        assertFalse(expired.snapshot.canReplaceLocal)
        f.transport.beforeDiscovery = {}
        val stillBlocked = f.manager.replaceLocal(assertNotNull(expired.snapshot.review), AccountDataReplacementMode.Fresh, true)
        assertEquals(AccountDataResetIssue.SignInRequired, stillBlocked.issue)
        assertTrue(stillBlocked.snapshot.offlineEditing)
        assertTrue(f.manager.reauthenticate(assertNotNull(stillBlocked.snapshot.review), PASSWORD).success)
        assertEquals(0, f.transport.registrations)
    }

    @Test fun rejectedSelectedAndAuthorityCredentialsDoNotAlternateForever() {
        for (errorCode in listOf(SelfHostedErrorCode.UNAUTHORIZED, SelfHostedErrorCode.ACCOUNT_SESSION_STALE)) fixture(bound = false) { f ->
            f.transport.loseResetResponse = true
            f.manager.submit(f.review(), PASSWORD)
            val pending = f.services.accountStateRepository.loadIntent()
            val original = f.keys.workspaceIdOrNull()
            val saved = assertNotNull(f.store.load())
            // A secure-store write can succeed for one slot and fail for the other.
            f.store.save(saved.copy(accessToken = "expired-selected"))
            var authorityCredentials = saved.copy(accessToken = "expired-authority")
            val splitStore = object : SelfHostedSessionCredentialStore by f.store {
                override fun loadForAuthority(authorityBindingId: String) =
                    authorityCredentials.takeIf { it.authorityBindingId == authorityBindingId }
            }
            f.transport.authenticationFailureCode = errorCode
            f.manager = f.newManager(managerSessionStore = splitStore)

            val first = f.manager.reconcile(assertNotNull(f.manager.load().review))
            assertEquals(AccountDataResetIssue.SignInRequired, first.issue)
            assertFalse(first.snapshot.requiresAuthentication, "The other credential has not yet been rejected.")
            val second = f.manager.reconcile(assertNotNull(first.snapshot.review))
            assertEquals(AccountDataResetIssue.SignInRequired, second.issue)
            assertTrue(second.snapshot.requiresAuthentication, "Both rejected tokens must stay excluded.")
            assertFalse(second.snapshot.canReplaceLocal)
            assertEquals(2, f.transport.receiptRequests)
            // Rotation of the other slot must not evict the still-selected rejected token.
            repeat(24) { index ->
                authorityCredentials = saved.copy(accessToken = "expired-authority-$index")
                val rotated = f.manager.reconcile(assertNotNull(f.manager.load().review))
                assertTrue(rotated.snapshot.requiresAuthentication)
            }
            assertEquals(26, f.transport.receiptRequests)
            val blocked = f.manager.reconcile(assertNotNull(second.snapshot.review))
            assertEquals(AccountDataResetIssue.SignInRequired, blocked.issue)
            assertEquals(26, f.transport.receiptRequests, "Known rejected tokens must not reach HTTP again.")
            assertEquals(pending, f.services.accountStateRepository.loadIntent())
            assertEquals(original, f.keys.workspaceIdOrNull())

            val authenticated = f.manager.reauthenticate(assertNotNull(blocked.snapshot.review), PASSWORD)
            assertTrue(authenticated.success, authenticated.issue.toString())
            assertFalse(authenticated.snapshot.requiresAuthentication)
            assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, authenticated.snapshot.phase)
            assertTrue(authenticated.snapshot.canReplaceLocal)
            assertEquals(0, f.transport.registrations)
            assertNull(f.services.accountStateRepository.loadIntent()?.consentTargetIncarnation)
            assertEquals(original, f.keys.workspaceIdOrNull())
            assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
        }
    }

    @Test fun failedEnrolledSessionSettingsWriteKeepsTheCopyAndCanRetryWithFreshConsent() = fixture(bound = false) { f ->
        f.manager.submit(f.review(), PASSWORD)
        f.forgetSessionAndHints()
        var failSessionSettings = true
        val failingSettings = object : ClientSettingsRepository by f.settings {
            override fun saveLocalSnapshot(settings: ClientSettings): ClientSettings {
                if (failSessionSettings && settings.syncConfiguration.selfHostedSession.loggedIn) error("Synthetic settings failure")
                return f.settings.saveLocalSnapshot(settings)
            }
        }
        f.manager = f.newManager(managerSettings = failingSettings)
        val original = f.keys.workspaceIdOrNull()
        val authenticated = f.manager.reauthenticate(assertNotNull(f.manager.load().review), PASSWORD, EMAIL)
        val failed = f.manager.replaceLocal(assertNotNull(authenticated.snapshot.review), AccountDataReplacementMode.Fresh, true)
        assertEquals(AccountDataResetIssue.ReplacementFailed, failed.issue)
        assertEquals(original, f.keys.workspaceIdOrNull())
        assertTrue(failed.snapshot.productReadOnly)
        assertNotNull(f.services.accountStateRepository.loadIntent())
        assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
        assertEquals(SyncMode.Off, f.settings.load().syncConfiguration.mode)
        assertEquals(NEXT, f.store.load()?.accountIncarnation)
        failSessionSettings = false
        val reauthenticated = f.manager.reauthenticate(assertNotNull(failed.snapshot.review), PASSWORD)
        assertTrue(reauthenticated.success, reauthenticated.issue.toString())
        assertTrue(reauthenticated.snapshot.productReadOnly)
        assertEquals(original, f.keys.workspaceIdOrNull())
        assertEquals(SyncMode.Off, f.settings.load().syncConfiguration.mode)
        assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
        assertFalse(f.manager.replaceLocal(assertNotNull(reauthenticated.snapshot.review), AccountDataReplacementMode.Fresh, false).success)
        val retried = f.manager.replaceLocal(assertNotNull(f.manager.load().review), AccountDataReplacementMode.Fresh, true)
        assertTrue(retried.success, retried.issue.toString())
        assertEquals(SyncMode.SelfHosted, f.settings.load().syncConfiguration.mode)
        assertTrue(f.settings.load().syncConfiguration.selfHostedSession.loggedIn)
        assertNotEquals(original, f.keys.workspaceIdOrNull())
    }

    @Test fun pairingFailureExplainsExpiredMissingOrUsedInvitationWithoutDiscardingLocalData() {
        for ((code, expected) in listOf(
            SelfHostedErrorCode.EXPIRED to AccountDataResetIssue.InvitationUnavailable,
            SelfHostedErrorCode.NOT_FOUND to AccountDataResetIssue.InvitationUnavailable,
            SelfHostedErrorCode.PAIRING_CONFLICT to AccountDataResetIssue.InvitationAlreadyUsed,
        )) fixture { f ->
            f.manager.submit(f.review(), PASSWORD)
            val original = f.keys.workspaceIdOrNull()
            withTargetWorkspace(f) { _, _, invitation, _ ->
                f.transport.claimFailure = code
                val result = f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Pair, true, invitation)
                assertEquals(expected, result.issue)
                assertEquals(original, f.keys.workspaceIdOrNull())
                assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
            }
        }
    }

    @Test fun missingRecoveryAndInvalidCodeAndRevokedDeviceHaveDistinctActionableResults() = fixture { f ->
        f.manager.submit(f.review(), PASSWORD)
        val original = f.keys.workspaceIdOrNull()
        withTargetWorkspace(f) { _, _, _, recoveryCode ->
            val invalid = f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Recover, true, "invalid")
            assertEquals(AccountDataResetIssue.InvalidSecret, invalid.issue)
            f.transport.recoveryEnvelope = null
            val missing = f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Recover, true, recoveryCode)
            assertEquals(AccountDataResetIssue.NoRecoveryEnvelope, missing.issue)
            f.transport.registerDeviceRevoked = true
            val revoked = f.manager.replaceLocal(f.review(), AccountDataReplacementMode.Fresh, true)
            assertEquals(AccountDataResetIssue.DeviceRevoked, revoked.issue)
            assertEquals(original, f.keys.workspaceIdOrNull())
            assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
        }
    }

    @Test fun committedUnboundCopyWithoutSessionOrHintsCanRecoverWithItsDurableAccountIdentity() = fixture(bound = false) { f ->
        f.manager.submit(f.review(), PASSWORD)
        val originalWorkspace = f.keys.workspaceIdOrNull()
        val originalKey = f.keys.unlockedKeyOrNull()!!.fingerprint
        val intent = assertNotNull(f.services.accountStateRepository.loadIntent())
        f.forgetSessionAndHints()
        val beforeSettings = f.settings.load()
        f.manager = f.newManager(f.newServices())

        val reopened = f.manager.load()
        val review = assertNotNull(reopened.review)
        assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, reopened.phase)
        assertEquals(intent.operationId, reopened.operationId)
        assertEquals(ENDPOINT, reopened.endpoint)
        assertEquals("", review.accountEmail)
        assertEquals(selfHostedAuthorityBindingId(ENDPOINT, USER), review.authorityBindingId)
        assertNull(review.localAuthorityBindingId)
        assertEquals(NEXT, review.targetIncarnation)
        assertTrue(reopened.productReadOnly)
        assertTrue(reopened.canExport)
        assertFalse(reopened.canReplaceLocal)
        assertTrue(reopened.requiresAuthentication)
        assertNull(reopened.issue)
        assertFailsWith<WorkspaceProductReadOnlyException> { f.services.notesRepository.createNotebook("still frozen") }

        val authenticated = f.manager.reauthenticate(review, PASSWORD, "  ${EMAIL.uppercase()}  ")
        assertTrue(authenticated.success, authenticated.issue.toString())
        assertEquals(EMAIL, f.transport.lastLoginEmail)
        assertEquals(EMAIL, authenticated.snapshot.review?.accountEmail)
        assertNotEquals(review.id, authenticated.snapshot.review?.id)
        assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, authenticated.snapshot.phase)
        assertTrue(authenticated.snapshot.productReadOnly)
        assertTrue(authenticated.snapshot.canReplaceLocal)
        assertEquals(0, f.transport.registrations)
        assertNull(f.store.load())
        assertNull(f.store.loadForAuthority(review.authorityBindingId))
        assertEquals(originalWorkspace, f.keys.workspaceIdOrNull())
        assertEquals(originalKey, f.keys.unlockedKeyOrNull()!!.fingerprint)
        assertEquals(intent, f.services.accountStateRepository.loadIntent())
        assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
        assertEquals(beforeSettings.copy(syncConfiguration = beforeSettings.syncConfiguration.copy(
            selfHostedEndpoint = ENDPOINT,
            selfHostedSession = beforeSettings.syncConfiguration.selfHostedSession.copy(userEmail = EMAIL),
        )), f.settings.load())

        val refused = f.manager.replaceLocal(assertNotNull(authenticated.snapshot.review), AccountDataReplacementMode.Fresh, false)
        assertEquals(AccountDataResetIssue.ConfirmationRequired, refused.issue)
        assertEquals(0, f.transport.registrations)
        assertNull(f.services.accountStateRepository.loadIntent()?.consentTargetIncarnation)
        val staleReview = assertNotNull(refused.snapshot.review)
        f.manager.cancel()
        val loginCalls = f.transport.logins
        assertEquals(AccountDataResetIssue.ContextChanged, f.manager.reauthenticate(staleReview, PASSWORD, EMAIL).issue)
        assertEquals(loginCalls, f.transport.logins)
        f.manager = f.newManager(f.newServices())
        assertEquals(EMAIL, f.manager.load().review?.accountEmail)
        assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, f.manager.load().phase)
        assertNull(f.store.load())
    }

    @Test fun unknownOutcomeWithoutSessionOrHintsKeepsItsLocalProtectionAndCanReconcile() {
        for (offline in listOf(false, true)) fixture(bound = false) { f ->
            f.transport.loseResetResponse = true
            val unknown = f.manager.submit(f.review(), PASSWORD)
            if (offline) assertTrue(f.manager.keepOffline(assertNotNull(unknown.snapshot.review)).success)
            f.forgetSessionAndHints()
            f.manager = f.newManager(f.newServices())
            val reopened = f.manager.load()
            assertEquals(AccountDataResetPhase.OutcomeUnknown, reopened.phase)
            assertEquals(offline, reopened.offlineEditing)
            assertEquals(!offline, reopened.productReadOnly)
            assertTrue(reopened.canExport)
            assertFalse(reopened.canReplaceLocal)
            assertEquals("", reopened.review?.accountEmail)
            assertFailsWith<AccountNetworkBlockedException> {
                f.services.accountStateRepository.requireNetworkAllowed(ENDPOINT, USER, f.keys.workspaceIdOrNull()!!)
            }
            if (!offline) assertFailsWith<WorkspaceProductReadOnlyException> { f.services.notesRepository.createNotebook("frozen") }

            val authenticated = f.manager.reauthenticate(assertNotNull(reopened.review), PASSWORD, EMAIL)
            assertTrue(authenticated.success, authenticated.issue.toString())
            assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, authenticated.snapshot.phase)
            assertEquals(offline, authenticated.snapshot.offlineEditing)
            assertEquals(!offline, authenticated.snapshot.productReadOnly)
            assertNull(f.services.accountStateRepository.loadIntent()?.consentTargetIncarnation)
            assertNull(f.store.load())
            assertEquals(0, f.transport.registrations)
            assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
        }
    }

    @Test fun mismatchedOrInvalidControlIdentityDoesNotPersistAccountHintsOrCredentials() {
        val invalidResponses: List<(SelfHostedAuthTokensResponse) -> SelfHostedAuthTokensResponse> = listOf(
            { it.copy(user = SelfHostedUserResponse("99999999-9999-4999-8999-999999999999", "other@example.invalid")) },
            { it.copy(accountProtocolVersion = null) },
            { it.copy(accountIncarnation = null) },
            { it.copy(accountIncarnation = "invalid") },
        )
        for ((index, invalidResponse) in invalidResponses.withIndex()) fixture(bound = false) { f ->
            f.manager.submit(f.review(), PASSWORD)
            f.forgetSessionAndHints()
            f.manager = f.newManager(f.newServices())
            val originalIntent = f.services.accountStateRepository.loadIntent()
            val originalSettings = f.settings.load()
            val review = assertNotNull(f.manager.load().review)
            f.transport.loginResponseTransform = invalidResponse

            val rejected = f.manager.reauthenticate(review, PASSWORD, "other@example.invalid")

            assertFalse(rejected.success)
            assertEquals(if (index == 0) AccountDataResetIssue.AccountMismatch else AccountDataResetIssue.ProtocolError, rejected.issue)
            assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, rejected.snapshot.phase)
            assertTrue(rejected.snapshot.productReadOnly)
            assertEquals("", rejected.snapshot.review?.accountEmail)
            assertEquals(originalSettings, f.settings.load())
            assertEquals(originalIntent, f.services.accountStateRepository.loadIntent())
            assertNull(f.store.load())
            assertNull(f.store.loadForAuthority(review.authorityBindingId))
            assertEquals(0, f.transport.registrations)
            assertEquals(AccountDataResetIssue.SignInRequired, f.manager.refresh().issue)
        }
    }

    @Test fun retiredWorkspaceGateWithoutIntentSessionOrHintsStillRequiresLocalConsent() = fixture { f ->
        f.transport.current = NEXT
        f.services.activeWorkspaceSessionGuard.markCurrentIncarnationMismatch()
        f.forgetSessionAndHints()
        f.manager = f.newManager(f.newServices())
        val reopened = f.manager.load()
        assertEquals(AccountDataResetPhase.ResetRequired, reopened.phase)
        assertTrue(reopened.productReadOnly)
        assertTrue(reopened.canExport)
        assertNull(reopened.operationId)
        assertEquals("", reopened.review?.accountEmail)
        assertFailsWith<WorkspaceProductReadOnlyException> { f.services.notesRepository.createNotebook("retired") }

        val authenticated = f.manager.reauthenticate(assertNotNull(reopened.review), PASSWORD, EMAIL)
        assertTrue(authenticated.success, authenticated.issue.toString())
        assertEquals(AccountDataResetPhase.ResetRequired, authenticated.snapshot.phase)
        assertTrue(authenticated.snapshot.productReadOnly)
        assertTrue(authenticated.snapshot.canReplaceLocal)
        assertEquals(NEXT, authenticated.snapshot.review?.targetIncarnation)
        assertNull(f.services.accountStateRepository.loadIntent())
        assertNull(f.store.load())
        assertEquals(0, f.transport.registrations)
        val refused = f.manager.replaceLocal(assertNotNull(authenticated.snapshot.review), AccountDataReplacementMode.Fresh, false)
        assertEquals(AccountDataResetIssue.ConfirmationRequired, refused.issue)
        assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
    }

    @Test fun authenticatedSameIncarnationObservationCanResolveAPersistedGateWithExplicitConsent() = fixture { f ->
        f.services.activeWorkspaceSessionGuard.markCurrentIncarnationMismatch()
        f.manager = f.newManager(f.newServices())
        val reopened = f.manager.load()
        val original = f.keys.workspaceIdOrNull()
        assertEquals(AccountDataResetPhase.ResetRequired, reopened.phase)
        assertTrue(reopened.requiresAuthentication)
        assertFalse(reopened.canReplaceLocal)

        val authenticated = f.manager.reauthenticate(assertNotNull(reopened.review), PASSWORD)
        assertTrue(authenticated.success, authenticated.issue.toString())
        assertEquals(G0, authenticated.snapshot.review?.targetIncarnation)
        assertFalse(authenticated.snapshot.requiresAuthentication)
        assertTrue(authenticated.snapshot.canReplaceLocal)
        assertTrue(authenticated.snapshot.productReadOnly)
        assertFalse(f.manager.replaceLocal(assertNotNull(authenticated.snapshot.review), AccountDataReplacementMode.Fresh, false).success)
        assertEquals(original, f.keys.workspaceIdOrNull())
        assertTrue(f.services.notesRepository.listNotebooks().any { it.title == "original" })
        val replaced = f.manager.replaceLocal(assertNotNull(f.manager.load().review), AccountDataReplacementMode.Fresh, true)
        assertTrue(replaced.success, replaced.issue.toString())
        assertTrue(replaced.localReplaced)
        assertNotEquals(original, f.keys.workspaceIdOrNull())
        assertNull(f.services.accountStateRepository.loadGate(ENDPOINT, USER, original!!))
    }

    @Test fun ordinaryLoginCannotEnrollAnUnboundCopyUntilResetReplacementIsConfirmed() = fixture(bound = false) { f ->
        assertNull(f.services.activeWorkspaceSessionGuard.currentRequirement())
        val originalWorkspace = f.keys.workspaceIdOrNull()
        val committed = f.manager.submit(f.review(), PASSWORD)
        assertEquals(AccountDataResetPhase.RemoteCommittedLocalPending, committed.snapshot.phase)
        val intent = f.services.accountStateRepository.loadIntent()
        val previousCredentials = f.store.load()
        val loginCalls = f.transport.logins
        val setup = f.newSetupService()
        val input = SelfHostedSetupInput(ENDPOINT, EMAIL, PASSWORD, "Test", "desktop", false)

        val refused = setup.setup(input)

        assertEquals(SelfHostedSetupReason.AccountResetRequired, refused.status.reason)
        assertFalse(refused.success)
        assertEquals(loginCalls, f.transport.logins)
        assertEquals(0, f.transport.registrations)
        assertEquals(previousCredentials, f.store.load())
        assertEquals(intent, f.services.accountStateRepository.loadIntent())
        assertEquals(originalWorkspace, f.keys.workspaceIdOrNull())

        // Control authentication remains available after restart and never enrolls a device.
        f.manager = f.newManager()
        val authenticated = f.manager.reauthenticate(assertNotNull(f.manager.load().review), PASSWORD)
        assertTrue(authenticated.success)
        assertEquals(0, f.transport.registrations)
        val unconfirmed = f.manager.replaceLocal(assertNotNull(authenticated.snapshot.review), AccountDataReplacementMode.Fresh, false)
        assertFalse(unconfirmed.success)
        assertEquals(0, f.transport.registrations)
        val replaced = f.manager.replaceLocal(assertNotNull(unconfirmed.snapshot.review), AccountDataReplacementMode.Fresh, true)
        assertTrue(replaced.success, replaced.issue.toString())
        assertNull(f.services.accountStateRepository.loadIntent())
        assertNotEquals(originalWorkspace, f.keys.workspaceIdOrNull())
        assertTrue(setup.setup(input).success, "Ordinary login works again after explicit local replacement.")
        assertEquals(2, f.transport.registrations)
    }

    @Test fun unknownResetOutcomeBlocksOrdinarySetupEvenWithoutSavedCredentials() = fixture(bound = false) { f ->
        f.transport.loseResetResponse = true
        val unknown = f.manager.submit(f.review(), PASSWORD)
        assertEquals(AccountDataResetPhase.OutcomeUnknown, unknown.snapshot.phase)
        val intent = f.services.accountStateRepository.loadIntent()
        val authority = assertNotNull(f.store.load()).authorityBindingId
        f.store.clearAuthority(authority)
        f.store.clear()
        val loginCalls = f.transport.logins

        val refused = f.newSetupService().setup(SelfHostedSetupInput(ENDPOINT, EMAIL, PASSWORD, "Test", "desktop", false))

        assertEquals(SelfHostedSetupReason.AccountResetRequired, refused.status.reason)
        assertEquals(loginCalls, f.transport.logins)
        assertEquals(0, f.transport.registrations)
        assertNull(f.store.load())
        assertEquals(intent, f.services.accountStateRepository.loadIntent())
    }

    @Test fun observedAccountMismatchBlocksOrdinarySetupBeforeRemoteAuthentication() = fixture { f ->
        f.services.activeWorkspaceSessionGuard.markCurrentIncarnationMismatch()
        assertNull(f.services.accountStateRepository.loadIntent())
        val refused = f.newSetupService().setup(SelfHostedSetupInput(ENDPOINT, EMAIL, PASSWORD, "Test", "desktop", false))
        assertEquals(SelfHostedSetupReason.AccountResetRequired, refused.status.reason)
        assertEquals(0, f.transport.logins)
        assertEquals(0, f.transport.registrations)
    }

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

    private fun fixture(bound: Boolean = true, block: (Fixture) -> Unit) {
        val f = Fixture(bound)
        try { block(f) } finally { f.transport.close(); f.driver.close(); f.root.toFile().deleteRecursively() }
    }

    private class Fixture(bound: Boolean = true) {
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
        fun newServices() = createSystemV3ClientServices(local, settings, keys::unlockedKeyOrNull, keys::workspaceIdOrNull,
            LocalMediaAssetStore(database, root.resolve("media").toString().toPath(), decodeValidator = MediaAssetDecodeValidator { DecodedMediaAsset(1, 1) }),
            transport, transport, transport, store)
        val services = newServices()
        init {
            if (bound) {
                services.workspaceLifecycleCoordinator.productAccess {
                    check(services.bindFreshWorkspaceAfterAccountReset(keys.unlockedKeyOrNull()!!, keys.workspaceIdOrNull()!!,
                        WorkspaceJoinAuthorityCapture(store.load()!!.authorityBindingId, DEVICE, G0)))
                }
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
        fun newSetupService() = SelfHostedSetupService(transport, store, services.activeWorkspaceSessionGuard,
            services.workspaceLifecycleCoordinator, { DEVICE })
        fun newManager(clientServices: SystemV3ClientServices = services, managerSettings: ClientSettingsRepository = settings,
            managerSessionStore: SelfHostedSessionCredentialStore = store) = SelfHostedAccountResetManager(clientServices, managerSessionStore, transport, managerSettings, keys::workspaceIdOrNull,
            { DEVICE }, "Test", "desktop", { before, after ->
                keys.replaceWithFreshWorkspace("Test", "desktop", before) { key, workspace ->
                    after(key, workspace)
                    check(!failReplacement) { "Synthetic local commit failure" }
                }
            }, pairing, recovery, canExportProvider = { keys.unlockedKeyOrNull() != null })
        fun forgetSessionAndHints() {
            store.clearAuthority(selfHostedAuthorityBindingId(ENDPOINT, USER))
            store.clear()
            val current = settings.load()
            settings.saveLocalSnapshot(current.copy(syncConfiguration = SyncConfiguration(
                mode = SyncMode.Off, lastError = "setup:AccountResetRequired",
            )))
        }
        fun review(): AccountDataResetReview {
            val refreshed = manager.refresh()
            return assertNotNull(refreshed.snapshot.review)
        }
    }

    private class WorkflowTransport(private val delegate: JdkSelfHostedSyncTransport = JdkSelfHostedSyncTransport()) :
        SelfHostedSyncTransport by delegate, SelfHostedSyncTransportV2 by delegate, SelfHostedMediaTransportV3 by delegate,
        SelfHostedAccountControlTransport, SelfHostedWorkspaceRecoveryTransport {
        val invitations = MemorySelfHostedPairingTransport()
        var recoveryEnvelope: SelfHostedWorkspaceRecoveryEnvelopeResponse? = null
        var claimFailure: SelfHostedErrorCode? = null
        var registerDeviceRevoked = false
        var deploymentReady = true
        var current = G0
        var registrations = 0
        var receiptRequests = 0
        var authenticationFailureCode = SelfHostedErrorCode.ACCOUNT_SESSION_STALE
        var logins = 0
        var lastLoginEmail: String? = null
        var loginResponseTransform: (SelfHostedAuthTokensResponse) -> SelfHostedAuthTokensResponse = { it }
        var loseResetResponse = false
        var committed: SelfHostedAccountResetReceiptResponse? = null
        var beforeDiscovery: () -> Unit = {}
        fun close() = delegate.close()
        override fun login(endpoint: String, request: SelfHostedAuthRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedAuthTokensResponse {
            logins++
            lastLoginEmail = request.email
            if (request.password != PASSWORD) throw SelfHostedSyncHttpException(401, "Rejected", SelfHostedErrorCode.INVALID_CREDENTIALS, true)
            return loginResponseTransform(SelfHostedAuthTokensResponse(current, "refresh", 60, SelfHostedUserResponse(USER, EMAIL), current, 1))
        }
        override fun registerDevice(endpoint: String, accessToken: String, request: SelfHostedDeviceRegistrationRequest, accountContext: SelfHostedAccountRequestContext): SelfHostedDeviceRegistrationResponse {
            requireCurrent(accessToken)
            check(accountContext.accountIncarnation == current && request.deviceId == DEVICE)
            registrations++
            return SelfHostedDeviceRegistrationResponse(SelfHostedDeviceResponse(DEVICE, "Test", "desktop", registerDeviceRevoked), current, "refresh", 60, current, 1)
        }
        override fun discoverAccountData(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountDiscoveryResult {
            beforeDiscovery()
            requireCurrent(accessToken)
            return SelfHostedAccountDiscoveryResult.Protocol1(SelfHostedAccountDataStateResponse(1, current, deploymentReady && current == G0,
                if (!deploymentReady) "deployment_not_ready" else if (current == G0) null else "retired_media_pending"))
        }
        override fun accountMe(endpoint: String, accessToken: String, accountContext: SelfHostedAccountRequestContext) = SelfHostedAccountMeResponse(USER, EMAIL, null, listOf("auth"))
        override fun getAccountResetReceipt(endpoint: String, accessToken: String, operationId: String, accountContext: SelfHostedAccountRequestContext): SelfHostedAccountResetReceiptResponse? {
            receiptRequests++
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
            claimFailure?.let { throw SelfHostedSyncHttpException(if (it == SelfHostedErrorCode.EXPIRED) 410 else if (it == SelfHostedErrorCode.NOT_FOUND) 404 else 409, "Synthetic invite rejection", it, true) }
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
            if (token != current) throw SelfHostedSyncHttpException(401, "Stale", authenticationFailureCode, true)
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
