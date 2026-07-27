@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.webdav

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WebDavConnectionInput
import saien.someday.domain.settings.WebDavCredentialStore
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
import saien.someday.sync.StrictJsonV2
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator

/**
 * WebDAV pairing is a one-use, high-entropy capability protocol. Creation is
 * append-only. Claim and cancellation replace the encrypted envelope with an
 * authenticated tombstone using the exact ETag returned by GET. Pairing never
 * deletes the remote record; retaining the tombstone prevents path-reuse
 * replay until server-side retention can collect expired records.
 */
class WebDavWorkspacePairingService(
    private val settingsProvider: () -> ClientSettings,
    private val credentialStore: WebDavCredentialStore,
    private val transport: WebDavTransport,
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
            val context = createContextOrFailure().getOrElse {
                return WorkspacePairingInvitationResult.failure(it.message ?: "WebDAV pairing is not configured.")
            }
            val packageResult = workspaceJoinPackageProvider.createPackage()
            val packageData = packageResult.packageData
                ?: return WorkspacePairingInvitationResult.failure(packageResult.message)

            repeat(MAX_CREATE_ATTEMPTS) {
                val token = WorkspacePairingToken.generate(crypto)
                val nowMillis = clock().toEpochMilliseconds()
                val expiresAtMillis = nowMillis + WorkspacePairingEnvelopeCodec.MAX_TTL_MILLIS
                val encoded = envelopeCodec.encode(
                    token = token,
                    authority = context.authority,
                    createdAtEpochMillis = nowMillis,
                    expiresAtEpochMillis = expiresAtMillis,
                    packageData = packageData,
                )
                val path = context.client.pathResolver().pairingInvite(encoded.inviteId)
                when (context.client.uploadRawAppendOnly(path, encoded.bytes)) {
                    is WebDavRawUploadResult.Uploaded ->
                        return WorkspacePairingInvitationResult.success(
                            message = "Pairing invitation created. Scan the QR code or enter the token before it expires.",
                            invitation = WorkspacePairingInvitation.create(
                                manualToken = token.formattedManualToken(),
                                qrPayload = token.qrPayload(),
                                expiresAtEpochMillis = expiresAtMillis,
                            ),
                        )
                    is WebDavRawUploadResult.PreconditionConflict -> Unit
                    is WebDavRawUploadResult.Rejected ->
                        return WorkspacePairingInvitationResult.failure(
                            "WebDAV rejected the pairing invitation; credentials and secrets redacted.",
                        )
                }
            }
            WorkspacePairingInvitationResult.failure("Could not create a unique pairing invitation. Try again.")
        }.getOrElse {
            WorkspacePairingInvitationResult.failure("Workspace pairing failed; credentials and secrets redacted.")
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
        val context = createContextOrFailure().getOrElse {
            return WorkspaceJoinResult.failure(it.message ?: "WebDAV pairing is not configured.")
        }
        val material = token.deriveMaterial()
        val path = context.client.pathResolver().pairingInvite(material.inviteId)
        val stored = context.client.getRawObject(path)
            ?: return WorkspaceJoinResult.failure("Pairing invitation was not found or has expired.")
        val decoded = when (
            val result = envelopeCodec.decode(
                token = token,
                authority = context.authority,
                envelopeBytes = stored.bytes,
                nowEpochMillis = clock().toEpochMilliseconds(),
            )
        ) {
            is WorkspacePairingEnvelopeDecodeResult.Success -> result
            WorkspacePairingEnvelopeDecodeResult.Expired ->
                return WorkspaceJoinResult.failure("Pairing invitation has expired. Generate a new one and try again.")
            WorkspacePairingEnvelopeDecodeResult.Invalid ->
                return WorkspaceJoinResult.failure(
                    "Pairing invitation could not be verified for this WebDAV workspace.",
                )
        }
        val claimId = base64UrlNoPadding(crypto.randomBytes(CLAIM_ID_BYTES))
        val tombstone = encodeTombstone(
            token = token,
            authority = context.authority,
            state = TombstoneState.Claimed,
            claimId = claimId,
            envelopeDigest = decoded.envelopeDigest,
            expiresAtEpochMillis = decoded.expiresAtEpochMillis,
        )
        if (
            !publishTombstone(
                context = context,
                path = path,
                previousEtag = stored.etag,
                expected = tombstone,
                token = token,
            )
        ) {
            return WorkspaceJoinResult.failure("This pairing invitation was already claimed or cancelled.")
        }
        return workspaceJoiner.join(decoded.packageData)
    }

    override fun cancelInvitation(invitation: WorkspacePairingInvitation): WorkspaceJoinResult =
        runCatching {
            val token = WorkspacePairingToken.parse(invitation.revealManualToken())
                ?: return WorkspaceJoinResult.failure("The local pairing invitation is invalid.")
            val context = createContextOrFailure().getOrElse {
                return WorkspaceJoinResult.failure(it.message ?: "WebDAV pairing is not configured.")
            }
            val material = token.deriveMaterial()
            val path = context.client.pathResolver().pairingInvite(material.inviteId)
            val stored = context.client.getRawObject(path)
                ?: return WorkspaceJoinResult.success("Pairing invitation is no longer available.")
            val existingState = decodeTombstone(stored.bytes, token, context.authority)
            if (existingState != null) {
                return when (TombstoneState.fromWire(existingState.state)) {
                    TombstoneState.Cancelled -> WorkspaceJoinResult.success("Pairing invitation cancelled.")
                    TombstoneState.Claimed -> WorkspaceJoinResult.failure(
                        "Pairing invitation was already claimed and cannot be cancelled.",
                    )
                    null -> WorkspaceJoinResult.failure("Pairing invitation state could not be verified.")
                }
            }
            val decoded = when (
                val result = envelopeCodec.decode(
                    token = token,
                    authority = context.authority,
                    envelopeBytes = stored.bytes,
                    nowEpochMillis = clock().toEpochMilliseconds(),
                )
            ) {
                is WorkspacePairingEnvelopeDecodeResult.Success -> result
                WorkspacePairingEnvelopeDecodeResult.Expired ->
                    return WorkspaceJoinResult.success("Pairing invitation has expired.")
                WorkspacePairingEnvelopeDecodeResult.Invalid ->
                    return WorkspaceJoinResult.failure("Pairing invitation could not be verified.")
            }
            val tombstone = encodeTombstone(
                token = token,
                authority = context.authority,
                state = TombstoneState.Cancelled,
                claimId = null,
                envelopeDigest = decoded.envelopeDigest,
                expiresAtEpochMillis = decoded.expiresAtEpochMillis,
            )
            if (
                publishTombstone(
                    context = context,
                    path = path,
                    previousEtag = stored.etag,
                    expected = tombstone,
                    token = token,
                )
            ) {
                WorkspaceJoinResult.success("Pairing invitation cancelled.")
            } else {
                WorkspaceJoinResult.failure("Pairing invitation changed before cancellation.")
            }
        }.getOrElse {
            WorkspaceJoinResult.failure("Could not cancel pairing invitation; credentials and secrets redacted.")
        }

    private fun publishTombstone(
        context: PairingContext,
        path: String,
        previousEtag: String?,
        expected: WorkspacePairingTombstone,
        token: WorkspacePairingToken,
    ): Boolean {
        if (previousEtag.isNullOrBlank()) return false
        val bytes = tombstoneJson.encodeToString(WorkspacePairingTombstone.serializer(), expected).encodeToByteArray()
        val upload = runCatching {
            context.client.uploadRawMutable(path, bytes, previousEtag)
        }
        val result = upload.getOrNull()
        if (result is WebDavRawUploadResult.Uploaded) return true
        val remote = when (result) {
            is WebDavRawUploadResult.PreconditionConflict -> result.remote
            is WebDavRawUploadResult.Rejected,
            is WebDavRawUploadResult.Uploaded,
            null,
            -> runCatching { context.client.getRawObject(path) }.getOrNull()
        } ?: return false
        val decoded = decodeTombstone(remote.bytes, token, context.authority) ?: return false
        return decoded == expected
    }

    private fun encodeTombstone(
        token: WorkspacePairingToken,
        authority: WorkspacePairingAuthority,
        state: TombstoneState,
        claimId: String?,
        envelopeDigest: String,
        expiresAtEpochMillis: Long,
    ): WorkspacePairingTombstone {
        val inviteId = token.deriveMaterial().inviteId
        val unsigned = WorkspacePairingTombstone(
            inviteId = inviteId,
            state = state.wireName,
            claimId = claimId,
            envelopeDigest = envelopeDigest,
            expiresAtEpochMillis = expiresAtEpochMillis,
            authenticator = "",
        )
        return unsigned.copy(
            authenticator = tombstoneAuthenticator(token, authority, unsigned),
        )
    }

    private fun decodeTombstone(
        bytes: ByteArray,
        token: WorkspacePairingToken,
        authority: WorkspacePairingAuthority,
    ): WorkspacePairingTombstone? {
        val text = runCatching {
            bytes.decodeToString(throwOnInvalidSequence = true)
        }.getOrNull() ?: return null
        if (runCatching {
                StrictJsonV2.requireValidObjectKeys(
                    text,
                    WorkspacePairingEnvelopeCodec.MAX_ENVELOPE_BYTES,
                )
            }.isFailure
        ) {
            return null
        }
        val record = try {
            tombstoneJson.decodeFromString(WorkspacePairingTombstone.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (record.format != TOMBSTONE_FORMAT ||
            record.protocolVersion != WorkspacePairingEnvelopeCodec.PROTOCOL_VERSION ||
            record.inviteId != token.deriveMaterial().inviteId ||
            TombstoneState.fromWire(record.state) == null ||
            record.expiresAtEpochMillis <= 0 ||
            (record.state == TombstoneState.Claimed.wireName && record.claimId.isNullOrBlank()) ||
            (record.state == TombstoneState.Cancelled.wireName && record.claimId != null)
        ) {
            return null
        }
        val expected = tombstoneAuthenticator(token, authority, record.copy(authenticator = ""))
        return record.takeIf { constantTimeEquals(record.authenticator, expected) }
    }

    private fun tombstoneAuthenticator(
        token: WorkspacePairingToken,
        authority: WorkspacePairingAuthority,
        record: WorkspacePairingTombstone,
    ): String {
        val canonical = listOf(
            record.format,
            record.protocolVersion.toString(),
            authority.remoteProfile.wireName,
            authority.binding,
            record.inviteId,
            record.state,
            record.claimId.orEmpty(),
            record.envelopeDigest,
            record.expiresAtEpochMillis.toString(),
        ).joinToString("\n").encodeToByteArray()
        return base64UrlNoPadding(
            canonical.toByteString()
                .hmacSha256(token.deriveMaterial().stateKey.toByteString())
                .toByteArray(),
        )
    }

    private fun createContextOrFailure(): Result<PairingContext> {
        val settings = settingsProvider()
        val sync = settings.syncConfiguration
        if (sync.mode != SyncMode.WebDav) {
            return Result.failure(IllegalStateException("Select WebDAV mode before pairing devices."))
        }
        val credential = credentialStore.load()?.takeIf { it.isNotBlank() }
        val input = WebDavConnectionInput(
            endpoint = sync.webDavEndpoint.orEmpty(),
            username = sync.webDavUsername,
            password = credential,
            appDirectory = sync.webDavAppDirectory,
        ).sanitized()
        val errors = input.validate() + input.validatePairingCredential()
        if (errors.isNotEmpty()) {
            return Result.failure(IllegalStateException(errors.joinToString(separator = " ")))
        }
        val configuration = WebDavConfiguration.fromConnectionInput(input)
        return Result.success(
            PairingContext(
                client = WebDavClient(configuration = configuration, transport = transport),
                authority = WorkspacePairingAuthority(
                    remoteProfile = WorkspacePairingRemoteProfile.WebDav,
                    binding = canonicalAuthorityBinding(
                        configuration.normalizedEndpoint,
                        configuration.username.orEmpty(),
                        configuration.normalizedAppDirectory,
                    ),
                ),
            ),
        )
    }

    private data class PairingContext(
        val client: WebDavClient,
        val authority: WorkspacePairingAuthority,
    )

    private companion object {
        const val MAX_CREATE_ATTEMPTS: Int = 3
        const val CLAIM_ID_BYTES: Int = 16
        const val TOMBSTONE_FORMAT: String = "someday.workspace-pairing-state"

        val tombstoneJson = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}

private fun canonicalAuthorityBinding(vararg parts: String): String =
    parts.joinToString(separator = "|") { part -> "${part.encodeToByteArray().size}:$part" }

private fun localHistoryRefusalMessage(): String =
    "This device already has local Sync V2 history for its current workspace key. " +
        "Clear local app data before joining another workspace."

private fun WebDavConnectionInput.validatePairingCredential(): List<String> =
    buildList {
        if (username.isNullOrBlank()) add("Enter the WebDAV username first.")
        if (password.isNullOrBlank()) add("Save the WebDAV credential on this device first.")
    }

private fun constantTimeEquals(left: String, right: String): Boolean {
    if (left.length != right.length) return false
    var difference = 0
    left.indices.forEach { index ->
        difference = difference or (left[index].code xor right[index].code)
    }
    return difference == 0
}

private enum class TombstoneState(
    val wireName: String,
) {
    Claimed("claimed"),
    Cancelled("cancelled");

    companion object {
        fun fromWire(value: String): TombstoneState? = entries.firstOrNull { it.wireName == value }
    }
}

@Serializable
private data class WorkspacePairingTombstone(
    val format: String = "someday.workspace-pairing-state",
    val protocolVersion: Int = WorkspacePairingEnvelopeCodec.PROTOCOL_VERSION,
    val inviteId: String,
    val state: String,
    val claimId: String?,
    val envelopeDigest: String,
    val expiresAtEpochMillis: Long,
    val authenticator: String,
)
