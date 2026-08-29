@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.selfhosted

import kotlin.time.Clock
import kotlin.time.Instant
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.normalizeSelfHostedEndpoint
import saien.someday.domain.settings.WorkspaceJoinPackageProvider
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspaceJoiner
import saien.someday.domain.settings.WorkspacePairingInvitation
import saien.someday.domain.settings.WorkspacePairingInvitationCanceller
import saien.someday.domain.settings.WorkspacePairingInvitationCreator
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.domain.settings.WorkspacePairingInvitationResult
import saien.someday.domain.settings.WorkspacePairingReason
import saien.someday.sync.pairing.WorkspacePairingAuthority
import saien.someday.sync.pairing.WorkspacePairingEnvelopeCodec
import saien.someday.sync.pairing.WorkspacePairingEnvelopeDecodeResult
import saien.someday.sync.pairing.WorkspacePairingToken
import saien.someday.sync.pairing.base64UrlNoPadding
import saien.someday.sync.WorkspaceLifecycleCoordinator

class SelfHostedWorkspacePairingService(
    private val settingsProvider: () -> ClientSettings,
    private val sessionStore: SelfHostedSessionCredentialStore,
    private val transport: SelfHostedSyncTransport,
    private val sessionExecutor: RefreshingSelfHostedSessionExecutor,
    private val workspaceJoinPackageProvider: WorkspaceJoinPackageProvider,
    private val workspaceJoiner: WorkspaceJoiner,
    private val workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator,
    private val activeWorkspaceSessionGuard: ActiveWorkspaceSessionGuard,
    private val workspacePairingInviterReady: () -> Boolean,
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
    private val clock: () -> Instant = { Clock.System.now() },
) : WorkspacePairingInvitationCreator,
    WorkspacePairingInvitationJoiner,
    WorkspacePairingInvitationCanceller {
    private val envelopeCodec = WorkspacePairingEnvelopeCodec(crypto)

    override fun createInvitation(): WorkspacePairingInvitationResult =
        runCatching {
            workspaceLifecycleCoordinator.exclusive { createInvitationLocked() }
        }.getOrElse { error ->
            WorkspacePairingInvitationResult.failure(
                reason = WorkspacePairingReason.Failed,
                diagnosticMessage = "Workspace pairing failed (${error.safePairingFailureDetail()}).",
            )
        }

    private fun createInvitationLocked(): WorkspacePairingInvitationResult {
        if (!workspacePairingInviterReady()) {
            return WorkspacePairingInvitationResult.failure(
                WorkspacePairingReason.PublishRequired,
            )
        }
        val session = when (val sessionResult = requireSession()) {
            is PairingSessionResult.Ready -> sessionResult.session
            is PairingSessionResult.Failed ->
                return WorkspacePairingInvitationResult.failure(sessionResult.reason)
        }
        val packageResult = workspaceJoinPackageProvider.createPackage()
        val packageData = packageResult.packageData
            ?: return WorkspacePairingInvitationResult.failure(
                packageResult.reason,
                packageResult.diagnosticMessage,
            )
        val authority = authority(session)

        repeat(MAX_CREATE_ATTEMPTS) {
            val token = WorkspacePairingToken.generate(crypto)
            val nowMillis = clock().toEpochMilliseconds()
            val requestedExpiry = nowMillis + WorkspacePairingEnvelopeCodec.MAX_TTL_MILLIS
            val encoded = envelopeCodec.encode(
                token = token,
                authority = authority,
                createdAtEpochMillis = nowMillis,
                expiresAtEpochMillis = requestedExpiry,
                packageData = packageData,
            )
            val response = try {
                sessionExecutor.authorized(session.endpoint, session.userId, session.accessToken) { accessToken ->
                    transport.createPairingInvite(
                        endpoint = session.endpoint,
                        accessToken = accessToken,
                        inviteId = encoded.inviteId,
                        request = SelfHostedPairingInviteCreateRequest(
                            envelopeJson = encoded.bytes.decodeToString(),
                            envelopeDigest = encoded.digest,
                            expiresAtEpochMillis = requestedExpiry,
                        ),
                    )
                }
            } catch (error: SelfHostedSyncHttpException) {
                if (error.status == 409) return@repeat
                return WorkspacePairingInvitationResult.failure(
                    reason = WorkspacePairingReason.ServerRequestFailed,
                    diagnosticMessage = error.safePairingFailureDetail(),
                )
            } catch (error: Throwable) {
                return WorkspacePairingInvitationResult.failure(
                    reason = WorkspacePairingReason.ServerRequestFailed,
                    diagnosticMessage = error.safePairingFailureDetail(),
                )
            }
            return WorkspacePairingInvitationResult.success(
                reason = WorkspacePairingReason.InvitationCreated,
                invitation = WorkspacePairingInvitation.create(
                    manualToken = token.formattedManualToken(),
                    qrPayload = token.qrPayload(),
                    expiresAtEpochMillis = response.expiresAtEpochMillis,
                ),
            )
        }
        return WorkspacePairingInvitationResult.failure(WorkspacePairingReason.Failed)
    }

    override fun joinWithToken(
        tokenInput: String,
        replaceExistingWorkspace: Boolean,
    ): WorkspaceJoinResult =
        runCatching {
            if (!replaceExistingWorkspace) {
                return WorkspaceJoinResult.failure(
                    WorkspacePairingReason.ReplacementConfirmationRequired,
                )
            }
            val token = WorkspacePairingToken.parse(tokenInput)
                ?: return WorkspaceJoinResult.failure(WorkspacePairingReason.InvalidToken)
            workspaceLifecycleCoordinator.exclusive {
                joinWithTokenLocked(token)
            }
        }.getOrElse { error ->
            WorkspaceJoinResult.failure(
                reason = WorkspacePairingReason.Failed,
                diagnosticMessage = "Workspace pairing failed (${error.safePairingFailureDetail()}).",
            )
        }

    private fun joinWithTokenLocked(token: WorkspacePairingToken): WorkspaceJoinResult {
        val session = when (val sessionResult = requireSession()) {
            is PairingSessionResult.Ready -> sessionResult.session
            is PairingSessionResult.Failed -> return WorkspaceJoinResult.failure(sessionResult.reason)
        }
        val material = token.deriveMaterial()
        val claimId = base64UrlNoPadding(crypto.randomBytes(CLAIM_ID_BYTES))
        val claimed = try {
            sessionExecutor.authorized(session.endpoint, session.userId, session.accessToken) { accessToken ->
                transport.claimPairingInvite(
                    endpoint = session.endpoint,
                    accessToken = accessToken,
                    inviteId = material.inviteId,
                    request = SelfHostedPairingInviteClaimRequest(claimId),
                )
            }
        } catch (error: SelfHostedSyncHttpException) {
            return when (error.status) {
                404 -> WorkspaceJoinResult.failure(WorkspacePairingReason.InvitationNotFound)
                409 -> WorkspaceJoinResult.failure(WorkspacePairingReason.InvitationAlreadyUsed)
                410 -> WorkspaceJoinResult.failure(WorkspacePairingReason.InvitationExpired)
                else -> WorkspaceJoinResult.failure(
                    WorkspacePairingReason.ServerRequestFailed,
                    error.safeMessage,
                )
            }
        } catch (error: Throwable) {
            return WorkspaceJoinResult.failure(
                WorkspacePairingReason.ServerRequestFailed,
                error.safePairingFailureDetail(),
            )
        }
        try {
            val bytes = claimed.envelopeJson.encodeToByteArray()
            if (WorkspacePairingEnvelopeCodec.digest(bytes) != claimed.envelopeDigest) {
                return WorkspaceJoinResult.failure(WorkspacePairingReason.VerificationFailed)
            }
            if (clock().toEpochMilliseconds() > claimed.expiresAtEpochMillis) {
                return WorkspaceJoinResult.failure(WorkspacePairingReason.InvitationExpired)
            }
            val decoded = when (
                val result = envelopeCodec.decode(
                    token = token,
                    authority = authority(session),
                    envelopeBytes = bytes,
                    nowEpochMillis = clock().toEpochMilliseconds(),
                )
            ) {
                is WorkspacePairingEnvelopeDecodeResult.Success -> result
                WorkspacePairingEnvelopeDecodeResult.Expired ->
                    return WorkspaceJoinResult.failure(WorkspacePairingReason.InvitationExpired)
                WorkspacePairingEnvelopeDecodeResult.Invalid ->
                    return WorkspaceJoinResult.failure(WorkspacePairingReason.VerificationFailed)
            }
            return workspaceLifecycleCoordinator.productAccess {
                workspaceJoiner.join(
                    packageData = decoded.packageData,
                    replaceExistingWorkspace = true,
                )
            }
        } finally {
            runCatching {
                sessionExecutor.authorized(session.endpoint, session.userId, session.accessToken) { accessToken ->
                    transport.completePairingInvite(
                        endpoint = session.endpoint,
                        accessToken = accessToken,
                        inviteId = material.inviteId,
                        request = SelfHostedPairingInviteCompleteRequest(claimId),
                    )
                }
            }
        }
    }

    override fun cancelInvitation(invitation: WorkspacePairingInvitation): WorkspaceJoinResult =
        runCatching {
            val token = WorkspacePairingToken.parse(invitation.revealManualToken())
                ?: return WorkspaceJoinResult.failure(WorkspacePairingReason.InvalidToken)
            val session = when (val sessionResult = requireSession()) {
                is PairingSessionResult.Ready -> sessionResult.session
                is PairingSessionResult.Failed -> return WorkspaceJoinResult.failure(sessionResult.reason)
            }
            try {
                sessionExecutor.authorized(session.endpoint, session.userId, session.accessToken) { accessToken ->
                    transport.cancelPairingInvite(
                        endpoint = session.endpoint,
                        accessToken = accessToken,
                        inviteId = token.deriveMaterial().inviteId,
                    )
                }
                WorkspaceJoinResult.success(WorkspacePairingReason.InvitationCancelled)
            } catch (error: SelfHostedSyncHttpException) {
                when (error.status) {
                    404, 410 -> WorkspaceJoinResult.success(WorkspacePairingReason.InvitationUnavailable)
                    409 -> WorkspaceJoinResult.failure(WorkspacePairingReason.InvitationAlreadyUsed)
                    else -> WorkspaceJoinResult.failure(
                        WorkspacePairingReason.ServerRequestFailed,
                        error.safeMessage,
                    )
                }
            } catch (error: Throwable) {
                WorkspaceJoinResult.failure(
                    WorkspacePairingReason.ServerRequestFailed,
                    error.safePairingFailureDetail(),
                )
            }
        }.getOrElse { error ->
            WorkspaceJoinResult.failure(
                WorkspacePairingReason.Failed,
                error.safePairingFailureDetail(),
            )
        }

    private fun requireSession(): PairingSessionResult {
        val sync = settingsProvider().syncConfiguration
        if (sync.mode != SyncMode.SelfHosted) {
            return PairingSessionResult.Failed(WorkspacePairingReason.SessionRequired)
        }
        if (!sync.selfHostedSession.loggedIn) {
            return PairingSessionResult.Failed(WorkspacePairingReason.SessionRequired)
        }
        val credentials = sessionStore.load()
            ?: return PairingSessionResult.Failed(WorkspacePairingReason.SessionRequired)
        if (!activeWorkspaceSessionGuard.isCompatible(credentials)) {
            return PairingSessionResult.Failed(WorkspacePairingReason.AuthorityMismatch)
        }
        return PairingSessionResult.Ready(SelfHostedSyncSession.fromCredentials(credentials))
    }

    private fun authority(session: SelfHostedSyncSession): WorkspacePairingAuthority =
        WorkspacePairingAuthority(
            binding = canonicalAuthorityBinding(normalizeSelfHostedEndpoint(session.endpoint), session.userId),
        )

    private companion object {
        const val MAX_CREATE_ATTEMPTS: Int = 3
        const val CLAIM_ID_BYTES: Int = 16
    }
}

private sealed interface PairingSessionResult {
    data class Ready(val session: SelfHostedSyncSession) : PairingSessionResult
    data class Failed(val reason: WorkspacePairingReason) : PairingSessionResult
}

private fun canonicalAuthorityBinding(vararg parts: String): String =
    parts.joinToString(separator = "|") { part -> "${part.encodeToByteArray().size}:$part" }

private fun Throwable.safePairingFailureDetail(): String =
    if (this is SelfHostedSyncHttpException) {
        "server request rejected with HTTP $status; details redacted"
    } else {
        "${this::class.simpleName ?: "unknown error"}; details redacted"
    }
