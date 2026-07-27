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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkspaceEpochRetentionV2Test {
    @Test
    fun expiredReadOnlyEpochCollectsAtomicallyButOutboxPinsIt() {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val database = SomedayDatabase(driver)
            val local = SqlDelightLocalDataRepository(database, WRITER, clock = { FIRST_CREATED })
            val protocol = SqlDelightSyncProtocolStoreV2(database)
            protocol.persistPreparingEpoch(PROFILE, descriptor(FIRST_EPOCH, FIRST_CHECKPOINT, FIRST_CREATED), "first")
            protocol.activateEpoch(PROFILE, FIRST_EPOCH, FIRST_CREATED, WRITER)

            val materializer = CanonicalWorkspaceCausalityMaterializerV2(
                SyncEpochKeyDerivationV2().derive(WORKSPACE_KEY, FIRST_EPOCH),
            )
            val validator = WorkspaceEntityValidatorV2(materializer)
            val wire = WorkspaceEntityWireCodecV2(materializer, validator)
            val cipher = WorkspaceObjectCipherV2(WORKSPACE_KEY, materializer)
            val store = SqlDelightWorkspaceEntityStoreV2(
                database,
                FIRST_EPOCH,
                WorkspaceEntityCausalityEngineV2(materializer, validator),
                materializer,
                wire,
                WorkspaceOutboxEncoderV2 { version, mutationId ->
                    PreparedWorkspaceOutboxObjectV2(
                        WRITER,
                        cipher.encodeJson(cipher.encryptEntity(version, mutationId, WRITER, wire.encode(version))),
                    )
                },
            )
            val factory = WorkspaceEntityVersionFactoryV2(FIRST_EPOCH, materializer)
            val root = factory.createGenesis(
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                WorkspacePreferencesV2(),
                "device:$WRITER",
                FIRST_CREATED,
            )
            val mutationId = factory.newMutationId()
            assertTrue(store.commitLocalMutations(listOf(
                LocalWorkspaceMutationV2(PROFILE, mutationId, root, FIRST_CREATED),
            )) is WorkspaceLocalCommitResultV2.Committed)

            protocol.persistPreparingEpoch(
                PROFILE,
                descriptor(SECOND_EPOCH, SECOND_CHECKPOINT, SUCCESSOR_CREATED, FIRST_EPOCH),
                "second",
            )
            // Simulate a device which only notices the rollover after the
            // entire supported window.  Observation time must not restart it.
            protocol.activateEpoch(PROFILE, SECOND_EPOCH, AFTER_HORIZON, WRITER)
            val old = assertNotNull(protocol.loadEpoch(PROFILE, FIRST_EPOCH))
            assertEquals(EXPECTED_RETAIN_UNTIL.toEpochMilliseconds(), old.retainUntilEpochMilliseconds)

            val retention = WorkspaceEpochRetentionServiceV2(local, protocol)
            val pinned = retention.collectExpiredLocalEpochs(PROFILE, AFTER_HORIZON)
            assertEquals(listOf("pending_mutations"), pinned.pinnedEpochs.getValue(FIRST_EPOCH).safeReasons)
            assertNotNull(protocol.loadEpoch(PROFILE, FIRST_EPOCH))
            assertNotNull(store.loadVersion(root.versionId))

            assertTrue(store.acknowledgePending(PROFILE, mutationId, root.versionId, root.objectDigest))
            database.somedayQueries.insertRepairReplicaV2(
                PROFILE,
                FIRST_EPOCH,
                root.versionId,
                root.objectDigest,
                WRITER,
                "ct2:sha256:${"56".repeat(32)}",
                "authenticated-repair-replica",
                "valid",
                FIRST_CREATED.toEpochMilliseconds(),
            )
            val repairPinned = retention.collectExpiredLocalEpochs(PROFILE, AFTER_HORIZON)
            assertEquals(listOf("repair_pins"), repairPinned.pinnedEpochs.getValue(FIRST_EPOCH).safeReasons)
            database.somedayQueries.insertRepairReplicaV2(
                PROFILE,
                FIRST_EPOCH,
                root.versionId,
                root.objectDigest,
                WRITER,
                "ct2:sha256:${"56".repeat(32)}",
                "authenticated-repair-replica",
                "invalid",
                FIRST_CREATED.toEpochMilliseconds(),
            )
            val collected = retention.collectExpiredLocalEpochs(PROFILE, AFTER_HORIZON)
            assertEquals(listOf(FIRST_EPOCH), collected.collectedEpochIds)
            assertNull(protocol.loadEpoch(PROFILE, FIRST_EPOCH))
            assertNull(store.loadVersion(root.versionId))
            assertEquals(SECOND_EPOCH, protocol.loadActiveEpoch(PROFILE)?.descriptor?.syncEpochId)
        } finally {
            driver.close()
        }
    }

    @Test
    fun retainedPriorEpochKeepsUndeleteAndHistoryUntilCollectionWithoutResurrection() {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val database = SomedayDatabase(driver)
            val local = SqlDelightLocalDataRepository(database, WRITER, clock = { FIRST_CREATED })
            val remote = InMemoryWorkspaceSyncRemoteV2(PROFILE)
            val firstSources = listOf(
                checkpointSource(
                    WorkspaceEntityTypeV2.NOTEBOOK,
                    RETAINED_NOTEBOOK_ID,
                    NotebookContentV2("Retained notebook", 1, FIRST_CREATED),
                    "retained-notebook",
                ),
                checkpointSource(
                    WorkspaceEntityTypeV2.NOTE,
                    RESTORED_NOTE_ID,
                    NoteContentV2(
                        RETAINED_NOTEBOOK_ID,
                        "Retained restore title",
                        "Retained restore body",
                        FIRST_CREATED,
                        "UTC",
                        NoteLocationV2(1.25, 2.5, "Retained location", 3.0, 4.0, FIRST_CREATED),
                    ),
                    "restored-note",
                ),
                checkpointSource(
                    WorkspaceEntityTypeV2.NOTE,
                    EXPIRED_NOTE_ID,
                    NoteContentV2(
                        RETAINED_NOTEBOOK_ID,
                        "Eventually expired title",
                        "Eventually expired body",
                        FIRST_CREATED,
                        null,
                        null,
                    ),
                    "expired-note",
                ),
                checkpointSource(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    WorkspacePreferencesV2(defaultNotebookId = RETAINED_NOTEBOOK_ID),
                    "preferences",
                ),
            ).sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)
            val first = WorkspaceCheckpointBuilderV2(WORKSPACE_KEY, WRITER).build(
                remoteProfile = PROFILE,
                sourceHeads = firstSources,
                createdAt = FIRST_CREATED,
                syncEpochId = FIRST_EPOCH,
                checkpointId = FIRST_CHECKPOINT,
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(local, WORKSPACE_KEY, WRITER).persist(first),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(local, remote).publish(first),
            )

            val firstContext = WorkspaceSystemV2ContextProvider(
                local, { WORKSPACE_KEY }, { WRITER }, { PROFILE },
            ).requireActive()
            listOf(RESTORED_NOTE_ID, EXPIRED_NOTE_ID).forEach { noteId ->
                val head = firstContext.store.loadHeads(WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, noteId)).single()
                val deletion = firstContext.factory.createDeletion(
                    head,
                    FIRST_DELETION,
                    firstContext.deviceActorId,
                    FIRST_DELETION,
                )
                assertIs<WorkspaceLocalCommitResultV2.Committed>(
                    firstContext.store.commitLocalMutations(listOf(
                        LocalWorkspaceMutationV2(PROFILE, firstContext.factory.newMutationId(), deletion, FIRST_DELETION),
                    )),
                )
            }
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                WorkspaceSyncCoordinatorV2(local, WORKSPACE_KEY, WRITER, remote).syncOnce().status,
            )
            assertTrue(firstContext.store.loadPending(PROFILE).isEmpty())

            val secondSources = firstContext.store.loadEntityKeys()
                .flatMap(firstContext.store::loadHeads)
                .map { head ->
                    WorkspaceCheckpointSourceHeadV2(
                        head.entityType,
                        head.entityId,
                        head.contentPayload,
                        head.deletionPayload,
                        PROFILE,
                        FIRST_EPOCH,
                        WRITER,
                        null,
                        head.versionId,
                        head.objectDigest,
                        head.authoredAt,
                    )
                }
                .sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)
            val second = WorkspaceCheckpointBuilderV2(WORKSPACE_KEY, WRITER).build(
                remoteProfile = PROFILE,
                sourceHeads = secondSources,
                createdAt = SUCCESSOR_CREATED,
                previousPointerDigest = first.pointerObject.objectDigest,
                previousEpochId = FIRST_EPOCH,
                previousEpochFrontiers = remote.epochFrontiers(FIRST_EPOCH),
                syncEpochId = SECOND_EPOCH,
                checkpointId = SECOND_CHECKPOINT,
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(local, WORKSPACE_KEY, WRITER).persist(second),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(local, remote).publish(second),
            )
            assertEquals(SyncEpochLifecycleV2.READ_ONLY, SqlDelightSyncProtocolStoreV2(database)
                .loadEpoch(PROFILE, FIRST_EPOCH)?.lifecycle)

            val notes = SystemV2NotesRepository(
                local,
                { WORKSPACE_KEY },
                { WRITER },
                { PROFILE },
                clock = { SUCCESSOR_CREATED },
                workspaceKeyForEpochProvider = { WORKSPACE_KEY },
            )
            val activeContext = WorkspaceSystemV2ContextProvider(
                local, { WORKSPACE_KEY }, { WRITER }, { PROFILE },
            ).requireActive()
            val conflictRoot = activeContext.factory.createGenesis(
                WorkspaceEntityTypeV2.NOTEBOOK,
                ACTIVE_CONFLICT_NOTEBOOK_ID,
                NotebookContentV2("Conflict base", 99, SUCCESSOR_CREATED),
                activeContext.deviceActorId,
                SUCCESSOR_CREATED,
            )
            val conflictLeft = activeContext.factory.createContentChild(
                conflictRoot,
                (conflictRoot.contentPayload as NotebookContentV2).copy(title = "Conflict left"),
                activeContext.deviceActorId,
                SUCCESSOR_CREATED,
            )
            val conflictRight = activeContext.factory.createContentChild(
                conflictRoot,
                (conflictRoot.contentPayload as NotebookContentV2).copy(title = "Conflict right"),
                "device:00000000-0000-4000-8000-000000000002",
                SUCCESSOR_CREATED,
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                activeContext.store.commitLocalMutations(
                    listOf(conflictRoot, conflictLeft, conflictRight).map { version ->
                        LocalWorkspaceMutationV2(
                            PROFILE,
                            activeContext.factory.newMutationId(),
                            version,
                            SUCCESSOR_CREATED,
                        )
                    },
                ),
            )
            val conflictKey = WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.NOTEBOOK,
                ACTIVE_CONFLICT_NOTEBOOK_ID,
            )
            assertEquals(2, activeContext.store.loadHeads(conflictKey).size)
            assertEquals(1, activeContext.store.loadConflicts(conflictKey).count {
                it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE
            })
            val beforeCollection = notes.listDeletedWorkspaceItems()
            assertEquals(setOf(RESTORED_NOTE_ID, EXPIRED_NOTE_ID), beforeCollection.map { it.entityId }.toSet())
            assertTrue(beforeCollection.all { it.canRestore })
            assertTrue(notes.listNoteVersions(RESTORED_NOTE_ID).any {
                it.mergeMetadata?.contains("retained-v2:$FIRST_EPOCH") == true
            })

            val selected = beforeCollection.single { it.entityId == RESTORED_NOTE_ID }
            val oldContentVersionId = assertNotNull(selected.retainedContentVersionId)
            val restored = notes.undeleteNote(
                RESTORED_NOTE_ID,
                oldContentVersionId,
                selected.causalToken,
            )
            assertEquals("Retained restore body", restored.markdownBody)
            assertEquals("Retained location", restored.location?.placeText)
            val restoredHead = WorkspaceSystemV2ContextProvider(
                local, { WORKSPACE_KEY }, { WRITER }, { PROFILE },
            ).requireActive().store.loadHeads(WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.NOTE,
                RESTORED_NOTE_ID,
            )).single()
            assertEquals(listOf(selected.causalToken.expectedBaseVersionId), restoredHead.parentVersionIds)
            assertTrue(oldContentVersionId !in restoredHead.parentVersionIds)

            val collected = WorkspaceEpochRetentionServiceV2(local)
                .collectExpiredLocalEpochs(PROFILE, AFTER_HORIZON)
            assertEquals(listOf(FIRST_EPOCH), collected.collectedEpochIds)
            assertNotNull(activeContext.store.loadVersion(conflictRoot.versionId))
            assertEquals(2, activeContext.store.loadHeads(conflictKey).size)
            assertEquals(1, activeContext.store.loadConflicts(conflictKey).count {
                it.lifecycle == WorkspaceConflictLifecycleV2.ACTIVE
            })
            val expired = notes.listDeletedWorkspaceItems().single { it.entityId == EXPIRED_NOTE_ID }
            assertTrue(!expired.canRestore)
            assertEquals("Deleted note", expired.displayTitle)
            val currentContext = WorkspaceSystemV2ContextProvider(
                local, { WORKSPACE_KEY }, { WRITER }, { PROFILE },
            ).requireActive()
            assertEquals(
                WorkspaceEntityVersionKindV2.DELETION,
                currentContext.store.loadHeads(WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.NOTE,
                    EXPIRED_NOTE_ID,
                )).single().kind,
            )
            assertEquals("Retained restore body", notes.getNoteDetails(RESTORED_NOTE_ID)?.markdownBody)
            assertTrue(notes.listNoteVersions(RESTORED_NOTE_ID).none {
                it.mergeMetadata?.contains("retained-v2:$FIRST_EPOCH") == true
            })
        } finally {
            driver.close()
        }
    }

    private fun checkpointSource(
        entityType: WorkspaceEntityTypeV2,
        entityId: String,
        content: WorkspaceEntityContentV2,
        sourceObjectId: String,
    ) = WorkspaceCheckpointSourceHeadV2(
        entityType,
        entityId,
        content,
        null,
        "fresh-local-v2",
        null,
        WRITER,
        null,
        sourceObjectId,
        "source-digest-$sourceObjectId",
        FIRST_CREATED,
    )

    private fun descriptor(
        epochId: String,
        checkpointId: String,
        createdAt: Instant,
        previousEpochId: String? = null,
    ) = SyncEpochDescriptorV2(
        syncEpochId = epochId,
        remoteProfile = PROFILE,
        checkpointId = checkpointId,
        checkpointDigest = "cd2:hmac-sha256:${"12".repeat(32)}",
        previousEpochId = previousEpochId,
        previousEpochPointerDigest = previousEpochId?.let { "cd2:hmac-sha256:${"34".repeat(32)}" },
        createdByDeviceId = WRITER,
        createdAt = createdAt,
    )

    private companion object {
        const val PROFILE = "webdav-log-v2"
        const val WRITER = "00000000-0000-4000-8000-000000000001"
        const val FIRST_EPOCH = "00000000-0000-4000-8000-000000000010"
        const val SECOND_EPOCH = "00000000-0000-4000-8000-000000000011"
        const val FIRST_CHECKPOINT = "00000000-0000-4000-8000-000000000020"
        const val SECOND_CHECKPOINT = "00000000-0000-4000-8000-000000000021"
        const val RETAINED_NOTEBOOK_ID = "00000000-0000-4000-8000-000000000030"
        const val RESTORED_NOTE_ID = "00000000-0000-4000-8000-000000000031"
        const val EXPIRED_NOTE_ID = "00000000-0000-4000-8000-000000000032"
        const val ACTIVE_CONFLICT_NOTEBOOK_ID = "00000000-0000-4000-8000-000000000033"
        val FIRST_CREATED = Instant.parse("2026-01-01T00:00:00Z")
        val FIRST_DELETION = Instant.parse("2026-01-01T01:00:00Z")
        val SUCCESSOR_CREATED = Instant.parse("2026-01-02T00:00:00Z")
        val EXPECTED_RETAIN_UNTIL = Instant.parse("2026-07-01T00:00:00Z")
        val AFTER_HORIZON = Instant.parse("2026-07-02T00:00:00Z")
        val WORKSPACE_KEY = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 33).toByte() })
    }
}
