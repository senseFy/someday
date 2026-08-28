@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.local

import saien.someday.data.local.db.SomedayDatabase
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Small device-local foundation store.
 *
 * Product notes, notebooks, deletions, and synchronized workspace preferences
 * deliberately do not pass through this class. Their only durable product
 * truth is the workspace entity DAG owned by the sync module.
 */
class SqlDelightLocalDataRepository(
    val database: SomedayDatabase,
    deviceId: String,
    private val clock: () -> Instant = { Clock.System.now() },
) {
    private val queries = database.somedayQueries

    val localDeviceId: String = deviceId.also {
        require(it.isNotBlank()) { "Local device id must not be blank." }
    }

    fun putSetting(
        key: String,
        value: String,
    ): SettingsEntry = putLocalOnlySetting(key, value)

    fun putLocalOnlySetting(
        key: String,
        value: String,
    ): SettingsEntry {
        require(key.isNotBlank()) { "Setting key must not be blank." }
        queries.insertOrReplaceSetting(
            id = key,
            value_ = value,
            updated_at = clock().toEpochMilliseconds(),
        )
        return checkNotNull(getSetting(key))
    }

    fun deleteLocalOnlySetting(key: String) {
        require(key.isNotBlank()) { "Setting key must not be blank." }
        queries.deleteSetting(key)
    }

    fun getSetting(key: String): SettingsEntry? =
        queries.selectSetting(key) { id, value, updatedAt ->
            SettingsEntry(
                key = id,
                value = value,
                updatedAt = Instant.fromEpochMilliseconds(updatedAt),
            )
        }.executeAsOneOrNull()

    fun registerDevice(
        id: String = localDeviceId,
        name: String,
        platform: String,
        workspaceKeyMetadata: String? = null,
    ): Device {
        require(id.isNotBlank()) { "Device id must not be blank." }
        require(name.isNotBlank()) { "Device name must not be blank." }
        require(platform.isNotBlank()) { "Device platform must not be blank." }

        val now = clock()
        val existing = getDevice(id)
        queries.insertOrReplaceDevice(
            id = id,
            name = name,
            platform = platform,
            created_at = existing?.createdAt?.toEpochMilliseconds() ?: now.toEpochMilliseconds(),
            last_seen_at = now.toEpochMilliseconds(),
            workspace_key_metadata = workspaceKeyMetadata ?: existing?.workspaceKeyMetadata,
        )
        return checkNotNull(getDevice(id))
    }

    fun persistWorkspaceMetadata(
        settingKey: String,
        metadataJson: String,
        deviceName: String,
        platform: String,
    ) {
        database.transaction {
            putLocalOnlySetting(settingKey, metadataJson)
            registerDevice(
                name = deviceName,
                platform = platform,
                workspaceKeyMetadata = metadataJson,
            )
        }
    }

    fun getDevice(id: String): Device? =
        queries.selectDevice(id) { deviceId, name, platform, createdAt, lastSeenAt, workspaceKeyMetadata ->
            Device(
                id = deviceId,
                name = name,
                platform = platform,
                createdAt = Instant.fromEpochMilliseconds(createdAt),
                lastSeenAt = Instant.fromEpochMilliseconds(lastSeenAt),
                workspaceKeyMetadata = workspaceKeyMetadata,
            )
        }.executeAsOneOrNull()
}
