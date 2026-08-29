@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.notes.NoteInput
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import saien.someday.sync.resolveActiveWorkspaceSessionRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceLocalDraftV2Test {
    @Test
    fun unboundHealthyDraftDoesNotRequireBoundSessionBeforeFirstPublish() {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val database = SomedayDatabase(driver)
            val local = SqlDelightLocalDataRepository(database, WRITER)
            val settings = SqlDelightClientSettingsRepository(local)
            val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 1).toByte() })
            ensureWorkspaceLocalDraftV2(local, settings, key)

            assertNull(
                resolveActiveWorkspaceSessionRequirement(
                    SqlDelightSyncProtocolStoreV2(database),
                    { "workspace-00000000000000000000000000000000" },
                ),
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun firstOfflineNoteLivesOnlyInDraftDagAndSurvivesRepositoryRestart() {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val database = SomedayDatabase(driver)
            val local = SqlDelightLocalDataRepository(database, WRITER)
            val settings = SqlDelightClientSettingsRepository(local)
            settings.saveLocalSnapshot(
                ClientSettings(
                    activeDeviceId = WRITER,
                    syncConfiguration = SyncConfiguration(mode = SyncMode.Off),
                ),
            )
            val key = SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 1).toByte() })
            val draft = ensureWorkspaceLocalDraftV2(local, settings, key)
            assertEquals(SyncEpochLifecycleV2.PREPARING, draft.lifecycle)

            val notes = repository(local, key)
            val notebook = notes.createNotebook("Offline")
            val note = notes.createNote(NoteInput(notebook.id, "Day one", "Stored in DAG"))

            val restarted = repository(SqlDelightLocalDataRepository(database, WRITER), key)
            assertEquals("Stored in DAG", assertNotNull(restarted.getNoteDetails(note.id)).markdownBody)
            val context = WorkspaceSystemV2ContextProvider(local, { key }, { WRITER }, { PROFILE }).requireWritable()
            assertTrue(context.store.loadPending(PROFILE).isNotEmpty())
        } finally {
            driver.close()
        }
    }

    private fun repository(
        local: SqlDelightLocalDataRepository,
        key: saien.someday.data.crypto.WorkspaceMasterKey,
    ) = SystemV2NotesRepository(local, { key }, { WRITER }, { PROFILE })

    private companion object {
        const val WRITER = "00000000-0000-4000-8000-000000000001"
        val PROFILE = SyncRemoteProfileV2.SELF_HOSTED.wireValue
    }
}
