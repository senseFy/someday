@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.selfhosted

import kotlin.time.Clock
import kotlin.time.Instant
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceJoinPackageProvider
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspaceJoiner
import saien.someday.domain.settings.WorkspacePairingInvitation
import saien.someday.domain.settings.WorkspacePairingInvitationCanceller
import saien.someday.domain.settings.WorkspacePairingInvitationCreator
import saien.someday.domain.settings.WorkspacePairingInvitationJoiner
import saien.someday.domain.settings.WorkspacePairingInvitationResult
import saien.someday.sync.pairing.WorkspacePairingAuthority
import saien.someday.sync.pairing.WorkspacePairingEnvelopeCodec
import saien.someday.sync.pairing.WorkspacePairingEnvelopeDecodeResult
import saien.someday.sync.pairing.WorkspacePairingRemoteProfile
import saien.someday.sync.pairing.WorkspacePairingToken
import saien.someday.sync.pairing.base64UrlNoPadding
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator

class SelfHostedWorkspacePairingService(
    private val settingsProvider: () -> ClientSettings,
    private val sessionStore: SelfHostedSessionCredentialStore,
    private val transport: SelfHostedSyncTransport,
    private val sessionExecutor: RefreshingSelfHostedSessionExecutor,
    private val workspaceJoinPackageProvider: WorkspaceJoinPackageProvider,
    private val workspaceJoiner: WorkspaceJoiner,
    private val localV2KeyBoundStatePresent: () -> Boolean,
    private val authorityMutationCoordinator: WorkspaceAuthorityMutationCoordinator,
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
    private val clock: () -> Instant = { Clock.System.now() },
) : WorkspacePairingInvitationCreator,
    WorkspacePairingInvitationJoiner,
    WorkspacePairingInvitationCanceller {
    private val envelopeCodec = WorkspacePairingEnvelopeCodec(crypto)

    override fun createInvitation(): WorkspacePairingInvitationResult =
        runCatching {
            val session = requireSession().getOrElse {
                return WorkspacePairingInvitationResult.failure(it.message ?: "Self-hosted session is missing.")
            }
            val packageResult = workspaceJoinPackageProvider.createPackage()
            val packageData = packageResult.packageData
                ?: return WorkspacePairingInvitationResult.failure(packageResult.message)
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
                    sessionExecutor.authorized(session.endpoint, session.accessToken) { accessToken ->
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
                    throw error
                }
                return WorkspacePairingInvitationResult.success(
                    message = "Pairing invitation created. Scan the QR code or enter the token before it expires.",
                    invitation = WorkspacePairingInvitation.create(
                        manualToken = token.formattedManualToken(),
                        qrPayload = token.qrPayload(),
                        expiresAtEpochMillis = response.expiresAtEpochMillis,
                    ),
                )
            }
            WorkspacePairingInvitationResult.failure("Could not create a unique pairing invitation. Try again.")
        }.getOrElse { error ->
            WorkspacePairingInvitationResult.failure(
                "Workspace pairing failed (${error.safePairingFailureDetail()}).",
            )
        }

    override fun joinWithToken(tokenInput: String): WorkspaceJoinResult =
        runCatching {
            val token = WorkspacePairingToken.parse(tokenInput)
                ?: return WorkspaceJoinResult.failure("Enter a valid pairing token or scan its QR code.")
            if (localV2KeyBoundStatePresent()) {
                return WorkspaceJoinResult.failure(localHistoryRefusalMessage())
            }
            authorityMutationCoordinator.exclusive { joinWithTokenLocked(token) }
        }.getOrElse {
            WorkspaceJoinResult.failure("Workspace pairing failed; credentials and secrets redacted.")
        }

    private fun joinWithTokenLocked(token: WorkspacePairingToken): WorkspaceJoinResult {
        if (localV2KeyBoundStatePresent()) {
            return WorkspaceJoinResult.failure(localHistoryRefusalMessage())
        }
        val session = requireSession().getOrElse {
            return WorkspaceJoinResult.failure(it.message ?: "Self-hosted session is missing.")
        }
        val material = token.deriveMaterial()
        val claimId = base64UrlNoPadding(crypto.randomBytes(CLAIM_ID_BYTES))
        val claimed = try {
            sessionExecutor.authorized(session.endpoint, session.accessToken) { accessToken ->
                transport.claimPairingInvite(
                    endpoint = session.endpoint,
                    accessToken = accessToken,
                    inviteId = material.inviteId,
                    request = SelfHostedPairingInviteClaimRequest(claimId),
                )
            }
        } catch (error: SelfHostedSyncHttpException) {
            return when (error.status) {
                404 -> WorkspaceJoinResult.failure("Pairing invitation was not found.")
                409 -> WorkspaceJoinResult.failure("Pairing invitation was already claimed or cancelled.")
                410 -> WorkspaceJoinResult.failure("Pairing invitation has expired. Generate a new one and try again.")
                else -> WorkspaceJoinResult.failure("Workspace pairing failed; credentials and secrets redacted.")
            }
        }
        try {
            val bytes = claimed.envelopeJson.encodeToByteArray()
            if (WorkspacePairingEnvelopeCodec.digest(bytes) != claimed.envelopeDigest) {
                return WorkspaceJoinResult.failure("Pairing invitation ciphertext failed its server digest check.")
            }
            if (clock().toEpochMilliseconds() > claimed.expiresAtEpochMillis) {
                return WorkspaceJoinResult.failure("Pairing invitation has expired. Generate a new one and try again.")
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
                    return WorkspaceJoinResult.failure("Pairing invitation has expired. Generate a new one and try again.")
                WorkspacePairingEnvelopeDecodeResult.Invalid ->
                    return WorkspaceJoinResult.failure(
                        "Pairing invitation could not be verified for this self-hosted account.",
                    )
            }
            return workspaceJoiner.join(decoded.packageData)
        } finally {
            runCatching {
                sessionExecutor.authorized(session.endpoint, session.accessToken) { accessToken ->
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
                ?: return WorkspaceJoinResult.failure("The local pairing invitation is invalid.")
            val session = requireSession().getOrElse {
                return WorkspaceJoinResult.failure(it.message ?: "Self-hosted session is missing.")
            }
            try {
                sessionExecutor.authorized(session.endpoint, session.accessToken) { accessToken ->
                    transport.cancelPairingInvite(
                        endpoint = session.endpoint,
                        accessToken = accessToken,
                        inviteId = token.deriveMaterial().inviteId,
                    )
                }
                WorkspaceJoinResult.success("Pairing invitation cancelled.")
            } catch (error: SelfHostedSyncHttpException) {
                when (error.status) {
                    404, 410 -> WorkspaceJoinResult.success("Pairing invitation is no longer available.")
                    409 -> WorkspaceJoinResult.failure(
                        "Pairing invitation was already claimed and cannot be cancelled.",
                    )
                    else -> WorkspaceJoinResult.failure(
                        "Could not cancel pairing invitation; credentials and secrets redacted.",
                    )
                }
            }
        }.getOrElse {
            WorkspaceJoinResult.failure("Could not cancel pairing invitation; credentials and secrets redacted.")
        }

    private fun requireSession(): Result<SelfHostedSyncSession> {
        val sync = settingsProvider().syncConfiguration
        if (sync.mode != SyncMode.SelfHosted) {
            return Result.failure(IllegalStateException("Select Self-hosted mode and sign in before pairing devices."))
        }
        if (!sync.selfHostedSession.loggedIn) {
            return Result.failure(IllegalStateException("Sign in to the self-hosted server before pairing devices."))
        }
        val credentials = sessionStore.load()
            ?: return Result.failure(IllegalStateException("Self-hosted session is missing; tokens redacted."))
        return Result.success(SelfHostedSyncSession.fromCredentials(credentials))
    }

    private fun authority(session: SelfHostedSyncSession): WorkspacePairingAuthority =
        WorkspacePairingAuthority(
            remoteProfile = WorkspacePairingRemoteProfile.SelfHosted,
            binding = canonicalAuthorityBinding(session.endpoint.trimEnd('/'), session.userId),
        )

    private companion object {
        const val MAX_CREATE_ATTEMPTS: Int = 3
        const val CLAIM_ID_BYTES: Int = 16
    }
}

class ModeRoutingWorkspacePairingService(
    private val settingsProvider: () -> ClientSettings,
    private val webDavCreator: WorkspacePairingInvitationCreator,
    private val webDavJoiner: WorkspacePairingInvitationJoiner,
    private val webDavCanceller: WorkspacePairingInvitationCanceller,
    private val selfHostedCreator: WorkspacePairingInvitationCreator,
    private val selfHostedJoiner: WorkspacePairingInvitationJoiner,
    private val selfHostedCanceller: WorkspacePairingInvitationCanceller,
) : WorkspacePairingInvitationCreator,
    WorkspacePairingInvitationJoiner,
    WorkspacePairingInvitationCanceller {
    override fun createInvitation(): WorkspacePairingInvitationResult =
        when (settingsProvider().syncConfiguration.mode) {
            SyncMode.WebDav -> webDavCreator.createInvitation()
            SyncMode.SelfHosted -> selfHostedCreator.createInvitation()
            SyncMode.Off -> WorkspacePairingInvitationResult.failure(
                "Choose WebDAV or Self-hosted mode before pairing devices.",
            )
        }

    override fun joinWithToken(tokenInput: String): WorkspaceJoinResult =
        when (settingsProvider().syncConfiguration.mode) {
            SyncMode.WebDav -> webDavJoiner.joinWithToken(tokenInput)
            SyncMode.SelfHosted -> selfHostedJoiner.joinWithToken(tokenInput)
            SyncMode.Off -> WorkspaceJoinResult.failure(
                "Choose WebDAV or Self-hosted mode before pairing devices.",
            )
        }

    override fun cancelInvitation(invitation: WorkspacePairingInvitation): WorkspaceJoinResult =
        when (settingsProvider().syncConfiguration.mode) {
            SyncMode.WebDav -> webDavCanceller.cancelInvitation(invitation)
            SyncMode.SelfHosted -> selfHostedCanceller.cancelInvitation(invitation)
            SyncMode.Off -> WorkspaceJoinResult.failure(
                "Choose WebDAV or Self-hosted mode before cancelling an invitation.",
            )
        }
}

private fun canonicalAuthorityBinding(vararg parts: String): String =
    parts.joinToString(separator = "|") { part -> "${part.encodeToByteArray().size}:$part" }

private fun localHistoryRefusalMessage(): String =
    "This device already has local Sync V2 history for its current workspace key. " +
        "Clear local app data before joining another workspace."

private fun Throwable.safePairingFailureDetail(): String =
    if (this is SelfHostedSyncHttpException) {
        "server request rejected with HTTP $status; details redacted"
    } else {
        "${this::class.simpleName ?: "unknown error"}; details redacted"
    }
