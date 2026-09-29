@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package saien.someday.sync.selfhosted

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import saien.someday.data.account.AccountResetIntentState
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.*
import saien.someday.sync.SystemV3ClientServices

/** Shared off-main workflow. Control-only login remains in memory until explicit rejoin. */
class SelfHostedAccountResetManager(
    private val services: SystemV3ClientServices,
    private val sessionStore: SelfHostedSessionCredentialStore,
    private val transport: SelfHostedSyncTransport,
    private val settingsRepository: ClientSettingsRepository,
    private val workspaceIdProvider: () -> String?,
    private val localDeviceIdProvider: () -> String,
    private val deviceName: String,
    private val platform: String,
    private val freshWorkspaceReplacement: ((() -> Unit, (WorkspaceMasterKey, String) -> Unit) -> Unit),
    private val pairing: WorkspacePairingInvitationJoiner,
    private val recovery: WorkspaceRecoveryManager,
    private val canExportProvider: () -> Boolean = { true },
) : AccountDataResetManager {
    private val states get() = services.accountStateRepository
    private val lifecycle get() = services.workspaceLifecycleCoordinator
    private val reset get() = checkNotNull(services.accountResetService)
    private val discovery get() = checkNotNull(services.accountDiscoveryService)
    // All memory below is protected by the existing short product boundary.
    private val reviews = mutableListOf<AccountDataResetReview>()
    private var controlCredentials: SelfHostedSessionCredentials? = null
    private var observed: Pair<String, SelfHostedAccountDataStateResponse>? = null
    private var issue: AccountDataResetIssue? = null
    private var busy = false
    private var replacementDecision: Any? = null
    private var completedWorkspace: String? = null

    override fun load(): AccountDataResetSnapshot = lifecycle.productAccess { snapshotLocked() }

    override fun refresh(): AccountDataResetActionResult = operate {
        val review = load().review ?: fail(AccountDataResetIssue.SignInRequired)
        observe(review, credentials(review))
        result(true)
    }

    override fun submit(review: AccountDataResetReview, password: String): AccountDataResetActionResult = operate {
        require(password.isNotEmpty())
        val operation = lifecycle.exclusive {
            lifecycle.productAccess {
                validate(review)
                val pending = states.loadIntent()
                check(pending?.operationId == review.operationId)
                if (pending == null) {
                    check(observed?.first == review.authorityBindingId && observed?.second?.resetAvailable == true)
                    check(gate(review) == null)
                }
                (pending?.operationId ?: Uuid.random().toString()) to (pending?.expectedIncarnation ?: review.targetIncarnation)
            }
        }
        val outcome = try {
            reset.submit(operation.first, password, operation.second,
                review.authorityBindingId, review.localCopy(), credentials(review))
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is SelfHostedProtocolException || failure is ResetWorkflowFailure) throw failure
            fail(AccountDataResetIssue.LocalFailure)
        }
        when (outcome) {
            is SelfHostedAccountResetResult.Committed -> {
                if (!outcome.locallyRecorded) {
                    lifecycle.productAccess { issue = AccountDataResetIssue.LocalFailure }
                } else {
                    // Password is reused only by this immediate ordinary login,
                    // never retained in a field, settings, an intent or a retry job.
                    try { loginForControl(review, password); observe(review, credentials(review)) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        lifecycle.productAccess { issue = classify(failure) }
                    }
                }
                result(true)
            }
            is SelfHostedAccountResetResult.OutcomeUnknown -> result(false)
            is SelfHostedAccountResetResult.Rejected -> {
                lifecycle.productAccess { issue = classify(outcome.errorCode) }
                result(false)
            }
        }
    }

    override fun reauthenticate(review: AccountDataResetReview, password: String): AccountDataResetActionResult = operate {
        loginForControl(review, password)
        val pending = lifecycle.productAccess { states.loadIntent() }
        if (pending != null && pending.receiptIncarnation == null) {
            reset.reconcile(pending.operationId, credentials(review))
        }
        observe(review, credentials(review))
        result(true)
    }

    override fun reconcile(review: AccountDataResetReview): AccountDataResetActionResult = operate {
        lifecycle.productAccess { validate(review) }
        val pending = states.loadIntent() ?: fail(AccountDataResetIssue.ContextChanged)
        check(pending.operationId == review.operationId)
        val outcome = reset.reconcile(pending.operationId, credentials(review))
        observe(review, credentials(review))
        result(outcome is SelfHostedAccountResetResult.Committed)
    }

    // This escape is deliberately available while reset/status HTTP is in flight.
    override fun keepOffline(review: AccountDataResetReview): AccountDataResetActionResult = guarded {
        lifecycle.exclusive {
            lifecycle.productAccess {
                validate(review)
                val authority = review.authority()
                states.enterOfflineMode(authority.endpoint, authority.authenticatedUserId, review.workspaceId)
                replacementDecision = null
                issue = null
            }
        }
        result(true)
    }

    override fun replaceLocal(
        review: AccountDataResetReview,
        mode: AccountDataReplacementMode,
        discardConfirmed: Boolean,
        secret: String,
        password: String?,
    ): AccountDataResetActionResult = operate {
        if (!discardConfirmed) fail(AccountDataResetIssue.ConfirmationRequired)
        if (mode != AccountDataReplacementMode.Fresh && secret.isBlank()) fail(AccountDataResetIssue.InvalidSecret)
        val decision = lifecycle.exclusive {
            lifecycle.productAccess {
                validate(review)
                val pending = states.loadIntent()
                if (pending != null && pending.receiptIncarnation == null) fail(AccountDataResetIssue.Conflict)
                val authority = review.authority()
                states.freezeForRejoin(authority.endpoint, authority.authenticatedUserId, review.workspaceId, review.localIncarnation)
                Any().also { replacementDecision = it }
            }
        }
        if (!password.isNullOrEmpty()) loginForControl(review, password)
        val auth = credentials(review)
        val current = observe(review, auth)
        if (current.accountIncarnation != review.targetIncarnation || auth.accountIncarnation != review.targetIncarnation) {
            fail(AccountDataResetIssue.ContextChanged)
        }
        lifecycle.exclusive {
            lifecycle.productAccess {
                requireDecision(review, decision)
                val authority = review.authority()
                states.recordRejoinConsent(authority.endpoint, authority.authenticatedUserId, review.workspaceId,
                    review.localIncarnation, review.targetIncarnation)
            }
            val device = transport.registerDevice(review.endpoint, auth.accessToken,
                SelfHostedDeviceRegistrationRequest(review.writerDeviceId, deviceName, platform),
                SelfHostedAccountRequestContext(review.targetIncarnation, true))
            if (device.device.revoked) fail(AccountDataResetIssue.DeviceRevoked)
            if (device.device.id != review.writerDeviceId || device.accountProtocolVersion != 1 ||
                device.accountIncarnation != review.targetIncarnation) fail(AccountDataResetIssue.ProtocolError)
            val enrolled = auth.copy(deviceId = device.device.id, deviceName = device.device.name,
                devicePlatform = device.device.platform, accessToken = device.accessToken, refreshToken = device.refreshToken)
            lifecycle.productAccess {
                requireDecision(review, decision)
                // The durable gate was written first. Secure storage failure can
                // never expose old data through the newly enrolled device.
                sessionStore.saveForAuthority(review.authorityBindingId, enrolled)
                sessionStore.save(enrolled)
                controlCredentials = null
            }
        }
        val replaced = when (mode) {
            AccountDataReplacementMode.Fresh -> replaceFresh(review, decision)
            AccountDataReplacementMode.Pair -> {
                lifecycle.productAccess { requireDecision(review, decision) }
                val joined = pairing.joinWithToken(secret, replaceExistingWorkspace = true)
                if (!joined.success) fail(when (joined.reason) {
                    WorkspacePairingReason.InvalidToken -> AccountDataResetIssue.InvalidSecret
                    WorkspacePairingReason.AuthorityMismatch -> AccountDataResetIssue.ContextChanged
                    else -> AccountDataResetIssue.ReplacementFailed
                })
                true
            }
            AccountDataReplacementMode.Recover -> {
                lifecycle.productAccess { requireDecision(review, decision) }
                val restored = recovery.recover(secret, replaceExistingWorkspace = true)
                if (!restored.success) fail(when (restored.reason) {
                    WorkspaceRecoveryReason.InvalidCodeFormat, WorkspaceRecoveryReason.DecryptionFailed -> AccountDataResetIssue.InvalidSecret
                    WorkspaceRecoveryReason.NotConfigured -> AccountDataResetIssue.NoRecoveryEnvelope
                    WorkspaceRecoveryReason.AuthorityMismatch -> AccountDataResetIssue.ContextChanged
                    else -> AccountDataResetIssue.ReplacementFailed
                })
                true
            }
        }
        lifecycle.productAccess {
            replacementDecision = null
            reviews.clear()
            issue = null
            completedWorkspace = workspaceIdProvider()
        }
        result(true, localReplaced = replaced)
    }

    override fun cancel() = lifecycle.productAccess {
        controlCredentials = null
        replacementDecision = null
        reviews.clear()
        Unit
    }

    private fun replaceFresh(review: AccountDataResetReview, decision: Any): Boolean = lifecycle.exclusive {
        val enrolled = checkNotNull(sessionStore.load())
        val previous = services.activeWorkspaceSessionGuard.currentRequirement()
        services.activeWorkspaceSessionGuard.requireCapturedReplacement(enrolled, previous,
            review.workspaceId to review.localIncarnation)
        lifecycle.productAccess {
            requireDecision(review, decision)
            freshWorkspaceReplacement(
                { check(services.discardLocalWorkspaceForReplacement()) },
                { key, workspace ->
                    check(services.bindFreshWorkspaceAfterAccountReset(key, workspace, WorkspaceJoinAuthorityCapture(
                        review.authorityBindingId, review.writerDeviceId, review.targetIncarnation,
                        review.workspaceId, review.localIncarnation)))
                },
            )
        }
        services.finalizeLocalWorkspaceReplacement()
        true
    }

    private fun loginForControl(review: AccountDataResetReview, password: String) {
        lifecycle.productAccess { validate(review) }
        if (password.isEmpty()) fail(AccountDataResetIssue.WrongPassword)
        val auth = try {
            transport.login(review.endpoint, SelfHostedAuthRequest(review.accountEmail, password),
                SelfHostedAccountRequestContext(review.localIncarnation, true))
        } catch (failure: SelfHostedSyncHttpException) {
            // This review already identifies a previously authenticated account, unlike an
            // initial email-only login. Valid typed metadata is durable capability evidence.
            if (failure.protocol1) states.markProtocol1(review.endpoint, review.authority().authenticatedUserId)
            throw failure
        }
        if (auth.user.id != review.authority().authenticatedUserId || auth.accountProtocolVersion != 1 ||
            auth.accountIncarnation == null || !isCanonicalAccountIncarnation(auth.accountIncarnation)) {
            fail(AccountDataResetIssue.ProtocolError)
        }
        val credentials = SelfHostedSessionCredentials(review.endpoint, auth.user.id, auth.user.email,
            review.writerDeviceId, deviceName, platform, auth.accessToken, auth.refreshToken, auth.accountIncarnation, 1)
        lifecycle.productAccess {
            validate(review)
            discovery.recordIssuance(credentials)
            controlCredentials = credentials
        }
    }

    private fun credentials(review: AccountDataResetReview): SelfHostedSessionCredentials = lifecycle.productAccess {
        validate(review)
        controlCredentials?.takeIf { it.authorityBindingId == review.authorityBindingId }
            ?: sessionStore.load()?.takeIf { it.authorityBindingId == review.authorityBindingId }
            ?: sessionStore.loadForAuthority(review.authorityBindingId)
            ?: fail(AccountDataResetIssue.SignInRequired)
    }

    private fun observe(review: AccountDataResetReview, credentials: SelfHostedSessionCredentials): SelfHostedAccountDataStateResponse {
        val remote = discovery.discover(credentials) as? SelfHostedAccountDiscoveryResult.Protocol1
            ?: fail(AccountDataResetIssue.UnsupportedServer)
        lifecycle.productAccess {
            validate(review)
            observed = review.authorityBindingId to remote.state
            if (review.localAuthorityBindingId != null && remote.state.accountIncarnation != review.localIncarnation) {
                val authority = review.authority()
                states.markResetRequired(authority.endpoint, authority.authenticatedUserId, review.workspaceId, review.localIncarnation)
            }
        }
        return remote.state
    }

    private fun requireDecision(review: AccountDataResetReview, decision: Any) {
        validate(review)
        if (replacementDecision !== decision || gate(review)?.offlineEditing == true) fail(AccountDataResetIssue.ContextChanged)
    }

    private fun validate(review: AccountDataResetReview) {
        if (review !in reviews || reset.localWorkspace() != review.localCopy() ||
            localDeviceIdProvider() != review.writerDeviceId) fail(AccountDataResetIssue.ContextChanged)
        sessionStore.load()?.let {
            if (it.authorityBindingId != review.authorityBindingId) fail(AccountDataResetIssue.ContextChanged)
        }
    }

    private fun gate(review: AccountDataResetReview) = review.authority().let {
        states.loadGate(it.endpoint, it.authenticatedUserId, review.workspaceId)
    }

    private fun snapshotLocked(): AccountDataResetSnapshot {
        if (services.accountResetService == null || services.accountDiscoveryService == null) return AccountDataResetSnapshot()
        val copy = reset.localWorkspace()
        val pending = states.loadIntent()
        if (pending != null && pending.originalWorkspaceId != copy.workspaceId) {
            // A valid replacement reconciles the intent in the same transaction. Never offer
            // a new reset or discard decision for a corrupted, unrelated installation copy.
            return AccountDataResetSnapshot(phase = AccountDataResetPhase.ResetRequired,
                productReadOnly = true, canExport = runCatching(canExportProvider).getOrDefault(false),
                issue = AccountDataResetIssue.LocalFailure)
        }
        val selected = sessionStore.load()
        val authorityId = pending?.let { selfHostedAuthorityBindingId(it.endpoint, it.userId) }
            ?: copy.authorityBindingId ?: selected?.authorityBindingId ?: return AccountDataResetSnapshot()
        val authority = parseSelfHostedAuthorityBindingId(authorityId) ?: return AccountDataResetSnapshot()
        val stored = selected?.takeIf { it.authorityBindingId == authorityId } ?: sessionStore.loadForAuthority(authorityId)
        val auth = controlCredentials?.takeIf { it.authorityBindingId == authorityId } ?: stored
        val email = auth?.userEmail ?: settingsRepository.load().syncConfiguration.selfHostedSession.userEmail
            ?: return AccountDataResetSnapshot(issue = AccountDataResetIssue.SignInRequired)
        if (copy.authorityBindingId != null && auth != null && auth.accountIncarnation != copy.accountIncarnation) {
            states.markResetRequired(authority.endpoint, authority.authenticatedUserId, copy.workspaceId, copy.accountIncarnation)
        }
        val gate = states.loadGate(authority.endpoint, authority.authenticatedUserId, copy.workspaceId)
        val remote = observed?.takeIf { it.first == authorityId }?.second
        val target = remote?.accountIncarnation ?: pending?.receiptIncarnation ?: auth?.accountIncarnation ?: copy.accountIncarnation
        val template = AccountDataResetReview("", authorityId, authority.endpoint, email, copy.workspaceId, copy.writerDeviceId,
            copy.accountIncarnation, copy.authorityBindingId, target, pending?.operationId)
        val review = reviews.lastOrNull()?.takeIf { it.copy(id = "") == template } ?: template.copy(id = Uuid.random().toString()).also {
            if (reviews.size >= 16) reviews.removeAt(0)
            reviews += it
        }
        val phase = when {
            pending != null -> if (pending.state == AccountResetIntentState.RemoteCommittedLocalPending) AccountDataResetPhase.RemoteCommittedLocalPending else AccountDataResetPhase.OutcomeUnknown
            gate != null -> AccountDataResetPhase.ResetRequired
            completedWorkspace == copy.workspaceId -> AccountDataResetPhase.LocalReady
            else -> AccountDataResetPhase.Ready
        }
        val availabilityIssue = when {
            auth == null -> AccountDataResetIssue.SignInRequired
            remote == null -> null
            remote.resetAvailable -> null
            remote.resetUnavailableReason == "retired_media_pending" -> AccountDataResetIssue.RetiredMediaPending
            else -> AccountDataResetIssue.DeploymentNotReady
        }
        return AccountDataResetSnapshot(phase, review, authority.endpoint, email, pending?.operationId,
            gate?.offlineEditing == true, gate?.productReadOnly == true,
            remote?.resetAvailable == true && pending == null && gate == null,
            runCatching(canExportProvider).getOrDefault(false),
            gate != null && (pending == null || pending.receiptIncarnation != null) && remote != null,
            issue ?: availabilityIssue)
    }

    private fun operate(block: () -> AccountDataResetActionResult): AccountDataResetActionResult {
        val acquired = lifecycle.productAccess {
            if (busy) false else { busy = true; issue = null; true }
        }
        if (!acquired) return AccountDataResetActionResult(load(), false, issue = AccountDataResetIssue.Busy)
        return try { guarded(block) } finally { lifecycle.productAccess { busy = false } }
    }

    private fun guarded(block: () -> AccountDataResetActionResult): AccountDataResetActionResult {
        val capturedRequirement = lifecycle.productAccess { services.activeWorkspaceSessionGuard.currentRequirement() }
        return try { block() }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            lifecycle.productAccess {
                // Control HTTP releases the lifecycle boundary. Another workflow may
                // install a different local copy before this response reaches us.
                val sameWorkspace = services.activeWorkspaceSessionGuard.currentRequirement() == capturedRequirement
                issue = if (sameWorkspace) classify(failure) else AccountDataResetIssue.ContextChanged
                if (sameWorkspace && failure is SelfHostedSyncHttpException && failure.errorCode in setOf(
                        SelfHostedErrorCode.ACCOUNT_SESSION_STALE, SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH,
                        SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED)) {
                    services.activeWorkspaceSessionGuard.markCurrentIncarnationMismatch()
                }
            }
            result(false)
        }
    }

    private fun result(success: Boolean, localReplaced: Boolean = false) = AccountDataResetActionResult(load(), success, localReplaced)
    private fun AccountDataResetReview.authority() = checkNotNull(parseSelfHostedAuthorityBindingId(authorityBindingId))
    private fun AccountDataResetReview.localCopy() = AccountResetLocalWorkspace(workspaceId, writerDeviceId, localAuthorityBindingId, localIncarnation)
    private fun fail(reason: AccountDataResetIssue): Nothing = throw ResetWorkflowFailure(reason)
    private class ResetWorkflowFailure(val reason: AccountDataResetIssue) : IllegalStateException("Account reset action requires attention.")
    private fun classify(failure: Exception): AccountDataResetIssue = when (failure) {
        is ResetWorkflowFailure -> failure.reason
        is SelfHostedSyncHttpException -> classify(failure.errorCode)
        is SelfHostedProtocolException -> AccountDataResetIssue.ProtocolError
        is IllegalArgumentException -> AccountDataResetIssue.ConfirmationRequired
        is IllegalStateException -> AccountDataResetIssue.LocalFailure
        else -> AccountDataResetIssue.NetworkError
    }
    private fun classify(code: SelfHostedErrorCode?): AccountDataResetIssue = when (code) {
        SelfHostedErrorCode.INVALID_CREDENTIALS -> AccountDataResetIssue.WrongPassword
        SelfHostedErrorCode.UNAUTHORIZED, SelfHostedErrorCode.ACCOUNT_SESSION_STALE -> AccountDataResetIssue.SignInRequired
        SelfHostedErrorCode.DEVICE_REVOKED -> AccountDataResetIssue.DeviceRevoked
        SelfHostedErrorCode.ACCOUNT_BUSY, SelfHostedErrorCode.AUTHENTICATION_BUSY -> AccountDataResetIssue.Busy
        SelfHostedErrorCode.RATE_LIMITED, SelfHostedErrorCode.RESET_RATE_LIMITED -> AccountDataResetIssue.RateLimited
        SelfHostedErrorCode.ACCOUNT_INCARNATION_MISMATCH, SelfHostedErrorCode.WORKSPACE_INCARNATION_RETIRED -> AccountDataResetIssue.ContextChanged
        SelfHostedErrorCode.RESET_REQUEST_CONFLICT -> AccountDataResetIssue.Conflict
        SelfHostedErrorCode.RETIRED_MEDIA_PENDING -> AccountDataResetIssue.RetiredMediaPending
        SelfHostedErrorCode.ACCOUNT_RESET_UNAVAILABLE -> AccountDataResetIssue.DeploymentNotReady
        SelfHostedErrorCode.ACCOUNT_PROTOCOL_UPGRADE_REQUIRED -> AccountDataResetIssue.UnsupportedServer
        else -> AccountDataResetIssue.NetworkError
    }
}
