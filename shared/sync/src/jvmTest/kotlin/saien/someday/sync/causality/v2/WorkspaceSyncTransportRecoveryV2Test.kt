@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.sync.causality.v2.testkit.FileBackedSyncDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkspaceSyncTransportRecoveryV2Test {
    @Test
    fun pullFailureDoesNotAdvanceDurableCursorOrProjectionUntilRetryAfterReopen() {
        val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(
            ByteArray(32) { byteIndex -> (byteIndex + 90).toByte() },
        )
        val remote = InMemoryWorkspaceSyncRemoteV2()
        val publisher = FileBackedSyncDevice.create(WRITER_A) { T0 }
        val consumer = FileBackedSyncDevice.create(WRITER_B) { T1 }
        try {
            val checkpoint = minimalCheckpoint(key)
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(publisher.local, key, WRITER_A).persist(checkpoint),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(publisher.local, remote, {}).publish(checkpoint),
            )
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                consumer.coordinator(key, remote).syncOnce().status,
            )

            val preferenceKey = WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                WORKSPACE_PREFERENCES_ENTITY_ID_V2,
            )
            val consumerBefore = consumer.requireActiveContext(key)
            val cursorBefore = consumerBefore.store.loadCursor(remote.remoteProfile, GLOBAL_STREAM)?.cursorValue
            val projectionBefore = assertNotNull(
                consumerBefore.store.loadProjection(preferenceKey),
            ).preferredHeadVersionId
            val headsBefore = consumerBefore.store.loadHeads(preferenceKey).map { it.versionId }.sorted()

            val publisherContext = publisher.requireActiveContext(key)
            val root = publisherContext.store.loadHeads(preferenceKey).single()
            val changed = publisherContext.factory.createContentChild(
                root,
                (root.contentPayload as WorkspacePreferencesV2).copy(
                    markdownToolbarVisible = false,
                    previewByDefault = true,
                ),
                publisherContext.deviceActorId,
                T1,
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                publisherContext.store.commitLocalMutations(listOf(
                    LocalWorkspaceMutationV2(
                        remote.remoteProfile,
                        publisherContext.factory.newMutationId(),
                        changed,
                        T1,
                    ),
                )),
            )
            assertEquals(
                SyncCoordinatorStatusV2.SUCCESS,
                publisher.coordinator(key, remote).syncOnce().status,
            )
            assertEquals(1, remote.allChanges().size)
            val remoteCursor = assertNotNull(
                remote.epochFrontiers(checkpoint.descriptor.syncEpochId).single().cursorValue,
            )
            assertTrue(remoteCursor != cursorBefore, "The remote cursor must be ahead before pull fails.")
            assertTrue(changed.versionId != projectionBefore, "The pending remote version must change the projection.")

            remote.faults.failNextPull = true
            val interrupted = consumer.coordinator(key, remote).syncOnce()

            assertTrue(interrupted.status != SyncCoordinatorStatusV2.SUCCESS)
            val afterFailure = consumer.requireActiveContext(key)
            assertEquals(
                cursorBefore,
                afterFailure.store.loadCursor(remote.remoteProfile, GLOBAL_STREAM)?.cursorValue,
                "A failed pull must not advance the durable authenticated cursor.",
            )
            assertEquals(
                projectionBefore,
                afterFailure.store.loadProjection(preferenceKey)?.preferredHeadVersionId,
                "A failed pull must not change the durable projection.",
            )
            assertEquals(
                headsBefore,
                afterFailure.store.loadHeads(preferenceKey).map { it.versionId }.sorted(),
                "The remote version must not be applied when pull fails.",
            )
            assertTrue(changed.versionId !in afterFailure.store.loadHeads(preferenceKey).map { it.versionId })

            consumer.reopen()
            val afterReopen = consumer.requireActiveContext(key)
            assertEquals(cursorBefore, afterReopen.store.loadCursor(remote.remoteProfile, GLOBAL_STREAM)?.cursorValue)
            assertEquals(projectionBefore, afterReopen.store.loadProjection(preferenceKey)?.preferredHeadVersionId)
            assertEquals(headsBefore, afterReopen.store.loadHeads(preferenceKey).map { it.versionId }.sorted())

            val retried = consumer.coordinator(key, remote).syncOnce()

            assertEquals(SyncCoordinatorStatusV2.SUCCESS, retried.status)
            val recovered = consumer.requireActiveContext(key)
            assertEquals(
                remoteCursor,
                recovered.store.loadCursor(remote.remoteProfile, GLOBAL_STREAM)?.cursorValue,
                "Retry must persist the exact authenticated remote cursor.",
            )
            assertEquals(changed.versionId, recovered.store.loadProjection(preferenceKey)?.preferredHeadVersionId)
            assertEquals(listOf(changed.versionId), recovered.store.loadHeads(preferenceKey).map { it.versionId })
            assertEquals(1, remote.allChanges().size)
        } finally {
            publisher.close()
            consumer.close()
        }
    }

    @Test
    fun ambiguousPushFailuresKeepTheExactOutboxUntilRetryAcknowledgesIt() {
        val cases = listOf(
            TransportFaultCase("push failure before commit", remoteCommitted = false) {
                it.failNextPushBeforeCommit = true
            },
            TransportFaultCase("push failure after commit", remoteCommitted = true) {
                it.failNextPushAfterCommit = true
            },
            TransportFaultCase("dropped acknowledgement", remoteCommitted = true) {
                it.dropNextAcknowledgement = true
            },
            TransportFaultCase("corrupt acknowledgement", remoteCommitted = true) {
                it.corruptNextAcknowledgement = true
            },
        )

        cases.forEachIndexed { index, case ->
            val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(
                ByteArray(32) { byteIndex -> (byteIndex + 40 + index).toByte() },
            )
            val remote = InMemoryWorkspaceSyncRemoteV2()
            val publisher = FileBackedSyncDevice.create(WRITER_A) { T0 }
            val recovering = FileBackedSyncDevice.create(WRITER_B) { T1 }
            val observer = FileBackedSyncDevice.create(WRITER_C) { T2 }
            try {
                val checkpoint = minimalCheckpoint(key)
                assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                    WorkspaceCheckpointPersistenceV2(publisher.local, key, WRITER_A).persist(checkpoint),
                    case.name,
                )
                assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                    WorkspaceCheckpointPublisherV2(publisher.local, remote, {}).publish(checkpoint),
                    case.name,
                )
                assertEquals(
                    SyncCoordinatorStatusV2.SUCCESS,
                    recovering.coordinator(key, remote).syncOnce().status,
                    case.name,
                )

                val context = recovering.requireActiveContext(key)
                val preferenceKey = WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                )
                val root = context.store.loadHeads(preferenceKey).single()
                val changed = context.factory.createContentChild(
                    root,
                    (root.contentPayload as WorkspacePreferencesV2).copy(
                        markdownToolbarVisible = false,
                        previewByDefault = true,
                    ),
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
                    case.name,
                )
                assertEquals(1, context.store.loadPending(remote.remoteProfile).size, case.name)

                case.arm(remote.faults)
                val interrupted = recovering.coordinator(key, remote).syncOnce()

                assertTrue(interrupted.status != SyncCoordinatorStatusV2.SUCCESS, case.name)
                assertEquals(
                    1,
                    recovering.requireActiveContext(key).store.loadPending(remote.remoteProfile).size,
                    "${case.name}: an ambiguous or invalid acknowledgement must not clear the outbox",
                )
                assertEquals(
                    if (case.remoteCommitted) 1 else 0,
                    remote.allChanges().size,
                    "${case.name}: unexpected remote commit state",
                )

                recovering.reopen()
                val retried = recovering.coordinator(key, remote).syncOnce()

                assertEquals(SyncCoordinatorStatusV2.SUCCESS, retried.status, case.name)
                assertTrue(
                    recovering.requireActiveContext(key).store.loadPending(remote.remoteProfile).isEmpty(),
                    "${case.name}: exact replay acknowledgement must clear the durable outbox",
                )
                assertEquals(1, remote.allChanges().size, "${case.name}: retry must be idempotent")

                assertEquals(
                    SyncCoordinatorStatusV2.SUCCESS,
                    observer.coordinator(key, remote).syncOnce().status,
                    case.name,
                )
                assertEquals(
                    changed.versionId,
                    observer.requireActiveContext(key).store.loadProjection(preferenceKey)?.preferredHeadVersionId,
                    "${case.name}: a fresh device must converge to the acknowledged version",
                )
            } finally {
                publisher.close()
                recovering.close()
                observer.close()
            }
        }
    }

    private fun minimalCheckpoint(
        key: saien.someday.data.crypto.WorkspaceMasterKey,
    ): PreparedWorkspaceEpochCheckpointV2 = WorkspaceCheckpointBuilderV2(key, WRITER_A).build(
        remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
        sourceHeads = listOf(
            WorkspaceCheckpointSourceHeadV2(
                entityType = WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                entityId = WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                content = WorkspacePreferencesV2(),
                deletion = null,
                sourceProfile = "transport-recovery-test",
                sourceEpoch = null,
                sourceWriterId = WRITER_A,
                sourceMutationId = null,
                sourceObjectId = "30000000-0000-4000-8000-000000000001",
                sourceObjectDigest = "transport-recovery-source",
                sourceAuthoredAt = T0,
            ),
        ),
        createdAt = T0,
    )

    private data class TransportFaultCase(
        val name: String,
        val remoteCommitted: Boolean,
        val arm: (WorkspaceSyncFaultPlanV2) -> Unit,
    )

    private companion object {
        const val WRITER_A = "10000000-0000-4000-8000-000000000001"
        const val WRITER_B = "10000000-0000-4000-8000-000000000002"
        const val WRITER_C = "10000000-0000-4000-8000-000000000003"
        const val GLOBAL_STREAM = "global"
        val T0 = Instant.parse("2026-08-20T00:00:00Z")
        val T1 = Instant.parse("2026-08-20T01:00:00Z")
        val T2 = Instant.parse("2026-08-20T02:00:00Z")
    }
}
