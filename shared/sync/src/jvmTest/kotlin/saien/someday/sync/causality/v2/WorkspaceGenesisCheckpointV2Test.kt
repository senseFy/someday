@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.local.LocationInput
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.EditorPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkspaceGenesisCheckpointV2Test {
    @Test
    fun genesisCapturesCompleteProductStateAndTypedPreferences() = withFixture { local, settings ->
        val notebook = local.createNotebook("Journal", id = NOTEBOOK_ID)
        local.createNote(
            notebookId = notebook.id,
            title = "Shanghai",
            markdownBody = "A complete local snapshot.",
            createdAt = CREATED_AT,
            location = LocationInput(
                latitude = 31.2304,
                longitude = 121.4737,
                placeText = "Shanghai",
            ),
            timeZoneId = "Asia/Shanghai",
            id = NOTE_ID,
        )
        settings.save(
            ClientSettings(
                theme = ClientTheme.Dark,
                editorPreferences = EditorPreferences(
                    previewByDefault = true,
                    markdownToolbarVisible = false,
                ),
                defaultNotebookId = NOTEBOOK_ID,
                activeDeviceId = WRITER,
            ),
        )

        val service = service(local, settings)
        val inventory = service.inventory()
        val noteSource = inventory.sourceHeads.single {
            it.entityType == WorkspaceEntityTypeV2.NOTE && it.entityId == NOTE_ID
        }
        val note = assertIs<NoteContentV2>(noteSource.content)
        assertEquals("Shanghai", assertNotNull(note.location).placeText)
        assertEquals("Asia/Shanghai", note.timeZoneId)

        val preferences = inventory.sourceHeads.single {
            it.entityType == WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES
        }
        assertEquals(
            WorkspacePreferencesV2(
                theme = WorkspaceThemeV2.DARK,
                previewByDefault = true,
                markdownToolbarVisible = false,
                defaultNotebookId = NOTEBOOK_ID,
            ),
            preferences.content,
        )

        val prepared = assertIs<WorkspaceGenesisCheckpointResultV2.Prepared>(service.prepare())
        assertTrue(prepared.checkpoint.entities.any {
            it.version.entityType == WorkspaceEntityTypeV2.NOTE &&
                it.version.entityId == NOTE_ID &&
                it.version.parentVersionIds.isEmpty()
        })
    }

    @Test
    fun invalidOversizedProductStateFailsBeforeAnEpochCanBePublished() = withFixture { local, settings ->
        val notebook = local.createNotebook("Journal", id = NOTEBOOK_ID)
        val oversizedBody = "x".repeat(MAX_NOTE_MARKDOWN_BYTES_V2 + 1)
        local.createNote(
            notebookId = notebook.id,
            title = "Oversized",
            markdownBody = oversizedBody,
            createdAt = CREATED_AT,
            id = NOTE_ID,
        )

        val blocked = assertIs<WorkspaceGenesisCheckpointResultV2.Blocked>(
            service(local, settings).prepare(),
        )

        assertEquals("genesis_checkpoint_invalid", blocked.safeErrorCode)
        assertEquals(oversizedBody, local.getNote(NOTE_ID)?.markdownBody)
    }

    private fun service(
        local: SqlDelightLocalDataRepository,
        settings: ClientSettingsRepository,
    ) = WorkspaceGenesisCheckpointServiceV2(
        localRepository = local,
        settingsRepository = settings,
        workspaceKey = WORKSPACE_KEY,
        writerDeviceId = WRITER,
        remoteProfile = SyncRemoteProfileV2.WEB_DAV.wireValue,
        idGenerator = SequentialIds(),
        clock = { SNAPSHOT_AT },
    )

    private fun withFixture(
        block: (SqlDelightLocalDataRepository, ClientSettingsRepository) -> Unit,
    ) {
        val driver = createSomedayJdbcDriver("jdbc:sqlite::memory:")
        try {
            val local = SqlDelightLocalDataRepository(
                SomedayDatabase(driver),
                WRITER,
                clock = { SNAPSHOT_AT },
            )
            val settings = InMemorySettings()
            block(local, settings)
        } finally {
            driver.close()
        }
    }

    private class InMemorySettings : ClientSettingsRepository {
        private var value = ClientSettings()

        override fun load(): ClientSettings = value

        override fun save(settings: ClientSettings): ClientSettings {
            value = settings
            return value
        }
    }

    private class SequentialIds : CausalityIdGeneratorV2 {
        private var value = 1L

        override fun newId(): String =
            "90000000-0000-4000-8000-${(value++).toString().padStart(12, '0')}"
    }

    private companion object {
        const val WRITER = "10000000-0000-4000-8000-000000000001"
        const val NOTEBOOK_ID = "20000000-0000-4000-8000-000000000001"
        const val NOTE_ID = "30000000-0000-4000-8000-000000000001"
        val CREATED_AT = Instant.parse("2026-07-19T00:00:00Z")
        val SNAPSHOT_AT = Instant.parse("2026-07-19T01:00:00Z")
        val WORKSPACE_KEY =
            SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 19).toByte() })
    }
}
