package saien.someday.data.settings

import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.EditorPreferences
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.normalizeSelfHostedEndpoint

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
                selfHostedEndpoint = readNullableString(ClientSettingsKeys.SYNC_SELF_HOSTED_ENDPOINT)
                    ?.let(::normalizeSelfHostedEndpoint),
                selfHostedSession = readSelfHostedSession(),
                lastError = readNullableString(ClientSettingsKeys.SYNC_LAST_ERROR),
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
    const val SYNC_SELF_HOSTED_ENDPOINT = "client.sync.self_hosted_endpoint"
    const val SYNC_SELF_HOSTED_LOGGED_IN = "client.sync.self_hosted_logged_in"
    const val SYNC_SELF_HOSTED_USER_EMAIL = "client.sync.self_hosted_user_email"
    const val SYNC_SELF_HOSTED_DEVICE_ID = "client.sync.self_hosted_device_id"
    const val SYNC_SELF_HOSTED_DEVICE_NAME = "client.sync.self_hosted_device_name"
    const val SYNC_SELF_HOSTED_DEVICE_PLATFORM = "client.sync.self_hosted_device_platform"
    const val SYNC_LAST_ERROR = "client.sync.last_error"
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
    ClientSettingsKeys.SYNC_SELF_HOSTED_ENDPOINT to
        syncConfiguration.selfHostedEndpoint?.let(::normalizeSelfHostedEndpoint).orEmpty(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_LOGGED_IN to syncConfiguration.selfHostedSession.loggedIn.toString(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_USER_EMAIL to syncConfiguration.selfHostedSession.userEmail.orEmpty(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_DEVICE_ID to syncConfiguration.selfHostedSession.deviceId.orEmpty(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_DEVICE_NAME to syncConfiguration.selfHostedSession.deviceName.orEmpty(),
    ClientSettingsKeys.SYNC_SELF_HOSTED_DEVICE_PLATFORM to syncConfiguration.selfHostedSession.devicePlatform.orEmpty(),
    ClientSettingsKeys.SYNC_LAST_ERROR to syncConfiguration.lastError.orEmpty(),
)

private fun String?.toClientTheme(): ClientTheme =
    ClientTheme.entries.firstOrNull { it.name == this } ?: ClientTheme.System

private fun String?.toAppLanguage(): AppLanguage =
    AppLanguage.entries.firstOrNull { it.name == this } ?: AppLanguage.System

private fun String?.toSyncMode(): SyncMode =
    SyncMode.entries.firstOrNull { it.name == this } ?: SyncMode.Off

private fun String?.toBooleanOrDefault(default: Boolean): Boolean =
    when (this?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }

private fun String?.toIntInRangeOrDefault(
    default: Int,
    range: IntRange,
): Int =
    this?.toIntOrNull()?.takeIf { it in range } ?: default
