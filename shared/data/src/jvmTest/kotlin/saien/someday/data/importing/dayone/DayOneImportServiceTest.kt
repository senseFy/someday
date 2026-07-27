@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.importing.dayone

import saien.someday.data.local.LocalIdGenerator
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.notes.noteCalendarDate
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DayOneImportServiceTest {
    @Test
    fun importsRichTextDatesTimezoneAndLocation() =
        withFixture { repository ->
            val document = dayOneDocument(
                entry(
                    uuid = "entry-1",
                    creationDate = "2026-05-01T16:30:00Z",
                    modifiedDate = "2026-05-03T08:00:00Z",
                    timeZone = "Asia/Shanghai",
                    richText = richTextDocument(),
                    location = buildJsonObject {
                        put("latitude", 31.2304)
                        put("longitude", 121.4737)
                        put("placeName", "Shanghai")
                    },
                ),
            )

            val summary = DayOneImportService(repository).importDocuments(
                listOf(DayOneJsonDocument(journalTitle = "Journal A", json = document)),
            )

            val note = assertNotNull(repository.getNote("dayone-entry-1"))
            val location = assertNotNull(repository.getLocation(note.id))

            assertEquals(1, summary.notesCreated)
            assertEquals(1, summary.richTextConverted)
            assertEquals(1, summary.photosReferenced)
            assertEquals(1, summary.locationsImported)
            assertEquals("Trip", note.title)
            assertEquals("Asia/Shanghai", note.timeZoneId)
            assertEquals("2026-05-02", noteCalendarDate(note.createdAt, note.timeZoneId).toString())
            assertEquals(Instant.parse("2026-05-01T16:30:00Z"), note.createdAt)
            assertEquals(Instant.parse("2026-05-03T08:00:00Z"), note.updatedAt)
            assertTrue(note.markdownBody.contains("# Trip"))
            assertTrue(note.markdownBody.contains("**bold**"))
            assertTrue(note.markdownBody.contains("[Photo: photo-1]"))
            assertEquals(31.2304, location.latitude)
            assertEquals(121.4737, location.longitude)
            assertEquals("Shanghai", location.placeText)
        }

    @Test
    fun skipsAlreadyImportedEntriesWithoutDuplicatingNotes() =
        withFixture { repository ->
            val document = dayOneDocument(
                entry(
                    uuid = "entry-1",
                    creationDate = "2026-05-01T16:30:00Z",
                    modifiedDate = "2026-05-03T08:00:00Z",
                    timeZone = "Asia/Shanghai",
                    text = "Plain Day One entry",
                ),
            )
            val service = DayOneImportService(repository)

            val first = service.importDocuments(listOf(DayOneJsonDocument("Journal A", document)))
            val second = service.importDocuments(listOf(DayOneJsonDocument("Journal A", document)))

            val notebook = repository.listActiveNotebooks().single()
            assertEquals(1, first.notesCreated)
            assertEquals(0, second.notesCreated)
            assertEquals(1, second.notesSkipped)
            assertEquals(listOf("dayone-entry-1"), repository.listActiveNotes(notebook.id).map { it.id })
        }

    @Test
    fun importsDayOneZipArchiveJsonEntries() =
        withFixture { repository ->
            val archive = zipOf(
                "Archive Journal.json" to dayOneDocument(
                    entry(
                        uuid = "entry-zip",
                        creationDate = "2026-05-01T10:00:00Z",
                        modifiedDate = "2026-05-01T10:00:00Z",
                        timeZone = "UTC",
                        text = "Zip entry",
                    ),
                ),
            )

            val summary = DayOneImportService(repository).importArchive(archive)

            assertEquals(1, summary.notesCreated)
            assertEquals("Archive Journal", repository.listActiveNotebooks().single().title)
            assertEquals("Zip entry", repository.getNote("dayone-entry-zip")?.markdownBody)
        }

    private fun richTextDocument(): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(
                    "contents",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("text", "Trip")
                                put(
                                    "attributes",
                                    buildJsonObject {
                                        put(
                                            "line",
                                            buildJsonObject {
                                                put("header", 1)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        add(JsonObject(mapOf("text" to JsonPrimitive("\n"))))
                        add(
                            buildJsonObject {
                                put("text", "bold")
                                put(
                                    "attributes",
                                    buildJsonObject {
                                        put("bold", true)
                                    },
                                )
                            },
                        )
                        add(JsonObject(mapOf("text" to JsonPrimitive("\n"))))
                        add(
                            buildJsonObject {
                                put(
                                    "embeddedObjects",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "photo")
                                                put("identifier", "photo-1")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

    private fun dayOneDocument(vararg entries: JsonObject): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("entries", JsonArray(entries.toList()))
                put(
                    "metadata",
                    buildJsonObject {
                        put("version", "1.0")
                    },
                )
            },
        )

    private fun entry(
        uuid: String,
        creationDate: String,
        modifiedDate: String,
        timeZone: String,
        text: String? = null,
        richText: String? = null,
        location: JsonObject? = null,
    ): JsonObject =
        buildJsonObject {
            put("uuid", uuid)
            put("creationDate", creationDate)
            put("modifiedDate", modifiedDate)
            put("timeZone", timeZone)
            text?.let { put("text", it) }
            richText?.let { put("richText", it) }
            location?.let { put("location", it) }
        }

    private fun withFixture(block: (SqlDelightLocalDataRepository) -> Unit) {
        val dbPath = Files.createTempFile("someday-day-one-import-", ".db")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        val database = SomedayDatabase(driver)
        val repository = SqlDelightLocalDataRepository(
            database = database,
            deviceId = "test-device",
            clock = { Instant.fromEpochMilliseconds(1_000) },
            idGenerator = SequentialTestIdGenerator(),
        )

        try {
            block(repository)
        } finally {
            driver.close()
            Files.deleteIfExists(dbPath)
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private class SequentialTestIdGenerator : LocalIdGenerator {
        private var counter = 0

        override fun newId(prefix: String): String {
            counter += 1
            return "$prefix-$counter"
        }
    }

    private companion object {
        val json = kotlinx.serialization.json.Json {
            encodeDefaults = true
        }
    }
}
