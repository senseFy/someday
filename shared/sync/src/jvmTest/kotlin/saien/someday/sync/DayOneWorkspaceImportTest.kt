package saien.someday.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import saien.someday.data.crypto.InMemorySecureWorkspaceKeyStore
import saien.someday.data.crypto.WorkspaceKeyRepository
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.media.DecodedMediaAsset
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.media.MediaAssetDecodeValidator
import saien.someday.data.media.MediaImageNormalizer
import saien.someday.data.settings.SqlDelightClientSettingsRepository
import saien.someday.domain.media.findSomedayAssetIds
import saien.someday.domain.settings.ClientSettings
import saien.someday.domain.settings.UnavailableSelfHostedSessionCredentialStore
import saien.someday.sync.selfhosted.JdkSelfHostedSyncTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DayOneWorkspaceImportTest {
    @Test
    fun validatesAllConvertedNotesBeforeAnyNotebookOrNoteWrite() {
        // UTF-8 byte counts differ from character counts; location has independent bounds.
        for ((body, extra) in listOf(
            "中".repeat(307200) + "x" to "",
            "Location" to ",\"location\":{\"latitude\":91,\"longitude\":120}",
            "Place" to ",\"location\":{\"placeName\":\"${"x".repeat(1025)}\"}",
        )) withServices { services ->
            assertFailsWith<IllegalArgumentException> {
                services.dayOneArchiveImporter(twoNotes(body, extra), "Day One", noNormalization)
            }
            assertTrue(services.notesRepository.listNotebooks().isEmpty())
        }
        withServices { services ->
            val result = services.dayOneArchiveImporter(twoNotes("中".repeat(307200)), "Day One", noNormalization)
            assertTrue(result.completed)
            assertEquals(2, result.notesCreated)
        }
    }

    @Test
    fun persistenceFailureReportsConfirmedWritesAndReplayFinishesWithoutDuplicates() {
        var writes = 0
        withServices(wrapDriver = { delegate ->
            object : SqlDriver by delegate {
                override fun execute(
                    identifier: Int?, sql: String, parameters: Int,
                    binders: (SqlPreparedStatement.() -> Unit)?,
                ): QueryResult<Long> {
                    if (sql.trimStart().startsWith("INSERT INTO sync_source_imports_system_v2") && ++writes == 3) {
                        error("Synthetic storage failure inside the second note transaction")
                    }
                    return delegate.execute(identifier, sql, parameters, binders)
                }
            }
        }) { services ->
            val archive = twoNotes("Second")
            val partial = services.dayOneArchiveImporter(archive, "Day One", noNormalization)
            assertFalse(partial.completed)
            assertEquals(1, partial.notebooksCreated)
            assertEquals(1, partial.notesCreated)
            assertEquals(0, partial.notesSkipped)
            val notebook = services.notesRepository.listNotebooks().single()
            val note = services.notesRepository.listNotes(notebook.id).single()
            assertEquals("First", services.notesRepository.getNoteDetails(note.id)?.markdownBody)

            val retry = services.dayOneArchiveImporter(archive, "Day One", noNormalization)
            assertTrue(retry.completed)
            assertEquals(0, retry.notebooksCreated)
            assertEquals(1, retry.notebooksReused)
            assertEquals(1, retry.notesCreated)
            assertEquals(1, retry.notesSkipped)
            assertEquals(2, services.notesRepository.listNotes(notebook.id).size)
        }
    }

    @Test
    fun productionCompositionImportsAssetsAndDagNotesAndReplaysWithoutDuplicates() = withServices { services ->
        val first = services.dayOneArchiveImporter(archive(), "Day One", noNormalization)
        assertEquals(1, first.notesCreated)
        assertEquals(1, first.photosImported)
        val notebook = services.notesRepository.listNotebooks().single { it.title == "Journal" }
        val note = services.notesRepository.listNotes(notebook.id).single()
        val details = assertNotNull(services.notesRepository.getNoteDetails(note.id))
        val assetId = findSomedayAssetIds(details.markdownBody).single()
        val asset = assertNotNull(services.localMediaAssetStore.getAsset(assetId))
        assertEquals("image/png", asset.metadata.mediaType)
        assertEquals(1, asset.metadata.pixelWidth)
        services.localMediaAssetStore.openSource(assetId).use { source ->
            val buffer = okio.Buffer()
            buffer.writeAll(source)
            assertContentEquals(png, buffer.readByteArray())
        }
        assertTrue(details.markdownBody.startsWith("# Travel\n\nBefore\n\n![Photo](someday-asset://"))
        assertTrue(details.markdownBody.endsWith("\n\nAfter"))

        val replay = services.dayOneArchiveImporter(archive(), "Day One", noNormalization)
        assertEquals(0, replay.notesCreated)
        assertEquals(1, replay.notesSkipped)
        assertEquals(1, services.notesRepository.listNotes(notebook.id).size)
        assertEquals(details.markdownBody, services.notesRepository.getNoteDetails(note.id)?.markdownBody)
    }

    @Test
    fun normalizationStagingFailuresAbortImportAndRetryKeepsTheImage() =
        assertStagingFailuresAbortAndRetry(normalize = true)

    @Test
    fun decodeStagingFailuresAbortImportAndRetryKeepsTheImage() =
        assertStagingFailuresAbortAndRetry(normalize = false)

    @Test
    fun corruptImagePayloadStillImportsTextWithARejectedPhoto() = withServices { services ->
        // Valid container and matching export MD5, but invalid compressed image data.
        val corrupt = png.copyOf().also { it[45] = (it[45].toInt() xor 0x7f).toByte() }
        val result = services.dayOneArchiveImporter(archive(corrupt), "Day One", noNormalization)
        assertTrue(result.completed)
        assertEquals(1, result.notesCreated)
        assertEquals(0, result.photosImported)
        assertEquals(1, result.photosRejected)
        val notebook = services.notesRepository.listNotebooks().single()
        val note = services.notesRepository.listNotes(notebook.id).single()
        assertEquals(
            "# Travel\n\nBefore\n\n[Photo: photo-a — unsupported or invalid image]\n\nAfter",
            services.notesRepository.getNoteDetails(note.id)?.markdownBody,
        )
    }

    private fun assertStagingFailuresAbortAndRetry(normalize: Boolean) {
        val image = if (normalize) ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(4000, 4000, BufferedImage.TYPE_BYTE_GRAY), "png", it)
        }.toByteArray() else png
        val zip = archive(image)
        val normalizer = if (normalize) MediaImageNormalizer { source, _ ->
            assertContentEquals(image, source.readByteArray())
            png.copyOf()
        } else noNormalization
        for (operation in listOf("open", "read", "close")) {
            val failure = IOException("Synthetic staging $operation failure")
            var opened = 0
            var injected = 0
            // Normalize: source inspection then codec input. Decode: also copy to final
            // staging and inspect it before opening its codec input (the fourth open).
            val codecInputOpen = if (normalize) 2 else 4
            val fileSystem = object : ForwardingFileSystem(FileSystem.SYSTEM) {
                override fun source(file: Path): Source {
                    if (++opened != codecInputOpen) return super.source(file)
                    if (operation == "open") {
                        injected++
                        throw failure
                    }
                    return object : ForwardingSource(super.source(file)) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (operation == "read") {
                                injected++
                                throw failure
                            }
                            return super.read(sink, byteCount)
                        }

                        override fun close() {
                            super.close()
                            if (operation == "close") {
                                injected++
                                throw failure
                            }
                        }
                    }
                }
            }
            withServices(fileSystem = fileSystem) { services ->
                assertSame(failure, assertFailsWith<IOException>(operation) {
                    services.dayOneArchiveImporter(zip, "Day One", normalizer)
                })
                assertEquals(1, injected)
                assertTrue(services.notesRepository.listNotebooks().isEmpty())

                val retry = services.dayOneArchiveImporter(zip, "Day One", normalizer)
                assertTrue(retry.completed)
                assertEquals(1, retry.notesCreated)
                assertEquals(1, retry.photosImported)
                assertEquals(0, retry.photosRejected)
                val notebook = services.notesRepository.listNotebooks().single()
                val note = services.notesRepository.listNotes(notebook.id).single()
                val body = assertNotNull(services.notesRepository.getNoteDetails(note.id)).markdownBody
                val assetId = findSomedayAssetIds(body).single()
                services.localMediaAssetStore.openSource(assetId).use { source ->
                    assertContentEquals(png, Buffer().also { it.writeAll(source) }.readByteArray())
                }
            }
        }
    }

    @Test
    fun workspaceLifecycleCannotChangeBetweenImageWriteAndNoteImport() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        withServices(beforeDecode = {
            reading.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }) { services ->
            val executor = Executors.newFixedThreadPool(2)
            try {
                val importing = executor.submit {
                    services.dayOneArchiveImporter(archive(), "Day One", noNormalization)
                }
                assertTrue(reading.await(5, TimeUnit.SECONDS))
                val attempted = CountDownLatch(1)
                val entered = CountDownLatch(1)
                val replacement = executor.submit {
                    attempted.countDown()
                    services.workspaceLifecycleCoordinator.exclusive {
                        entered.countDown()
                        val notebook = services.notesRepository.listNotebooks().single { it.title == "Journal" }
                        assertEquals(1, services.notesRepository.listNotes(notebook.id).size)
                    }
                }
                assertTrue(attempted.await(5, TimeUnit.SECONDS))
                assertFalse(entered.await(150, TimeUnit.MILLISECONDS))
                release.countDown()
                importing.get(5, TimeUnit.SECONDS)
                replacement.get(5, TimeUnit.SECONDS)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun withServices(
        beforeDecode: () -> Unit = {},
        wrapDriver: (SqlDriver) -> SqlDriver = { it },
        fileSystem: FileSystem = FileSystem.SYSTEM,
        block: (SystemV3ClientServices) -> Unit,
    ) {
        val directory = Files.createTempDirectory("someday-day-one-")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${directory.resolve("notes.db")}")
        val transport = JdkSelfHostedSyncTransport()
        try {
            val database = SomedayDatabase(wrapDriver(driver))
            val deviceId = "00000000-0000-4000-8000-000000000001"
            val local = SqlDelightLocalDataRepository(database, deviceId)
            val settings = SqlDelightClientSettingsRepository(local)
            settings.saveLocalSnapshot(ClientSettings(activeDeviceId = deviceId))
            val keys = WorkspaceKeyRepository(local, InMemorySecureWorkspaceKeyStore())
            keys.createFirstRunWorkspace("Test device", "desktop")
            val services = createSystemV3ClientServices(
                localRepository = local,
                settingsRepository = settings,
                workspaceKeyProvider = keys::unlockedKeyOrNull,
                workspaceIdProvider = keys::workspaceIdOrNull,
                localMediaAssetStore = LocalMediaAssetStore(
                    database, directory.resolve("private").toString().toPath(),
                    fileSystem = fileSystem,
                    decodeValidator = MediaAssetDecodeValidator { source ->
                        beforeDecode()
                        val image = checkNotNull(ImageIO.read(ByteArrayInputStream(source.readByteArray())))
                        DecodedMediaAsset(image.width, image.height)
                    },
                ),
                selfHostedTransport = transport,
                selfHostedTransportV2 = transport,
                selfHostedMediaTransportV3 = transport,
                selfHostedSessionStore = UnavailableSelfHostedSessionCredentialStore,
            )
            block(services)
        } finally {
            transport.close()
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun twoNotes(secondBody: String, extraFields: String = ""): ByteArray {
        val json = """{"metadata":{"version":"1.0"},"entries":[
            {"uuid":"a","creationDate":"2026-01-01T00:00:00Z","text":"First"},
            {"uuid":"b","creationDate":"2026-01-02T00:00:00Z","text":${JsonPrimitive(secondBody)}$extraFields}
        ]}"""
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("Journal.json"))
                zip.write(json.encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
    }

    private fun archive(image: ByteArray = png): ByteArray {
        val md5 = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
        val json = """{"metadata":{"version":"1.0"},"entries":[{
            "uuid":"00000000000000000000000000000001","creationDate":"2026-05-01T16:30:00Z","timeZone":"Asia/Shanghai",
            "text":"# Travel\n\nBefore\n\n![](dayone-moment://photo-a)\n\nAfter",
            "photos":[{"identifier":"photo-a","md5":"$md5","type":"png"}]
        }]}"""
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in listOf("Journal.json" to json.encodeToByteArray(), "photos/$md5.png" to image)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private companion object {
        val noNormalization = MediaImageNormalizer { _, _ -> error("The fixture already fits the media bounds") }
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    }
}
