@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.integration

import app.cash.sqldelight.db.SqlDriver
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.export.LocalDataExporter
import saien.someday.data.export.LocalDataImporter
import saien.someday.data.importing.dayone.DayOneImportService
import saien.someday.data.importing.dayone.DayOneJsonDocument
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.sync.WorkspaceAuthorityMutationCoordinator
import saien.someday.sync.causality.v2.ActiveWorkspaceSystemV2
import saien.someday.sync.causality.v2.AuthorityCoordinatedLocalDataTransferV2
import saien.someday.sync.causality.v2.CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2
import saien.someday.sync.causality.v2.LocalWorkspaceMutationV2
import saien.someday.sync.causality.v2.NoteContentV2
import saien.someday.sync.causality.v2.NoteLocationV2
import saien.someday.sync.causality.v2.NotebookContentV2
import saien.someday.sync.causality.v2.SqlDelightSyncProtocolStoreV2
import saien.someday.sync.causality.v2.SyncRemoteTransportFactoryV2
import saien.someday.sync.causality.v2.SyncCoordinatorStatusV2
import saien.someday.sync.causality.v2.SyncRemoteProfileV2
import saien.someday.sync.causality.v2.SyncV2RuntimeService
import saien.someday.sync.causality.v2.WORKSPACE_PREFERENCES_ENTITY_ID_V2
import saien.someday.sync.causality.v2.WorkspaceCheckpointBuilderV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPersistResultV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPersistenceV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPublishResultV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointPublisherV2
import saien.someday.sync.causality.v2.WorkspaceCheckpointSourceHeadV2
import saien.someday.sync.causality.v2.WorkspaceEntityKeyV2
import saien.someday.sync.causality.v2.WorkspaceEntityTypeV2
import saien.someday.sync.causality.v2.WorkspaceLocalCommitResultV2
import saien.someday.sync.causality.v2.WorkspaceLocalDataTransferV2
import saien.someday.sync.causality.v2.WorkspacePreferencesV2
import saien.someday.sync.causality.v2.WorkspaceSyncCoordinatorV2
import saien.someday.sync.causality.v2.WorkspaceSyncRemoteV2
import saien.someday.sync.causality.v2.WorkspaceSystemV2ContextProvider
import saien.someday.sync.selfhosted.JdkSelfHostedSyncTransport
import saien.someday.sync.selfhosted.SelfHostedSyncClient
import saien.someday.sync.selfhosted.SelfHostedSyncRemoteV2
import saien.someday.sync.webdav.JdkWebDavTransport
import saien.someday.sync.webdav.WebDavClient
import saien.someday.sync.webdav.WebDavConfiguration
import saien.someday.sync.webdav.WebDavRequest
import saien.someday.sync.webdav.WorkspaceWebDavSyncRemoteV2
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Release E2E corpus for someday-system-v2. The dedicated realRemoteTest task
 * deliberately has no Assume/skip or localhost fallback: its caller must
 * provide both real services explicitly.
 */
class WorkspaceV2RealProfilesIntegrationTest {
    @Test
    fun realWebDavFreshDayOneImportPublishesFirstEpochAndBootstrapsFollower() {
        val endpoint = requiredEnv("SOMEDAY_WEBDAV_ENDPOINT")
        val username = requiredEnv("SOMEDAY_WEBDAV_USERNAME")
        val password = requiredEnv("SOMEDAY_WEBDAV_PASSWORD")
        val appDirectory = "/someday-system-v2-fresh-import-${UUID.randomUUID()}/"
        val configuration = WebDavConfiguration(endpoint, username, password, appDirectory)
        val transport = JdkWebDavTransport()
        val cleanupClient = WebDavClient(configuration, transport)
        val key = testWorkspaceKey()
        val leader = device("webdav-fresh-import-leader")
        val follower = device("webdav-fresh-import-follower")
        val leaderWriter = UUID.randomUUID().toString()
        val followerWriter = UUID.randomUUID().toString()
        try {
            val leaderRemote = WorkspaceWebDavSyncRemoteV2(
                WebDavClient(configuration, transport),
                key,
                leaderWriter,
                SqlDelightSyncProtocolStoreV2(leader.database),
                clock = { SYNC_AT },
            )
            val leaderSettings = webDavSettings(leaderWriter)
            val leaderCoordinator = WorkspaceAuthorityMutationCoordinator()
            val leaderTransfer = localDataTransfer(
                leader,
                key,
                leaderWriter,
                leaderSettings,
                leaderCoordinator,
            )
            val imported = DayOneImportService(
                leader.local,
                authoritativeImporter = leaderTransfer::importDocument,
            ).importDocuments(
                listOf(
                    DayOneJsonDocument(
                        "Fresh Day One",
                        """
                            {
                              "entries": [
                                {
                                  "uuid": "22222222-2222-4222-8222-222222222222",
                                  "text": "# Fresh import over WebDAV\nVisible on the follower.",
                                  "creationDate": "2026-07-19T00:00:00Z",
                                  "modifiedDate": "2026-07-19T01:00:00Z",
                                  "timeZone": "Asia/Shanghai"
                                }
                              ]
                            }
                        """.trimIndent(),
                    ),
                ),
            )
            assertEquals(1, imported.notesCreated)
            assertEquals(null, SqlDelightSyncProtocolStoreV2(leader.database).loadAuthoritativeEpoch())
            val preAuthorityBackup = LocalDataExporter(
                leader.local,
                clock = { SYNC_AT },
                authoritativeDocumentProvider = leaderTransfer::exportDocument,
            ).exportDocument()
            val backupOnlyNote = preAuthorityBackup.notes.single().copy(
                id = "real-webdav-backup-only-note",
                title = "Backup import over WebDAV",
                markdownBody = "The pre-authority backup also survives.",
                excerpt = "The pre-authority backup also survives.",
                createdAt = "2026-07-19T02:00:00Z",
                updatedAt = "2026-07-19T02:00:00Z",
                currentVersionId = "real-webdav-backup-only-version",
                parentVersionId = null,
                baseVersionId = null,
                versionDeviceId = "backup-import",
                mergeMetadataJson = null,
            )
            val backupImported = LocalDataImporter(
                leader.local,
                authoritativeImporter = leaderTransfer::importDocument,
            ).importDocument(
                preAuthorityBackup.copy(notes = preAuthorityBackup.notes + backupOnlyNote),
            )
            assertEquals(1, backupImported.notesCreated)

            val leaderResult = runtime(
                leader,
                key,
                leaderWriter,
                leaderSettings,
                leaderCoordinator,
                leaderRemote,
            ).run()
            assertTrue(leaderResult.success, leaderResult.message)
            assertNotNull(SqlDelightSyncProtocolStoreV2(leader.database).loadAuthoritativeEpoch())

            val followerRemote = WorkspaceWebDavSyncRemoteV2(
                WebDavClient(configuration, transport),
                key,
                followerWriter,
                SqlDelightSyncProtocolStoreV2(follower.database),
                clock = { SYNC_AT },
            )
            val followerSettings = webDavSettings(followerWriter)
            val followerCoordinator = WorkspaceAuthorityMutationCoordinator()
            val followerResult = runtime(
                follower,
                key,
                followerWriter,
                followerSettings,
                followerCoordinator,
                followerRemote,
            ).run()
            assertTrue(followerResult.success, followerResult.message)
            val followerDocument = LocalDataExporter(
                follower.local,
                clock = { SYNC_AT },
                authoritativeDocumentProvider = localDataTransfer(
                    follower,
                    key,
                    followerWriter,
                    followerSettings,
                    followerCoordinator,
                )::exportDocument,
            ).exportDocument()
            assertEquals(listOf("Fresh Day One"), followerDocument.notebooks.map { it.title })
            assertEquals(
                setOf("Fresh import over WebDAV", "Backup import over WebDAV"),
                followerDocument.notes.map { it.title }.toSet(),
            )
            assertEquals(
                "Visible on the follower.",
                followerDocument.notes.single { it.title == "Fresh import over WebDAV" }.markdownBody.lines().last(),
            )
        } finally {
            runCatching {
                transport.execute(configuration, WebDavRequest("DELETE", cleanupClient.pathResolver().root))
            }
            leader.close()
            follower.close()
        }
    }

    @Test
    fun realWebDavConvergesWholeProductCorpus() {
        val endpoint = requiredEnv("SOMEDAY_WEBDAV_ENDPOINT")
        val username = requiredEnv("SOMEDAY_WEBDAV_USERNAME")
        val password = requiredEnv("SOMEDAY_WEBDAV_PASSWORD")
        val appDirectory = "/someday-system-v2-it-${UUID.randomUUID()}/"
        val configuration = WebDavConfiguration(endpoint, username, password, appDirectory)
        val transport = JdkWebDavTransport()
        val cleanupClient = WebDavClient(configuration, transport)
        val key = testWorkspaceKey()
        val leader = device("webdav-leader")
        val follower = device("webdav-follower")
        val leaderWriter = UUID.randomUUID().toString()
        val followerWriter = UUID.randomUUID().toString()
        try {
            val leaderRemote = WorkspaceWebDavSyncRemoteV2(
                WebDavClient(configuration, transport),
                key,
                leaderWriter,
                SqlDelightSyncProtocolStoreV2(leader.database),
                clock = { SYNC_AT },
            )
            val followerRemote = WorkspaceWebDavSyncRemoteV2(
                WebDavClient(configuration, transport),
                key,
                followerWriter,
                SqlDelightSyncProtocolStoreV2(follower.database),
                clock = { SYNC_AT },
            )
            runCorpus(
                key = key,
                remoteProfile = SyncRemoteProfileV2.WEB_DAV.wireValue,
                leader = leader,
                follower = follower,
                leaderWriter = leaderWriter,
                followerWriter = followerWriter,
                leaderRemote = leaderRemote,
                followerRemote = followerRemote,
            )
            val opaqueRemoteText = cleanupClient.discoverRecursively(maxDepth = 12)
                .filterNot { it.collection }
                .mapNotNull { cleanupClient.getRawObject(it.path)?.bytes?.decodeToString() }
                .joinToString("\n")
            mapOf(
                "note title" to NOTE_TITLE_SENTINEL,
                "note body" to NOTE_BODY_SENTINEL,
                "location" to LOCATION_SENTINEL,
                "notebook title" to NOTEBOOK_SENTINEL,
                "WebDAV username" to username,
                "WebDAV credential" to password,
                "workspace key material" to WORKSPACE_KEY_BASE64_SENTINEL,
            ).forEach { (label, sentinel) ->
                assertFalse(opaqueRemoteText.contains(sentinel), "V2 WebDAV metadata leaked $label.")
            }
        } finally {
            runCatching {
                transport.execute(configuration, WebDavRequest("DELETE", cleanupClient.pathResolver().root))
            }
            leader.close()
            follower.close()
        }
    }

    @Test
    fun realSelfHostedConvergesWholeProductCorpus() {
        val endpoint = requiredEnv("SOMEDAY_E2E_ENDPOINT")
        val transport = JdkSelfHostedSyncTransport()
        val client = SelfHostedSyncClient(endpoint, transport)
        val unique = UUID.randomUUID().toString()
        val email = "system-v2-$unique@example.com"
        val password = "System-v2-E2E-$unique"
        val leaderSession = client.registerAndConnect(email, password, "V2 E2E leader", "desktop")
        val followerSession = client.loginAndConnect(email, password, "V2 E2E follower", "ios")
        val key = testWorkspaceKey()
        val leader = device("self-hosted-leader")
        val follower = device("self-hosted-follower")
        try {
            runCorpus(
                key = key,
                remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
                leader = leader,
                follower = follower,
                leaderWriter = leaderSession.deviceId,
                followerWriter = followerSession.deviceId,
                leaderRemote = SelfHostedSyncRemoteV2(
                    endpoint,
                    key,
                    { leaderSession.accessToken },
                    transport,
                ),
                followerRemote = SelfHostedSyncRemoteV2(
                    endpoint,
                    key,
                    { followerSession.accessToken },
                    transport,
                ),
            )
            val opaqueServerRows = readOpaqueSelfHostedRows(email)
            mapOf(
                "note title" to NOTE_TITLE_SENTINEL,
                "note body" to NOTE_BODY_SENTINEL,
                "location" to LOCATION_SENTINEL,
                "notebook title" to NOTEBOOK_SENTINEL,
                "account credential" to password,
                "workspace key material" to WORKSPACE_KEY_BASE64_SENTINEL,
            ).forEach { (label, sentinel) ->
                assertFalse(opaqueServerRows.contains(sentinel), "V2 self-hosted rows leaked $label.")
            }
        } finally {
            leader.close()
            follower.close()
        }
    }

    private fun runCorpus(
        key: WorkspaceMasterKey,
        remoteProfile: String,
        leader: Device,
        follower: Device,
        leaderWriter: String,
        followerWriter: String,
        leaderRemote: WorkspaceSyncRemoteV2,
        followerRemote: WorkspaceSyncRemoteV2,
    ) {
        val prepared = WorkspaceCheckpointBuilderV2(key, leaderWriter).build(
            remoteProfile = remoteProfile,
            sourceHeads = checkpointSources(leaderWriter),
            createdAt = ROOT_AT,
        )
        assertIs<WorkspaceCheckpointPersistResultV2.Ready>(
            WorkspaceCheckpointPersistenceV2(leader.local, key, leaderWriter).persist(prepared),
        )
        assertIs<WorkspaceCheckpointPublishResultV2.Published>(
            WorkspaceCheckpointPublisherV2(leader.local, leaderRemote).publish(prepared),
        )

        val leaderContext = context(leader, key, leaderWriter, remoteProfile)
        assertSuccessful(coordinator(follower, key, followerWriter, followerRemote).syncOnce())
        val followerContext = context(follower, key, followerWriter, remoteProfile)
        assertEquivalentWorkspace(leaderContext, followerContext)

        commitConcurrentSide(leaderContext, remoteProfile, leaderSide = true)
        commitConcurrentSide(followerContext, remoteProfile, leaderSide = false)

        assertSuccessful(coordinator(leader, key, leaderWriter, leaderRemote).syncOnce())
        assertSuccessful(coordinator(follower, key, followerWriter, followerRemote).syncOnce())
        assertSuccessful(coordinator(leader, key, leaderWriter, leaderRemote).syncOnce())
        assertSuccessful(coordinator(follower, key, followerWriter, followerRemote).syncOnce())

        assertEquivalentWorkspace(leaderContext, followerContext)
        assertTrue(leaderContext.store.loadPending(remoteProfile).isEmpty())
        assertTrue(followerContext.store.loadPending(remoteProfile).isEmpty())
        assertTrue(leaderContext.store.loadActiveConflicts().isEmpty())
        assertTrue(followerContext.store.loadActiveConflicts().isEmpty())

        val note = leaderContext.store.loadProjection(NOTE_KEY)?.content as NoteContentV2
        assertEquals("leader body", note.markdownBody)
        assertEquals("follower place", note.location?.placeText)
        val notebook = leaderContext.store.loadProjection(NOTEBOOK_KEY)?.content as NotebookContentV2
        assertEquals("Leader journal", notebook.title)
        assertEquals(99L, notebook.sortOrder)
        val preferences = leaderContext.store.loadProjection(PREFERENCES_KEY)?.content as WorkspacePreferencesV2
        assertEquals(saien.someday.sync.causality.v2.WorkspaceThemeV2.DARK, preferences.theme)
        assertTrue(preferences.previewByDefault)
    }

    private fun commitConcurrentSide(
        context: ActiveWorkspaceSystemV2,
        remoteProfile: String,
        leaderSide: Boolean,
    ) {
        val note = context.store.loadHeads(NOTE_KEY).single()
        val notebook = context.store.loadHeads(NOTEBOOK_KEY).single()
        val preferences = context.store.loadHeads(PREFERENCES_KEY).single()
        val noteContent = note.contentPayload as NoteContentV2
        val notebookContent = notebook.contentPayload as NotebookContentV2
        val preferenceContent = preferences.contentPayload as WorkspacePreferencesV2
        val versions = if (leaderSide) {
            listOf(
                context.factory.createContentChild(
                    note,
                    noteContent.copy(markdownBody = "leader body"),
                    context.deviceActorId,
                    LEADER_EDIT_AT,
                ),
                context.factory.createContentChild(
                    notebook,
                    notebookContent.copy(title = "Leader journal"),
                    context.deviceActorId,
                    LEADER_EDIT_AT,
                ),
                context.factory.createContentChild(
                    preferences,
                    preferenceContent.copy(theme = saien.someday.sync.causality.v2.WorkspaceThemeV2.DARK),
                    context.deviceActorId,
                    LEADER_EDIT_AT,
                ),
            )
        } else {
            listOf(
                context.factory.createContentChild(
                    note,
                    noteContent.copy(
                        location = NoteLocationV2(31.2304, 121.4737, "follower place", 4.0, 10.0, FOLLOWER_EDIT_AT),
                    ),
                    context.deviceActorId,
                    FOLLOWER_EDIT_AT,
                ),
                context.factory.createContentChild(
                    notebook,
                    notebookContent.copy(sortOrder = 99),
                    context.deviceActorId,
                    FOLLOWER_EDIT_AT,
                ),
                context.factory.createContentChild(
                    preferences,
                    preferenceContent.copy(previewByDefault = true),
                    context.deviceActorId,
                    FOLLOWER_EDIT_AT,
                ),
            )
        }
        assertIs<WorkspaceLocalCommitResultV2.Committed>(
            context.store.commitLocalMutations(
                versions.map { version ->
                    LocalWorkspaceMutationV2(
                        remoteProfile,
                        context.factory.newMutationId(),
                        version,
                        if (leaderSide) LEADER_EDIT_AT else FOLLOWER_EDIT_AT,
                    )
                },
            ),
        )
    }

    private fun assertEquivalentWorkspace(first: ActiveWorkspaceSystemV2, second: ActiveWorkspaceSystemV2) {
        assertEquals(first.store.loadEntityKeys(), second.store.loadEntityKeys())
        first.store.loadEntityKeys().forEach { key ->
            val firstHeads = first.store.loadHeads(key)
            val secondHeads = second.store.loadHeads(key)
            assertEquals(firstHeads.map { it.versionId }, secondHeads.map { it.versionId }, key.toString())
            assertEquals(firstHeads.map { it.objectDigest }, secondHeads.map { it.objectDigest }, key.toString())
            assertEquals(first.store.loadProjection(key), second.store.loadProjection(key), key.toString())
            firstHeads.zip(secondHeads).forEach { (left, right) ->
                assertContentEquals(first.materializer.fullEnvelopeBytes(left), second.materializer.fullEnvelopeBytes(right))
            }
        }
    }

    private fun assertSuccessful(summary: saien.someday.sync.causality.v2.WorkspaceSyncSummaryV2) {
        assertEquals(SyncCoordinatorStatusV2.SUCCESS, summary.status, summary.toString())
    }

    private fun checkpointSources(writerId: String): List<WorkspaceCheckpointSourceHeadV2> = listOf(
        WorkspaceCheckpointSourceHeadV2(
            WorkspaceEntityTypeV2.NOTE,
            NOTE_ID,
            NoteContentV2(
                NOTEBOOK_ID,
                NOTE_TITLE_SENTINEL,
                NOTE_BODY_SENTINEL,
                ROOT_AT,
                "Asia/Shanghai",
                NoteLocationV2(null, null, LOCATION_SENTINEL, null, null, ROOT_AT),
            ),
            null,
            "fresh-local-v2",
            null,
            writerId,
            null,
            "source-note",
            "source-note-digest",
        ),
        WorkspaceCheckpointSourceHeadV2(
            WorkspaceEntityTypeV2.NOTEBOOK,
            NOTEBOOK_ID,
            NotebookContentV2(NOTEBOOK_SENTINEL, 0, ROOT_AT),
            null,
            "fresh-local-v2",
            null,
            writerId,
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
            writerId,
            null,
            "source-preferences",
            "source-preferences-digest",
        ),
    ).sortedWith(CHECKPOINT_SOURCE_COMPARATOR_SYSTEM_V2)

    private fun coordinator(
        device: Device,
        key: WorkspaceMasterKey,
        writerId: String,
        remote: WorkspaceSyncRemoteV2,
    ) = WorkspaceSyncCoordinatorV2(
        device.local,
        key,
        writerId,
        remote,
        SqlDelightSyncProtocolStoreV2(device.database),
        clock = { SYNC_AT },
    )

    private fun webDavSettings(writerDeviceId: String): InMemorySettingsRepository =
        InMemorySettingsRepository(
            ClientSettings(
                activeDeviceId = writerDeviceId,
                syncConfiguration = SyncConfiguration(mode = SyncMode.WebDav),
            ),
        )

    private fun runtime(
        device: Device,
        key: WorkspaceMasterKey,
        writerDeviceId: String,
        settings: ClientSettingsRepository,
        authorityMutationCoordinator: WorkspaceAuthorityMutationCoordinator,
        remote: WorkspaceSyncRemoteV2,
    ): SyncV2RuntimeService =
        SyncV2RuntimeService(
            mode = SyncMode.WebDav,
            localRepository = device.local,
            settingsRepository = settings,
            workspaceKeyProvider = { key },
            writerDeviceIdProvider = { writerDeviceId },
            transportFactory = SyncRemoteTransportFactoryV2 { remote },
            activationEnabled = true,
            authorityMutationCoordinator = authorityMutationCoordinator,
            clock = { SYNC_AT },
        )

    private fun localDataTransfer(
        device: Device,
        key: WorkspaceMasterKey,
        writerDeviceId: String,
        settings: ClientSettingsRepository,
        authorityMutationCoordinator: WorkspaceAuthorityMutationCoordinator,
    ): AuthorityCoordinatedLocalDataTransferV2 =
        AuthorityCoordinatedLocalDataTransferV2(
            localRepository = device.local,
            authorityMutationCoordinator = authorityMutationCoordinator,
            v2Transfer = WorkspaceLocalDataTransferV2(
                localRepository = device.local,
                settingsRepository = settings,
                workspaceKeyProvider = { key },
                writerDeviceIdProvider = { writerDeviceId },
                remoteProfileProvider = { SyncRemoteProfileV2.WEB_DAV.wireValue },
            ),
        )

    private fun context(
        device: Device,
        key: WorkspaceMasterKey,
        writerId: String,
        remoteProfile: String,
    ) = WorkspaceSystemV2ContextProvider(
        device.local,
        { key },
        { writerId },
        { remoteProfile },
    ).requireActive()

    private fun device(label: String): Device {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        val database = SomedayDatabase(driver)
        return Device(driver, database, SqlDelightLocalDataRepository(database, label, clock = { SYNC_AT }))
    }

    private fun testWorkspaceKey(): WorkspaceMasterKey =
        SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { index -> (index + 17).toByte() })

    private fun requiredEnv(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: error("$name is required by :integration-tests:realRemoteTest.")

    private fun readOpaqueSelfHostedRows(email: String): String {
        val databaseUrl = requiredEnv("SOMEDAY_DB_URL")
        val databaseUser = requiredEnv("SOMEDAY_DB_USER")
        val databasePassword = requiredEnv("SOMEDAY_DB_PASSWORD")
        val statements = listOf(
            """
                SELECT row_to_json(value)::text
                FROM (
                    SELECT epoch_id, pointer_digest, pointer_object_json, contract_id, schema_set_version,
                           key_set_version, remote_profile, checkpoint_id, checkpoint_digest,
                           previous_epoch_id, previous_epoch_pointer_digest
                    FROM someday_sync_v2_epochs
                    WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
                ) value
            """.trimIndent(),
            """
                SELECT row_to_json(value)::text
                FROM (
                    SELECT epoch_id, checkpoint_id, chunk_index, chunk_id, chunk_digest,
                           object_count, plaintext_bytes, encrypted_object_json
                    FROM someday_sync_v2_checkpoint_chunks
                    WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
                ) value
            """.trimIndent(),
            """
                SELECT row_to_json(value)::text
                FROM (
                    SELECT epoch_id, checkpoint_id, checkpoint_digest, chunk_count,
                           total_object_count, chunk_refs_fingerprint, encrypted_object_json
                    FROM someday_sync_v2_checkpoint_manifests
                    WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
                ) value
            """.trimIndent(),
            """
                SELECT row_to_json(value)::text
                FROM (
                    SELECT epoch_id, object_id, object_type, object_digest, mutation_id, cursor
                    FROM someday_sync_v2_objects
                    WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
                ) value
            """.trimIndent(),
            """
                SELECT row_to_json(value)::text
                FROM (
                    SELECT epoch_id, object_id, object_digest, mutation_id, ciphertext_digest,
                           encrypted_object_json, repair_replica
                    FROM someday_sync_v2_object_replicas
                    WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
                ) value
            """.trimIndent(),
            """
                SELECT row_to_json(value)::text
                FROM (
                    SELECT epoch_id, object_id, object_digest, mutation_id, cursor
                    FROM someday_sync_v2_changes
                    WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
                ) value
            """.trimIndent(),
            """
                SELECT row_to_json(value)::text
                FROM (
                    SELECT epoch_id, mutation_id, object_id, object_digest, cursor
                    FROM someday_sync_v2_mutations
                    WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
                ) value
            """.trimIndent(),
            """
                SELECT row_to_json(value)::text
                FROM (
                    SELECT password_hash
                    FROM someday_users
                    WHERE email = ?
                ) value
            """.trimIndent(),
        )
        return DriverManager.getConnection(databaseUrl, databaseUser, databasePassword).use { connection ->
            buildString {
                statements.forEach { sql ->
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, email)
                        statement.executeQuery().use { rows ->
                            while (rows.next()) appendLine(rows.getString(1))
                        }
                    }
                }
            }
        }
    }

    private data class Device(
        val driver: SqlDriver,
        val database: SomedayDatabase,
        val local: SqlDelightLocalDataRepository,
    ) {
        fun close() = driver.close()
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

    private companion object {
        const val NOTEBOOK_ID = "20000000-0000-4000-8000-000000000001"
        const val NOTE_ID = "30000000-0000-4000-8000-000000000001"
        const val NOTE_TITLE_SENTINEL = "v2-private-note-title-7cb74d"
        const val NOTE_BODY_SENTINEL = "v2-private-note-body-9702ee"
        const val LOCATION_SENTINEL = "v2-private-location-1e381c"
        const val NOTEBOOK_SENTINEL = "v2-private-notebook-9ce5aa"
        const val WORKSPACE_KEY_BASE64_SENTINEL = "ERITFBUWFxgZGhscHR4fICEiIyQlJicoKSorLC0uLzA="
        val NOTE_KEY = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTE, NOTE_ID)
        val NOTEBOOK_KEY = WorkspaceEntityKeyV2(WorkspaceEntityTypeV2.NOTEBOOK, NOTEBOOK_ID)
        val PREFERENCES_KEY = WorkspaceEntityKeyV2(
            WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES,
            WORKSPACE_PREFERENCES_ENTITY_ID_V2,
        )
        val ROOT_AT = Instant.parse("2026-07-19T00:00:00Z")
        val LEADER_EDIT_AT = Instant.parse("2026-07-19T01:00:00Z")
        val FOLLOWER_EDIT_AT = Instant.parse("2026-07-19T02:00:00Z")
        val SYNC_AT = Instant.parse("2026-07-19T03:00:00Z")
    }
}
