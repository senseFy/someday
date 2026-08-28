@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.export.LocalDataExporter
import saien.someday.data.export.LocalDataImporter
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.notes.NoteBatchDeletion
import saien.someday.domain.notes.NoteBatchUndelete
import saien.someday.domain.notes.NoteBatchUpdate
import saien.someday.domain.notes.NotebookOrderEdit
import saien.someday.domain.notes.NoteSyncBadge
import saien.someday.domain.notes.NotesLocationInput
import saien.someday.domain.notes.DeletedWorkspaceItemType
import saien.someday.domain.notes.ConflictBranchResolutionResult
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.EditorPreferences
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspacePreferencesSyncStatus
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemV2ProductRepositoriesTest {
    @Test
    fun noteBatchesValidateAllTokensBeforeOneAtomicCommitAndCanBeUndone() = withFixture { fixture ->
        val notebook = fixture.notes.createNotebook("Batch")
        val first = fixture.notes.createNote(NoteInput(notebook.id, "First", "One"))
        val second = fixture.notes.createNote(NoteInput(notebook.id, "Second", "Two"))
        val firstEdit = NoteBatchUpdate(
            first.id,
            NoteInput(
                notebookId = first.notebookId,
                title = "First updated",
                markdownBody = first.markdownBody,
                createdAt = first.createdAt,
                location = first.location,
                timeZoneId = first.timeZoneId,
                causalToken = first.causalToken,
            ),
        )
        val invalidSecondEdit = NoteBatchUpdate(
            second.id,
            NoteInput(
                notebookId = second.notebookId,
                title = "Second updated",
                markdownBody = second.markdownBody,
                createdAt = second.createdAt,
                location = second.location,
                timeZoneId = second.timeZoneId,
                causalToken = checkNotNull(second.causalToken).copy(expectedBaseVersionId = "missing-version"),
            ),
        )

        assertFails { fixture.notes.updateNotes(listOf(firstEdit, invalidSecondEdit)) }
        assertEquals("First", fixture.notes.getNoteDetails(first.id)?.title)
        assertEquals("Second", fixture.notes.getNoteDetails(second.id)?.title)

        val updated = fixture.notes.updateNotes(
            listOf(
                firstEdit,
                invalidSecondEdit.copy(
                    input = invalidSecondEdit.input.copy(causalToken = second.causalToken),
                ),
            ),
        )
        assertEquals(setOf("First updated", "Second updated"), updated.map { it.title }.toSet())

        fixture.notes.deleteNotes(updated.map { NoteBatchDeletion(it.id, checkNotNull(it.causalToken)) })
        assertTrue(fixture.notes.listNotes(notebook.id).isEmpty())
        val deleted = fixture.notes.listDeletedWorkspaceItems()
            .filter { it.type == DeletedWorkspaceItemType.Note }
        assertEquals(setOf(first.id, second.id), deleted.map { it.entityId }.toSet())

        fixture.notes.undeleteNotes(deleted.map { item ->
            NoteBatchUndelete(
                noteId = item.entityId,
                retainedContentVersionId = checkNotNull(item.retainedContentVersionId),
                causalToken = item.causalToken,
            )
        })
        assertEquals(
            setOf("First updated", "Second updated"),
            fixture.notes.listNotes(notebook.id).map { it.title }.toSet(),
        )
    }

    @Test
    fun settingsReadWaitsForProductRouteCommitBarrier() = withFixture { fixture ->
        val coordinator = WorkspaceAuthorityMutationCoordinator()
        val settings = SystemV2ClientSettingsRepository(
            fixture.local,
            fixture.rawSettings,
            { fixture.workspaceKey },
            { WRITER_A },
            { PROFILE },
            clock = { T1 },
            authorityMutationCoordinator = coordinator,
        )
        val barrierEntered = CountDownLatch(1)
        val releaseBarrier = CountDownLatch(1)
        val readStarted = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val barrier = executor.submit {
                coordinator.productAccess {
                    barrierEntered.countDown()
                    check(releaseBarrier.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(barrierEntered.await(5, TimeUnit.SECONDS))
            val read = executor.submit<ClientSettings> {
                readStarted.countDown()
                try {
                    settings.load()
                } finally {
                    readFinished.countDown()
                }
            }
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            assertFalse(readFinished.await(150, TimeUnit.MILLISECONDS))

            releaseBarrier.countDown()
            assertEquals(WRITER_A, read.get(5, TimeUnit.SECONDS).activeDeviceId)
            barrier.get(5, TimeUnit.SECONDS)
        } finally {
            releaseBarrier.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun deletedItemSurfaceExplicitlyUndeletesCompleteNoteAndNotebookSnapshots() = withFixture { fixture ->
        val notebook = fixture.notes.createNotebook("Retained notebook")
        val note = fixture.notes.createNote(
            NoteInput(
                notebook.id,
                "Retained note",
                "Retained body",
                location = NotesLocationInput(12.5, 44.75, "Retained place", capturedAt = T1),
            ),
        )
        fixture.notes.deleteNote(note.id, checkNotNull(note.causalToken))
        fixture.notes.deleteNotebook(notebook.id, checkNotNull(notebook.causalToken))

        val deleted = fixture.notes.listDeletedWorkspaceItems()

        assertEquals(setOf(DeletedWorkspaceItemType.Note, DeletedWorkspaceItemType.Notebook), deleted.map { it.type }.toSet())
        assertTrue(deleted.all { it.canRestore })
        val deletedBook = deleted.single { it.type == DeletedWorkspaceItemType.Notebook }
        val restoredBook = fixture.notes.restoreNotebook(
            deletedBook.entityId,
            checkNotNull(deletedBook.retainedContentVersionId),
            deletedBook.causalToken,
        )
        assertEquals("Retained notebook", restoredBook.title)
        val deletedNote = fixture.notes.listDeletedWorkspaceItems().single { it.type == DeletedWorkspaceItemType.Note }
        val restoredNote = fixture.notes.undeleteNote(
            deletedNote.entityId,
            checkNotNull(deletedNote.retainedContentVersionId),
            deletedNote.causalToken,
        )
        assertEquals("Retained body", restoredNote.markdownBody)
        assertEquals("Retained place", restoredNote.location?.placeText)
        assertTrue(fixture.notes.listDeletedWorkspaceItems().isEmpty())
    }

    @Test
    fun productNotesAndNotebooksUseOneDagWithLocationAndExactTokens() = withFixture { fixture ->
        val notes = fixture.notes
        val notebook = notes.createNotebook("Journal")
        val note = notes.createNote(
            NoteInput(
                notebookId = notebook.id,
                title = "Day",
                markdownBody = "Original",
                location = NotesLocationInput(
                    latitude = 31.2304,
                    longitude = 121.4737,
                    placeText = "Shanghai",
                    accuracyMeters = 8.0,
                ),
            ),
        )
        assertNotNull(note.causalToken)
        assertNotNull(note.location?.capturedAt)
        val stored = fixture.context().store.loadHeads(WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, note.id)).single()
        assertEquals("Shanghai", (stored.contentPayload as NoteContentV2).location?.placeText)

        val opened = notes.getNoteDetails(note.id)!!
        val remote = fixture.context().factory.createContentChild(
            parent = stored,
            content = (stored.contentPayload as NoteContentV2).copy(title = "Remote title"),
            deviceActorId = "device:$WRITER_B",
            authoredAt = T2,
        )
        fixture.commit(remote, T2)
        val saved = notes.updateNote(
            note.id,
            NoteInput(
                notebookId = opened.notebookId,
                title = opened.title,
                markdownBody = "Local body",
                createdAt = opened.createdAt,
                location = opened.location,
                timeZoneId = opened.timeZoneId,
                causalToken = opened.causalToken,
            ),
        )
        assertEquals("Remote title", saved.title)
        assertEquals("Local body", saved.markdownBody)
        assertEquals(1, fixture.context().store.loadHeads(WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, note.id)).size)

        val clearedLocation = notes.updateNote(
            note.id,
            NoteInput(
                notebookId = saved.notebookId,
                title = saved.title,
                markdownBody = saved.markdownBody,
                createdAt = saved.createdAt,
                location = null,
                timeZoneId = saved.timeZoneId,
                causalToken = saved.causalToken,
            ),
        )
        assertNull(clearedLocation.location)
        assertNull(
            (fixture.context().store.loadHeads(noteKeyForTest(note.id)).single().contentPayload as NoteContentV2).location,
        )

        assertFailsWith<IllegalStateException> { notes.deleteNote(note.id) }
        notes.deleteNote(note.id, checkNotNull(clearedLocation.causalToken))
        val deleted = fixture.context().store.loadHeads(WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, note.id)).single()
        assertEquals(WorkspaceEntityVersionKindV2.DELETION, deleted.kind)
        val undeleted = notes.undeleteNote(
            note.id,
            retainedContentVersionId = stored.versionId,
            causalToken = saien.someday.domain.notes.CausalEditToken(
                fixture.epochId,
                WorkspaceEntityTypeV2.NOTE.wireValue,
                note.id,
                deleted.versionId,
            ),
        )
        assertEquals("Shanghai", undeleted.location?.placeText)
    }

    @Test
    fun listNotesOrdersByJournalCreatedAtNotVersionAuthoredAt() = withFixture { fixture ->
        val notes = fixture.notes
        val notebook = notes.createNotebook("Journal")
        // Same authored wall-clock as a bulk import, different journal dates.
        val olderJournal = Instant.parse("2023-01-15T08:00:00Z")
        val newerJournal = Instant.parse("2024-06-20T08:00:00Z")
        val bulkAuthored = Instant.parse("2025-01-01T00:00:00Z")
        val olderId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val newerId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val older = fixture.context().factory.createGenesis(
            entityType = WorkspaceEntityTypeV2.NOTE,
            entityId = olderId,
            content = NoteContentV2(
                notebookId = notebook.id,
                title = "Older journal entry",
                markdownBody = "older journal day",
                noteCreatedAt = olderJournal,
                timeZoneId = "UTC",
                location = null,
            ),
            deviceActorId = "device:$WRITER_B",
            authoredAt = bulkAuthored,
        )
        val newer = fixture.context().factory.createGenesis(
            entityType = WorkspaceEntityTypeV2.NOTE,
            entityId = newerId,
            content = NoteContentV2(
                notebookId = notebook.id,
                title = "Newer journal entry",
                markdownBody = "newer journal day",
                noteCreatedAt = newerJournal,
                timeZoneId = null,
                location = null,
            ),
            deviceActorId = "device:$WRITER_B",
            authoredAt = bulkAuthored,
        )
        // Apply older first so list order cannot follow insertion/id accident alone.
        fixture.applyRemote(older, "sort-1")
        fixture.applyRemote(newer, "sort-2")

        val listed = notes.listNotes(notebook.id)
        assertEquals(listOf("Newer journal entry", "Older journal entry"), listed.map { it.title })
        assertEquals(listOf(newerId, olderId), listed.map { it.id })
        assertEquals(newerJournal, listed[0].createdAt)
        assertEquals(olderJournal, listed[1].createdAt)
        // updatedAt may still reflect version authoredAt; it must not reorder the list.
        assertEquals(bulkAuthored, listed[0].updatedAt)
        assertEquals(bulkAuthored, listed[1].updatedAt)

        val other = notes.createNotebook("Other")
        assertTrue(notes.listNotes(other.id).isEmpty())
        assertEquals(1, notes.listNotes(notebook.id).count { it.id == newerId })
    }

    @Test
    fun noteHistoryConflictAndEveryCommandPreserveCompletePayloadAndExactTokens() = withFixture { fixture ->
        val firstNotebook = fixture.notes.createNotebook("First")
        val secondNotebook = fixture.notes.createNotebook("Second")
        val original = fixture.notes.createNote(
            NoteInput(
                notebookId = firstNotebook.id,
                title = "Historical title",
                markdownBody = "Historical body",
                createdAt = T1,
                timeZoneId = "Asia/Shanghai",
                location = NotesLocationInput(31.2, 121.4, "Historical place", 4.0, 12.0, T1),
            ),
        )
        val historicalVersionId = checkNotNull(original.causalToken).expectedBaseVersionId
        val changed = fixture.notes.updateNote(
            original.id,
            NoteInput(
                notebookId = secondNotebook.id,
                title = "Current title",
                markdownBody = "Current body",
                createdAt = T2,
                timeZoneId = "UTC",
                location = NotesLocationInput(null, null, "Current place", null, null, T2),
                causalToken = original.causalToken,
            ),
        )
        val changedVersionId = checkNotNull(changed.causalToken).expectedBaseVersionId
        val restored = fixture.notes.restoreNoteVersion(
            original.id,
            historicalVersionId,
            checkNotNull(changed.causalToken),
        )
        assertEquals("Historical title", restored.title)
        assertEquals("Historical body", restored.markdownBody)
        assertEquals(secondNotebook.id, restored.notebookId)
        assertEquals(T2, restored.createdAt)
        assertEquals("UTC", restored.timeZoneId)
        assertEquals("Current place", restored.location?.placeText)
        assertEquals(T2, restored.location?.capturedAt)
        val restoredToken = checkNotNull(restored.causalToken)
        assertTrue(restoredToken.expectedBaseVersionId != changedVersionId)
        assertEquals(
            listOf(changedVersionId),
            fixture.context().store.loadVersion(restoredToken.expectedBaseVersionId)?.parentVersionIds,
        )

        val opened = fixture.notes.getNoteDetails(original.id)!!
        val base = fixture.context().store.loadVersion(checkNotNull(opened.causalToken).expectedBaseVersionId)!!
        val baseContent = base.contentPayload as NoteContentV2
        val remote = fixture.context().factory.createContentChild(
            base,
            baseContent.copy(title = "Remote title"),
            "device:$WRITER_B",
            T3,
        )
        fixture.applyRemote(remote, "1")
        val localBranch = fixture.notes.updateNote(
            original.id,
            NoteInput(
                notebookId = opened.notebookId,
                title = "Local title",
                markdownBody = opened.markdownBody,
                createdAt = opened.createdAt,
                timeZoneId = opened.timeZoneId,
                location = opened.location,
                causalToken = opened.causalToken,
            ),
        )
        val conflict = assertNotNull(fixture.notes.getConflictDetails(original.id))
        assertEquals(2, conflict.versionBranches.size)
        assertEquals(conflict.expectedHeadVersionIds, conflict.versionBranches.map { it.versionId }.sorted())
        assertEquals(
            setOf("Local title", "Remote title"),
            conflict.versionBranches.map { it.history.versions.last().title }.toSet(),
        )
        assertTrue(localBranch.syncBadge is NoteSyncBadge.Conflict)
        assertFailsWith<IllegalArgumentException> {
            fixture.notes.updateNote(
                original.id,
                NoteInput(
                    localBranch.notebookId,
                    "Ambiguous save",
                    localBranch.markdownBody,
                    localBranch.createdAt,
                    localBranch.location,
                    localBranch.timeZoneId,
                    localBranch.causalToken,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.notes.resolveConflictBranch(
                conflict.conflictNoteId,
                conflict.expectedHeadVersionIds.first(),
                conflict.expectedHeadVersionIds.dropLast(1),
            )
        }
        val selectedId = conflict.expectedHeadVersionIds.single { id ->
            (fixture.context().store.loadVersion(id)?.contentPayload as? NoteContentV2)?.title == "Remote title"
        }
        val resolved = assertIs<ConflictBranchResolutionResult.Content>(
            fixture.notes.resolveConflictBranch(conflict.conflictNoteId, selectedId, conflict.expectedHeadVersionIds),
        ).note
        assertEquals("Remote title", resolved.title)
        assertEquals(secondNotebook.id, resolved.notebookId)
        assertEquals("Current place", resolved.location?.placeText)
        assertNull(fixture.notes.getConflictDetails(original.id))
        val resolution = fixture.context().store.loadHeads(noteKeyForTest(original.id)).single()
        assertTrue(conflict.expectedHeadVersionIds.all { fixture.isAncestor(it, resolution.versionId) })
    }

    @Test
    fun selectingADeletionHeadReturnsAnExplicitSuccessfulDeletion() = withFixture { fixture ->
        val notebook = fixture.notes.createNotebook("Diary")
        val note = fixture.notes.createNote(NoteInput(notebook.id, "Local", "Body"))
        val base = fixture.context().store.loadVersion(
            checkNotNull(note.causalToken).expectedBaseVersionId,
        )!!
        val remoteDeletion = fixture.context().factory.createDeletion(
            parent = base,
            deletedAt = T2,
            deviceActorId = "device:$WRITER_B",
            authoredAt = T2,
        )
        fixture.applyRemote(remoteDeletion, "remote-deletion")
        fixture.notes.updateNote(
            note.id,
            NoteInput(
                notebookId = note.notebookId,
                title = "Local edit",
                markdownBody = note.markdownBody,
                createdAt = note.createdAt,
                location = note.location,
                timeZoneId = note.timeZoneId,
                causalToken = note.causalToken,
            ),
        )
        val conflict = assertNotNull(fixture.notes.getConflictDetails(note.id))
        val deletionHead = conflict.versionBranches.single { it.deleted }

        val result = fixture.notes.resolveConflictBranch(
            conflict.conflictNoteId,
            deletionHead.versionId,
            conflict.expectedHeadVersionIds,
        )

        assertEquals(ConflictBranchResolutionResult.Deletion, result)
        assertNull(fixture.notes.getNoteDetails(note.id))
        assertNull(fixture.notes.getConflictDetails(note.id))
    }

    @Test
    fun notebookReferencesRecoveryOrderingAndStaleViewCommandsAreCausal() = withFixture { fixture ->
        fixture.createPreferenceRoot()
        val protected = fixture.notes.createNotebook("Protected")
        val target = fixture.notes.createNotebook("Target")
        val renamedTarget = fixture.notes.renameNotebook(
            target.id,
            "Renamed target",
            checkNotNull(target.causalToken),
        )
        assertEquals("Renamed target", renamedTarget.title)
        assertEquals(
            listOf(checkNotNull(target.causalToken).expectedBaseVersionId),
            fixture.context().store.loadVersion(checkNotNull(renamedTarget.causalToken).expectedBaseVersionId)?.parentVersionIds,
        )
        val note = fixture.notes.createNote(NoteInput(protected.id, "Reference", "Body"))
        assertFailsWith<IllegalArgumentException> {
            fixture.notes.deleteNotebook(protected.id, checkNotNull(protected.causalToken))
        }
        val moved = fixture.notes.updateNote(
            note.id,
            NoteInput(
                target.id,
                note.title,
                note.markdownBody,
                note.createdAt,
                note.location,
                note.timeZoneId,
                note.causalToken,
            ),
        )
        assertEquals(target.id, moved.notebookId)
        val withDefault = fixture.settings.save(fixture.settings.load().copy(defaultNotebookId = protected.id))
        assertEquals(protected.id, withDefault.defaultNotebookId)
        assertFailsWith<IllegalArgumentException> {
            fixture.notes.deleteNotebook(protected.id, checkNotNull(protected.causalToken))
        }
        fixture.settings.save(withDefault.copy(defaultNotebookId = null))
        fixture.notes.deleteNotebook(protected.id, checkNotNull(protected.causalToken))

        val dangling = fixture.context().factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            DANGLING_NOTE_ID,
            NoteContentV2(MISSING_NOTEBOOK_ID, "Recovered", "Still retained", T1, null, null),
            "device:$WRITER_B",
            T2,
        )
        fixture.applyRemote(dangling, "1")
        assertTrue(fixture.notes.listNotebooks().any { it.id == RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2 })
        assertEquals(DANGLING_NOTE_ID, fixture.notes.listNotes(RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2).single().id)
        assertEquals(
            MISSING_NOTEBOOK_ID,
            (fixture.context().store.loadHeads(noteKeyForTest(DANGLING_NOTE_ID)).single().contentPayload as NoteContentV2).notebookId,
        )
        val recoveredNotebook = fixture.context().factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            MISSING_NOTEBOOK_ID,
            NotebookContentV2("Arrived later", 7, T1),
            "device:$WRITER_B",
            T3,
        )
        fixture.applyRemote(recoveredNotebook, "2")
        assertTrue(fixture.notes.listNotebooks().none { it.id == RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2 })
        assertEquals(DANGLING_NOTE_ID, fixture.notes.listNotes(MISSING_NOTEBOOK_ID).single().id)

        val targetView = fixture.notes.listNotebooks().single { it.id == target.id }
        val targetBase = fixture.context().store.loadVersion(
            checkNotNull(targetView.causalToken).expectedBaseVersionId,
        )!!
        val remoteTargetRename = fixture.context().factory.createContentChild(
            targetBase,
            (targetBase.contentPayload as NotebookContentV2).copy(title = "Remote target branch"),
            "device:$WRITER_B",
            Instant.parse("2026-07-19T03:30:00Z"),
        )
        fixture.applyRemote(remoteTargetRename, "3")
        val localTargetBranch = fixture.notes.renameNotebook(
            target.id,
            "Local target branch",
            checkNotNull(targetView.causalToken),
        )
        assertTrue(localTargetBranch.syncBadge is NoteSyncBadge.Conflict)
        val notebookConflict = assertNotNull(fixture.notes.getNotebookConflictDetails(target.id))
        assertEquals(
            setOf("Local target branch", "Remote target branch"),
            notebookConflict.branches.mapNotNull { it.title }.toSet(),
        )
        assertTrue(fixture.notes.listNotes(RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2).any { it.id == moved.id })
        assertFailsWith<IllegalArgumentException> {
            fixture.notes.renameNotebook(
                target.id,
                "Ambiguous rename",
                checkNotNull(localTargetBranch.causalToken),
            )
        }
        val resolvedTarget = assertNotNull(
            fixture.notes.resolveNotebookConflictBranch(
                notebookConflict.conflictId,
                remoteTargetRename.versionId,
                notebookConflict.expectedHeadVersionIds,
            ),
        )
        assertEquals("Remote target branch", resolvedTarget.title)
        assertNull(fixture.notes.getNotebookConflictDetails(target.id))
        assertEquals(moved.id, fixture.notes.listNotes(target.id).single().id)

        val orderA = fixture.context().factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            ORDER_NOTEBOOK_A,
            NotebookContentV2("Order A", 50, T1),
            "device:$WRITER_B",
            T3,
        )
        val orderB = fixture.context().factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            ORDER_NOTEBOOK_B,
            NotebookContentV2("Order B", 50, T1),
            "device:$WRITER_B",
            T3,
        )
        fixture.applyRemote(orderB, "4")
        fixture.applyRemote(orderA, "5")
        assertEquals(
            listOf(ORDER_NOTEBOOK_A, ORDER_NOTEBOOK_B),
            fixture.notes.listNotebooks().filter { it.sortOrder == 50L }.map { it.id },
        )

        val reorderA = fixture.context().factory.createContentChild(
            orderA,
            (orderA.contentPayload as NotebookContentV2).copy(sortOrder = 80),
            "device:$WRITER_B",
            Instant.parse("2026-07-19T03:40:00Z"),
        )
        val reorderB = fixture.context().factory.createContentChild(
            orderB,
            (orderB.contentPayload as NotebookContentV2).copy(sortOrder = 70),
            "device:$WRITER_B",
            Instant.parse("2026-07-19T03:41:00Z"),
        )
        fixture.applyRemote(reorderB, "6")
        assertEquals(50, fixture.notes.listNotebooks().single { it.id == ORDER_NOTEBOOK_A }.sortOrder)
        assertEquals(70, fixture.notes.listNotebooks().single { it.id == ORDER_NOTEBOOK_B }.sortOrder)
        fixture.applyRemote(reorderA, "7")
        assertEquals(80, fixture.notes.listNotebooks().single { it.id == ORDER_NOTEBOOK_A }.sortOrder)
        assertEquals(70, fixture.notes.listNotebooks().single { it.id == ORDER_NOTEBOOK_B }.sortOrder)

        val stale = fixture.notes.listNotebooks().single { it.id == ORDER_NOTEBOOK_A }
        val staleBase = fixture.context().store.loadVersion(checkNotNull(stale.causalToken).expectedBaseVersionId)!!
        val remoteRename = fixture.context().factory.createContentChild(
            staleBase,
            (staleBase.contentPayload as NotebookContentV2).copy(title = "Remote rename"),
            "device:$WRITER_B",
            Instant.parse("2026-07-19T04:00:00Z"),
        )
        fixture.applyRemote(remoteRename, "8")
        fixture.notes.reorderNotebooks(listOf(NotebookOrderEdit(stale.id, 75, checkNotNull(stale.causalToken))))
        val merged = fixture.notes.listNotebooks().single { it.id == stale.id }
        assertEquals("Remote rename", merged.title)
        assertEquals(75, merged.sortOrder)
        val mergedHead = fixture.context().store.loadHeads(notebookKeyForTest(stale.id)).single()
        assertEquals(2, mergedHead.parentVersionIds.size)
        assertTrue(mergedHead.parentVersionIds.contains(remoteRename.versionId))
    }

    @Test
    fun preferencesRoundTripMergeConflictLocalControlsAndReferenceWarnings() = withFixture { fixture ->
        fixture.createPreferenceRoot()
        val notebook = fixture.notes.createNotebook("Default")
        val initial = fixture.settings.load()
        val allFields = fixture.settings.save(
            initial.copy(
                theme = ClientTheme.Dark,
                editorPreferences = EditorPreferences(previewByDefault = true, markdownToolbarVisible = false),
                defaultNotebookId = notebook.id,
            ),
        )
        assertEquals(ClientTheme.Dark, allFields.theme)
        assertEquals(EditorPreferences(true, false), allFields.editorPreferences)
        assertEquals(notebook.id, allFields.defaultNotebookId)

        val stale = allFields
        val base = fixture.context().store.loadHeads(preferenceKey()).single()
        val remoteTheme = fixture.context().factory.createContentChild(
            base,
            (base.contentPayload as WorkspacePreferencesV2).copy(theme = WorkspaceThemeV2.LIGHT),
            "device:$WRITER_B",
            T2,
        )
        fixture.applyRemote(remoteTheme, "1")
        val merged = fixture.settings.save(
            stale.copy(editorPreferences = stale.editorPreferences.copy(previewByDefault = false)),
        )
        assertEquals(ClientTheme.Light, merged.theme)
        assertEquals(false, merged.editorPreferences.previewByDefault)
        assertEquals(1, fixture.context().store.loadHeads(preferenceKey()).size)

        val conflictBaseView = fixture.settings.load()
        val conflictBase = fixture.context().store.loadHeads(preferenceKey()).single()
        val remoteConflict = fixture.context().factory.createContentChild(
            conflictBase,
            (conflictBase.contentPayload as WorkspacePreferencesV2).copy(theme = WorkspaceThemeV2.SYSTEM),
            "device:$WRITER_B",
            T3,
        )
        fixture.applyRemote(remoteConflict, "2")
        val conflicted = fixture.settings.save(conflictBaseView.copy(theme = ClientTheme.Dark))
        assertEquals(WorkspacePreferencesSyncStatus.Conflict, conflicted.workspacePreferencesState.status)
        val conflict = assertNotNull(conflicted.workspacePreferencesState.conflict)
        assertEquals(2, conflict.branches.size)
        val localOnly = fixture.settings.save(
            conflicted.copy(onThisDayNotifications = OnThisDayNotificationPreferences(true, 8, 30)),
        )
        assertTrue(localOnly.onThisDayNotifications.enabled)
        assertEquals(WorkspacePreferencesSyncStatus.Conflict, localOnly.workspacePreferencesState.status)
        assertFailsWith<IllegalStateException> { fixture.settings.save(localOnly.copy(theme = ClientTheme.Light)) }
        val resolved = fixture.settings.resolveWorkspacePreferencesBranch(
            conflict.conflictId,
            conflict.branches.first().versionId,
            conflict.expectedHeadVersionIds,
        )
        assertTrue(resolved.workspacePreferencesState.status != WorkspacePreferencesSyncStatus.Conflict)

        val resolvedHead = fixture.context().store.loadHeads(preferenceKey()).single()
        val missingReference = fixture.context().factory.createContentChild(
            resolvedHead,
            (resolvedHead.contentPayload as WorkspacePreferencesV2).copy(defaultNotebookId = MISSING_NOTEBOOK_ID),
            "device:$WRITER_B",
            Instant.parse("2026-07-19T05:00:00Z"),
        )
        fixture.applyRemote(missingReference, "3")
        val warning = fixture.settings.load()
        assertNull(warning.defaultNotebookId)
        assertEquals(WorkspacePreferencesSyncStatus.Warning, warning.workspacePreferencesState.status)
        assertNotNull(warning.workspacePreferencesState.warning)
        val laterNotebook = fixture.context().factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            MISSING_NOTEBOOK_ID,
            NotebookContentV2("Missing default arrived", 90, T1),
            "device:$WRITER_B",
            Instant.parse("2026-07-19T06:00:00Z"),
        )
        fixture.applyRemote(laterNotebook, "4")
        assertEquals(MISSING_NOTEBOOK_ID, fixture.settings.load().defaultNotebookId)
        assertFailsWith<IllegalArgumentException> { fixture.context().factory.createDeletion(resolvedHead, T3, "device:$WRITER_B", T3) }
        assertFailsWith<IllegalArgumentException> {
            fixture.context().factory.createGenesis(
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                "another-preferences",
                WorkspacePreferencesV2(),
                "device:$WRITER_B",
                T3,
            )
        }
    }

    @Test
    fun preferencesSyncOnlyFourFieldsAndRemoteProjectionCreatesNoEcho() = withFixture { fixture ->
        fixture.createPreferenceRoot()
        val settings = fixture.settings
        val loaded = settings.load()
        assertEquals(WorkspacePreferencesSyncStatus.Pending, loaded.workspacePreferencesState.status)
        val before = fixture.context().store.loadPending(PROFILE).size

        val localOnly = settings.save(
            loaded.copy(onThisDayNotifications = OnThisDayNotificationPreferences(true, 9, 15)),
        )
        assertEquals(before, fixture.context().store.loadPending(PROFILE).size)
        assertEquals(true, localOnly.onThisDayNotifications.enabled)

        val changed = settings.save(localOnly.copy(theme = ClientTheme.Dark))
        assertEquals(ClientTheme.Dark, changed.theme)
        assertTrue(fixture.context().store.loadPending(PROFILE).size > before)
        val head = fixture.context().store.loadHeads(preferenceKey()).single()
        assertEquals(WorkspaceThemeV2.DARK, (head.contentPayload as WorkspacePreferencesV2).theme)

        val remote = fixture.context().factory.createContentChild(
            parent = head,
            content = (head.contentPayload as WorkspacePreferencesV2).copy(markdownToolbarVisible = false),
            deviceActorId = "device:$WRITER_B",
            authoredAt = T2,
        )
        fixture.applyRemote(remote, "2")
        val pendingAfterRemote = fixture.context().store.loadPending(PROFILE).size
        val projected = settings.load()
        assertEquals(false, projected.editorPreferences.markdownToolbarVisible)
        assertEquals(pendingAfterRemote, fixture.context().store.loadPending(PROFILE).size)
    }

    @Test
    fun workspacePreferencesResetIsAnOrdinaryContentVersion() = withFixture { fixture ->
        fixture.createPreferenceRoot()
        val notebook = fixture.notes.createNotebook("Reset default")
        val changed = fixture.settings.save(
            fixture.settings.load().copy(
                theme = ClientTheme.Dark,
                editorPreferences = EditorPreferences(true, false),
                defaultNotebookId = notebook.id,
            ),
        )
        val changedHead = fixture.context().store.loadHeads(preferenceKey()).single()

        val reset = fixture.settings.save(
            changed.copy(
                theme = ClientTheme.System,
                editorPreferences = EditorPreferences(false, true),
                defaultNotebookId = null,
            ),
        )
        val resetHead = fixture.context().store.loadHeads(preferenceKey()).single()

        assertEquals(ClientTheme.System, reset.theme)
        assertEquals(EditorPreferences(false, true), reset.editorPreferences)
        assertNull(reset.defaultNotebookId)
        assertEquals(WorkspaceEntityVersionKindV2.CONTENT, resetHead.kind)
        assertEquals(listOf(changedHead.versionId), resetHead.parentVersionIds)
        assertEquals(WorkspacePreferencesV2(), resetHead.contentPayload)
    }

    @Test
    fun networkOffStillWritesToTheDurableAuthoritativeOutbox() = withFixture { fixture ->
        val offlineNotes = SystemV2NotesRepository(
            fixture.local,
            { fixture.workspaceKey },
            { error("A network-mode writer must not be consulted while Off.") },
            { "" },
            clock = { T2 },
        )

        val notebook = offlineNotes.createNotebook("Offline")

        assertTrue(offlineNotes.listNotebooks().any { it.id == notebook.id })
        assertEquals(WRITER_A, fixture.context().writerDeviceId)
        assertTrue(fixture.context().store.loadPending(PROFILE).isNotEmpty())
    }

    @Test
    fun exportAndRestoreUseTheAuthoritativeV2Dag() = withFixture { fixture ->
        fixture.createPreferenceRoot()
        val notebook = fixture.notes.createNotebook("V2 export")
        val note = fixture.notes.createNote(
            NoteInput(
                notebook.id,
                "Authoritative V2 title",
                "Authoritative V2 body",
                location = NotesLocationInput(31.2, 121.4, "V2 place", capturedAt = T1),
            ),
        )
        val transfer = WorkspaceLocalDataTransferV2(
            fixture.local,
            fixture.rawSettings,
            { fixture.workspaceKey },
            { WRITER_A },
            { PROFILE },
        )
        val exporter = LocalDataExporter(
            authoritativeDocumentProvider = transfer::exportDocument,
            clock = { T2 },
        )

        val exported = exporter.exportDocument()

        assertEquals(listOf("V2 export"), exported.notebooks.map { it.title })
        assertEquals("V2 place", exported.notes.single().location?.placeText)
        assertEquals(note.causalToken?.expectedBaseVersionId, exported.notes.single().currentVersionId)
        val restored = exported.copy(
            notes = listOf(exported.notes.single().copy(title = "Restored branch")),
        )
        val summary = LocalDataImporter(
            authoritativeImporter = transfer::importDocument,
        ).importDocument(restored)

        assertEquals(1, summary.noteConflictsCreated)
        val key = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, note.id)
        assertEquals(2, fixture.context().store.loadHeads(key).size)
        assertEquals(1, fixture.context().store.loadConflicts(key).count {
            it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE
        })
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val database = SomedayDatabase(driver)
            val crypto = SodiumWorkspaceCrypto()
            val key = crypto.workspaceKeyFromBytes(ByteArray(32) { (it + 17).toByte() })
            val local = SqlDelightLocalDataRepository(database, "test-$WRITER_A", clock = { T1 })
            val rawSettings = SqlDelightClientSettingsRepository(local)
            rawSettings.saveLocalSnapshot(
                ClientSettings(
                    activeDeviceId = WRITER_A,
                    syncConfiguration = SyncConfiguration(mode = SyncMode.SelfHosted),
                ),
            )
            val protocol = SqlDelightSyncProtocolStoreV2(database)
            val descriptor = SyncEpochDescriptorV2(
                syncEpochId = EPOCH,
                remoteProfile = PROFILE,
                checkpointId = CHECKPOINT,
                checkpointDigest = "cd2:hmac-sha256:${"12".repeat(32)}",
                createdByDeviceId = WRITER_A,
                createdAt = T1,
            )
            protocol.persistPreparingEpoch(PROFILE, descriptor, "descriptor-digest")
            protocol.activateEpoch(PROFILE, EPOCH, T1)
            val contextProvider = WorkspaceSystemV2ContextProvider(local, { key }, { WRITER_A }, { PROFILE })
            val notes = SystemV2NotesRepository(local, { key }, { WRITER_A }, { PROFILE }, clock = { T1 })
            val settings = SystemV2ClientSettingsRepository(
                local,
                rawSettings,
                { key },
                { WRITER_A },
                { PROFILE },
                clock = { T1 },
            )
            block(Fixture(database, EPOCH, local, key, rawSettings, contextProvider, notes, settings))
        } finally {
            driver.close()
        }
    }

    private data class Fixture(
        val database: SomedayDatabase,
        val epochId: String,
        val local: SqlDelightLocalDataRepository,
        val workspaceKey: saien.someday.data.crypto.WorkspaceMasterKey,
        val rawSettings: SqlDelightClientSettingsRepository,
        val contextProvider: WorkspaceSystemV2ContextProvider,
        val notes: SystemV2NotesRepository,
        val settings: SystemV2ClientSettingsRepository,
    ) {
        private var remoteCursor: String? = null
        private var remoteMutationOrdinal: Long = 100

        fun context() = contextProvider.requireActive()

        fun commit(version: WorkspaceEntityVersionV2, at: Instant) {
            val context = context()
            val result = context.store.commitLocalMutations(listOf(
                LocalWorkspaceMutationV2(PROFILE, context.factory.newMutationId(), version, at),
            ))
            assertTrue(result is WorkspaceLocalCommitResultV2.Committed)
        }

        fun applyRemote(version: WorkspaceEntityVersionV2, cursor: String) {
            val mutationId = "00000000-0000-4000-8000-${(remoteMutationOrdinal++).toString().padStart(12, '0')}"
            val result = context().store.applyRemoteCursorUnit(
                RemoteWorkspaceCursorUnitV2(
                    PROFILE,
                    WorkspaceRemoteCursorAdvanceV2("global", remoteCursor, cursor, "unit-$cursor", "digest-$cursor"),
                    listOf(RemoteWorkspaceMutationV2(
                        mutationId,
                        version.versionId,
                        version.objectDigest,
                        WRITER_B,
                        version,
                    )),
                    T2,
                ),
            )
            assertTrue(result is WorkspaceRemoteUnitApplyResultV2.Applied)
            remoteCursor = cursor
        }

        fun isAncestor(ancestorVersionId: String, descendantVersionId: String): Boolean {
            val seen = mutableSetOf<String>()
            val queue = ArrayDeque<String>().apply { add(descendantVersionId) }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!seen.add(current)) continue
                if (current == ancestorVersionId) return true
                context().store.loadVersion(current)?.parentVersionIds?.forEach(queue::addLast)
            }
            return false
        }

        fun createPreferenceRoot() {
            val context = context()
            val root = context.factory.createGenesis(
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                WorkspacePreferencesV2(),
                context.deviceActorId,
                T1,
            )
            commit(root, T1)
        }
    }

    private fun preferenceKey() = WorkspaceEntityKeyV2(
        WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
        WORKSPACE_PREFERENCES_ENTITY_ID_V2,
    )

    private fun noteKeyForTest(noteId: String) = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, noteId)

    private fun notebookKeyForTest(notebookId: String) =
        WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTEBOOK, notebookId)

    private companion object {
        const val PROFILE = "self-hosted-v2"
        const val WRITER_A = "00000000-0000-4000-8000-000000000001"
        const val WRITER_B = "00000000-0000-4000-8000-000000000002"
        const val EPOCH = "00000000-0000-4000-8000-000000000010"
        const val CHECKPOINT = "00000000-0000-4000-8000-000000000020"
        const val MISSING_NOTEBOOK_ID = "00000000-0000-4000-8000-000000000030"
        const val DANGLING_NOTE_ID = "00000000-0000-4000-8000-000000000031"
        const val ORDER_NOTEBOOK_A = "00000000-0000-4000-8000-000000000032"
        const val ORDER_NOTEBOOK_B = "00000000-0000-4000-8000-000000000033"
        const val ARCHIVED_NOTE_ID = "00000000-0000-4000-8000-000000000034"
        val T1 = Instant.parse("2026-07-19T01:00:00Z")
        val T2 = Instant.parse("2026-07-19T02:00:00Z")
        val T3 = Instant.parse("2026-07-19T03:00:00Z")
    }
}
