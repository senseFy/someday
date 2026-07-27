package saien.someday.data.importing.dayone

import okio.Buffer
import okio.Inflater
import okio.buffer
import okio.inflate

object DayOneArchiveReader {
    fun readJsonDocuments(
        archiveBytes: ByteArray,
        fallbackJournalTitle: String = "Day One",
    ): List<DayOneJsonDocument> {
        val entries = ZipJsonReader.readJsonEntries(archiveBytes)
        return entries
            .filter { entry -> entry.name.endsWith(".json", ignoreCase = true) }
            .map { entry ->
                DayOneJsonDocument(
                    journalTitle = entry.name.toJournalTitle(fallbackJournalTitle),
                    json = entry.bytes.decodeToString(),
                )
            }
    }

    private fun String.toJournalTitle(fallback: String): String {
        val fileName = substringAfterLast('/').substringAfterLast('\\')
        return fileName.substringBeforeLast('.', fileName).trim().ifBlank { fallback }
    }
}

private object ZipJsonReader {
    fun readJsonEntries(bytes: ByteArray): List<ZipJsonEntry> {
        val eocdOffset = findEndOfCentralDirectory(bytes)
        val entryCount = bytes.readUInt16Le(eocdOffset + 10)
        val centralDirectoryOffset = bytes.readUInt32Le(eocdOffset + 16).toInt()
        val entries = mutableListOf<ZipJsonEntry>()
        var cursor = centralDirectoryOffset

        repeat(entryCount) {
            require(bytes.readUInt32Le(cursor) == CentralDirectoryHeaderSignature) {
                "Invalid zip central directory entry."
            }
            val flags = bytes.readUInt16Le(cursor + 8)
            require(flags and EncryptedFlag == 0) { "Encrypted Day One zip exports are not supported." }
            val compressionMethod = bytes.readUInt16Le(cursor + 10)
            val compressedSize = bytes.readUInt32Le(cursor + 20)
            val uncompressedSize = bytes.readUInt32Le(cursor + 24)
            val fileNameLength = bytes.readUInt16Le(cursor + 28)
            val extraLength = bytes.readUInt16Le(cursor + 30)
            val commentLength = bytes.readUInt16Le(cursor + 32)
            val localHeaderOffset = bytes.readUInt32Le(cursor + 42).toInt()
            val fileName = bytes.decodeUtf8(cursor + 46, fileNameLength)

            if (!fileName.endsWith("/") && fileName.endsWith(".json", ignoreCase = true)) {
                entries += ZipJsonEntry(
                    name = fileName,
                    bytes = bytes.readEntryBytes(
                        localHeaderOffset = localHeaderOffset,
                        compressionMethod = compressionMethod,
                        compressedSize = compressedSize,
                        uncompressedSize = uncompressedSize,
                    ),
                )
            }

            cursor += 46 + fileNameLength + extraLength + commentLength
        }

        return entries
    }

    private fun ByteArray.readEntryBytes(
        localHeaderOffset: Int,
        compressionMethod: Int,
        compressedSize: Long,
        uncompressedSize: Long,
    ): ByteArray {
        require(readUInt32Le(localHeaderOffset) == LocalFileHeaderSignature) {
            "Invalid zip local file header."
        }
        val fileNameLength = readUInt16Le(localHeaderOffset + 26)
        val extraLength = readUInt16Le(localHeaderOffset + 28)
        val dataOffset = localHeaderOffset + 30 + fileNameLength + extraLength
        val compressed = copyOfRange(dataOffset, dataOffset + compressedSize.toInt())
        return when (compressionMethod) {
            StoredCompression -> compressed
            DeflatedCompression -> inflateRaw(compressed, uncompressedSize)
            else -> error("Unsupported zip compression method: $compressionMethod")
        }
    }

    private fun inflateRaw(
        bytes: ByteArray,
        expectedSize: Long,
    ): ByteArray {
        val source = Buffer().write(bytes).inflate(Inflater(true)).buffer()
        val inflated = source.readByteArray()
        require(expectedSize < 0 || inflated.size.toLong() == expectedSize) {
            "Inflated zip entry size does not match central directory metadata."
        }
        return inflated
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        val minimumOffset = 0.coerceAtLeast(bytes.size - MaxCommentLength - EndOfCentralDirectoryLength)
        var cursor = bytes.size - EndOfCentralDirectoryLength
        while (cursor >= minimumOffset) {
            if (bytes.readUInt32Le(cursor) == EndOfCentralDirectorySignature) {
                return cursor
            }
            cursor -= 1
        }
        error("Invalid zip archive: end of central directory not found.")
    }

    private fun ByteArray.readUInt16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readUInt32Le(offset: Int): Long =
        (this[offset].toLong() and 0xffL) or
            ((this[offset + 1].toLong() and 0xffL) shl 8) or
            ((this[offset + 2].toLong() and 0xffL) shl 16) or
            ((this[offset + 3].toLong() and 0xffL) shl 24)

    private fun ByteArray.decodeUtf8(
        offset: Int,
        byteCount: Int,
    ): String =
        copyOfRange(offset, offset + byteCount).decodeToString()

    private const val LocalFileHeaderSignature = 0x04034b50L
    private const val CentralDirectoryHeaderSignature = 0x02014b50L
    private const val EndOfCentralDirectorySignature = 0x06054b50L
    private const val EndOfCentralDirectoryLength = 22
    private const val MaxCommentLength = 65_535
    private const val StoredCompression = 0
    private const val DeflatedCompression = 8
    private const val EncryptedFlag = 1
}

private data class ZipJsonEntry(
    val name: String,
    val bytes: ByteArray,
)
