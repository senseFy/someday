@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.integration

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.sync.causality.v2.ActiveWorkspaceSystemV2
import saien.someday.sync.causality.v2.LocalWorkspaceMutationV2
import saien.someday.sync.causality.v2.NoteContentV2
import saien.someday.sync.causality.v2.NotebookContentV2
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.SyncCoordinatorStatusV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.WORKSPACE_PREFERENCES_ENTITY_ID_V2
import saien.someday.sync.causality.v2.WorkspaceCheckpointBuilderV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPersistResultV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPersistenceV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPublishResultV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPublisherV2
import saien.someday.sync.causality.v2.CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2
import saien.someday.sync.causality.v2.WorkspaceCheckpointSourceHeadV2
import saien.someday.sync.causality.v2.WorkspaceEntityKeyV2
import saien.someday.sync.causality.v2.WorkspaceEntityTypeV2
import saien.someday.sync.causality.v2.WorkspaceLocalCommitResultV2
import saien.someday.sync.causality.v2.WorkspacePreferencesV2
import saien.someday.sync.causality.v2.WorkspaceSyncCoordinatorV2
import saien.someday.sync.causality.v2.WorkspaceSystemV2ContextProvider
import saien.someday.sync.selfhosted.JdkSelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedSyncClient
import saien.someday.sync.selfhosted.SelfHostedSyncRemoteV2
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Live dual-device conflict against an explicitly configured self-hosted
 * server. This runs only through the dedicated realRemoteTest task.
 *
 * Two devices diverge on the same note body field, then sync; the result must
 * surface a durable multi-head conflict (not silent overwrite).
 */
class SelfHostedV2ConflictLiveTest {
    @Test
    fun concurrentSameFieldEditsSurfaceActiveConflictOnBothDevices() {
        val endpoint = System.getenv("SOMEDAY_E2E_ENDPOINT")
            ?.takeIf(String::isNotBlank)
            ?: error("SOMEDAY_E2E_ENDPOINT is required by :integration-tests:realRemoteTest.")
        val transport = JdkSelfHostedSyncTransport()
        val client = SelfHostedSyncClient(endpoint, transport)
        val unique = UUID.randomUUID().toString()
        val email = "conflict-live-$unique@example.com"
        val password = "Conflict-Live-$unique"
        val leaderSession = client.registerAndConnect(email, password, "Android-like leader", "android")
        val followerSession = client.loginAndConnect(email, password, "iOS-like follower", "ios")
        val key = workspaceKey()
        val profile = SyncRemoteProfileV2.SELF_HOSTED.wireValue
        val leader = device("leader")
        val follower = device("follower")
        try {
            val leaderRemote = SelfHostedSyncRemoteV2(endpoint, key, { leaderSession.accessToken }, transport)
            val followerRemote = SelfHostedSyncRemoteV2(endpoint, key, { followerSession.accessToken }, transport)

            val notebookId = "b1000000-0000-4000-8000-000000000001"
            val noteId = "n1000000-0000-4000-8000-000000000001"
            val writer = leaderSession.deviceId
            val sourceHeads = listOf(
                WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.NOTE,
                    noteId,
                    NoteContentV2(notebookId, "Shared note", "base body", ROOT, "UTC", null),
                    null,
                    "fresh-local-v2",
                    null,
                    writer,
                    null,
                    "source-note",
                    "source-note-digest",
                    ROOT,
                ),
                WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.NOTEBOOK,
                    notebookId,
                    NotebookContentV2("Conflict journal", 1, ROOT),
                    null,
                    "fresh-local-v2",
                    null,
                    writer,
                    null,
                    "source-notebook",
                    "source-notebook-digest",
                    ROOT,
                ),
                WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    WorkspacePreferencesV2(defaultNotebookId = notebookId),
                    null,
                    "fresh-local-v2",
                    null,
                    writer,
                    null,
                    "source-preferences",
                    "source-preferences-digest",
                    ROOT,
                ),
            ).sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)
            val prepared = WorkspaceCheckpointBuilderV2(key, writer).build(
                remoteProfile = profile,
                sourceHeads = sourceHeads,
                createdAt = ROOT,
            )
            assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
                WorkspaceCheckpointPersistenceV2(leader.local, key, leaderSession.deviceId).persist(prepared),
            )
            assertIs<WorkspaceCheckpointPublishResultV2.Published>(
                WorkspaceCheckpointPublisherV2(leader.local, leaderRemote).publish(prepared),
            )

            assertSuccessful(coordinator(follower, key, followerSession.deviceId, followerRemote).syncOnce())
            val leaderCtx = context(leader, key, leaderSession.deviceId, profile)
            val followerCtx = context(follower, key, followerSession.deviceId, profile)

            // Same-field concurrent divergence: both rewrite markdownBody.
            val leaderNote = leaderCtx.store.loadHeads(WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, noteId)).single()
            val followerNote = followerCtx.store.loadHeads(WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, noteId)).single()
            val leaderEdit = leaderCtx.factory.createContentChild(
                leaderNote,
                (leaderNote.contentPayload as NoteContentV2).copy(markdownBody = "android body"),
                leaderCtx.deviceActorId,
                LEADER_AT,
            )
            val followerEdit = followerCtx.factory.createContentChild(
                followerNote,
                (followerNote.contentPayload as NoteContentV2).copy(markdownBody = "ios body"),
                followerCtx.deviceActorId,
                FOLLOWER_AT,
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                leaderCtx.store.commitLocalMutations(
                    listOf(LocalWorkspaceMutationV2(profile, leaderCtx.factory.newMutationId(), leaderEdit, LEADER_AT)),
                ),
            )
            assertIs<WorkspaceLocalCommitResultV2.Committed>(
                followerCtx.store.commitLocalMutations(
                    listOf(LocalWorkspaceMutationV2(profile, followerCtx.factory.newMutationId(), followerEdit, FOLLOWER_AT)),
                ),
            )

            assertSuccessful(coordinator(leader, key, leaderSession.deviceId, leaderRemote).syncOnce())
            assertSuccessful(coordinator(follower, key, followerSession.deviceId, followerRemote).syncOnce())
            assertSuccessful(coordinator(leader, key, leaderSession.deviceId, leaderRemote).syncOnce())
            assertSuccessful(coordinator(follower, key, followerSession.deviceId, followerRemote).syncOnce())

            val leaderHeads = leaderCtx.store.loadHeads(WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, noteId))
            val followerHeads = followerCtx.store.loadHeads(WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, noteId))
            assertEquals(2, leaderHeads.size, "Leader should retain two concurrent heads after same-field conflict.")
            assertEquals(2, followerHeads.size, "Follower should retain two concurrent heads after same-field conflict.")
            assertTrue(leaderCtx.store.loadActiveConflicts().isNotEmpty(), "Leader must expose an active conflict record.")
            assertTrue(followerCtx.store.loadActiveConflicts().isNotEmpty(), "Follower must expose an active conflict record.")
            val bodies = leaderHeads.map { (it.contentPayload as NoteContentV2).markdownBody }.toSet()
            assertEquals(setOf("android body", "ios body"), bodies)
        } finally {
            leader.close()
            follower.close()
        }
    }

    private fun coordinator(
        device: Device,
        key: WorkspaceMasterKey,
        writer: String,
        remote: SelfHostedSyncRemoteV2,
    ) = WorkspaceSyncCoordinatorV2(device.local, key, writer, remote)

    private fun context(
        device: Device,
        key: WorkspaceMasterKey,
        writer: String,
        profile: String,
    ): ActiveWorkspaceSystemV2 =
        WorkspaceSystemV2ContextProvider(device.local, { key }, { writer }, { profile }).requireActive()

    private fun assertSuccessful(summary: saien.someday.sync.causality.v2.WorkspaceSyncSummaryV2) {
        assertEquals(SyncCoordinatorStatusV2.SUCCESS, summary.status, summary.safeMessage ?: "sync failed")
    }

    private fun device(label: String): Device {
        val path = kotlin.io.path.createTempFile("someday-conflict-$label-", ".db")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${path.toAbsolutePath()}")
        val database = SomedayDatabase(driver)
        val local = SqlDelightLocalDataRepository(database, "device-$label", clock = { ROOT })
        return Device(driver, database, local, path.toFile())
    }

    private fun workspaceKey(): WorkspaceMasterKey {
        val crypto = SodiumWorkspaceCrypto()
        return crypto.workspaceKeyFromBytes(ByteArray(32) { (it + 3).toByte() })
    }

    private data class Device(
        val driver: app.cash.sqldelight.db.SqlDriver,
        val database: SomedayDatabase,
        val local: SqlDelightLocalDataRepository,
        val file: java.io.File,
    ) {
        fun close() {
            driver.close()
            file.delete()
        }
    }

    private companion object {
        val ROOT = Instant.parse("2026-07-26T00:00:00Z")
        val LEADER_AT = Instant.parse("2026-07-26T01:00:00Z")
        val FOLLOWER_AT = Instant.parse("2026-07-26T01:00:01Z")
    }
}
