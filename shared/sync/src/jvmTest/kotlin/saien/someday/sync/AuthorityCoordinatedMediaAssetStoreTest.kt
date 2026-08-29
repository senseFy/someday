package saien.someday.sync

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import saien.someday.data.local.createSomedayJdbcDriver
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.data.media.DecodedMediaAsset
import saien.someday.data.media.LocalMediaAssetStore
import saien.someday.data.media.MediaAssetAddressingStrategy
import saien.someday.data.media.MediaAssetDecodeValidator
import saien.someday.data.media.MediaAssetImportRequest
import saien.someday.data.media.MediaAssetInspection
import saien.someday.data.media.MediaAssetInspector
import saien.someday.data.media.MediaAssetVerificationResult
import saien.someday.domain.media.MediaAssetId
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Source

class AuthorityCoordinatedMediaAssetStoreTest {
    @Test
    fun workspaceReplacementWaitsUntilVerifiedPreviewBytesAreCopied() {
        val directory = Files.createTempDirectory("someday-media-preview-race-")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${directory.resolve("someday.db").toAbsolutePath()}")
        val previewReadEntered = CountDownLatch(1)
        val releasePreviewRead = CountDownLatch(1)
        val blockNextRead = AtomicBoolean(false)
        val didBlockRead = AtomicBoolean(false)
        val fileSystem = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun source(file: Path): Source = object : ForwardingSource(super.source(file)) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (blockNextRead.get() && didBlockRead.compareAndSet(false, true)) {
                        previewReadEntered.countDown()
                        assertTrue(releasePreviewRead.await(5, TimeUnit.SECONDS))
                    }
                    return super.read(sink, byteCount)
                }
            }
        }
        val rawStore = LocalMediaAssetStore(
            database = SomedayDatabase(driver),
            appPrivateRoot = directory.resolve("private").toString().toPath(),
            fileSystem = fileSystem,
            addressingStrategy = MediaAssetAddressingStrategy(MediaAssetId::fromCanonicalValue),
            inspector = MediaAssetInspector { _, _, declared, _ ->
                MediaAssetInspection(checkNotNull(declared), 32, 32)
            },
            decodeValidator = MediaAssetDecodeValidator { DecodedMediaAsset(32, 32) },
        )
        val coordinator = WorkspaceLifecycleCoordinator()
        val mediaStore = AuthorityCoordinatedMediaAssetStore(rawStore, coordinator)
        val imported = mediaStore.importAsset(
            source = Buffer().write(IMAGE_BYTES),
            request = MediaAssetImportRequest(mediaType = "image/png"),
        )
        val digest = imported.asset.contentSha256
        val objectPath = directory.resolve(
            "private/media-v1/objects/${digest.take(2)}/${digest.substring(2, 4)}/$digest.blob",
        )
        Files.delete(objectPath)
        assertIs<MediaAssetVerificationResult.Missing>(mediaStore.verifyAsset(imported.asset.metadata.id))
        Files.write(objectPath, IMAGE_BYTES)
        val replacementAttempted = CountDownLatch(1)
        val replacementEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            blockNextRead.set(true)
            val preview = executor.submit<CoordinatedMediaPreviewReadResult> {
                mediaStore.readVerifiedPreview(imported.asset.metadata.id)
            }
            assertTrue(previewReadEntered.await(5, TimeUnit.SECONDS))

            val replacement = executor.submit {
                replacementAttempted.countDown()
                coordinator.productAccess { replacementEntered.countDown() }
            }
            assertTrue(replacementAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(
                replacementEntered.await(250, TimeUnit.MILLISECONDS),
                "Workspace replacement bypassed an in-flight preview copy.",
            )

            releasePreviewRead.countDown()
            val loaded = assertIs<CoordinatedMediaPreviewReadResult.Loaded>(preview.get(5, TimeUnit.SECONDS))
            replacement.get(5, TimeUnit.SECONDS)

            assertContentEquals(IMAGE_BYTES, loaded.copyBytes())
            assertTrue(replacementEntered.await(1, TimeUnit.SECONDS))
        } finally {
            releasePreviewRead.countDown()
            executor.shutdownNow()
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun workspaceReplacementWaitsForInFlightMediaImportBeforeTakingFinalSnapshot() {
        val directory = Files.createTempDirectory("someday-media-replacement-race-")
        val driver = createSomedayJdbcDriver("jdbc:sqlite:${directory.resolve("someday.db").toAbsolutePath()}")
        val rawStore = LocalMediaAssetStore(
            database = SomedayDatabase(driver),
            appPrivateRoot = directory.resolve("private").toString().toPath(),
            addressingStrategy = MediaAssetAddressingStrategy(MediaAssetId::fromCanonicalValue),
            inspector = MediaAssetInspector { _, _, declared, _ ->
                MediaAssetInspection(checkNotNull(declared), 32, 32)
            },
            decodeValidator = MediaAssetDecodeValidator { DecodedMediaAsset(32, 32) },
        )
        val workspaceLifecycleCoordinator = WorkspaceLifecycleCoordinator()
        val mediaStore = AuthorityCoordinatedMediaAssetStore(rawStore, workspaceLifecycleCoordinator)
        val importEntered = CountDownLatch(1)
        val releaseImport = CountDownLatch(1)
        val replacementAttempted = CountDownLatch(1)
        val replacementEntered = CountDownLatch(1)
        val replacementSawImportedAsset = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val importing = executor.submit {
                mediaStore.importAsset(
                    MediaAssetImportRequest(mediaType = "image/png", originalFileName = "image.png"),
                ) { sink ->
                    importEntered.countDown()
                    assertTrue(releaseImport.await(5, TimeUnit.SECONDS))
                    sink.write(IMAGE_BYTES)
                }
            }
            assertTrue(importEntered.await(5, TimeUnit.SECONDS))

            val replacementSnapshot = executor.submit {
                replacementAttempted.countDown()
                workspaceLifecycleCoordinator.productAccess {
                    replacementEntered.countDown()
                    replacementSawImportedAsset.set(rawStore.listAssets().isNotEmpty())
                }
            }
            assertTrue(replacementAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(
                replacementEntered.await(250, TimeUnit.MILLISECONDS),
                "Workspace replacement bypassed an in-flight coordinated media import.",
            )

            releaseImport.countDown()
            importing.get(5, TimeUnit.SECONDS)
            replacementSnapshot.get(5, TimeUnit.SECONDS)

            assertTrue(replacementEntered.await(1, TimeUnit.SECONDS))
            assertTrue(
                replacementSawImportedAsset.get(),
                "Workspace replacement must snapshot state after the media import commits.",
            )
        } finally {
            releaseImport.countDown()
            executor.shutdownNow()
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        val IMAGE_BYTES = ByteArray(2_048) { (it * 17).toByte() }
    }
}
