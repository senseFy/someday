@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("DEPRECATION")

package saien.someday.sync.webdav

import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.settings.WebDavConnectionInput
import kotlin.time.Instant
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavBackupServiceJvmTest {
    @Test
    fun backupUploadsLatestExportAndRestoreImportsItIntoAnotherRepository() {
        val transport = MemoryBackupWebDavTransport()
        val input = WebDavConnectionInput(
            endpoint = "http://127.0.0.1:8080",
            username = "alice",
            password = "secret-token",
            appDirectory = "/someday-backup-test/",
        )

        withRepository("source-device") { sourceRepository ->
            val notebook = sourceRepository.createNotebook("Archive")
            val note = sourceRepository.createNote(
                notebookId = notebook.id,
                title = "Backed up note",
                markdownBody = "This content crosses WebDAV.",
                createdAt = Instant.parse("2026-05-24T00:00:00Z"),
            )

            val backup = WebDavBackupService(sourceRepository, transport).backup(input)

            assertTrue(backup.success)
            assertEquals(1, backup.notebookCount)
            assertEquals(1, backup.noteCount)
            assertFalse(backup.message.contains("secret-token"))
            assertNotNull(transport.bodyAt("someday-backup-test/backups/latest.json"))
            assertNotNull(backup.version?.path?.let(transport::bodyAt))
            val versions = WebDavBackupService(sourceRepository, transport).listBackups(input)
            assertTrue(versions.success)
            assertTrue(versions.versions.any { it.id == "latest" })
            assertTrue(versions.versions.any { it.path == backup.version?.path })

            withRepository("target-device") { targetRepository ->
                val restore = WebDavBackupService(targetRepository, transport).restore(input, backup.version?.path)

                assertTrue(restore.success)
                assertEquals(1, restore.notebooksCreated)
                assertEquals(1, restore.notesCreated)
                assertEquals(0, restore.notesSkipped)
                assertFalse(restore.message.contains("secret-token"))

                val restoredNotebook = assertNotNull(targetRepository.getNotebook(notebook.id))
                val restoredNote = assertNotNull(targetRepository.getNote(note.id))
                assertEquals("Archive", restoredNotebook.title)
                assertEquals("Backed up note", restoredNote.title)
                assertEquals("This content crosses WebDAV.", restoredNote.markdownBody)
            }
        }
    }

    @Test
    fun restoreMergesBackedUpNotesIntoExistingNotebookWithSameTitle() {
        val transport = MemoryBackupWebDavTransport()
        val input = WebDavConnectionInput(
            endpoint = "http://127.0.0.1:8080",
            username = "alice",
            password = "secret-token",
            appDirectory = "/someday-merge-test/",
        )

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

            val backup = WebDavBackupService(sourceRepository, transport).backup(input)
            assertTrue(backup.success)

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

                val restore = WebDavBackupService(targetRepository, transport).restore(input, backup.version?.path)

                assertTrue(restore.success)
                assertEquals(0, restore.notebooksCreated)
                assertEquals(1, restore.notebooksReused)
                assertEquals(1, restore.notesCreated)
                assertEquals(0, restore.notesSkipped)
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
    fun restoreWithoutRemoteBackupReturnsFailure() {
        withRepository("empty-device") { repository ->
            val result = WebDavBackupService(repository, MemoryBackupWebDavTransport()).restore(
                WebDavConnectionInput(
                    endpoint = "http://127.0.0.1:8080",
                    appDirectory = "/empty-backup-test/",
                ),
                backupPath = null,
            )

            assertFalse(result.success)
            assertEquals("No WebDAV backup found.", result.message)
        }
    }

    private fun withRepository(
        deviceId: String,
        block: (SqlDelightLocalDataRepository) -> Unit,
    ) {
        val dbPath = Files.createTempFile("someday-webdav-backup-", ".db")
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

private class MemoryBackupWebDavTransport : WebDavTransport {
    private val objects = mutableMapOf<String, ByteArray>()
    private val collections = mutableSetOf<String>()

    fun bodyAt(path: String): ByteArray? = objects[path]

    override fun execute(
        configuration: WebDavConfiguration,
        request: WebDavRequest,
    ): WebDavResponse =
        when (request.method) {
            "MKCOL" -> {
                val created = collections.add(request.path)
                WebDavResponse(status = if (created) 201 else 405)
            }

            "PUT" -> {
                objects[request.path] = request.body ?: ByteArray(0)
                WebDavResponse(status = 201)
            }

            "GET" -> objects[request.path]?.let { body ->
                WebDavResponse(status = 200, body = body)
            } ?: WebDavResponse(status = 404)

            "PROPFIND" -> WebDavResponse(
                status = 207,
                body = multistatus(request.path).encodeToByteArray(),
            )

            else -> WebDavResponse(status = 405)
        }

    private fun multistatus(root: String): String {
        val responses = objects.keys
            .filter { path -> path.startsWith(root) }
            .joinToString(separator = "") { path ->
                """
                <D:response>
                  <D:href>/$path</D:href>
                  <D:propstat>
                    <D:prop>
                      <D:resourcetype/>
                    </D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status>
                  </D:propstat>
                </D:response>
                """.trimIndent()
            }
        return """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">$responses</D:multistatus>"""
    }
}
