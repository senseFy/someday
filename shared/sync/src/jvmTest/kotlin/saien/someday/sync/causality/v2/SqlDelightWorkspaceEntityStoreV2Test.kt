@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SqlDelightWorkspaceEntityStoreV2Test {
    @Test
    fun localMutationRollsBackAtEveryDurableEffectBoundary() = withFixture { fixture ->
        val note = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(NOTEBOOK_ID, "Atomic", "all or none", at(1), null, null),
            ACTOR,
            at(2),
        )
        val mutation = LocalWorkspaceMutationV2(PROFILE, fixture.ids.newId(), note, at(3))
        listOf(
            "workspace_entity_versions_v2",
            "workspace_entity_heads_v2",
            "sync_pending_mutations_system_v2",
            "note_projections_system_v2",
        ).forEach { table ->
            fixture.failNextInsert(table)
            assertFailsWith<Exception>(table) {
                fixture.store.commitLocalMutations(listOf(mutation))
            }
            fixture.clearFault()
            assertTrue(fixture.store.loadAllVersions().isEmpty(), table)
            assertTrue(fixture.store.loadPending(PROFILE).isEmpty(), table)
            assertNull(fixture.store.loadProjection(note.key), table)
        }

        assertIs<WorkspaceLocalCommitResultV2.Committed>(
            fixture.store.commitLocalMutations(listOf(mutation)),
        )
        assertEquals(note.versionId, fixture.store.loadHeads(note.key).single().versionId)
        assertEquals(mutation.mutationId, fixture.store.loadPending(PROFILE).single().mutationId)
    }

    @Test
    fun remoteApplyRollsBackVersionsReplayProjectionAndCursorAtEveryBoundary() = withFixture { fixture ->
        val note = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(NOTEBOOK_ID, "Remote", "atomic unit", at(1), null, null),
            ACTOR,
            at(2),
        )
        val remoteMutation = fixture.remote(note)
        val unit = fixture.unit(listOf(remoteMutation))
        listOf(
            "workspace_entity_versions_v2",
            "workspace_entity_heads_v2",
            "sync_applied_mutations_system_v2",
            "note_projections_system_v2",
            "sync_remote_cursors_system_v2",
        ).forEach { table ->
            fixture.failNextInsert(table)
            assertFailsWith<Exception>(table) { fixture.store.applyRemoteCursorUnit(unit) }
            fixture.clearFault()
            assertTrue(fixture.store.loadAllVersions().isEmpty(), table)
            assertNull(fixture.store.findApplied(PROFILE, remoteMutation.mutationId), table)
            assertNull(fixture.store.loadProjection(note.key), table)
            assertNull(fixture.store.loadCursor(PROFILE, STREAM), table)
        }

        assertIs<WorkspaceRemoteUnitApplyResultV2.Applied>(fixture.store.applyRemoteCursorUnit(unit))
        assertIs<WorkspaceRemoteUnitApplyResultV2.AlreadyApplied>(fixture.store.applyRemoteCursorUnit(unit))
        assertEquals("1", fixture.store.loadCursor(PROFILE, STREAM)?.cursorValue)
    }

    @Test
    fun immutableAndMutationIdentityReplayExactlyAndMismatchBlocks() = withFixture { fixture ->
        val root = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("Identity", 0, at(1)),
            ACTOR,
            at(1),
        )
        val mutation = fixture.remote(root)
        val first = fixture.unit(listOf(mutation))
        assertIs<WorkspaceRemoteUnitApplyResultV2.Applied>(fixture.store.applyRemoteCursorUnit(first))

        val rotatedWriterReplay = mutation.copy(
            writerDeviceId = OTHER_WRITER,
            version = mutation.version.copy(authorActorId = mutation.version.authorActorId),
        )
        val replay = RemoteWorkspaceCursorUnitV2(
            PROFILE,
            WorkspaceRemoteCursorAdvanceV2(STREAM, "1", "2", "unit-2", "unit-digest-2"),
            listOf(rotatedWriterReplay),
            at(21),
        )
        assertEquals(
            1,
            assertIs<WorkspaceRemoteUnitApplyResultV2.Applied>(fixture.store.applyRemoteCursorUnit(replay)).replayedMutations,
        )
        assertEquals(WRITER, fixture.store.findApplied(PROFILE, mutation.mutationId)?.firstWriterDeviceId)

        val other = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            "other-notebook",
            NotebookContentV2("Other", 1, at(1)),
            ACTOR,
            at(2),
        )
        val reusedMutation = RemoteWorkspaceCursorUnitV2(
            PROFILE,
            WorkspaceRemoteCursorAdvanceV2(STREAM, "2", "3", "unit-3", "unit-digest-3"),
            listOf(mutation.copy(objectId = other.versionId, objectDigest = other.objectDigest, version = other)),
            at(22),
        )
        val rejected = assertIs<WorkspaceRemoteUnitApplyResultV2.Rejected>(
            fixture.store.applyRemoteCursorUnit(reusedMutation),
        )
        assertEquals(WorkspaceStoreErrorCodeV2.MUTATION_OBJECT_MISMATCH, rejected.error.code)
        assertEquals("2", fixture.store.loadCursor(PROFILE, STREAM)?.cursorValue)
        assertNull(fixture.store.loadVersion(other.versionId))

        val sameIdDifferentDigest = root.copy(objectDigest = "od2:hmac-sha256:${"0".repeat(64)}")
        val mismatch = RemoteWorkspaceCursorUnitV2(
            PROFILE,
            WorkspaceRemoteCursorAdvanceV2(STREAM, "2", "4", "unit-4", "unit-digest-4"),
            listOf(fixture.remote(sameIdDifferentDigest)),
            at(23),
        )
        assertIs<WorkspaceRemoteUnitApplyResultV2.Rejected>(fixture.store.applyRemoteCursorUnit(mismatch))
        assertEquals("2", fixture.store.loadCursor(PROFILE, STREAM)?.cursorValue)
    }

    @Test
    fun commitsWholeProductRootsOutboxAndTypedProjectionsAtomically() = withFixture { fixture ->
        val notebook = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("Notebook", 4, at(1)),
            ACTOR,
            at(2),
        )
        val location = NoteLocationV2(31.23, 121.47, "上海", 2.5, 9.0, at(3))
        val note = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(NOTEBOOK_ID, "Title", "Body", at(1), "Asia/Shanghai", location),
            ACTOR,
            at(4),
        )
        val preferences = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            WorkspacePreferencesV2(WorkspaceThemeV2.DARK, true, false, NOTEBOOK_ID),
            ACTOR,
            at(5),
        )
        val committed = assertIs<WorkspaceLocalCommitResultV2.Committed>(
            fixture.store.commitLocalMutations(
                listOf(notebook, note, preferences).map { version ->
                    LocalWorkspaceMutationV2(PROFILE, fixture.ids.newId(), version, at(10))
                },
            ),
        )

        assertEquals(3, committed.plans.size)
        assertEquals(3, committed.pending.size)
        assertEquals(setOf(notebook, note, preferences), fixture.store.loadAllVersions().toSet())
        val noteProjection = fixture.store.loadProjection(note.key)
        assertEquals(WorkspaceProjectionStatusV2.CONTENT, noteProjection?.status)
        assertEquals(NOTEBOOK_ID, noteProjection?.referencedEntityId)
        assertEquals(NOTEBOOK_ID, noteProjection?.effectiveEntityId)
        assertEquals(location, (noteProjection?.content as NoteContentV2).location)
        val storedProjection = fixture.database.somedayQueries
            .selectNoteProjectionSystemV2(EPOCH, NOTE_ID).executeAsOne()
        assertEquals(location.latitude, storedProjection.location_latitude)
        assertEquals(location.capturedAt.epochSeconds, storedProjection.location_captured_at_seconds)
        assertEquals(NOTEBOOK_ID, fixture.store.loadProjection(preferences.key)?.effectiveEntityId)

        val replay = assertIs<WorkspaceLocalCommitResultV2.AlreadyCommitted>(
            fixture.store.commitLocalMutations(
                committed.pending.map { pending ->
                    LocalWorkspaceMutationV2(
                        PROFILE,
                        pending.mutationId,
                        fixture.store.loadVersion(pending.objectId)!!,
                        at(10),
                    )
                },
            ),
        )
        assertEquals(3, replay.pending.size)
        assertEquals(3, fixture.store.loadPending(PROFILE).size)
    }

    @Test
    fun danglingNotebookReferenceNeverBlocksAndReprojectsWhenNotebookArrives() = withFixture { fixture ->
        val note = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(NOTEBOOK_ID, "Title", "Body", at(1), null, null),
            ACTOR,
            at(2),
        )
        assertIs<WorkspaceLocalCommitResultV2.Committed>(
            fixture.store.commitLocalMutations(listOf(LocalWorkspaceMutationV2(PROFILE, fixture.ids.newId(), note, at(2)))),
        )
        val dangling = fixture.store.loadProjection(note.key)
        assertEquals(NOTEBOOK_ID, dangling?.referencedEntityId)
        assertEquals(RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2, dangling?.effectiveEntityId)
        assertEquals("unresolved_notebook_reference", dangling?.warning)

        val notebook = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("Later", 0, at(1)),
            ACTOR,
            at(3),
        )
        assertIs<WorkspaceLocalCommitResultV2.Committed>(
            fixture.store.commitLocalMutations(listOf(LocalWorkspaceMutationV2(PROFILE, fixture.ids.newId(), notebook, at(3)))),
        )
        val recovered = fixture.store.loadProjection(note.key)
        assertEquals(NOTEBOOK_ID, recovered?.effectiveEntityId)
        assertNull(recovered?.warning)
    }

    @Test
    fun localNoteCommitDoesNotRebuildUnrelatedNoteProjections() = withFixture { fixture ->
        val notebook = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("Notebook", 1, at(1)),
            ACTOR,
            at(2),
        )
        val firstNote = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(NOTEBOOK_ID, "First", "First body", at(1), null, null),
            ACTOR,
            at(3),
        )
        fixture.commit(notebook)
        fixture.commit(firstNote)
        fixture.database.somedayQueries.deleteNoteProjectionsSystemV2(EPOCH)
        assertNull(fixture.database.somedayQueries.selectNoteProjectionSystemV2(EPOCH, NOTE_ID).executeAsOneOrNull())

        val secondNote = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            "30000000-0000-4000-8000-000000000002",
            NoteContentV2(NOTEBOOK_ID, "Second", "Second body", at(2), null, null),
            ACTOR,
            at(4),
        )
        fixture.commit(secondNote)

        assertNull(fixture.database.somedayQueries.selectNoteProjectionSystemV2(EPOCH, NOTE_ID).executeAsOneOrNull())
        val secondProjection = fixture.database.somedayQueries
            .selectNoteProjectionSystemV2(EPOCH, secondNote.entityId)
            .executeAsOne()
        assertEquals("Second", secondProjection.title)
        assertEquals("Second body", secondProjection.markdown_body)
    }

    @Test
    fun remoteUnitRequiresTopologicalParentsAndAdvancesCursorWithAllEffects() = withFixture { fixture ->
        val root = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("Root", 0, at(1)),
            ACTOR,
            at(1),
        )
        val child = fixture.factory.createContentChild(
            root,
            NotebookContentV2("Child", 0, at(1)),
            ACTOR,
            at(2),
        )
        val bad = fixture.unit(listOf(fixture.remote(child), fixture.remote(root)))
        val rejected = assertIs<WorkspaceRemoteUnitApplyResultV2.Rejected>(fixture.store.applyRemoteCursorUnit(bad))
        assertEquals(WorkspaceStoreErrorCodeV2.NON_TOPOLOGICAL_UNIT, rejected.error.code)
        assertTrue(fixture.store.loadAllVersions().isEmpty())
        assertNull(fixture.store.loadCursor(PROFILE, STREAM))

        val good = fixture.unit(listOf(fixture.remote(root), fixture.remote(child)))
        val applied = assertIs<WorkspaceRemoteUnitApplyResultV2.Applied>(fixture.store.applyRemoteCursorUnit(good))
        assertEquals("1", applied.cursor.cursorValue)
        assertEquals(child.versionId, fixture.store.loadHeads(root.key).single().versionId)
        assertEquals(2, fixture.store.loadAllVersions().size)

        assertIs<WorkspaceRemoteUnitApplyResultV2.AlreadyApplied>(fixture.store.applyRemoteCursorUnit(good))
    }

    @Test
    fun missingParentBlocksAtomicallyAndRetrySucceedsAfterParentArrival() = withFixture { fixture ->
        val root = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("Root", 0, at(1)),
            ACTOR,
            at(1),
        )
        val child = fixture.factory.createContentChild(
            root,
            NotebookContentV2("Child", 0, at(1)),
            ACTOR,
            at(2),
        )
        val childFirst = RemoteWorkspaceCursorUnitV2(
            PROFILE,
            WorkspaceRemoteCursorAdvanceV2(STREAM, null, "1", "missing-parent", "missing-parent-digest"),
            listOf(fixture.remote(child)),
            at(2),
        )

        val blocked = assertIs<WorkspaceRemoteUnitApplyResultV2.Rejected>(
            fixture.store.applyRemoteCursorUnit(childFirst),
        )
        assertEquals(WorkspaceStoreErrorCodeV2.MISSING_PARENT, blocked.error.code)
        assertTrue(fixture.store.loadAllVersions().isEmpty())
        assertNull(fixture.store.loadCursor(PROFILE, STREAM))

        val parentUnit = RemoteWorkspaceCursorUnitV2(
            PROFILE,
            WorkspaceRemoteCursorAdvanceV2(STREAM, null, "1", "parent", "parent-digest"),
            listOf(fixture.remote(root)),
            at(3),
        )
        assertIs<WorkspaceRemoteUnitApplyResultV2.Applied>(fixture.store.applyRemoteCursorUnit(parentUnit))
        val retriedChild = childFirst.copy(
            cursor = WorkspaceRemoteCursorAdvanceV2(STREAM, "1", "2", "missing-parent", "missing-parent-digest"),
        )
        assertIs<WorkspaceRemoteUnitApplyResultV2.Applied>(fixture.store.applyRemoteCursorUnit(retriedChild))
        assertEquals(child.versionId, fixture.store.loadHeads(root.key).single().versionId)
        assertEquals("2", fixture.store.loadCursor(PROFILE, STREAM)?.cursorValue)
    }

    @Test
    fun generatedJoinAndConflictLifecycleAreDurableAndGeneric() = withFixture { fixture ->
        val root = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("Base", 0, at(1)),
            ACTOR,
            at(1),
        )
        val rename = fixture.factory.createContentChild(root, NotebookContentV2("Renamed", 0, at(1)), ACTOR, at(2))
        val reorder = fixture.factory.createContentChild(root, NotebookContentV2("Base", 5, at(1)), ACTOR, at(3))
        fixture.applyRemote(root, "1")
        fixture.applyRemote(rename, "2", expected = "1")
        val merged = fixture.applyRemote(reorder, "3", expected = "2")
        assertEquals(1, merged.plans[root.key]?.generatedVersions?.size)
        assertEquals(1, fixture.store.loadPending(PROFILE).size)
        val joined = fixture.store.loadHeads(root.key).single()
        assertEquals(NotebookContentV2("Renamed", 5, at(1)), joined.contentPayload)

        val otherRename = fixture.factory.createContentChild(root, NotebookContentV2("Other", 0, at(1)), ACTOR, at(4))
        val conflictApply = fixture.applyRemote(otherRename, "4", expected = "3")
        assertIs<WorkspaceReconciliationOutcomeV2.Conflict>(conflictApply.plans[root.key]?.outcome)
        assertEquals(1, fixture.store.loadActiveConflicts().size)
        assertEquals(WorkspaceProjectionStatusV2.CONFLICT, fixture.store.loadProjection(root.key)?.status)

        val active = fixture.store.loadActiveConflicts().single().descriptor
        val resolution = fixture.factory.createManualResolution(
            active.headVersionIds.map { fixture.store.loadVersion(it)!! },
            NotebookContentV2("Resolved", 5, at(1)),
            null,
            ACTOR,
            at(5),
        )
        fixture.applyRemote(resolution, "5", expected = "4")
        assertTrue(fixture.store.loadActiveConflicts().isEmpty())
        assertEquals(
            WorkspaceConflictLifecycleV2.RESOLVED,
            fixture.store.loadConflicts(root.key).last().lifecycle,
        )
        assertEquals("Resolved", (fixture.store.loadProjection(root.key)?.content as NotebookContentV2).title)
    }

    @Test
    fun everyTypedProjectionIncludingLocationWarningAndConflictCanBeRebuiltFromDag() = withFixture { fixture ->
        val notebook = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("Notebook", 1, at(1)),
            ACTOR,
            at(2),
        )
        val note = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(
                NOTEBOOK_ID,
                "Projected note",
                "Projected body",
                at(1),
                "Asia/Shanghai",
                NoteLocationV2(31.2, 121.4, "Projection place", 3.0, 8.0, at(1)),
            ),
            ACTOR,
            at(3),
        )
        val preferences = fixture.factory.createGenesis(
            WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            WorkspacePreferencesV2(defaultNotebookId = NOTEBOOK_ID),
            ACTOR,
            at(4),
        )
        fixture.commit(notebook)
        fixture.commit(note)
        fixture.commit(preferences)
        val preferenceLight = fixture.factory.createContentChild(
            preferences,
            (preferences.contentPayload as WorkspacePreferencesV2).copy(theme = WorkspaceThemeV2.LIGHT),
            ACTOR,
            at(5),
        )
        val preferenceDark = fixture.factory.createContentChild(
            preferences,
            (preferences.contentPayload as WorkspacePreferencesV2).copy(theme = WorkspaceThemeV2.DARK),
            "device:00000000-0000-4000-8000-000000000002",
            at(6),
        )
        fixture.commit(preferenceLight)
        fixture.commit(preferenceDark)
        val before = listOf(notebook.key, note.key, preferences.key).associateWith(fixture.store::loadProjection)
        fixture.database.somedayQueries.deleteNotebookProjectionsV2(EPOCH)
        fixture.database.somedayQueries.deleteNoteProjectionsSystemV2(EPOCH)
        fixture.database.somedayQueries.deleteWorkspacePreferencesProjectionV2(EPOCH)
        fixture.database.somedayQueries.deleteEpochProjectionWarningsSystemV2(EPOCH)
        assertTrue(fixture.database.somedayQueries.selectAllNotebookProjectionsV2(EPOCH).executeAsList().isEmpty())
        assertTrue(fixture.database.somedayQueries.selectAllNoteProjectionsSystemV2(EPOCH).executeAsList().isEmpty())
        assertNull(fixture.database.somedayQueries.selectWorkspacePreferencesProjectionV2(EPOCH).executeAsOneOrNull())
        fixture.store.rebuildProjections(at(9))
        assertEquals(before, before.keys.associateWith(fixture.store::loadProjection))
        assertEquals(
            "Projection place",
            (fixture.store.loadProjection(note.key)?.content as NoteContentV2).location?.placeText,
        )
        assertEquals(WorkspaceProjectionStatusV2.CONFLICT, fixture.store.loadProjection(preferences.key)?.status)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        val database = SomedayDatabase(driver)
        val ids = StoreIdsV2()
        val materializer = CanonicalWorkspaceCausalityMaterializerV2(
            SyncEpochKeysV2(
                ByteArray(32) { (it + 1).toByte() },
                ByteArray(32) { (it + 80).toByte() },
            ),
        )
        val validator = WorkspaceEntityValidatorV2(materializer)
        val fixture = Fixture(
            driver,
            database,
            ids,
            WorkspaceEntityVersionFactoryV2(EPOCH, materializer, ids),
            SqlDelightWorkspaceEntityStoreV2(
                database,
                EPOCH,
                WorkspaceEntityCausalityEngineV2(materializer, validator),
                materializer,
                WorkspaceEntityWireCodecV2(materializer, validator),
                WorkspaceOutboxEncoderV2 { version, mutationId ->
                    PreparedWorkspaceOutboxObjectV2(WRITER, "outer:${version.versionId}:$mutationId:${version.objectDigest}")
                },
            ),
        )
        try {
            block(fixture)
        } finally {
            driver.close()
        }
    }

    private data class Fixture(
        val driver: app.cash.sqldelight.db.SqlDriver,
        val database: SomedayDatabase,
        val ids: StoreIdsV2,
        val factory: WorkspaceEntityVersionFactoryV2,
        val store: SqlDelightWorkspaceEntityStoreV2,
    ) {
        private var mutationCounter = 500L

        fun failNextInsert(table: String) {
            require(table.matches(Regex("[a-z0-9_]+")))
            driver.execute(
                null,
                "CREATE TEMP TRIGGER sync_v2_fault BEFORE INSERT ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'sync-v2 injected transaction fault'); END",
                0,
            ).value
        }

        fun clearFault() {
            driver.execute(null, "DROP TRIGGER IF EXISTS sync_v2_fault", 0).value
        }

        fun commit(version: WorkspaceEntityVersionV2) = assertIs<WorkspaceLocalCommitResultV2.Committed>(
            store.commitLocalMutations(listOf(LocalWorkspaceMutationV2(PROFILE, ids.newId(), version, at(8)))),
        )

        fun remote(version: WorkspaceEntityVersionV2): RemoteWorkspaceMutationV2 = RemoteWorkspaceMutationV2(
            mutationId = "70000000-0000-4000-8000-${(mutationCounter++).toString().padStart(12, '0')}",
            objectId = version.versionId,
            objectDigest = version.objectDigest,
            writerDeviceId = WRITER,
            version = version,
        )

        fun unit(mutations: List<RemoteWorkspaceMutationV2>) = RemoteWorkspaceCursorUnitV2(
            PROFILE,
            WorkspaceRemoteCursorAdvanceV2(STREAM, null, "1", "unit-1", "unit-digest-1"),
            mutations,
            at(20),
        )

        fun applyRemote(
            version: WorkspaceEntityVersionV2,
            next: String,
            expected: String? = null,
        ): WorkspaceRemoteUnitApplyResultV2.Applied = assertIs(
            store.applyRemoteCursorUnit(
                RemoteWorkspaceCursorUnitV2(
                    PROFILE,
                    WorkspaceRemoteCursorAdvanceV2(STREAM, expected, next, "unit-$next", "digest-$next"),
                    listOf(remote(version)),
                    at(20 + next.toLong()),
                ),
            ),
        )
    }

    companion object {
        const val EPOCH = "10000000-0000-4000-8000-000000000001"
        const val NOTEBOOK_ID = "20000000-0000-4000-8000-000000000001"
        const val NOTE_ID = "30000000-0000-4000-8000-000000000001"
        const val ACTOR = "device:40000000-0000-4000-8000-000000000001"
        const val WRITER = "50000000-0000-4000-8000-000000000001"
        const val OTHER_WRITER = "50000000-0000-4000-8000-000000000002"
        const val PROFILE = "webdav-log-v2:test"
        const val STREAM = "writer-stream"

        fun at(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
    }
}

private class StoreIdsV2 : CausalityIdGeneratorV2 {
    private var next = 1_000L
    override fun newId(): String = "60000000-0000-4000-8000-${(next++).toString().padStart(12, '0')}"
}
