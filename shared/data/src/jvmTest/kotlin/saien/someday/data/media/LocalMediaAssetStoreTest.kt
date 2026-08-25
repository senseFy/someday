@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.media

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.media.MediaAssetId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LocalMediaAssetStoreTest {
    @Test
    fun localImageImportDetectsCanonicalMediaTypeFromBytes() = withFixture { fixture ->
        val imported = fixture.store.importAsset(
            source = Buffer().write(PNG_1X1),
            request = MediaAssetImportRequest(originalFileName = "no-extension"),
        )

        assertEquals("image/png", imported.asset.metadata.mediaType)
    }

    @Test
    fun streamsBoundedImportIntoContentAddressedPrivateStorage() = withFixture { fixture ->
        val source = RecordingSource(Buffer().write(PNG_1X1))
        val result = source.use {
            fixture.store.importAsset(
                source = it,
                request = MediaAssetImportRequest(
                    mediaType = "image/png",
                    originalFileName = "pixel.png",
                    expectedByteSize = PNG_1X1.size.toLong(),
                ),
            )
        }

        assertFalse(result.reusedExistingAsset)
        assertEquals(result.asset.contentSha256, result.asset.metadata.id.value) // deterministic test strategy only
        assertEquals(PNG_1X1.size.toLong(), result.asset.metadata.byteSize)
        assertEquals(1, result.asset.metadata.pixelWidth)
        assertEquals(1, result.asset.metadata.pixelHeight)
        assertEquals(MediaAssetLocalState.Available, result.asset.localState)
        assertNull(result.asset.publishedAuthorityBindingId)
        assertEquals(
            listOf(result.asset.metadata.id),
            fixture.store.listAssetsPendingPublication(AUTHORITY_A, WORKSPACE_A).map { it.metadata.id },
        )
        assertTrue(source.maxRequestedByteCount in 1L..8_192L)

        val storedBytes = fixture.store.openSource(result.asset.metadata.id).buffer().use { it.readByteArray() }
        assertContentEquals(PNG_1X1, storedBytes)
        assertTrue(FileSystem.SYSTEM.metadata(objectPath(fixture.root, result.asset.contentSha256)).isRegularFile)
    }

    @Test
    fun chunkProducerUsesTheSameBoundedVerifiedImportPath() = withFixture { fixture ->
        val result = fixture.store.importAsset(
            request = MediaAssetImportRequest(
                mediaType = "image/png",
                expectedByteSize = PNG_1X1.size.toLong(),
            ),
        ) { sink ->
            PNG_1X1.asList().chunked(7).forEach { chunk ->
                sink.write(chunk.toByteArray())
            }
        }

        assertContentEquals(
            PNG_1X1,
            fixture.store.openSource(result.asset.metadata.id).buffer().use { it.readByteArray() },
        )
        assertFailsWith<MediaAssetImportTooLargeException> {
            fixture.store.importAsset(
                request = MediaAssetImportRequest(mediaType = "image/png", maxBytes = 10),
            ) { sink -> sink.write(PNG_1X1) }
        }
    }

    @Test
    fun duplicateContentKeepsMetadataImmutableAndPublicationMonotonic() = withFixture { fixture ->
        val first = fixture.importPng(originalFileName = "first.png").asset
        val second = fixture.store.importAsset(
            source = Buffer().write(PNG_1X1),
            request = MediaAssetImportRequest(
                mediaType = "image/png",
                originalFileName = "renamed.png",
                publishedAuthorityBindingId = AUTHORITY_A,
                publishedWorkspaceId = WORKSPACE_A,
                publishedObjectDigest = "object-v3:001",
            ),
        )

        assertTrue(second.reusedExistingAsset)
        assertEquals(first.metadata.id, second.asset.metadata.id)
        assertEquals("first.png", second.asset.metadata.originalFileName)
        assertEquals(AUTHORITY_A, second.asset.publishedAuthorityBindingId)
        assertEquals("object-v3:001", second.asset.publishedObjectDigest)
        assertTrue(fixture.store.listAssetsPendingPublication(AUTHORITY_A, WORKSPACE_A).isEmpty())
        assertEquals(second.asset, fixture.store.markPublished(second.asset.metadata.id, AUTHORITY_A, WORKSPACE_A, "object-v3:001"))
        assertFailsWith<MediaAssetIdentityConflictException> {
            fixture.store.markPublished(second.asset.metadata.id, AUTHORITY_A, WORKSPACE_A, "object-v3:different")
        }
    }

    @Test
    fun remoteMaterializationCanStartPublished() = withFixture { fixture ->
        val expectedId = MediaAssetId.fromCanonicalValue("12".repeat(32))
        val imported = fixture.store.importAsset(
            source = Buffer().write(PNG_1X1),
            request = MediaAssetImportRequest(
                mediaType = "image/png",
                expectedAssetId = expectedId,
                expectedPixelWidth = 1,
                expectedPixelHeight = 1,
                publishedAuthorityBindingId = AUTHORITY_A,
                publishedWorkspaceId = WORKSPACE_A,
                publishedObjectDigest = "remote-object:abc",
            ),
        ).asset

        assertEquals(expectedId, imported.metadata.id)
        assertEquals(AUTHORITY_A, imported.publishedAuthorityBindingId)
        assertEquals("remote-object:abc", imported.publishedObjectDigest)
        assertTrue(fixture.store.listAssetsPendingPublication(AUTHORITY_A, WORKSPACE_A).isEmpty())
    }

    @Test
    fun remoteMaterializationRejectsManifestDimensionMismatchBeforeCommit() = withFixture { fixture ->
        val expectedId = MediaAssetId.fromCanonicalValue("56".repeat(32))

        assertFailsWith<MediaAssetIntegrityException> {
            fixture.store.importAsset(
                source = Buffer().write(PNG_1X1),
                request = MediaAssetImportRequest(
                    mediaType = "image/png",
                    expectedAssetId = expectedId,
                    expectedPixelWidth = 2,
                    expectedPixelHeight = 1,
                    publishedAuthorityBindingId = AUTHORITY_A,
                    publishedWorkspaceId = WORKSPACE_A,
                    publishedObjectDigest = "remote-object:mismatch-test",
                ),
            )
        }
        assertNull(fixture.store.getAsset(expectedId))
    }

    @Test
    fun distinctRemoteAssetIdsCanShareOneContentAddressedObject() = withFixture { fixture ->
        val firstId = MediaAssetId.fromCanonicalValue("12".repeat(32))
        val secondId = MediaAssetId.fromCanonicalValue("34".repeat(32))

        val first = fixture.store.importAsset(
            source = Buffer().write(PNG_1X1),
            request = MediaAssetImportRequest(
                mediaType = "image/png",
                expectedAssetId = firstId,
                publishedAuthorityBindingId = AUTHORITY_A,
                publishedWorkspaceId = WORKSPACE_A,
                publishedObjectDigest = "remote-object:first",
            ),
        )
        val second = fixture.store.importAsset(
            source = Buffer().write(PNG_1X1),
            request = MediaAssetImportRequest(
                mediaType = "image/png",
                expectedAssetId = secondId,
                publishedAuthorityBindingId = AUTHORITY_A,
                publishedWorkspaceId = WORKSPACE_A,
                publishedObjectDigest = "remote-object:second",
            ),
        )

        assertFalse(first.reusedExistingAsset)
        assertFalse(second.reusedExistingAsset)
        assertEquals(2, fixture.store.listAssets().size)
        assertEquals(first.asset.contentSha256, second.asset.contentSha256)
        assertContentEquals(
            fixture.store.openSource(firstId).buffer().use { it.readByteArray() },
            fixture.store.openSource(secondId).buffer().use { it.readByteArray() },
        )
    }

    @Test
    fun authorityScopedPublicationEvidenceSurvivesDatabaseRestart() {
        val directory = Files.createTempDirectory("someday-media-publication-")
        val dbPath = directory.resolve("someday.db")
        val jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        val root = directory.resolve("private-files").toString().toPath()
        val assetId: MediaAssetId
        try {
            createSomedayJdbcDriver(jdbcUrl).use { driver ->
                val store = testStore(SomedayDatabase(driver), root)
                assetId = store.importAsset(
                    Buffer().write(PNG_1X1),
                    MediaAssetImportRequest(mediaType = "image/png"),
                ).asset.metadata.id
            }
            JdbcSqliteDriver(jdbcUrl).use { driver ->
                val reopened = testStore(SomedayDatabase(driver), root)
                assertEquals(
                    listOf(assetId),
                    reopened.listAssetsPendingPublication(AUTHORITY_A, WORKSPACE_A).map { it.metadata.id },
                )
                reopened.markPublished(assetId, AUTHORITY_A, WORKSPACE_A, "object-after-restart")
            }
            JdbcSqliteDriver(jdbcUrl).use { driver ->
                val reopened = testStore(SomedayDatabase(driver), root)
                assertTrue(reopened.listAssetsPendingPublication(AUTHORITY_A, WORKSPACE_A).isEmpty())
                assertEquals(
                    "object-after-restart",
                    reopened.getAsset(assetId)?.publishedObjectDigest,
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun publicationEvidenceIsScopedToTheAuthenticatedAuthority() = withFixture { fixture ->
        val asset = fixture.importPng().asset

        fixture.store.markPublished(asset.metadata.id, AUTHORITY_A, WORKSPACE_A, "object-a")

        assertTrue(fixture.store.listAssetsPendingPublication(AUTHORITY_A, WORKSPACE_A).isEmpty())
        assertEquals(
            listOf(asset.metadata.id),
            fixture.store.listAssetsPendingPublication(AUTHORITY_B, WORKSPACE_A).map { it.metadata.id },
        )

        val republished = fixture.store.markPublished(asset.metadata.id, AUTHORITY_B, WORKSPACE_A, "object-b")
        assertEquals(AUTHORITY_B, republished.publishedAuthorityBindingId)
        assertEquals("object-b", republished.publishedObjectDigest)
        assertTrue(fixture.store.listAssetsPendingPublication(AUTHORITY_B, WORKSPACE_A).isEmpty())
        assertEquals(
            listOf(asset.metadata.id),
            fixture.store.listAssetsPendingPublication(AUTHORITY_A, WORKSPACE_A).map { it.metadata.id },
        )
    }

    @Test
    fun sizeDigestAndInspectorFailuresLeaveNoMetadataOrStagingFile() = withFixture { fixture ->
        assertFailsWith<MediaAssetImportTooLargeException> {
            fixture.store.importAsset(
                Buffer().write(PNG_1X1),
                MediaAssetImportRequest(mediaType = "image/png", maxBytes = PNG_1X1.size.toLong() - 1L),
            )
        }
        assertFailsWith<MediaAssetIntegrityException> {
            fixture.store.importAsset(
                Buffer().write(PNG_1X1),
                MediaAssetImportRequest(mediaType = "image/png", expectedContentSha256 = "00".repeat(32)),
            )
        }
        assertFailsWith<MediaAssetInspectionException> {
            fixture.store.importAsset(
                Buffer().write(PNG_1X1),
                MediaAssetImportRequest(mediaType = "image/jpeg"),
            )
        }

        assertTrue(fixture.store.listAssets().isEmpty())
        assertTrue(FileSystem.SYSTEM.listOrNull(stagingRoot(fixture.root)).orEmpty().isEmpty())
        assertTrue(FileSystem.SYSTEM.listRecursivelyOrEmpty(objectsRoot(fixture.root)).none())
    }

    @Test
    fun corruptEncodedPayloadFailsFullDecodeAndLeavesNoStoredAsset() = withFixture { fixture ->
        val corruptPng = PNG_1X1.copyOf().also { bytes ->
            bytes[45] = (bytes[45].toInt() xor 0x7f).toByte()
        }

        assertFailsWith<MediaAssetInspectionException> {
            fixture.store.importAsset(
                Buffer().write(corruptPng),
                MediaAssetImportRequest(mediaType = "image/png"),
            )
        }

        assertTrue(fixture.store.listAssets().isEmpty())
        assertTrue(FileSystem.SYSTEM.listOrNull(stagingRoot(fixture.root)).orEmpty().isEmpty())
        assertTrue(FileSystem.SYSTEM.listRecursivelyOrEmpty(objectsRoot(fixture.root)).none())
    }

    @Test
    fun decodedDimensionsMustMatchInspectedMetadataBeforePromotion() = withFixture(
        decodeValidator = MediaAssetDecodeValidator { DecodedMediaAsset(2, 1) },
    ) { fixture ->
        assertFailsWith<MediaAssetInspectionException> {
            fixture.importPng()
        }

        assertTrue(fixture.store.listAssets().isEmpty())
        assertTrue(FileSystem.SYSTEM.listOrNull(stagingRoot(fixture.root)).orEmpty().isEmpty())
        assertTrue(FileSystem.SYSTEM.listRecursivelyOrEmpty(objectsRoot(fixture.root)).none())
    }

    @Test
    fun failedAtomicPromotionCleansStagingAndDoesNotCommitMetadata() {
        val failingFileSystem = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: Path, target: Path) {
                throw IOException("injected atomic move failure")
            }
        }
        withFixture(fileSystem = failingFileSystem) { fixture ->
            assertFailsWith<IOException> {
                fixture.importPng()
            }

            assertTrue(fixture.store.listAssets().isEmpty())
            assertTrue(FileSystem.SYSTEM.listOrNull(stagingRoot(fixture.root)).orEmpty().isEmpty())
        }
    }

    @Test
    fun verificationDetectsCorruptionAndReimportRepairsCanonicalObject() = withFixture { fixture ->
        val first = fixture.importPng().asset
        val objectPath = objectPath(fixture.root, first.contentSha256)
        FileSystem.SYSTEM.write(objectPath) { write(ByteArray(PNG_1X1.size)) }

        val corrupt = fixture.store.verifyAsset(first.metadata.id)
        assertIs<MediaAssetVerificationResult.Corrupt>(corrupt)
        assertNotEquals(first.contentSha256, corrupt.observedContentSha256)
        assertEquals(MediaAssetLocalState.Corrupt, fixture.store.getAsset(first.metadata.id)?.localState)

        val repaired = fixture.importPng().asset
        assertEquals(first.metadata.id, repaired.metadata.id)
        assertEquals(MediaAssetLocalState.Available, repaired.localState)
        assertIs<MediaAssetVerificationResult.Verified>(fixture.store.verifyAsset(first.metadata.id))
        val restoredBytes = fixture.store.openSource(first.metadata.id).buffer().use { it.readByteArray() }
        assertContentEquals(PNG_1X1, restoredBytes)
    }

    @Test
    fun cleanupRemovesOldStoreOwnedOrphansAndMarksMissingRows() = withFixture { fixture ->
        val asset = fixture.importPng().asset
        val staleTemporary = stagingRoot(fixture.root).resolve("abandoned.part")
        FileSystem.SYSTEM.write(staleTemporary) { writeUtf8("temporary") }
        val orphan = objectsRoot(fixture.root).resolve("aa").resolve("bb").resolve("orphan.blob")
        FileSystem.SYSTEM.createDirectories(checkNotNull(orphan.parent))
        FileSystem.SYSTEM.write(orphan) { writeUtf8("orphan") }
        FileSystem.SYSTEM.delete(objectPath(fixture.root, asset.contentSha256))

        val result = fixture.store.cleanupOrphans(Instant.parse("2100-01-01T00:00:00Z"))

        assertEquals(1, result.temporaryFilesRemoved)
        assertEquals(1, result.orphanObjectFilesRemoved)
        assertEquals(1, result.assetsMarkedMissing)
        assertFalse(FileSystem.SYSTEM.exists(staleTemporary))
        assertFalse(FileSystem.SYSTEM.exists(orphan))
        assertEquals(MediaAssetLocalState.Missing, fixture.store.getAsset(asset.metadata.id)?.localState)
    }

    @Test
    fun productionAddressingDefaultDoesNotExposeContentHash() {
        val contentHash = "ab".repeat(32)
        val strategy = RandomMediaAssetAddressingStrategy { size -> ByteArray(size) { 0x5a } }

        val assetId = strategy.createAssetId(contentHash)

        assertEquals("5a".repeat(32), assetId.value)
        assertNotEquals(contentHash, assetId.value)
    }

    private fun withFixture(
        fileSystem: FileSystem = FileSystem.SYSTEM,
        decodeValidator: MediaAssetDecodeValidator = JVM_TEST_DECODE_VALIDATOR,
        block: (Fixture) -> Unit,
    ) {
        val directory = Files.createTempDirectory("someday-media-store-")
        val dbPath = directory.resolve("someday.db")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        val root = directory.resolve("private-files").toString().toPath()
        val database = SomedayDatabase(driver)
        val fixture = Fixture(
            root = root,
            store = testStore(database, root, fileSystem, decodeValidator),
        )
        try {
            block(fixture)
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    private data class Fixture(
        val root: Path,
        val store: LocalMediaAssetStore,
    ) {
        fun importPng(originalFileName: String? = null): MediaAssetImportResult =
            store.importAsset(
                source = Buffer().write(PNG_1X1),
                request = MediaAssetImportRequest(
                    mediaType = "image/png",
                    originalFileName = originalFileName,
                ),
            )
    }

    private class RecordingSource(delegate: Source) : ForwardingSource(delegate) {
        var maxRequestedByteCount: Long = 0L
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            maxRequestedByteCount = maxOf(maxRequestedByteCount, byteCount)
            return super.read(sink, byteCount)
        }
    }

    companion object {
        private const val AUTHORITY_A = "self-hosted|22:https://sync.example|6:user-a"
        private const val AUTHORITY_B = "self-hosted|22:https://sync.example|6:user-b"
        private const val WORKSPACE_A = "workspace-0123456789abcdef0123456789abcdef"
        private val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
        private val TEST_CLOCK: Instant = Instant.parse("2026-08-25T12:00:00Z")
        private val JVM_TEST_DECODE_VALIDATOR = MediaAssetDecodeValidator { source ->
            val image = checkNotNull(ImageIO.read(ByteArrayInputStream(source.readByteArray()))) {
                "Test image decoder rejected the encoded image."
            }
            DecodedMediaAsset(image.width, image.height)
        }

        private fun testStore(
            database: SomedayDatabase,
            root: Path,
            fileSystem: FileSystem = FileSystem.SYSTEM,
            decodeValidator: MediaAssetDecodeValidator = JVM_TEST_DECODE_VALIDATOR,
        ): LocalMediaAssetStore = LocalMediaAssetStore(
            database = database,
            appPrivateRoot = root,
            fileSystem = fileSystem,
            addressingStrategy = MediaAssetAddressingStrategy(MediaAssetId::fromCanonicalValue),
            decodeValidator = decodeValidator,
            clock = { TEST_CLOCK },
        )

        private fun stagingRoot(root: Path): Path = root.resolve("media-v1").resolve(".staging")

        private fun objectsRoot(root: Path): Path = root.resolve("media-v1").resolve("objects")

        private fun objectPath(root: Path, digest: String): Path =
            objectsRoot(root)
                .resolve(digest.take(2))
                .resolve(digest.substring(2, 4))
                .resolve("$digest.blob")

        private fun FileSystem.listRecursivelyOrEmpty(directory: Path): Sequence<Path> =
            if (metadataOrNull(directory)?.isDirectory == true) listRecursively(directory) else emptySequence()
    }
}
