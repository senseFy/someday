@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.local

import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.notes.ConflictDetails
import saien.someday.domain.notes.ConflictHistory
import saien.someday.domain.notes.ConflictResolutionAction
import saien.someday.domain.notes.NoteMergeSnapshot
import saien.someday.domain.notes.NoteThreeWayMerger
import saien.someday.domain.notes.NoteVersionSummary
import saien.someday.domain.notes.MemoryDayCount
import saien.someday.domain.notes.MemoryMonth
import saien.someday.domain.notes.noteCalendarDate
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

class SqlDelightLocalDataRepository(
    val database: SomedayDatabase,
    private val deviceId: String,
    private val clock: () -> Instant = { Clock.System.now() },
    private val idGenerator: LocalIdGenerator = TimeBasedLocalIdGenerator(clock),
) {
    private val queries = database.somedayQueries
    val localDeviceId: String = deviceId

    init {
        pruneStaleConflictMetadata()
    }

    fun createNotebook(
        title: String,
        sortOrder: Long? = null,
        id: String = idGenerator.newId("notebook"),
    ): Notebook {
        require(title.isNotBlank()) { "Notebook title must not be blank." }

        var created: Notebook? = null
        database.transaction {
            val now = clock()
            val revision = 1L
            val resolvedSortOrder = sortOrder ?: ((listActiveNotebooks().maxOfOrNull { it.sortOrder } ?: 0L) + 1L)
            val hash = contentHashForNotebook(title)

            queries.insertNotebook(
                id,
                title,
                resolvedSortOrder,
                now.toEpochMilliseconds(),
                now.toEpochMilliseconds(),
                revision,
                SyncState.DIRTY.storageValue,
                hash,
            )
            markDirty(id, EntityType.NOTEBOOK, revision, now)
            created = getNotebook(id, includeDeleted = true)
        }
        return checkNotNull(created)
    }

    fun listActiveNotebooks(): List<Notebook> =
        queries.selectActiveNotebooks(::mapNotebook).executeAsList()

    fun renameNotebook(
        notebookId: String,
        title: String,
    ): Notebook {
        require(title.isNotBlank()) { "Notebook title must not be blank." }
        val existing = requireNotNull(getNotebook(notebookId)) {
            "Cannot rename missing or deleted notebook: $notebookId"
        }

        var renamed: Notebook? = null
        database.transaction {
            val now = clock()
            val revision = existing.revision + 1L
            val hash = contentHashForNotebook(title)

            queries.updateNotebookTitle(
                title,
                now.toEpochMilliseconds(),
                revision,
                SyncState.DIRTY.storageValue,
                hash,
                notebookId,
            )
            markDirty(notebookId, EntityType.NOTEBOOK, revision, now)
            renamed = getNotebook(notebookId)
        }
        return checkNotNull(renamed)
    }

    fun createNote(
        notebookId: String,
        title: String,
        markdownBody: String,
        createdAt: Instant? = null,
        timeZoneId: String? = null,
        location: LocationInput? = null,
        id: String = idGenerator.newId("note"),
    ): Note =
        createNoteInternal(
            id = id,
            notebookId = notebookId,
            title = title,
            markdownBody = markdownBody,
            createdAt = createdAt,
            timeZoneId = timeZoneId,
            location = location,
            syncState = SyncState.DIRTY,
            conflictState = ConflictState.NONE,
            versionDeviceId = deviceId,
        )

    fun importNoteSnapshot(
        id: String,
        notebookId: String,
        title: String,
        markdownBody: String,
        timeZoneId: String? = null,
        createdAt: Instant,
        updatedAt: Instant,
        revision: Long = 1L,
        location: LocationInput? = null,
        currentVersionId: String? = null,
        parentVersionId: String? = null,
        baseVersionId: String? = null,
        versionDeviceId: String = "import",
        mergeMetadataJson: String? = null,
    ): Note {
        require(title.isNotBlank()) { "Note title must not be blank." }
        require(revision >= 1L) { "Imported note revision must be at least 1." }
        requireNotNull(getNotebook(notebookId)) {
            "Cannot import note in missing or deleted notebook: $notebookId"
        }
        require(getNote(id, includeDeleted = true) == null) {
            "Cannot import note over an existing note: $id"
        }

        var imported: Note? = null
        database.transaction {
            var versionId = currentVersionId?.takeIf { getNoteVersion(it) == null } ?: idGenerator.newId("version")
            while (getNoteVersion(versionId) != null) {
                versionId = idGenerator.newId("version")
            }
            val normalizedTimeZoneId = timeZoneId.normalizedTimeZoneId()
            val hash = contentHashForNote(title, markdownBody, createdAt, normalizedTimeZoneId)

            queries.insertNote(
                id,
                notebookId,
                title,
                markdownBody,
                excerptFor(markdownBody),
                searchTextFor(title, markdownBody),
                normalizedTimeZoneId,
                createdAt.toEpochMilliseconds(),
                updatedAt.toEpochMilliseconds(),
                hash,
                revision,
                SyncState.DIRTY.storageValue,
                versionId,
            )
            queries.insertNoteVersion(
                versionId,
                id,
                parentVersionId,
                baseVersionId,
                revision,
                title,
                markdownBody,
                hash,
                versionDeviceId,
                mergeMetadataJson,
                updatedAt.toEpochMilliseconds(),
            )
            if (location != null) {
                upsertLocation(id, location, revision, SyncState.DIRTY, updatedAt)
                markDirty(id, EntityType.LOCATION, revision, updatedAt)
            }
            markDirty(id, EntityType.NOTE, revision, updatedAt)
            markDirty(versionId, EntityType.NOTE_VERSION, revision, updatedAt)
            imported = getNote(id)
        }
        return checkNotNull(imported)
    }

    fun updateNote(
        noteId: String,
        notebookId: String? = null,
        title: String? = null,
        markdownBody: String? = null,
        createdAt: Instant? = null,
        timeZoneId: String? = null,
        clearTimeZone: Boolean = false,
        location: LocationInput? = null,
        clearLocation: Boolean = false,
    ): Note {
        val existing = requireNotNull(getNote(noteId)) {
            "Cannot edit missing or deleted note: $noteId"
        }
        if (notebookId != null) {
            requireNotNull(getNotebook(notebookId)) {
                "Cannot move note to missing or deleted notebook: $notebookId"
            }
        }

        var updated: Note? = null
        database.transaction {
            val now = clock()
            val resolvedNotebookId = notebookId ?: existing.notebookId
            val resolvedTitle = title ?: existing.title
            val resolvedBody = markdownBody ?: existing.markdownBody
            val resolvedCreatedAt = createdAt ?: existing.createdAt
            val resolvedTimeZoneId = if (clearTimeZone) null else timeZoneId.normalizedTimeZoneId() ?: existing.timeZoneId
            val existingLocation = getLocation(noteId)
            val snapshotChanged = resolvedNotebookId != existing.notebookId ||
                resolvedTitle != existing.title ||
                resolvedBody != existing.markdownBody ||
                resolvedCreatedAt != existing.createdAt ||
                resolvedTimeZoneId != existing.timeZoneId
            val locationChanged = when {
                clearLocation -> existingLocation != null
                location != null -> !existingLocation.matches(location)
                else -> false
            }

            if (!snapshotChanged && !locationChanged) {
                updated = existing
                return@transaction
            }

            val revision = existing.revision + 1L
            val versionId = if (snapshotChanged) {
                idGenerator.newId("version")
            } else {
                existing.currentVersionId
            }
            val hash = contentHashForNote(resolvedTitle, resolvedBody, resolvedCreatedAt, resolvedTimeZoneId)

            queries.updateNoteSnapshot(
                resolvedNotebookId,
                resolvedTitle,
                resolvedBody,
                excerptFor(resolvedBody),
                searchTextFor(resolvedTitle, resolvedBody),
                resolvedTimeZoneId,
                resolvedCreatedAt.toEpochMilliseconds(),
                now.toEpochMilliseconds(),
                hash,
                revision,
                SyncState.DIRTY.storageValue,
                versionId,
                noteId,
            )
            if (snapshotChanged) {
                queries.insertNoteVersion(
                    checkNotNull(versionId),
                    noteId,
                    existing.currentVersionId,
                    existing.currentVersionId,
                    revision,
                    resolvedTitle,
                    resolvedBody,
                    hash,
                    deviceId,
                    null,
                    now.toEpochMilliseconds(),
                )
                markDirty(checkNotNull(versionId), EntityType.NOTE_VERSION, revision, now)
            }
            markDirty(noteId, EntityType.NOTE, revision, now)
            if (clearLocation) {
                queries.deleteLocationForNote(noteId)
                markDirty(noteId, EntityType.LOCATION, revision, now)
            } else if (location != null) {
                upsertLocation(noteId, location, revision, SyncState.DIRTY, now)
                markDirty(noteId, EntityType.LOCATION, revision, now)
            }
            updated = getNote(noteId)
        }
        return checkNotNull(updated)
    }

    fun deleteNote(noteId: String): Note {
        val existing = requireNotNull(getNote(noteId)) {
            "Cannot delete missing or already deleted note: $noteId"
        }

        var deleted: Note? = null
        database.transaction {
            val now = clock()
            val revision = existing.revision + 1L

            queries.markNoteDeleted(
                now.toEpochMilliseconds(),
                now.toEpochMilliseconds(),
                revision,
                SyncState.DELETED.storageValue,
                noteId,
            )
            upsertTombstone(
                entityId = noteId,
                entityType = EntityType.NOTE,
                deletedAt = now,
                lastKnownRevision = revision,
            )
            markDirty(noteId, EntityType.NOTE, revision, now)
            markDirty(noteId, EntityType.TOMBSTONE, revision, now)
            clearConflictStateForNoteVersions(noteId, now)
            deleted = getNote(noteId, includeDeleted = true)
        }
        return checkNotNull(deleted)
    }

    fun deleteNotebook(notebookId: String): Notebook {
        val existing = requireNotNull(getNotebook(notebookId)) {
            "Cannot delete missing or already deleted notebook: $notebookId"
        }
        val activeNoteCount = queries.countActiveNotesForNotebook(notebookId).executeAsOne()
        require(activeNoteCount == 0L) {
            "Cannot delete a notebook that still contains active notes."
        }

        var deleted: Notebook? = null
        database.transaction {
            val now = clock()
            val revision = existing.revision + 1L
            val hash = contentHashForNotebook(existing.title)

            queries.updateNotebookForDelete(
                now.toEpochMilliseconds(),
                now.toEpochMilliseconds(),
                revision,
                SyncState.DELETED.storageValue,
                hash,
                notebookId,
            )
            upsertTombstone(
                entityId = notebookId,
                entityType = EntityType.NOTEBOOK,
                deletedAt = now,
                lastKnownRevision = revision,
            )
            markDirty(notebookId, EntityType.NOTEBOOK, revision, now)
            markDirty(notebookId, EntityType.TOMBSTONE, revision, now)
            deleted = getNotebook(notebookId, includeDeleted = true)
        }
        return checkNotNull(deleted)
    }

    fun listActiveNotes(notebookId: String): List<Note> =
        queries.selectActiveNotesByNotebook(notebookId, ::mapNote).executeAsList()

    fun listMemoryDayCounts(month: MemoryMonth): List<MemoryDayCount> =
        listAllActiveNotes()
            .map { noteCalendarDate(it.createdAt, it.timeZoneId) }
            .filter(month::contains)
            .groupingBy { it }
            .eachCount()
            .map { (date, noteCount) -> MemoryDayCount(date = date, noteCount = noteCount) }
            .sortedBy { it.date.toString() }

    fun listActiveNotesForDate(date: LocalDate): List<Note> =
        listAllActiveNotes()
            .filter { noteCalendarDate(it.createdAt, it.timeZoneId) == date }
            .sortedWith(compareByDescending<Note> { it.updatedAt }.thenBy { it.title })

    fun listPriorYearSameDayNotes(date: LocalDate): List<Note> =
        listAllActiveNotes()
            .filter {
                val noteDate = noteCalendarDate(it.createdAt, it.timeZoneId)
                noteDate < date && sameMonthDayKey(noteDate) == sameMonthDayKey(date)
            }
            .sortedWith(
                compareByDescending<Note> { noteCalendarDate(it.createdAt, it.timeZoneId).toString() }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.title },
            )

    fun listAllActiveNotes(): List<Note> =
        queries.selectActiveNotes(::mapNote).executeAsList()

    fun searchActiveNotes(query: String): List<Note> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            return emptyList()
        }
        return queries.searchActiveNotes(normalizedQuery, ::mapNote).executeAsList()
    }

    fun getNote(
        noteId: String,
        includeDeleted: Boolean = false,
    ): Note? =
        if (includeDeleted) {
            queries.selectNoteByIdIncludingDeleted(noteId, ::mapNote).executeAsOneOrNull()
        } else {
            queries.selectActiveNoteById(noteId, ::mapNote).executeAsOneOrNull()
        }

    fun listNoteVersions(noteId: String): List<NoteVersion> =
        queries.selectNoteVersions(noteId, ::mapNoteVersion).executeAsList()

    fun getNoteVersion(
        noteId: String,
        versionId: String,
    ): NoteVersion? =
        queries.selectNoteVersion(noteId, versionId, ::mapNoteVersion).executeAsOneOrNull()

    fun getNoteVersion(versionId: String): NoteVersion? =
        queries.selectNoteVersionById(versionId, ::mapNoteVersion).executeAsOneOrNull()

    fun restoreNoteVersion(
        noteId: String,
        versionId: String,
    ): Note {
        val existing = requireNotNull(getNote(noteId)) {
            "Cannot restore a version for missing or deleted note: $noteId"
        }
        val version = requireNotNull(getNoteVersion(noteId, versionId)) {
            "Cannot restore missing version $versionId for note: $noteId"
        }

        var restored: Note? = null
        database.transaction {
            val now = clock()
            val revision = existing.revision + 1L
            val restoredVersionId = idGenerator.newId("version")
            val hash = contentHashForNote(version.title, version.markdownBody, existing.createdAt, existing.timeZoneId)

            queries.updateNoteSnapshot(
                existing.notebookId,
                version.title,
                version.markdownBody,
                excerptFor(version.markdownBody),
                searchTextFor(version.title, version.markdownBody),
                existing.timeZoneId,
                existing.createdAt.toEpochMilliseconds(),
                now.toEpochMilliseconds(),
                hash,
                revision,
                SyncState.DIRTY.storageValue,
                restoredVersionId,
                noteId,
            )
            queries.insertNoteVersion(
                restoredVersionId,
                noteId,
                existing.currentVersionId,
                version.versionId,
                revision,
                version.title,
                version.markdownBody,
                hash,
                deviceId,
                """{"source":"restore","restoredVersionId":"${jsonEscape(version.versionId)}"}""",
                now.toEpochMilliseconds(),
            )
            markDirty(noteId, EntityType.NOTE, revision, now)
            markDirty(restoredVersionId, EntityType.NOTE_VERSION, revision, now)
            restored = getNote(noteId)
        }
        return checkNotNull(restored)
    }

    fun getTombstone(
        entityId: String,
        entityType: EntityType,
    ): Tombstone? =
        queries.selectTombstone(
            entityId,
            entityType.storageValue,
            ::mapTombstone,
        ).executeAsOneOrNull()

    fun getLocation(noteId: String): NoteLocation? =
        queries.selectLocationByNote(noteId, ::mapLocation).executeAsOneOrNull()

    fun getSyncMetadata(
        entityId: String,
        entityType: EntityType,
    ): SyncMetadata? =
        queries.selectSyncMetadata(
            entityId,
            entityType.storageValue,
            ::mapSyncMetadata,
        ).executeAsOneOrNull()

    fun listDirtySyncMetadata(): List<SyncMetadata> =
        queries.selectDirtySyncMetadata(::mapSyncMetadata).executeAsList()

    fun listConflictingSyncMetadata(): List<SyncMetadata> =
        queries.selectConflictingSyncMetadata(::mapSyncMetadata).executeAsList()

    fun pruneStaleConflictMetadata(): Int {
        var pruned = 0
        database.transaction {
            val now = clock()
            queries.selectConflictingSyncMetadata(::mapSyncMetadata)
                .executeAsList()
                .forEach { metadata ->
                    val stale = when (metadata.entityType) {
                        EntityType.NOTE -> {
                            val note = getNote(metadata.entityId, includeDeleted = true)
                            note == null || note.deletedAt != null
                        }

                        EntityType.NOTE_VERSION -> {
                            val version = getNoteVersion(metadata.entityId)
                            val note = version?.let { getNote(it.noteId, includeDeleted = true) }
                            version == null || note == null || note.deletedAt != null
                        }

                        EntityType.LOCATION -> getNote(metadata.entityId, includeDeleted = true)?.deletedAt != null

                        EntityType.NOTEBOOK,
                        EntityType.TOMBSTONE,
                        EntityType.SETTING,
                        EntityType.DEVICE,
                        -> false
                    }
                    if (stale) {
                        clearConflictState(metadata, now)
                        pruned += 1
                    }
                }
        }
        return pruned
    }

    fun listDirtyTombstones(): List<Tombstone> =
        queries.selectDirtyTombstones(::mapTombstone).executeAsList()

    fun listAllTombstones(): List<Tombstone> =
        queries.selectAllTombstones(::mapTombstone).executeAsList()

    fun markAllLocalContentDirtyForSyncReset() {
        database.transaction {
            val now = clock()
            listActiveNotebooks().forEach { notebook ->
                queries.updateNotebookSyncState(SyncState.DIRTY.storageValue, notebook.id)
                markDirty(notebook.id, EntityType.NOTEBOOK, notebook.revision, now)
                listActiveNotes(notebook.id).forEach { note ->
                    queries.updateNoteSyncState(SyncState.DIRTY.storageValue, note.id)
                    markDirty(note.id, EntityType.NOTE, note.revision, now)
                    listNoteVersions(note.id).forEach { version ->
                        markDirty(version.versionId, EntityType.NOTE_VERSION, version.revision, now)
                    }
                    getLocation(note.id)?.let { location ->
                        queries.updateLocationSyncState(SyncState.DIRTY.storageValue, note.id)
                        markDirty(note.id, EntityType.LOCATION, location.revision, now)
                    }
                }
            }
            listAllTombstones().forEach { tombstone ->
                queries.updateTombstoneDirty(true.toLongFlag(), tombstone.entityId, tombstone.entityType.storageValue)
                markDirty(tombstone.entityId, EntityType.TOMBSTONE, tombstone.lastKnownRevision, now)
            }
        }
    }

    fun markEntitySynced(
        entityId: String,
        entityType: EntityType,
        remoteRevision: Long?,
        remoteEtag: String?,
        syncedAt: Instant = clock(),
    ) {
        val current = getSyncMetadata(entityId, entityType)
        val localRevision = current?.localRevision ?: remoteRevision ?: 0L
        upsertSyncMetadata(
            entityId = entityId,
            entityType = entityType,
            localRevision = localRevision,
            remoteRevision = remoteRevision ?: current?.remoteRevision,
            remoteEtag = remoteEtag ?: current?.remoteEtag,
            dirty = false,
            conflictState = current?.conflictState ?: ConflictState.NONE,
            updatedAt = syncedAt,
            lastSyncedAt = syncedAt,
            lastError = null,
        )
        when (entityType) {
            EntityType.NOTEBOOK -> queries.updateNotebookSyncState(
                if (getNotebook(entityId, includeDeleted = true)?.deletedAt == null) {
                    SyncState.CLEAN.storageValue
                } else {
                    SyncState.DELETED.storageValue
                },
                entityId,
            )

            EntityType.NOTE -> queries.updateNoteSyncState(
                if (getNote(entityId, includeDeleted = true)?.deletedAt == null) {
                    SyncState.CLEAN.storageValue
                } else {
                    SyncState.DELETED.storageValue
                },
                entityId,
            )

            EntityType.LOCATION -> queries.updateLocationSyncState(SyncState.CLEAN.storageValue, entityId)
            EntityType.SETTING -> queries.updateSettingDirty(false.toLongFlag(), entityId)
            EntityType.TOMBSTONE -> Unit
            EntityType.NOTE_VERSION,
            EntityType.DEVICE,
            -> Unit
        }
    }

    fun markTombstoneSynced(
        tombstone: Tombstone,
        remoteRevision: Long?,
        remoteEtag: String?,
        syncedAt: Instant = clock(),
    ) {
        markEntitySynced(
            entityId = tombstone.entityId,
            entityType = EntityType.TOMBSTONE,
            remoteRevision = remoteRevision ?: tombstone.lastKnownRevision,
            remoteEtag = remoteEtag,
            syncedAt = syncedAt,
        )
        queries.updateTombstoneDirty(false.toLongFlag(), tombstone.entityId, tombstone.entityType.storageValue)
        markEntitySynced(
            entityId = tombstone.entityId,
            entityType = tombstone.entityType,
            remoteRevision = remoteRevision ?: tombstone.lastKnownRevision,
            remoteEtag = remoteEtag,
            syncedAt = syncedAt,
        )
    }

    fun applyRemoteNotebookSnapshot(snapshot: RemoteNotebookSnapshot): RemoteApplyResult {
        val existing = getNotebook(snapshot.id, includeDeleted = true)
        if (existing != null &&
            existing.hasUnconfirmedLocalChanges() &&
            snapshot.contentHash != existing.contentHash
        ) {
            return RemoteApplyResult(RemoteApplyStatus.CONFLICT_REQUIRES_LOCAL_RESOLUTION, snapshot.id)
        }
        if (existing != null && snapshot.revision <= existing.revision) {
            return RemoteApplyResult(RemoteApplyStatus.IGNORED_OLDER_REVISION, snapshot.id)
        }

        database.transaction {
            val createdAt = (existing?.createdAt ?: snapshot.createdAt).toEpochMilliseconds()
            val syncState = if (snapshot.deletedAt == null) SyncState.CLEAN.storageValue else SyncState.DELETED.storageValue
            if (existing == null) {
                queries.insertRemoteNotebookSnapshot(
                    snapshot.id,
                    snapshot.title,
                    snapshot.sortOrder,
                    createdAt,
                    snapshot.updatedAt.toEpochMilliseconds(),
                    snapshot.deletedAt?.toEpochMilliseconds(),
                    snapshot.revision,
                    syncState,
                    snapshot.contentHash,
                )
            } else {
                queries.updateRemoteNotebookSnapshot(
                    snapshot.title,
                    snapshot.sortOrder,
                    createdAt,
                    snapshot.updatedAt.toEpochMilliseconds(),
                    snapshot.deletedAt?.toEpochMilliseconds(),
                    snapshot.revision,
                    syncState,
                    snapshot.contentHash,
                    snapshot.id,
                )
            }
            upsertSyncMetadata(
                entityId = snapshot.id,
                entityType = EntityType.NOTEBOOK,
                localRevision = snapshot.revision,
                remoteRevision = snapshot.revision,
                remoteEtag = snapshot.remoteEtag,
                dirty = false,
                conflictState = ConflictState.NONE,
                updatedAt = snapshot.updatedAt,
                lastSyncedAt = clock(),
            )
        }
        return RemoteApplyResult(RemoteApplyStatus.APPLIED, snapshot.id)
    }

    /**
     * Projection-only notebook apply for sync v2. Winner selection happens in
     * the immutable auxiliary-version store before this method is called.
     */
    fun projectNotebookSnapshotV2(
        snapshot: RemoteNotebookSnapshot,
        keepDirty: Boolean,
    ) {
        val existing = getNotebook(snapshot.id, includeDeleted = true)
        database.transaction {
            val createdAt = (existing?.createdAt ?: snapshot.createdAt).toEpochMilliseconds()
            val state = when {
                snapshot.deletedAt != null -> SyncState.DELETED
                keepDirty -> SyncState.DIRTY
                else -> SyncState.CLEAN
            }
            if (existing == null) {
                queries.insertRemoteNotebookSnapshot(
                    snapshot.id,
                    snapshot.title,
                    snapshot.sortOrder,
                    createdAt,
                    snapshot.updatedAt.toEpochMilliseconds(),
                    snapshot.deletedAt?.toEpochMilliseconds(),
                    snapshot.revision,
                    state.storageValue,
                    snapshot.contentHash,
                )
            } else {
                queries.updateRemoteNotebookSnapshot(
                    snapshot.title,
                    snapshot.sortOrder,
                    createdAt,
                    snapshot.updatedAt.toEpochMilliseconds(),
                    snapshot.deletedAt?.toEpochMilliseconds(),
                    snapshot.revision,
                    state.storageValue,
                    snapshot.contentHash,
                    snapshot.id,
                )
            }
            if (snapshot.deletedAt == null) {
                queries.deleteTombstone(snapshot.id, EntityType.NOTEBOOK.storageValue)
            } else {
                queries.upsertTombstone(
                    snapshot.id,
                    EntityType.NOTEBOOK.storageValue,
                    snapshot.deletedAt.toEpochMilliseconds(),
                    snapshot.deviceId,
                    snapshot.revision,
                    null,
                    keepDirty.toLongFlag(),
                )
            }
            upsertSyncMetadata(
                entityId = snapshot.id,
                entityType = EntityType.NOTEBOOK,
                localRevision = snapshot.revision,
                remoteRevision = snapshot.revision,
                remoteEtag = snapshot.remoteEtag,
                dirty = keepDirty,
                conflictState = ConflictState.NONE,
                updatedAt = snapshot.updatedAt,
                lastSyncedAt = if (keepDirty) null else clock(),
            )
            if (snapshot.deletedAt != null) {
                upsertSyncMetadata(
                    entityId = snapshot.id,
                    entityType = EntityType.TOMBSTONE,
                    localRevision = snapshot.revision,
                    remoteRevision = snapshot.revision,
                    remoteEtag = snapshot.remoteEtag,
                    dirty = keepDirty,
                    conflictState = ConflictState.NONE,
                    updatedAt = snapshot.updatedAt,
                    lastSyncedAt = if (keepDirty) null else clock(),
                )
            }
        }
    }

    fun applyRemoteNoteVersionSnapshot(snapshot: RemoteNoteVersionSnapshot): RemoteApplyResult {
        if (getNoteVersion(snapshot.versionId) != null) {
            return RemoteApplyResult(RemoteApplyStatus.IGNORED_OLDER_REVISION, snapshot.versionId)
        }
        if (getNote(snapshot.noteId, includeDeleted = true) == null) {
            val tombstone = getTombstone(snapshot.noteId, EntityType.NOTE)
            if (tombstone != null && snapshot.revision <= tombstone.lastKnownRevision) {
                return RemoteApplyResult(RemoteApplyStatus.IGNORED_BY_TOMBSTONE, snapshot.versionId)
            }
            return RemoteApplyResult(RemoteApplyStatus.CONFLICT_REQUIRES_LOCAL_RESOLUTION, snapshot.versionId)
        }

        database.transaction {
            queries.insertNoteVersion(
                snapshot.versionId,
                snapshot.noteId,
                snapshot.parentVersionId,
                snapshot.baseVersionId,
                snapshot.revision,
                snapshot.title,
                snapshot.markdownBody,
                snapshot.contentHash,
                snapshot.deviceId,
                snapshot.mergeMetadataJson,
                snapshot.createdAt.toEpochMilliseconds(),
            )
            upsertSyncMetadata(
                entityId = snapshot.versionId,
                entityType = EntityType.NOTE_VERSION,
                localRevision = snapshot.revision,
                remoteRevision = snapshot.revision,
                remoteEtag = snapshot.remoteEtag,
                dirty = false,
                conflictState = ConflictState.NONE,
                updatedAt = snapshot.createdAt,
                lastSyncedAt = clock(),
            )
        }
        return RemoteApplyResult(RemoteApplyStatus.APPLIED, snapshot.versionId)
    }

    fun applyRemoteTombstoneSnapshot(snapshot: RemoteTombstoneSnapshot): RemoteApplyResult {
        val existingTombstone = getTombstone(snapshot.entityId, snapshot.entityType)
        if (existingTombstone != null && snapshot.lastKnownRevision <= existingTombstone.lastKnownRevision) {
            return RemoteApplyResult(RemoteApplyStatus.IGNORED_BY_TOMBSTONE, snapshot.entityId)
        }

        val entityConflict = when (snapshot.entityType) {
            EntityType.NOTE -> getNote(snapshot.entityId)?.hasUnconfirmedLocalChanges() == true
            EntityType.NOTEBOOK -> {
                val notebook = getNotebook(snapshot.entityId)
                notebook?.hasUnconfirmedLocalChanges() == true ||
                    (notebook != null && listActiveNotes(notebook.id).isNotEmpty())
            }

            EntityType.NOTE_VERSION,
            EntityType.TOMBSTONE,
            EntityType.LOCATION,
            EntityType.SETTING,
            EntityType.DEVICE,
            -> false
        }
        if (entityConflict) {
            return RemoteApplyResult(RemoteApplyStatus.CONFLICT_REQUIRES_LOCAL_RESOLUTION, snapshot.entityId)
        }

        database.transaction {
            when (snapshot.entityType) {
                EntityType.NOTE -> {
                    if (getNote(snapshot.entityId, includeDeleted = true) != null) {
                        queries.markNoteDeleted(
                            snapshot.deletedAt.toEpochMilliseconds(),
                            snapshot.deletedAt.toEpochMilliseconds(),
                            snapshot.lastKnownRevision,
                            SyncState.DELETED.storageValue,
                            snapshot.entityId,
                        )
                    }
                }

                EntityType.NOTEBOOK -> {
                    val notebook = getNotebook(snapshot.entityId, includeDeleted = true)
                    if (notebook != null) {
                        queries.updateNotebookForDelete(
                            snapshot.deletedAt.toEpochMilliseconds(),
                            snapshot.deletedAt.toEpochMilliseconds(),
                            snapshot.lastKnownRevision,
                            SyncState.DELETED.storageValue,
                            contentHashForNotebook(notebook.title),
                            snapshot.entityId,
                        )
                    }
                }

                EntityType.NOTE_VERSION,
                EntityType.TOMBSTONE,
                EntityType.LOCATION,
                EntityType.SETTING,
                EntityType.DEVICE,
                -> Unit
            }
            queries.upsertTombstone(
                snapshot.entityId,
                snapshot.entityType.storageValue,
                snapshot.deletedAt.toEpochMilliseconds(),
                snapshot.deletedByDeviceId,
                snapshot.lastKnownRevision,
                snapshot.purgeAfter?.toEpochMilliseconds(),
                false.toLongFlag(),
            )
            upsertSyncMetadata(
                entityId = snapshot.entityId,
                entityType = snapshot.entityType,
                localRevision = snapshot.lastKnownRevision,
                remoteRevision = snapshot.lastKnownRevision,
                remoteEtag = snapshot.remoteEtag,
                dirty = false,
                conflictState = ConflictState.NONE,
                updatedAt = snapshot.deletedAt,
                lastSyncedAt = clock(),
            )
            upsertSyncMetadata(
                entityId = snapshot.entityId,
                entityType = EntityType.TOMBSTONE,
                localRevision = snapshot.lastKnownRevision,
                remoteRevision = snapshot.lastKnownRevision,
                remoteEtag = snapshot.remoteEtag,
                dirty = false,
                conflictState = ConflictState.NONE,
                updatedAt = snapshot.deletedAt,
                lastSyncedAt = clock(),
            )
        }
        return RemoteApplyResult(RemoteApplyStatus.APPLIED, snapshot.entityId)
    }

    fun recordEntitySyncError(
        entityId: String,
        entityType: EntityType,
        error: String,
        occurredAt: Instant = clock(),
    ) {
        val current = getSyncMetadata(entityId, entityType)
        upsertSyncMetadata(
            entityId = entityId,
            entityType = entityType,
            localRevision = current?.localRevision ?: 0L,
            remoteRevision = current?.remoteRevision,
            remoteEtag = current?.remoteEtag,
            dirty = true,
            conflictState = current?.conflictState ?: ConflictState.NONE,
            updatedAt = occurredAt,
            lastSyncedAt = current?.lastSyncedAt,
            lastError = error.take(500),
        )
    }

    fun putSetting(
        key: String,
        value: String,
    ): SettingsEntry {
        require(key.isNotBlank()) { "Setting key must not be blank." }

        var entry: SettingsEntry? = null
        database.transaction {
            val now = clock()
            val revision = (getSyncMetadata(key, EntityType.SETTING)?.localRevision ?: 0L) + 1L

            queries.insertOrReplaceSetting(
                key,
                value,
                now.toEpochMilliseconds(),
                true.toLongFlag(),
            )
            markDirty(key, EntityType.SETTING, revision, now)
            entry = getSetting(key)
        }
        return checkNotNull(entry)
    }

    fun putLocalOnlySetting(
        key: String,
        value: String,
    ): SettingsEntry {
        require(key.isNotBlank()) { "Setting key must not be blank." }
        queries.insertOrReplaceSetting(
            key,
            value,
            clock().toEpochMilliseconds(),
            false.toLongFlag(),
        )
        return checkNotNull(getSetting(key))
    }

    fun deleteLocalOnlySetting(key: String) {
        require(key.isNotBlank()) { "Setting key must not be blank." }
        queries.deleteSetting(key)
    }

    fun getSetting(key: String): SettingsEntry? =
        queries.selectSetting(key) { id, value, updated_at, dirty ->
                SettingsEntry(
                    key = id,
                    value = value,
                    updatedAt = updated_at.toInstant(),
                    dirty = dirty.toBooleanFlag(),
                )
            }
            .executeAsOneOrNull()

    fun registerDevice(
        id: String = deviceId,
        name: String,
        platform: String,
        syncCursor: String? = null,
        publicKey: String? = null,
        workspaceKeyMetadata: String? = null,
    ): Device {
        require(id.isNotBlank()) { "Device id must not be blank." }
        require(name.isNotBlank()) { "Device name must not be blank." }
        require(platform.isNotBlank()) { "Device platform must not be blank." }

        var device: Device? = null
        database.transaction {
            val now = clock()
            val existing = getDevice(id)
            val revision = (getSyncMetadata(id, EntityType.DEVICE)?.localRevision ?: 0L) + 1L

            queries.insertOrReplaceDevice(
                id,
                name,
                platform,
                existing?.createdAt?.toEpochMilliseconds() ?: now.toEpochMilliseconds(),
                now.toEpochMilliseconds(),
                true.toLongFlag(),
                syncCursor ?: existing?.syncCursor,
                publicKey ?: existing?.publicKey,
                workspaceKeyMetadata ?: existing?.workspaceKeyMetadata,
            )
            markDirty(id, EntityType.DEVICE, revision, now)
            device = getDevice(id)
        }
        return checkNotNull(device)
    }

    fun persistWorkspaceMetadata(
        settingKey: String,
        metadataJson: String,
        deviceName: String,
        platform: String,
    ) {
        database.transaction {
            putSetting(settingKey, metadataJson)
            registerDevice(
                name = deviceName,
                platform = platform,
                workspaceKeyMetadata = metadataJson,
            )
        }
    }

    fun getDevice(id: String): Device? =
        queries.selectDevice(
            id,
        ) { deviceId,
                name,
                platform,
                created_at,
                last_seen_at,
                is_active,
                sync_cursor,
                public_key,
                workspace_key_metadata,
                ->
                Device(
                    id = deviceId,
                    name = name,
                    platform = platform,
                    createdAt = created_at.toInstant(),
                    lastSeenAt = last_seen_at.toInstant(),
                    isActive = is_active.toBooleanFlag(),
                    syncCursor = sync_cursor,
                    publicKey = public_key,
                    workspaceKeyMetadata = workspace_key_metadata,
                )
            }
            .executeAsOneOrNull()

    fun applyRemoteNoteSnapshot(snapshot: RemoteNoteSnapshot): RemoteApplyResult {
        val localTombstone = getTombstone(snapshot.id, EntityType.NOTE)
        if (localTombstone != null) {
            if (snapshot.revision <= localTombstone.lastKnownRevision) {
                return RemoteApplyResult(RemoteApplyStatus.IGNORED_BY_TOMBSTONE, snapshot.id)
            }

            val conflictCopy = findExistingConflictCopy(
                snapshot = snapshot,
                conflictState = ConflictState.DELETE_VS_EDIT,
                source = "delete-vs-edit",
            ) ?: createConflictCopyFromRemote(
                snapshot = snapshot,
                conflictState = ConflictState.DELETE_VS_EDIT,
                source = "delete-vs-edit",
            )
            return RemoteApplyResult(RemoteApplyStatus.CONFLICT_COPY_CREATED, conflictCopy.id)
        }

        val existing = getNote(snapshot.id)
        if (existing != null && snapshot.revision < existing.revision) {
            return RemoteApplyResult(RemoteApplyStatus.IGNORED_OLDER_REVISION, snapshot.id)
        }
        if (existing != null &&
            existing.hasUnconfirmedLocalChanges() &&
            snapshot.representsDifferentContentThan(existing)
        ) {
            val merged = applyCommonBaseMergeIfPossible(snapshot = snapshot, existing = existing)
            if (merged != null) {
                return merged
            }

            val conflictCopy = findExistingConflictCopy(
                snapshot = snapshot,
                conflictState = ConflictState.MANUAL_RESOLUTION_REQUIRED,
                source = "remote-vs-dirty-local",
            ) ?: createConflictCopyFromRemote(
                snapshot = snapshot,
                conflictState = ConflictState.MANUAL_RESOLUTION_REQUIRED,
                source = "remote-vs-dirty-local",
            )
            return RemoteApplyResult(RemoteApplyStatus.CONFLICT_COPY_CREATED, conflictCopy.id)
        }
        if (existing != null &&
            snapshot.revision == existing.revision &&
            snapshot.representsDifferentContentThan(existing)
        ) {
            val merged = applyCommonBaseMergeIfPossible(snapshot = snapshot, existing = existing)
            if (merged != null) {
                return merged
            }

            val conflictCopy = findExistingConflictCopy(
                snapshot = snapshot,
                conflictState = ConflictState.MANUAL_RESOLUTION_REQUIRED,
                source = "remote-vs-equal-revision-local",
            ) ?: createConflictCopyFromRemote(
                snapshot = snapshot,
                conflictState = ConflictState.MANUAL_RESOLUTION_REQUIRED,
                source = "remote-vs-equal-revision-local",
            )
            return RemoteApplyResult(RemoteApplyStatus.CONFLICT_COPY_CREATED, conflictCopy.id)
        }
        if (existing != null && snapshot.revision <= existing.revision) {
            return RemoteApplyResult(RemoteApplyStatus.IGNORED_OLDER_REVISION, snapshot.id)
        }
        if (getNotebook(snapshot.notebookId) == null) {
            val conflictCopy = findExistingConflictCopy(
                snapshot = snapshot,
                conflictState = ConflictState.MANUAL_RESOLUTION_REQUIRED,
                source = "remote-missing-notebook",
            ) ?: createConflictCopyFromRemote(
                snapshot = snapshot,
                conflictState = ConflictState.MANUAL_RESOLUTION_REQUIRED,
                source = "remote-missing-notebook",
            )
            return RemoteApplyResult(RemoteApplyStatus.CONFLICT_COPY_CREATED, conflictCopy.id)
        }

        applyRemoteAsCurrent(snapshot, existing)
        return RemoteApplyResult(RemoteApplyStatus.APPLIED, snapshot.id)
    }

    private fun applyCommonBaseMergeIfPossible(
        snapshot: RemoteNoteSnapshot,
        existing: Note,
    ): RemoteApplyResult? {
        val baseVersionId = snapshot.baseVersionId ?: snapshot.parentVersionId ?: return null
        val baseVersion = getNoteVersion(baseVersionId) ?: return null
        val localVersion = existing.currentVersionId?.let(::getNoteVersion) ?: return null
        if (localVersion.versionId == baseVersion.versionId) {
            return null
        }

        val merge = NoteThreeWayMerger.merge(
            base = baseVersion.toMergeSnapshot(
                createdAt = existing.createdAt,
                timeZoneId = existing.timeZoneId,
            ),
            local = localVersion.toMergeSnapshot(
                createdAt = existing.createdAt,
                timeZoneId = existing.timeZoneId,
            ),
            remote = snapshot.toMergeSnapshot(),
        )
        if (!merge.autoMerged) {
            return null
        }

        database.transaction {
            val now = clock()
            val remoteVersionId = snapshot.currentVersionId ?: idGenerator.newId("remote-version")
            insertRemoteVersionIfMissing(
                snapshot = snapshot,
                versionId = remoteVersionId,
            )

            val mergedRevision = maxOf(existing.revision, snapshot.revision) + 1L
            val mergedVersionId = idGenerator.newId("merge-version")
            val mergedHash = contentHashForNote(merge.title, merge.markdownBody, merge.createdAt, merge.timeZoneId)
            queries.updateNoteSnapshot(
                existing.notebookId,
                merge.title,
                merge.markdownBody,
                excerptFor(merge.markdownBody),
                searchTextFor(merge.title, merge.markdownBody),
                merge.timeZoneId,
                merge.createdAt.toEpochMilliseconds(),
                now.toEpochMilliseconds(),
                mergedHash,
                mergedRevision,
                SyncState.DIRTY.storageValue,
                mergedVersionId,
                existing.id,
            )
            queries.insertNoteVersion(
                mergedVersionId,
                existing.id,
                localVersion.versionId,
                baseVersion.versionId,
                mergedRevision,
                merge.title,
                merge.markdownBody,
                mergedHash,
                deviceId,
                """{"source":"common-base-auto-merge","baseVersionId":"${jsonEscape(baseVersion.versionId)}","localVersionId":"${jsonEscape(localVersion.versionId)}","remoteVersionId":"${jsonEscape(remoteVersionId)}","remoteDeviceId":"${jsonEscape(snapshot.deviceId)}"}""",
                now.toEpochMilliseconds(),
            )
            markDirty(existing.id, EntityType.NOTE, mergedRevision, now)
            markDirty(mergedVersionId, EntityType.NOTE_VERSION, mergedRevision, now)
            upsertSyncMetadata(
                entityId = remoteVersionId,
                entityType = EntityType.NOTE_VERSION,
                localRevision = snapshot.revision,
                remoteRevision = snapshot.revision,
                remoteEtag = snapshot.remoteEtag,
                dirty = false,
                conflictState = ConflictState.NONE,
                updatedAt = snapshot.updatedAt,
                lastSyncedAt = now,
            )
        }
        return RemoteApplyResult(RemoteApplyStatus.MERGED_COMMON_BASE, snapshot.id)
    }

    private fun insertRemoteVersionIfMissing(
        snapshot: RemoteNoteSnapshot,
        versionId: String,
    ) {
        if (getNoteVersion(versionId) != null) {
            return
        }
        queries.insertNoteVersion(
            versionId,
            snapshot.id,
            snapshot.parentVersionId,
            snapshot.baseVersionId,
            snapshot.revision,
            snapshot.title,
            snapshot.markdownBody,
            snapshot.contentHash,
            snapshot.deviceId,
            snapshot.mergeMetadataJson ?: """{"source":"remote","remoteDeviceId":"${jsonEscape(snapshot.deviceId)}"}""",
            snapshot.updatedAt.toEpochMilliseconds(),
        )
    }

    private fun NoteVersion.toMergeSnapshot(
        createdAt: Instant,
        timeZoneId: String?,
    ): NoteMergeSnapshot =
        NoteMergeSnapshot(
            versionId = versionId,
            title = title,
            markdownBody = markdownBody,
            createdAt = createdAt,
            timeZoneId = timeZoneId,
        )

    private fun RemoteNoteSnapshot.toMergeSnapshot(): NoteMergeSnapshot =
        NoteMergeSnapshot(
            versionId = currentVersionId,
            title = title,
            markdownBody = markdownBody,
            createdAt = createdAt ?: updatedAt,
            timeZoneId = timeZoneId,
        )

    private fun RemoteNoteSnapshot.representsDifferentContentThan(existing: Note): Boolean =
        currentVersionId != existing.currentVersionId || contentHash != existing.contentHash

    private fun findExistingConflictCopy(
        snapshot: RemoteNoteSnapshot,
        conflictState: ConflictState,
        source: String,
    ): Note? =
        listActiveNotebooks()
            .asSequence()
            .flatMap { notebook -> listActiveNotes(notebook.id).asSequence() }
            .firstOrNull { note ->
                val metadata = note.currentVersionId
                    ?.let(::getNoteVersion)
                    ?.mergeMetadataJson
                    .orEmpty()
                metadata.contains(""""source":"${jsonEscape(source)}"""") &&
                    metadata.contains(""""remoteNoteId":"${jsonEscape(snapshot.id)}"""") &&
                    metadata.contains(""""remoteDeviceId":"${jsonEscape(snapshot.deviceId)}"""") &&
                    metadata.contains(""""remoteUpdatedAt":"${jsonEscape(snapshot.updatedAt.toString())}"""") &&
                    metadata.contains(""""conflictState":"${conflictState.storageValue}"""")
            }

    private fun createConflictCopyFromRemote(
        snapshot: RemoteNoteSnapshot,
        conflictState: ConflictState,
        source: String,
    ): Note {
        val notebookId = resolveConflictNotebookId(snapshot.notebookId)
        return createNoteInternal(
            id = idGenerator.newId("conflict-note"),
            notebookId = notebookId,
            title = "Conflict copy from ${snapshot.deviceId} at ${snapshot.updatedAt}: ${snapshot.title}",
            markdownBody = snapshot.markdownBody,
            createdAt = snapshot.createdAt ?: snapshot.updatedAt,
            timeZoneId = snapshot.timeZoneId,
            location = null,
            syncState = SyncState.CONFLICT,
            conflictState = conflictState,
            versionDeviceId = snapshot.deviceId,
            mergeMetadataJson = """{"source":"${jsonEscape(source)}","remoteNoteId":"${jsonEscape(snapshot.id)}","remoteVersionId":"${jsonEscape(snapshot.currentVersionId.orEmpty())}","remoteBaseVersionId":"${jsonEscape(snapshot.baseVersionId.orEmpty())}","remoteDeviceId":"${jsonEscape(snapshot.deviceId)}","remoteUpdatedAt":"${jsonEscape(snapshot.updatedAt.toString())}","conflictState":"${conflictState.storageValue}"}""",
        )
    }

    private fun resolveConflictNotebookId(preferredNotebookId: String): String {
        getNotebook(preferredNotebookId)?.let { return it.id }

        val existingConflictNotebook = listActiveNotebooks()
            .firstOrNull { it.title == conflictNotebookTitle }
        if (existingConflictNotebook != null) {
            return existingConflictNotebook.id
        }

        return createNotebook(conflictNotebookTitle).id
    }

    private fun createNoteInternal(
        id: String,
        notebookId: String,
        title: String,
        markdownBody: String,
        createdAt: Instant?,
        timeZoneId: String?,
        location: LocationInput?,
        syncState: SyncState,
        conflictState: ConflictState,
        versionDeviceId: String,
        mergeMetadataJson: String? = null,
    ): Note {
        require(title.isNotBlank()) { "Note title must not be blank." }
        requireNotNull(getNotebook(notebookId)) {
            "Cannot create note in missing or deleted notebook: $notebookId"
        }

        var created: Note? = null
        database.transaction {
            val now = clock()
            val noteCreatedAt = createdAt ?: now
            val revision = 1L
            val versionId = idGenerator.newId("version")
            val normalizedTimeZoneId = timeZoneId.normalizedTimeZoneId()
            val hash = contentHashForNote(title, markdownBody, noteCreatedAt, normalizedTimeZoneId)

            queries.insertNote(
                id,
                notebookId,
                title,
                markdownBody,
                excerptFor(markdownBody),
                searchTextFor(title, markdownBody),
                normalizedTimeZoneId,
                noteCreatedAt.toEpochMilliseconds(),
                now.toEpochMilliseconds(),
                hash,
                revision,
                syncState.storageValue,
                versionId,
            )
            queries.insertNoteVersion(
                versionId,
                id,
                null,
                null,
                revision,
                title,
                markdownBody,
                hash,
                versionDeviceId,
                mergeMetadataJson,
                now.toEpochMilliseconds(),
            )
            if (location != null) {
                upsertLocation(id, location, revision, syncState, now)
                markDirty(id, EntityType.LOCATION, revision, now, conflictState)
            }
            markDirty(id, EntityType.NOTE, revision, now, conflictState)
            markDirty(versionId, EntityType.NOTE_VERSION, revision, now, conflictState)
            created = getNote(id)
        }
        return checkNotNull(created)
    }

    private fun applyRemoteAsCurrent(
        snapshot: RemoteNoteSnapshot,
        existing: Note?,
    ) {
        requireNotNull(getNotebook(snapshot.notebookId)) {
            "Cannot apply remote note for missing or deleted notebook: ${snapshot.notebookId}"
        }

        database.transaction {
            val versionId = snapshot.currentVersionId ?: idGenerator.newId("remote-version")
            val createdAt = existing?.createdAt ?: snapshot.createdAt ?: snapshot.updatedAt

            if (existing == null) {
                queries.insertNote(
                    snapshot.id,
                    snapshot.notebookId,
                    snapshot.title,
                    snapshot.markdownBody,
                    excerptFor(snapshot.markdownBody),
                    searchTextFor(snapshot.title, snapshot.markdownBody),
                    snapshot.timeZoneId.normalizedTimeZoneId(),
                    createdAt.toEpochMilliseconds(),
                    snapshot.updatedAt.toEpochMilliseconds(),
                    snapshot.contentHash,
                    snapshot.revision,
                    SyncState.CLEAN.storageValue,
                    versionId,
                )
            } else {
                queries.updateNoteSnapshot(
                    snapshot.notebookId,
                    snapshot.title,
                    snapshot.markdownBody,
                    excerptFor(snapshot.markdownBody),
                    searchTextFor(snapshot.title, snapshot.markdownBody),
                    snapshot.timeZoneId.normalizedTimeZoneId(),
                    createdAt.toEpochMilliseconds(),
                    snapshot.updatedAt.toEpochMilliseconds(),
                    snapshot.contentHash,
                    snapshot.revision,
                    SyncState.CLEAN.storageValue,
                    versionId,
                    snapshot.id,
                )
            }

            queries.insertNoteVersion(
                versionId,
                snapshot.id,
                snapshot.parentVersionId ?: existing?.currentVersionId,
                snapshot.baseVersionId ?: existing?.currentVersionId,
                snapshot.revision,
                snapshot.title,
                snapshot.markdownBody,
                snapshot.contentHash,
                snapshot.deviceId,
                snapshot.mergeMetadataJson ?: """{"source":"remote"}""",
                snapshot.updatedAt.toEpochMilliseconds(),
            )
            upsertSyncMetadata(
                entityId = snapshot.id,
                entityType = EntityType.NOTE,
                localRevision = snapshot.revision,
                remoteRevision = snapshot.revision,
                remoteEtag = snapshot.remoteEtag,
                dirty = false,
                conflictState = ConflictState.NONE,
                updatedAt = snapshot.updatedAt,
            )
        }
    }

    fun getConflictDetails(conflictNoteId: String): ConflictDetails? {
        val conflictNote = getNote(conflictNoteId) ?: return null
        val conflictMetadata = conflictNote.currentVersionId
            ?.let(::getNoteVersion)
            ?.mergeMetadataJson
            .orEmpty()
        val originalNoteId = extractJsonString(conflictMetadata, "remoteNoteId") ?: return null
        val originalVersions = listNoteVersions(originalNoteId)
        val conflictVersions = listNoteVersions(conflictNoteId)
        if (conflictVersions.isEmpty()) {
            return null
        }
        val originalNote = getNote(originalNoteId, includeDeleted = true)
        return ConflictDetails(
            conflictNoteId = conflictNoteId,
            originalNoteId = originalNoteId,
            originalHistory = ConflictHistory(
                noteId = originalNoteId,
                title = originalNote?.title ?: originalVersions.lastOrNull()?.title ?: "Missing original note",
                versions = originalVersions.map { it.toDomainVersionSummary() },
            ),
            conflictHistory = ConflictHistory(
                noteId = conflictNoteId,
                title = conflictNote.title,
                versions = conflictVersions.map { it.toDomainVersionSummary() },
            ),
            sourceDeviceId = extractJsonString(conflictMetadata, "remoteDeviceId"),
            sourceUpdatedAt = extractJsonString(conflictMetadata, "remoteUpdatedAt")
                ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() },
            availableActions = if (originalVersions.isEmpty() || originalNote == null) {
                listOf(
                    ConflictResolutionAction.KeepConflictCopy,
                    ConflictResolutionAction.DeleteConflictCopy,
                )
            } else {
                ConflictResolutionAction.entries
            },
        )
    }

    fun getConflictDetailsForOriginal(originalNoteId: String): ConflictDetails? =
        listAllActiveNotes()
            .asSequence()
            .filter { note ->
                val conflictState = getSyncMetadata(note.id, EntityType.NOTE)?.conflictState
                note.syncState == SyncState.CONFLICT ||
                    (conflictState != null && conflictState != ConflictState.NONE)
            }
            .mapNotNull { note ->
                val conflictMetadata = note.currentVersionId
                    ?.let(::getNoteVersion)
                    ?.mergeMetadataJson
                    .orEmpty()
                if (extractJsonString(conflictMetadata, "remoteNoteId") == originalNoteId) {
                    getConflictDetails(note.id)
                } else {
                    null
                }
            }
            .firstOrNull()

    fun resolveConflictCopy(
        conflictNoteId: String,
        action: ConflictResolutionAction,
    ): Note? {
        val conflictNote = getNote(conflictNoteId) ?: return null
        val details = getConflictDetails(conflictNoteId)
        return when (action) {
            ConflictResolutionAction.KeepConflictCopy -> keepConflictCopy(conflictNote)
            ConflictResolutionAction.DeleteConflictCopy -> {
                deleteNote(conflictNoteId)
                null
            }
            ConflictResolutionAction.RestoreOriginalFromConflict -> {
                val originalNoteId = details?.originalNoteId ?: return keepConflictCopy(conflictNote)
                val original = getNote(originalNoteId) ?: return keepConflictCopy(conflictNote)
                val restored = updateNote(
                    noteId = original.id,
                    title = conflictNote.title.removeConflictTitlePrefix(),
                    markdownBody = conflictNote.markdownBody,
                    createdAt = conflictNote.createdAt,
                    timeZoneId = conflictNote.timeZoneId,
                    clearTimeZone = conflictNote.timeZoneId == null,
                )
                deleteNote(conflictNoteId)
                restored
            }
            ConflictResolutionAction.MergeIntoOriginal -> {
                val originalNoteId = details?.originalNoteId ?: return keepConflictCopy(conflictNote)
                val original = getNote(originalNoteId) ?: return keepConflictCopy(conflictNote)
                val mergedBody = if (original.markdownBody.contains(conflictNote.markdownBody)) {
                    original.markdownBody
                } else {
                    "${original.markdownBody}\n\n---\nMerged conflict copy from ${details.sourceDeviceId ?: "remote device"}\n\n${conflictNote.markdownBody}"
                }
                val merged = updateNote(
                    noteId = original.id,
                    title = original.title,
                    markdownBody = mergedBody,
                    createdAt = original.createdAt,
                )
                deleteNote(conflictNoteId)
                merged
            }
        }
    }

    private fun keepConflictCopy(conflictNote: Note): Note {
        val currentMetadata = getSyncMetadata(conflictNote.id, EntityType.NOTE)
        if (conflictNote.syncState != SyncState.CONFLICT &&
            conflictNote.syncState != SyncState.CLEAN &&
            currentMetadata?.conflictState == ConflictState.NONE
        ) {
            return conflictNote
        }
        database.transaction {
            val now = clock()
            queries.updateNoteSyncState(SyncState.DIRTY.storageValue, conflictNote.id)
            markDirty(conflictNote.id, EntityType.NOTE, conflictNote.revision, now, ConflictState.NONE)
            clearConflictStateForNoteVersions(conflictNote.id, now, dirtyOverride = true)
        }
        return checkNotNull(getNote(conflictNote.id))
    }

    private fun NoteVersion.toDomainVersionSummary(): NoteVersionSummary =
        NoteVersionSummary(
            versionId = versionId,
            noteId = noteId,
            parentVersionId = parentVersionId,
            baseVersionId = baseVersionId,
            revision = revision,
            title = title,
            markdownBody = markdownBody,
            contentHash = contentHash,
            deviceId = deviceId,
            mergeMetadata = mergeMetadataJson,
            createdAt = createdAt,
        )

    private fun extractJsonString(
        metadataJson: String?,
        key: String,
    ): String? {
        if (metadataJson.isNullOrBlank()) {
            return null
        }
        val needle = """"${jsonEscape(key)}":""""
        val start = metadataJson.indexOf(needle)
        if (start < 0) {
            return null
        }
        val valueStart = start + needle.length
        val builder = StringBuilder()
        var index = valueStart
        var escaped = false
        while (index < metadataJson.length) {
            val char = metadataJson[index]
            if (escaped) {
                builder.append(
                    when (char) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> char
                    },
                )
                escaped = false
            } else {
                when (char) {
                    '\\' -> escaped = true
                    '"' -> return builder.toString()
                    else -> builder.append(char)
                }
            }
            index += 1
        }
        return null
    }

    private fun String.removeConflictTitlePrefix(): String =
        substringAfter(": ", this)

    fun getNotebook(
        notebookId: String,
        includeDeleted: Boolean = false,
    ): Notebook? =
        if (includeDeleted) {
            queries.selectNotebookByIdIncludingDeleted(notebookId, ::mapNotebook).executeAsOneOrNull()
        } else {
            queries.selectNotebookById(notebookId, ::mapNotebook).executeAsOneOrNull()
        }

    private fun Note.hasUnconfirmedLocalChanges(): Boolean =
        syncState != SyncState.CLEAN || (getSyncMetadata(id, EntityType.NOTE)?.dirty == true)

    private fun Notebook.hasUnconfirmedLocalChanges(): Boolean =
        syncState != SyncState.CLEAN || (getSyncMetadata(id, EntityType.NOTEBOOK)?.dirty == true)

    private fun NoteLocation?.matches(input: LocationInput): Boolean =
        this != null &&
            latitude == input.latitude &&
            longitude == input.longitude &&
            accuracyMeters == input.accuracyMeters &&
            altitudeMeters == input.altitudeMeters &&
            placeText == input.placeText?.takeIf { it.isNotBlank() } &&
            capturedAt == (input.capturedAt ?: capturedAt)

    private fun upsertLocation(
        noteId: String,
        input: LocationInput,
        revision: Long,
        syncState: SyncState,
        now: Instant,
    ) {
        queries.insertOrReplaceLocation(
            noteId,
            input.latitude,
            input.longitude,
            input.accuracyMeters,
            input.altitudeMeters,
            input.placeText,
            (input.capturedAt ?: now).toEpochMilliseconds(),
            now.toEpochMilliseconds(),
            revision,
            syncState.storageValue,
        )
    }

    private fun upsertTombstone(
        entityId: String,
        entityType: EntityType,
        deletedAt: Instant,
        lastKnownRevision: Long,
    ) {
        queries.upsertTombstone(
            entityId,
            entityType.storageValue,
            deletedAt.toEpochMilliseconds(),
            deviceId,
            lastKnownRevision,
            null,
            true.toLongFlag(),
        )
    }

    private fun markDirty(
        entityId: String,
        entityType: EntityType,
        localRevision: Long,
        updatedAt: Instant,
        conflictState: ConflictState = ConflictState.NONE,
    ) {
        upsertSyncMetadata(
            entityId = entityId,
            entityType = entityType,
            localRevision = localRevision,
            remoteRevision = getSyncMetadata(entityId, entityType)?.remoteRevision,
            remoteEtag = getSyncMetadata(entityId, entityType)?.remoteEtag,
            dirty = true,
            conflictState = conflictState,
            updatedAt = updatedAt,
        )
    }

    private fun clearConflictStateForNoteVersions(
        noteId: String,
        updatedAt: Instant,
        dirtyOverride: Boolean? = null,
    ) {
        listNoteVersions(noteId).forEach { version ->
            val metadata = getSyncMetadata(version.versionId, EntityType.NOTE_VERSION) ?: return@forEach
            clearConflictState(metadata, updatedAt, dirtyOverride)
        }
    }

    private fun clearConflictState(
        metadata: SyncMetadata,
        updatedAt: Instant,
        dirtyOverride: Boolean? = null,
    ) {
        if (metadata.conflictState == ConflictState.NONE && dirtyOverride == null) {
            return
        }
        upsertSyncMetadata(
            entityId = metadata.entityId,
            entityType = metadata.entityType,
            localRevision = metadata.localRevision,
            remoteRevision = metadata.remoteRevision,
            remoteEtag = metadata.remoteEtag,
            dirty = dirtyOverride ?: metadata.dirty,
            conflictState = ConflictState.NONE,
            updatedAt = updatedAt,
            lastSyncedAt = metadata.lastSyncedAt,
            lastError = metadata.lastError,
        )
    }

    private fun upsertSyncMetadata(
        entityId: String,
        entityType: EntityType,
        localRevision: Long,
        remoteRevision: Long?,
        remoteEtag: String?,
        dirty: Boolean,
        conflictState: ConflictState,
        updatedAt: Instant,
        lastSyncedAt: Instant? = null,
        lastError: String? = null,
    ) {
        queries.upsertSyncMetadata(
            entityId,
            entityType.storageValue,
            localRevision,
            remoteRevision,
            remoteEtag,
            """{"${jsonEscape(deviceId)}":$localRevision}""",
            dirty.toLongFlag(),
            conflictState.storageValue,
            lastSyncedAt?.toEpochMilliseconds(),
            lastError,
            updatedAt.toEpochMilliseconds(),
        )
    }

    private companion object {
        const val conflictNotebookTitle = "Recovered conflicts"

        fun sameMonthDayKey(date: LocalDate): String =
            date.toString().substring(startIndex = 5)
    }
}
