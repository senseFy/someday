@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package saien.someday.domain.settings

import saien.someday.domain.notes.CausalEditToken
import kotlin.io.encoding.Base64

data class ClientSettings(
    val theme: ClientTheme = ClientTheme.System,
    /**
     * Device-local UI language. Not part of workspace-synced preferences.
     * [AppLanguage.System] follows the OS locale for compose resources.
     */
    val appLanguage: AppLanguage = AppLanguage.System,
    val editorPreferences: EditorPreferences = EditorPreferences(),
    val onThisDayNotifications: OnThisDayNotificationPreferences = OnThisDayNotificationPreferences(),
    val defaultNotebookId: String? = null,
    val lastSelectedNotebookId: String? = null,
    val activeDeviceId: String = DefaultActiveDeviceId,
    val syncConfiguration: SyncConfiguration = SyncConfiguration(),
    /** Transient V2 view metadata; the raw settings key/value store never persists it. */
    val workspacePreferencesState: WorkspacePreferencesSyncState = WorkspacePreferencesSyncState(),
) {
    companion object {
        const val DefaultActiveDeviceId: String = "local-device"
    }
}

/**
 * In-app UI language override for compose string resources.
 *
 * [System] uses the platform locale. Explicit values force a language tag
 * (`en`, `zh`, `ko`, `ja`) regardless of the OS setting.
 */
enum class AppLanguage(
    /** BCP-47 language subtag, or null when following the system. */
    val languageTag: String?,
) {
    System(languageTag = null),
    English(languageTag = "en"),
    Chinese(languageTag = "zh"),
    Korean(languageTag = "ko"),
    Japanese(languageTag = "ja"),
}

enum class WorkspacePreferencesSyncStatus {
    Unavailable,
    Synced,
    Pending,
    Warning,
    Conflict,
}

data class WorkspacePreferencesConflictBranch(
    val versionId: String,
    val theme: ClientTheme,
    val previewByDefault: Boolean,
    val markdownToolbarVisible: Boolean,
    val defaultNotebookId: String?,
)

data class WorkspacePreferencesConflictView(
    val conflictId: String,
    val expectedHeadVersionIds: List<String>,
    val conflictingFields: Set<String>,
    val branches: List<WorkspacePreferencesConflictBranch>,
)

data class WorkspacePreferencesSyncState(
    val status: WorkspacePreferencesSyncStatus = WorkspacePreferencesSyncStatus.Unavailable,
    val causalToken: CausalEditToken? = null,
    val warning: String? = null,
    val conflict: WorkspacePreferencesConflictView? = null,
    /** Exact semantic base and effective values represented by this Settings view. */
    val baseSnapshot: WorkspacePreferencesSnapshot? = null,
    val displayedSnapshot: WorkspacePreferencesSnapshot? = null,
)

data class WorkspacePreferencesSnapshot(
    val theme: ClientTheme,
    val previewByDefault: Boolean,
    val markdownToolbarVisible: Boolean,
    val defaultNotebookId: String?,
)

interface WorkspacePreferencesConflictResolver {
    fun resolveWorkspacePreferencesBranch(
        conflictId: String,
        selectedVersionId: String,
        expectedHeadVersionIds: List<String>,
    ): ClientSettings
}

enum class ClientTheme {
    System,
    Light,
    Dark,
}

data class EditorPreferences(
    val previewByDefault: Boolean = false,
    val markdownToolbarVisible: Boolean = true,
)

data class OnThisDayNotificationPreferences(
    val enabled: Boolean = false,
    val hour: Int = DefaultHour,
    val minute: Int = DefaultMinute,
) {
    init {
        require(hour in 0..23) { "On This Day notification hour must be between 0 and 23." }
        require(minute in 0..59) { "On This Day notification minute must be between 0 and 59." }
    }

    companion object {
        const val DefaultHour: Int = 10
        const val DefaultMinute: Int = 0
    }
}

data class SyncConfiguration(
    val mode: SyncMode = SyncMode.Off,
    val selfHostedEndpoint: String? = null,
    val selfHostedSession: SelfHostedSessionSummary = SelfHostedSessionSummary(),
    val lastError: String? = null,
)

enum class SyncMode {
    Off,
    SelfHosted,
}

data class SelfHostedSessionSummary(
    val loggedIn: Boolean = false,
    val userEmail: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val devicePlatform: String? = null,
) {
    val deviceLabel: String =
        listOfNotNull(deviceName, devicePlatform)
            .joinToString(separator = " / ")
            .ifBlank { deviceId ?: "not registered" }
}

data class SelfHostedSessionCredentials(
    val endpoint: String,
    val userId: String,
    val userEmail: String,
    val deviceId: String,
    val deviceName: String,
    val devicePlatform: String,
    val accessToken: String,
    val refreshToken: String,
) {
    init {
        require(isSecureSyncEndpoint(endpoint)) {
            "Self-hosted requires HTTPS unless the server is on this device's loopback interface."
        }
        require(userId.isNotBlank()) { "Self-hosted user id must be present." }
        require(userEmail.isNotBlank()) { "Self-hosted user email must be present." }
        require(deviceId.isNotBlank()) { "Self-hosted device id must be present." }
        require(accessToken.isNotBlank()) { "Self-hosted access token must be present." }
        require(refreshToken.isNotBlank()) { "Self-hosted refresh token must be present." }
    }

    fun toSummary(): SelfHostedSessionSummary =
        SelfHostedSessionSummary(
            loggedIn = true,
            userEmail = userEmail,
            deviceId = deviceId,
            deviceName = deviceName,
            devicePlatform = devicePlatform,
        )

    fun redactedDescription(): String =
        "endpoint=$endpoint user=$userEmail device=$deviceId accessToken=redacted refreshToken=redacted"
}

fun SelfHostedSessionCredentials.encodeForSecureStorage(): String {
    val payload = listOf(
        SelfHostedSessionCredentialsPayloadVersion,
        endpoint,
        userId,
        userEmail,
        deviceId,
        deviceName,
        devicePlatform,
        accessToken,
        refreshToken,
    ).joinToString(separator = "\n") { value ->
        Base64.encode(value.encodeToByteArray())
    }
    return "$SelfHostedSessionCredentialsEnvelopeVersion:${Base64.encode(payload.encodeToByteArray())}"
}

fun decodeSelfHostedSessionCredentials(value: String): SelfHostedSessionCredentials? {
    val payload = if (value.startsWith("$SelfHostedSessionCredentialsEnvelopeVersion:")) {
        val encodedPayload = value.substringAfter(':')
        runCatching {
            Base64.decode(encodedPayload).decodeToString(throwOnInvalidSequence = true)
        }.getOrNull()
            ?: return null
    } else {
        // Development builds before the single-line envelope stored this payload directly.
        value
    }
    val decoded = buildList {
        payload.split('\n')
            .forEach { line ->
                val decodedLine = runCatching {
                    Base64.decode(line).decodeToString(throwOnInvalidSequence = true)
                }.getOrNull()
                    ?: return null
                add(decodedLine)
            }
    }
    if (decoded.size != 9 || decoded[0] != SelfHostedSessionCredentialsPayloadVersion) {
        return null
    }
    return runCatching {
        SelfHostedSessionCredentials(
            endpoint = decoded[1],
            userId = decoded[2],
            userEmail = decoded[3],
            deviceId = decoded[4],
            deviceName = decoded[5],
            devicePlatform = decoded[6],
            accessToken = decoded[7],
            refreshToken = decoded[8],
        )
    }.getOrNull()
}

private const val SelfHostedSessionCredentialsEnvelopeVersion = "self-hosted-session-v3"
private const val SelfHostedSessionCredentialsPayloadVersion = "self-hosted-session-v2"

interface SelfHostedSessionCredentialStore {
    fun load(): SelfHostedSessionCredentials?

    fun save(credentials: SelfHostedSessionCredentials)

    fun clear()

    fun loadForAuthority(authorityBindingId: String): SelfHostedSessionCredentials? =
        load()?.takeIf { it.authorityBindingId == authorityBindingId }

    fun saveForAuthority(authorityBindingId: String, credentials: SelfHostedSessionCredentials) {
        require(authorityBindingId == credentials.authorityBindingId) {
            "Self-hosted authority credential does not match its authenticated account binding."
        }
        val current = load()
        if (current == null || current.authorityBindingId == authorityBindingId) {
            save(credentials)
        } else {
            error("Authority-scoped self-hosted credential storage is unavailable in this build.")
        }
    }

    fun clearAuthority(authorityBindingId: String) = Unit
}

/**
 * Stable identity of one authenticated self-hosted account.
 *
 * The endpoint alone is not an authority: one server can host many mutually
 * isolated users. Length-prefixing keeps the value unambiguous without leaking
 * tokens, email addresses, or device credentials into protocol metadata.
 */
fun selfHostedAuthorityBindingId(endpoint: String, authenticatedUserId: String): String {
    val canonicalEndpoint = normalizeSelfHostedEndpoint(endpoint)
    val canonicalUserId = authenticatedUserId.trim()
    require(isSecureSyncEndpoint(canonicalEndpoint)) {
        "Self-hosted authority endpoint must be a valid HTTPS origin or loopback HTTP origin."
    }
    require(canonicalUserId.isNotBlank()) { "Self-hosted authority user id must be present." }
    return "self-hosted|${canonicalEndpoint.encodeToByteArray().size}:$canonicalEndpoint|" +
        "${canonicalUserId.encodeToByteArray().size}:$canonicalUserId"
}

data class SelfHostedAuthorityBinding(
    val endpoint: String,
    val authenticatedUserId: String,
)

/**
 * Decodes the canonical, length-prefixed account authority persisted with a workspace.
 *
 * Parsing the binding instead of trusting the currently selected session lets a bound
 * workspace safely obtain fresh tokens: the endpoint is checked before authentication and
 * the immutable server user id is checked before the stable device is re-registered.
 */
fun parseSelfHostedAuthorityBindingId(value: String): SelfHostedAuthorityBinding? {
    val prefix = "self-hosted|".encodeToByteArray()
    val encoded = value.encodeToByteArray()
    if (encoded.size < prefix.size || !encoded.copyOfRange(0, prefix.size).contentEquals(prefix)) {
        return null
    }

    var cursor = prefix.size
    fun readLengthPrefixedField(): String? {
        val lengthStart = cursor
        while (cursor < encoded.size && encoded[cursor].toInt() in '0'.code..'9'.code) {
            cursor += 1
        }
        if (cursor == lengthStart || cursor >= encoded.size || encoded[cursor].toInt() != ':'.code) {
            return null
        }
        val byteLength = encoded.copyOfRange(lengthStart, cursor)
            .decodeToString()
            .toLongOrNull()
            ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: return null
        cursor += 1
        if (byteLength > encoded.size - cursor) return null
        val fieldBytes = encoded.copyOfRange(cursor, cursor + byteLength)
        cursor += byteLength
        val field = fieldBytes.decodeToString()
        return field.takeIf { it.encodeToByteArray().contentEquals(fieldBytes) }
    }

    val endpoint = readLengthPrefixedField() ?: return null
    if (cursor >= encoded.size || encoded[cursor].toInt() != '|'.code) return null
    cursor += 1
    val authenticatedUserId = readLengthPrefixedField() ?: return null
    if (cursor != encoded.size) return null

    return runCatching {
        SelfHostedAuthorityBinding(endpoint, authenticatedUserId).also { parsed ->
            require(selfHostedAuthorityBindingId(parsed.endpoint, parsed.authenticatedUserId) == value) {
                "Self-hosted authority binding is not canonical."
            }
        }
    }.getOrNull()
}

val SelfHostedSessionCredentials.authorityBindingId: String
    get() = selfHostedAuthorityBindingId(endpoint, userId)

object UnavailableSelfHostedSessionCredentialStore : SelfHostedSessionCredentialStore {
    override fun load(): SelfHostedSessionCredentials? = null

    override fun save(credentials: SelfHostedSessionCredentials) {
        error("Self-hosted session credential storage is unavailable in this build.")
    }

    override fun clear() = Unit
}

data class SelfHostedSetupInput(
    val endpoint: String,
    val email: String,
    val password: String,
    val deviceName: String,
    val platform: String,
    val createAccount: Boolean,
) {
    fun sanitized(): SelfHostedSetupInput =
        copy(
            endpoint = normalizeSelfHostedEndpoint(endpoint),
            email = email.trim().lowercase(),
            deviceName = deviceName.trim(),
            platform = platform.trim().lowercase(),
        )

    fun validate(): List<SelfHostedSetupValidationIssue> {
        val sanitized = sanitized()
        return buildList {
            if (sanitized.endpoint.isBlank()) {
                add(SelfHostedSetupValidationIssue.EndpointRequired)
            }
            if (!sanitized.endpoint.startsWith("http://") && !sanitized.endpoint.startsWith("https://")) {
                add(SelfHostedSetupValidationIssue.EndpointSchemeRequired)
            } else if (!isSecureSyncEndpoint(sanitized.endpoint)) {
                add(SelfHostedSetupValidationIssue.HttpsRequired)
            }
            if (sanitized.email.isBlank() || "@" !in sanitized.email || "." !in sanitized.email.substringAfter("@")) {
                add(SelfHostedSetupValidationIssue.EmailInvalid)
            }
            if (password.length < 8) {
                add(SelfHostedSetupValidationIssue.PasswordTooShort)
            }
            if (sanitized.deviceName.isBlank()) {
                add(SelfHostedSetupValidationIssue.DeviceNameRequired)
            }
            if (sanitized.platform.isBlank()) {
                add(SelfHostedSetupValidationIssue.PlatformRequired)
            }
        }
    }

    fun redactedDescription(): String =
        "endpoint=${normalizeSelfHostedEndpoint(endpoint).ifBlank { "missing" }} " +
            "email=${email.trim().lowercase().ifBlank { "missing" }} " +
            "password=${if (password.isBlank()) "not-provided" else "redacted"} " +
            "device=${deviceName.trim().ifBlank { "missing" }} " +
            "platform=${platform.trim().lowercase().ifBlank { "missing" }} " +
            "mode=${if (createAccount) "register" else "login"}"
}

enum class SelfHostedSetupValidationIssue {
    EndpointRequired,
    EndpointSchemeRequired,
    HttpsRequired,
    EmailInvalid,
    PasswordTooShort,
    DeviceNameRequired,
    PlatformRequired,
}

enum class SelfHostedSetupReason {
    Ready,
    BoundSessionRenewed,
    AccountChangeBlocked,
    AuthorityInvalid,
    EndpointMismatch,
    AuthorityMismatch,
    DeviceRevoked,
    Unavailable,
    Failed,
}

data class SelfHostedSetupStatus(
    val ready: Boolean,
    val reason: SelfHostedSetupReason,
    val diagnosticMessage: String? = null,
) {
    init {
        require(
            diagnosticMessage == null ||
                !diagnosticMessage.contains("password", ignoreCase = true) ||
                diagnosticMessage.contains("redacted", ignoreCase = true),
        ) {
            "Self-hosted setup status must not expose credential secrets."
        }
    }
}

data class SelfHostedSetupResult(
    val success: Boolean,
    val status: SelfHostedSetupStatus,
    val session: SelfHostedSessionSummary? = null,
) {
    companion object {
        fun success(
            status: SelfHostedSetupStatus,
            session: SelfHostedSessionSummary,
        ): SelfHostedSetupResult =
            SelfHostedSetupResult(
                success = true,
                status = status,
                session = session.copy(loggedIn = true),
            )

        fun failure(
            reason: SelfHostedSetupReason,
            diagnosticMessage: String? = null,
        ): SelfHostedSetupResult =
            SelfHostedSetupResult(
                success = false,
                status = SelfHostedSetupStatus(
                    ready = false,
                    reason = reason,
                    diagnosticMessage = diagnosticMessage?.let(::redactSelfHostedSecretWords),
                ),
            )
    }
}

fun interface SelfHostedSetupClient {
    fun setup(input: SelfHostedSetupInput): SelfHostedSetupResult
}

data class ManualSyncResult(
    val success: Boolean,
    val mode: SyncMode,
    val pushedObjects: Int,
    val pulledObjects: Int,
    /**
     * Count of durable, user-actionable conflict records created locally.
     *
     * A non-zero value must correspond to conflict UI that can be opened after the
     * notes/settings controllers refresh. Transport contention, retries, corruption,
     * authentication failures, and non-persisted apply blockers are failures or ignored
     * states, not manual-sync conflicts.
    */
    val conflicts: Int,
    val reason: ManualSyncReason,
    val diagnosticMessage: String? = null,
) {
    companion object {
        fun success(
            mode: SyncMode,
            pushedObjects: Int,
            pulledObjects: Int,
            conflicts: Int,
            reason: ManualSyncReason = ManualSyncReason.Completed,
            diagnosticMessage: String? = null,
        ): ManualSyncResult =
            ManualSyncResult(
                success = true,
                mode = mode,
                pushedObjects = pushedObjects,
                pulledObjects = pulledObjects,
                conflicts = conflicts,
                reason = reason,
                diagnosticMessage = diagnosticMessage,
            )

        fun failure(
            mode: SyncMode,
            reason: ManualSyncReason,
            diagnosticMessage: String? = null,
            pushedObjects: Int = 0,
            pulledObjects: Int = 0,
            conflicts: Int = 0,
        ): ManualSyncResult =
            ManualSyncResult(
                success = false,
                mode = mode,
                pushedObjects = pushedObjects,
                pulledObjects = pulledObjects,
                conflicts = conflicts,
                reason = reason,
                diagnosticMessage = diagnosticMessage
                    ?.let(::redactSelfHostedSecretWords)
                    ?.replace(Regex("(?i)\\bV2\\b"), "entity-DAG"),
            )
    }
}

enum class ManualSyncReason {
    Completed,
    Initialized,
    Disabled,
    Unavailable,
    AlreadyRunning,
    ProviderChanged,
    AuthorityMismatch,
    WorkspaceLocked,
    RemoteHistoryConflict,
    CheckpointInvalid,
    RetryRequired,
    Blocked,
    Failed,
}

fun interface ManualSyncRunner {
    fun run(): ManualSyncResult
}

class WorkspaceJoinPackage(
    val metadataJson: String,
    val recoveryCode: String,
    val workspaceId: String,
    val keyFingerprint: String,
) {
    override fun equals(other: Any?): Boolean =
        other is WorkspaceJoinPackage &&
            metadataJson == other.metadataJson &&
            recoveryCode == other.recoveryCode &&
            workspaceId == other.workspaceId &&
            keyFingerprint == other.keyFingerprint

    override fun hashCode(): Int {
        var result = metadataJson.hashCode()
        result = 31 * result + recoveryCode.hashCode()
        result = 31 * result + workspaceId.hashCode()
        result = 31 * result + keyFingerprint.hashCode()
        return result
    }

    override fun toString(): String =
        "WorkspaceJoinPackage(workspaceId=$workspaceId, keyFingerprint=$keyFingerprint, secrets=<redacted>)"
}

data class WorkspaceJoinResult(
    val success: Boolean,
    val reason: WorkspacePairingReason,
    val diagnosticMessage: String? = null,
    val packageData: WorkspaceJoinPackage? = null,
) {
    companion object {
        fun success(
            reason: WorkspacePairingReason,
            packageData: WorkspaceJoinPackage? = null,
        ): WorkspaceJoinResult =
            WorkspaceJoinResult(
                success = true,
                reason = reason,
                packageData = packageData,
            )

        fun failure(
            reason: WorkspacePairingReason,
            diagnosticMessage: String? = null,
        ): WorkspaceJoinResult =
            WorkspaceJoinResult(
                success = false,
                reason = reason,
                diagnosticMessage = diagnosticMessage?.let(::redactSelfHostedSecretWords),
            )
    }
}

fun interface WorkspaceJoinPackageProvider {
    fun createPackage(): WorkspaceJoinResult
}

fun interface WorkspaceJoiner {
    fun join(packageData: WorkspaceJoinPackage): WorkspaceJoinResult
}

/**
 * Decides whether this installation may adopt another workspace identity.
 * Null means replacement is safe; a non-null value is a stable refusal reason.
 */
fun interface LocalWorkspaceAdoptionPolicy {
    fun refusalReason(): WorkspacePairingReason?
}

class WorkspacePairingInvitation private constructor(
    private val manualToken: String,
    private val qrPayload: String,
    val expiresAtEpochMillis: Long,
) {
    fun revealManualToken(): String = manualToken

    fun revealQrPayload(): String = qrPayload

    override fun toString(): String =
        "WorkspacePairingInvitation(expiresAtEpochMillis=$expiresAtEpochMillis, token=<redacted>)"

    companion object {
        fun create(
            manualToken: String,
            qrPayload: String,
            expiresAtEpochMillis: Long,
        ): WorkspacePairingInvitation {
            require(manualToken.isNotBlank()) { "Pairing token must not be blank." }
            require(qrPayload.isNotBlank()) { "Pairing QR payload must not be blank." }
            require(expiresAtEpochMillis > 0) { "Pairing expiry must be positive." }
            return WorkspacePairingInvitation(manualToken, qrPayload, expiresAtEpochMillis)
        }
    }
}

data class WorkspacePairingInvitationResult(
    val success: Boolean,
    val reason: WorkspacePairingReason,
    val diagnosticMessage: String? = null,
    val invitation: WorkspacePairingInvitation? = null,
) {
    companion object {
        fun success(
            reason: WorkspacePairingReason,
            invitation: WorkspacePairingInvitation,
        ): WorkspacePairingInvitationResult =
            WorkspacePairingInvitationResult(
                success = true,
                reason = reason,
                invitation = invitation,
            )

        fun failure(
            reason: WorkspacePairingReason,
            diagnosticMessage: String? = null,
        ): WorkspacePairingInvitationResult =
            WorkspacePairingInvitationResult(
                success = false,
                reason = reason,
                diagnosticMessage = diagnosticMessage?.let(::redactSelfHostedSecretWords),
            )
    }
}

enum class WorkspacePairingReason {
    PackageCreated,
    InvitationCreated,
    InvitationCancelled,
    InvitationUnavailable,
    Joined,
    PublishRequired,
    SessionRequired,
    InvalidToken,
    InvitationNotFound,
    InvitationAlreadyUsed,
    InvitationExpired,
    VerificationFailed,
    AuthorityMismatch,
    WorkspaceLocked,
    LocalWorkspaceNotReplaceable,
    LocalContentPresent,
    AdoptionFailed,
    Unavailable,
    Failed,
}

fun interface WorkspacePairingInvitationCreator {
    fun createInvitation(): WorkspacePairingInvitationResult
}

fun interface WorkspacePairingInvitationJoiner {
    fun joinWithToken(tokenInput: String): WorkspaceJoinResult
}

fun interface WorkspacePairingInvitationCanceller {
    fun cancelInvitation(invitation: WorkspacePairingInvitation): WorkspaceJoinResult
}

fun normalizeSelfHostedEndpoint(value: String): String {
    val trimmed = value.trim()
    return parseSelfHostedOriginOrNull(trimmed)?.canonicalValue ?: trimmed.trimEnd('/')
}

/** Credentials and mutations may use plaintext HTTP only over true loopback. */
fun isSecureSyncEndpoint(value: String): Boolean {
    val origin = parseSelfHostedOriginOrNull(value.trim()) ?: return false
    if (origin.scheme == "https") return true
    val host = origin.host
    return host == "localhost" || host.endsWith(".localhost") || host == "::1" ||
        host.split('.').let { parts ->
            parts.size == 4 && parts.firstOrNull() == "127" && parts.all { part ->
                part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
}

private data class ParsedSelfHostedOrigin(
    val scheme: String,
    val host: String,
    val canonicalValue: String,
)

/** Strict origin parser shared by validation and authority identity. */
private fun parseSelfHostedOriginOrNull(value: String): ParsedSelfHostedOrigin? {
    if (value.isBlank() || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = value.substring(0, schemeEnd).lowercase()
    if (scheme != "https" && scheme != "http") return null
    val remainder = value.substring(schemeEnd + 3)
    val suffixStart = remainder.indexOfFirst { it == '/' || it == '?' || it == '#' }
        .let { if (it < 0) remainder.length else it }
    val authority = remainder.substring(0, suffixStart)
    val suffix = remainder.substring(suffixStart)
    if (authority.isBlank() || '@' in authority || '%' in authority || (suffix.isNotEmpty() && suffix != "/")) {
        return null
    }

    val (host, port) = if (authority.startsWith('[')) {
        val closing = authority.indexOf(']')
        if (closing <= 1) return null
        val literal = authority.substring(1, closing)
        if (literal.none { it == ':' } || literal.any { it !in "0123456789abcdefABCDEF:." }) return null
        val portValue = authority.substring(closing + 1).parseEndpointPortOrNull() ?: return null
        "[${literal.lowercase()}]" to portValue
    } else {
        if (authority.count { it == ':' } > 1) return null
        val separator = authority.indexOf(':')
        val hostValue = (if (separator < 0) authority else authority.substring(0, separator)).lowercase()
        if (hostValue.isBlank() || hostValue.any { it !in 'a'..'z' && it !in '0'..'9' && it !in ".-" }) {
            return null
        }
        val portValue = (if (separator < 0) "" else authority.substring(separator)).parseEndpointPortOrNull()
            ?: return null
        hostValue to portValue
    }
    val canonicalPort = when {
        port == NO_EXPLICIT_ENDPOINT_PORT -> ""
        scheme == "https" && port == 443 -> ""
        scheme == "http" && port == 80 -> ""
        else -> ":$port"
    }
    return ParsedSelfHostedOrigin(
        scheme = scheme,
        host = host.removePrefix("[").removeSuffix("]"),
        canonicalValue = "$scheme://$host$canonicalPort",
    )
}

/** Empty means no explicit port; every non-empty value must be canonical. */
private fun String.parseEndpointPortOrNull(): Int? = when {
    isEmpty() -> NO_EXPLICIT_ENDPOINT_PORT
    !startsWith(':') -> null
    else -> drop(1).toIntOrNull()?.takeIf { it in 1..65_535 && ":$it" == this }
}

private const val NO_EXPLICIT_ENDPOINT_PORT: Int = -1

private fun redactSelfHostedSecretWords(message: String): String =
    message
        .replace(Regex("(?i)(password|token|secret|recovery\\s*code|recoveryCode)\\s*[:=]\\s*\\S+"), "$1=redacted")
        .replace(Regex("(?i)super-secret|correct-password|bad-password"), "redacted")
        .replace(Regex("SOMEDAY-[A-Za-z0-9-]+"), "SOMEDAY-REDACTED")
