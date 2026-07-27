@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.export

import saien.someday.data.local.LocationInput
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalDataExporterTest {
    @Test
    fun exportIncludesNotesAndNotebooksButExcludesSettingsDevicesAndSecrets() =
        withFixture { localRepository ->
            val diary = localRepository.createNotebook("Diary")
            val travel = localRepository.createNotebook("Travel")
            localRepository.createNote(
                notebookId = diary.id,
                title = "Morning pages",
                markdownBody = "Plain local note body is intentionally exported for the user.",
                createdAt = Instant.parse("2026-05-22T00:00:00Z"),
                location = LocationInput(placeText = "Kyoto", latitude = 35.0, longitude = 135.0),
            )
            localRepository.createNote(
                notebookId = travel.id,
                title = "Train plan",
                markdownBody = "Pack tickets and tea.",
                createdAt = Instant.parse("2026-05-23T00:00:00Z"),
            )

            localRepository.putSetting("encryption.workspace.key_metadata", "RAW_WORKSPACE_KEY_SENTINEL")
            localRepository.putSetting("client.sync.refresh_token", "REFRESH_TOKEN_SENTINEL")
            localRepository.putSetting("client.account.password", "PASSWORD_SENTINEL")
            localRepository.putSetting("client.recovery.material", "SOMEDAY-RECOVERY-SENTINEL")
            localRepository.registerDevice(
                name = "Developer Mac",
                platform = "desktop",
                workspaceKeyMetadata = "SECURE_ALIAS_SENTINEL RAW_WORKSPACE_KEY_SENTINEL",
            )

            val json = LocalDataExporter(
                localRepository = localRepository,
                clock = { Instant.fromEpochMilliseconds(2_000) },
            ).exportJson()
            val document = Json.decodeFromString<LocalDataExportDocument>(json)

            assertEquals("someday.local-export.v2", document.format)
            assertEquals("1970-01-01T00:00:02Z", document.exportedAt)
            assertEquals(listOf("Diary", "Travel"), document.notebooks.map { it.title })
            assertEquals(2, document.notes.size)
            assertNotNull(document.notes.single { it.title == "Morning pages" }.location)
            assertTrue(json.contains("Plain local note body is intentionally exported"))
            assertTrue(document.excludedSensitiveFields.contains("raw workspace keys"))
            assertTrue(document.excludedSensitiveFields.contains("refresh tokens"))
            assertTrue(document.excludedSensitiveFields.contains("passwords"))
            assertTrue(document.excludedSensitiveFields.contains("recovery material"))

            listOf(
                "RAW_WORKSPACE_KEY_SENTINEL",
                "REFRESH_TOKEN_SENTINEL",
                "PASSWORD_SENTINEL",
                "SOMEDAY-RECOVERY-SENTINEL",
                "SECURE_ALIAS_SENTINEL",
            ).forEach { secret ->
                assertFalse(json.contains(secret), "Export JSON must not contain $secret")
            }
        }

    private fun withFixture(block: (SqlDelightLocalDataRepository) -> Unit) {
        val dbPath = Files.createTempFile("someday-local-export-", ".db")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        val database = SomedayDatabase(driver)
        val localRepository = SqlDelightLocalDataRepository(
            database = database,
            deviceId = "export-test-device",
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )

        try {
            block(localRepository)
        } finally {
            driver.close()
            Files.deleteIfExists(dbPath)
        }
    }
}
