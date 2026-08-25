package saien.someday.server.media

import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class FileSystemMediaBlobStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun storesOpaqueBytesAtAWorkspaceScopedShardedImmutablePath() {
        val root = temporaryFolder.newFolder("media").toPath()
        val store = FileSystemMediaBlobStore(root)
        val userId = UUID.fromString("123e4567-e89b-42d3-a456-426614174000")
        val mediaId = "0123456789abcdef".repeat(4)
        val key = MediaBlobKey(userId, WORKSPACE, mediaId)
        val bytes = "client-encrypted-media".encodeToByteArray()
        val digest = sha256(bytes)

        assertEquals(MediaBlobPutResult.Stored(false), store.putImmutable(key, bytes, digest))
        assertEquals(MediaBlobPutResult.Stored(true), store.putImmutable(key, bytes, digest))
        assertEquals(MediaBlobMetadata(bytes.size.toLong(), digest), store.head(key))
        assertContentEquals(bytes, store.read(key, bytes.size)?.bytes)

        val expected = root.resolve(userId.toString())
            .resolve(WORKSPACE)
            .resolve("01")
            .resolve("23")
            .resolve(mediaId)
            .resolve("object.bin")
        assertTrue(Files.isRegularFile(expected))
        assertContentEquals(bytes, Files.readAllBytes(expected))
    }

    @Test
    fun rejectsASecondValueAndNeverCrossesAccountOrObjectBoundaries() {
        val store = FileSystemMediaBlobStore(temporaryFolder.newFolder("media").toPath())
        val firstUser = UUID.fromString("123e4567-e89b-42d3-a456-426614174000")
        val secondUser = UUID.fromString("123e4567-e89b-42d3-a456-426614174001")
        val mediaId = "abcdef0123456789".repeat(4)
        val key = MediaBlobKey(firstUser, WORKSPACE, mediaId)
        val first = ByteArray(41) { 1 }
        val second = ByteArray(41) { 2 }

        assertIs<MediaBlobPutResult.Stored>(store.putImmutable(key, first, sha256(first)))
        assertEquals(MediaBlobPutResult.ImmutableMismatch, store.putImmutable(key, second, sha256(second)))
        assertContentEquals(first, store.read(key, first.size)?.bytes)
        assertNull(store.head(MediaBlobKey(secondUser, WORKSPACE, mediaId)))
    }

    @Test
    fun validatesKeysDigestsAndReadBoundsBeforeMaterializing() {
        val userId = UUID.randomUUID()
        assertFailsWith<IllegalArgumentException> {
            MediaBlobKey(userId, WORKSPACE, UUID.randomUUID().toString())
        }
        assertFailsWith<IllegalArgumentException> {
            MediaBlobKey(userId, WORKSPACE, "A".repeat(64))
        }
        assertFailsWith<IllegalArgumentException> {
            MediaBlobKey(userId, "invalid", "0".repeat(64))
        }

        val store = FileSystemMediaBlobStore(temporaryFolder.newFolder("media").toPath())
        val key = MediaBlobKey(userId, WORKSPACE, "0".repeat(64))
        val bytes = ByteArray(128) { it.toByte() }
        assertFailsWith<IllegalArgumentException> {
            store.putImmutable(key, bytes, "sha256:${"0".repeat(64)}")
        }
        store.putImmutable(key, bytes, sha256(bytes))
        assertFailsWith<IllegalArgumentException> { store.read(key, 127) }
    }

    @Test
    fun deleteAndAbsentMetadataReconciliationAreExactAndIdempotent() {
        val store = FileSystemMediaBlobStore(temporaryFolder.newFolder("media").toPath())
        val userId = UUID.randomUUID()
        val mediaId = "1".repeat(64)
        val orphanKey = MediaBlobKey(userId, WORKSPACE, mediaId)
        val neighborKey = MediaBlobKey(userId, WORKSPACE, "2".repeat(64))
        val orphanBytes = ByteArray(41) { 3 }
        val neighborBytes = ByteArray(41) { 4 }

        store.putImmutable(orphanKey, orphanBytes, sha256(orphanBytes))
        store.putImmutable(neighborKey, neighborBytes, sha256(neighborBytes))

        assertTrue(store.removeUntracked(orphanKey))
        assertFalse(store.removeUntracked(orphanKey))
        assertNull(store.head(orphanKey))
        assertEquals(
            MediaBlobMetadata(neighborBytes.size.toLong(), sha256(neighborBytes)),
            store.head(neighborKey),
        )

        val replacement = ByteArray(41) { 5 }
        assertEquals(
            MediaBlobPutResult.Stored(idempotentReplay = false),
            store.putImmutable(orphanKey, replacement, sha256(replacement)),
        )
        assertTrue(store.delete(neighborKey))
        assertFalse(store.delete(neighborKey))
        assertNull(store.head(neighborKey))
        assertContentEquals(replacement, store.read(orphanKey, replacement.size)?.bytes)
    }

    @Test
    fun concurrentPublicationAtTheRealFilesystemBoundaryIsExactAndImmutable() {
        val store = FileSystemMediaBlobStore(temporaryFolder.newFolder("concurrent-media").toPath())
        val userId = UUID.randomUUID()
        val exactKey = MediaBlobKey(userId, WORKSPACE, "3".repeat(64))
        val exactBytes = ByteArray(128) { it.toByte() }

        val exact = race(
            { store.putImmutable(exactKey, exactBytes, sha256(exactBytes)) },
            { store.putImmutable(exactKey, exactBytes, sha256(exactBytes)) },
        ).map { assertIs<MediaBlobPutResult.Stored>(it) }
        assertEquals(listOf(false, true), exact.map { it.idempotentReplay }.sorted())
        assertContentEquals(exactBytes, store.read(exactKey, exactBytes.size)?.bytes)

        val mismatchKey = MediaBlobKey(userId, WORKSPACE, "4".repeat(64))
        val candidates = listOf(ByteArray(129) { 4 }, ByteArray(129) { 5 })
        val mismatch = race(
            { store.putImmutable(mismatchKey, candidates[0], sha256(candidates[0])) },
            { store.putImmutable(mismatchKey, candidates[1], sha256(candidates[1])) },
        )
        assertEquals(1, mismatch.filterIsInstance<MediaBlobPutResult.Stored>().size)
        assertEquals(1, mismatch.count { it == MediaBlobPutResult.ImmutableMismatch })
        val published = checkNotNull(store.read(mismatchKey, candidates[0].size)).bytes
        assertTrue(candidates.any(published::contentEquals))
    }

    private fun <T> race(first: () -> T, second: () -> T): List<T> {
        val start = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        return try {
            listOf(first, second).map { action ->
                executor.submit<T> {
                    start.await(30, TimeUnit.SECONDS)
                    action()
                }
            }.map { future -> future.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val WORKSPACE = "workspace-0123456789abcdef0123456789abcdef"
    }
}

private fun sha256(bytes: ByteArray): String =
    "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}"
