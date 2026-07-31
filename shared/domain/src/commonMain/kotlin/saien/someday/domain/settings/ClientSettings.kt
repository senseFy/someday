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
    val webDavEndpoint: String? = null,
    val webDavUsername: String? = null,
    val webDavAppDirectory: String = WebDavDefaults.appDirectory,
    val webDavLastTest: WebDavConnectionStatus? = null,
    val webDavAutoBackupEnabled: Boolean = false,
    val webDavAutoBackupFrequency: WebDavAutoBackupFrequency = WebDavAutoBackupFrequency.Daily,
    val webDavLastBackup: WebDavBackupStatus? = null,
    val selfHostedEndpoint: String? = null,
    val selfHostedSession: SelfHostedSessionSummary = SelfHostedSessionSummary(),
    val lastError: String? = null,
    val lastErrorCode: SyncErrorCode? = null,
)

enum class SyncMode {
    Off,
    WebDav,
    SelfHosted,
}

enum class SyncErrorCode {
    WebDavWorkspaceKeyMismatch,
}

object WebDavDefaults {
    const val appDirectory: String = "/someday/"
}

enum class WebDavAutoBackupFrequency {
    Daily,
    Weekly,
}

data class WebDavConnectionInput(
    val endpoint: String,
    val username: String? = null,
    val password: String? = null,
    val appDirectory: String = WebDavDefaults.appDirectory,
) {
    fun sanitized(): WebDavConnectionInput =
        copy(
            endpoint = endpoint.trim().trimEnd('/'),
            username = username?.trim()?.takeIf { it.isNotBlank() },
            password = password?.takeIf { it.isNotBlank() },
            appDirectory = normalizeWebDavAppDirectory(appDirectory),
        )

    fun validate(): List<String> {
        val sanitized = sanitized()
        return buildList {
            if (sanitized.endpoint.isBlank()) {
                add("WebDAV endpoint is required.")
            }
            if (!sanitized.endpoint.startsWith("http://") && !sanitized.endpoint.startsWith("https://")) {
                add("WebDAV endpoint must use http:// or https://.")
            } else if (!isSecureSyncEndpoint(sanitized.endpoint)) {
                add("WebDAV requires HTTPS unless the server is on this device's loopback interface.")
            }
            if (sanitized.appDirectory != WebDavDefaults.appDirectory && !sanitized.appDirectory.startsWith("/")) {
                add("WebDAV app directory must be absolute.")
            }
            if (sanitized.appDirectory.contains("..")) {
                add("WebDAV app directory must stay within the app-owned path.")
            }
        }
    }

    fun redactedDescription(): String =
        "endpoint=${endpoint.trim().ifBlank { "missing" }} " +
            "username=${username?.trim()?.takeIf { it.isNotBlank() } ?: "anonymous"} " +
            "password=${if (password.isNullOrBlank()) "not-provided" else "redacted"} " +
            "appDirectory=${normalizeWebDavAppDirectory(appDirectory)}"
}

data class WebDavConnectionStatus(
    val ready: Boolean,
    val message: String,
    val appDirectory: String = WebDavDefaults.appDirectory,
) {
    init {
        require(!message.contains("password", ignoreCase = true) || message.contains("redacted", ignoreCase = true)) {
            "WebDAV connection status must not expose credential secrets."
        }
    }
}

data class WebDavConnectionTestResult(
    val success: Boolean,
    val status: WebDavConnectionStatus,
) {
    companion object {
        fun validationFailed(errors: List<String>): WebDavConnectionTestResult =
            WebDavConnectionTestResult(
                success = false,
                status = WebDavConnectionStatus(
                    ready = false,
                    message = errors.joinToString(separator = " "),
                ),
            )
    }
}

fun interface WebDavConnectionTester {
    fun testConnection(input: WebDavConnectionInput): WebDavConnectionTestResult
}

interface WebDavCredentialStore {
    fun load(): String?

    fun save(secret: String)

    fun clear()

    /**
     * Loads the complete credential tuple for a retained V2 authority.  This
     * is deliberately separate from [load]: endpoint migration must be able
     * to authenticate both the old read-only authority and the configured
     * target without treating one mutable "current password" slot as causal
     * state.
     */
    fun loadForAuthority(authorityBindingId: String): WebDavAuthorityCredentials? = null

    /** Stores an authority-scoped tuple in the platform secure store. */
    fun saveForAuthority(credentials: WebDavAuthorityCredentials) {
        error("Authority-scoped WebDAV credential storage is unavailable in this build.")
    }

    /** Removes one expired authority tuple without touching the current slot. */
    fun clearAuthority(authorityBindingId: String) = Unit

    fun hasSavedCredential(): Boolean = load()?.isNotBlank() == true
}

data class WebDavAuthorityCredentials(
    val authorityBindingId: String,
    val endpoint: String,
    val username: String,
    val appDirectory: String,
    val secret: String,
) {
    init {
        require(authorityBindingId == webDavV2AuthorityBindingId(endpoint, appDirectory)) {
            "WebDAV authority credential does not match its endpoint binding."
        }
        require(username.isNotBlank()) { "WebDAV authority username must not be blank." }
        require(secret.isNotBlank()) { "WebDAV authority credential must not be blank." }
    }

    fun encodeForSecureStorage(): String = listOf(
        WebDavAuthorityCredentialsStorageVersion,
        authorityBindingId,
        endpoint.trim().trimEnd('/'),
        username,
        normalizeWebDavAppDirectory(appDirectory),
        secret,
    ).joinToString(separator = "\n") { Base64.encode(it.encodeToByteArray()) }
}

fun decodeWebDavAuthorityCredentials(value: String): WebDavAuthorityCredentials? {
    val decoded = buildList {
        value.lineSequence().filter(String::isNotBlank).forEach { line ->
            add(runCatching { Base64.decode(line).decodeToString() }.getOrNull() ?: return null)
        }
    }
    if (decoded.size != 6 || decoded[0] != WebDavAuthorityCredentialsStorageVersion) return null
    return runCatching {
        WebDavAuthorityCredentials(
            authorityBindingId = decoded[1],
            endpoint = decoded[2],
            username = decoded[3],
            appDirectory = decoded[4],
            secret = decoded[5],
        )
    }.getOrNull()
}

fun webDavV2AuthorityBindingId(endpoint: String, appDirectory: String): String =
    "webdav-log-v2|${endpoint.trim().trimEnd('/')}|${normalizeWebDavAppDirectory(appDirectory)}"

private const val WebDavAuthorityCredentialsStorageVersion = "webdav-authority-credential-v2"

object UnavailableWebDavCredentialStore : WebDavCredentialStore {
    override fun load(): String? = null

    override fun save(secret: String) {
        require(secret.isNotBlank()) { "WebDAV credential must not be blank." }
        error("WebDAV credential storage is unavailable in this build.")
    }

    override fun clear() = Unit
}

data class WebDavBackupResult(
    val success: Boolean,
    val message: String,
    val notebookCount: Int = 0,
    val noteCount: Int = 0,
    val version: WebDavBackupVersion? = null,
) {
    companion object {
        fun failure(message: String): WebDavBackupResult =
            WebDavBackupResult(success = false, message = redactWebDavSecretWords(message))
    }
}

data class WebDavBackupStatus(
    val success: Boolean,
    val message: String,
    val versionLabel: String? = null,
    val completedAtEpochMillis: Long? = null,
) {
    init {
        require(!message.contains("password", ignoreCase = true) || message.contains("redacted", ignoreCase = true)) {
            "WebDAV backup status must not expose credential secrets."
        }
    }
}

data class WebDavBackupVersion(
    val id: String,
    val label: String,
    val path: String?,
)

data class WebDavBackupListResult(
    val success: Boolean,
    val message: String,
    val versions: List<WebDavBackupVersion> = emptyList(),
) {
    companion object {
        fun failure(message: String): WebDavBackupListResult =
            WebDavBackupListResult(success = false, message = redactWebDavSecretWords(message))
    }
}

data class WebDavRestoreResult(
    val success: Boolean,
    val message: String,
    val notebooksCreated: Int = 0,
    val notebooksReused: Int = 0,
    val notesCreated: Int = 0,
    val notesUpdated: Int = 0,
    val notesMerged: Int = 0,
    val noteConflictsCreated: Int = 0,
    val notesSkipped: Int = 0,
) {
    companion object {
        fun failure(message: String): WebDavRestoreResult =
            WebDavRestoreResult(success = false, message = redactWebDavSecretWords(message))
    }
}

fun interface WebDavBackupRunner {
    fun backup(input: WebDavConnectionInput): WebDavBackupResult
}

fun interface WebDavRestoreRunner {
    fun restore(
        input: WebDavConnectionInput,
        backupPath: String?,
    ): WebDavRestoreResult
}

fun interface WebDavBackupCatalogRunner {
    fun listBackups(input: WebDavConnectionInput): WebDavBackupListResult
}

data class WebDavDiscoveredDevice(
    val deviceId: String,
    val firstSeenAtEpochMillis: Long?,
    val lastSeenAtEpochMillis: Long?,
    val isCurrentDevice: Boolean,
)

data class WebDavDiscoveredDevicesResult(
    val success: Boolean,
    val devices: List<WebDavDiscoveredDevice> = emptyList(),
    val message: String,
) {
    companion object {
        fun success(devices: List<WebDavDiscoveredDevice>) =
            WebDavDiscoveredDevicesResult(true, devices, "Discovered ${devices.size} WebDAV device(s).")

        fun failure(message: String) = WebDavDiscoveredDevicesResult(false, message = message)
    }
}

fun interface WebDavDiscoveredDevicesRunner {
    fun listDiscoveredDevices(): WebDavDiscoveredDevicesResult
}

fun normalizeWebDavAppDirectory(value: String): String {
    val trimmed = value.trim().ifBlank { WebDavDefaults.appDirectory }
    val withLeadingSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    return if (withLeadingSlash.endsWith("/")) withLeadingSlash else "$withLeadingSlash/"
}

private fun redactWebDavSecretWords(message: String): String =
    if (message.contains("password", ignoreCase = true) ||
        message.contains("token", ignoreCase = true) ||
        message.contains("secret", ignoreCase = true)
    ) {
        "WebDAV operation failed; credentials redacted."
    } else {
        message
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

fun SelfHostedSessionCredentials.encodeForSecureStorage(): String =
    listOf(
        SelfHostedSessionCredentialsStorageVersion,
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

fun decodeSelfHostedSessionCredentials(value: String): SelfHostedSessionCredentials? {
    val decoded = buildList {
        value.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val decodedLine = runCatching { Base64.decode(line).decodeToString() }.getOrNull()
                    ?: return null
                add(decodedLine)
            }
    }
    if (decoded.size != 9 || decoded[0] != SelfHostedSessionCredentialsStorageVersion) {
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

private const val SelfHostedSessionCredentialsStorageVersion = "self-hosted-session-v2"

interface SelfHostedSessionCredentialStore {
    fun load(): SelfHostedSessionCredentials?

    fun save(credentials: SelfHostedSessionCredentials)

    fun clear()

    fun loadForAuthority(authorityBindingId: String): SelfHostedSessionCredentials? =
        load()?.takeIf { selfHostedV2AuthorityBindingId(it.endpoint) == authorityBindingId }

    fun saveForAuthority(authorityBindingId: String, credentials: SelfHostedSessionCredentials) {
        require(authorityBindingId == selfHostedV2AuthorityBindingId(credentials.endpoint)) {
            "Self-hosted authority credential does not match its endpoint binding."
        }
        val current = load()
        if (current == null || selfHostedV2AuthorityBindingId(current.endpoint) == authorityBindingId) {
            save(credentials)
        } else {
            error("Authority-scoped self-hosted credential storage is unavailable in this build.")
        }
    }

    fun clearAuthority(authorityBindingId: String) = Unit
}

fun selfHostedV2AuthorityBindingId(endpoint: String): String =
    "self-hosted-v2|${normalizeSelfHostedEndpoint(endpoint)}"

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

    fun validate(): List<String> {
        val sanitized = sanitized()
        return buildList {
            if (sanitized.endpoint.isBlank()) {
                add("Self-hosted endpoint is required.")
            }
            if (!sanitized.endpoint.startsWith("http://") && !sanitized.endpoint.startsWith("https://")) {
                add("Self-hosted endpoint must use http:// or https://.")
            } else if (!isSecureSyncEndpoint(sanitized.endpoint)) {
                add("Self-hosted requires HTTPS unless the server is on this device's loopback interface.")
            }
            if (sanitized.email.isBlank() || "@" !in sanitized.email || "." !in sanitized.email.substringAfter("@")) {
                add("Self-hosted email is invalid.")
            }
            if (password.length < 8) {
                add("Self-hosted password must be at least 8 characters.")
            }
            if (sanitized.deviceName.isBlank()) {
                add("Self-hosted device name is required.")
            }
            if (sanitized.platform.isBlank()) {
                add("Self-hosted device platform is required.")
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

data class SelfHostedSetupStatus(
    val ready: Boolean,
    val message: String,
) {
    init {
        require(!message.contains("password", ignoreCase = true) || message.contains("redacted", ignoreCase = true)) {
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

        fun failure(message: String): SelfHostedSetupResult =
            SelfHostedSetupResult(
                success = false,
                status = SelfHostedSetupStatus(
                    ready = false,
                    message = redactSelfHostedSecretWords(message),
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
    val message: String,
    val errorCode: SyncErrorCode? = null,
) {
    companion object {
        fun success(
            mode: SyncMode,
            pushedObjects: Int,
            pulledObjects: Int,
            conflicts: Int,
            message: String,
        ): ManualSyncResult =
            ManualSyncResult(
                success = true,
                mode = mode,
                pushedObjects = pushedObjects,
                pulledObjects = pulledObjects,
                conflicts = conflicts,
                message = message,
                errorCode = null,
            )

        fun failure(
            mode: SyncMode,
            message: String,
            pushedObjects: Int = 0,
            pulledObjects: Int = 0,
            conflicts: Int = 0,
            errorCode: SyncErrorCode? = null,
        ): ManualSyncResult =
            ManualSyncResult(
                success = false,
                mode = mode,
                pushedObjects = pushedObjects,
                pulledObjects = pulledObjects,
                conflicts = conflicts,
                message = redactSelfHostedSecretWords(message),
                errorCode = errorCode,
            )
    }
}

fun interface ManualSyncRunner {
    fun run(): ManualSyncResult
}

/**
 * Typed mid-run progress for manual sync (e.g. first-epoch checkpoint upload).
 * Product copy is formatted by the UI layer; this surface carries no secrets.
 */
sealed interface ManualSyncPhase {
    data class UploadingChunks(val completed: Int, val total: Int) : ManualSyncPhase
    data object UploadingManifest : ManualSyncPhase
    data object VerifyingRemote : ManualSyncPhase
    data object CommittingPointer : ManualSyncPhase
}

/**
 * Optional mid-run progress for manual sync.
 * Implementations must be safe to call from background threads; UI layers hop to Main.
 */
fun interface ManualSyncProgressListener {
    fun onProgress(phase: ManualSyncPhase)
}

/** Explicit maintenance operations for an already active causal-sync epoch. */
interface SyncV2MaintenanceRunner {
    fun rollEpoch(): ManualSyncResult

    fun repairIntegrity(): ManualSyncResult

    /**
     * Last-resort recovery after exact repair is impossible. The caller must
     * obtain explicit user confirmation because unseen remote branches may be
     * absent from the verified local checkpoint.
     */
    fun recoverWithVerifiedLocalCheckpoint(userConfirmedPotentialDataLoss: Boolean): ManualSyncResult =
        ManualSyncResult.failure(
            SyncMode.Off,
            "Authorized V2 checkpoint recovery is unavailable in this build.",
        )

    fun migrateToConfiguredRemote(): ManualSyncResult = ManualSyncResult.failure(
        SyncMode.Off,
        "Explicit V2 remote migration is unavailable in this build.",
    )

    fun collectExpiredLocalHistory(): ManualSyncResult = ManualSyncResult.failure(
        SyncMode.Off,
        "V2 retention maintenance is unavailable in this build.",
    )
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
    val message: String,
    val packageData: WorkspaceJoinPackage? = null,
) {
    companion object {
        fun success(
            message: String,
            packageData: WorkspaceJoinPackage? = null,
        ): WorkspaceJoinResult =
            WorkspaceJoinResult(
                success = true,
                message = message,
                packageData = packageData,
            )

        fun failure(message: String): WorkspaceJoinResult =
            WorkspaceJoinResult(
                success = false,
                message = redactSelfHostedSecretWords(message),
            )
    }
}

fun interface WorkspaceJoinPackageProvider {
    fun createPackage(): WorkspaceJoinResult
}

fun interface WorkspaceJoiner {
    fun join(packageData: WorkspaceJoinPackage): WorkspaceJoinResult
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
    val message: String,
    val invitation: WorkspacePairingInvitation? = null,
) {
    companion object {
        fun success(
            message: String,
            invitation: WorkspacePairingInvitation,
        ): WorkspacePairingInvitationResult =
            WorkspacePairingInvitationResult(
                success = true,
                message = message,
                invitation = invitation,
            )

        fun failure(message: String): WorkspacePairingInvitationResult =
            WorkspacePairingInvitationResult(
                success = false,
                message = redactSelfHostedSecretWords(message),
            )
    }
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

data class ManualSyncProgress(
    val running: Boolean,
    val mode: SyncMode,
    val message: String,
    val pushedObjects: Int = 0,
    val pulledObjects: Int = 0,
    val conflicts: Int = 0,
) {
    companion object {
        fun idle(mode: SyncMode = SyncMode.Off): ManualSyncProgress =
            ManualSyncProgress(
                running = false,
                mode = mode,
                message = "Ready when sync is configured.",
            )

        fun inProgress(
            mode: SyncMode,
            message: String = "Syncing changes now.",
        ): ManualSyncProgress =
            ManualSyncProgress(
                running = true,
                mode = mode,
                message = message,
            )

        fun fromResult(result: ManualSyncResult): ManualSyncProgress =
            ManualSyncProgress(
                running = false,
                mode = result.mode,
                message = result.message,
                pushedObjects = result.pushedObjects,
                pulledObjects = result.pulledObjects,
                conflicts = result.conflicts,
            )
    }
}

fun normalizeSelfHostedEndpoint(value: String): String =
    value.trim().trimEnd('/')

/** Credentials and mutations may use plaintext HTTP only over true loopback. */
fun isSecureSyncEndpoint(value: String): Boolean {
    val endpoint = value.trim().trimEnd('/')
    if (endpoint.startsWith("https://")) return true
    if (!endpoint.startsWith("http://")) return false
    val authority = endpoint.removePrefix("http://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    if (authority.isBlank() || '@' in authority) return false
    val host = if (authority.startsWith('[')) {
        val closingBracket = authority.indexOf(']')
        if (closingBracket <= 1) return false
        val suffix = authority.substring(closingBracket + 1)
        if (suffix.isNotEmpty() && !suffix.isValidEndpointPortSuffix()) return false
        authority.substring(1, closingBracket).lowercase()
    } else {
        if (authority.count { it == ':' } > 1) return false
        val suffix = authority.substringAfter(':', missingDelimiterValue = "")
        if (':' in authority && !":$suffix".isValidEndpointPortSuffix()) return false
        authority.substringBefore(':').lowercase()
    }
    return host == "localhost" || host.endsWith(".localhost") || host == "::1" ||
        host.split('.').let { parts ->
            parts.size == 4 && parts.firstOrNull() == "127" && parts.all { part ->
                part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
}

private fun String.isValidEndpointPortSuffix(): Boolean =
    startsWith(':') && drop(1).toIntOrNull()?.let { it in 1..65_535 } == true

private fun redactSelfHostedSecretWords(message: String): String =
    message
        .replace(Regex("(?i)(password|token|secret|recovery\\s*code|recoveryCode)\\s*[:=]\\s*\\S+"), "$1=redacted")
        .replace(Regex("(?i)super-secret|correct-password|bad-password"), "redacted")
        .replace(Regex("SOMEDAY-[A-Za-z0-9-]+"), "SOMEDAY-REDACTED")
