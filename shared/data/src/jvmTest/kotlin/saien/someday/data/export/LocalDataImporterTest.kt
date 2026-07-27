@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.data.export

import saien.someday.data.local.EntityType
import saien.someday.data.local.LocationInput
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.SyncState
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import kotlin.time.Instant
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDataImporterTest {
    @Test
    fun importJsonCreatesMissingContentAndSkipsExistingContentOnRetry() {
        withRepository("export-device") { sourceRepository ->
            val notebook = sourceRepository.createNotebook("Journal")
            val note = sourceRepository.createNote(
                notebookId = notebook.id,
                title = "First backup",
                markdownBody = "Restored body",
                createdAt = Instant.parse("2026-05-24T00:00:00Z"),
                location = LocationInput(
                    placeText = "Shanghai",
                    latitude = 31.2304,
                    longitude = 121.4737,
                    capturedAt = Instant.parse("2026-05-24T09:30:00Z"),
                ),
            )
            val backupJson = LocalDataExporter(sourceRepository).exportJson()

            withRepository("restore-device") { targetRepository ->
                val firstImport = LocalDataImporter(targetRepository).importJson(backupJson)

                assertEquals(1, firstImport.notebooksCreated)
                assertEquals(0, firstImport.notebooksReused)
                assertEquals(1, firstImport.notesCreated)
                assertEquals(0, firstImport.notesSkipped)

                val importedNotebook = assertNotNull(targetRepository.getNotebook(notebook.id))
                val importedNote = assertNotNull(targetRepository.getNote(note.id))
                val importedLocation = assertNotNull(targetRepository.getLocation(note.id))
                assertEquals("Journal", importedNotebook.title)
                assertEquals(importedNotebook.id, importedNote.notebookId)
                assertEquals("First backup", importedNote.title)
                assertEquals("Restored body", importedNote.markdownBody)
                assertEquals("Shanghai", importedLocation.placeText)
                assertEquals(31.2304, importedLocation.latitude)
                assertEquals(121.4737, importedLocation.longitude)

                val retryImport = LocalDataImporter(targetRepository).importJson(backupJson)

                assertEquals(0, retryImport.notebooksCreated)
                assertEquals(1, retryImport.notebooksReused)
                assertEquals(0, retryImport.notesCreated)
                assertEquals(1, retryImport.notesSkipped)
            }
        }
    }

    @Test
    fun importJsonMergesNotesIntoExistingNotebookWithSameTitleAcrossDevices() {
        withRepository("android-device") { sourceRepository ->
            val androidNotebook = sourceRepository.createNotebook(
                title = "日记",
                id = "notebook-android-diary",
            )
            val androidNote = sourceRepository.createNote(
                id = "note-android-entry",
                notebookId = androidNotebook.id,
                title = "Android entry",
                markdownBody = "Created on Android",
                createdAt = Instant.parse("2026-05-24T00:00:00Z"),
            )
            val backupJson = LocalDataExporter(sourceRepository).exportJson()

            withRepository("ios-device") { targetRepository ->
                val iosNotebook = targetRepository.createNotebook(
                    title = "日记",
                    id = "notebook-ios-diary",
                )
                val iosNote = targetRepository.createNote(
                    id = "note-ios-entry",
                    notebookId = iosNotebook.id,
                    title = "iOS entry",
                    markdownBody = "Created on iOS",
                    createdAt = Instant.parse("2026-05-24T00:00:00Z"),
                )

                val import = LocalDataImporter(targetRepository).importJson(backupJson)

                assertEquals(0, import.notebooksCreated)
                assertEquals(1, import.notebooksReused)
                assertEquals(1, import.notesCreated)
                assertEquals(0, import.notesSkipped)
                assertNull(targetRepository.getNotebook(androidNotebook.id))

                val notebooks = targetRepository.listActiveNotebooks()
                assertEquals(1, notebooks.count { it.title == "日记" })

                val mergedNotes = targetRepository.listActiveNotes(iosNotebook.id)
                assertEquals(2, mergedNotes.size)
                assertTrue(mergedNotes.any { it.id == iosNote.id && it.notebookId == iosNotebook.id })
                assertTrue(mergedNotes.any { it.id == androidNote.id && it.notebookId == iosNotebook.id })
            }
        }
    }

    @Test
    fun importJsonUpdatesExistingCleanNoteWithNewerBackupSnapshot() {
        withRepository("android-device") { sourceRepository ->
            val notebook = sourceRepository.createNotebook(
                title = "日记",
                id = "notebook-diary",
            )
            val note = sourceRepository.createNote(
                id = "note-shared-entry",
                notebookId = notebook.id,
                title = "Shared entry",
                markdownBody = "Base body",
                createdAt = Instant.parse("2026-05-24T00:00:00Z"),
            )
            sourceRepository.updateNote(
                noteId = note.id,
                markdownBody = "Base body\nAndroid update",
            )
            val backupJson = LocalDataExporter(sourceRepository).exportJson()

            withRepository("ios-device") { targetRepository ->
                val targetNotebook = targetRepository.createNotebook(
                    title = "日记",
                    id = notebook.id,
                )
                val targetNote = targetRepository.createNote(
                    id = note.id,
                    notebookId = targetNotebook.id,
                    title = "Shared entry",
                    markdownBody = "Base body",
                    createdAt = Instant.parse("2026-05-24T00:00:00Z"),
                )
                targetRepository.markEntitySynced(targetNote.id, EntityType.NOTE, targetNote.revision, "etag-base")

                val import = LocalDataImporter(targetRepository).importJson(backupJson)

                assertEquals(0, import.notesCreated)
                assertEquals(1, import.notesUpdated)
                assertEquals(0, import.notesMerged)
                assertEquals(0, import.noteConflictsCreated)
                assertEquals(0, import.notesSkipped)
                assertEquals("Base body\nAndroid update", targetRepository.getNote(note.id)?.markdownBody)
            }
        }
    }

    @Test
    fun importJsonCreatesConflictCopyWhenBackupDiffersFromDirtyLocalNote() {
        withRepository("android-device") { sourceRepository ->
            val notebook = sourceRepository.createNotebook(
                title = "日记",
                id = "notebook-diary",
            )
            val note = sourceRepository.createNote(
                id = "note-shared-entry",
                notebookId = notebook.id,
                title = "Shared entry",
                markdownBody = "Base body",
                createdAt = Instant.parse("2026-05-24T00:00:00Z"),
            )
            sourceRepository.updateNote(
                noteId = note.id,
                markdownBody = "Base body\nAndroid first update",
            )
            sourceRepository.updateNote(
                noteId = note.id,
                markdownBody = "Base body\nAndroid first update\nAndroid second update",
            )
            val backupJson = LocalDataExporter(sourceRepository).exportJson()

            withRepository("ios-device") { targetRepository ->
                val targetNotebook = targetRepository.createNotebook(
                    title = "日记",
                    id = notebook.id,
                )
                targetRepository.createNote(
                    id = note.id,
                    notebookId = targetNotebook.id,
                    title = "Shared entry",
                    markdownBody = "Base body",
                    createdAt = Instant.parse("2026-05-24T00:00:00Z"),
                )
                targetRepository.updateNote(
                    noteId = note.id,
                    markdownBody = "Base body\niOS update",
                )

                val import = LocalDataImporter(targetRepository).importJson(backupJson)

                assertEquals(0, import.notesCreated)
                assertEquals(0, import.notesUpdated)
                assertEquals(0, import.notesMerged)
                assertEquals(1, import.noteConflictsCreated)
                assertEquals(0, import.notesSkipped)
                assertEquals("Base body\niOS update", targetRepository.getNote(note.id)?.markdownBody)

                val notes = targetRepository.listActiveNotes(targetNotebook.id)
                val conflictCopy = assertNotNull(notes.singleOrNull { it.syncState == SyncState.CONFLICT })
                assertEquals("Base body\nAndroid first update\nAndroid second update", conflictCopy.markdownBody)
                assertTrue(conflictCopy.title.contains("android-device"))
            }
        }
    }

    private fun withRepository(
        deviceId: String,
        block: (SqlDelightLocalDataRepository) -> Unit,
    ) {
        val dbPath = Files.createTempFile("someday-local-import-", ".db")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        val database = SomedayDatabase(driver)
        val localRepository = SqlDelightLocalDataRepository(
            database = database,
            deviceId = deviceId,
            clock = { Instant.parse("2026-05-24T10:00:00Z") },
        )

        try {
            block(localRepository)
        } finally {
            driver.close()
            Files.deleteIfExists(dbPath)
        }
    }
}
