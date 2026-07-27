@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.local.EntityType
import saien.someday.data.local.LocationInput
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ManualSyncResult
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.notes.NoteInput
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
    fun trueMasterKeyRotationPublishesOneAuthenticatedSuccessorAndIsIdempotent() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        val newKey = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 17).toByte() })
        withRuntimeFixture(remote) { fixture ->
            val runtime = fixture.runtime()
            assertTrue(runtime.run().success)
            val notes = fixture.notes(NOW)
            val notebook = notes.createNotebook("Key rotation")
            val note = notes.createNote(NoteInput(notebook.id, "Preserved", "encrypted under a successor epoch"))
            assertTrue(runtime.run().success)
            assertTrue(runtime.run().success)
            val oldEpoch = assertNotNull(fixture.protocolStore.loadActiveEpoch(remote.remoteProfile))

            val service = WorkspaceMasterKeyRotationServiceV2(
                fixture.localRepository,
                WORKSPACE_KEY,
                newKey,
                fixture.writerDeviceId,
                remote,
                remote,
                fixture.protocolStore,
                { NOW },
            )
            val refused = assertIs<WorkspaceMasterKeyRotationResultV2.Blocked>(
                service.rotate(
                    WorkspaceMasterKeyRotationAuthorizationV2(
                        newKeyDistributedToRetainedDevices = false,
                        removedDevicesRevoked = true,
                        newKeyDurablyStagedLocally = true,
                    ),
                ),
            )
            assertEquals("new_workspace_key_not_distributed", refused.safeErrorCode)
            assertEquals(oldEpoch.descriptorDigest, remote.loadEpochPointer()?.objectDigest)

            val authorization = WorkspaceMasterKeyRotationAuthorizationV2(
                newKeyDistributedToRetainedDevices = true,
                removedDevicesRevoked = true,
                newKeyDurablyStagedLocally = true,
            )
            val rotated = assertIs<WorkspaceMasterKeyRotationResultV2.Rotated>(service.rotate(authorization))

            assertEquals(oldEpoch.descriptor.syncEpochId, rotated.previousEpochId)
            assertTrue(rotated.newEpochId != oldEpoch.descriptor.syncEpochId)
            assertEquals(rotated.newPointerDigest, remote.loadEpochPointer()?.objectDigest)
            assertEquals(
                SyncEpochLifecycleV2.READ_ONLY,
                fixture.protocolStore.loadEpoch(remote.remoteProfile, oldEpoch.descriptor.syncEpochId)?.lifecycle,
            )
            val active = assertNotNull(fixture.protocolStore.loadActiveEpoch(remote.remoteProfile))
            assertEquals(rotated.newEpochId, active.descriptor.syncEpochId)
            assertTrue(pointerDecodesWith(newKey, active, remote))
            assertFalse(pointerDecodesWith(WORKSPACE_KEY, active, remote))

            val current = WorkspaceSystemV2ContextProvider(
                fixture.localRepository,
                { newKey },
                { fixture.writerDeviceId },
                { remote.remoteProfile },
            ).requireActive()
            assertEquals(
                "encrypted under a successor epoch",
                (current.store.loadHeads(WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.NOTE,
                    note.id,
                )).single().contentPayload as NoteContentV2).markdownBody,
            )

            val replay = assertIs<WorkspaceMasterKeyRotationResultV2.Rotated>(service.rotate(authorization))
            assertTrue(replay.idempotentResume)
            assertEquals(rotated.newEpochId, replay.newEpochId)
            assertEquals(2, fixture.protocolStore.loadEpochs(remote.remoteProfile).size)
        }
    }

    @Test
    fun authorizedRecoveryRequiresConfirmationAndArchivesBlockedEpochAfterLocalDagVerification() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        withRuntimeFixture(remote) { fixture ->
            val runtime = fixture.runtime()
            assertTrue(runtime.run().success)
            val prior = assertNotNull(fixture.protocolStore.loadActiveEpoch(remote.remoteProfile))
            fixture.protocolStore.blockEpoch(
                remote.remoteProfile,
                prior.descriptor.syncEpochId,
                "immutable_object_mismatch",
                "Exact repair copies are unavailable in this test.",
            )

            val refused = runtime.recoverWithVerifiedLocalCheckpoint(userConfirmedPotentialDataLoss = false)

            assertFalse(refused.success)
            assertEquals(prior.descriptor.syncEpochId, remote.loadEpochPointer()?.syncEpochId)

            val recovered = runtime.recoverWithVerifiedLocalCheckpoint(userConfirmedPotentialDataLoss = true)

            assertTrue(recovered.success, recovered.message)
            val active = assertNotNull(fixture.protocolStore.loadActiveEpoch(remote.remoteProfile))
            assertTrue(active.descriptor.syncEpochId != prior.descriptor.syncEpochId)
            val archive = assertNotNull(
                fixture.protocolStore.loadEpoch(remote.remoteProfile, prior.descriptor.syncEpochId),
            )
            assertEquals(SyncEpochLifecycleV2.READ_ONLY, archive.lifecycle)
            assertEquals(SyncEpochHealthV2.BLOCKED, archive.health)
            assertEquals("authorized_rebootstrap_archive", archive.safeErrorCode)
        }
    }

    @Test
    fun releaseDisabledBuildCannotPublishFirstV2Authority() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        withRuntimeFixture(remote) { fixture ->
            val result = fixture.runtime(
                activationEnabled = false,
            ).run()

            assertFalse(result.success)
            assertTrue("release-disabled" in result.message)
            assertEquals(null, remote.loadEpochPointer())
        }
    }

    @Test
    fun emptyWorkspaceActivationPublishesCheckpointBeforeSwitchingAuthority() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        withRuntimeFixture(remote) { fixture ->
            val runtime = fixture.runtime()

            val activated = runtime.run()

            assertTrue(activated.success)
            assertNotNull(remote.loadEpochPointer())
            assertEquals(
                SyncEpochLifecycleV2.ACTIVE,
                assertNotNull(fixture.protocolStore.loadActiveEpoch(remote.remoteProfile)).lifecycle,
            )
            assertTrue(runtime.run().success)
        }
    }

    @Test
    fun genuinelyEmptyRemotePublishesCompleteGenesisWithLocation() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        withRuntimeFixture(remote) { fixture ->
            val notebook = fixture.localRepository.createNotebook("Fresh notebook", id = "fresh-notebook")
            val note = fixture.localRepository.createNote(
                notebookId = notebook.id,
                title = "Fresh located note",
                markdownBody = "The complete local state is the first authority.",
                location = LocationInput(latitude = 31.2304, longitude = 121.4737),
                id = "fresh-note",
            )
            assertTrue(fixture.localRepository.listDirtySyncMetadata().isNotEmpty())
            val runtime = fixture.runtime()

            val activated = runtime.run()

            assertTrue(activated.success)
            val context = WorkspaceSystemV2ContextProvider(
                fixture.localRepository,
                { WORKSPACE_KEY },
                { fixture.writerDeviceId },
                { remote.remoteProfile },
            ).requireActive()
            val versions = context.store.loadVersions(
                WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, note.id),
            )
            assertEquals(1, versions.size)
            val root = versions.single()
            assertEquals(WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT, root.provenance?.type)
            assertNotNull((root.contentPayload as NoteContentV2).location)
            assertTrue(root.parentVersionIds.isEmpty())
        }
    }

    @Test
    fun followerBootstrapsAnExistingV2EpochDirectly() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
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
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
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

            assertTrue(result.success, result.message)
            assertTrue(
                result.pulledObjects > 0,
                "activation via manual sync must report bootstrap pulls; got message=${result.message}",
            )
            assertNotNull(follower.protocolStore.loadActiveEpoch(remote.remoteProfile))
            val followerNotes = follower.notes(NOW)
            assertEquals(1, followerNotes.listNotebooks().size)
            assertEquals("HelloClean", followerNotes.listNotes(followerNotes.listNotebooks().single().id).single().title)
        }
    }

    @Test
    fun nonemptyJoiningDeviceImportsIndependentRootsAndSurfacesCollisionAsConflict() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        val notebookId = "31000000-0000-4000-8000-000000000001"
        val noteId = "41000000-0000-4000-8000-000000000001"
        withRuntimeFixture(remote, writerDeviceId = WRITER_A) { leader ->
            leader.localRepository.createNotebook("Shared", id = notebookId)
            leader.localRepository.createNote(
                notebookId = notebookId,
                title = "Same identity",
                markdownBody = "remote checkpoint branch",
                id = noteId,
            )
            assertTrue(leader.runtime().run().success)
        }
        withRuntimeFixture(remote, writerDeviceId = WRITER_B) { follower ->
            follower.localRepository.createNotebook("Shared", id = notebookId)
            follower.localRepository.createNote(
                notebookId = notebookId,
                title = "Same identity",
                markdownBody = "unpublished joining-device branch",
                id = noteId,
            )

            val joined = follower.runtime().run()

            assertTrue(joined.success, joined.message)
            val context = WorkspaceSystemV2ContextProvider(
                follower.localRepository,
                { WORKSPACE_KEY },
                { WRITER_B },
                { remote.remoteProfile },
            ).requireActive()
            val noteKey = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, noteId)
            assertTrue(context.store.loadHeads(noteKey).mapNotNull {
                (it.contentPayload as? NoteContentV2)?.markdownBody
            }.containsAll(listOf("remote checkpoint branch", "unpublished joining-device branch")))
            assertEquals(
                1,
                context.store.loadActiveConflicts().count {
                    it.descriptor.entityType == noteKey.entityType &&
                        it.descriptor.entityId == noteKey.entityId
                },
            )
        }
    }

    @Test
    fun emptyRemoteInitializationRaceBootstrapsWinnerAndImportsLosingLocalState() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        val notebookId = "32000000-0000-4000-8000-000000000001"
        val noteId = "42000000-0000-4000-8000-000000000001"
        withRuntimeFixture(remote, writerDeviceId = WRITER_A) { fixture ->
            fixture.localRepository.createNotebook("Losing initializer", id = notebookId)
            fixture.localRepository.createNote(
                notebookId = notebookId,
                title = "CAS-losing local state",
                markdownBody = "must be imported after the winner is authenticated",
                id = noteId,
            )
            val winner = WorkspaceCheckpointBuilderV2(WORKSPACE_KEY, WRITER_B).build(
                remoteProfile = remote.remoteProfile,
                sourceHeads = listOf(WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    WorkspacePreferencesV2(),
                    null,
                    "independent-empty-initializer",
                    null,
                    WRITER_B,
                    null,
                    "winner-source",
                    "winner-source-digest",
                    NOW,
                )),
                createdAt = NOW,
            )
            remote.faults.beforeNextPointerCompareAndSet = {
                winner.chunks.forEach { chunk ->
                    assertIs<WorkspaceImmutablePutResultV2.Stored>(
                        remote.putCheckpointChunk(winner.descriptor, chunk.ref, chunk.encryptedObject),
                    )
                }
                assertIs<WorkspaceImmutablePutResultV2.Stored>(
                    remote.putCheckpointManifest(winner.descriptor, winner.manifestObject),
                )
                assertIs<WorkspacePointerPublishResultV2.Published>(
                    remote.compareAndSetEpochPointer(winner.descriptor, null, winner.pointerObject),
                )
            }

            val activated = fixture.runtime().run()

            assertTrue(activated.success, activated.message)
            assertEquals(winner.descriptor.syncEpochId, remote.loadEpochPointer()?.syncEpochId)
            val active = WorkspaceSystemV2ContextProvider(
                fixture.localRepository,
                { WORKSPACE_KEY },
                { WRITER_A },
                { remote.remoteProfile },
            ).requireActive()
            assertEquals(
                "must be imported after the winner is authenticated",
                (active.store.loadProjection(WorkspaceEntityKeyV2(
                    WorkspaceEntityTypeV2.NOTE,
                    noteId,
                ))?.content as NoteContentV2).markdownBody,
            )
            assertTrue(remote.allChanges().isNotEmpty())
        }
    }

    @Test
    fun checkpointPreparedBeforePointerIsResumedWithTheSameDurableIdentity() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        remote.faults.failNextPointerCompareAndSet = true
        withRuntimeFixture(remote) { fixture ->
            val runtime = fixture.runtime()

            val interrupted = runtime.run()

            assertFalse(interrupted.success)
            assertNull(remote.loadEpochPointer())
            val prepared = fixture.protocolStore.loadEpochs(remote.remoteProfile).single()
            assertEquals(SyncEpochLifecycleV2.PREPARING, prepared.lifecycle)

            val resumed = runtime.run()

            assertTrue(resumed.success)
            assertEquals(prepared.descriptor.syncEpochId, remote.loadEpochPointer()?.syncEpochId)
        }
    }

    @Test
    fun restoredBackupFlagClearsOnlyAfterAuthenticatedPullFirstV2Success() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        withRuntimeFixture(remote) { fixture ->
            val runtime = fixture.runtime()
            assertTrue(runtime.run().success)
            fixture.protocolStore.markBackupReconciliationPending(NOW)
            remote.faults.failNextPull = true

            val interrupted = runtime.run()

            assertFalse(interrupted.success)
            assertNotNull(fixture.protocolStore.loadReconciliationState())
            val reconciled = runtime.run()
            assertTrue(reconciled.success)
            assertTrue("Restored backup reconciliation completed" in reconciled.message)
            assertNull(fixture.protocolStore.loadReconciliationState())
        }
    }

    @Test
    fun restoredOldEpochBackupPullsCurrentCheckpointBeforeImportingAndPushingItsLocalBranch() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        withRuntimeFixture(remote, writerDeviceId = WRITER_A) { leader ->
            val leaderRuntime = leader.runtime()
            assertTrue(leaderRuntime.run().success)
            val leaderNotes = leader.notes(NOW)
            val notebook = leaderNotes.createNotebook("Backup reconciliation")
            val note = leaderNotes.createNote(NoteInput(notebook.id, "Restored", "shared base"))
            assertTrue(leaderRuntime.run().success)

            withRuntimeFixture(remote, writerDeviceId = WRITER_B) { restored ->
                val restoredRuntime = restored.runtime()
                assertTrue(restoredRuntime.run().success)
                val oldEpochId = assertNotNull(restored.protocolStore.loadActiveEpoch(remote.remoteProfile))
                    .descriptor.syncEpochId
                val restoredNotes = restored.notes(NOW)
                val localView = assertNotNull(restoredNotes.getNoteDetails(note.id))
                restoredNotes.updateNote(note.id, NoteInput(
                    notebookId = localView.notebookId,
                    title = localView.title,
                    markdownBody = "unpublished branch in restored backup",
                    createdAt = localView.createdAt,
                    location = localView.location,
                    timeZoneId = localView.timeZoneId,
                    causalToken = localView.causalToken,
                ))

                val onlineView = assertNotNull(leaderNotes.getNoteDetails(note.id))
                leaderNotes.updateNote(note.id, NoteInput(
                    notebookId = onlineView.notebookId,
                    title = onlineView.title,
                    markdownBody = "current remote branch",
                    createdAt = onlineView.createdAt,
                    location = onlineView.location,
                    timeZoneId = onlineView.timeZoneId,
                    causalToken = onlineView.causalToken,
                ))
                assertTrue(leaderRuntime.run().success)
                assertTrue(leaderRuntime.rollEpoch().success)
                val currentEpochId = checkNotNull(remote.loadEpochPointer()).syncEpochId
                assertTrue(currentEpochId != oldEpochId)

                restored.protocolStore.markBackupReconciliationPending(NOW)
                val proofRemote = PullBeforePushRemote(remote, currentEpochId)
                val reconciled = restored.runtime(
                    transportRemote = proofRemote,
                ).run()

                assertTrue(reconciled.success, reconciled.message)
                assertTrue(proofRemote.currentFrontierPulled)
                assertTrue(proofRemote.pushCalls > 0)
                assertNull(restored.protocolStore.loadReconciliationState())
                val context = WorkspaceSystemV2ContextProvider(
                    restored.localRepository,
                    { WORKSPACE_KEY },
                    { WRITER_B },
                    { remote.remoteProfile },
                ).requireActive()
                val key = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, note.id)
                assertTrue(context.store.loadHeads(key).mapNotNull {
                    (it.contentPayload as? NoteContentV2)?.markdownBody
                }.containsAll(listOf("current remote branch", "unpublished branch in restored backup")))
                assertEquals(1, context.store.loadActiveConflicts().count {
                    it.descriptor.entityType == key.entityType && it.descriptor.entityId == key.entityId
                })
            }
        }
    }

    @Test
    fun deviceBeyondHorizonBootstrapsCurrentCheckpointAndImportsUnpublishedOldEpochEdit() {
        val remote = InMemoryWorkspaceSyncRemoteV2(SyncRemoteProfileV2.WEB_DAV.wireValue)
        withRuntimeFixture(remote, writerDeviceId = WRITER_A) { leader ->
            val leaderRuntime = leader.runtime()
            assertTrue(leaderRuntime.run().success)
            val leaderNotes = leader.notes(NOW)
            val notebook = leaderNotes.createNotebook("Horizon")
            val created = leaderNotes.createNote(NoteInput(
                notebookId = notebook.id,
                title = "Retained draft",
                markdownBody = "shared base",
            ))
            assertTrue(leaderRuntime.run().success)

            withRuntimeFixture(remote, writerDeviceId = WRITER_B) { laggard ->
                val laggardRuntime = laggard.runtime()
                assertTrue(laggardRuntime.run().success)
                val oldEpochId = assertNotNull(laggard.protocolStore.loadActiveEpoch(remote.remoteProfile))
                    .descriptor.syncEpochId
                val laggardNotes = laggard.notes(NOW)
                val laggardOpened = assertNotNull(laggardNotes.getNoteDetails(created.id))
                laggardNotes.updateNote(created.id, NoteInput(
                    notebookId = laggardOpened.notebookId,
                    title = laggardOpened.title,
                    markdownBody = "offline unpublished branch",
                    createdAt = laggardOpened.createdAt,
                    location = laggardOpened.location,
                    timeZoneId = laggardOpened.timeZoneId,
                    causalToken = laggardOpened.causalToken,
                ))

                val leaderOpened = assertNotNull(leaderNotes.getNoteDetails(created.id))
                leaderNotes.updateNote(created.id, NoteInput(
                    notebookId = leaderOpened.notebookId,
                    title = leaderOpened.title,
                    markdownBody = "online branch captured by checkpoint",
                    createdAt = leaderOpened.createdAt,
                    location = leaderOpened.location,
                    timeZoneId = leaderOpened.timeZoneId,
                    causalToken = leaderOpened.causalToken,
                ))
                assertTrue(leaderRuntime.run().success)
                assertTrue(leaderRuntime.rollEpoch().success)
                val currentEpochId = checkNotNull(remote.loadEpochPointer()).syncEpochId
                assertTrue(currentEpochId != oldEpochId)
                remote.collectReadOnlyEpochForTest(oldEpochId)

                val resumed = laggard.runtime(
                    clockValue = AFTER_HORIZON,
                ).run()

                assertTrue(resumed.success, resumed.message)
                assertEquals(currentEpochId, laggard.protocolStore.loadActiveEpoch(remote.remoteProfile)?.descriptor?.syncEpochId)
                val current = WorkspaceSystemV2ContextProvider(
                    laggard.localRepository,
                    { WORKSPACE_KEY },
                    { WRITER_B },
                    { remote.remoteProfile },
                ).requireActive()
                val conflict = current.store.loadActiveConflicts().single {
                    it.descriptor.entityType == WorkspaceEntityTypeV2.NOTE && it.descriptor.entityId == created.id
                }
                assertEquals(2, conflict.descriptor.headVersionIds.size)
                assertTrue(current.store.loadHeads(WorkspaceEntityKeyV2(
                    conflict.descriptor.entityType,
                    conflict.descriptor.entityId,
                )).mapNotNull {
                    (it.contentPayload as? NoteContentV2)?.markdownBody
                }.containsAll(listOf("offline unpublished branch", "online branch captured by checkpoint")))

                val collection = WorkspaceEpochRetentionServiceV2(
                    laggard.localRepository,
                    laggard.protocolStore,
                ).collectExpiredLocalEpochs(remote.remoteProfile, AFTER_HORIZON)
                assertEquals(listOf(oldEpochId), collection.collectedEpochIds)
            }
        }
    }

    @Test
    fun sameProfileDifferentEndpointCannotSilentlyReplaceBoundAuthority() {
        val boundRemote = InMemoryWorkspaceSyncRemoteV2(
            SyncRemoteProfileV2.WEB_DAV.wireValue,
            bindingId = "webdav-log-v2|https://bound.example|/someday/",
        )
        val otherRemote = InMemoryWorkspaceSyncRemoteV2(
            SyncRemoteProfileV2.WEB_DAV.wireValue,
            bindingId = "webdav-log-v2|https://other.example|/someday/",
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
            assertTrue("explicit remote migration" in rejected.message)
            assertEquals(original.descriptor.syncEpochId, fixture.protocolStore.loadAuthoritativeEpoch()?.descriptor?.syncEpochId)
            assertTrue(otherRemote.allChanges().isEmpty())
        }
    }

    @Test
    fun explicitCrossProfileMigrationPublishesOneTargetCheckpointWithoutDualWrite() {
        val source = InMemoryWorkspaceSyncRemoteV2(
            SyncRemoteProfileV2.WEB_DAV.wireValue,
            bindingId = "webdav-log-v2|https://source.example|/someday/",
        )
        val target = InMemoryWorkspaceSyncRemoteV2(
            SyncRemoteProfileV2.SELF_HOSTED.wireValue,
            bindingId = "self-hosted-v2|https://target.example",
        )
        withRuntimeFixture(source, writerDeviceId = WRITER_A) { fixture ->
            val runtime = fixture.runtime()
            assertTrue(runtime.run().success)
            val notes = fixture.notes(NOW)
            val notebook = notes.createNotebook("Migration")
            val note = notes.createNote(NoteInput(notebook.id, "Cross profile", "one authority"))
            assertTrue(runtime.run().success)
            val sourceEpochId = checkNotNull(source.loadEpochPointer()).syncEpochId

            val migrated = WorkspaceRemoteMigrationServiceV2(
                fixture.localRepository,
                WORKSPACE_KEY,
                WRITER_B,
                source,
                target,
                fixture.protocolStore,
                { NOW },
            ).migrate()

            assertTrue(migrated is WorkspaceRemoteMigrationResultV2.Migrated)
            migrated as WorkspaceRemoteMigrationResultV2.Migrated
            assertEquals(sourceEpochId, migrated.sourceEpochId)
            assertEquals(target.loadEpochPointer()?.syncEpochId, migrated.targetEpochId)
            assertEquals(SyncRemoteProfileV2.SELF_HOSTED.wireValue, fixture.protocolStore.loadAuthoritativeEpoch()?.remoteProfile)
            assertEquals(SyncEpochLifecycleV2.READ_ONLY, fixture.protocolStore.loadEpoch(
                SyncRemoteProfileV2.WEB_DAV.wireValue,
                sourceEpochId,
            )?.lifecycle)
            assertTrue(target.allChanges().isEmpty(), "Checkpoint publication is not a dual-written mutation log.")
            val targetContext = WorkspaceSystemV2ContextProvider(
                fixture.localRepository,
                { WORKSPACE_KEY },
                { WRITER_B },
                { SyncRemoteProfileV2.SELF_HOSTED.wireValue },
            ).requireActive()
            assertEquals("one authority", (targetContext.store.loadHeads(WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.NOTE,
                note.id,
            )).single().contentPayload as NoteContentV2).markdownBody)
        }
    }

    @Test
    fun explicitSameProfileIndependentEndpointMigrationUsesOneNewEpoch() {
        val source = InMemoryWorkspaceSyncRemoteV2(
            SyncRemoteProfileV2.WEB_DAV.wireValue,
            bindingId = "webdav-log-v2|https://source.example|/someday/",
        )
        val target = InMemoryWorkspaceSyncRemoteV2(
            SyncRemoteProfileV2.WEB_DAV.wireValue,
            bindingId = "webdav-log-v2|https://target.example|/someday/",
        )
        withRuntimeFixture(source, writerDeviceId = WRITER_A) { fixture ->
            val runtime = fixture.runtime()
            assertTrue(runtime.run().success)
            val notes = fixture.notes(NOW)
            val notebook = notes.createNotebook("Endpoint move")
            val note = notes.createNote(NoteInput(notebook.id, "Same profile", "preserved"))
            assertTrue(runtime.run().success)
            val sourceEpochId = checkNotNull(source.loadEpochPointer()).syncEpochId

            val migrated = WorkspaceRemoteMigrationServiceV2(
                fixture.localRepository,
                WORKSPACE_KEY,
                WRITER_B,
                source,
                target,
                fixture.protocolStore,
                { NOW },
            ).migrate()

            assertTrue(migrated is WorkspaceRemoteMigrationResultV2.Migrated)
            migrated as WorkspaceRemoteMigrationResultV2.Migrated
            assertEquals(sourceEpochId, migrated.sourceEpochId)
            assertEquals(target.authorityBindingId, fixture.protocolStore.loadLocalAuthority()?.authorityBindingId)
            assertEquals(SyncEpochLifecycleV2.READ_ONLY, fixture.protocolStore.loadEpoch(
                SyncRemoteProfileV2.WEB_DAV.wireValue,
                sourceEpochId,
            )?.lifecycle)
            assertTrue(target.allChanges().isEmpty())
            val targetContext = WorkspaceSystemV2ContextProvider(
                fixture.localRepository,
                { WORKSPACE_KEY },
                { WRITER_B },
                { SyncRemoteProfileV2.WEB_DAV.wireValue },
            ).requireActive()
            assertEquals("preserved", (targetContext.store.loadHeads(WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.NOTE,
                note.id,
            )).single().contentPayload as NoteContentV2).markdownBody)
        }
    }

    @Test
    fun migrationPointerRaceBootstrapsWinnerAndImportsLosingSourceHeads() {
        val source = InMemoryWorkspaceSyncRemoteV2(
            SyncRemoteProfileV2.WEB_DAV.wireValue,
            bindingId = "webdav-log-v2|https://source.example|/someday/",
        )
        val target = InMemoryWorkspaceSyncRemoteV2(
            SyncRemoteProfileV2.SELF_HOSTED.wireValue,
            bindingId = "self-hosted-v2|https://target.example",
        )
        withRuntimeFixture(source, writerDeviceId = WRITER_A) { fixture ->
            val runtime = fixture.runtime()
            assertTrue(runtime.run().success)
            val notes = fixture.notes(NOW)
            val notebook = notes.createNotebook("CAS race")
            val note = notes.createNote(NoteInput(notebook.id, "Losing initializer", "must survive"))
            assertTrue(runtime.run().success)

            val winner = WorkspaceCheckpointBuilderV2(WORKSPACE_KEY, WRITER_B).build(
                remoteProfile = target.remoteProfile,
                sourceHeads = listOf(WorkspaceCheckpointSourceHeadV2(
                    WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
                    WORKSPACE_PREFERENCES_ENTITY_ID_V2,
                    WorkspacePreferencesV2(),
                    null,
                    "independent-empty-initializer",
                    null,
                    WRITER_B,
                    null,
                    "11111111-1111-4111-8111-111111111111",
                    "independent-source-digest",
                    NOW,
                )),
                createdAt = NOW,
            )
            target.faults.beforeNextPointerCompareAndSet = {
                winner.chunks.forEach { chunk ->
                    assertTrue(target.putCheckpointChunk(winner.descriptor, chunk.ref, chunk.encryptedObject) is
                        WorkspaceImmutablePutResultV2.Stored)
                }
                assertTrue(target.putCheckpointManifest(winner.descriptor, winner.manifestObject) is
                    WorkspaceImmutablePutResultV2.Stored)
                assertTrue(target.compareAndSetEpochPointer(
                    winner.descriptor,
                    expectedCurrentDigest = null,
                    winner.pointerObject,
                ) is WorkspacePointerPublishResultV2.Published)
            }

            val migrated = WorkspaceRemoteMigrationServiceV2(
                fixture.localRepository,
                WORKSPACE_KEY,
                WRITER_A,
                source,
                target,
                fixture.protocolStore,
                { NOW },
            ).migrate()

            assertTrue(migrated is WorkspaceRemoteMigrationResultV2.Migrated)
            migrated as WorkspaceRemoteMigrationResultV2.Migrated
            assertEquals(winner.descriptor.syncEpochId, migrated.targetEpochId)
            assertTrue(migrated.importedLateObjects >= 2)
            val targetContext = WorkspaceSystemV2ContextProvider(
                fixture.localRepository,
                { WORKSPACE_KEY },
                { WRITER_A },
                { target.remoteProfile },
            ).requireActive()
            assertEquals("must survive", (targetContext.store.loadHeads(WorkspaceEntityKeyV2(
                WorkspaceEntityTypeV2.NOTE,
                note.id,
            )).single().contentPayload as NoteContentV2).markdownBody)
        }
    }

    private fun withRuntimeFixture(
        remote: InMemoryWorkspaceSyncRemoteV2,
        writerDeviceId: String = WRITER_A,
        block: (RuntimeFixture) -> Unit,
    ) {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
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
                    mode = SyncMode.WebDav,
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

        fun runtime(
            clockValue: Instant = NOW,
            transportRemote: WorkspaceSyncRemoteV2 = remote,
            activationEnabled: Boolean = true,
        ): SyncV2RuntimeService = SyncV2RuntimeService(
            mode = SyncMode.WebDav,
            localRepository = localRepository,
            settingsRepository = settings,
            workspaceKeyProvider = { WORKSPACE_KEY },
            writerDeviceIdProvider = { writerDeviceId },
            transportFactory = SyncRemoteTransportFactoryV2 { transportRemote },
            activationEnabled = activationEnabled,
            authorityMutationCoordinator = saien.someday.sync.WorkspaceAuthorityMutationCoordinator(),
            clock = { clockValue },
        )

        fun notes(clockValue: Instant) = SystemV2NotesRepository(
            localRepository,
            { WORKSPACE_KEY },
            { writerDeviceId },
            { remote.remoteProfile },
            clock = { clockValue },
        )
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
