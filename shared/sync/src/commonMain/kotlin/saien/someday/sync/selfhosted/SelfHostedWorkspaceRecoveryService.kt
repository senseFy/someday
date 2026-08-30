package saien.someday.sync.selfhosted

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.someday.data.serialization.StrictJsonFraming
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.domain.settings.WorkspaceJoinPackageProvider
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspaceJoiner
import saien.someday.domain.settings.WorkspacePairingReason
import saien.someday.domain.settings.WorkspaceRecoveryCode
import saien.someday.domain.settings.WorkspaceRecoveryCodeResult
import saien.someday.domain.settings.WorkspaceRecoveryManager
import saien.someday.domain.settings.WorkspaceRecoveryReason
import saien.someday.domain.settings.WorkspaceRecoveryRestoreResult
import saien.someday.domain.settings.WorkspaceRecoveryState
import saien.someday.domain.settings.WorkspaceRecoveryStatusResult
import saien.someday.domain.settings.WorkspaceRecoverySyncGate
import saien.someday.domain.settings.authorityBindingId
import saien.someday.sync.WorkspaceLifecycleCoordinator
import saien.someday.sync.StrictJsonV2
import saien.someday.sync.pairing.WorkspacePairingEnvelopeCodec

/**
 * Publishes and consumes only a recovery-code-wrapped workspace key. The
 * recovery code itself never crosses the transport boundary.
 */
class SelfHostedWorkspaceRecoveryService(
    private val settingsProvider: () -> ClientSettings,
    private val sessionStore: SelfHostedSessionCredentialStore,
    private val transport: SelfHostedWorkspaceRecoveryTransport,
    private val sessionExecutor: RefreshingSelfHostedSessionExecutor,
    private val workspaceJoinPackageProvider: WorkspaceJoinPackageProvider,
    private val workspaceJoiner: WorkspaceJoiner,
    private val workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator,
    private val activeWorkspaceSessionGuard: ActiveWorkspaceSessionGuard,
    private val workspaceRecoveryPublisherReady: () -> Boolean,
    private val localWorkspaceKeyFingerprint: () -> String?,
) : WorkspaceRecoveryManager {
    private var pendingSetup: PendingRecoverySetup? = null

    override fun status(): WorkspaceRecoveryStatusResult =
        runCatching {
            workspaceLifecycleCoordinator.exclusive { statusLocked() }
        }.getOrElse { failure ->
            WorkspaceRecoveryStatusResult.failure(
                reason = WorkspaceRecoveryReason.Failed,
                diagnosticMessage = failure.safeRecoveryFailureDetail(),
            )
        }

    override fun prepareCode(): WorkspaceRecoveryCodeResult =
        runCatching {
            workspaceLifecycleCoordinator.exclusive { prepareCodeLocked() }
        }.getOrElse { failure ->
            WorkspaceRecoveryCodeResult.failure(
                reason = WorkspaceRecoveryReason.Failed,
                diagnosticMessage = failure.safeRecoveryFailureDetail(),
            )
        }

    override fun confirmPreparedCode(candidate: String): WorkspaceRecoveryCodeResult =
        runCatching {
            workspaceLifecycleCoordinator.exclusive { confirmPreparedCodeLocked(candidate) }
        }.getOrElse { failure ->
            WorkspaceRecoveryCodeResult.failure(
                reason = WorkspaceRecoveryReason.Failed,
                diagnosticMessage = failure.safeRecoveryFailureDetail(),
            )
        }

    override fun discardPreparedCode() {
        workspaceLifecycleCoordinator.exclusive { pendingSetup = null }
    }

    override fun recover(
        recoveryCode: String,
        replaceExistingWorkspace: Boolean,
    ): WorkspaceRecoveryRestoreResult =
        runCatching {
            if (!replaceExistingWorkspace) {
                return WorkspaceRecoveryRestoreResult.failure(
                    WorkspaceRecoveryReason.ReplacementConfirmationRequired,
                )
            }
            val normalizedRecoveryCode = recoveryCode.normalizedRecoveryCodeOrNull()
            if (normalizedRecoveryCode == null) {
                return WorkspaceRecoveryRestoreResult.failure(WorkspaceRecoveryReason.InvalidCode)
            }
            workspaceLifecycleCoordinator.exclusive {
                recoverLocked(normalizedRecoveryCode)
            }
        }.getOrElse { failure ->
            WorkspaceRecoveryRestoreResult.failure(
                reason = WorkspaceRecoveryReason.Failed,
                diagnosticMessage = failure.safeRecoveryFailureDetail(),
            )
        }

    private fun statusLocked(): WorkspaceRecoveryStatusResult {
        val session = when (val result = requireSession()) {
            is RecoverySessionResult.Ready -> result.session
            is RecoverySessionResult.Failed -> return WorkspaceRecoveryStatusResult.failure(result.reason)
        }
        val requirement = activeWorkspaceSessionGuard.currentRequirement()
        val localKeyFingerprint = localWorkspaceKeyFingerprint()
        val localSyncGate = if (requirement != null && localKeyFingerprint != null) {
            WorkspaceRecoverySyncGate.Allowed
        } else {
            WorkspaceRecoverySyncGate.VerificationUnavailable
        }
        val remote = when (val result = loadRemoteEnvelope(session)) {
            RecoveryEnvelopeLoadResult.Missing -> return WorkspaceRecoveryStatusResult.ready(
                state = WorkspaceRecoveryState.NotConfigured,
                // A verified 404 means the recovery control plane is simply
                // not configured. Core workspace/session guards remain the
                // authority for whether the data plane can publish.
                syncGate = WorkspaceRecoverySyncGate.Allowed,
                reason = WorkspaceRecoveryReason.NotConfigured,
            )
            is RecoveryEnvelopeLoadResult.Ready -> result.envelope
            is RecoveryEnvelopeLoadResult.Failed -> return WorkspaceRecoveryStatusResult.failure(
                reason = result.reason,
                syncGate = localSyncGate,
                diagnosticMessage = result.diagnosticMessage,
            )
        }
        if (requirement != null && requirement.workspaceId != remote.packageData.workspaceId) {
            return WorkspaceRecoveryStatusResult.failure(
                reason = WorkspaceRecoveryReason.AuthorityMismatch,
                syncGate = localSyncGate,
            )
        }
        if (requirement == null || localKeyFingerprint == null) {
            return WorkspaceRecoveryStatusResult.ready(
                state = WorkspaceRecoveryState.RecoveryAvailable,
                syncGate = WorkspaceRecoverySyncGate.RecoveryRequired,
                reason = WorkspaceRecoveryReason.RecoveryAvailable,
            )
        }
        if (localKeyFingerprint != remote.packageData.keyFingerprint) {
            return WorkspaceRecoveryStatusResult.failure(
                reason = WorkspaceRecoveryReason.AuthorityMismatch,
                syncGate = WorkspaceRecoverySyncGate.Allowed,
            )
        }
        return WorkspaceRecoveryStatusResult.ready(
            state = WorkspaceRecoveryState.Configured,
            syncGate = WorkspaceRecoverySyncGate.Allowed,
            reason = WorkspaceRecoveryReason.Configured,
        )
    }

    private fun prepareCodeLocked(): WorkspaceRecoveryCodeResult {
        val session = when (val result = requireSession()) {
            is RecoverySessionResult.Ready -> result.session
            is RecoverySessionResult.Failed -> return WorkspaceRecoveryCodeResult.failure(result.reason)
        }
        val requirement = activeWorkspaceSessionGuard.currentRequirement()
            ?: return WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.PublishRequired)
        if (!workspaceRecoveryPublisherReady()) {
            return WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.PublishRequired)
        }
        if (!activeWorkspaceSessionGuard.isCompatible(session.toCredentials(), requirement.workspaceId)) {
            return WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.AuthorityMismatch)
        }

        val current = when (val result = loadRemoteEnvelope(session)) {
            RecoveryEnvelopeLoadResult.Missing -> null
            is RecoveryEnvelopeLoadResult.Ready -> result.envelope
            is RecoveryEnvelopeLoadResult.Failed -> return WorkspaceRecoveryCodeResult.failure(
                result.reason,
                result.diagnosticMessage,
            )
        }
        val packageResult = workspaceJoinPackageProvider.createPackage()
        val packageData = packageResult.packageData
            ?: return WorkspaceRecoveryCodeResult.failure(
                packageResult.reason.toRecoveryReason(),
                packageResult.diagnosticMessage,
            )
        if (packageData.workspaceId != requirement.workspaceId) {
            return WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.AuthorityMismatch)
        }
        val encoded = WorkspaceRecoveryEnvelopeCodec.encode(packageData)
        val request = SelfHostedWorkspaceRecoveryEnvelopePutRequest(
            workspaceId = packageData.workspaceId,
            keyFingerprint = packageData.keyFingerprint,
            envelopeJson = encoded.json,
            envelopeDigest = encoded.digest,
            expectedRevision = current?.revision,
        )
        pendingSetup = PendingRecoverySetup(
            authorityBindingId = session.toCredentials().authorityBindingId,
            packageData = packageData,
            request = request,
        )
        return WorkspaceRecoveryCodeResult.prepared(
            WorkspaceRecoveryCode.fromUserVisibleValue(packageData.recoveryCode),
        )
    }

    private fun confirmPreparedCodeLocked(candidate: String): WorkspaceRecoveryCodeResult {
        val pending = pendingSetup
            ?: return WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.Failed)
        if (!candidate.matchesRecoveryCode(pending.packageData.recoveryCode)) {
            return WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.InvalidCode)
        }
        val session = when (val result = requireSession()) {
            is RecoverySessionResult.Ready -> result.session
            is RecoverySessionResult.Failed -> return WorkspaceRecoveryCodeResult.failure(result.reason)
        }
        val requirement = activeWorkspaceSessionGuard.currentRequirement()
            ?: return WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.PublishRequired)
        if (!workspaceRecoveryPublisherReady()) {
            return WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.PublishRequired)
        }
        if (session.toCredentials().authorityBindingId != pending.authorityBindingId ||
            requirement.workspaceId != pending.request.workspaceId ||
            localWorkspaceKeyFingerprint() != pending.request.keyFingerprint ||
            !activeWorkspaceSessionGuard.isCompatible(session.toCredentials(), pending.request.workspaceId)
        ) {
            pendingSetup = null
            return WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.AuthorityMismatch)
        }
        val stored = try {
            sessionExecutor.authorized(session.endpoint, session.userId, session.accessToken) { accessToken ->
                transport.putWorkspaceRecoveryEnvelope(session.endpoint, accessToken, pending.request)
            }
        } catch (failure: SelfHostedSyncHttpException) {
            if (failure.status == 409) pendingSetup = null
            return WorkspaceRecoveryCodeResult.failure(
                reason = if (failure.status == 409) {
                    WorkspaceRecoveryReason.ServerConflict
                } else {
                    WorkspaceRecoveryReason.ServerRequestFailed
                },
                diagnosticMessage = failure.safeRecoveryFailureDetail(),
            )
        } catch (failure: Throwable) {
            return WorkspaceRecoveryCodeResult.failure(
                WorkspaceRecoveryReason.ServerRequestFailed,
                failure.safeRecoveryFailureDetail(),
            )
        }
        if (!stored.matches(pending.request) || WorkspaceRecoveryEnvelopeCodec.decode(stored) == null) {
            return WorkspaceRecoveryCodeResult.failure(WorkspaceRecoveryReason.ServerRequestFailed)
        }
        pendingSetup = null
        return WorkspaceRecoveryCodeResult.created()
    }

    private fun recoverLocked(recoveryCode: String): WorkspaceRecoveryRestoreResult {
        val session = when (val result = requireSession()) {
            is RecoverySessionResult.Ready -> result.session
            is RecoverySessionResult.Failed -> return WorkspaceRecoveryRestoreResult.failure(result.reason)
        }
        val remote = when (val result = loadRemoteEnvelope(session)) {
            RecoveryEnvelopeLoadResult.Missing -> return WorkspaceRecoveryRestoreResult.failure(
                WorkspaceRecoveryReason.NotConfigured,
            )
            is RecoveryEnvelopeLoadResult.Ready -> result.envelope
            is RecoveryEnvelopeLoadResult.Failed -> return WorkspaceRecoveryRestoreResult.failure(
                result.reason,
                result.diagnosticMessage,
            )
        }
        val requirement = activeWorkspaceSessionGuard.currentRequirement()
        val localKeyFingerprint = localWorkspaceKeyFingerprint()
        if (requirement != null && requirement.workspaceId != remote.packageData.workspaceId) {
            return WorkspaceRecoveryRestoreResult.failure(WorkspaceRecoveryReason.AuthorityMismatch)
        }
        if (requirement != null && localKeyFingerprint != null) {
            return WorkspaceRecoveryRestoreResult.failure(
                if (localKeyFingerprint == remote.packageData.keyFingerprint) {
                    WorkspaceRecoveryReason.RecoveryNotRequired
                } else {
                    WorkspaceRecoveryReason.AuthorityMismatch
                },
            )
        }
        val packageData = WorkspaceJoinPackage(
            metadataJson = remote.packageData.metadataJson,
            recoveryCode = recoveryCode,
            workspaceId = remote.packageData.workspaceId,
            keyFingerprint = remote.packageData.keyFingerprint,
        )
        val joined = workspaceLifecycleCoordinator.productAccess {
            workspaceJoiner.join(packageData, replaceExistingWorkspace = true)
        }
        return if (joined.success) {
            WorkspaceRecoveryRestoreResult.recovered()
        } else {
            WorkspaceRecoveryRestoreResult.failure(
                joined.reason.toRecoveryReason(),
                joined.diagnosticMessage,
            )
        }
    }

    private fun loadRemoteEnvelope(session: SelfHostedSyncSession): RecoveryEnvelopeLoadResult {
        val response = try {
            sessionExecutor.authorized(session.endpoint, session.userId, session.accessToken) { accessToken ->
                transport.getWorkspaceRecoveryEnvelope(session.endpoint, accessToken)
            }
        } catch (failure: Throwable) {
            return RecoveryEnvelopeLoadResult.Failed(
                reason = WorkspaceRecoveryReason.ServerRequestFailed,
                diagnosticMessage = failure.safeRecoveryFailureDetail(),
            )
        } ?: return RecoveryEnvelopeLoadResult.Missing
        val decoded = WorkspaceRecoveryEnvelopeCodec.decode(response)
            ?: return RecoveryEnvelopeLoadResult.Failed(WorkspaceRecoveryReason.ServerRequestFailed)
        return RecoveryEnvelopeLoadResult.Ready(
            StoredRecoveryEnvelope(
                packageData = decoded,
                revision = response.revision,
            ),
        )
    }

    private fun requireSession(): RecoverySessionResult {
        val sync = settingsProvider().syncConfiguration
        if (sync.mode != SyncMode.SelfHosted || !sync.selfHostedSession.loggedIn) {
            return RecoverySessionResult.Failed(WorkspaceRecoveryReason.SessionRequired)
        }
        val credentials = sessionStore.load()
            ?: return RecoverySessionResult.Failed(WorkspaceRecoveryReason.SessionRequired)
        if (!activeWorkspaceSessionGuard.isCompatible(credentials)) {
            return RecoverySessionResult.Failed(WorkspaceRecoveryReason.AuthorityMismatch)
        }
        return RecoverySessionResult.Ready(SelfHostedSyncSession.fromCredentials(credentials))
    }
}

private object WorkspaceRecoveryEnvelopeCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(packageData: WorkspaceJoinPackage): EncodedRecoveryEnvelope {
        requirePackage(packageData)
        val encoded = json.encodeToString(
            WorkspaceRecoveryEnvelopePayload(
                metadataJson = packageData.metadataJson,
                workspaceId = packageData.workspaceId,
                keyFingerprint = packageData.keyFingerprint,
            ),
        )
        require(encoded.encodeToByteArray().size <= MAX_ENVELOPE_BYTES) {
            "Workspace recovery envelope exceeds the protocol limit."
        }
        return EncodedRecoveryEnvelope(
            json = encoded,
            digest = WorkspacePairingEnvelopeCodec.digest(encoded.encodeToByteArray()),
        )
    }

    fun decode(response: SelfHostedWorkspaceRecoveryEnvelopeResponse): WorkspaceJoinPackage? {
        if (response.revision <= 0L || response.updatedAtEpochMillis <= 0L) return null
        val bytes = response.envelopeJson.encodeToByteArray()
        if (bytes.isEmpty() || bytes.size > MAX_ENVELOPE_BYTES) return null
        if (WorkspacePairingEnvelopeCodec.digest(bytes) != response.envelopeDigest) return null
        val payload = decodeStrictPayload(response.envelopeJson) ?: return null
        if (payload.format != FORMAT || payload.protocolVersion != PROTOCOL_VERSION) return null
        if (payload.workspaceId != response.workspaceId ||
            payload.keyFingerprint != response.keyFingerprint ||
            !payload.workspaceId.isRecoveryWorkspaceId() ||
            !payload.keyFingerprint.isRecoveryKeyFingerprint() ||
            payload.metadataJson.isBlank() ||
            payload.metadataJson.encodeToByteArray().size > MAX_METADATA_BYTES
        ) {
            return null
        }
        return WorkspaceJoinPackage(
            metadataJson = payload.metadataJson,
            recoveryCode = RECOVERY_CODE_PLACEHOLDER,
            workspaceId = payload.workspaceId,
            keyFingerprint = payload.keyFingerprint,
        )
    }

    private fun requirePackage(packageData: WorkspaceJoinPackage) {
        require(packageData.workspaceId.isRecoveryWorkspaceId()) { "Workspace identifier is invalid." }
        require(packageData.keyFingerprint.isRecoveryKeyFingerprint()) { "Workspace key fingerprint is invalid." }
        require(packageData.metadataJson.isNotBlank()) { "Workspace recovery metadata is missing." }
        require(packageData.metadataJson.encodeToByteArray().size <= MAX_METADATA_BYTES) {
            "Workspace recovery metadata exceeds the protocol limit."
        }
        require(
            portableMetadataMatches(
                metadataJson = packageData.metadataJson,
                workspaceId = packageData.workspaceId,
                keyFingerprint = packageData.keyFingerprint,
            ),
        ) { "Workspace recovery metadata does not match the portable v1 package." }
        require(packageData.recoveryCode.isRecoveryCodeInput()) { "Workspace recovery code is invalid." }
    }

    private fun decodeStrictPayload(value: String): WorkspaceRecoveryEnvelopePayload? {
        if (runCatching { StrictJsonV2.requireValidObjectKeys(value, MAX_ENVELOPE_BYTES) }.isFailure) {
            return null
        }
        val objectValue = try {
            json.parseToJsonElement(value).jsonObject
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (objectValue.keys != ENVELOPE_FIELDS ||
            ENVELOPE_STRING_FIELDS.any { field ->
                val primitive = objectValue[field] as? JsonPrimitive
                primitive == null || !primitive.isString
            }
        ) {
            return null
        }
        val protocolVersion = objectValue["protocolVersion"] as? JsonPrimitive ?: return null
        if (protocolVersion.isString || protocolVersion.intOrNull == null) return null
        val metadataJson = objectValue["metadataJson"]?.jsonPrimitive?.content ?: return null
        val workspaceId = objectValue["workspaceId"]?.jsonPrimitive?.content ?: return null
        val keyFingerprint = objectValue["keyFingerprint"]?.jsonPrimitive?.content ?: return null
        if (!portableMetadataMatches(metadataJson, workspaceId, keyFingerprint)) return null
        return runCatching {
            json.decodeFromJsonElement(WorkspaceRecoveryEnvelopePayload.serializer(), objectValue)
        }.getOrNull()
    }

    private fun portableMetadataMatches(
        metadataJson: String,
        workspaceId: String,
        keyFingerprint: String,
    ): Boolean {
        if (!StrictJsonFraming.isStrictWorkspaceRecoveryMetadata(metadataJson, MAX_METADATA_BYTES)) return false
        val metadata = runCatching { json.parseToJsonElement(metadataJson).jsonObject }.getOrNull() ?: return false
        val format = metadata["format"] as? JsonPrimitive ?: return false
        val version = metadata["version"] as? JsonPrimitive ?: return false
        val nestedWorkspaceId = metadata["workspaceId"] as? JsonPrimitive ?: return false
        val nestedKeyFingerprint = metadata["keyFingerprint"] as? JsonPrimitive ?: return false
        return format.isString && format.content == METADATA_FORMAT &&
            !version.isString && version.intOrNull == METADATA_VERSION &&
            nestedWorkspaceId.isString && nestedWorkspaceId.content == workspaceId &&
            nestedKeyFingerprint.isString && nestedKeyFingerprint.content == keyFingerprint
    }

    private const val FORMAT = "someday.workspace-recovery"
    private const val PROTOCOL_VERSION = 1
    private const val METADATA_FORMAT = "someday.workspace-recovery-metadata"
    private const val METADATA_VERSION = 1
    private const val MAX_ENVELOPE_BYTES = 64 * 1_024
    private const val MAX_METADATA_BYTES = 48 * 1_024
    private const val RECOVERY_CODE_PLACEHOLDER = "<provided-by-user>"
    private val ENVELOPE_FIELDS = setOf(
        "format",
        "protocolVersion",
        "metadataJson",
        "workspaceId",
        "keyFingerprint",
    )
    private val ENVELOPE_STRING_FIELDS = setOf(
        "format",
        "metadataJson",
        "workspaceId",
        "keyFingerprint",
    )
}

@Serializable
private data class WorkspaceRecoveryEnvelopePayload(
    val format: String = "someday.workspace-recovery",
    val protocolVersion: Int = 1,
    val metadataJson: String,
    val workspaceId: String,
    val keyFingerprint: String,
)

private data class EncodedRecoveryEnvelope(
    val json: String,
    val digest: String,
)

private data class StoredRecoveryEnvelope(
    val packageData: WorkspaceJoinPackage,
    val revision: Long,
)

private data class PendingRecoverySetup(
    val authorityBindingId: String,
    val packageData: WorkspaceJoinPackage,
    val request: SelfHostedWorkspaceRecoveryEnvelopePutRequest,
) {
    override fun toString(): String = "PendingRecoverySetup(<redacted>)"
}

private sealed interface RecoveryEnvelopeLoadResult {
    data object Missing : RecoveryEnvelopeLoadResult
    data class Ready(val envelope: StoredRecoveryEnvelope) : RecoveryEnvelopeLoadResult
    data class Failed(
        val reason: WorkspaceRecoveryReason,
        val diagnosticMessage: String? = null,
    ) : RecoveryEnvelopeLoadResult
}

private sealed interface RecoverySessionResult {
    data class Ready(val session: SelfHostedSyncSession) : RecoverySessionResult
    data class Failed(val reason: WorkspaceRecoveryReason) : RecoverySessionResult
}

private fun SelfHostedWorkspaceRecoveryEnvelopeResponse.matches(
    request: SelfHostedWorkspaceRecoveryEnvelopePutRequest,
): Boolean =
    revision > 0L && updatedAtEpochMillis > 0L &&
        workspaceId == request.workspaceId &&
        keyFingerprint == request.keyFingerprint &&
        envelopeJson == request.envelopeJson &&
        envelopeDigest == request.envelopeDigest

private fun String.isRecoveryWorkspaceId(): Boolean =
    length == 42 && startsWith("workspace-") && drop(10).all { it in '0'..'9' || it in 'a'..'f' }

private fun String.isRecoveryKeyFingerprint(): Boolean =
    length == 32 && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.matchesRecoveryCode(expected: String): Boolean {
    val candidateBytes = normalizedRecoveryCodeOrNull()?.encodeToByteArray() ?: return false
    val expectedBytes = expected.normalizedRecoveryCodeOrNull()?.encodeToByteArray() ?: return false
    var difference = candidateBytes.size xor expectedBytes.size
    val comparedSize = maxOf(candidateBytes.size, expectedBytes.size)
    repeat(comparedSize) { index ->
        val candidateByte = candidateBytes.getOrElse(index) { 0 }
        val expectedByte = expectedBytes.getOrElse(index) { 0 }
        difference = difference or (candidateByte.toInt() xor expectedByte.toInt())
    }
    return difference == 0
}

private fun String.isRecoveryCodeInput(): Boolean = normalizedRecoveryCodeOrNull() != null

private fun String.normalizedRecoveryCodeOrNull(): String? {
    if (length > MAX_RECOVERY_CODE_INPUT_CHARS) return null
    val normalized = trim().uppercase().filter { character ->
        character in 'A'..'Z' || character in '0'..'9'
    }
    return normalized.takeIf {
        it.length == NORMALIZED_RECOVERY_CODE_CHARS &&
            it.startsWith(RECOVERY_CODE_PREFIX) &&
            it.drop(RECOVERY_CODE_PREFIX.length).all { character ->
                character in '0'..'9' || character in 'A'..'F'
            }
    }
}

private const val RECOVERY_CODE_PREFIX = "SOMEDAY"
private const val NORMALIZED_RECOVERY_CODE_CHARS = 39
private const val MAX_RECOVERY_CODE_INPUT_CHARS = 96

private fun WorkspacePairingReason.toRecoveryReason(): WorkspaceRecoveryReason =
    when (this) {
        WorkspacePairingReason.PublishRequired -> WorkspaceRecoveryReason.PublishRequired
        WorkspacePairingReason.SessionRequired -> WorkspaceRecoveryReason.SessionRequired
        WorkspacePairingReason.AuthorityMismatch -> WorkspaceRecoveryReason.AuthorityMismatch
        WorkspacePairingReason.WorkspaceLocked -> WorkspaceRecoveryReason.WorkspaceLocked
        WorkspacePairingReason.VerificationFailed,
        WorkspacePairingReason.InvalidToken,
        -> WorkspaceRecoveryReason.InvalidCode
        WorkspacePairingReason.ReplacementConfirmationRequired ->
            WorkspaceRecoveryReason.ReplacementConfirmationRequired
        WorkspacePairingReason.ReplacementFailed -> WorkspaceRecoveryReason.ReplacementFailed
        WorkspacePairingReason.ServerRequestFailed -> WorkspaceRecoveryReason.ServerRequestFailed
        else -> WorkspaceRecoveryReason.Failed
    }

private fun Throwable.safeRecoveryFailureDetail(): String =
    if (this is SelfHostedSyncHttpException) {
        "server request rejected with HTTP $status; recovery secrets redacted"
    } else {
        "${this::class.simpleName ?: "unknown error"}; recovery secrets redacted"
    }
