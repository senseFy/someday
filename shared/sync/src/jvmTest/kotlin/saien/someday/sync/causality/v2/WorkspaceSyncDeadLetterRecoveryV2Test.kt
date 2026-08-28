@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.sync.causality.v2.testkit.FileBackedSyncDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkspaceSyncDeadLetterRecoveryV2Test {
    @Test
    fun persistentIntegrityDeadLetterSurvivesReopenAndKeepsEpochBlocked() {
        val key = workspaceKey(3)
        val remote = InMemoryWorkspaceSyncRemoteV2()
        val publisher = FileBackedSyncDevice.create(WRITER_A) { T0 }
        val consumer = FileBackedSyncDevice.create(WRITER_B) { T1 }
        try {
            val checkpoint = persistAndPublishMinimalCheckpoint(publisher, key, remote)
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                consumer.coordinator(key, remote).syncOnce().status,
            )

            val context = publisher.requireActiveContext(key)
            val root = context.store.loadHeads(PREFERENCES_KEY).single()
            val changed = context.factory.createContentChild(
                root,
                (root.contentPayload as WorkspacePreferencesV2).copy(theme = WorkspaceThemeV2.DARK),
                context.deviceActorId,
                T1,
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                context.store.commitLocalMutations(listOf(
                    LocalWorkspaceMutationV2(
                        remote.remoteProfile,
                        context.factory.newMutationId(),
                        changed,
                        T1,
                    ),
                )),
            )
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                publisher.coordinator(key, remote).syncOnce().status,
            )

            remote.faults.corruptNextPulledObject = true
            val blocked = consumer.coordinator(key, remote).syncOnce()

            assertEquals(SyncCoordinatorStatusV2.BLOCKED, blocked.status)
            val recorded = consumer.protocolStore()
                .loadUnresolvedDeadLetters(remote.remoteProfile, checkpoint.descriptor.syncEpochId)
                .single()
            assertEquals(SyncDeadLetterFailureClassV2.PERSISTENT_INTEGRITY, recorded.input.failureClass)

            consumer.reopen()
            assertEquals(
                recorded,
                consumer.protocolStore()
                    .loadUnresolvedDeadLetters(remote.remoteProfile, checkpoint.descriptor.syncEpochId)
                    .single(),
            )
            val afterRestart = consumer.coordinator(key, remote).syncOnce()
            assertEquals(SyncCoordinatorStatusV2.BLOCKED, afterRestart.status)
            assertEquals(recorded.input.safeErrorCode, afterRestart.safeErrorCode)
        } finally {
            publisher.close()
            consumer.close()
        }
    }

    @Test
    fun retryableDependencyDeadLetterSurvivesReopenAndDeletesItselfWhenParentArrives() {
        val key = workspaceKey(17)
        val baseRemote = InMemoryWorkspaceSyncRemoteV2()
        val publisher = FileBackedSyncDevice.create(WRITER_A) { T0 }
        val consumer = FileBackedSyncDevice.create(WRITER_B) { T1 }
        try {
            val checkpoint = persistAndPublishMinimalCheckpoint(publisher, key, baseRemote)
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                consumer.coordinator(key, baseRemote).syncOnce().status,
            )
            val source = publisher.requireActiveContext(key)
            val root = source.store.loadHeads(PREFERENCES_KEY).single()
            val parent = source.factory.createContentChild(
                root,
                (root.contentPayload as WorkspacePreferencesV2).copy(theme = WorkspaceThemeV2.DARK),
                source.deviceActorId,
                T1,
            )
            val child = source.factory.createContentChild(
                parent,
                (parent.contentPayload as WorkspacePreferencesV2).copy(previewByDefault = true),
                source.deviceActorId,
                T2,
            )
            val parentOuter = source.cipher.encryptEntity(
                parent,
                PARENT_MUTATION_ID,
                WRITER_A,
                source.wireCodec.encode(parent),
            )
            val childOuter = source.cipher.encryptEntity(
                child,
                CHILD_MUTATION_ID,
                WRITER_A,
                source.wireCodec.encode(child),
            )
            val dependencyRemote = ParentArrivesOnRetryRemote(
                baseRemote,
                checkpoint.descriptor.syncEpochId,
                parentOuter,
                childOuter,
            )

            val missingParent = consumer.coordinator(key, dependencyRemote).syncOnce()

            assertEquals(SyncCoordinatorStatusV2.BLOCKED, missingParent.status)
            assertEquals("missing_parent", missingParent.safeErrorCode)
            val recorded = consumer.protocolStore()
                .loadUnresolvedDeadLetters(baseRemote.remoteProfile, checkpoint.descriptor.syncEpochId)
                .single()
            assertEquals(SyncDeadLetterFailureClassV2.RETRYABLE_DEPENDENCY, recorded.input.failureClass)
            assertEquals(CHILD_UNIT_ID, recorded.input.unitId)

            consumer.reopen()
            assertEquals(
                recorded,
                consumer.protocolStore()
                    .loadUnresolvedDeadLetters(baseRemote.remoteProfile, checkpoint.descriptor.syncEpochId)
                    .single(),
            )
            val recovered = consumer.coordinator(key, dependencyRemote).syncOnce()

            assertEquals(SyncCoordinatorStatusV2.SUCCESS, recovered.status)
            assertTrue(
                consumer.protocolStore()
                    .loadUnresolvedDeadLetters(baseRemote.remoteProfile, checkpoint.descriptor.syncEpochId)
                    .isEmpty(),
            )
            assertEquals(
                child.versionId,
                consumer.requireActiveContext(key).store.loadProjection(PREFERENCES_KEY)?.preferredHeadVersionId,
            )
        } finally {
            publisher.close()
            consumer.close()
        }
    }

    private fun persistAndPublishMinimalCheckpoint(
        publisher: FileBackedSyncDevice,
        key: WorkspaceMasterKey,
        remote: InMemoryWorkspaceSyncRemoteV2,
    ): PreparedWorkspaceEpochCheckpointV2 {
        val checkpoint = WorkspaceCheckpointBuilderV2(key, WRITER_A).build(
            remoteProfile = remote.remoteProfile,
            sourceHeads = listOf(
                WorkspaceCheckpointSourceHeadV2(
                    entityType = WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    entityId = WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    content = WorkspacePreferencesV2(),
                    deletion = null,
                    sourceProfile = "dead-letter-recovery-test",
                    sourceEpoch = null,
                    sourceWriterId = WRITER_A,
                    sourceMutationId = null,
                    sourceObjectId = SOURCE_OBJECT_ID,
                    sourceObjectDigest = "dead-letter-source",
                    sourceAuthoredAt = T0,
                ),
            ),
            createdAt = T0,
        )
        assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
            WorkspaceCheckpointPersistenceV2(publisher.local, key, WRITER_A).persist(checkpoint),
        )
        assertIs<WorkspaceCheckpointPublishResultV2.Published>(
            WorkspaceCheckpointPublisherV2(publisher.local, remote, {}).publish(checkpoint),
        )
        return checkpoint
    }

    private fun workspaceKey(seed: Int): WorkspaceMasterKey =
        SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + seed).toByte() })

    private class ParentArrivesOnRetryRemote(
        private val delegate: WorkspaceSyncRemoteV2,
        private val epochId: String,
        private val parent: EncryptedWorkspaceObjectV2,
        private val child: EncryptedWorkspaceObjectV2,
    ) : WorkspaceSyncRemoteV2 by delegate {
        private var pullCount = 0

        override fun pull(
            syncEpochId: String,
            cursors: Map<String, String?>,
            limit: Int,
        ): WorkspaceSyncPullResultV2 {
            require(syncEpochId == epochId)
            return when (pullCount++) {
                0 -> WorkspaceSyncPullResultV2(
                    units = listOf(childUnit()),
                    frontierStable = true,
                )
                1 -> WorkspaceSyncPullResultV2(
                    units = listOf(
                        WorkspaceEncryptedCursorUnitV2(
                            syncEpochId = epochId,
                            streamId = PARENT_STREAM,
                            expectedCursorValue = cursors[PARENT_STREAM],
                            nextCursorValue = "1",
                            unitId = PARENT_UNIT_ID,
                            unitDigest = PARENT_UNIT_DIGEST,
                            objects = listOf(parent),
                        ),
                        childUnit(),
                    ),
                    frontierStable = true,
                )
                else -> WorkspaceSyncPullResultV2(emptyList(), frontierStable = true)
            }
        }

        private fun childUnit() = WorkspaceEncryptedCursorUnitV2(
            syncEpochId = epochId,
            streamId = CHILD_STREAM,
            expectedCursorValue = null,
            nextCursorValue = "1",
            unitId = CHILD_UNIT_ID,
            unitDigest = CHILD_UNIT_DIGEST,
            objects = listOf(child),
        )
    }

    private companion object {
        const val WRITER_A = "10000000-0000-4000-8000-000000000021"
        const val WRITER_B = "10000000-0000-4000-8000-000000000022"
        const val SOURCE_OBJECT_ID = "30000000-0000-4000-8000-000000000021"
        const val PARENT_MUTATION_ID = "40000000-0000-4000-8000-000000000021"
        const val CHILD_MUTATION_ID = "40000000-0000-4000-8000-000000000022"
        const val PARENT_STREAM = "dependency-parent"
        const val CHILD_STREAM = "dependency-child"
        const val PARENT_UNIT_ID = "parent-unit"
        const val CHILD_UNIT_ID = "child-unit"
        const val PARENT_UNIT_DIGEST = "parent-unit-digest"
        const val CHILD_UNIT_DIGEST = "child-unit-digest"
        val PREFERENCES_KEY = WorkspaceEntityKeyV2(
            WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            WORKSPACE_PREFERENCES_ENTITY_ID_V2,
        )
        val T0 = Instant.parse("2026-08-22T00:00:00Z")
        val T1 = Instant.parse("2026-08-22T01:00:00Z")
        val T2 = Instant.parse("2026-08-22T02:00:00Z")
    }
}
