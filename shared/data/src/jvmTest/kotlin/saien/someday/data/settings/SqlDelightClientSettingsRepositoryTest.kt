@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.ClientTheme
import saien.someday.domain.settings.EditorPreferences
import saien.someday.domain.settings.OnThisDayNotificationPreferences
import saien.someday.domain.settings.SyncConfiguration
import saien.someday.domain.settings.SyncMode
import kotlin.time.Instant
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

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
                    mode = SyncMode.SelfHosted,
                    selfHostedEndpoint = "https://sync.example",
                    lastError = "last retry failed",
                ),
            )

            fixture.settingsRepository.save(expected)

            assertEquals("Dark", fixture.localRepository.getSetting("client.theme")?.value)
            assertEquals("device-persisted", fixture.localRepository.getSetting("client.active_device_id")?.value)
            assertEquals("notebook-last-selected", fixture.localRepository.getSetting("client.last_selected_notebook_id")?.value)

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

    @Test
    fun removedAndUnknownSyncModesFailClosedAfterRestart() =
        withFixture { fixture ->
            listOf("Web" + "Dav", "future-provider").forEach { storedMode ->
                fixture.localRepository.putSetting(ClientSettingsKeys.SYNC_MODE, storedMode)

                withReopenedSettingsRepository(fixture.jdbcUrl) { reopened ->
                    assertEquals(SyncMode.Off, reopened.load().syncConfiguration.mode)
                }
            }
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
