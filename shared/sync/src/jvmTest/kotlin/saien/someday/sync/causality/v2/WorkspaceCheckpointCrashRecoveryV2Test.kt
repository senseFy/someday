@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.sync.causality.v2.testkit.FileBackedSyncDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkspaceCheckpointCrashRecoveryV2Test {
    @Test
    fun multiChunkCheckpointResumesExactCiphertextsAfterEveryPublicationBoundary() {
        CheckpointFailureStage.entries.forEachIndexed { index, stage ->
            val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(
                ByteArray(32) { byteIndex -> (byteIndex + 70 + index).toByte() },
            )
            val device = FileBackedSyncDevice.create(WRITER) { T0 }
            val remote = InMemoryWorkspaceSyncRemoteV2()
            try {
                val prepared = multiChunkCheckpoint(key)
                assertTrue(prepared.chunks.size > 1, stage.name)
                assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                    WorkspaceCheckpointPersistenceV2(device.local, key, WRITER).persist(prepared),
                    stage.name,
                )

                val failingRemote = FailOnceCheckpointRemote(remote, stage)
                val interrupted = runCatching {
                    WorkspaceCheckpointPublisherV2(
                        localRepository = device.local,
                        remote = failingRemote,
                        beforeEntityPublication = {},
                        chunkPublishParallelism = 1,
                    ).publish(prepared)
                }

                when (stage) {
                    CheckpointFailureStage.CHUNK,
                    CheckpointFailureStage.MANIFEST,
                    -> assertIs<WorkspaceCheckpointPublishResultV2.Rejected>(
                        interrupted.getOrThrow(),
                        stage.name,
                    )
                    CheckpointFailureStage.POINTER -> assertIs<WorkspaceCheckpointPublishResultV2.LostRace>(
                        interrupted.getOrThrow(),
                        stage.name,
                    )
                    CheckpointFailureStage.LOCAL_ACTIVATION -> assertTrue(
                        interrupted.isFailure,
                        "The injected crash must happen after the remote pointer commit.",
                    )
                }
                assertNull(device.protocolStore().loadActiveEpoch(remote.remoteProfile), stage.name)
                assertEquals(
                    stage == CheckpointFailureStage.LOCAL_ACTIVATION,
                    remote.loadEpochPointer() != null,
                    "${stage.name}: only the local-activation crash occurs after pointer commit",
                )
                if (stage == CheckpointFailureStage.CHUNK) {
                    assertEquals(
                        1,
                        failingRemote.successfulChunkPuts,
                        "The first chunk must reach the remote before the second chunk fails.",
                    )
                    assertTrue(
                        failingRemote.successfulChunkPuts < prepared.chunks.size,
                        "The interrupted remote must contain only a strict subset of checkpoint chunks.",
                    )
                    assertTrue(
                        remote.hasCheckpointDraftForTest(prepared.descriptor.syncEpochId),
                        "The partial immutable checkpoint draft must actually exist remotely.",
                    )
                }

                device.reopen()
                val recovered = assertIs<WorkspacePreparedCheckpointLoadResultV2.Loaded>(
                    WorkspacePreparedCheckpointRecoveryV2(device.local, key).loadCompatible(
                        remote.remoteProfile,
                        remote.loadEpochPointer(),
                    ),
                    stage.name,
                ).prepared
                assertEquals(prepared.pointerObject, recovered.pointerObject, stage.name)
                assertEquals(
                    prepared.chunks.map { it.encryptedObject },
                    recovered.chunks.map { it.encryptedObject },
                    "${stage.name}: recovery must reuse exact immutable ciphertexts",
                )

                val published = assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                    WorkspaceCheckpointPublisherV2(
                        localRepository = device.local,
                        remote = remote,
                        beforeEntityPublication = {},
                        chunkPublishParallelism = 1,
                    ).publish(recovered),
                    stage.name,
                )

                assertEquals(
                    stage == CheckpointFailureStage.LOCAL_ACTIVATION,
                    published.idempotentReplay,
                    "${stage.name}: only an already-committed pointer is an idempotent CAS replay",
                )
                assertEquals(prepared.pointerObject, remote.loadEpochPointer(), stage.name)
                assertEquals(
                    prepared.descriptor.syncEpochId,
                    device.protocolStore().loadActiveEpoch(remote.remoteProfile)?.descriptor?.syncEpochId,
                    stage.name,
                )
                assertEquals(SOURCE_COUNT, device.requireActiveContext(key).store.loadEntityKeys().size, stage.name)
            } finally {
                device.close()
            }
        }
    }

    private fun multiChunkCheckpoint(
        key: saien.someday.data.crypto.WorkspaceMasterKey,
    ): PreparedWorkspaceEpochCheckpointV2 {
        val notebookSources = (1 until SOURCE_COUNT).map { index ->
            WorkspaceCheckpointSourceHeadV2(
                entityType = WorkspaceEntityTypeV2.NOTEBOOK,
                entityId = uuid("2", index),
                content = NotebookContentV2("Notebook $index", index.toLong(), T0),
                deletion = null,
                sourceProfile = "checkpoint-recovery-test",
                sourceEpoch = null,
                sourceWriterId = WRITER,
                sourceMutationId = null,
                sourceObjectId = uuid("3", index),
                sourceObjectDigest = "checkpoint-source-$index",
                sourceAuthoredAt = T0,
            )
        }
        val preferences = WorkspaceCheckpointSourceHeadV2(
            entityType = WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            entityId = WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            content = WorkspacePreferencesV2(),
            deletion = null,
            sourceProfile = "checkpoint-recovery-test",
            sourceEpoch = null,
            sourceWriterId = WRITER,
            sourceMutationId = null,
            sourceObjectId = uuid("4", SOURCE_COUNT),
            sourceObjectDigest = "checkpoint-preferences-source",
            sourceAuthoredAt = T0,
        )
        return WorkspaceCheckpointBuilderV2(key, WRITER).build(
            remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
            sourceHeads = (notebookSources + preferences).sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2),
            createdAt = T0,
        )
    }

    private fun uuid(prefix: String, index: Int): String =
        "${prefix}0000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private enum class CheckpointFailureStage {
        CHUNK,
        MANIFEST,
        POINTER,
        LOCAL_ACTIVATION,
    }

    private class FailOnceCheckpointRemote(
        private val delegate: WorkspaceSyncRemoteV2,
        private val stage: CheckpointFailureStage,
    ) : WorkspaceSyncRemoteV2 by delegate {
        private var failed = false
        private var chunkPutCalls = 0
        var successfulChunkPuts = 0
            private set

        override fun putCheckpointChunk(
            descriptor: SyncEpochDescriptorV2,
            ref: WorkspaceCheckpointChunkRefV2,
            chunk: EncryptedWorkspaceObjectV2,
        ): WorkspaceImmutablePutResultV2 {
            chunkPutCalls += 1
            if (stage == CheckpointFailureStage.CHUNK && !failed && chunkPutCalls == 2) {
                failed = true
                return WorkspaceImmutablePutResultV2.Rejected(
                    "injected_chunk_failure",
                    "Injected checkpoint chunk failure.",
                )
            }
            return delegate.putCheckpointChunk(descriptor, ref, chunk).also { result ->
                if (result is WorkspaceImmutablePutResultV2.Stored) successfulChunkPuts += 1
            }
        }

        override fun putCheckpointManifest(
            descriptor: SyncEpochDescriptorV2,
            manifest: EncryptedWorkspaceObjectV2,
        ): WorkspaceImmutablePutResultV2 {
            if (stage == CheckpointFailureStage.MANIFEST && !failed) {
                failed = true
                return WorkspaceImmutablePutResultV2.Rejected(
                    "injected_manifest_failure",
                    "Injected checkpoint manifest failure.",
                )
            }
            return delegate.putCheckpointManifest(descriptor, manifest)
        }

        override fun compareAndSetEpochPointer(
            descriptor: SyncEpochDescriptorV2,
            expectedCurrentDigest: String?,
            pointer: EncryptedWorkspaceObjectV2,
        ): WorkspacePointerPublishResultV2 {
            if (stage == CheckpointFailureStage.POINTER && !failed) {
                failed = true
                return WorkspacePointerPublishResultV2.CompareAndSetFailed(delegate.loadEpochPointer())
            }
            val result = delegate.compareAndSetEpochPointer(descriptor, expectedCurrentDigest, pointer)
            if (stage == CheckpointFailureStage.LOCAL_ACTIVATION && !failed) {
                failed = true
                error("Injected process crash before local checkpoint activation.")
            }
            return result
        }
    }

    private companion object {
        const val WRITER = "10000000-0000-4000-8000-000000000011"
        const val SOURCE_COUNT = MAX_CHECKPOINT_CHUNK_OBJECTS_SYSTEM_V2 + 2
        val T0 = Instant.parse("2026-08-21T00:00:00Z")
    }
}
