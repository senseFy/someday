@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
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
import kotlin.time.Instant

class WorkspaceGenesisCheckpointV2Test {
    @Test
    fun genesisCreatesOnlyTypedWorkspacePreferences() = withFixture { local, settings ->
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
        assertEquals(listOf(WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES), prepared.checkpoint.entities.map { it.version.entityType })
    }

    private fun service(
        local: SqlDelightLocalDataRepository,
        settings: ClientSettingsRepository,
    ) = WorkspaceGenesisCheckpointServiceV2(
        settingsRepository = settings,
        workspaceKey = WORKSPACE_KEY,
        writerDeviceId = WRITER,
        remoteProfile = SyncRemoteProfileV2.SELF_HOSTED.wireValue,
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
        val SNAPSHOT_AT = Instant.parse("2026-07-19T01:00:00Z")
        val WORKSPACE_KEY =
            SodiumWorkspaceCrypto().workspaceKeyFromBytes(ByteArray(32) { (it + 19).toByte() })
    }
}
