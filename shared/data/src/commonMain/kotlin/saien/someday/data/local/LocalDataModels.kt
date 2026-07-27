@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.local

import kotlin.time.Instant
import kotlin.time.Clock

enum class EntityType(val storageValue: String) {
    NOTEBOOK("notebook"),
    NOTE("note"),
    NOTE_VERSION("note_version"),
    TOMBSTONE("tombstone"),
    LOCATION("location"),
    SETTING("setting"),
    DEVICE("device"),
    ;

    companion object {
        fun fromStorageValue(value: String): EntityType =
            entries.firstOrNull { it.storageValue == value }
                ?: error("Unknown entity type: $value")
    }
}

enum class SyncState(val storageValue: String) {
    CLEAN("clean"),
    DIRTY("dirty"),
    DELETED("deleted"),
    CONFLICT("conflict"),
    ;

    companion object {
        fun fromStorageValue(value: String): SyncState =
            entries.firstOrNull { it.storageValue == value }
                ?: error("Unknown sync state: $value")
    }
}

enum class ConflictState(val storageValue: String) {
    NONE("none"),
    DELETE_VS_EDIT("delete_vs_edit"),
    MANUAL_RESOLUTION_REQUIRED("manual_resolution_required"),
    ;

    companion object {
        fun fromStorageValue(value: String): ConflictState =
            entries.firstOrNull { it.storageValue == value }
                ?: error("Unknown conflict state: $value")
    }
}

data class Notebook(
    val id: String,
    val title: String,
    val sortOrder: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val revision: Long,
    val syncState: SyncState,
    val contentHash: String,
)

data class Note(
    val id: String,
    val notebookId: String,
    val title: String,
    val markdownBody: String,
    val excerpt: String,
    val searchText: String,
    val timeZoneId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val contentHash: String,
    val revision: Long,
    val syncState: SyncState,
    val currentVersionId: String?,
)

data class NoteVersion(
    val versionId: String,
    val noteId: String,
    val parentVersionId: String?,
    val baseVersionId: String?,
    val revision: Long,
    val title: String,
    val markdownBody: String,
    val contentHash: String,
    val deviceId: String,
    val mergeMetadataJson: String?,
    val createdAt: Instant,
)

data class Tombstone(
    val entityId: String,
    val entityType: EntityType,
    val deletedAt: Instant,
    val deletedByDeviceId: String,
    val lastKnownRevision: Long,
    val purgeAfter: Instant?,
    val dirty: Boolean,
)

data class LocationInput(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Double? = null,
    val altitudeMeters: Double? = null,
    val placeText: String? = null,
    val capturedAt: Instant? = null,
)

data class NoteLocation(
    val noteId: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
    val altitudeMeters: Double?,
    val placeText: String?,
    val capturedAt: Instant,
    val updatedAt: Instant,
    val revision: Long,
    val syncState: SyncState,
)

data class SyncMetadata(
    val entityId: String,
    val entityType: EntityType,
    val localRevision: Long,
    val remoteRevision: Long?,
    val remoteEtag: String?,
    val vectorClockJson: String,
    val dirty: Boolean,
    val conflictState: ConflictState,
    val lastSyncedAt: Instant?,
    val lastError: String?,
    val updatedAt: Instant,
)

data class SettingsEntry(
    val key: String,
    val value: String,
    val updatedAt: Instant,
    val dirty: Boolean,
)

data class Device(
    val id: String,
    val name: String,
    val platform: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val isActive: Boolean,
    val syncCursor: String?,
    val publicKey: String?,
    val workspaceKeyMetadata: String?,
)

data class RemoteNoteSnapshot(
    val id: String,
    val notebookId: String,
    val title: String,
    val markdownBody: String,
    val timeZoneId: String? = null,
    val createdAt: Instant? = null,
    val revision: Long,
    val updatedAt: Instant,
    val deviceId: String,
    val remoteEtag: String? = null,
    val contentHash: String = contentHashForNote(title, markdownBody, createdAt, timeZoneId),
    val currentVersionId: String? = null,
    val parentVersionId: String? = null,
    val baseVersionId: String? = null,
    val mergeMetadataJson: String? = null,
)

data class RemoteNotebookSnapshot(
    val id: String,
    val title: String,
    val sortOrder: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val revision: Long,
    val deviceId: String,
    val remoteEtag: String? = null,
    val contentHash: String = contentHashForNotebook(title),
)

data class RemoteNoteVersionSnapshot(
    val versionId: String,
    val noteId: String,
    val parentVersionId: String?,
    val baseVersionId: String?,
    val revision: Long,
    val title: String,
    val markdownBody: String,
    val contentHash: String,
    val deviceId: String,
    val mergeMetadataJson: String?,
    val createdAt: Instant,
    val remoteEtag: String? = null,
)

data class RemoteTombstoneSnapshot(
    val entityId: String,
    val entityType: EntityType,
    val deletedAt: Instant,
    val deletedByDeviceId: String,
    val lastKnownRevision: Long,
    val purgeAfter: Instant?,
    val remoteEtag: String? = null,
)

enum class RemoteApplyStatus {
    APPLIED,
    MERGED_COMMON_BASE,
    IGNORED_BY_TOMBSTONE,
    CONFLICT_COPY_CREATED,
    IGNORED_OLDER_REVISION,
    CONFLICT_REQUIRES_LOCAL_RESOLUTION,
}

data class RemoteApplyResult(
    val status: RemoteApplyStatus,
    val noteId: String? = null,
)

interface LocalIdGenerator {
    fun newId(prefix: String): String
}

class TimeBasedLocalIdGenerator(
    private val clock: () -> Instant = { Clock.System.now() },
) : LocalIdGenerator {
    private var counter = 0L

    override fun newId(prefix: String): String {
        counter += 1
        return "$prefix-${clock().toEpochMilliseconds()}-$counter"
    }
}

fun contentHashForNotebook(title: String): String = stableContentHash("notebook", title)

fun contentHashForNote(
    title: String,
    markdownBody: String,
    createdAt: Instant? = null,
    timeZoneId: String? = null,
): String = stableContentHash(
    "note",
    title,
    markdownBody,
    createdAt?.toString().orEmpty(),
    timeZoneId.normalizedTimeZoneId().orEmpty(),
)

internal fun String?.normalizedTimeZoneId(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

internal fun excerptFor(markdownBody: String): String =
    markdownBody
        .lineSequence()
        .joinToString(" ")
        .trim()
        .take(240)

internal fun searchTextFor(
    title: String,
    markdownBody: String,
): String = "$title\n$markdownBody".lowercase()

private fun stableContentHash(vararg parts: String): String {
    var hash = -0x340d631b7bdddcdbL
    parts.forEach { part ->
        part.encodeToByteArray().forEach { byte ->
            hash = hash xor byte.toLong()
            hash *= 0x100000001b3L
        }
        hash = hash xor 0xff
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
