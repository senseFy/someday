@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.export.ExportedNote
import saien.someday.data.export.ExportedNotebook
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataExporter
import saien.someday.data.export.LocalDataImporter
import saien.someday.data.importing.dayone.DayOneImportService
import saien.someday.data.importing.dayone.DayOneJsonDocument
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ManualSyncReason
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.notes.NoteInput
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncV2RuntimeServiceTest {
    @Test
    fun dayOneDraftPublishesSameGenerationAndKeepsOfflineMutation() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        withRuntimeFixture(remote) { fixture ->
            val draft = ensureWorkspaceLocalDraftV2(
                fixture.localRepository,
                fixture.settings,
                WORKSPACE_KEY,
            )
            val notes = fixture.notes(NOW)
            val notebook = notes.createNotebook("Offline")
            val note = notes.createNote(NoteInput(notebook.id, "Before sign-in", "day-one DAG"))

            val result = fixture.runtime().run()

            assertTrue(result.success, result.diagnosticMessage)
            assertEquals(draft.descriptor.syncEpochId, remote.loadEpochPointer()?.syncEpochId)
            assertEquals("day-one DAG", notes.getNoteDetails(note.id)?.markdownBody)
            assertEquals(
                SyncEpochLifecycleV2.ACTIVE,
                fixture.protocolStore.loadEpoch(remote.remoteProfile, draft.descriptor.syncEpochId)?.lifecycle,
            )
        }
    }

    @Test
    fun existingRemotePointerDoesNotSilentlyMergeNonEmptyLocalDraft() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        withRuntimeFixture(remote, writerDeviceId = WRITER_A) { leader ->
            ensureWorkspaceLocalDraftV2(leader.localRepository, leader.settings, WORKSPACE_KEY)
            assertTrue(leader.runtime().run().success)

            withRuntimeFixture(remote, writerDeviceId = WRITER_B) { local ->
                ensureWorkspaceLocalDraftV2(local.localRepository, local.settings, WORKSPACE_KEY)
                local.notes(NOW).createNotebook("Must not merge")

                val result = local.runtime().run()

                assertFalse(result.success)
                assertEquals(
                    ManualSyncReason.RemoteHistoryConflict,
                    result.reason,
                    result.diagnosticMessage,
                )
                assertNull(local.protocolStore.loadAuthoritativeEpoch())
            }
        }
    }

    @Test
    fun portableTransferUsesTheWritableDagAndNeverFallsBackToLocalRows() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        val document = LocalDataExportDocument(
            exportedAt = NOW.toString(),
            notebooks = listOf(
                ExportedNotebook(
                    id = "must-not-fallback-notebook",
                    title = "Must not fall back",
                    sortOrder = 1,
                    createdAt = NOW.toString(),
                    updatedAt = NOW.toString(),
                ),
            ),
            notes = listOf(
                ExportedNote(
                    id = "must-not-fallback-note",
                    notebookId = "must-not-fallback-notebook",
                    title = "Must not fall back",
                    markdownBody = "An unusable V2 authority stays authoritative.",
                    excerpt = "An unusable V2 authority stays authoritative.",
                    createdAt = NOW.toString(),
                    updatedAt = NOW.toString(),
                    revision = 1,
                ),
            ),
        )
        withRuntimeFixture(remote) { preparing ->
            val descriptor = SyncEpochDescriptorV2(
                syncEpochId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                remoteProfile = remote.remoteProfile,
                checkpointId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                checkpointDigest = "cd2:hmac-sha256:${"12".repeat(32)}",
                createdByDeviceId = preparing.writerDeviceId,
                createdAt = NOW,
            )
            preparing.protocolStore.persistPreparingEpoch(
                remote.remoteProfile,
                descriptor,
                "prepared-descriptor-digest",
            )
            val preparingTransfer = preparing.localDataTransfer()

            val imported = preparingTransfer.importDocument(document)
            assertEquals(1, imported.notesCreated)
            assertEquals(listOf("Must not fall back"), preparingTransfer.exportDocument(NOW).notes.map { it.title })
        }

        withRuntimeFixture(remote) { fixture ->
            assertTrue(fixture.runtime().run().success)
            val missingKeyTransfer = fixture.localDataTransfer(workspaceKeyProvider = { null })

            assertFailsWith<IllegalStateException> {
                LocalDataImporter(
                    authoritativeImporter = missingKeyTransfer::importDocument,
                ).importDocument(document)
            }
            assertFailsWith<IllegalStateException> {
                LocalDataExporter(
                    authoritativeDocumentProvider = missingKeyTransfer::exportDocument,
                    clock = { NOW },
                ).exportDocument()
            }

            val authority = assertNotNull(fixture.protocolStore.loadAuthoritativeEpoch())
            fixture.protocolStore.blockEpoch(
                authority.remoteProfile,
                authority.descriptor.syncEpochId,
                "test_blocked_authority",
                "The test authority is intentionally blocked.",
            )
            val blockedTransfer = fixture.localDataTransfer()

            assertFailsWith<IllegalStateException> {
                blockedTransfer.importDocument(document)
            }
            assertFailsWith<IllegalStateException> {
                blockedTransfer.exportDocument(NOW)
            }
        }
    }

    @Test
    fun emptyWorkspaceActivationPublishesCheckpointBeforeSwitchingAuthority() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        withRuntimeFixture(remote) { fixture ->
            val runtime = fixture.runtime()

            val activated = runtime.run()

            assertTrue(activated.success, activated.diagnosticMessage)
            assertNotNull(remote.loadEpochPointer())
            assertEquals(
                SyncEpochLifecycleV2.ACTIVE,
                assertNotNull(fixture.protocolStore.loadActiveEpoch(remote.remoteProfile)).lifecycle,
            )
            assertTrue(runtime.run().success)
        }
    }

    @Test
    fun productMutationAtPointerCommitWaitsAndRoutesToActivatedV2() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        withFileBackedRuntimeFixture(remote) { fixture ->
            val casEntered = CountDownLatch(1)
            val releaseCas = CountDownLatch(1)
            val mutationStarted = CountDownLatch(1)
            val mutationFinished = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val blockingRemote = BlockingPointerCommitRemote(remote, casEntered, releaseCas)
            val routedNotes = fixture.notes(NOW)
            try {
                val activation = executor.submit<ManualSyncResult> {
                    fixture.runtime(transportRemote = blockingRemote).run()
                }
                assertTrue(casEntered.await(5, TimeUnit.SECONDS))
                val mutation = executor.submit {
                    mutationStarted.countDown()
                    try {
                        routedNotes.createNotebook("Created at commit")
                    } finally {
                        mutationFinished.countDown()
                    }
                }
                assertTrue(mutationStarted.await(5, TimeUnit.SECONDS))
                assertFalse(
                    mutationFinished.await(150, TimeUnit.MILLISECONDS),
                    "Product mutation crossed the pointer-commit barrier.",
                )

                releaseCas.countDown()
                val activated = activation.get(10, TimeUnit.SECONDS)
                mutation.get(10, TimeUnit.SECONDS)

                assertTrue(activated.success, activated.diagnosticMessage)
                val active = WorkspaceSystemV2ContextProvider(
                    fixture.localRepository,
                    { WORKSPACE_KEY },
                    { fixture.writerDeviceId },
                    { remote.remoteProfile },
                ).requireActive()
                assertTrue(
                    active.store.loadProjections(WorkspaceEntityTypeV2.NOTEBOOK).any {
                        (it.content as? NotebookContentV2)?.title == "Created at commit"
                    },
                )
                assertTrue(active.store.loadActiveConflicts().isEmpty())
            } finally {
                releaseCas.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun unauthenticatedCompetingPointerDoesNotDiscardPreparedDraft() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        withRuntimeFixture(remote) { fixture ->
            val result = fixture.runtime(
                transportRemote = UnauthenticatedCompetingPointerRemote(remote),
            ).run()

            assertFalse(result.success)
            val draft = fixture.protocolStore.loadEpochs(remote.remoteProfile).single()
            assertEquals(SyncEpochLifecycleV2.PREPARING, draft.lifecycle)
            assertTrue(remote.hasCheckpointDraftForTest(draft.descriptor.syncEpochId))
        }
    }

    @Test
    fun followerBootstrapsAnExistingV2EpochDirectly() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        withRuntimeFixture(remote) { leader ->
            assertTrue(leader.runtime().run().success)
        }
        withRuntimeFixture(remote, writerDeviceId = WRITER_B) { follower ->
            val result = follower.runtime().run()

            assertTrue(result.success)
            assertNotNull(follower.protocolStore.loadActiveEpoch(remote.remoteProfile))
        }
    }

    @Test
    fun followerManualSyncReportsBootstrapPullCountsSoProductUiCanRefresh() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        withRuntimeFixture(remote) { leader ->
            assertTrue(leader.runtime().run().success)
            val notes = leader.notes(NOW)
            val notebook = notes.createNotebook("Shared notebook")
            notes.createNote(NoteInput(notebook.id, "HelloClean", "BodyFromLeader"))
            assertTrue(leader.runtime().run().success)
        }
        withRuntimeFixture(remote, writerDeviceId = WRITER_B) { follower ->
            // No local authority yet: run() must activate and surface pull counts
            // so SettingsUiController can refresh Notes after join+Sync.
            val result = follower.runtime().run()

            assertTrue(result.success, result.diagnosticMessage)
            assertTrue(
                result.pulledObjects > 0,
                "activation via manual sync must report bootstrap pulls; " +
                    "reason=${result.reason}, diagnostic=${result.diagnosticMessage}",
            )
            assertNotNull(follower.protocolStore.loadActiveEpoch(remote.remoteProfile))
            val followerNotes = follower.notes(NOW)
            assertEquals(1, followerNotes.listNotebooks().size)
            assertEquals("HelloClean", followerNotes.listNotes(followerNotes.listNotebooks().single().id).single().title)
        }
    }

    @Test
    fun checkpointPreparedBeforePointerIsResumedWithTheSameDurableIdentity() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.SELF_HOSTED.wireValue)
        remote.faults.failNextPointerCompareAndSet = true
        withRuntimeFixture(remote) { fixture ->
            val runtime = fixture.runtime()

            val interrupted = runtime.run()

            assertFalse(interrupted.success)
            assertNull(remote.loadEpochPointer())
            val prepared = fixture.protocolStore.loadEpochs(remote.remoteProfile).single()
            assertEquals(SyncEpochLifecycleV2.PREPARING, prepared.lifecycle)
            assertEquals(
                ManualSyncReason.Failed,
                interrupted.reason,
                interrupted.diagnosticMessage,
            )

            // Second transient empty CAS failure must not create another PREPARING draft.
            remote.faults.failNextPointerCompareAndSet = true
            val stillInterrupted = runtime.run()
            assertFalse(stillInterrupted.success)
            assertEquals(
                listOf(prepared.descriptor.syncEpochId),
                fixture.protocolStore.loadEpochs(remote.remoteProfile).map { it.descriptor.syncEpochId },
            )

            val resumed = runtime.run()

            assertTrue(resumed.success, resumed.diagnosticMessage)
            assertEquals(prepared.descriptor.syncEpochId, remote.loadEpochPointer()?.syncEpochId)
        }
    }

    @Test
    fun sameProfileDifferentEndpointCannotSilentlyReplaceBoundAuthority() {
        val boundRemote = InMemoryWorkspaceSyncRemoteV2(
            SyncRemoteProfileV2.SELF_HOSTED.wireValue,
            bindingId = "self-hosted-v2|https://bound.example|/someday/",
        )
        val otherRemote = InMemoryWorkspaceSyncRemoteV2(
            SyncRemoteProfileV2.SELF_HOSTED.wireValue,
            bindingId = "self-hosted-v2|https://other.example|/someday/",
        )
        withRuntimeFixture(otherRemote, writerDeviceId = WRITER_B) { other ->
            assertTrue(other.runtime().run().success)
        }
        withRuntimeFixture(boundRemote, writerDeviceId = WRITER_A) { fixture ->
            val bound = fixture.runtime()
            assertTrue(bound.run().success)
            val original = assertNotNull(fixture.protocolStore.loadActiveEpoch(boundRemote.remoteProfile))

            val rejected = fixture.runtime(
                transportRemote = otherRemote,
            ).run()

            assertFalse(rejected.success)
            assertEquals(ManualSyncReason.Blocked, rejected.reason, rejected.diagnosticMessage)
            assertEquals(original.descriptor.syncEpochId, fixture.protocolStore.loadAuthoritativeEpoch()?.descriptor?.syncEpochId)
            assertTrue(otherRemote.allChanges().isEmpty())
        }
    }

    private fun withFileBackedRuntimeFixture(
        remote: InMemoryWorkspaceSyncRemoteV2,
        writerDeviceId: String = WRITER_A,
        block: (RuntimeFixture) -> Unit,
    ) {
        val directory = Files.createTempDirectory("someday-sync-v2-runtime-")
        try {
            withRuntimeFixture(
                remote = remote,
                writerDeviceId = writerDeviceId,
                jdbcUrl = "jdbc:sqlite:${directory.resolve("someday.db").toAbsolutePath()}",
                block = block,
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun withRuntimeFixture(
        remote: InMemoryWorkspaceSyncRemoteV2,
        writerDeviceId: String = WRITER_A,
        jdbcUrl: String = "jdbc:sqlite::memory:",
        block: (RuntimeFixture) -> Unit,
    ) {
        val driver = createSomedayJdbcDriver(jdbcUrl)
        val database = SomedayDatabase(driver)
        val localRepository = SqlDelightLocalDataRepository(
            database = database,
            deviceId = writerDeviceId,
            clock = { NOW },
        )
        val settings = InMemorySettingsRepository(
            ClientSettings(
                activeDeviceId = writerDeviceId,
                syncConfiguration = SyncConfiguration(
                    mode = SyncMode.SelfHosted,
                ),
            ),
        )
        val fixture = RuntimeFixture(localRepository, settings, remote, writerDeviceId)
        try {
            block(fixture)
        } finally {
            driver.close()
        }
    }

    private data class RuntimeFixture(
        val localRepository: SqlDelightLocalDataRepository,
        val settings: InMemorySettingsRepository,
        val remote: InMemoryWorkspaceSyncRemoteV2,
        val writerDeviceId: String,
    ) {
        val protocolStore = SqlDelightSyncProtocolStoreV2(localRepository.database)
        val authorityMutationCoordinator = WorkspaceAuthorityMutationCoordinator()

        fun runtime(
            clockValue: Instant = NOW,
            transportRemote: WorkspaceSyncRemoteV2 = remote,
        ): SyncV2RuntimeService = SyncV2RuntimeService(
            mode = SyncMode.SelfHosted,
            localRepository = localRepository,
            settingsRepository = settings,
            workspaceKeyProvider = { WORKSPACE_KEY },
            writerDeviceIdProvider = { writerDeviceId },
            transportFactory = SyncRemoteTransportFactoryV2 { transportRemote },
            authorityMutationCoordinator = authorityMutationCoordinator,
            clock = { clockValue },
            beforeEntityPublication = {},
        )

        fun localDataTransfer(
            workspaceKeyProvider: () -> WorkspaceMasterKey? = { WORKSPACE_KEY },
        ): WorkspaceLocalDataTransferV2 =
            WorkspaceLocalDataTransferV2(
                localRepository = localRepository,
                settingsRepository = settings,
                workspaceKeyProvider = workspaceKeyProvider,
                writerDeviceIdProvider = { writerDeviceId },
                remoteProfileProvider = { remote.remoteProfile },
            )

        fun notes(clockValue: Instant) = saien.someday.sync.AuthorityCoordinatedNotesRepository(SystemV2NotesRepository(
            localRepository,
            { WORKSPACE_KEY },
            { writerDeviceId },
            { remote.remoteProfile },
            clock = { clockValue },
        ), authorityMutationCoordinator)
    }

    private class InMemorySettingsRepository(
        initial: ClientSettings,
    ) : ClientSettingsRepository {
        private var value = initial

        override fun load(): ClientSettings = value

        override fun save(settings: ClientSettings): ClientSettings {
            value = settings
            return value
        }
    }

    private class PullBeforePushRemote(
        private val delegate: WorkspaceSyncRemoteV2,
        private val expectedCurrentEpochId: String,
    ) : WorkspaceSyncRemoteV2 by delegate {
        var authenticatedPointerLoaded: Boolean = false
            private set
        var currentCheckpointFetched: Boolean = false
            private set
        var currentFrontierPulled: Boolean = false
            private set
        var pushCalls: Int = 0
            private set

        override fun loadEpochPointer(): EncryptedWorkspaceObjectV2? = delegate.loadEpochPointer().also { pointer ->
            if (pointer?.syncEpochId == expectedCurrentEpochId) authenticatedPointerLoaded = true
        }

        override fun fetchCheckpoint(
            pointer: EncryptedWorkspaceObjectV2,
            descriptor: SyncEpochDescriptorV2,
        ): WorkspaceRemoteCheckpointBundleV2 {
            check(authenticatedPointerLoaded)
            check(descriptor.syncEpochId == expectedCurrentEpochId)
            return delegate.fetchCheckpoint(pointer, descriptor).also {
                currentCheckpointFetched = true
            }
        }

        override fun pull(
            syncEpochId: String,
            cursors: Map<String, String?>,
            limit: Int,
        ): WorkspaceSyncPullResultV2 {
            if (syncEpochId == expectedCurrentEpochId) {
                check(authenticatedPointerLoaded)
                check(currentCheckpointFetched)
                currentFrontierPulled = true
            }
            return delegate.pull(syncEpochId, cursors, limit)
        }

        override fun push(
            syncEpochId: String,
            objects: List<EncryptedWorkspaceObjectV2>,
        ): WorkspaceSyncPushResultV2 {
            check(syncEpochId != expectedCurrentEpochId || currentFrontierPulled) {
                "A restored backup attempted to upload before authenticating and pulling the current epoch."
            }
            pushCalls += 1
            return delegate.push(syncEpochId, objects)
        }
    }

    private class MutateAfterCheckpointFetchRemote(
        private val delegate: WorkspaceSyncRemoteV2,
        private val mutation: () -> Unit,
    ) : WorkspaceSyncRemoteV2 by delegate {
        private var fired = false

        override fun fetchCheckpoint(
            pointer: EncryptedWorkspaceObjectV2,
            descriptor: SyncEpochDescriptorV2,
        ): WorkspaceRemoteCheckpointBundleV2 =
            delegate.fetchCheckpoint(pointer, descriptor).also {
                if (!fired) {
                    fired = true
                    mutation()
                }
            }
    }

    private class BlockingPointerCommitRemote(
        private val delegate: WorkspaceSyncRemoteV2,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : WorkspaceSyncRemoteV2 by delegate {
        override fun compareAndSetEpochPointer(
            descriptor: SyncEpochDescriptorV2,
            expectedCurrentDigest: String?,
            pointer: EncryptedWorkspaceObjectV2,
        ): WorkspacePointerPublishResultV2 {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            return delegate.compareAndSetEpochPointer(descriptor, expectedCurrentDigest, pointer)
        }
    }

    private class UnauthenticatedCompetingPointerRemote(
        private val delegate: WorkspaceSyncRemoteV2,
    ) : WorkspaceSyncRemoteV2 by delegate {
        private var unauthenticatedPointer: EncryptedWorkspaceObjectV2? = null

        override fun loadEpochPointer(): EncryptedWorkspaceObjectV2? =
            unauthenticatedPointer ?: delegate.loadEpochPointer()

        override fun compareAndSetEpochPointer(
            descriptor: SyncEpochDescriptorV2,
            expectedCurrentDigest: String?,
            pointer: EncryptedWorkspaceObjectV2,
        ): WorkspacePointerPublishResultV2 {
            val corrupt = pointer.copy(ciphertextBase64 = "AA==")
            unauthenticatedPointer = corrupt
            return WorkspacePointerPublishResultV2.CompareAndSetFailed(corrupt)
        }
    }

    private companion object {
        const val WRITER_A = "00000000-0000-4000-8000-0000000000a1"
        const val WRITER_B = "00000000-0000-4000-8000-0000000000b2"
        val NOW = Instant.parse("2026-07-19T03:00:00Z")
        val AFTER_HORIZON = Instant.parse("2027-01-16T03:00:00Z")
        val WORKSPACE_KEY = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 91).toByte() })

        fun pointerDecodesWith(
            key: saien.someday.data.crypto.WorkspaceMasterKey,
            epoch: StoredSyncEpochV2,
            remote: WorkspaceSyncRemoteV2,
        ): Boolean {
            val materializer = CanonicalWorkspaceCausalityMaterializerV2(
                SyncEpochKeyDerivationV2().derive(key, epoch.descriptor.syncEpochId),
            )
            val outer = remote.loadEpochPointer() ?: return false
            return WorkspaceSyncControlCodecV2(
                WorkspaceObjectCipherV2(key, materializer),
            ).decodeEpochPointer(outer) is WorkspaceControlDecodeResultV2.Decoded
        }
    }
}
