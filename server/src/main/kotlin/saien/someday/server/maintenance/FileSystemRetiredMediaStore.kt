package saien.someday.server.maintenance

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView

/** Descriptor-relative traversal rejects symlinks at every ancestor and descendant. */
class FileSystemRetiredMediaStore(root: Path, private val batchSize: Int = 250) : RetiredMediaStore {
    private val root = root.toAbsolutePath().normalize()
    override val backend = MaintenanceMediaBackend.FILESYSTEM
    override val storageIdentity: String
        get() = guarded {
            var fileKey: Any? = null
            withDirectory(root, missingAllowed = false) { directory ->
                fileKey = directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()
            }
            checkNotNull(fileKey) { "Filesystem identity is unavailable." }
            "filesystem:" + storageIdentityDigest(root.toString(), fileKey.toString())
        }

    init { require(batchSize in 1..1000) }

    override fun scan(namespace: RetiredMediaNamespace, consume: (List<RetiredMediaEntry>) -> Unit): RetiredMediaScan = guarded {
        var count = 0L
        val batch = ArrayList<RetiredMediaEntry>(batchSize)
        withDirectory(targetPath(namespace), missingAllowed = true) { directory ->
            fun visit(current: SecureDirectoryStream<Path>, prefix: String, depth: Int) {
                if (depth > 64) throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.UNSAFE_PATH)
                for (entry in current) {
                    val name = entry.fileName
                    val attributes = current.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                        .readAttributes()
                    val relative = if (prefix.isEmpty()) name.toString() else "$prefix/$name"
                    when {
                        attributes.isSymbolicLink -> throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.UNSAFE_PATH)
                        attributes.isDirectory -> current.newDirectoryStream(name, NOFOLLOW_LINKS).use { visit(it, relative, depth + 1) }
                        attributes.isRegularFile -> {
                            count++
                            batch += RetiredMediaEntry(relative)
                            // Deliver the first physical observation immediately. A later
                            // traversal failure must not hide a late object from the audit.
                            if (count == 1L || batch.size == batchSize) {
                                consume(batch.toList())
                                batch.clear()
                            }
                        }
                        else -> throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.UNSAFE_PATH)
                    }
                }
            }
            visit(directory, "", 0)
        }
        if (batch.isNotEmpty()) consume(batch.toList())
        RetiredMediaScan(count)
    }

    override fun deleteBatch(namespace: RetiredMediaNamespace, entries: List<RetiredMediaEntry>) = guarded {
        require(entries.size <= batchSize)
        for (entry in entries) {
            require(entry.versionId == null)
            val relative = safeRelativePath(entry.key)
            val parent = targetPath(namespace).resolve(relative).parent
            withDirectory(parent, missingAllowed = true) { directory ->
                try {
                    val attributes = directory.getFileAttributeView(relative.fileName, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                        .readAttributes()
                    if (!attributes.isRegularFile || attributes.isSymbolicLink) {
                        throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.UNSAFE_PATH)
                    }
                    directory.deleteFile(relative.fileName)
                } catch (_: NoSuchFileException) {
                    // A duplicate maintenance deletion is already complete.
                }
            }
        }
    }

    private fun targetPath(namespace: RetiredMediaNamespace): Path = root.resolve(namespace.relativeRoot).normalize().also {
        check(it.startsWith(root) && it != root)
    }

    private fun safeRelativePath(value: String): Path {
        if (value.isEmpty() || value.contains('\\') || value.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.UNSAFE_PATH)
        }
        return Path.of(value).also {
            if (it.isAbsolute || it.normalize() != it) throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.UNSAFE_PATH)
        }
    }

    private fun withDirectory(path: Path, missingAllowed: Boolean, block: (SecureDirectoryStream<Path>) -> Unit) {
        val opened = mutableListOf<SecureDirectoryStream<Path>>()
        var enteredTarget = false
        try {
            val stream = Files.newDirectoryStream(checkNotNull(path.root))
            if (stream !is SecureDirectoryStream<Path>) {
                stream.close()
                throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.UNSAFE_PATH)
            }
            opened += stream
            for (part in path) {
                val attributes = opened.last().getFileAttributeView(part, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                    .readAttributes()
                if (!attributes.isDirectory || attributes.isSymbolicLink) {
                    throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.UNSAFE_PATH)
                }
                opened += opened.last().newDirectoryStream(part, NOFOLLOW_LINKS)
            }
            enteredTarget = true
            block(opened.last())
        } catch (failure: NoSuchFileException) {
            // A disappearing entry during traversal is an incomplete scan, not an empty root.
            // A missing configured mount/root is also not evidence of reclamation.
            if (!missingAllowed || enteredTarget || opened.size <= root.nameCount) throw failure
        } finally {
            opened.asReversed().forEach { it.close() }
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (failure: MaintenanceMediaFailure) {
        throw failure
    } catch (failure: AccessDeniedException) {
        throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.PERMISSION_DENIED, failure)
    } catch (failure: Exception) {
        throw MaintenanceMediaFailure(MaintenanceMediaFailureReason.STORAGE_FAILURE, failure)
    }
}
