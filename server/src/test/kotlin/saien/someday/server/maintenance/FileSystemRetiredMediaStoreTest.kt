package saien.someday.server.maintenance

import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION

class FileSystemRetiredMediaStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun boundedCleanupIncludesOrphansAndTemporaryFilesAndPreservesCurrentAndOtherRoots() {
        val root = temporaryFolder.newFolder("media").toPath().toRealPath()
        val retired = RetiredMediaNamespace(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION, "legacy")
        val current = RetiredMediaNamespace(retired.userId, UUID.randomUUID(), "incarnation-v1")
        val other = RetiredMediaNamespace(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION, "legacy")
        listOf("unknown-workspace/orphan.bin", "workspace/shard/.media-upload-abcd.tmp").forEach {
            write(root.resolve(retired.relativeRoot).resolve(it))
        }
        val survivors = listOf(root.resolve(current.relativeRoot).resolve("current.bin"), root.resolve(other.relativeRoot).resolve("other.bin"), root.resolve(".someday-system/startup-probe-v1.bin"))
        survivors.forEach(::write)
        val store = FileSystemRetiredMediaStore(root, batchSize = 1)
        if (!supportsSecureTraversal(root)) {
            assertUnsafe { store.scan(retired) {} }
            assertUnsafe { store.deleteBatch(retired, listOf(RetiredMediaEntry("unknown-workspace/orphan.bin"))) }
            assertTrue(Files.isRegularFile(root.resolve(retired.relativeRoot).resolve("unknown-workspace/orphan.bin")))
            survivors.forEach { assertTrue(Files.isRegularFile(it)) }
            return
        }
        val initial = store.scan(retired) { assertEquals(1, it.size) }
        assertEquals(2L, initial.entries)
        val deleting = store.scan(retired) { store.deleteBatch(retired, it) }
        assertEquals(2L, deleting.entries)
        assertEquals(0L, store.scan(retired) {}.entries)
        survivors.forEach { assertTrue(Files.isRegularFile(it)) }
    }

    @Test fun symlinksAtTargetOrWithinItAndForgedRelativePathsNeverEscape() {
        val root = temporaryFolder.newFolder("protected").toPath().toRealPath()
        val outside = temporaryFolder.newFolder("outside").toPath().toRealPath()
        val sentinel = outside.resolve("sentinel")
        write(sentinel)
        val target = RetiredMediaNamespace(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION, "legacy")
        val targetRoot = root.resolve(target.relativeRoot)
        Files.createSymbolicLink(targetRoot, outside)
        val store = FileSystemRetiredMediaStore(root)
        assertUnsafe { store.scan(target) {} }
        Files.delete(targetRoot)
        Files.createDirectory(targetRoot)
        Files.createSymbolicLink(targetRoot.resolve("unsafe"), outside)
        assertUnsafe { store.scan(target) {} }
        assertUnsafe { store.deleteBatch(target, listOf(RetiredMediaEntry("../outside/sentinel"))) }
        assertTrue(Files.isRegularFile(sentinel))
    }

    @Test fun missingTargetIsEmptyButMissingConfiguredRootCannotCertifyAnything() {
        val root = temporaryFolder.newFolder("mounted").toPath().toRealPath()
        val target = RetiredMediaNamespace(UUID.randomUUID(), UUID.randomUUID(), "incarnation-v1")
        if (supportsSecureTraversal(root)) {
            assertEquals(0L, FileSystemRetiredMediaStore(root).scan(target) {}.entries)
        } else {
            assertUnsafe { FileSystemRetiredMediaStore(root).scan(target) {} }
        }
        assertFailsWith<MaintenanceMediaFailure> { FileSystemRetiredMediaStore(root.resolve("missing-mount")).scan(target) {} }
    }

    @Test fun firstFileIsReportedBeforeALaterEntryBecomesUnsafe() {
        val root = temporaryFolder.newFolder("partial-observation").toPath().toRealPath()
        val outside = temporaryFolder.newFolder("partial-outside").toPath().toRealPath()
        val target = RetiredMediaNamespace(UUID.randomUUID(), ACCOUNT_INITIAL_INCARNATION, "legacy")
        val targetRoot = root.resolve(target.relativeRoot)
        val names = listOf("first.bin", "second.bin")
        names.forEach { write(targetRoot.resolve(it)) }
        val store = FileSystemRetiredMediaStore(root, batchSize = 250)
        if (!supportsSecureTraversal(root)) {
            assertUnsafe { store.scan(target) {} }
            names.forEach { assertTrue(Files.isRegularFile(targetRoot.resolve(it))) }
            return
        }
        var observed = false
        assertUnsafe {
            store.scan(target) { entries ->
                assertEquals(1, entries.size, "The first file must be delivered before filling the batch or finishing traversal")
                observed = true
                // Directory iteration order is irrelevant: replace whichever file has not
                // yet been observed. Its no-follow attributes are read on the next iteration.
                val remaining = names.single { it != entries.single().key }
                Files.delete(targetRoot.resolve(remaining))
                Files.createSymbolicLink(targetRoot.resolve(remaining), outside)
            }
        }
        assertTrue(observed, "A later traversal failure must not erase the first object observation")
        assertTrue(Files.isDirectory(outside))
    }

    private fun write(path: java.nio.file.Path) {
        Files.createDirectories(path.parent)
        Files.writeString(path, "synthetic")
    }

    private fun assertUnsafe(block: () -> Unit) {
        assertEquals(MaintenanceMediaFailureReason.UNSAFE_PATH, assertFailsWith<MaintenanceMediaFailure>(block = block).reason)
    }

    private fun supportsSecureTraversal(root: java.nio.file.Path): Boolean {
        val supported = Files.newDirectoryStream(root.root).use { it is SecureDirectoryStream<*> }
        if (System.getenv("SOMEDAY_REQUIRE_SECURE_DIRECTORY_STREAM") == "true") {
            assertTrue(supported, "This gate requires actual secure directory traversal support.")
        }
        return supported
    }
}
