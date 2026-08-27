package saien.someday.server.media

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

data class MediaBlobKey(
    val userId: UUID,
    val workspaceId: String,
    val mediaId: String,
) {
    init {
        require(workspaceId.matches(Regex("^workspace-[0-9a-f]{32}$")))
        require(mediaId.matches(Regex("^[0-9a-f]{64}$")))
    }
}

data class MediaBlobMetadata(
    val bytes: Long,
    val sha256: String,
)

data class MediaBlobValue(
    val metadata: MediaBlobMetadata,
    val bytes: ByteArray,
)

sealed interface MediaBlobPutResult {
    data class Stored(val idempotentReplay: Boolean) : MediaBlobPutResult
    data object ImmutableMismatch : MediaBlobPutResult
}

/**
 * Opaque binary storage boundary for System V3 media. Implementations never
 * receive plaintext, account credentials, or workspace keys.
 */
interface MediaBlobStore {
    fun putImmutable(
        key: MediaBlobKey,
        bytes: ByteArray,
        expectedSha256: String,
    ): MediaBlobPutResult

    fun head(key: MediaBlobKey): MediaBlobMetadata?

    fun read(key: MediaBlobKey, maxBytes: Int): MediaBlobValue?
}

/**
 * Local durable implementation. Files are written to a sibling temporary file,
 * forced to disk, and atomically promoted without replacement.
 */
class FileSystemMediaBlobStore(root: Path) : MediaBlobStore {
    private val root: Path = root.toAbsolutePath().normalize().also(Files::createDirectories)

    override fun putImmutable(
        key: MediaBlobKey,
        bytes: ByteArray,
        expectedSha256: String,
    ): MediaBlobPutResult {
        require(bytes.isNotEmpty())
        require(expectedSha256 == sha256(bytes))
        val target = pathFor(key)
        existingResult(target, bytes.size.toLong(), expectedSha256)?.let { return it }

        val parent = checkNotNull(target.parent)
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".media-upload-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                // A same-filesystem hard link publishes the already-fsynced inode atomically and,
                // unlike an implementation-specific ATOMIC_MOVE, can never replace an existing key.
                Files.createLink(target, temporary)
                forceDirectory(parent)
                return MediaBlobPutResult.Stored(idempotentReplay = false)
            } catch (_: FileAlreadyExistsException) {
                return checkNotNull(existingResult(target, bytes.size.toLong(), expectedSha256))
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun head(key: MediaBlobKey): MediaBlobMetadata? {
        val path = pathFor(key)
        if (!Files.isRegularFile(path)) return null
        val size = Files.size(path)
        return MediaBlobMetadata(size, Files.newInputStream(path).use(::sha256))
    }

    override fun read(key: MediaBlobKey, maxBytes: Int): MediaBlobValue? {
        require(maxBytes > 0)
        val path = pathFor(key)
        if (!Files.isRegularFile(path)) return null
        val size = Files.size(path)
        require(size in 1..maxBytes.toLong()) { "Stored media blob exceeds its protocol bound." }
        val bytes = Files.readAllBytes(path)
        return MediaBlobValue(MediaBlobMetadata(bytes.size.toLong(), sha256(bytes)), bytes)
    }

    private fun existingResult(
        path: Path,
        expectedBytes: Long,
        expectedSha256: String,
    ): MediaBlobPutResult? {
        if (!Files.exists(path)) return null
        val metadata = MediaBlobMetadata(Files.size(path), Files.newInputStream(path).use(::sha256))
        return if (metadata.bytes == expectedBytes && metadata.sha256 == expectedSha256) {
            MediaBlobPutResult.Stored(idempotentReplay = true)
        } else {
            MediaBlobPutResult.ImmutableMismatch
        }
    }

    private fun pathFor(key: MediaBlobKey): Path {
        val mediaDirectory = root
            .resolve(key.userId.toString())
            .resolve(key.workspaceId)
            .resolve(key.mediaId.substring(0, 2))
            .resolve(key.mediaId.substring(2, 4))
            .resolve(key.mediaId)
        return mediaDirectory.resolve("object.bin").normalize().also { path ->
            require(path.startsWith(root)) { "Media blob path escaped its configured root." }
        }
    }

    private fun forceDirectory(directory: Path) {
        runCatching {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

private fun sha256(bytes: ByteArray): String =
    "sha256:${MessageDigest.getInstance("SHA-256").digest(bytes).hex()}"

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) digest.update(buffer, 0, read)
    }
    return "sha256:${digest.digest().hex()}"
}

private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte) }
