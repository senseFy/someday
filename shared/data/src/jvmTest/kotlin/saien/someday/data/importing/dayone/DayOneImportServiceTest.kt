@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.importing.dayone

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataImportSummary

class DayOneImportServiceTest {
    @Test
    fun convertsRichTextTimezoneAndLocationBeforeWritingThroughTheWorkspaceDag() {
        var importedDocument: LocalDataExportDocument? = null
        val service = DayOneImportService { document ->
            importedDocument = document
            successfulImport(document)
        }
        val source = dayOneDocument(
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

        val summary = service.importDocuments(listOf(DayOneJsonDocument("Journal A", source)))
        val document = assertNotNull(importedDocument)
        val note = document.notes.single()
        val location = assertNotNull(note.location)

        assertEquals(1, summary.notesCreated)
        assertEquals(1, summary.richTextConverted)
        assertEquals(1, summary.photosReferenced)
        assertEquals(1, summary.locationsImported)
        assertEquals("Journal A", document.notebooks.single().title)
        assertEquals("Trip", note.title)
        assertEquals("Asia/Shanghai", note.timeZoneId)
        assertEquals("2026-05-01T16:30:00Z", note.createdAt)
        assertEquals("2026-05-03T08:00:00Z", note.updatedAt)
        assertTrue(note.markdownBody.contains("# Trip"))
        assertTrue(note.markdownBody.contains("**bold**"))
        assertTrue(note.markdownBody.contains("[Photo: photo-1]"))
        assertEquals(31.2304, location.latitude)
        assertEquals(121.4737, location.longitude)
        assertEquals("Shanghai", location.placeText)
        assertEquals(false, document.includesMediaBytes)
        assertEquals(true, document.assetReferencesMayBeUnresolved)
    }

    @Test
    fun workspaceDagImporterFailurePropagatesWithoutASecondaryWritePath() {
        var calls = 0
        val service = DayOneImportService {
            calls += 1
            throw IllegalStateException("workspace DAG unavailable")
        }

        val failure = assertFailsWith<IllegalStateException> {
            service.importDocuments(
                listOf(
                    DayOneJsonDocument(
                        "Journal A",
                        dayOneDocument(
                            entry(
                                uuid = "entry-1",
                                creationDate = "2026-05-01T16:30:00Z",
                                modifiedDate = "2026-05-03T08:00:00Z",
                                timeZone = "UTC",
                                text = "Plain Day One entry",
                            ),
                        ),
                    ),
                ),
            )
        }

        assertEquals(1, calls)
        assertEquals("workspace DAG unavailable", failure.message)
    }

    @Test
    fun readsZipEntriesThenWritesTheConvertedDocumentThroughTheWorkspaceDag() {
        var importedDocument: LocalDataExportDocument? = null
        val service = DayOneImportService { document ->
            importedDocument = document
            successfulImport(document)
        }
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

        val summary = service.importArchive(archive)

        assertEquals(1, summary.notesCreated)
        assertEquals("Archive Journal", importedDocument?.notebooks?.single()?.title)
        assertEquals("Zip entry", importedDocument?.notes?.single()?.markdownBody)
    }

    private fun successfulImport(document: LocalDataExportDocument): LocalDataImportSummary =
        LocalDataImportSummary(
            notebooksCreated = document.notebooks.size,
            notebooksReused = 0,
            notesCreated = document.notes.size,
            notesSkipped = 0,
        )

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
                                        put("line", buildJsonObject { put("header", 1) })
                                    },
                                )
                            },
                        )
                        add(JsonObject(mapOf("text" to JsonPrimitive("\n"))))
                        add(
                            buildJsonObject {
                                put("text", "bold")
                                put("attributes", buildJsonObject { put("bold", true) })
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
                put("metadata", buildJsonObject { put("version", "1.0") })
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

    private companion object {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    }
}
