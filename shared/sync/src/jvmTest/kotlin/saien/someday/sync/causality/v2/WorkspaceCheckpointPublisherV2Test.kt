@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkspaceCheckpointPublisherV2Test {
    @Test
    fun parallelChunkPublishSucceedsAndObservesConcurrency() = withFixture { local, key, writer ->
        val notebook = local.createNotebook("Bulk")
        // Well above MAX_CHECKPOINT_CHUNK_OBJECTS (64) so multiple chunks are required.
        repeat(130) { index ->
            local.createNote(
                notebookId = notebook.id,
                title = "Note $index",
                markdownBody = "Body $index",
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L + index),
            )
        }
        val settings = SqlDelightClientSettingsRepository(local)
        val genesis = WorkspaceGenesisCheckpointServiceV2(
            localRepository = local,
            settingsRepository = settings,
            workspaceKey = key,
            writerDeviceId = writer,
            remoteProfile = SyncRemoteProfileV2.WEB_DAV.wireValue,
            clock = { Instant.fromEpochMilliseconds(1_780_000_000_000L) },
        )
        val preparedResult = genesis.prepare()
        if (preparedResult is WorkspaceGenesisCheckpointResultV2.Blocked) {
            error("genesis blocked: ${preparedResult.safeErrorCode} ${preparedResult.safeMessage}")
        }
        val prepared = assertIs<WorkspaceGenesisCheckpointResultV2.Prepared>(preparedResult)
        assertTrue(prepared.checkpoint.chunks.size >= 2, "expected multi-chunk checkpoint")

        assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
            WorkspaceCheckpointPersistenceV2(local, key, writer).persist(prepared.checkpoint),
        )

        val base = InMemoryWorkspaceSyncRemoteV2(remoteProfile = SyncRemoteProfileV2.WEB_DAV.wireValue)
        val remote = ConcurrentPutRemote(base)
        val progress = mutableListOf<WorkspaceCheckpointPublishProgressV2>()
        val published = WorkspaceCheckpointPublisherV2(
            localRepository = local,
            remote = remote,
            chunkPublishParallelism = 4,
            onProgress = { progress += it },
        ).publish(prepared.checkpoint)

        assertIs<WorkspaceCheckpointPublishResultV2.Published>(published)
        assertTrue(remote.maxInflight.get() >= 2, "expected concurrent chunk puts, max=${remote.maxInflight.get()}")
        assertTrue(progress.any { it is WorkspaceCheckpointPublishProgressV2.UploadingChunks })
        assertTrue(progress.any { it is WorkspaceCheckpointPublishProgressV2.UploadingManifest })
        assertTrue(progress.any { it is WorkspaceCheckpointPublishProgressV2.VerifyingRemote })
        assertTrue(progress.any { it is WorkspaceCheckpointPublishProgressV2.CommittingPointer })
        assertEquals(prepared.checkpoint.descriptor.syncEpochId, base.loadEpochPointer()?.syncEpochId)
    }

    @Test
    fun chunkFailureDoesNotCommitPointerAndRetrySucceeds() = withFixture { local, key, writer ->
        val notebook = local.createNotebook("Retry")
        repeat(70) { index ->
            local.createNote(
                notebookId = notebook.id,
                title = "Note $index",
                markdownBody = "Body $index",
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L + index),
            )
        }
        val settings = SqlDelightClientSettingsRepository(local)
        val prepared = assertIs<WorkspaceGenesisCheckpointResultV2.Prepared>(
            WorkspaceGenesisCheckpointServiceV2(
                localRepository = local,
                settingsRepository = settings,
                workspaceKey = key,
                writerDeviceId = writer,
                remoteProfile = SyncRemoteProfileV2.WEB_DAV.wireValue,
                clock = { Instant.fromEpochMilliseconds(1_780_000_000_000L) },
            ).prepare(),
        )
        assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
            WorkspaceCheckpointPersistenceV2(local, key, writer).persist(prepared.checkpoint),
        )

        val base = InMemoryWorkspaceSyncRemoteV2(remoteProfile = SyncRemoteProfileV2.WEB_DAV.wireValue)
        val failing = FailNthChunkRemote(base, failOnCall = 2)
        val rejected = WorkspaceCheckpointPublisherV2(
            localRepository = local,
            remote = failing,
            chunkPublishParallelism = 4,
        ).publish(prepared.checkpoint)
        assertIs<WorkspaceCheckpointPublishResultV2.Rejected>(rejected)
        assertNull(base.loadEpochPointer())

        val recovered = assertIs<WorkspacePreparedCheckpointLoadResultV2.Loaded>(
            WorkspacePreparedCheckpointRecoveryV2(local, key).loadCompatible(
                SyncRemoteProfileV2.WEB_DAV.wireValue,
                null,
            ),
        )
        val published = WorkspaceCheckpointPublisherV2(
            localRepository = local,
            remote = base,
            chunkPublishParallelism = 4,
        ).publish(recovered.prepared)
        assertIs<WorkspaceCheckpointPublishResultV2.Published>(published)
        assertEquals(prepared.checkpoint.descriptor.syncEpochId, base.loadEpochPointer()?.syncEpochId)
    }

    private fun withFixture(
        block: (SqlDelightLocalDataRepository, saien.someday.data.crypto.WorkspaceMasterKey, String) -> Unit,
    ) {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        val database = SomedayDatabase(driver)
        val local = SqlDelightLocalDataRepository(database, WRITER)
        val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 9).toByte() })
        block(local, key, WRITER)
    }

    private companion object {
        const val WRITER = "10000000-0000-4000-8000-0000000000aa"
    }
}

private class ConcurrentPutRemote(
    private val delegate: InMemoryWorkspaceSyncRemoteV2,
) : WorkspaceSyncRemoteV2 by delegate {
    private val inflight = AtomicInteger(0)
    val maxInflight = AtomicInteger(0)

    override fun putCheckpointChunk(
        descriptor: SyncEpochDescriptorV2,
        ref: WorkspaceCheckpointChunkRefV2,
        chunk: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 {
        val now = inflight.incrementAndGet()
        maxInflight.updateAndGet { current -> maxOf(current, now) }
        try {
            Thread.sleep(40)
            return delegate.putCheckpointChunk(descriptor, ref, chunk)
        } finally {
            inflight.decrementAndGet()
        }
    }
}

private class FailNthChunkRemote(
    private val delegate: InMemoryWorkspaceSyncRemoteV2,
    private val failOnCall: Int,
) : WorkspaceSyncRemoteV2 by delegate {
    private val calls = AtomicInteger(0)

    override fun putCheckpointChunk(
        descriptor: SyncEpochDescriptorV2,
        ref: WorkspaceCheckpointChunkRefV2,
        chunk: EncryptedWorkspaceObjectV2,
    ): WorkspaceImmutablePutResultV2 {
        if (calls.incrementAndGet() == failOnCall) {
            return WorkspaceImmutablePutResultV2.Rejected(
                "injected_chunk_failure",
                "Injected checkpoint chunk failure.",
            )
        }
        return delegate.putCheckpointChunk(descriptor, ref, chunk)
    }
}
