@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.local

import kotlin.time.Instant

internal fun mapNotebook(
    id: String,
    title: String,
    sort_order: Long,
    created_at: Long,
    updated_at: Long,
    deleted_at: Long?,
    revision: Long,
    sync_state: String,
    content_hash: String,
): Notebook =
    Notebook(
        id = id,
        title = title,
        sortOrder = sort_order,
        createdAt = created_at.toInstant(),
        updatedAt = updated_at.toInstant(),
        deletedAt = deleted_at?.toInstant(),
        revision = revision,
        syncState = SyncState.fromStorageValue(sync_state),
        contentHash = content_hash,
    )

internal fun mapNote(
    id: String,
    notebook_id: String,
    title: String,
    markdown_body: String,
    excerpt: String,
    search_text: String,
    time_zone_id: String?,
    created_at: Long,
    updated_at: Long,
    deleted_at: Long?,
    content_hash: String,
    revision: Long,
    sync_state: String,
    current_version_id: String?,
): Note =
    Note(
        id = id,
        notebookId = notebook_id,
        title = title,
        markdownBody = markdown_body,
        excerpt = excerpt,
        searchText = search_text,
        timeZoneId = time_zone_id,
        createdAt = created_at.toInstant(),
        updatedAt = updated_at.toInstant(),
        deletedAt = deleted_at?.toInstant(),
        contentHash = content_hash,
        revision = revision,
        syncState = SyncState.fromStorageValue(sync_state),
        currentVersionId = current_version_id,
    )

internal fun mapNoteVersion(
    version_id: String,
    note_id: String,
    parent_version_id: String?,
    base_version_id: String?,
    revision: Long,
    title: String,
    markdown_body: String,
    content_hash: String,
    device_id: String,
    merge_metadata_json: String?,
    created_at: Long,
): NoteVersion =
    NoteVersion(
        versionId = version_id,
        noteId = note_id,
        parentVersionId = parent_version_id,
        baseVersionId = base_version_id,
        revision = revision,
        title = title,
        markdownBody = markdown_body,
        contentHash = content_hash,
        deviceId = device_id,
        mergeMetadataJson = merge_metadata_json,
        createdAt = created_at.toInstant(),
    )

internal fun mapTombstone(
    entity_id: String,
    entity_type: String,
    deleted_at: Long,
    deleted_by_device_id: String,
    last_known_revision: Long,
    purge_after: Long?,
    dirty: Long,
): Tombstone =
    Tombstone(
        entityId = entity_id,
        entityType = EntityType.fromStorageValue(entity_type),
        deletedAt = deleted_at.toInstant(),
        deletedByDeviceId = deleted_by_device_id,
        lastKnownRevision = last_known_revision,
        purgeAfter = purge_after?.toInstant(),
        dirty = dirty.toBooleanFlag(),
    )

internal fun mapLocation(
    note_id: String,
    latitude: Double?,
    longitude: Double?,
    accuracy_meters: Double?,
    altitude_meters: Double?,
    place_text: String?,
    captured_at: Long,
    updated_at: Long,
    revision: Long,
    sync_state: String,
): NoteLocation =
    NoteLocation(
        noteId = note_id,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy_meters,
        altitudeMeters = altitude_meters,
        placeText = place_text,
        capturedAt = captured_at.toInstant(),
        updatedAt = updated_at.toInstant(),
        revision = revision,
        syncState = SyncState.fromStorageValue(sync_state),
    )

internal fun mapSyncMetadata(
    entity_id: String,
    entity_type: String,
    local_revision: Long,
    remote_revision: Long?,
    remote_etag: String?,
    vector_clock_json: String,
    dirty: Long,
    conflict_state: String,
    last_synced_at: Long?,
    last_error: String?,
    updated_at: Long,
): SyncMetadata =
    SyncMetadata(
        entityId = entity_id,
        entityType = EntityType.fromStorageValue(entity_type),
        localRevision = local_revision,
        remoteRevision = remote_revision,
        remoteEtag = remote_etag,
        vectorClockJson = vector_clock_json,
        dirty = dirty.toBooleanFlag(),
        conflictState = ConflictState.fromStorageValue(conflict_state),
        lastSyncedAt = last_synced_at?.toInstant(),
        lastError = last_error,
        updatedAt = updated_at.toInstant(),
    )

internal fun Long.toInstant(): Instant = Instant.fromEpochMilliseconds(this)

internal fun Boolean.toLongFlag(): Long = if (this) 1L else 0L

internal fun Long.toBooleanFlag(): Boolean = this != 0L

internal fun jsonEscape(value: String): String =
    buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
