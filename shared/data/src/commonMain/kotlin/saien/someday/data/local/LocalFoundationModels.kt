@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.local

import kotlin.time.Instant

data class SettingsEntry(
    val key: String,
    val value: String,
    val updatedAt: Instant,
)

data class Device(
    val id: String,
    val name: String,
    val platform: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val workspaceKeyMetadata: String?,
)
