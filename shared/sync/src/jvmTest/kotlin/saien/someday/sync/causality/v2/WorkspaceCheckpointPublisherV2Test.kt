@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkspaceCheckpointPublisherV2Test {
    @Test
    fun entityPrerequisiteRejectsBeforeAnyCheckpointObjectOrPointerIsPublished() =
        withFixture { local, key, writer ->
            val prepared = assertIs<WorkspaceGenesisCheckpointResultV2.Prepared>(
                WorkspaceGenesisCheckpointServiceV2(
                    settingsRepository = SqlDelightClientSettingsRepository(local),
                    workspaceKey = key,
                    writerDeviceId = writer,
                    remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
                    clock = { Instant.fromEpochMilliseconds(1_780_000_000_000L) },
                ).prepare(),
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(local, key, writer).persist(prepared.checkpoint),
            )
            val remote = InMemoryWorkspaceSyncRemoteV2()
            var inspectedVersions = 0

            val rejected = WorkspaceCheckpointPublisherV2(
                localRepository = local,
                remote = remote,
                beforeEntityPublication = { versions ->
                    inspectedVersions += versions.size
                    error("media publication prerequisite failed")
                },
            ).publish(prepared.checkpoint)

            assertIs<WorkspaceCheckpointPublishResultV2.Rejected>(rejected)
            assertEquals("entity_publication_prerequisite_failed", rejected.safeErrorCode)
            assertTrue(inspectedVersions > 0)
            assertNull(remote.loadEpochPointer())
            assertTrue(!remote.hasCheckpointDraftForTest(prepared.checkpoint.descriptor.syncEpochId))
            val protocolStore = SqlDelightSyncProtocolStoreV2(local.database)
            WorkspacePublicationFailureV2.entries.forEach { failure ->
                val interrupted = assertIs<WorkspaceCheckpointPublishResultV2.Rejected>(
                    WorkspaceCheckpointPublisherV2(
                        local, remote,
                        beforeEntityPublication = { throw WorkspacePublicationExceptionV2(failure) },
                    ).publish(prepared.checkpoint),
                )
                assertEquals(failure.safeCode, interrupted.safeErrorCode)
                assertEquals(failure.safeMessage, interrupted.safeMessage)
                val recorded = protocolStore.loadRuns(remote.remoteProfile).first { it.safeErrorCode == failure.safeCode }
                assertEquals(
                    if (failure.retryable) SyncRunStatusV2.FAILED else SyncRunStatusV2.BLOCKED,
                    recorded.status,
                )
                assertEquals(failure.safeMessage, recorded.safeErrorMessage)
                assertEquals(
                    SyncEpochHealthV2.HEALTHY,
                    protocolStore.loadEpoch(remote.remoteProfile, prepared.checkpoint.descriptor.syncEpochId)?.health,
                )
                assertNull(remote.loadEpochPointer())
                assertTrue(!remote.hasCheckpointDraftForTest(prepared.checkpoint.descriptor.syncEpochId))
            }
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                WorkspaceCheckpointPublisherV2(
                    local, remote,
                    beforeEntityPublication = { throw kotlinx.coroutines.CancellationException("cancelled") },
                ).publish(prepared.checkpoint)
            }
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(local, remote, {}).publish(prepared.checkpoint),
            )
        }

    private fun withFixture(
        block: (SqlDelightLocalDataRepository, saien.someday.data.crypto.WorkspaceMasterKey, String) -> Unit,
    ) {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val database = SomedayDatabase(driver)
            val local = SqlDelightLocalDataRepository(database, WRITER)
            val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 9).toByte() })
            block(local, key, WRITER)
        } finally {
            driver.close()
        }
    }

    private companion object {
        const val WRITER = "10000000-0000-4000-8000-0000000000aa"
    }
}
