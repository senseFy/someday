package saien.someday.data.settings

import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.EditorPreferences
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncErrorCode
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WebDavAutoBackupFrequency
import saien.someday.domain.settings.WebDavBackupStatus
import saien.someday.domain.settings.WebDavConnectionStatus
import saien.someday.domain.settings.WebDavDefaults
import saien.someday.domain.settings.normalizeSelfHostedEndpoint
import saien.someday.domain.settings.normalizeWebDavAppDirectory

interface ClientSettingsRepository {
    fun load(): ClientSettings

    fun save(settings: ClientSettings): ClientSettings

    /** Internal projection/device-local write used by whole-product V2. */
    fun saveLocalSnapshot(settings: ClientSettings): ClientSettings = save(settings)
}

class SqlDelightClientSettingsRepository(
    private val localRepository: SqlDelightLocalDataRepository,
) : ClientSettingsRepository {
    override fun load(): ClientSettings =
        ClientSettings(
            theme = readString(ClientSettingsKeys.THEME).toClientTheme(),
            appLanguage = readString(ClientSettingsKeys.APP_LANGUAGE).toAppLanguage(),
            editorPreferences = EditorPreferences(
                previewByDefault = readString(ClientSettingsKeys.EDITOR_PREVIEW_BY_DEFAULT)
                    .toBooleanOrDefault(EditorPreferences().previewByDefault),
                markdownToolbarVisible = readString(ClientSettingsKeys.EDITOR_MARKDOWN_TOOLBAR_VISIBLE)
                    .toBooleanOrDefault(EditorPreferences().markdownToolbarVisible),
            ),
            onThisDayNotifications = OnThisDayNotificationPreferences(
                enabled = readString(ClientSettingsKeys.ON_THIS_DAY_NOTIFICATIONS_ENABLED)
                    .toBooleanOrDefault(false),
                hour = readString(ClientSettingsKeys.ON_THIS_DAY_NOTIFICATIONS_HOUR)
                    .toIntInRangeOrDefault(
                        default = OnThisDayNotificationPreferences.DefaultHour,
                        range = 0..23,
                    ),
                minute = readString(ClientSettingsKeys.ON_THIS_DAY_NOTIFICATIONS_MINUTE)
                    .toIntInRangeOrDefault(
                        default = OnThisDayNotificationPreferences.DefaultMinute,
                        range = 0..59,
                    ),
            ),
            defaultNotebookId = readNullableString(ClientSettingsKeys.DEFAULT_NOTEBOOK_ID),
            lastSelectedNotebookId = readNullableString(ClientSettingsKeys.LAST_SELECTED_NOTEBOOK_ID),
            activeDeviceId = readString(ClientSettingsKeys.ACTIVE_DEVICE_ID)
                ?.takeIf { it.isNotBlank() }
                ?: ClientSettings.DefaultActiveDeviceId,
            syncConfiguration = SyncConfiguration(
                mode = readString(ClientSettingsKeys.SYNC_MODE).toSyncMode(),
                webDavEndpoint = readNullableString(ClientSettingsKeys.SYNC_WEBDAV_ENDPOINT),
                webDavUsername = readNullableString(ClientSettingsKeys.SYNC_WEBDAV_USERNAME),
                webDavAppDirectory = normalizeWebDavAppDirectory(
                    readNullableString(ClientSettingsKeys.SYNC_WEBDAV_APP_DIRECTORY)
                        ?: WebDavDefaults.appDirectory,
                ),
                webDavLastTest = readWebDavLastTest(),
                webDavAutoBackupEnabled = readString(ClientSettingsKeys.SYNC_WEBDAV_AUTO_BACKUP_ENABLED)
                    .toBooleanOrDefault(false),
                webDavAutoBackupFrequency = readString(ClientSettingsKeys.SYNC_WEBDAV_AUTO_BACKUP_FREQUENCY)
                    .toWebDavAutoBackupFrequency(),
                webDavLastBackup = readWebDavLastBackup(),
                selfHostedEndpoint = readNullableString(ClientSettingsKeys.SYNC_SELF_HOSTED_ENDPOINT)
                    ?.let(::normalizeSelfHostedEndpoint),
                selfHostedSession = readSelfHostedSession(),
                lastError = readNullableString(ClientSettingsKeys.SYNC_LAST_ERROR),
                lastErrorCode = readString(ClientSettingsKeys.SYNC_LAST_ERROR_CODE).toSyncErrorCode(),
            ),
        )

    override fun save(settings: ClientSettings): ClientSettings = saveValues(settings, localOnly = false)

    override fun saveLocalSnapshot(settings: ClientSettings): ClientSettings = saveValues(settings, localOnly = true)

    private fun saveValues(settings: ClientSettings, localOnly: Boolean): ClientSettings {
        val values = settings.toStoredValues()
        localRepository.database.transaction {
            values.forEach { (key, value) ->
                if (localOnly) localRepository.putLocalOnlySetting(key, value)
                else localRepository.putSetting(key, value)
            }
        }
        return load()
    }

    private fun readString(key: String): String? = localRepository.getSetting(key)?.value

    private fun readNullableString(key: String): String? = readString(key)?.takeIf { it.isNotBlank() }

    private fun readWebDavLastTest(): WebDavConnectionStatus? {
        val ready = readString(ClientSettingsKeys.SYNC_WEBDAV_LAST_TEST_READY).toBooleanOrNull() ?: return null
        val message = readNullableString(ClientSettingsKeys.SYNC_WEBDAV_LAST_TEST_MESSAGE) ?: return null
        return WebDavConnectionStatus(
            ready = ready,
            message = message,
            appDirectory = normalizeWebDavAppDirectory(
                readNullableString(ClientSettingsKeys.SYNC_WEBDAV_APP_DIRECTORY)
                    ?: WebDavDefaults.appDirectory,
            ),
        )
    }

    private fun readSelfHostedSession(): SelfHostedSessionSummary {
        val loggedIn = readString(ClientSettingsKeys.SYNC_SELF_HOSTED_LOGGED_IN).toBooleanOrDefault(false)
        return SelfHostedSessionSummary(
            loggedIn = loggedIn,
            userEmail = readNullableString(ClientSettingsKeys.SYNC_SELF_HOSTED_USER_EMAIL),
            deviceId = readNullableString(ClientSettingsKeys.SYNC_SELF_HOSTED_DEVICE_ID),
            deviceName = readNullableString(ClientSettingsKeys.SYNC_SELF_HOSTED_DEVICE_NAME),
            devicePlatform = readNullableString(ClientSettingsKeys.SYNC_SELF_HOSTED_DEVICE_PLATFORM),
        )
    }

    private fun readWebDavLastBackup(): WebDavBackupStatus? {
        val success = readString(ClientSettingsKeys.SYNC_WEBDAV_LAST_BACKUP_SUCCESS).toBooleanOrNull() ?: return null
        val message = readNullableString(ClientSettingsKeys.SYNC_WEBDAV_LAST_BACKUP_MESSAGE) ?: return null
        return WebDavBackupStatus(
            success = success,
            message = message,
            versionLabel = readNullableString(ClientSettingsKeys.SYNC_WEBDAV_LAST_BACKUP_VERSION),
            completedAtEpochMillis = readNullableString(ClientSettingsKeys.SYNC_WEBDAV_LAST_BACKUP_COMPLETED_AT)?.toLongOrNull(),
        )
    }
}

object ClientSettingsKeys {
    const val THEME = "client.theme"
    const val APP_LANGUAGE = "client.language"
    const val EDITOR_PREVIEW_BY_DEFAULT = "client.editor.preview_by_default"
    const val EDITOR_MARKDOWN_TOOLBAR_VISIBLE = "client.editor.markdown_toolbar_visible"
    const val ON_THIS_DAY_NOTIFICATIONS_ENABLED = "client.notifications.on_this_day.enabled"
    const val ON_THIS_DAY_NOTIFICATIONS_HOUR = "client.notifications.on_this_day.hour"
    const val ON_THIS_DAY_NOTIFICATIONS_MINUTE = "client.notifications.on_this_day.minute"
    const val DEFAULT_NOTEBOOK_ID = "client.default_notebook_id"
    const val LAST_SELECTED_NOTEBOOK_ID = "client.last_selected_notebook_id"
    const val ACTIVE_DEVICE_ID = "client.active_device_id"
    const val SYNC_MODE = "client.sync.mode"
    const val SYNC_WEBDAV_ENDPOINT = "client.sync.webdav_endpoint"
    const val SYNC_WEBDAV_USERNAME = "client.sync.webdav_username"
    const val SYNC_WEBDAV_APP_DIRECTORY = "client.sync.webdav_app_directory"
    const val SYNC_WEBDAV_LAST_TEST_READY = "client.sync.webdav_last_test_ready"
    const val SYNC_WEBDAV_LAST_TEST_MESSAGE = "client.sync.webdav_last_test_message"
    const val SYNC_WEBDAV_AUTO_BACKUP_ENABLED = "client.sync.webdav_auto_backup_enabled"
    const val SYNC_WEBDAV_AUTO_BACKUP_FREQUENCY = "client.sync.webdav_auto_backup_frequency"
    const val SYNC_WEBDAV_LAST_BACKUP_SUCCESS = "client.sync.webdav_last_backup_success"
    const val SYNC_WEBDAV_LAST_BACKUP_MESSAGE = "client.sync.webdav_last_backup_message"
    const val SYNC_WEBDAV_LAST_BACKUP_VERSION = "client.sync.webdav_last_backup_version"
    const val SYNC_WEBDAV_LAST_BACKUP_COMPLETED_AT = "client.sync.webdav_last_backup_completed_at"
    const val SYNC_SELF_HOSTED_ENDPOINT = "client.sync.self_hosted_endpoint"
    const val SYNC_SELF_HOSTED_LOGGED_IN = "client.sync.self_hosted_logged_in"
    const val SYNC_SELF_HOSTED_USER_EMAIL = "client.sync.self_hosted_user_email"
    const val SYNC_SELF_HOSTED_DEVICE_ID = "client.sync.self_hosted_device_id"
    const val SYNC_SELF_HOSTED_DEVICE_NAME = "client.sync.self_hosted_device_name"
    const val SYNC_SELF_HOSTED_DEVICE_PLATFORM = "client.sync.self_hosted_device_platform"
    const val SYNC_LAST_ERROR = "client.sync.last_error"
    const val SYNC_LAST_ERROR_CODE = "client.sync.last_error_code"
}

private fun ClientSettings.toStoredValues(): Map<String, String> = linkedMapOf(
    ClientSettingsKeys.THEME to theme.name,
    ClientSettingsKeys.APP_LANGUAGE to appLanguage.name,
    ClientSettingsKeys.EDITOR_PREVIEW_BY_DEFAULT to editorPreferences.previewByDefault.toString(),
    ClientSettingsKeys.EDITOR_MARKDOWN_TOOLBAR_VISIBLE to editorPreferences.markdownToolbarVisible.toString(),
    ClientSettingsKeys.ON_THIS_DAY_NOTIFICATIONS_ENABLED to onThisDayNotifications.enabled.toString(),
    ClientSettingsKeys.ON_THIS_DAY_NOTIFICATIONS_HOUR to onThisDayNotifications.hour.toString(),
    ClientSettingsKeys.ON_THIS_DAY_NOTIFICATIONS_MINUTE to onThisDayNotifications.minute.toString(),
    ClientSettingsKeys.DEFAULT_NOTEBOOK_ID to defaultNotebookId.orEmpty(),
    ClientSettingsKeys.LAST_SELECTED_NOTEBOOK_ID to lastSelectedNotebookId.orEmpty(),
    ClientSettingsKeys.ACTIVE_DEVICE_ID to activeDeviceId,
    ClientSettingsKeys.SYNC_MODE to syncConfiguration.mode.name,
    ClientSettingsKeys.SYNC_WEBDAV_ENDPOINT to syncConfiguration.webDavEndpoint.orEmpty(),
    ClientSettingsKeys.SYNC_WEBDAV_USERNAME to syncConfiguration.webDavUsername.orEmpty(),
    ClientSettingsKeys.SYNC_WEBDAV_APP_DIRECTORY to normalizeWebDavAppDirectory(syncConfiguration.webDavAppDirectory),
    ClientSettingsKeys.SYNC_WEBDAV_LAST_TEST_READY to syncConfiguration.webDavLastTest?.ready?.toString().orEmpty(),
    ClientSettingsKeys.SYNC_WEBDAV_LAST_TEST_MESSAGE to syncConfiguration.webDavLastTest?.message.orEmpty(),
    ClientSettingsKeys.SYNC_WEBDAV_AUTO_BACKUP_ENABLED to syncConfiguration.webDavAutoBackupEnabled.toString(),
    ClientSettingsKeys.SYNC_WEBDAV_AUTO_BACKUP_FREQUENCY to syncConfiguration.webDavAutoBackupFrequency.name,
    ClientSettingsKeys.SYNC_WEBDAV_LAST_BACKUP_SUCCESS to syncConfiguration.webDavLastBackup?.success?.toString().orEmpty(),
    ClientSettingsKeys.SYNC_WEBDAV_LAST_BACKUP_MESSAGE to syncConfiguration.webDavLastBackup?.message.orEmpty(),
    ClientSettingsKeys.SYNC_WEBDAV_LAST_BACKUP_VERSION to syncConfiguration.webDavLastBackup?.versionLabel.orEmpty(),
    ClientSettingsKeys.SYNC_WEBDAV_LAST_BACKUP_COMPLETED_AT to
        syncConfiguration.webDavLastBackup?.completedAtEpochMillis?.toString().orEmpty(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_ENDPOINT to
        syncConfiguration.selfHostedEndpoint?.let(::normalizeSelfHostedEndpoint).orEmpty(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_LOGGED_IN to syncConfiguration.selfHostedSession.loggedIn.toString(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_USER_EMAIL to syncConfiguration.selfHostedSession.userEmail.orEmpty(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_DEVICE_ID to syncConfiguration.selfHostedSession.deviceId.orEmpty(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_DEVICE_NAME to syncConfiguration.selfHostedSession.deviceName.orEmpty(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_DEVICE_PLATFORM to syncConfiguration.selfHostedSession.devicePlatform.orEmpty(),
    ClientSettingsKeys.SYNC_LAST_ERROR to syncConfiguration.lastError.orEmpty(),
    ClientSettingsKeys.SYNC_LAST_ERROR_CODE to syncConfiguration.lastErrorCode?.name.orEmpty(),
)

private fun String?.toClientTheme(): ClientTheme =
    ClientTheme.entries.firstOrNull { it.name == this } ?: ClientTheme.System

private fun String?.toAppLanguage(): AppLanguage =
    AppLanguage.entries.firstOrNull { it.name == this } ?: AppLanguage.System

private fun String?.toSyncMode(): SyncMode =
    SyncMode.entries.firstOrNull { it.name == this } ?: SyncMode.Off

private fun String?.toSyncErrorCode(): SyncErrorCode? =
    SyncErrorCode.entries.firstOrNull { it.name == this }

private fun String?.toWebDavAutoBackupFrequency(): WebDavAutoBackupFrequency =
    WebDavAutoBackupFrequency.entries.firstOrNull { it.name == this } ?: WebDavAutoBackupFrequency.Daily

private fun String?.toBooleanOrDefault(default: Boolean): Boolean =
    when (this?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }

private fun String?.toBooleanOrNull(): Boolean? =
    when (this?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

private fun String?.toIntInRangeOrDefault(
    default: Int,
    range: IntRange,
): Int =
    this?.toIntOrNull()?.takeIf { it in range } ?: default
