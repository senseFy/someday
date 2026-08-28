@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.crypto.InMemorySecureWorkspaceKeyStore
import saien.someday.data.crypto.WorkspaceKeyRepository
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.crypto.workspaceJoinPackageProvider
import saien.someday.data.crypto.workspaceJoiner
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.settings.AppLanguage
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.EditorPreferences
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SelfHostedSessionSummary
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.domain.settings.WorkspacePreferencesSyncState
import saien.someday.domain.settings.WorkspacePreferencesSyncStatus
import saien.someday.domain.settings.WorkspacePairingReason
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkspaceLocalReplacementV2Test {
    @Test
    fun confirmedReplacementClearsAllGenerationsAndWorkspaceOwnedStateWhilePreservingInstallationState() {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val database = SomedayDatabase(driver)
            val local = SqlDelightLocalDataRepository(database, WRITER, clock = { T1 })
            val settings = TransientWorkspacePreferencesSettingsRepository(
                SqlDelightClientSettingsRepository(local),
                SEEDED_WORKSPACE_PREFERENCES_STATE,
            )
            val session = SelfHostedSessionSummary(
                loggedIn = true,
                userEmail = "owner@example.test",
                deviceId = WRITER,
                deviceName = "This installation",
                devicePlatform = "desktop",
            )
            settings.saveLocalSnapshot(
                ClientSettings(
                    theme = ClientTheme.Dark,
                    appLanguage = AppLanguage.Japanese,
                    editorPreferences = EditorPreferences(
                        previewByDefault = true,
                        markdownToolbarVisible = false,
                    ),
                    onThisDayNotifications = OnThisDayNotificationPreferences(
                        enabled = true,
                        hour = 7,
                        minute = 45,
                    ),
                    defaultNotebookId = OLD_DEFAULT_NOTEBOOK,
                    lastSelectedNotebookId = OLD_LAST_SELECTED_NOTEBOOK,
                    activeDeviceId = WRITER,
                    syncConfiguration = SyncConfiguration(
                        mode = SyncMode.SelfHosted,
                        selfHostedEndpoint = "http://127.0.0.1:3180",
                        selfHostedSession = session,
                        lastError = "old workspace failure",
                    ),
                    workspacePreferencesState = SEEDED_WORKSPACE_PREFERENCES_STATE,
                ),
            )
            local.putLocalOnlySetting(INSTALLATION_SETTING, "preserve-me")
            val deviceBeforeReplacement = local.registerDevice(
                name = "This installation",
                platform = "desktop",
                workspaceKeyMetadata = "old-workspace-metadata",
            )

            val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 1).toByte() })
            val seeded = seedActiveBoundContentfulWorkspace(database, local, settings, key)

            assertEquals(SEEDED_WORKSPACE_PREFERENCES_STATE, settings.load().workspacePreferencesState)
            assertReplacementStatePresent(database, seeded)
            var discarded = false
            database.transaction {
                discarded = discardLocalWorkspaceForReplacementV2(local, settings)
            }
            assertTrue(discarded)
            assertReplacementStateRemoved(database, seeded)

            val after = settings.load()
            assertEquals(AppLanguage.Japanese, after.appLanguage)
            assertEquals(
                OnThisDayNotificationPreferences(enabled = true, hour = 7, minute = 45),
                after.onThisDayNotifications,
            )
            assertEquals(WRITER, after.activeDeviceId)
            assertEquals(SyncMode.SelfHosted, after.syncConfiguration.mode)
            assertEquals("http://127.0.0.1:3180", after.syncConfiguration.selfHostedEndpoint)
            assertEquals(session, after.syncConfiguration.selfHostedSession)
            assertEquals("preserve-me", local.getSetting(INSTALLATION_SETTING)?.value)
            assertEquals(deviceBeforeReplacement, local.getDevice(WRITER))

            assertEquals(ClientTheme.System, after.theme)
            assertEquals(EditorPreferences(), after.editorPreferences)
            assertNull(after.defaultNotebookId)
            assertNull(after.lastSelectedNotebookId)
            assertNull(after.syncConfiguration.lastError)
            assertEquals(WorkspacePreferencesSyncState(), after.workspacePreferencesState)
        } finally {
            driver.close()
        }
    }

    @Test
    fun failedAuthorityBindingRollsBackTheCompleteLockedWorkspaceCleanup() {
        val inviterDriver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        val joiningDriver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val inviterLocal = SqlDelightLocalDataRepository(
                SomedayDatabase(inviterDriver),
                "00000000-0000-4000-8000-000000000099",
                clock = { T1 },
            )
            val inviterKeys = WorkspaceKeyRepository(
                inviterLocal,
                InMemorySecureWorkspaceKeyStore(),
                clock = { T1 },
            )
            inviterKeys.createFirstRunWorkspace("Inviter", "desktop")
            val joinPackage = assertNotNull(
                inviterKeys.workspaceJoinPackageProvider().createPackage().packageData,
            )

            val joiningDatabase = SomedayDatabase(joiningDriver)
            val joiningLocal = SqlDelightLocalDataRepository(joiningDatabase, WRITER, clock = { T1 })
            val joiningSettings = TransientWorkspacePreferencesSettingsRepository(
                SqlDelightClientSettingsRepository(joiningLocal),
                SEEDED_WORKSPACE_PREFERENCES_STATE,
            )
            val joiningKeys = WorkspaceKeyRepository(
                joiningLocal,
                InMemorySecureWorkspaceKeyStore(),
                clock = { T1 },
            )
            val oldWorkspace = joiningKeys.createFirstRunWorkspace("Joining", "desktop")
            val oldMetadata = assertNotNull(joiningKeys.exportRecoveryMetadataJson())
            val oldKey = assertNotNull(joiningKeys.unlockedKeyOrNull())
            val seeded = seedActiveBoundContentfulWorkspace(
                joiningDatabase,
                joiningLocal,
                joiningSettings,
                oldKey,
            )
            joiningKeys.lock()

            val result = joiningKeys.workspaceJoiner(
                deviceName = "Joining",
                platform = "desktop",
                beforeWorkspaceReplacement = {
                    discardLocalWorkspaceForReplacementV2(joiningLocal, joiningSettings)
                },
                afterWorkspaceReplacement = { _, _, _ -> false },
                afterWorkspaceReplacementCommitted = {},
            ).join(
                packageData = joinPackage,
                replaceExistingWorkspace = true,
            )

            assertEquals(WorkspacePairingReason.ReplacementFailed, result.reason)
            assertEquals(oldWorkspace.state.workspaceId, joiningKeys.workspaceIdOrNull())
            assertEquals(oldMetadata, joiningKeys.exportRecoveryMetadataJson())
            assertReplacementStatePresent(joiningDatabase, seeded)
        } finally {
            joiningDriver.close()
            inviterDriver.close()
        }
    }

    private fun repository(
        local: SqlDelightLocalDataRepository,
        key: WorkspaceMasterKey,
    ) = SystemV2NotesRepository(local, { key }, { WRITER }, { PROFILE })

    private fun seedActiveBoundContentfulWorkspace(
        database: SomedayDatabase,
        local: SqlDelightLocalDataRepository,
        settings: ClientSettingsRepository,
        key: WorkspaceMasterKey,
    ): ReplacementState {
        val protocol = SqlDelightSyncProtocolStoreV2(database)
        val draft = ensureWorkspaceLocalDraftV2(local, settings, key)
        val active = protocol.activateEpoch(
            PROFILE,
            draft.descriptor.syncEpochId,
            T1,
            localWriterDeviceId = WRITER,
            authorityBindingId = AUTHORITY,
        )
        val notes = repository(local, key)
        val notebook = notes.createNotebook("Discarded notebook")
        val firstNotebookVersion = checkNotNull(notebook.causalToken).expectedBaseVersionId
        val renamedNotebook = notes.renameNotebook(
            notebook.id,
            "Discarded notebook with a parent version",
            checkNotNull(notebook.causalToken),
        )
        val notebookHeadVersion = checkNotNull(renamedNotebook.causalToken).expectedBaseVersionId
        val note = notes.createNote(
            NoteInput(
                notebookId = notebook.id,
                title = "Discarded note",
                markdownBody = "Content proves this is not an empty draft.",
            ),
        )
        val noteHeadVersion = checkNotNull(note.causalToken).expectedBaseVersionId
        val queries = database.somedayQueries
        val notebookVersion = queries.selectWorkspaceEntityVersionV2(
            active.descriptor.syncEpochId,
            notebookHeadVersion,
        ).executeAsOne()

        queries.insertWorkspaceEntityConflictV2(
            epoch_id = active.descriptor.syncEpochId,
            conflict_id = CONFLICT,
            entity_type = "notebook",
            entity_id = notebook.id,
            base_version_id = firstNotebookVersion,
            reason = "field_conflict",
            conflicting_fields = "[\"title\"]",
            detected_at = T1.toEpochMilliseconds(),
            lifecycle = "active",
            superseded_by_conflict_id = null,
            resolved_by_version_id = null,
        )
        queries.insertWorkspaceEntityConflictHeadV2(
            active.descriptor.syncEpochId,
            CONFLICT,
            notebookHeadVersion,
        )
        queries.insertProjectionWarningV2(
            active.descriptor.syncEpochId,
            "note",
            note.id,
            "test-warning",
            "notebook",
            notebook.id,
            T1.toEpochMilliseconds(),
        )
        queries.insertProjectionWarningV2(
            ORPHAN_EPOCH,
            "note",
            "orphan-note",
            "test-orphan-warning",
            null,
            null,
            T1.toEpochMilliseconds(),
        )
        queries.insertAppliedMutationSystemV2(
            PROFILE,
            active.descriptor.syncEpochId,
            APPLIED_MUTATION,
            notebookHeadVersion,
            notebookVersion.object_digest,
            WRITER,
            T1.toEpochMilliseconds(),
        )
        queries.upsertRemoteCursorSystemV2(
            PROFILE,
            active.descriptor.syncEpochId,
            REMOTE_STREAM,
            "cursor-1",
            "unit-1",
            "unit-digest-1",
            T1.toEpochMilliseconds(),
        )
        queries.insertCheckpointSystemV2(
            PROFILE,
            active.descriptor.syncEpochId,
            EXTRA_CHECKPOINT,
            "manifest-digest",
            "encoded-manifest",
            "published",
            T1.toEpochMilliseconds(),
            T1.toEpochMilliseconds(),
        )
        queries.insertCheckpointObjectSystemV2(
            PROFILE,
            active.descriptor.syncEpochId,
            EXTRA_CHECKPOINT,
            0,
            0,
            notebookHeadVersion,
            notebookVersion.object_digest,
            "encoded-checkpoint-object",
        )
        queries.insertControlObjectSystemV2(
            PROFILE,
            active.descriptor.syncEpochId,
            "sync_checkpoint_manifest_v2",
            CONTROL_OBJECT,
            "control-digest",
            "encoded-control-object",
            "published",
            T1.toEpochMilliseconds(),
            T1.toEpochMilliseconds(),
        )
        queries.insertSourceImportSystemV2(
            remote_profile = PROFILE,
            epoch_id = active.descriptor.syncEpochId,
            source_profile = "source-test",
            source_epoch = null,
            source_writer_id = WRITER,
            source_mutation_id = null,
            source_object_id = SOURCE_OBJECT,
            source_digest = "source-digest",
            entity_type = "notebook",
            source_entity_id = notebook.id,
            mapped_entity_id = notebook.id,
            version_id = notebookHeadVersion,
            mutation_id = SOURCE_MUTATION,
            state = "committed",
            created_at = T1.toEpochMilliseconds(),
            published_at = null,
        )
        queries.insertMediaAsset(
            asset_id = MEDIA_ASSET,
            content_sha256 = MEDIA_CONTENT_DIGEST,
            byte_size = 3,
            media_type = "image/png",
            original_file_name = "discarded.png",
            pixel_width = 1,
            pixel_height = 1,
            created_at = T1.toEpochMilliseconds(),
            local_state = "available",
            last_verified_at = T1.toEpochMilliseconds(),
            published_authority_binding_id = AUTHORITY,
            published_workspace_id = OLD_WORKSPACE,
            published_object_digest = "media-object-digest",
        )

        protocol.recordDeadLetter(
            SyncDeadLetterInputV2(
                remoteProfile = PROFILE,
                epochId = active.descriptor.syncEpochId,
                streamId = REMOTE_STREAM,
                unitId = "dead-unit",
                cursorValue = "cursor-2",
                unitDigest = "dead-unit-digest",
                objectId = noteHeadVersion,
                objectDigest = "dead-object-digest",
                authenticatedUnit = "opaque",
                failureClass = SyncDeadLetterFailureClassV2.RETRYABLE_DEPENDENCY,
                safeErrorCode = "dependency_unresolved",
                safeErrorMessage = "A dependency is still missing.",
            ),
            T1,
        )
        protocol.startRun(PROFILE, active.descriptor.syncEpochId, T1)
        protocol.startRun(PROFILE, null, T1)

        val abandoned = extraEpoch(ABANDONED_EPOCH, ABANDONED_CHECKPOINT)
        protocol.persistPreparingEpoch(PROFILE, abandoned, "abandoned-descriptor-digest")
        protocol.abandonPreparingEpoch(
            PROFILE,
            ABANDONED_EPOCH,
            "superseded_draft",
            "This draft is retained until replacement.",
        )
        protocol.persistPreparingEpoch(
            PROFILE,
            extraEpoch(PREPARING_EPOCH, PREPARING_CHECKPOINT),
            "preparing-descriptor-digest",
        )

        return ReplacementState(
            activeEpochId = active.descriptor.syncEpochId,
            notebookId = notebook.id,
            firstNotebookVersion = firstNotebookVersion,
            notebookHeadVersion = notebookHeadVersion,
            noteId = note.id,
        )
    }

    private fun assertReplacementStatePresent(database: SomedayDatabase, state: ReplacementState) {
        val queries = database.somedayQueries
        val protocol = SqlDelightSyncProtocolStoreV2(database)

        assertEquals(SyncEpochLifecycleV2.ACTIVE, protocol.loadAuthoritativeEpoch()?.lifecycle)
        assertEquals(AUTHORITY, protocol.loadLocalAuthority()?.authorityBindingId)
        assertEquals(
            setOf(SyncEpochLifecycleV2.ACTIVE, SyncEpochLifecycleV2.ABANDONED, SyncEpochLifecycleV2.PREPARING),
            protocol.loadAllEpochs().map { it.lifecycle }.toSet(),
        )
        assertTrue(queries.selectWorkspaceEntityVersionsByEpochV2(state.activeEpochId).executeAsList().isNotEmpty())
        assertContains(
            queries.selectWorkspaceEntityParentsV2(state.activeEpochId, state.notebookHeadVersion).executeAsList(),
            state.firstNotebookVersion,
        )
        assertTrue(queries.selectAllWorkspaceEntityHeadsV2(state.activeEpochId).executeAsList().isNotEmpty())
        assertTrue(
            queries.selectWorkspaceEntityConflictsV2(
                state.activeEpochId,
                "notebook",
                state.notebookId,
            ).executeAsList().isNotEmpty(),
        )
        assertEquals(
            listOf(state.notebookHeadVersion),
            queries.selectWorkspaceEntityConflictHeadsV2(state.activeEpochId, CONFLICT).executeAsList(),
        )
        assertNotNull(queries.selectNoteProjectionSystemV2(state.activeEpochId, state.noteId).executeAsOneOrNull())
        assertNotNull(queries.selectNotebookProjectionV2(state.activeEpochId, state.notebookId).executeAsOneOrNull())
        assertNotNull(queries.selectWorkspacePreferencesProjectionV2(state.activeEpochId).executeAsOneOrNull())
        assertTrue(queries.selectProjectionWarningsV2(state.activeEpochId).executeAsList().isNotEmpty())
        assertTrue(queries.selectProjectionWarningsV2(ORPHAN_EPOCH).executeAsList().isNotEmpty())
        assertTrue(queries.selectPendingMutationsSystemV2(PROFILE, state.activeEpochId).executeAsList().isNotEmpty())
        assertNotNull(
            queries.selectAppliedMutationSystemV2(PROFILE, state.activeEpochId, APPLIED_MUTATION)
                .executeAsOneOrNull(),
        )
        assertNotNull(
            queries.selectRemoteCursorSystemV2(PROFILE, state.activeEpochId, REMOTE_STREAM)
                .executeAsOneOrNull(),
        )
        assertNotNull(
            queries.selectCheckpointSystemV2(PROFILE, state.activeEpochId, EXTRA_CHECKPOINT)
                .executeAsOneOrNull(),
        )
        assertTrue(
            queries.selectCheckpointObjectsSystemV2(PROFILE, state.activeEpochId, EXTRA_CHECKPOINT)
                .executeAsList().isNotEmpty(),
        )
        assertNotNull(
            queries.selectControlObjectSystemV2(
                PROFILE,
                state.activeEpochId,
                "sync_checkpoint_manifest_v2",
                CONTROL_OBJECT,
            ).executeAsOneOrNull(),
        )
        assertNotNull(
            queries.selectSourceImportByMutationSystemV2(PROFILE, state.activeEpochId, SOURCE_MUTATION)
                .executeAsOneOrNull(),
        )
        assertTrue(protocol.loadUnresolvedDeadLetters(PROFILE, state.activeEpochId).isNotEmpty())
        assertEquals(2, protocol.loadRuns(PROFILE).size)
        assertEquals(1, queries.selectAllMediaAssets().executeAsList().size)
    }

    private fun assertReplacementStateRemoved(database: SomedayDatabase, state: ReplacementState) {
        val queries = database.somedayQueries
        val protocol = SqlDelightSyncProtocolStoreV2(database)

        assertTrue(protocol.loadAllEpochs().isEmpty())
        assertNull(protocol.loadLocalAuthority())
        assertTrue(queries.selectWorkspaceEntityVersionsByEpochV2(state.activeEpochId).executeAsList().isEmpty())
        assertTrue(
            queries.selectWorkspaceEntityParentsV2(state.activeEpochId, state.notebookHeadVersion)
                .executeAsList().isEmpty(),
        )
        assertTrue(queries.selectAllWorkspaceEntityHeadsV2(state.activeEpochId).executeAsList().isEmpty())
        assertTrue(
            queries.selectWorkspaceEntityConflictsV2(
                state.activeEpochId,
                "notebook",
                state.notebookId,
            ).executeAsList().isEmpty(),
        )
        assertTrue(
            queries.selectWorkspaceEntityConflictHeadsV2(state.activeEpochId, CONFLICT)
                .executeAsList().isEmpty(),
        )
        assertNull(queries.selectNoteProjectionSystemV2(state.activeEpochId, state.noteId).executeAsOneOrNull())
        assertNull(queries.selectNotebookProjectionV2(state.activeEpochId, state.notebookId).executeAsOneOrNull())
        assertNull(queries.selectWorkspacePreferencesProjectionV2(state.activeEpochId).executeAsOneOrNull())
        assertTrue(queries.selectProjectionWarningsV2(state.activeEpochId).executeAsList().isEmpty())
        assertTrue(queries.selectProjectionWarningsV2(ORPHAN_EPOCH).executeAsList().isEmpty())
        assertTrue(queries.selectPendingMutationsSystemV2(PROFILE, state.activeEpochId).executeAsList().isEmpty())
        assertNull(
            queries.selectAppliedMutationSystemV2(PROFILE, state.activeEpochId, APPLIED_MUTATION)
                .executeAsOneOrNull(),
        )
        assertNull(
            queries.selectRemoteCursorSystemV2(PROFILE, state.activeEpochId, REMOTE_STREAM)
                .executeAsOneOrNull(),
        )
        assertNull(
            queries.selectCheckpointSystemV2(PROFILE, state.activeEpochId, EXTRA_CHECKPOINT)
                .executeAsOneOrNull(),
        )
        assertTrue(
            queries.selectCheckpointObjectsSystemV2(PROFILE, state.activeEpochId, EXTRA_CHECKPOINT)
                .executeAsList().isEmpty(),
        )
        assertNull(
            queries.selectControlObjectSystemV2(
                PROFILE,
                state.activeEpochId,
                "sync_checkpoint_manifest_v2",
                CONTROL_OBJECT,
            ).executeAsOneOrNull(),
        )
        assertNull(
            queries.selectSourceImportByMutationSystemV2(PROFILE, state.activeEpochId, SOURCE_MUTATION)
                .executeAsOneOrNull(),
        )
        assertTrue(protocol.loadUnresolvedDeadLetters(PROFILE, state.activeEpochId).isEmpty())
        assertTrue(protocol.loadRuns(PROFILE).isEmpty())
        assertTrue(queries.selectAllMediaAssets().executeAsList().isEmpty())
    }

    private fun extraEpoch(epochId: String, checkpointId: String) = SyncEpochDescriptorV2(
        syncEpochId = epochId,
        remoteProfile = PROFILE,
        checkpointId = checkpointId,
        checkpointDigest = "cd2:hmac-sha256:${"12".repeat(32)}",
        createdByDeviceId = WRITER,
        createdAt = T1,
    )

    private class TransientWorkspacePreferencesSettingsRepository(
        private val delegate: ClientSettingsRepository,
        initialState: WorkspacePreferencesSyncState,
    ) : ClientSettingsRepository {
        private var workspacePreferencesState = initialState

        override fun load(): ClientSettings = delegate.load().copy(
            workspacePreferencesState = workspacePreferencesState,
        )

        override fun save(settings: ClientSettings): ClientSettings {
            workspacePreferencesState = settings.workspacePreferencesState
            return delegate.save(settings).copy(workspacePreferencesState = workspacePreferencesState)
        }

        override fun saveLocalSnapshot(settings: ClientSettings): ClientSettings {
            workspacePreferencesState = settings.workspacePreferencesState
            return delegate.saveLocalSnapshot(settings).copy(workspacePreferencesState = workspacePreferencesState)
        }
    }

    private data class ReplacementState(
        val activeEpochId: String,
        val notebookId: String,
        val firstNotebookVersion: String,
        val notebookHeadVersion: String,
        val noteId: String,
    )

    private companion object {
        const val WRITER = "00000000-0000-4000-8000-000000000001"
        val PROFILE = SyncRemoteProfileV2.SELF_HOSTED.wireValue
        const val AUTHORITY = "self-hosted-v2|http://127.0.0.1:3180|owner"
        const val OLD_DEFAULT_NOTEBOOK = "00000000-0000-4000-8000-0000000000b1"
        const val OLD_LAST_SELECTED_NOTEBOOK = "00000000-0000-4000-8000-0000000000b2"
        const val INSTALLATION_SETTING = "installation.test.preference"
        const val CONFLICT = "00000000-0000-4000-8000-0000000000f1"
        const val APPLIED_MUTATION = "00000000-0000-4000-8000-0000000000a1"
        const val SOURCE_MUTATION = "00000000-0000-4000-8000-0000000000a2"
        const val SOURCE_OBJECT = "source-object-1"
        const val REMOTE_STREAM = "writer:$WRITER"
        const val EXTRA_CHECKPOINT = "00000000-0000-4000-8000-0000000000c1"
        const val CONTROL_OBJECT = "control-object-1"
        const val ABANDONED_EPOCH = "00000000-0000-4000-8000-0000000000e1"
        const val ABANDONED_CHECKPOINT = "00000000-0000-4000-8000-0000000000c2"
        const val PREPARING_EPOCH = "00000000-0000-4000-8000-0000000000e2"
        const val PREPARING_CHECKPOINT = "00000000-0000-4000-8000-0000000000c3"
        const val ORPHAN_EPOCH = "00000000-0000-4000-8000-0000000000ef"
        const val MEDIA_ASSET = "abababababababababababababababababababababababababababababababab"
        const val MEDIA_CONTENT_DIGEST = "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"
        const val OLD_WORKSPACE = "workspace-11111111111111111111111111111111"
        val SEEDED_WORKSPACE_PREFERENCES_STATE = WorkspacePreferencesSyncState(
            status = WorkspacePreferencesSyncStatus.Pending,
            warning = "Old workspace preferences are pending.",
        )
        val T1 = Instant.fromEpochMilliseconds(1_000)
    }
}
