@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import saien.someday.data.local.EntityType
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.EditorPreferences
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncErrorCode
import saien.someday.domain.settings.SyncMode
import kotlin.time.Instant
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightClientSettingsRepositoryTest {
    @Test
    fun clientSettingsSurviveRepositoryRestart() =
        withFixture { fixture ->
            val expected = ClientSettings(
                theme = ClientTheme.Dark,
                editorPreferences = EditorPreferences(
                    previewByDefault = true,
                    markdownToolbarVisible = false,
                ),
                onThisDayNotifications = OnThisDayNotificationPreferences(
                    enabled = true,
                    hour = 8,
                    minute = 30,
                ),
                defaultNotebookId = "notebook-default",
                lastSelectedNotebookId = "notebook-last-selected",
                activeDeviceId = "device-persisted",
                syncConfiguration = SyncConfiguration(
                    mode = SyncMode.WebDav,
                    webDavEndpoint = "https://dav.example/someday",
                    selfHostedEndpoint = "https://sync.example",
                    lastError = "last retry failed",
                    lastErrorCode = SyncErrorCode.WebDavWorkspaceKeyMismatch,
                ),
            )

            fixture.settingsRepository.save(expected)

            assertTrue(
                fixture.localRepository.getSyncMetadata("client.theme", EntityType.SETTING)?.dirty == true,
                "Theme setting should be durable local state and dirty for later sync.",
            )
            assertTrue(
                fixture.localRepository.getSyncMetadata("client.active_device_id", EntityType.SETTING)?.dirty == true,
                "Active device id should be persisted as client settings state.",
            )
            assertTrue(
                fixture.localRepository.getSyncMetadata("client.last_selected_notebook_id", EntityType.SETTING)?.dirty == true,
                "Last selected notebook should be persisted as client settings state.",
            )

            withReopenedSettingsRepository(fixture.jdbcUrl) { reopened ->
                val reloaded = reopened.load()

                assertEquals(expected, reloaded)
                assertEquals("notebook-default", reloaded.defaultNotebookId)
                assertEquals("notebook-last-selected", reloaded.lastSelectedNotebookId)
            }
        }

    @Test
    fun defaultSettingsAreStableWhenNoRowsExist() =
        withFixture { fixture ->
            assertEquals(ClientSettings(), fixture.settingsRepository.load())
        }

    private fun withFixture(block: (SettingsFixture) -> Unit) {
        val dbPath = Files.createTempFile("someday-client-settings-", ".db")
        val jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        val driver = createSomedayJdbcDriver(jdbcUrl)
        val database = SomedayDatabase(driver)
        val localRepository = localRepositoryFor(database)
        val settingsRepository = SqlDelightClientSettingsRepository(localRepository)

        try {
            block(SettingsFixture(jdbcUrl, localRepository, settingsRepository))
        } finally {
            driver.close()
            Files.deleteIfExists(dbPath)
        }
    }

    private fun withReopenedSettingsRepository(
        jdbcUrl: String,
        block: (SqlDelightClientSettingsRepository) -> Unit,
    ) {
        val driver = JdbcSqliteDriver(jdbcUrl)
        val database = SomedayDatabase(driver)

        try {
            block(SqlDelightClientSettingsRepository(localRepositoryFor(database)))
        } finally {
            driver.close()
        }
    }

    private fun localRepositoryFor(database: SomedayDatabase): SqlDelightLocalDataRepository =
        SqlDelightLocalDataRepository(
            database = database,
            deviceId = "test-device",
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )

    private data class SettingsFixture(
        val jdbcUrl: String,
        val localRepository: SqlDelightLocalDataRepository,
        val settingsRepository: SqlDelightClientSettingsRepository,
    )
}
