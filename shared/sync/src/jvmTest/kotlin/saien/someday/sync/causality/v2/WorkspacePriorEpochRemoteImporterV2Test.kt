@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkspacePriorEpochRemoteImporterV2Test {
    @Test
    fun lateOldEpochWriteIsImportedBeforeCurrentEpochPushAndCursorAdvancesAtomically() {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val local = SqlDelightLocalDataRepository(SomedayDatabase(driver), "prior-epoch-test", clock = { T0 })
            val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 43).toByte() })
            val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
            val ids = PriorEpochIdsV2()

            val first = WorkspaceCheckpointBuilderV2(key, WRITER_A, ids).build(
                remoteProfile = remote.remoteProfile,
                sourceHeads = initialSources(),
                createdAt = T0,
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(local, key, WRITER_A).persist(first),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(local, remote).publish(first),
            )
            val firstContext = context(local, key)
            val firstNoteHead = firstContext.store.loadHeads(noteKey()).single()

            val rolloverSources = firstContext.store.loadEntityKeys()
                .flatMap(firstContext.store::loadHeads)
                .map { head ->
                    WorkspaceCheckpointSourceHeadV2(
                        head.entityType,
                        head.entityId,
                        head.contentPayload,
                        head.deletionPayload,
                        remote.remoteProfile,
                        first.descriptor.syncEpochId,
                        null,
                        null,
                        head.versionId,
                        head.objectDigest,
                    )
                }
                .sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)
            val second = WorkspaceCheckpointBuilderV2(key, WRITER_A, ids).build(
                remoteProfile = remote.remoteProfile,
                sourceHeads = rolloverSources,
                createdAt = T1,
                previousPointerDigest = first.pointerObject.objectDigest,
                previousEpochId = first.descriptor.syncEpochId,
                previousEpochFrontiers = remote.epochFrontiers(first.descriptor.syncEpochId),
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(local, key, WRITER_A).persist(second),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(local, remote).publish(second),
            )

            val lateVersion = firstContext.factory.createContentChild(
                firstNoteHead,
                (firstNoteHead.contentPayload as NoteContentV2).copy(markdownBody = "late old writer"),
                "device:$WRITER_B",
                T2,
            )
            val lateOuter = firstContext.cipher.encryptEntity(
                lateVersion,
                LATE_MUTATION,
                WRITER_B,
                firstContext.wireCodec.encode(lateVersion),
            )
            remote.injectRetainedEpochObjectForTest(lateOuter)

            val imported = WorkspacePriorEpochRemoteImporterV2(
                local,
                key,
                WRITER_A,
                remote,
                clock = { T2 },
            ).importUntilStable(first.descriptor.syncEpochId)

            assertEquals(1, assertIs<WorkspacePriorEpochRemoteImportResultV2.Imported>(imported).importedVersions)
            val current = context(local, key)
            val mapped = current.store.loadVersions(noteKey()).single { version ->
                version.provenance?.type == WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT &&
                    version.provenance.sourceEpoch == first.descriptor.syncEpochId &&
                    version.provenance.sourceObjectId == lateVersion.versionId
            }
            assertEquals("late old writer", (mapped.contentPayload as NoteContentV2).markdownBody)
            val checkpointParent = assertNotNull(current.store.loadVersion(mapped.parentVersionIds.single()))
            assertEquals(firstNoteHead.versionId, checkpointParent.provenance?.sourceObjectId)
            assertTrue(current.store.loadPending(remote.remoteProfile).any { it.objectId == mapped.versionId })

            // A deletion authored long ago but first delivered after rollover
            // enters the current epoch as a new immutable import. Its old
            // deletedAt/authoredAt cannot shorten the current epoch's future
            // retention window or resurrect the checkpoint content.
            val lateDeletion = firstContext.factory.createDeletion(
                lateVersion,
                T0,
                "device:$WRITER_B",
                T3,
            )
            remote.injectRetainedEpochObjectForTest(
                firstContext.cipher.encryptEntity(
                    lateDeletion,
                    LATE_DELETE_MUTATION,
                    WRITER_B,
                    firstContext.wireCodec.encode(lateDeletion),
                ),
            )
            val deletionImport = WorkspacePriorEpochRemoteImporterV2(
                local,
                key,
                WRITER_A,
                remote,
                clock = { T3 },
            ).importUntilStable(first.descriptor.syncEpochId)
            assertEquals(1, assertIs<WorkspacePriorEpochRemoteImportResultV2.Imported>(deletionImport).importedVersions)
            val currentAfterDeletion = context(local, key)
            val deletionHead = currentAfterDeletion.store.loadHeads(noteKey()).single()
            assertEquals(WorkspaceEntityVersionKindV2.DELETION, deletionHead.kind)
            assertEquals(T0, deletionHead.deletionPayload?.deletedAt)
            assertTrue(currentAfterDeletion.store.loadVersion(mapped.versionId) != null)

            val replay = WorkspacePriorEpochRemoteImporterV2(
                local,
                key,
                WRITER_A,
                remote,
                clock = { T3 },
            ).importUntilStable(first.descriptor.syncEpochId)
            assertEquals(0, assertIs<WorkspacePriorEpochRemoteImportResultV2.Imported>(replay).importedVersions)
        } finally {
            driver.close()
        }
    }

    private fun context(
        local: SqlDelightLocalDataRepository,
        key: saien.someday.data.crypto.WorkspaceMasterKey,
    ) = WorkspaceSystemV2ContextProvider(
        local,
        { key },
        { WRITER_A },
        { SyncRemoteProfileV2.WEB_DAV.wireValue },
    ).requireActive()

    private fun initialSources() = listOf(
        WorkspaceCheckpointSourceHeadV2(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(NOTEBOOK_ID, "Note", "body", T0, "Asia/Shanghai", null),
            null,
            "fresh-local-v2",
            null,
            WRITER_A,
            null,
            "source-note",
            "source-note-digest",
        ),
        WorkspaceCheckpointSourceHeadV2(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2("Journal", 0, T0),
            null,
            "fresh-local-v2",
            null,
            WRITER_A,
            null,
            "source-notebook",
            "source-notebook-digest",
        ),
        WorkspaceCheckpointSourceHeadV2(
            WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            WorkspacePreferencesV2(defaultNotebookId = NOTEBOOK_ID),
            null,
            "fresh-local-v2",
            null,
            WRITER_A,
            null,
            "source-preferences",
            "source-preferences-digest",
        ),
    ).sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)

    private fun noteKey() = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, NOTE_ID)

    private companion object {
        const val WRITER_A = "10000000-0000-4000-8000-000000000001"
        const val WRITER_B = "10000000-0000-4000-8000-000000000002"
        const val NOTEBOOK_ID = "20000000-0000-4000-8000-000000000001"
        const val NOTE_ID = "30000000-0000-4000-8000-000000000001"
        const val LATE_MUTATION = "40000000-0000-4000-8000-000000000001"
        const val LATE_DELETE_MUTATION = "40000000-0000-4000-8000-000000000002"
        val T0 = Instant.parse("2026-07-19T00:00:00Z")
        val T1 = Instant.parse("2026-07-19T01:00:00Z")
        val T2 = Instant.parse("2026-07-19T02:00:00Z")
        val T3 = Instant.parse("2026-07-19T03:00:00Z")
    }
}

private class PriorEpochIdsV2 : CausalityIdGeneratorV2 {
    private var value = 1L
    override fun newId(): String = "90000000-0000-4000-8000-${(value++).toString().padStart(12, '0')}"
}
