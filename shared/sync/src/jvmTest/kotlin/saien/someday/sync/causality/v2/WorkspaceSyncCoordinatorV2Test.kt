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

class WorkspaceSyncCoordinatorV2Test {
    @Test
    fun missingSelfHostedCursorObjectRequiresRebootstrapAndPreservesPendingUpload() {
        val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 18).toByte() })
        val remote = InMemoryWorkspaceSyncRemoteV2()
        val publisher = fixture(WRITER_A)
        val client = fixture(WRITER_B)
        try {
            val prepared = WorkspaceCheckpointBuilderV2(key, WRITER_A).build(
                remoteProfile = remote.remoteProfile,
                sourceHeads = listOf(WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    WorkspacePreferencesV2(),
                    null,
                    "missing-cursor-object-test",
                    null,
                    WRITER_A,
                    null,
                    "missing-cursor-source",
                    "missing-cursor-source-digest",
                    T0,
                )),
                createdAt = T0,
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(publisher.local, key, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(publisher.local, remote).publish(prepared),
            )
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                WorkspaceSyncCoordinatorV2(client.local, key, WRITER_B, remote).syncOnce().status,
            )
            val context = WorkspaceSystemV2ContextProvider(
                client.local,
                { key },
                { WRITER_B },
                { remote.remoteProfile },
            ).requireActive()
            val preferenceKey = WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            )
            val parent = context.store.loadHeads(preferenceKey).single()
            val local = context.factory.createContentChild(
                parent,
                (parent.contentPayload as WorkspacePreferencesV2).copy(previewByDefault = true),
                context.deviceActorId,
                T1,
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                context.store.commitLocalMutations(listOf(
                    LocalWorkspaceMutationV2(remote.remoteProfile, context.factory.newMutationId(), local, T1),
                )),
            )

            val blocked = WorkspaceSyncCoordinatorV2(
                client.local,
                key,
                WRITER_B,
                MissingCursorObjectRemoteV2(remote),
            ).syncOnce()

            assertEquals(SyncCoordinatorStatusV2.BLOCKED, blocked.status)
            assertEquals("missing_remote_object", blocked.safeErrorCode)
            assertEquals(0, blocked.pushedObjects)
            assertEquals(1, context.store.loadPending(remote.remoteProfile).size)
            assertTrue(remote.allChanges().isEmpty())
            val run = SqlDelightSyncProtocolStoreV2(client.local.database).loadRuns(remote.remoteProfile).first()
            assertEquals(SyncRunRepairStateV2.REBOOTSTRAP_REQUIRED, run.counters.repairState)
        } finally {
            publisher.close()
            client.close()
        }
    }

    @Test
    fun prototypePointerWithWrongContractOrSchemaSetFailsClosedBeforeBootstrap() {
        val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 19).toByte() })
        val variants: List<(EncryptedWorkspaceObjectV2) -> EncryptedWorkspaceObjectV2> = listOf(
            { it.copy(contractId = "someday-note-only-prototype-v2") },
            { it.copy(schemaSetVersion = "notes-only-schema-v0") },
        )
        variants.forEach { mutatePointer ->
            val remote = InMemoryWorkspaceSyncRemoteV2()
            val joining = fixture(WRITER_B)
            try {
                val prepared = WorkspaceCheckpointBuilderV2(key, WRITER_A).build(
                    remoteProfile = remote.remoteProfile,
                    sourceHeads = listOf(WorkspaceCheckpointSourceHeadV2(
                        WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                        WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                        WorkspacePreferencesV2(),
                        null,
                        "prototype-marker-test",
                        null,
                        WRITER_A,
                        null,
                        "prototype-source",
                        "prototype-source-digest",
                        T0,
                    )),
                    createdAt = T0,
                )
                prepared.chunks.forEach { chunk ->
                    assertIs<WorkspaceImmutablePutResultV2.Stored>(
                        remote.putCheckpointChunk(prepared.descriptor, chunk.ref, chunk.encryptedObject),
                    )
                }
                assertIs<WorkspaceImmutablePutResultV2.Stored>(
                    remote.putCheckpointManifest(prepared.descriptor, prepared.manifestObject),
                )
                assertIs<WorkspacePointerPublishResultV2.Published>(
                    remote.compareAndSetEpochPointer(
                        prepared.descriptor,
                        null,
                        mutatePointer(prepared.pointerObject),
                    ),
                )

                val result = WorkspaceSyncCoordinatorV2(joining.local, key, WRITER_B, remote).syncOnce()

                assertEquals(SyncCoordinatorStatusV2.BLOCKED, result.status)
                assertEquals(0, result.pulledObjects)
                assertEquals(0, result.pushedObjects)
                assertEquals(null, SqlDelightSyncProtocolStoreV2(joining.local.database).loadActiveEpoch(remote.remoteProfile))
            } finally {
                joining.close()
            }
        }
    }

    @Test
    fun wrongWorkspaceKeyFailsPointerAuthenticationBeforeAnyUpload() {
        val correctKey = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 21).toByte() })
        val wrongKey = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 22).toByte() })
        val remote = InMemoryWorkspaceSyncRemoteV2()
        val publisher = fixture(WRITER_A)
        val joining = fixture(WRITER_B)
        try {
            val prepared = WorkspaceCheckpointBuilderV2(correctKey, WRITER_A).build(
                remoteProfile = remote.remoteProfile,
                sourceHeads = listOf(WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    WorkspacePreferencesV2(),
                    null,
                    "wrong-key-test",
                    null,
                    WRITER_A,
                    null,
                    "wrong-key-source",
                    "wrong-key-source-digest",
                    T0,
                )),
                createdAt = T0,
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(publisher.local, correctKey, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(publisher.local, remote).publish(prepared),
            )
            assertTrue(remote.allChanges().isEmpty())

            val result = WorkspaceSyncCoordinatorV2(joining.local, wrongKey, WRITER_B, remote).syncOnce()

            assertEquals(SyncCoordinatorStatusV2.BLOCKED, result.status)
            assertEquals(0, result.pushedObjects)
            assertTrue(remote.allChanges().isEmpty())
            assertEquals(null, SqlDelightSyncProtocolStoreV2(joining.local.database).loadActiveEpoch(remote.remoteProfile))
        } finally {
            publisher.close()
            joining.close()
        }
    }

    @Test
    fun exactCheckpointBootstrapsWholeProductAndThenSyncsGenericMutations() {
        val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 31).toByte() })
        val remote = InMemoryWorkspaceSyncRemoteV2()
        val ids = CheckpointIdsV2()
        val first = fixture(WRITER_A)
        val second = fixture(WRITER_B)
        try {
            val sources = listOf(
                WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.NOTE,
                    NOTE_ID,
                    NoteContentV2(
                        NOTEBOOK_ID,
                        "Initial",
                        "Body",
                        T0,
                        "Asia/Shanghai",
                        NoteLocationV2(31.2, 121.4, "Place", 4.0, null, T0),
                    ),
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
                    "source-book",
                    "source-book-digest",
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
            val prepared = WorkspaceCheckpointBuilderV2(key, WRITER_A, ids).build(
                remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
                sourceHeads = sources,
                createdAt = T0,
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(first.local, key, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(first.local, remote).publish(prepared),
            )

            val firstContext = WorkspaceSystemV2ContextProvider(
                first.local, { key }, { WRITER_A }, { SyncRemoteProfileV2.SELF_HOSTED.wireValue },
            ).requireActive()
            assertEquals(3, firstContext.store.loadEntityKeys().size)
            assertEquals("Place", (firstContext.store.loadProjection(noteKey())?.content as NoteContentV2).location?.placeText)

            val bootstrap = WorkspaceSyncCoordinatorV2(second.local, key, WRITER_B, remote).syncOnce()
            assertEquals(SyncCoordinatorStatusV2.SUCCESS, bootstrap.status)
            val secondContext = WorkspaceSystemV2ContextProvider(
                second.local, { key }, { WRITER_B }, { SyncRemoteProfileV2.SELF_HOSTED.wireValue },
            ).requireActive()
            assertEquals(
                firstContext.store.loadProjection(noteKey()),
                secondContext.store.loadProjection(noteKey()),
            )

            val head = firstContext.store.loadHeads(noteKey()).single()
            val edited = firstContext.factory.createContentChild(
                head,
                (head.contentPayload as NoteContentV2).copy(markdownBody = "Changed"),
                firstContext.deviceActorId,
                T1,
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                firstContext.store.commitLocalMutations(listOf(
                    LocalWorkspaceMutationV2(
                        SyncRemoteProfileV2.SELF_HOSTED.wireValue,
                        firstContext.factory.newMutationId(),
                        edited,
                        T1,
                    ),
                )),
            )
            assertEquals(SyncCoordinatorStatusV2.SUCCESS, WorkspaceSyncCoordinatorV2(
                first.local, key, WRITER_A, remote,
            ).syncOnce().status)
            val pulled = WorkspaceSyncCoordinatorV2(
                second.local, key, WRITER_B, remote,
            ).syncOnce()
            assertEquals(SyncCoordinatorStatusV2.SUCCESS, pulled.status)
            assertEquals(
                "Changed",
                (secondContext.store.loadProjection(noteKey())?.content as NoteContentV2).markdownBody,
            )
            assertTrue(firstContext.store.loadPending(SyncRemoteProfileV2.SELF_HOSTED.wireValue).isEmpty())

            val whileOff = SystemV2NotesRepository(
                first.local,
                { key },
                { error("SyncMode.Off must use the durable authority writer identity.") },
                { "" },
                clock = { T1 },
            )
            val offlineNotebook = whileOff.createNotebook("Created while sync is off")
            assertTrue(firstContext.store.loadPending(remote.remoteProfile).any {
                it.objectId == offlineNotebook.causalToken?.expectedBaseVersionId
            })
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                WorkspaceSyncCoordinatorV2(first.local, key, WRITER_A, remote).syncOnce().status,
            )
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                WorkspaceSyncCoordinatorV2(second.local, key, WRITER_B, remote).syncOnce().status,
            )
            assertEquals(
                "Created while sync is off",
                (secondContext.store.loadProjection(WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.NOTEBOOK,
                    offlineNotebook.id,
                ))?.content as NotebookContentV2).title,
            )
            assertTrue(firstContext.store.loadPending(remote.remoteProfile).isEmpty())

            val run = SqlDelightSyncProtocolStoreV2(second.local.database)
                .loadRuns(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
                .first()
            assertEquals(SYNC_V2_CONTRACT_ID, run.contractId)
            assertEquals(prepared.descriptor.syncEpochId, run.epochId)
            assertEquals(SyncRunStatusV2.SUCCESS, run.status)
            assertTrue(run.counters.storedVersions >= 1)
            assertTrue(run.counters.fastForwards >= 1)
            assertEquals(0, run.counters.deadLetters)
            assertEquals(SyncRunRepairStateV2.HEALTHY, run.counters.repairState)
            assertTrue(run.counters.checkpointHorizonEpochMilliseconds != null)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun completeCheckpointPreservesDeletionEveryConflictBranchProjectionAndWarning() {
        val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 41).toByte() })
        val remote = InMemoryWorkspaceSyncRemoteV2()
        val first = fixture(WRITER_A)
        val second = fixture(WRITER_B)
        try {
            fun source(
                entityType: WorkspaceEntityTypeV2,
                entityId: String,
                content: WorkspaceEntityContentV2? = null,
                deletion: WorkspaceDeletionV2? = null,
                sourceId: String,
            ) = WorkspaceCheckpointSourceHeadV2(
                entityType,
                entityId,
                content,
                deletion,
                remote.remoteProfile,
                "80000000-0000-4000-8000-000000000001",
                WRITER_A,
                null,
                sourceId,
                "source-digest-$sourceId",
                T0,
            )

            val location = NoteLocationV2(-33.86, 151.21, "Complete checkpoint place", 6.0, 11.0, T0)
            val sources = listOf(
                source(
                    WorkspaceEntityTypeV2.NOTEBOOK,
                    COMPLETE_NOTEBOOK_ID,
                    NotebookContentV2("Complete notebook", 1, T0),
                    sourceId = "live-notebook",
                ),
                source(
                    WorkspaceEntityTypeV2.NOTEBOOK,
                    CONFLICT_NOTEBOOK_ID,
                    NotebookContentV2("Notebook branch A", 2, T0),
                    sourceId = "conflict-notebook-a",
                ),
                source(
                    WorkspaceEntityTypeV2.NOTEBOOK,
                    CONFLICT_NOTEBOOK_ID,
                    NotebookContentV2("Notebook branch B", 2, T0),
                    sourceId = "conflict-notebook-b",
                ),
                source(
                    WorkspaceEntityTypeV2.NOTE,
                    COMPLETE_NOTE_ID,
                    NoteContentV2(COMPLETE_NOTEBOOK_ID, "Complete note", "Body", T0, "UTC", location),
                    sourceId = "live-note",
                ),
                source(
                    WorkspaceEntityTypeV2.NOTE,
                    DANGLING_NOTE_ID,
                    NoteContentV2(MISSING_NOTEBOOK_ID, "Dangling", "Retained", T0, null, null),
                    sourceId = "dangling-note",
                ),
                source(
                    WorkspaceEntityTypeV2.NOTE,
                    DELETED_NOTE_ID,
                    deletion = WorkspaceDeletionV2(T0),
                    sourceId = "deleted-note",
                ),
                source(
                    WorkspaceEntityTypeV2.NOTE,
                    CONFLICT_NOTE_ID,
                    NoteContentV2(COMPLETE_NOTEBOOK_ID, "Note branch A", "Body", T0, null, null),
                    sourceId = "conflict-note-a",
                ),
                source(
                    WorkspaceEntityTypeV2.NOTE,
                    CONFLICT_NOTE_ID,
                    NoteContentV2(COMPLETE_NOTEBOOK_ID, "Note branch B", "Body", T0, null, null),
                    sourceId = "conflict-note-b",
                ),
                source(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    WorkspacePreferencesV2(theme = WorkspaceThemeV2.DARK, defaultNotebookId = COMPLETE_NOTEBOOK_ID),
                    sourceId = "preferences-a",
                ),
                source(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    WorkspacePreferencesV2(theme = WorkspaceThemeV2.LIGHT, defaultNotebookId = COMPLETE_NOTEBOOK_ID),
                    sourceId = "preferences-b",
                ),
            ).sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)
            val prepared = WorkspaceCheckpointBuilderV2(key, WRITER_A).build(
                remoteProfile = remote.remoteProfile,
                sourceHeads = sources,
                createdAt = T0,
            )
            assertEquals(sources.size, prepared.entities.count { it.version.provenance != null })
            assertTrue(prepared.chunks.all { it.value.objects.size <= MAX_CHECKPOINT_CHUNK_OBJECTS_SYSTEM_V2 })
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(first.local, key, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(first.local, remote).publish(prepared),
            )
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                WorkspaceSyncCoordinatorV2(second.local, key, WRITER_B, remote).syncOnce().status,
            )

            val firstContext = WorkspaceSystemV2ContextProvider(
                first.local, { key }, { WRITER_A }, { remote.remoteProfile },
            ).requireActive()
            val secondContext = WorkspaceSystemV2ContextProvider(
                second.local, { key }, { WRITER_B }, { remote.remoteProfile },
            ).requireActive()
            firstContext.store.loadEntityKeys().forEach { entityKey ->
                assertEquals(
                    firstContext.store.loadHeads(entityKey).map { it.versionId }.sorted(),
                    secondContext.store.loadHeads(entityKey).map { it.versionId }.sorted(),
                    entityKey.toString(),
                )
                assertEquals(
                    firstContext.store.loadConflicts(entityKey).map { it.descriptor },
                    secondContext.store.loadConflicts(entityKey).map { it.descriptor },
                    entityKey.toString(),
                )
            }
            assertEquals(3, secondContext.store.loadActiveConflicts().size)
            assertEquals(
                setOf("Note branch A", "Note branch B"),
                secondContext.store.loadHeads(WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.NOTE,
                    CONFLICT_NOTE_ID,
                )).map { (it.contentPayload as NoteContentV2).title }.toSet(),
            )
            assertEquals(
                setOf("Notebook branch A", "Notebook branch B"),
                secondContext.store.loadHeads(WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.NOTEBOOK,
                    CONFLICT_NOTEBOOK_ID,
                )).map { (it.contentPayload as NotebookContentV2).title }.toSet(),
            )
            assertEquals(
                setOf(WorkspaceThemeV2.DARK, WorkspaceThemeV2.LIGHT),
                secondContext.store.loadHeads(WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                )).map { (it.contentPayload as WorkspacePreferencesV2).theme }.toSet(),
            )
            assertEquals(
                WorkspaceEntityVersionKindV2.DELETION,
                secondContext.store.loadHeads(WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.NOTE,
                    DELETED_NOTE_ID,
                )).single().kind,
            )
            assertEquals(
                location,
                (secondContext.store.loadProjection(WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.NOTE,
                    COMPLETE_NOTE_ID,
                ))?.content as NoteContentV2).location,
            )
            val danglingProjection = assertNotNull(secondContext.store.loadProjection(WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.NOTE,
                DANGLING_NOTE_ID,
            )))
            assertEquals("unresolved_notebook_reference", danglingProjection.warning)
            assertEquals(MISSING_NOTEBOOK_ID, danglingProjection.referencedEntityId)
            assertEquals(RECOVERY_INBOX_EFFECTIVE_NOTEBOOK_ID_V2, danglingProjection.effectiveEntityId)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun retainedPointerChainProvesMissedRolloversAndRejectsAuthenticatedRollback() {
        val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 51).toByte() })
        val remote = InMemoryWorkspaceSyncRemoteV2()
        val fixture = fixture(WRITER_A)
        try {
            fun source(label: String, prior: PreparedWorkspaceEpochCheckpointV2? = null) =
                listOf(WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    WorkspacePreferencesV2(),
                    null,
                    "pointer-chain-test",
                    prior?.descriptor?.syncEpochId,
                    WRITER_A,
                    null,
                    "source-$label",
                    "source-digest-$label",
                    T0,
                ))

            fun build(label: String, prior: PreparedWorkspaceEpochCheckpointV2? = null) =
                WorkspaceCheckpointBuilderV2(key, WRITER_A).build(
                    remoteProfile = remote.remoteProfile,
                    sourceHeads = source(label, prior),
                    createdAt = T0,
                    previousPointerDigest = prior?.pointerObject?.objectDigest,
                    previousEpochId = prior?.descriptor?.syncEpochId,
                    previousEpochFrontiers = prior?.let { remote.epochFrontiers(it.descriptor.syncEpochId) }.orEmpty(),
                )

            fun publish(value: PreparedWorkspaceEpochCheckpointV2) {
                value.chunks.forEach { chunk ->
                    assertIs<WorkspaceImmutablePutResultV2.Stored>(
                        remote.putCheckpointChunk(value.descriptor, chunk.ref, chunk.encryptedObject),
                    )
                }
                assertIs<WorkspaceImmutablePutResultV2.Stored>(
                    remote.putCheckpointManifest(value.descriptor, value.manifestObject),
                )
                assertIs<WorkspacePointerPublishResultV2.Published>(
                    remote.compareAndSetEpochPointer(
                        value.descriptor,
                        value.pointer.previousPointerDigest,
                        value.pointerObject,
                    ),
                )
            }

            val first = build("first")
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(fixture.local, key, WRITER_A).persist(first),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(fixture.local, remote).publish(first),
            )
            val second = build("second", first).also(::publish)
            val third = build("third", second).also(::publish)

            val caughtUp = WorkspaceSyncCoordinatorV2(fixture.local, key, WRITER_A, remote).syncOnce()
            assertEquals(SyncCoordinatorStatusV2.SUCCESS, caughtUp.status)
            assertEquals(third.descriptor.syncEpochId, caughtUp.epochId)

            remote.forceEpochPointerForTest(first.pointerObject)
            val rollback = WorkspaceSyncCoordinatorV2(fixture.local, key, WRITER_A, remote).syncOnce()
            assertEquals(SyncCoordinatorStatusV2.BLOCKED, rollback.status)
            assertEquals("remote_rollback_detected", rollback.safeErrorCode)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun selfHostedCursorRollbackBlocksBeforeAnyUpload() {
        val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 61).toByte() })
        val backing = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        val remote = CursorProofRemoteV2(backing)
        val leader = fixture(WRITER_A)
        val follower = fixture(WRITER_B)
        try {
            val prepared = WorkspaceCheckpointBuilderV2(key, WRITER_A).build(
                remoteProfile = remote.remoteProfile,
                sourceHeads = listOf(WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    WorkspacePreferencesV2(),
                    null,
                    "cursor-proof-test",
                    null,
                    WRITER_A,
                    null,
                    "cursor-proof-preferences",
                    "cursor-proof-digest",
                    T0,
                )),
                createdAt = T0,
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(leader.local, key, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(leader.local, remote).publish(prepared),
            )
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                WorkspaceSyncCoordinatorV2(follower.local, key, WRITER_B, remote).syncOnce().status,
            )

            remote.emitOneValidCursorAdvance = true
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                WorkspaceSyncCoordinatorV2(follower.local, key, WRITER_B, remote).syncOnce().status,
            )
            val followerContext = WorkspaceSystemV2ContextProvider(
                follower.local, { key }, { WRITER_B }, { remote.remoteProfile },
            ).requireActive()
            assertEquals("1", followerContext.store.loadCursor(remote.remoteProfile, "global")?.cursorValue)

            remote.emitRollback = true
            val pushesBefore = remote.pushCalls
            val blocked = WorkspaceSyncCoordinatorV2(follower.local, key, WRITER_B, remote).syncOnce()
            assertEquals(SyncCoordinatorStatusV2.BLOCKED, blocked.status)
            assertEquals("remote_rollback_detected", blocked.safeErrorCode)
            assertEquals("1", followerContext.store.loadCursor(remote.remoteProfile, "global")?.cursorValue)
            assertEquals(pushesBefore, remote.pushCalls)
        } finally {
            leader.close()
            follower.close()
        }
    }

    @Test
    fun persistentCipherMismatchBlocksPushAndExactReplicaRepairReplaysOriginalCursorUnit() {
        val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 71).toByte() })
        val remote = InMemoryWorkspaceSyncRemoteV2()
        val first = fixture(WRITER_A)
        val second = fixture(WRITER_B)
        try {
            val prepared = WorkspaceCheckpointBuilderV2(key, WRITER_A).build(
                remoteProfile = remote.remoteProfile,
                sourceHeads = listOf(
                    WorkspaceCheckpointSourceHeadV2(
                        WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                        WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                        WorkspacePreferencesV2(),
                        null,
                        "repair-test",
                        null,
                        WRITER_A,
                        null,
                        "repair-source",
                        "repair-source-digest",
                        T0,
                    ),
                ),
                createdAt = T0,
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(first.local, key, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(first.local, remote).publish(prepared),
            )
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                WorkspaceSyncCoordinatorV2(second.local, key, WRITER_B, remote).syncOnce().status,
            )
            val firstContext = WorkspaceSystemV2ContextProvider(
                first.local,
                { key },
                { WRITER_A },
                { remote.remoteProfile },
            ).requireActive()
            val preferenceKey = WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            )
            val base = firstContext.store.loadHeads(preferenceKey).single()
            val edited = firstContext.factory.createContentChild(
                base,
                (base.contentPayload as WorkspacePreferencesV2).copy(markdownToolbarVisible = false),
                firstContext.deviceActorId,
                T1,
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                firstContext.store.commitLocalMutations(listOf(
                    LocalWorkspaceMutationV2(remote.remoteProfile, firstContext.factory.newMutationId(), edited, T1),
                )),
            )
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                WorkspaceSyncCoordinatorV2(first.local, key, WRITER_A, remote).syncOnce().status,
            )

            remote.faults.corruptNextPulledObject = true
            val blocked = WorkspaceSyncCoordinatorV2(second.local, key, WRITER_B, remote).syncOnce()

            assertEquals(SyncCoordinatorStatusV2.BLOCKED, blocked.status)
            val protocol = SqlDelightSyncProtocolStoreV2(second.local.database)
            val deadLetter = protocol.loadActiveDeadLetters(remote.remoteProfile, prepared.descriptor.syncEpochId).single()
            assertEquals(SyncDeadLetterFailureClassV2.PERSISTENT_INTEGRITY, deadLetter.input.failureClass)
            assertEquals(
                SyncEpochHealthV2.BLOCKED,
                protocol.loadEpoch(remote.remoteProfile, prepared.descriptor.syncEpochId)?.health,
            )
            val repaired = WorkspaceImmutableObjectRepairServiceV2(
                second.local,
                key,
                WRITER_B,
                remote,
                protocol,
                clock = { T1 },
            ).repair(deadLetter)
            assertIs<WorkspaceRepairResultV2.Repaired>(repaired)
            assertTrue(repaired.repairedObjectIds.isNotEmpty())
            assertTrue(protocol.loadActiveDeadLetters(remote.remoteProfile, prepared.descriptor.syncEpochId).isEmpty())

            val resumed = WorkspaceSyncCoordinatorV2(second.local, key, WRITER_B, remote).syncOnce()

            assertEquals(SyncCoordinatorStatusV2.SUCCESS, resumed.status)
            val secondContext = WorkspaceSystemV2ContextProvider(
                second.local,
                { key },
                { WRITER_B },
                { remote.remoteProfile },
            ).requireActive()
            assertEquals(
                false,
                (secondContext.store.loadProjection(preferenceKey)?.content as WorkspacePreferencesV2).markdownToolbarVisible,
            )
        } finally {
            first.close()
            second.close()
        }
    }

    private fun fixture(writer: String): Fixture {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        val database = SomedayDatabase(driver)
        return Fixture(driver, SqlDelightLocalDataRepository(database, "test-$writer", clock = { T0 }))
    }

    private data class Fixture(
        val driver: app.cash.sqldelight.db.SqlDriver,
        val local: SqlDelightLocalDataRepository,
    ) {
        fun close() = driver.close()
    }

    private fun noteKey() = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, NOTE_ID)

    private companion object {
        const val WRITER_A = "10000000-0000-4000-8000-000000000001"
        const val WRITER_B = "10000000-0000-4000-8000-000000000002"
        const val NOTEBOOK_ID = "20000000-0000-4000-8000-000000000001"
        const val NOTE_ID = "30000000-0000-4000-8000-000000000001"
        const val COMPLETE_NOTEBOOK_ID = "20000000-0000-4000-8000-000000000011"
        const val CONFLICT_NOTEBOOK_ID = "20000000-0000-4000-8000-000000000012"
        const val COMPLETE_NOTE_ID = "30000000-0000-4000-8000-000000000011"
        const val DANGLING_NOTE_ID = "30000000-0000-4000-8000-000000000012"
        const val DELETED_NOTE_ID = "30000000-0000-4000-8000-000000000013"
        const val CONFLICT_NOTE_ID = "30000000-0000-4000-8000-000000000014"
        const val MISSING_NOTEBOOK_ID = "20000000-0000-4000-8000-000000000099"
        val T0 = Instant.parse("2026-07-19T00:00:00Z")
        val T1 = Instant.parse("2026-07-19T01:00:00Z")
    }
}

private class MissingCursorObjectRemoteV2(
    private val delegate: WorkspaceSyncRemoteV2,
) : WorkspaceSyncRemoteV2 by delegate {
    override fun pull(
        syncEpochId: String,
        cursors: Map<String, String?>,
        limit: Int,
    ): WorkspaceSyncPullResultV2 = WorkspaceSyncPullResultV2(
        units = emptyList(),
        frontierStable = false,
        rebootstrapRequired = true,
        safeErrorCode = "missing_remote_object",
    )
}

private class CursorProofRemoteV2(
    private val delegate: WorkspaceSyncRemoteV2,
) : WorkspaceSyncRemoteV2 by delegate {
    var emitOneValidCursorAdvance: Boolean = false
    var emitRollback: Boolean = false
    var pushCalls: Int = 0

    override fun pull(
        syncEpochId: String,
        cursors: Map<String, String?>,
        limit: Int,
    ): WorkspaceSyncPullResultV2 = when {
        emitRollback -> WorkspaceSyncPullResultV2(
            units = listOf(WorkspaceEncryptedCursorUnitV2(
                syncEpochId,
                "global",
                expectedCursorValue = null,
                nextCursorValue = "0",
                unitId = "rolled-back-unit",
                unitDigest = "rolled-back-digest",
                objects = emptyList(),
            )),
            frontierStable = true,
        )
        emitOneValidCursorAdvance -> {
            emitOneValidCursorAdvance = false
            WorkspaceSyncPullResultV2(
                units = listOf(WorkspaceEncryptedCursorUnitV2(
                    syncEpochId,
                    "global",
                    expectedCursorValue = null,
                    nextCursorValue = "1",
                    unitId = "valid-unit",
                    unitDigest = "valid-unit-digest",
                    objects = emptyList(),
                )),
                frontierStable = true,
            )
        }
        else -> delegate.pull(syncEpochId, cursors, limit)
    }

    override fun push(
        syncEpochId: String,
        objects: List<EncryptedWorkspaceObjectV2>,
    ): WorkspaceSyncPushResultV2 {
        pushCalls++
        return delegate.push(syncEpochId, objects)
    }
}

private class CheckpointIdsV2 : CausalityIdGeneratorV2 {
    private var value = 1L
    override fun newId(): String = "90000000-0000-4000-8000-${(value++).toString().padStart(12, '0')}"
}
