@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.requiredLocalTables
import saien.someday.domain.notes.ConflictResolutionAction
import kotlin.time.Instant
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightLocalDataRepositoryTest {
    @Test
    fun jdbcFactoryRegistersSqliteDriverWithDriverManager() {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")

        try {
            val registeredDriver = DriverManager.getDriver("jdbc:sqlite::memory:")

            assertTrue(registeredDriver.acceptsURL("jdbc:sqlite::memory:"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun createsRequiredSchemaFromEmptyDatabase() =
        withFixture { fixture ->
            val tableNames = tableNamesFor(fixture.jdbcUrl)

            assertTrue(tableNames.containsAll(requiredLocalTables), "Missing required tables from $tableNames")
            assertColumns(
                jdbcUrl = fixture.jdbcUrl,
                table = "notes",
                expectedColumns = setOf(
                    "notebook_id",
                    "markdown_body",
                    "created_at",
                    "content_hash",
                    "revision",
                    "sync_state",
                    "current_version_id",
                ),
            )
            assertColumns(
                jdbcUrl = fixture.jdbcUrl,
                table = "note_versions",
                expectedColumns = setOf(
                    "parent_version_id",
                    "base_version_id",
                    "revision",
                    "content_hash",
                    "device_id",
                    "merge_metadata_json",
                ),
            )
            assertColumns(
                jdbcUrl = fixture.jdbcUrl,
                table = "tombstones",
                expectedColumns = setOf(
                    "entity_id",
                    "entity_type",
                    "deleted_at",
                    "deleted_by_device_id",
                    "last_known_revision",
                    "purge_after",
                    "dirty",
                ),
            )
            assertColumns(
                jdbcUrl = fixture.jdbcUrl,
                table = "locations",
                expectedColumns = setOf(
                    "note_id",
                    "latitude",
                    "longitude",
                    "accuracy_meters",
                    "altitude_meters",
                    "place_text",
                    "captured_at",
                ),
            )
            assertColumns(
                jdbcUrl = fixture.jdbcUrl,
                table = "sync_metadata",
                expectedColumns = setOf(
                    "entity_id",
                    "entity_type",
                    "local_revision",
                    "remote_revision",
                    "remote_etag",
                    "vector_clock_json",
                    "dirty",
                    "conflict_state",
                    "last_synced_at",
                ),
            )
        }

    @Test
    fun localNotebookNoteVersionLocationSettingsAndDeviceWritesStayOfflineAndDirty() =
        withFixture { fixture ->
            val repository = fixture.repository

            val device = repository.registerDevice(
                id = "device-local",
                name = "Developer Mac",
                platform = "desktop",
            )
            val setting = repository.putSetting("theme", "dark")
            val notebook = repository.renameNotebook(
                notebookId = repository.createNotebook("Daily", sortOrder = 1).id,
                title = "Daily renamed",
            )
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Offline note",
                markdownBody = "# Hello\nCreated without network",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                location = LocationInput(
                    latitude = 37.3317,
                    longitude = -122.0301,
                    accuracyMeters = 12.5,
                    placeText = "Cupertino",
                ),
            )
            val edited = repository.updateNote(
                noteId = note.id,
                title = "Offline note edited",
                markdownBody = "# Hello\nEdited without network",
            )

            assertEquals(device.id, repository.getDevice("device-local")?.id)
            assertEquals("dark", repository.getSetting("theme")?.value)
            assertEquals(setting.key, repository.getSyncMetadata(setting.key, EntityType.SETTING)?.entityId)
            assertEquals(listOf(notebook.id), repository.listActiveNotebooks().map { it.id })
            assertEquals("Daily renamed", repository.listActiveNotebooks().single().title)
            assertEquals(listOf(edited.id), repository.listActiveNotes(notebook.id).map { it.id })
            assertEquals("Cupertino", repository.getLocation(note.id)?.placeText)
            assertEquals(2, repository.listNoteVersions(note.id).size)
            assertEquals(2L, edited.revision)

            val noteSync = repository.getSyncMetadata(note.id, EntityType.NOTE)
            val notebookSync = repository.getSyncMetadata(notebook.id, EntityType.NOTEBOOK)
            val deviceSync = repository.getSyncMetadata(device.id, EntityType.DEVICE)

            assertNotNull(noteSync)
            assertNotNull(notebookSync)
            assertNotNull(deviceSync)
            assertTrue(noteSync.dirty)
            assertTrue(notebookSync.dirty)
            assertTrue(deviceSync.dirty)
            assertEquals(2L, noteSync.localRevision)
            assertEquals(2L, notebookSync.localRevision)
        }

    @Test
    fun deletingNotesAndNotebooksCreatesDurableDirtyTombstones() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Delete me")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Short lived",
                markdownBody = "Will be deleted",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            val emptyNotebook = repository.createNotebook("Empty")

            repository.deleteNote(note.id)
            repository.deleteNotebook(emptyNotebook.id)

            val deletedNote = repository.getNote(note.id, includeDeleted = true)
            val noteTombstone = repository.getTombstone(note.id, EntityType.NOTE)
            val notebookTombstone = repository.getTombstone(emptyNotebook.id, EntityType.NOTEBOOK)

            assertNotNull(deletedNote?.deletedAt)
            assertNull(repository.getNote(note.id))
            assertTrue(repository.listActiveNotes(notebook.id).isEmpty())
            assertNotNull(noteTombstone)
            assertEquals(note.id, noteTombstone.entityId)
            assertEquals(EntityType.NOTE, noteTombstone.entityType)
            assertEquals("test-device", noteTombstone.deletedByDeviceId)
            assertEquals(deletedNote?.revision, noteTombstone.lastKnownRevision)
            assertTrue(noteTombstone.dirty)
            assertNotNull(notebookTombstone)
            assertTrue(repository.getSyncMetadata(note.id, EntityType.NOTE)?.dirty == true)
            assertTrue(repository.getSyncMetadata(emptyNotebook.id, EntityType.NOTEBOOK)?.dirty == true)

            withReopenedRepository(fixture.jdbcUrl) { reopened ->
                assertNotNull(reopened.getNote(note.id, includeDeleted = true)?.deletedAt)
                assertNotNull(reopened.getTombstone(note.id, EntityType.NOTE))
                assertNotNull(reopened.getTombstone(emptyNotebook.id, EntityType.NOTEBOOK))
            }
        }

    @Test
    fun syncResetMarksCleanTombstonesDirtyForRepublish() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Reset tombstones")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Deleted note",
                markdownBody = "Deletion must survive a sync folder reset.",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )

            repository.deleteNote(note.id)
            val tombstone = assertNotNull(repository.getTombstone(note.id, EntityType.NOTE))
            repository.markTombstoneSynced(
                tombstone = tombstone,
                remoteRevision = tombstone.lastKnownRevision,
                remoteEtag = "etag-note-delete",
            )
            assertFalse(assertNotNull(repository.getTombstone(note.id, EntityType.NOTE)).dirty)

            repository.markAllLocalContentDirtyForSyncReset()

            assertTrue(assertNotNull(repository.getTombstone(note.id, EntityType.NOTE)).dirty)
            assertTrue(repository.getSyncMetadata(note.id, EntityType.TOMBSTONE)?.dirty == true)
        }

    @Test
    fun nonEmptyNotebookDeletionIsBlockedWithoutDeletingOrOrphaningNotes() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Safe delete strategy")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Keep me",
                markdownBody = "Blocking notebook deletion preserves this note.",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )

            val failure = assertFailsWith<IllegalArgumentException> {
                repository.deleteNotebook(notebook.id)
            }

            assertTrue(failure.message.orEmpty().contains("still contains active notes"))
            assertEquals(listOf(notebook.id), repository.listActiveNotebooks().map { it.id })
            assertEquals(listOf(note.id), repository.listActiveNotes(notebook.id).map { it.id })
            assertNull(repository.getTombstone(notebook.id, EntityType.NOTEBOOK))
            assertNull(repository.getNote(note.id)?.deletedAt)
        }

    @Test
    fun activeNoteListsAreFilteredByNotebookSelection() =
        withFixture { fixture ->
            val repository = fixture.repository
            val diary = repository.createNotebook("Diary")
            val work = repository.createNotebook("Work")

            val diaryNote = repository.createNote(
                notebookId = diary.id,
                title = "Diary note",
                markdownBody = "Private memory",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            val workNote = repository.createNote(
                notebookId = work.id,
                title = "Work note",
                markdownBody = "Meeting notes",
                createdAt = Instant.parse("2026-05-23T00:00:00Z"),
            )

            assertEquals(listOf(diaryNote.id), repository.listActiveNotes(diary.id).map { it.id })
            assertEquals(listOf(workNote.id), repository.listActiveNotes(work.id).map { it.id })
        }

    @Test
    fun noteCrudPersistsDiaryDateLocationNotebookChangesAndSoftDelete() =
        withFixture { fixture ->
            val repository = fixture.repository
            val diary = repository.createNotebook("Diary")
            val travel = repository.createNotebook("Travel")
            val note = repository.createNote(
                notebookId = diary.id,
                title = "Morning",
                markdownBody = "Coffee at home",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                location = LocationInput(
                    latitude = 37.3317,
                    longitude = -122.0301,
                    placeText = "Home",
                ),
            )

            val movedAndEdited = repository.updateNote(
                noteId = note.id,
                notebookId = travel.id,
                title = "Travel morning",
                markdownBody = "Coffee near the station",
                createdAt = Instant.parse("2026-05-23T00:00:00Z"),
                location = LocationInput(
                    latitude = 35.985,
                    longitude = 135.758,
                    accuracyMeters = 9.0,
                    placeText = "Kyoto Station",
                ),
            )

            assertTrue(repository.listActiveNotes(diary.id).isEmpty())
            assertEquals(listOf(note.id), repository.listActiveNotes(travel.id).map { it.id })
            assertEquals(travel.id, movedAndEdited.notebookId)
            assertEquals("Travel morning", movedAndEdited.title)
            assertEquals("Coffee near the station", movedAndEdited.markdownBody)
            assertEquals(Instant.parse("2026-05-23T00:00:00Z"), movedAndEdited.createdAt)
            assertEquals("Kyoto Station", repository.getLocation(note.id)?.placeText)
            assertEquals(35.985, repository.getLocation(note.id)?.latitude)
            assertEquals(135.758, repository.getLocation(note.id)?.longitude)
            assertEquals(2, repository.listNoteVersions(note.id).size)

            repository.deleteNote(note.id)

            assertTrue(repository.listActiveNotes(travel.id).isEmpty())
            assertNull(repository.getNote(note.id))
            assertNotNull(repository.getNote(note.id, includeDeleted = true)?.deletedAt)
            assertNotNull(repository.getTombstone(note.id, EntityType.NOTE))
        }

    @Test
    fun markdownSourceRoundTripsExactlyThroughNotesAndVersions() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Markdown diary")
            val originalSource = """
                |# Day one
                |
                |A **bold** memory with [source](https://example.com).
                |
                |```
                |  preserve indentation
                |```
                |
                |Trailing spaces stay here:
            """.trimMargin() + "   "
            val editedSource = "$originalSource\n\n- appended list item"

            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Source stays markdown",
                markdownBody = originalSource,
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            val createdVersion = repository.listNoteVersions(note.id).single()

            assertEquals(originalSource, repository.getNote(note.id)?.markdownBody)
            assertEquals(originalSource, createdVersion.markdownBody)

            repository.updateNote(
                noteId = note.id,
                markdownBody = editedSource,
            )

            val versions = repository.listNoteVersions(note.id)
            assertEquals(2, versions.size)
            assertEquals(originalSource, versions.first().markdownBody)
            assertEquals(editedSource, versions.last().markdownBody)
            assertEquals(editedSource, repository.getNote(note.id)?.markdownBody)
            assertFalse(repository.getNote(note.id)?.markdownBody.orEmpty().contains("<h1>"))
            assertFalse(repository.getNote(note.id)?.markdownBody.orEmpty().contains("<strong>"))
        }

    @Test
    fun meaningfulEditsAppendImmutableVersionsWithParentAndBaseMetadata() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Versioned diary")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Morning",
                markdownBody = "Original memory",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            val originalVersion = repository.listNoteVersions(note.id).single()

            val edited = repository.updateNote(
                noteId = note.id,
                title = "Morning edited",
                markdownBody = "Meaningful saved edit",
            )
            val versionsAfterEdit = repository.listNoteVersions(note.id)

            assertEquals(2, versionsAfterEdit.size)
            assertEquals(originalVersion, versionsAfterEdit.first(), "Historical version rows must remain immutable.")
            assertEquals(edited.currentVersionId, versionsAfterEdit.last().versionId)
            assertEquals(originalVersion.versionId, versionsAfterEdit.last().parentVersionId)
            assertEquals(originalVersion.versionId, versionsAfterEdit.last().baseVersionId)
            assertEquals(2L, versionsAfterEdit.last().revision)
            assertEquals("Meaningful saved edit", versionsAfterEdit.last().markdownBody)
            assertEquals("test-device", versionsAfterEdit.last().deviceId)
            assertEquals(
                contentHashForNote("Morning edited", "Meaningful saved edit", edited.createdAt, edited.timeZoneId),
                versionsAfterEdit.last().contentHash,
            )

            val noOp = repository.updateNote(
                noteId = note.id,
                notebookId = edited.notebookId,
                title = edited.title,
                markdownBody = edited.markdownBody,
                createdAt = edited.createdAt,
            )

            assertEquals(edited.revision, noOp.revision, "A save with no meaningful changes must not advance revision.")
            assertEquals(versionsAfterEdit, repository.listNoteVersions(note.id))
        }

    @Test
    fun restoringOlderVersionCreatesNewHeadWithoutRewritingHistory() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Restore diary")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "First title",
                markdownBody = "First body",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            repository.updateNote(
                noteId = note.id,
                title = "Second title",
                markdownBody = "Second body",
            )
            repository.updateNote(
                noteId = note.id,
                title = "Current title",
                markdownBody = "Current body",
            )
            val beforeRestore = repository.listNoteVersions(note.id)
            val firstVersion = beforeRestore.first()
            val currentBeforeRestore = beforeRestore.last()

            val restored = repository.restoreNoteVersion(note.id, firstVersion.versionId)
            val afterRestore = repository.listNoteVersions(note.id)
            val restoreVersion = afterRestore.last()

            assertEquals(3, beforeRestore.size)
            assertEquals(4, afterRestore.size)
            assertEquals(beforeRestore, afterRestore.take(beforeRestore.size), "Restore must append, not mutate history.")
            assertEquals(firstVersion.title, restored.title)
            assertEquals(firstVersion.markdownBody, restored.markdownBody)
            assertEquals(restoreVersion.versionId, restored.currentVersionId)
            assertNotEquals(firstVersion.versionId, restoreVersion.versionId)
            assertEquals(currentBeforeRestore.versionId, restoreVersion.parentVersionId)
            assertEquals(firstVersion.versionId, restoreVersion.baseVersionId)
            assertTrue(restoreVersion.mergeMetadataJson.orEmpty().contains("restore"))
            assertTrue(restoreVersion.mergeMetadataJson.orEmpty().contains(firstVersion.versionId))
            assertTrue(repository.getSyncMetadata(note.id, EntityType.NOTE)?.dirty == true)
            assertTrue(repository.getSyncMetadata(restoreVersion.versionId, EntityType.NOTE_VERSION)?.dirty == true)
        }

    @Test
    fun localTombstoneIgnoresStaleRemoteNoteInsteadOfResurrectingIt() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Daily")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Local note",
                markdownBody = "Deleted locally",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            repository.deleteNote(note.id)

            val result = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = note.id,
                    notebookId = notebook.id,
                    title = "Stale remote note",
                    markdownBody = "This older remote copy must not return",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    revision = 1,
                    updatedAt = Instant.fromEpochMilliseconds(1_800),
                    deviceId = "remote-device",
                    remoteEtag = "etag-old",
                ),
            )

            assertEquals(RemoteApplyStatus.IGNORED_BY_TOMBSTONE, result.status)
            assertNull(repository.getNote(note.id))
            assertNotNull(repository.getNote(note.id, includeDeleted = true)?.deletedAt)
            assertNotNull(repository.getTombstone(note.id, EntityType.NOTE))
            assertTrue(repository.listActiveNotes(notebook.id).isEmpty())
        }

    @Test
    fun noteVersionForMissingTombstonedNoteIsIgnoredInsteadOfReportedAsConflict() =
        withFixture { fixture ->
            val repository = fixture.repository
            val tombstone = RemoteTombstoneSnapshot(
                entityId = "note-deleted-remotely",
                entityType = EntityType.NOTE,
                deletedAt = Instant.fromEpochMilliseconds(3_000),
                deletedByDeviceId = "phone",
                lastKnownRevision = 3,
                purgeAfter = null,
                remoteEtag = "etag-tombstone",
            )
            repository.applyRemoteTombstoneSnapshot(tombstone)

            val result = repository.applyRemoteNoteVersionSnapshot(
                RemoteNoteVersionSnapshot(
                    versionId = "version-deleted-note-2",
                    noteId = "note-deleted-remotely",
                    parentVersionId = null,
                    baseVersionId = null,
                    revision = 2,
                    title = "Deleted old title",
                    markdownBody = "Deleted old body",
                    contentHash = contentHashForNote(
                        title = "Deleted old title",
                        markdownBody = "Deleted old body",
                        createdAt = Instant.fromEpochMilliseconds(2_000),
                        timeZoneId = null,
                    ),
                    deviceId = "phone",
                    mergeMetadataJson = null,
                    createdAt = Instant.fromEpochMilliseconds(2_000),
                    remoteEtag = "etag-version",
                ),
            )

            assertEquals(RemoteApplyStatus.IGNORED_BY_TOMBSTONE, result.status)
            assertNull(repository.getNoteVersion("version-deleted-note-2"))
            assertNull(repository.getSyncMetadata("version-deleted-note-2", EntityType.NOTE_VERSION))
        }

    @Test
    fun olderRemoteNoteIsIgnoredBeforeDirtyLocalConflictCheck() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Daily")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Local note",
                markdownBody = "Original body",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            val edited = repository.updateNote(
                noteId = note.id,
                markdownBody = "Newer local body",
            )

            val result = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = note.id,
                    notebookId = notebook.id,
                    title = "Local note",
                    markdownBody = "Older remote body",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    revision = edited.revision - 1,
                    updatedAt = Instant.fromEpochMilliseconds(1_500),
                    deviceId = "remote-device",
                    remoteEtag = "etag-older",
                ),
            )

            assertEquals(RemoteApplyStatus.IGNORED_OLDER_REVISION, result.status)
            assertEquals("Newer local body", repository.getNote(note.id)?.markdownBody)
            assertTrue(repository.activeNotesInAllNotebooks().none { it.syncState == SyncState.CONFLICT })
        }

    @Test
    fun remoteNoteWithMissingNotebookCreatesRecoveredConflictCopy() =
        withFixture { fixture ->
            val repository = fixture.repository
            val result = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = "note-remote-missing-parent",
                    notebookId = "notebook-missing-parent",
                    title = "Remote recovered note",
                    markdownBody = "Remote content that could not be applied normally.",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    revision = 3,
                    updatedAt = Instant.fromEpochMilliseconds(2_500),
                    deviceId = "remote-device",
                    remoteEtag = "etag-missing-parent",
                    currentVersionId = "remote-version-missing-parent",
                ),
            )
            val conflictCopyId = checkNotNull(result.noteId)
            val conflictCopy = repository.getNote(conflictCopyId)
            val conflictNotebook = repository.listActiveNotebooks().single { it.title == "Recovered conflicts" }
            val details = repository.getConflictDetails(conflictCopyId)

            assertEquals(RemoteApplyStatus.CONFLICT_COPY_CREATED, result.status)
            assertNull(repository.getNote("note-remote-missing-parent"))
            assertNotNull(conflictCopy)
            assertEquals(conflictNotebook.id, conflictCopy.notebookId)
            assertEquals(SyncState.CONFLICT, conflictCopy.syncState)
            assertEquals("Remote content that could not be applied normally.", conflictCopy.markdownBody)
            assertEquals("note-remote-missing-parent", details?.originalNoteId)
            assertTrue(details?.originalHistory?.versions.orEmpty().isEmpty())
            assertTrue(details?.conflictHistory?.versions?.single()?.title.orEmpty().contains("Remote recovered note"))
            assertEquals(
                "Remote content that could not be applied normally.",
                details?.conflictHistory?.versions?.single()?.markdownBody,
            )
            assertEquals(
                listOf(
                    ConflictResolutionAction.KeepConflictCopy,
                    ConflictResolutionAction.DeleteConflictCopy,
                ),
                details?.availableActions,
            )

            val replay = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = "note-remote-missing-parent",
                    notebookId = "notebook-missing-parent",
                    title = "Remote recovered note",
                    markdownBody = "Remote content that could not be applied normally.",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    revision = 3,
                    updatedAt = Instant.fromEpochMilliseconds(2_500),
                    deviceId = "remote-device",
                    remoteEtag = "etag-missing-parent",
                    currentVersionId = "remote-version-missing-parent",
                ),
            )

            assertEquals(conflictCopyId, replay.noteId)
        }

    @Test
    fun newerRemoteNoteDoesNotOverwriteDirtyLocalEdit() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Daily")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Local note",
                markdownBody = "Original body",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            val edited = repository.updateNote(
                noteId = note.id,
                title = "Local edited title",
                markdownBody = "Local offline edit must survive",
            )

            val result = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = note.id,
                    notebookId = notebook.id,
                    title = "Remote edited title",
                    markdownBody = "Remote edit becomes conflict copy",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    revision = edited.revision + 5,
                    updatedAt = Instant.fromEpochMilliseconds(2_000),
                    deviceId = "remote-device",
                    remoteEtag = "etag-newer",
                ),
            )

            val preservedLocal = repository.getNote(note.id)
            val conflictCopy = repository.getNote(checkNotNull(result.noteId))
            val conflictSync = repository.getSyncMetadata(checkNotNull(result.noteId), EntityType.NOTE)

            assertEquals(RemoteApplyStatus.CONFLICT_COPY_CREATED, result.status)
            assertEquals("Local offline edit must survive", preservedLocal?.markdownBody)
            assertTrue(repository.getSyncMetadata(note.id, EntityType.NOTE)?.dirty == true)
            assertNotNull(conflictCopy)
            assertEquals("Remote edit becomes conflict copy", conflictCopy.markdownBody)
            assertEquals(SyncState.CONFLICT, conflictCopy.syncState)
            assertEquals(ConflictState.MANUAL_RESOLUTION_REQUIRED, conflictSync?.conflictState)
            assertTrue(conflictSync?.dirty == true)
            assertTrue(conflictCopy.title.contains("remote-device"))
            assertTrue(conflictCopy.title.contains("1970-01-01T00:00:02Z"))
            assertEquals(1, repository.listNoteVersions(conflictCopy.id).size)
            assertEquals("remote-device", repository.listNoteVersions(conflictCopy.id).single().deviceId)
            assertTrue(repository.listNoteVersions(conflictCopy.id).single().mergeMetadataJson.orEmpty().contains("remote-vs-dirty-local"))
        }

    @Test
    fun deleteVsEditNewerRemotePreservesTombstoneAndEditedConflictCopy() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Daily")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Local note",
                markdownBody = "Local body before deletion",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            val deleted = repository.deleteNote(note.id)
            val tombstoneBeforeRemote = repository.getTombstone(note.id, EntityType.NOTE)

            val result = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = note.id,
                    notebookId = notebook.id,
                    title = "Remote edited after delete",
                    markdownBody = "Remote edited content must be preserved",
                    createdAt = Instant.parse("2026-05-23T00:00:00Z"),
                    revision = deleted.revision + 7,
                    updatedAt = Instant.fromEpochMilliseconds(3_000),
                    deviceId = "phone-device",
                    remoteEtag = "etag-delete-vs-edit",
                ),
            )

            val conflictCopy = repository.getNote(checkNotNull(result.noteId))
            val conflictSync = repository.getSyncMetadata(checkNotNull(result.noteId), EntityType.NOTE)
            val originalTombstone = repository.getTombstone(note.id, EntityType.NOTE)
            val conflictVersions = repository.listNoteVersions(checkNotNull(result.noteId))

            assertEquals(RemoteApplyStatus.CONFLICT_COPY_CREATED, result.status)
            assertNull(repository.getNote(note.id), "Original note remains deleted.")
            assertNotNull(repository.getNote(note.id, includeDeleted = true)?.deletedAt)
            assertEquals(tombstoneBeforeRemote, originalTombstone, "The local tombstone must remain durable.")
            assertNotNull(conflictCopy)
            assertEquals("Remote edited content must be preserved", conflictCopy.markdownBody)
            assertEquals(SyncState.CONFLICT, conflictCopy.syncState)
            assertEquals(ConflictState.DELETE_VS_EDIT, conflictSync?.conflictState)
            assertTrue(conflictCopy.title.contains("phone-device"))
            assertTrue(conflictCopy.title.contains("1970-01-01T00:00:03Z"))
            assertEquals(1, conflictVersions.size)
            assertEquals("phone-device", conflictVersions.single().deviceId)
            assertTrue(conflictVersions.single().mergeMetadataJson.orEmpty().contains("delete-vs-edit"))
        }

    @Test
    fun concurrentRemoteEditUsesCommonBaseMergeDespiteSkewedClock() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Skewed clocks")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Morning",
                markdownBody = "Coffee at home",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            val baseVersion = repository.listNoteVersions(note.id).single()
            repository.markEntitySynced(note.id, EntityType.NOTE, note.revision, "etag-base")
            repository.markEntitySynced(baseVersion.versionId, EntityType.NOTE_VERSION, baseVersion.revision, "etag-base-version")

            val localEdited = repository.updateNote(
                noteId = note.id,
                title = "Morning walk",
            )

            val result = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = note.id,
                    notebookId = notebook.id,
                    title = "Morning",
                    markdownBody = "Coffee at home\nRemote phone added breakfast details.",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    revision = localEdited.revision,
                    updatedAt = Instant.fromEpochMilliseconds(100),
                    deviceId = "phone-with-slow-clock",
                    remoteEtag = "etag-remote",
                    currentVersionId = "remote-version-skewed",
                    parentVersionId = baseVersion.versionId,
                    baseVersionId = baseVersion.versionId,
                ),
            )

            val merged = repository.getNote(note.id)
            val versions = repository.listNoteVersions(note.id)
            val mergeVersion = versions.last()

            assertEquals(RemoteApplyStatus.MERGED_COMMON_BASE, result.status)
            assertEquals("Morning walk", merged?.title, "Local title edit must survive.")
            assertEquals(
                "Coffee at home\nRemote phone added breakfast details.",
                merged?.markdownBody,
                "Remote body edit must be merged even though its wall clock is older.",
            )
            assertTrue(repository.getSyncMetadata(note.id, EntityType.NOTE)?.dirty == true)
            assertTrue(versions.any { it.versionId == "remote-version-skewed" && it.deviceId == "phone-with-slow-clock" })
            assertEquals(localEdited.currentVersionId, mergeVersion.parentVersionId)
            assertEquals(baseVersion.versionId, mergeVersion.baseVersionId)
            assertTrue(mergeVersion.mergeMetadataJson.orEmpty().contains("common-base-auto-merge"))
            assertTrue(mergeVersion.mergeMetadataJson.orEmpty().contains("remote-version-skewed"))
        }

    @Test
    fun repeatedFailedCommonBaseConflictDoesNotCreateDuplicateConflictCopies() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Manual merge")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Morning",
                markdownBody = "Original body",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            val baseVersion = repository.listNoteVersions(note.id).single()
            repository.markEntitySynced(note.id, EntityType.NOTE, note.revision, "etag-base")
            repository.markEntitySynced(baseVersion.versionId, EntityType.NOTE_VERSION, baseVersion.revision, "etag-base-version")
            repository.updateNote(
                noteId = note.id,
                markdownBody = "Local rewrite of the body",
            )
            val remoteSnapshot = RemoteNoteSnapshot(
                id = note.id,
                notebookId = notebook.id,
                title = "Morning",
                markdownBody = "Remote rewrite of the body",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                revision = 2,
                updatedAt = Instant.fromEpochMilliseconds(200),
                deviceId = "phone",
                remoteEtag = "etag-conflict",
                currentVersionId = "remote-version-conflict",
                parentVersionId = baseVersion.versionId,
                baseVersionId = baseVersion.versionId,
            )

            val first = repository.applyRemoteNoteSnapshot(remoteSnapshot)
            val replay = repository.applyRemoteNoteSnapshot(remoteSnapshot)
            val conflictCopies = repository.activeNotesInAllNotebooks()
                .filter { it.syncState == SyncState.CONFLICT }

            assertEquals(RemoteApplyStatus.CONFLICT_COPY_CREATED, first.status)
            assertEquals(RemoteApplyStatus.CONFLICT_COPY_CREATED, replay.status)
            assertEquals(first.noteId, replay.noteId)
            assertEquals(1, conflictCopies.size, "The same remote conflict must not create duplicate visible copies.")
        }

    @Test
    fun conflictDetailsCanBeFoundFromOriginalNote() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Manual merge")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Morning",
                markdownBody = "Original body",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            repository.updateNote(
                noteId = note.id,
                markdownBody = "Local dirty body",
            )
            val conflict = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = note.id,
                    notebookId = notebook.id,
                    title = "Remote morning",
                    markdownBody = "Remote dirty body",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    revision = 5,
                    updatedAt = Instant.fromEpochMilliseconds(300),
                    deviceId = "phone",
                    remoteEtag = "etag-conflict",
                ),
            )

            val details = repository.getConflictDetailsForOriginal(note.id)

            assertEquals(conflict.noteId, details?.conflictNoteId)
            assertEquals(note.id, details?.originalNoteId)
            assertTrue(details?.conflictHistory?.versions.orEmpty().any { it.markdownBody == "Remote dirty body" })
        }

    @Test
    fun keepConflictCopyResolutionIsIdempotentAndRemainsDirtyForSync() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Resolution")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Original",
                markdownBody = "Local body",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            repository.updateNote(noteId = note.id, markdownBody = "Local dirty body")
            val conflict = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = note.id,
                    notebookId = notebook.id,
                    title = "Remote",
                    markdownBody = "Remote body to keep",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    revision = 5,
                    updatedAt = Instant.fromEpochMilliseconds(300),
                    deviceId = "tablet",
                    remoteEtag = "etag-resolution",
                ),
            )
            val conflictNoteId = checkNotNull(conflict.noteId)
            val conflictVersionId = checkNotNull(repository.getNote(conflictNoteId)?.currentVersionId)
            val versionsBefore = repository.listNoteVersions(conflictNoteId)

            val kept = repository.resolveConflictCopy(
                conflictNoteId = conflictNoteId,
                action = ConflictResolutionAction.KeepConflictCopy,
            )
            val replay = repository.resolveConflictCopy(
                conflictNoteId = conflictNoteId,
                action = ConflictResolutionAction.KeepConflictCopy,
            )
            val syncMetadata = repository.getSyncMetadata(conflictNoteId, EntityType.NOTE)
            val versionMetadata = repository.getSyncMetadata(conflictVersionId, EntityType.NOTE_VERSION)

            assertEquals(kept, replay)
            assertEquals(SyncState.DIRTY, kept?.syncState)
            assertEquals(ConflictState.NONE, syncMetadata?.conflictState)
            assertTrue(syncMetadata?.dirty == true)
            assertEquals(ConflictState.NONE, versionMetadata?.conflictState)
            assertTrue(versionMetadata?.dirty == true)
            assertEquals(versionsBefore, repository.listNoteVersions(conflictNoteId))
            assertEquals(1, repository.activeNotesInAllNotebooks().count { it.id == conflictNoteId })
        }

    @Test
    fun deletingConflictCopyClearsVersionConflictMetadata() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Delete conflict")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Original",
                markdownBody = "Local body",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            repository.updateNote(noteId = note.id, markdownBody = "Local dirty body")
            val conflict = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = note.id,
                    notebookId = notebook.id,
                    title = "Remote",
                    markdownBody = "Remote body to delete",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    revision = 5,
                    updatedAt = Instant.fromEpochMilliseconds(300),
                    deviceId = "tablet",
                    remoteEtag = "etag-delete-conflict",
                ),
            )
            val conflictNoteId = checkNotNull(conflict.noteId)
            val conflictVersionId = checkNotNull(repository.getNote(conflictNoteId)?.currentVersionId)

            assertEquals(
                ConflictState.MANUAL_RESOLUTION_REQUIRED,
                repository.getSyncMetadata(conflictVersionId, EntityType.NOTE_VERSION)?.conflictState,
            )

            repository.deleteNote(conflictNoteId)

            assertEquals(
                ConflictState.NONE,
                repository.getSyncMetadata(conflictVersionId, EntityType.NOTE_VERSION)?.conflictState,
            )
        }

    @Test
    fun openingRepositoryPrunesDeletedConflictVersionMetadata() =
        withFixture { fixture ->
            val repository = fixture.repository
            val notebook = repository.createNotebook("Prune conflict")
            val note = repository.createNote(
                notebookId = notebook.id,
                title = "Original",
                markdownBody = "Local body",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
            )
            repository.updateNote(noteId = note.id, markdownBody = "Local dirty body")
            val conflict = repository.applyRemoteNoteSnapshot(
                RemoteNoteSnapshot(
                    id = note.id,
                    notebookId = notebook.id,
                    title = "Remote",
                    markdownBody = "Remote body to prune",
                    createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                    revision = 5,
                    updatedAt = Instant.fromEpochMilliseconds(300),
                    deviceId = "tablet",
                    remoteEtag = "etag-prune-conflict",
                ),
            )
            val conflictNoteId = checkNotNull(conflict.noteId)
            val conflictVersionId = checkNotNull(repository.getNote(conflictNoteId)?.currentVersionId)
            repository.deleteNote(conflictNoteId)
            val metadata = checkNotNull(repository.getSyncMetadata(conflictVersionId, EntityType.NOTE_VERSION))
            fixture.database.somedayQueries.upsertSyncMetadata(
                metadata.entityId,
                metadata.entityType.storageValue,
                metadata.localRevision,
                metadata.remoteRevision,
                metadata.remoteEtag,
                metadata.vectorClockJson,
                metadata.dirty.toLongFlag(),
                ConflictState.MANUAL_RESOLUTION_REQUIRED.storageValue,
                metadata.lastSyncedAt?.toEpochMilliseconds(),
                metadata.lastError,
                metadata.updatedAt.toEpochMilliseconds(),
            )

            assertEquals(
                ConflictState.MANUAL_RESOLUTION_REQUIRED,
                repository.getSyncMetadata(conflictVersionId, EntityType.NOTE_VERSION)?.conflictState,
            )

            withReopenedRepository(fixture.jdbcUrl) { reopened ->
                assertEquals(
                    ConflictState.NONE,
                    reopened.getSyncMetadata(conflictVersionId, EntityType.NOTE_VERSION)?.conflictState,
                )
            }
        }

    private fun withFixture(block: (RepositoryFixture) -> Unit) {
        val dbPath = Files.createTempFile("someday-local-data-", ".db")
        val jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        val driver = createSomedayJdbcDriver(jdbcUrl)
        val database = SomedayDatabase(driver)
        val repository = SqlDelightLocalDataRepository(
            database = database,
            deviceId = "test-device",
            clock = { Instant.fromEpochMilliseconds(1_000) },
            idGenerator = SequentialTestIdGenerator(),
        )

        try {
            block(RepositoryFixture(jdbcUrl, database, repository))
        } finally {
            driver.close()
            Files.deleteIfExists(dbPath)
        }
    }

    private data class RepositoryFixture(
        val jdbcUrl: String,
        val database: SomedayDatabase,
        val repository: SqlDelightLocalDataRepository,
    )

    private fun withReopenedRepository(
        jdbcUrl: String,
        block: (SqlDelightLocalDataRepository) -> Unit,
    ) {
        val driver = JdbcSqliteDriver(jdbcUrl)
        val database = SomedayDatabase(driver)
        val repository = SqlDelightLocalDataRepository(
            database = database,
            deviceId = "test-device",
            clock = { Instant.fromEpochMilliseconds(3_000) },
            idGenerator = SequentialTestIdGenerator(),
        )

        try {
            block(repository)
        } finally {
            driver.close()
        }
    }

    private fun tableNamesFor(jdbcUrl: String): Set<String> =
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")
                    .use { resultSet ->
                        buildSet {
                            while (resultSet.next()) {
                                add(resultSet.getString("name"))
                            }
                        }
                    }
            }
        }

    private fun assertColumns(
        jdbcUrl: String,
        table: String,
        expectedColumns: Set<String>,
    ) {
        val actualColumns = tableColumnsFor(jdbcUrl, table)

        assertTrue(
            actualColumns.containsAll(expectedColumns),
            "Table $table is missing columns ${expectedColumns - actualColumns}; actual=$actualColumns",
        )
    }

    private fun tableColumnsFor(
        jdbcUrl: String,
        table: String,
    ): Set<String> =
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("PRAGMA table_info($table)")
                    .use { resultSet ->
                        buildSet {
                            while (resultSet.next()) {
                                add(resultSet.getString("name"))
                            }
                        }
                    }
            }
        }

    private fun SqlDelightLocalDataRepository.activeNotesInAllNotebooks(): List<Note> =
        listActiveNotebooks().flatMap { notebook -> listActiveNotes(notebook.id) }

    private class SequentialTestIdGenerator : LocalIdGenerator {
        private var next = 0

        override fun newId(prefix: String): String {
            next += 1
            return "$prefix-$next"
        }
    }
}
