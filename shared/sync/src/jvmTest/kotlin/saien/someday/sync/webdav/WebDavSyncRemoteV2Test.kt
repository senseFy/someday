@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.webdav

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.sync.causality.v2.CausalityIdGeneratorV2
import saien.someday.sync.causality.v2.CanonicalWorkspaceCausalityMaterializerV2
import saien.someday.sync.causality.v2.CborValueV2
import saien.someday.sync.causality.v2.DeterministicCborV2
import saien.someday.sync.causality.v2.EncryptedWorkspaceObjectDecodeResultV2
import saien.someday.sync.causality.v2.MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2
import saien.someday.sync.causality.v2.LocalWorkspaceMutationV2
import saien.someday.sync.causality.v2.NoteContentV2
import saien.someday.sync.causality.v2.NoteLocationV2
import saien.someday.sync.causality.v2.NotebookContentV2
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.SyncEpochKeyDerivationV2
import saien.someday.sync.causality.v2.SyncCoordinatorStatusV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.WORKSPACE_PREFERENCES_ENTITY_ID_V2
import saien.someday.sync.causality.v2.WorkspaceCheckpointBuilderV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPersistResultV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPersistenceV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPublishResultV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPublisherV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointSourceHeadV2
import saien.someday.sync.causality.v2.WorkspaceEntityKeyV2
import saien.someday.sync.causality.v2.WorkspaceEntityTypeV2
import saien.someday.sync.causality.v2.WorkspaceEntityValidatorV2
import saien.someday.sync.causality.v2.WorkspaceEntityWireCodecV2
import saien.someday.sync.causality.v2.WorkspaceImmutablePutResultV2
import saien.someday.sync.causality.v2.WorkspaceLocalCommitResultV2
import saien.someday.sync.causality.v2.WorkspacePreferencesV2
import saien.someday.sync.causality.v2.WorkspaceSyncControlCodecV2
import saien.someday.sync.causality.v2.WorkspaceSyncCoordinatorV2
import saien.someday.sync.causality.v2.WorkspaceSystemV2ContextProvider
import saien.someday.sync.causality.v2.CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2
import saien.someday.sync.causality.v2.WorkspaceWebDavSegmentRefV2
import saien.someday.sync.causality.v2.WorkspaceWebDavWriterManifestV2
import saien.someday.sync.causality.v2.WorkspaceObjectCipherV2
import saien.someday.sync.causality.v2.cborInt
import saien.someday.sync.causality.v2.cborText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WebDavSyncRemoteV2Test {
    @Test
    fun publishedCheckpointIsDiscoverableWithoutAnyRetiredNamespaceProbe() {
        val workspaceKey = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 13).toByte() })
        val transport = MemoryV2WebDavTransport()
        val fixture = fixture(WRITER_A, workspaceKey, transport)
        try {
            val prepared = checkpoint(workspaceKey)
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(fixture.local, workspaceKey, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(fixture.local, fixture.remote).publish(prepared),
            )
            assertEquals(prepared.pointerObject, fixture.remote.loadEpochPointer())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun exactWholeProductSegmentSurvivesLostResponseAndConvergesOnAnotherDevice() {
        val crypto = SodiumWorkspaceCrypto()
        val workspaceKey = crypto.workspaceKeyFromBytes(ByteArray(32) { (it + 83).toByte() })
        val transport = MemoryV2WebDavTransport()
        val leader = fixture(WRITER_A, workspaceKey, transport)
        val follower = fixture(WRITER_B, workspaceKey, transport)
        try {
            val prepared = checkpoint(workspaceKey)
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(leader.local, workspaceKey, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(leader.local, leader.remote).publish(prepared),
            )

            val leaderContext = context(leader, workspaceKey, WRITER_A)
            assertEquals(3, leaderContext.store.loadEntityKeys().size)
            val noteKey = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, NOTE_ID)
            val notebookKey = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTEBOOK, NOTEBOOK_ID)
            val preferencesKey = WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            )
            val noteHead = leaderContext.store.loadHeads(noteKey).single()
            val noteEdit = leaderContext.factory.createContentChild(
                noteHead,
                (noteHead.contentPayload as NoteContentV2).copy(
                    title = "WebDAV V2 retry-safe edit",
                    location = NoteLocationV2(31.23, 121.47, "Shanghai", 5.0, null, EDITED_AT),
                ),
                leaderContext.deviceActorId,
                EDITED_AT,
            )
            val notebookHead = leaderContext.store.loadHeads(notebookKey).single()
            val notebookEdit = leaderContext.factory.createContentChild(
                notebookHead,
                (notebookHead.contentPayload as NotebookContentV2).copy(title = "Retry-safe notebook"),
                leaderContext.deviceActorId,
                EDITED_AT,
            )
            val preferencesHead = leaderContext.store.loadHeads(preferencesKey).single()
            val preferencesEdit = leaderContext.factory.createContentChild(
                preferencesHead,
                (preferencesHead.contentPayload as WorkspacePreferencesV2).copy(markdownToolbarVisible = false),
                leaderContext.deviceActorId,
                EDITED_AT,
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                leaderContext.store.commitLocalMutations(
                    listOf(noteEdit, notebookEdit, preferencesEdit).map { version ->
                        LocalWorkspaceMutationV2(
                            SyncRemoteProfileV2.WEB_DAV.wireValue,
                            leaderContext.factory.newMutationId(),
                            version,
                            EDITED_AT,
                        )
                    },
                ),
            )
            assertEquals(3, leaderContext.store.loadPending(SyncRemoteProfileV2.WEB_DAV.wireValue).size)

            transport.failAfterSuccessfulPutOnce { request ->
                request.path.contains("/log-v2/epochs/") && request.path.contains("/logs/")
            }
            assertEquals(SyncCoordinatorStatusV2.FAILED, coordinator(leader, workspaceKey, WRITER_A).syncOnce().status)
            val openUnits = leader.protocolStore.loadOpenWorkspaceTransportUnits(
                SyncRemoteProfileV2.WEB_DAV.wireValue,
                prepared.descriptor.syncEpochId,
                WRITER_A,
            )
            assertEquals(1, openUnits.size)
            assertEquals(3, leaderContext.store.loadPending(SyncRemoteProfileV2.WEB_DAV.wireValue).size)
            assertTrue(listOf(noteEdit, notebookEdit, preferencesEdit).all { version ->
                openUnits.single().orderedMutationTuples.contains(version.versionId)
            })

            transport.stopFailing()
            assertEquals(SyncCoordinatorStatusV2.SUCCESS, coordinator(leader, workspaceKey, WRITER_A).syncOnce().status)
            assertEquals(
                1,
                transport.objectCount { it.contains("/log-v2/epochs/") && it.contains("/logs/") },
            )
            val discovered = leader.remote.discoveredDevices(prepared.descriptor.syncEpochId)
            assertEquals(1, discovered.size)
            assertEquals(WRITER_A, discovered.single().deviceId)
            assertEquals(SYNC_AT.toEpochMilliseconds(), discovered.single().firstSeenAtEpochMillis)
            assertEquals(SYNC_AT.toEpochMilliseconds(), discovered.single().lastSeenAtEpochMillis)
            assertTrue(discovered.single().isCurrentDevice)
            assertTrue(leaderContext.store.loadPending(SyncRemoteProfileV2.WEB_DAV.wireValue).isEmpty())

            assertEquals(SyncCoordinatorStatusV2.SUCCESS, coordinator(follower, workspaceKey, WRITER_B).syncOnce().status)
            val followerContext = context(follower, workspaceKey, WRITER_B)
            assertEquals(3, followerContext.store.loadEntityKeys().size)
            val projected = followerContext.store.loadProjection(noteKey)?.content as NoteContentV2
            assertEquals("WebDAV V2 retry-safe edit", projected.title)
            assertEquals("Shanghai", projected.location?.placeText)
            assertEquals(
                "Retry-safe notebook",
                (followerContext.store.loadProjection(notebookKey)?.content as NotebookContentV2).title,
            )
            assertEquals(
                NOTEBOOK_ID,
                (followerContext.store.loadProjection(preferencesKey)?.content as WorkspacePreferencesV2).defaultNotebookId,
            )
            assertFalse(
                (followerContext.store.loadProjection(preferencesKey)?.content as WorkspacePreferencesV2)
                    .markdownToolbarVisible,
            )
        } finally {
            leader.close()
            follower.close()
        }
    }

    @Test
    fun staleManifestEtagRetriesWithoutForkingOrDuplicatingTheWriterStream() {
        val workspaceKey = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 41).toByte() })
        val transport = MemoryV2WebDavTransport()
        val leader = fixture(WRITER_A, workspaceKey, transport)
        val follower = fixture(WRITER_B, workspaceKey, transport)
        try {
            val prepared = checkpoint(workspaceKey)
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(leader.local, workspaceKey, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(leader.local, leader.remote).publish(prepared),
            )
            val leaderContext = context(leader, workspaceKey, WRITER_A)
            val key = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, NOTE_ID)
            val firstBase = leaderContext.store.loadHeads(key).single()
            val first = leaderContext.factory.createContentChild(
                firstBase,
                (firstBase.contentPayload as NoteContentV2).copy(title = "First segment"),
                leaderContext.deviceActorId,
                EDITED_AT,
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                leaderContext.store.commitLocalMutations(listOf(
                    LocalWorkspaceMutationV2(
                        SyncRemoteProfileV2.WEB_DAV.wireValue,
                        leaderContext.factory.newMutationId(),
                        first,
                        EDITED_AT,
                    ),
                )),
            )
            assertEquals(SyncCoordinatorStatusV2.SUCCESS, coordinator(leader, workspaceKey, WRITER_A).syncOnce().status)

            val second = leaderContext.factory.createContentChild(
                leaderContext.store.loadHeads(key).single(),
                (leaderContext.store.loadHeads(key).single().contentPayload as NoteContentV2).copy(title = "Second segment"),
                leaderContext.deviceActorId,
                SYNC_AT,
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                leaderContext.store.commitLocalMutations(listOf(
                    LocalWorkspaceMutationV2(
                        SyncRemoteProfileV2.WEB_DAV.wireValue,
                        leaderContext.factory.newMutationId(),
                        second,
                        SYNC_AT,
                    ),
                )),
            )
            transport.preconditionConflictOnce { request -> request.path.contains("/manifests/") }
            assertEquals(SyncCoordinatorStatusV2.SUCCESS, coordinator(leader, workspaceKey, WRITER_A).syncOnce().status)
            assertEquals(2, transport.objectCount { it.contains("/logs/") })
            assertEquals(1, transport.objectCount { it.contains("/manifests/") })

            assertEquals(SyncCoordinatorStatusV2.SUCCESS, coordinator(follower, workspaceKey, WRITER_B).syncOnce().status)
            assertEquals(
                "Second segment",
                (context(follower, workspaceKey, WRITER_B).store.loadProjection(key)?.content as NoteContentV2).title,
            )
        } finally {
            leader.close()
            follower.close()
        }
    }

    @Test
    fun authenticatedManifestGapHashBreakForkAndMissingSegmentFailClosed() {
        val workspaceKey = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 55).toByte() })
        val transport = MemoryV2WebDavTransport()
        val fixture = fixture(WRITER_A, workspaceKey, transport)
        try {
            val prepared = checkpoint(workspaceKey)
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(fixture.local, workspaceKey, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(fixture.local, fixture.remote).publish(prepared),
            )
            val epochId = prepared.descriptor.syncEpochId
            val materializer = CanonicalWorkspaceCausalityMaterializerV2(
                SyncEpochKeyDerivationV2().derive(workspaceKey, epochId),
            )
            val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
            val control = WorkspaceSyncControlCodecV2(cipher)
            val firstRef = WorkspaceWebDavSegmentRefV2(
                1,
                SEGMENT,
                CONTROL_DIGEST_ONE,
                null,
                1,
                1,
                CREATED_AT,
            )
            val secondRef = WorkspaceWebDavSegmentRefV2(
                2,
                SEGMENT_TWO,
                CONTROL_DIGEST_TWO,
                CONTROL_DIGEST_ONE,
                1,
                1,
                EDITED_AT,
            )
            val valid = control.encodeWriterManifest(
                WorkspaceWebDavWriterManifestV2(
                    syncEpochId = epochId,
                    writerDeviceId = WRITER_A,
                    previousManifestDigest = null,
                    segments = listOf(firstRef, secondRef),
                ),
                WRITER_A,
            )
            val manifestPath = WebDavPathResolver("/someday-v2-test/").v2LogManifest(epochId, WRITER_A)

            fun malformed(segmentIndex: Int, field: String, replacement: CborValueV2) = run {
                val plaintext = assertIs<EncryptedWorkspaceObjectDecodeResultV2.Decoded>(cipher.decrypt(valid)).plaintext
                val root = DeterministicCborV2.decode(plaintext) as CborValueV2.Map
                val mutated = CborValueV2.Map(root.entries.map { (key, value) ->
                    if ((key as? CborValueV2.TextString)?.value != "segments") return@map key to value
                    val segments = value as CborValueV2.Array
                    key to CborValueV2.Array(segments.values.mapIndexed { index, segment ->
                        if (index != segmentIndex) return@mapIndexed segment
                        val map = segment as CborValueV2.Map
                        CborValueV2.Map(map.entries.map { (segmentKey, segmentValue) ->
                            if ((segmentKey as? CborValueV2.TextString)?.value == field) {
                                segmentKey to replacement
                            } else {
                                segmentKey to segmentValue
                            }
                        })
                    })
                })
                cipher.encryptControl(
                    epochId,
                    "webdav_writer_manifest_v2",
                    "writer-manifest:$WRITER_A",
                    WRITER_A,
                    DeterministicCborV2.encode(mutated),
                )
            }

            listOf(
                malformed(1, "ordinal", cborInt(3)),
                malformed(1, "previousSegmentDigest", cborText(CONTROL_DIGEST_THREE)),
            ).forEach { invalid ->
                transport.seedBytes(manifestPath, cipher.encodeJson(invalid).encodeToByteArray())
                assertFailsWith<IllegalStateException> { fixture.remote.pull(epochId, emptyMap(), 16) }
                assertNull(context(fixture, workspaceKey, WRITER_A).store.loadCursor(
                    SyncRemoteProfileV2.WEB_DAV.wireValue,
                    WRITER_A,
                ))
            }

            val forkedSecond = secondRef.copy(segmentId = SEGMENT_THREE, segmentDigest = CONTROL_DIGEST_THREE)
            val fork = control.encodeWriterManifest(
                WorkspaceWebDavWriterManifestV2(
                    syncEpochId = epochId,
                    writerDeviceId = WRITER_A,
                    previousManifestDigest = valid.objectDigest,
                    segments = listOf(firstRef, forkedSecond),
                ),
                WRITER_A,
            )
            transport.seedBytes(manifestPath, cipher.encodeJson(fork).encodeToByteArray())
            val forkResult = fixture.remote.pull(
                epochId,
                mapOf(WRITER_A to "2:$SEGMENT_TWO:$CONTROL_DIGEST_TWO"),
                16,
            )
            assertEquals("remote_rollback_detected", forkResult.safeErrorCode)

            transport.seedBytes(manifestPath, cipher.encodeJson(valid).encodeToByteArray())
            assertFailsWith<IllegalStateException> { fixture.remote.pull(epochId, emptyMap(), 16) }
            assertNull(context(fixture, workspaceKey, WRITER_A).store.loadCursor(
                SyncRemoteProfileV2.WEB_DAV.wireValue,
                WRITER_A,
            ))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun authenticatedManifestMustOccupyItsCanonicalWriterPathAndPullLimitIsBounded() {
        val workspaceKey = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 59).toByte() })
        val transport = MemoryV2WebDavTransport()
        val fixture = fixture(WRITER_A, workspaceKey, transport)
        try {
            val epochId = "70000000-0000-4000-8000-000000000001"
            val materializer = CanonicalWorkspaceCausalityMaterializerV2(
                SyncEpochKeyDerivationV2().derive(workspaceKey, epochId),
            )
            val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
            val control = WorkspaceSyncControlCodecV2(cipher)
            val manifest = control.encodeWriterManifest(
                WorkspaceWebDavWriterManifestV2(
                    syncEpochId = epochId,
                    writerDeviceId = WRITER_A,
                    previousManifestDigest = null,
                    segments = emptyList(),
                ),
                WRITER_A,
            )
            val wrongPath = WebDavPathResolver("/someday-v2-test/").v2LogManifest(epochId, WRITER_B)
            transport.seedBytes(wrongPath, cipher.encodeJson(manifest).encodeToByteArray())

            assertFailsWith<IllegalStateException> { fixture.remote.pull(epochId, emptyMap(), 16) }
            assertFailsWith<IllegalStateException> { fixture.remote.epochFrontiers(epochId) }
            assertFailsWith<IllegalArgumentException> { fixture.remote.pull(epochId, emptyMap(), 0) }
            assertFailsWith<IllegalArgumentException> { fixture.remote.pull(epochId, emptyMap(), 257) }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun repairReplicasDeduplicateSemanticObjectsRejectCorruptionAndBoundFlooding() {
        val workspaceKey = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 67).toByte() })
        val transport = MemoryV2WebDavTransport()
        val fixture = fixture(WRITER_A, workspaceKey, transport)
        try {
            val prepared = checkpoint(workspaceKey)
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(fixture.local, workspaceKey, WRITER_A).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(fixture.local, fixture.remote).publish(prepared),
            )
            val epochId = prepared.descriptor.syncEpochId
            val materializer = CanonicalWorkspaceCausalityMaterializerV2(
                SyncEpochKeyDerivationV2().derive(workspaceKey, epochId),
            )
            val validator = WorkspaceEntityValidatorV2(materializer)
            val wire = WorkspaceEntityWireCodecV2(materializer, validator)
            val cipher = WorkspaceObjectCipherV2(workspaceKey, materializer)
            val root = context(fixture, workspaceKey, WRITER_A).store.loadHeads(
                WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, NOTE_ID),
            ).single()
            val plaintext = wire.encode(root)
            val original = cipher.encryptEntity(root, REPAIR_MUTATION, WRITER_A, plaintext)
            val firstReplica = cipher.reencryptReplica(original, WRITER_A, plaintext)
            val secondReplica = cipher.reencryptReplica(original, WRITER_A, plaintext)
            assertNotEquals(firstReplica.ciphertextDigest, secondReplica.ciphertextDigest)
            assertIs<WorkspaceImmutablePutResultV2.Stored>(fixture.remote.publishRepairReplica(firstReplica))
            val replay = assertIs<WorkspaceImmutablePutResultV2.Stored>(
                fixture.remote.publishRepairReplica(secondReplica),
            )
            assertTrue(replay.idempotentReplay)
            assertEquals(1, fixture.remote.fetchRepairReplicas(epochId, root.versionId, root.objectDigest).size)

            val repairPath = WebDavPathResolver("/someday-v2-test/").v2RepairReplica(
                epochId,
                root.versionId,
                WRITER_A,
            )
            transport.seedBytes(
                repairPath,
                cipher.encodeJson(secondReplica.copy(objectDigest = "od2:hmac-sha256:${"44".repeat(32)}")).encodeToByteArray(),
            )
            assertFailsWith<IllegalStateException> {
                fixture.remote.fetchRepairReplicas(epochId, root.versionId, root.objectDigest)
            }

            repeat(65) { index ->
                val writer = "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                transport.seedObject(
                    WebDavPathResolver("/someday-v2-test/").v2RepairReplica(epochId, root.versionId, writer),
                )
            }
            assertFailsWith<IllegalStateException> {
                fixture.remote.fetchRepairReplicas(epochId, root.versionId, root.objectDigest)
            }
            assertNull(context(fixture, workspaceKey, WRITER_A).store.loadCursor(
                SyncRemoteProfileV2.WEB_DAV.wireValue,
                WRITER_A,
            ))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun writerManifestBoundRequiresRolloverWithoutTruncation() {
        assertFalse(workspaceWebDavManifestRequiresEpochRolloverV2(MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2))
        assertTrue(workspaceWebDavManifestRequiresEpochRolloverV2(MAX_CHECKPOINT_CHUNK_PLAINTEXT_SYSTEM_V2 + 1))
    }

    private fun checkpoint(workspaceKey: WorkspaceMasterKey) = WorkspaceCheckpointBuilderV2(
        workspaceKey,
        WRITER_A,
        SequentialIdsV2(),
    ).build(
        remoteProfile = SyncRemoteProfileV2.WEB_DAV.wireValue,
        sourceHeads = listOf(
            WorkspaceCheckpointSourceHeadV2(
                WorkspaceEntityTypeV2.NOTE,
                NOTE_ID,
                NoteContentV2(
                    NOTEBOOK_ID,
                    "Root",
                    "Body",
                    CREATED_AT,
                    "Asia/Shanghai",
                    NoteLocationV2(31.2, 121.4, "Initial place", 4.0, null, CREATED_AT),
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
                NotebookContentV2("Journal", 0, CREATED_AT),
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
        ).sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2),
        createdAt = CREATED_AT,
    )

    private fun fixture(
        writerId: String,
        workspaceKey: WorkspaceMasterKey,
        transport: MemoryV2WebDavTransport,
    ): DeviceFixture {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        val database = SomedayDatabase(driver)
        val local = SqlDelightLocalDataRepository(database, "webdav-v2-$writerId", clock = { SYNC_AT })
        val protocolStore = SqlDelightSyncProtocolStoreV2(database)
        val remote = WorkspaceWebDavSyncRemoteV2(
            client = WebDavClient(
                WebDavConfiguration(
                    endpoint = "https://dav.invalid",
                    username = "alice",
                    password = "redacted-test-secret",
                    appDirectory = "/someday-v2-test/",
                ),
                transport,
            ),
            workspaceKey = workspaceKey,
            localWriterDeviceId = writerId,
            protocolStore = protocolStore,
            clock = { SYNC_AT },
        )
        return DeviceFixture(driver, local, protocolStore, remote)
    }

    private fun coordinator(
        fixture: DeviceFixture,
        workspaceKey: WorkspaceMasterKey,
        writerId: String,
    ) = WorkspaceSyncCoordinatorV2(
        fixture.local,
        workspaceKey,
        writerId,
        fixture.remote,
        fixture.protocolStore,
        clock = { SYNC_AT },
    )

    private fun context(
        fixture: DeviceFixture,
        workspaceKey: WorkspaceMasterKey,
        writerId: String,
    ) = WorkspaceSystemV2ContextProvider(
        fixture.local,
        { workspaceKey },
        { writerId },
        { SyncRemoteProfileV2.WEB_DAV.wireValue },
    ).requireActive()

    private data class DeviceFixture(
        val driver: app.cash.sqldelight.db.SqlDriver,
        val local: SqlDelightLocalDataRepository,
        val protocolStore: SqlDelightSyncProtocolStoreV2,
        val remote: WorkspaceWebDavSyncRemoteV2,
    ) {
        fun close() = driver.close()
    }

    private companion object {
        const val WRITER_A = "00000000-0000-4000-8000-0000000000a1"
        const val WRITER_B = "00000000-0000-4000-8000-0000000000b2"
        const val NOTE_ID = "30000000-0000-4000-8000-000000000001"
        const val NOTEBOOK_ID = "20000000-0000-4000-8000-000000000001"
        const val SEGMENT = "50000000-0000-4000-8000-000000000001"
        const val SEGMENT_TWO = "50000000-0000-4000-8000-000000000002"
        const val SEGMENT_THREE = "50000000-0000-4000-8000-000000000003"
        const val REPAIR_MUTATION = "60000000-0000-4000-8000-000000000001"
        val CONTROL_DIGEST_ONE = "cd2:hmac-sha256:${"11".repeat(32)}"
        val CONTROL_DIGEST_TWO = "cd2:hmac-sha256:${"22".repeat(32)}"
        val CONTROL_DIGEST_THREE = "cd2:hmac-sha256:${"33".repeat(32)}"
        val CREATED_AT = Instant.parse("2026-07-19T00:00:00Z")
        val EDITED_AT = Instant.parse("2026-07-19T01:00:00Z")
        val SYNC_AT = Instant.parse("2026-07-19T02:00:00Z")
    }
}

private class SequentialIdsV2 : CausalityIdGeneratorV2 {
    private var value = 1L
    override fun newId(): String = "90000000-0000-4000-8000-${(value++).toString().padStart(12, '0')}"
}

private class MemoryV2WebDavTransport : WebDavTransport {
    private val objects = mutableMapOf<String, Stored>()
    private val collections = mutableSetOf<String>()
    private var nextEtag = 1
    private var failurePredicate: ((WebDavRequest) -> Boolean)? = null
    private var failureAlreadyUsed = false
    private var preconditionPredicate: ((WebDavRequest) -> Boolean)? = null
    private var preconditionAlreadyUsed = false

    fun objectCount(predicate: (String) -> Boolean): Int = objects.keys.count(predicate)

    fun seedObject(path: String) {
        seedBytes(path, byteArrayOf(1))
    }

    fun seedBytes(path: String, body: ByteArray) {
        val normalized = path.trimStart('/')
        normalized.substringBeforeLast('/').split('/').runningFold("") { parent, segment ->
            if (parent.isBlank()) "$segment/" else "$parent$segment/"
        }.drop(1).forEach(collections::add)
        objects[normalized] = Stored("\"seed-${nextEtag++}\"", body)
    }

    fun failAfterSuccessfulPutOnce(predicate: (WebDavRequest) -> Boolean) {
        failurePredicate = predicate
        failureAlreadyUsed = false
    }

    fun stopFailing() {
        failurePredicate = null
        failureAlreadyUsed = false
    }

    fun preconditionConflictOnce(predicate: (WebDavRequest) -> Boolean) {
        preconditionPredicate = predicate
        preconditionAlreadyUsed = false
    }

    override fun execute(configuration: WebDavConfiguration, request: WebDavRequest): WebDavResponse {
        if (request.method == "PUT" && !preconditionAlreadyUsed && preconditionPredicate?.invoke(request) == true) {
            preconditionAlreadyUsed = true
            return WebDavResponse(412)
        }
        val response = when (request.method) {
            "MKCOL" -> WebDavResponse(status = if (collections.add(request.path.asCollectionPath())) 201 else 405)
            "PROPFIND" -> WebDavResponse(
                status = 207,
                body = multistatus(request.path.asCollectionPath()).encodeToByteArray(),
            )
            "GET" -> objects[request.path]?.let { stored ->
                WebDavResponse(200, mapOf("ETag" to stored.etag), stored.body)
            } ?: WebDavResponse(404)
            "PUT" -> put(request)
            else -> WebDavResponse(405)
        }
        val shouldFail = request.method == "PUT" &&
            response.status in setOf(200, 201, 204) &&
            !failureAlreadyUsed &&
            failurePredicate?.invoke(request) == true
        if (shouldFail) {
            failureAlreadyUsed = true
            error("Injected WebDAV response loss after durable PUT.")
        }
        return response
    }

    private fun put(request: WebDavRequest): WebDavResponse {
        val existing = objects[request.path]
        if (request.headers["If-None-Match"] == "*" && existing != null) return WebDavResponse(412)
        request.headers["If-Match"]?.let { expected ->
            if (existing?.etag != expected) return WebDavResponse(412)
        }
        val etag = "\"etag-${nextEtag++}\""
        objects[request.path] = Stored(etag, request.body ?: ByteArray(0))
        return WebDavResponse(if (existing == null) 201 else 204, mapOf("ETag" to etag))
    }

    private fun multistatus(root: String): String {
        val directCollections = collections.filter { it != root && it.parentCollection() == root }
        val directObjects = objects.filterKeys { it.parentCollection() == root }
        val responses = buildString {
            append(responseXml(root, null, true))
            directCollections.forEach { append(responseXml(it, null, true)) }
            directObjects.forEach { (path, stored) -> append(responseXml(path, stored.etag, false)) }
        }
        return """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">$responses</D:multistatus>"""
    }

    private fun responseXml(path: String, etag: String?, collection: Boolean): String =
        """
        <D:response>
          <D:href>/$path</D:href>
          <D:propstat><D:prop>
            ${etag?.let { "<D:getetag>$it</D:getetag>" } ?: ""}
            <D:resourcetype>${if (collection) "<D:collection/>" else ""}</D:resourcetype>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
        </D:response>
        """.trimIndent()

    private fun String.asCollectionPath(): String =
        trimStart('/').let { if (it.endsWith('/')) it else "$it/" }

    private fun String.parentCollection(): String =
        trimEnd('/').substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" }

    private data class Stored(val etag: String, val body: ByteArray)
}
