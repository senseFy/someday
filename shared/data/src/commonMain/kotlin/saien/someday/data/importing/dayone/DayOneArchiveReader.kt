package saien.someday.data.importing.dayone

import okio.Buffer
import okio.Inflater
import okio.buffer
import okio.inflate

/** The supported archive profile and resource bounds are specified in docs/day-one-import.md. */
object DayOneArchiveReader {
    fun readJsonDocuments(
        archiveBytes: ByteArray,
        fallbackJournalTitle: String = "Day One",
    ): List<DayOneJsonDocument> = open(archiveBytes).documents(fallbackJournalTitle)

    internal fun open(bytes: ByteArray): DayOneArchive = DayOneArchive(bytes)
}

internal class DayOneArchive(private val bytes: ByteArray) {
    private data class Entry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val offset: Long,
    )

    private val entries: Map<String, Entry>
    private val directoryOffset: Long

    init {
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "Day One archive exceeds 512 MiB; export fewer entries." }
        val end = findEnd()
        require(u16(end + 4) == 0 && u16(end + 6) == 0) { "Split Day One archives are not supported." }
        var count = u16(end + 10).toLong()
        var directorySize = u32(end + 12)
        var offset = u32(end + 16)
        if (count == 0xffffL || directorySize == UINT_MAX || offset == UINT_MAX) {
            val locator = end - 20
            require(u32(locator) == 0x07064b50L && u32(locator + 4) == 0L && u32(locator + 16) == 1L) {
                "Invalid Day One ZIP64 locator."
            }
            val zip64 = index(u64(locator + 8), 56)
            require(u32(zip64) == 0x06064b50L && u64(zip64 + 4) >= 44L &&
                u32(zip64 + 16) == 0L && u32(zip64 + 20) == 0L &&
                u64(zip64 + 24) == u64(zip64 + 32)
            ) { "Invalid Day One ZIP64 directory." }
            count = u64(zip64 + 32)
            directorySize = u64(zip64 + 40)
            offset = u64(zip64 + 48)
        } else {
            require(u16(end + 8).toLong() == count) { "Split Day One archives are not supported." }
        }
        require(count in 1..100_000) { "Day One archive must contain 1 to 100000 files." }
        directoryOffset = offset
        var cursor = index(offset, directorySize)
        val directoryEnd = cursor + directorySize.toInt()
        require(directoryEnd <= end) { "Invalid Day One ZIP directory bounds." }
        val found = linkedMapOf<String, Entry>()
        repeat(count.toInt()) {
            require(u32(cursor) == 0x02014b50L) { "Invalid Day One ZIP directory entry." }
            index(cursor.toLong(), 46)
            val flags = u16(cursor + 8)
            require(flags and 0x41 == 0) { "Encrypted Day One archives are not supported." }
            val method = u16(cursor + 10)
            val crc = u32(cursor + 16)
            var compressed = u32(cursor + 20)
            var size = u32(cursor + 24)
            val nameLength = u16(cursor + 28)
            val extraLength = u16(cursor + 30)
            val commentLength = u16(cursor + 32)
            val disk = u16(cursor + 34)
            var localOffset = u32(cursor + 42)
            val next = cursor.toLong() + 46 + nameLength + extraLength + commentLength
            require(next <= directoryEnd) { "Invalid Day One ZIP directory entry bounds." }
            val name = bytes.decodeToString(cursor + 46, cursor + 46 + nameLength, throwOnInvalidSequence = true)
            require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && ':' !in name &&
                name.none(Char::isISOControl) && name.trimEnd('/').split('/').none { it == ".." || it == "." || it.isEmpty() }
            ) { "Unsafe Day One archive path." }
            require(disk == 0) { "Split Day One archives are not supported." }
            if (compressed == UINT_MAX || size == UINT_MAX || localOffset == UINT_MAX) {
                var extra = cursor + 46 + nameLength
                val extraEnd = extra + extraLength
                var resolved = false
                while (extra + 4 <= extraEnd) {
                    val tag = u16(extra)
                    val length = u16(extra + 2)
                    require(extra + 4 + length <= extraEnd) { "Invalid Day One ZIP extra field." }
                    if (tag == 1) {
                        var value = extra + 4
                        fun nextLong(): Long {
                            require(value + 8 <= extra + 4 + length) { "Incomplete Day One ZIP64 sizes." }
                            return u64(value).also { value += 8 }
                        }
                        if (size == UINT_MAX) size = nextLong()
                        if (compressed == UINT_MAX) compressed = nextLong()
                        if (localOffset == UINT_MAX) localOffset = nextLong()
                        resolved = true
                        break
                    }
                    extra += 4 + length
                }
                require(resolved) { "Missing Day One ZIP64 sizes." }
            }
            require(localOffset < directoryOffset && compressed <= bytes.size.toLong()) { "Invalid Day One ZIP file bounds." }
            val key = name.lowercase()
            require(key !in found) { "Ambiguous duplicate Day One archive path." }
            found[key] = Entry(name, flags, method, crc, compressed, size, localOffset)
            cursor = next.toInt()
        }
        require(cursor == directoryEnd) { "Invalid Day One ZIP directory length." }
        entries = found
    }

    fun documents(fallback: String): List<DayOneJsonDocument> {
        var totalBytes = 0L
        return entries.values.filter {
            it.name.endsWith(".json", ignoreCase = true) &&
                it.name.split('/').none { part -> part.startsWith('.') || part == "__MACOSX" }
        }.map { entry ->
            totalBytes += entry.size
            require(totalBytes <= 64L * 1024 * 1024) { "Day One JSON exceeds 64 MiB; export fewer entries." }
            val fileName = entry.name.substringAfterLast('/')
            DayOneJsonDocument(
                journalTitle = fileName.substringBeforeLast('.').trim().ifBlank { fallback },
                json = read(entry, 16L * 1024 * 1024).decodeToString(throwOnInvalidSequence = true),
                archiveDirectory = entry.name.substringBeforeLast('/', ""),
            )
        }.also { require(it.isNotEmpty()) { "Day One archive contains no journal JSON files." } }
    }

    fun contains(path: String): Boolean = path.lowercase() in entries

    fun read(path: String, maxBytes: Long): ByteArray = read(requireNotNull(entries[path.lowercase()]), maxBytes)

    private fun read(entry: Entry, maxBytes: Long): ByteArray {
        require(entry.size <= maxBytes) { "Day One archive member exceeds the supported size." }
        val local = index(entry.offset, 30)
        require(u32(local) == 0x04034b50L && u16(local + 6) == entry.flags && u16(local + 8) == entry.method) {
            "Invalid Day One ZIP local header."
        }
        val nameLength = u16(local + 26)
        val extraLength = u16(local + 28)
        index(local.toLong() + 30, nameLength.toLong() + extraLength)
        require(bytes.decodeToString(local + 30, local + 30 + nameLength, throwOnInvalidSequence = true) == entry.name) {
            "Day One ZIP local and directory names differ."
        }
        val start = local.toLong() + 30 + nameLength + extraLength
        val offset = index(start, entry.compressedSize)
        require(start + entry.compressedSize <= directoryOffset) { "Invalid Day One ZIP payload bounds." }
        val source = Buffer().write(bytes, offset, entry.compressedSize.toInt())
        val result = when (entry.method) {
            0 -> {
                require(entry.compressedSize == entry.size) { "Invalid stored Day One ZIP size." }
                source.readByteArray()
            }
            8 -> {
                val inflated = source.inflate(Inflater(true)).buffer()
                try {
                    val output = Buffer()
                    while (output.size <= entry.size) {
                        if (inflated.read(output, minOf(8192L, entry.size + 1 - output.size)) == -1L) break
                    }
                    require(output.size == entry.size) { "Day One ZIP inflated size mismatch." }
                    output.readByteArray()
                } finally {
                    inflated.close()
                }
            }
            else -> error("Unsupported Day One ZIP compression method.")
        }
        require(crc32(result) == entry.crc) { "Day One ZIP checksum mismatch." }
        return result
    }

    private fun findEnd(): Int {
        require(bytes.size >= 22) { "Invalid Day One ZIP archive." }
        for (offset in bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 65535)) {
            if (u32(offset) == 0x06054b50L && offset + 22 + u16(offset + 20) == bytes.size) return offset
        }
        error("Day One ZIP end of directory was not found.")
    }

    private fun index(offset: Long, length: Long): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) {
            "Truncated or invalid Day One ZIP archive."
        }
        return offset.toInt()
    }

    private fun u16(offset: Int): Int {
        index(offset.toLong(), 2)
        return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    }

    private fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

    private fun u64(offset: Int): Long {
        val high = u32(offset + 4)
        require(high <= Int.MAX_VALUE) { "Day One ZIP64 value exceeds the supported range." }
        return u32(offset) or (high shl 32)
    }

    private companion object {
        const val UINT_MAX = 0xffffffffL
        const val MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
        val crcTable = IntArray(256) { value ->
            var crc = value
            repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
            crc
        }

        fun crc32(bytes: ByteArray): Long {
            var crc = -1
            bytes.forEach { crc = crcTable[(crc xor it.toInt()) and 255] xor (crc ushr 8) }
            return crc.inv().toLong() and UINT_MAX
        }
    }
}
