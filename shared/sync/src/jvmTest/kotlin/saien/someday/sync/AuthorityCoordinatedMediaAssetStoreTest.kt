package saien.someday.sync

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
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
import saien.someday.domain.media.MediaAssetId
import okio.Path.Companion.toPath

class AuthorityCoordinatedMediaAssetStoreTest {
    @Test
    fun finalAdoptionEmptyCheckWaitsForInFlightMediaImportAndSeesIt() {
        val directory = Files.createTempDirectory("someday-media-adoption-race-")
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
        val mutationCoordinator = WorkspaceAuthorityMutationCoordinator()
        val mediaStore = AuthorityCoordinatedMediaAssetStore(rawStore, mutationCoordinator)
        val importEntered = CountDownLatch(1)
        val releaseImport = CountDownLatch(1)
        val adoptionAttempted = CountDownLatch(1)
        val adoptionEntered = CountDownLatch(1)
        val adoptionSawContent = AtomicBoolean(false)
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

            val finalAdoptionCheck = executor.submit {
                adoptionAttempted.countDown()
                mutationCoordinator.productAccess {
                    adoptionEntered.countDown()
                    adoptionSawContent.set(rawStore.listAssets().isNotEmpty())
                }
            }
            assertTrue(adoptionAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(
                adoptionEntered.await(250, TimeUnit.MILLISECONDS),
                "The final adoption check bypassed an in-flight coordinated media import.",
            )

            releaseImport.countDown()
            importing.get(5, TimeUnit.SECONDS)
            finalAdoptionCheck.get(5, TimeUnit.SECONDS)

            assertTrue(adoptionEntered.await(1, TimeUnit.SECONDS))
            assertTrue(
                adoptionSawContent.get(),
                "Adoption must re-check emptiness after the media import commits.",
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
