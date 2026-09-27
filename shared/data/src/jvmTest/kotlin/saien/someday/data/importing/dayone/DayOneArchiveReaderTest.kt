package saien.someday.data.importing.dayone

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DayOneArchiveReaderTest {
    @Test
    fun readsStoredDeflatedAndSmallZip64ArchivesWithUtf8NamesAndDescriptors() {
        for (stored in listOf(false, true)) {
            for (sizes64 in listOf(false, true)) {
                for (directory64 in listOf(false, true)) {
                    val archive = dayOneZip(
                        "Export/旅行.json" to "甲🙂".encodeToByteArray(),
                        "Export/Second.json" to "other".encodeToByteArray(),
                        "__MACOSX/._Journal.json" to byteArrayOf(1),
                        stored = stored, sizes64 = sizes64, directory64 = directory64,
                    )
                    val documents = DayOneArchiveReader.readJsonDocuments(archive)
                    assertEquals(listOf("旅行", "Second"), documents.map { it.journalTitle })
                    assertEquals(listOf("甲🙂", "other"), documents.map { it.json })
                    assertEquals(listOf("Export", "Export"), documents.map { it.archiveDirectory })
                }
            }
        }
    }

    @Test
    fun rejectsUnsafeAndAmbiguousPaths() {
        for (path in listOf("../Journal.json", "/Journal.json", "C:/Journal.json", "A/../Journal.json", "A\\Journal.json")) {
            assertFails { DayOneArchiveReader.readJsonDocuments(dayOneZip(path to byteArrayOf(1))) }
        }
        assertFails {
            DayOneArchiveReader.readJsonDocuments(dayOneZip("A.json" to byteArrayOf(1), "a.JSON" to byteArrayOf(2)))
        }
    }

    @Test
    fun rejectsCrcMismatchTruncationEncryptionAndInflationBeyondDeclaredSize() {
        val original = dayOneZip("A.json" to "a substantive payload".encodeToByteArray())
        val directory = original.centralOffset()
        for ((relativeOffset, value) in listOf(16 to 0L, 24 to 1L, 24 to 16L * 1024 * 1024 + 1, 20 to 0xfffffff0L)) {
            val invalid = original.copyOf()
            invalid.putUInt(directory + relativeOffset, value)
            assertFails { DayOneArchiveReader.readJsonDocuments(invalid) }
        }
        val encrypted = original.copyOf()
        encrypted[directory + 8] = (encrypted[directory + 8].toInt() or 1).toByte()
        assertFails { DayOneArchiveReader.readJsonDocuments(encrypted) }
        assertFails { DayOneArchiveReader.readJsonDocuments(original.copyOf(original.size - 4)) }
        assertFails { DayOneArchiveReader.readJsonDocuments(byteArrayOf()) }
    }

    @Test
    fun rejectsMissingZip64ValuesAndLocalDirectoryNameDisagreement() {
        val archive = dayOneZip("A.json" to "value".encodeToByteArray(), sizes64 = true)
        val directory = archive.centralOffset()
        val invalidExtra = archive.copyOf()
        invalidExtra[directory + 46 + "A.json".length] = 2 // replace the ZIP64 extra tag
        assertFails { DayOneArchiveReader.readJsonDocuments(invalidExtra) }
        val differentName = archive.copyOf()
        differentName[30] = 'B'.code.toByte()
        assertFails { DayOneArchiveReader.readJsonDocuments(differentName) }
    }
}

/** Independent synthetic fixture writer; never includes private journal content. */
internal fun dayOneZip(
    vararg files: Pair<String, ByteArray>,
    stored: Boolean = false,
    sizes64: Boolean = false,
    directory64: Boolean = false,
): ByteArray {
    val output = ByteArrayOutputStream()
    val central = ByteArrayOutputStream()
    fun ByteArrayOutputStream.number(value: Long, width: Int) {
        repeat(width) { write((value ushr (it * 8)).toInt() and 255) }
    }
    files.forEach { (path, bytes) ->
        val name = path.encodeToByteArray()
        val compressed = if (stored) bytes else ByteArrayOutputStream().also { target ->
            val deflater = Deflater(6, true)
            try { DeflaterOutputStream(target, deflater).use { it.write(bytes) } } finally { deflater.end() }
        }.toByteArray()
        val crc = CRC32().also { it.update(bytes) }.value
        val extra = ByteArrayOutputStream().apply {
            if (sizes64) {
                number(1, 2); number(16, 2)
                number(bytes.size.toLong(), 8); number(compressed.size.toLong(), 8)
            }
        }.toByteArray()
        val offset = output.size().toLong()
        val method = if (stored) 0L else 8L
        output.apply {
            number(0x04034b50, 4); number(45, 2); number(0x808, 2); number(method, 2)
            number(0, 4); number(0, 4)
            number(if (sizes64) 0xffffffff else 0, 4); number(if (sizes64) 0xffffffff else 0, 4)
            number(name.size.toLong(), 2); number(extra.size.toLong(), 2)
            write(name); write(extra); write(compressed)
            number(0x08074b50, 4); number(crc, 4)
            number(compressed.size.toLong(), if (sizes64) 8 else 4)
            number(bytes.size.toLong(), if (sizes64) 8 else 4)
        }
        central.apply {
            number(0x02014b50, 4); number(45, 2); number(45, 2)
            number(0x808, 2); number(method, 2); number(0, 4); number(crc, 4)
            number(if (sizes64) 0xffffffff else compressed.size.toLong(), 4)
            number(if (sizes64) 0xffffffff else bytes.size.toLong(), 4)
            number(name.size.toLong(), 2); number(extra.size.toLong(), 2)
            number(0, 2); number(0, 2); number(0, 2); number(0, 4); number(offset, 4)
            write(name); write(extra)
        }
    }
    val directoryOffset = output.size().toLong()
    output.write(central.toByteArray())
    if (directory64) {
        val end64 = output.size().toLong()
        output.apply {
            number(0x06064b50, 4); number(44, 8); number(45, 2); number(45, 2)
            number(0, 4); number(0, 4); number(files.size.toLong(), 8); number(files.size.toLong(), 8)
            number(central.size().toLong(), 8); number(directoryOffset, 8)
            number(0x07064b50, 4); number(0, 4); number(end64, 8); number(1, 4)
        }
    }
    output.apply {
        number(0x06054b50, 4); number(0, 2); number(0, 2)
        repeat(2) { number(if (directory64) 0xffff else files.size.toLong(), 2) }
        number(if (directory64) 0xffffffff else central.size().toLong(), 4)
        number(if (directory64) 0xffffffff else directoryOffset, 4)
        val comment = "comment PK\u0005\u0006 not an end record".encodeToByteArray()
        number(comment.size.toLong(), 2); write(comment)
    }
    return output.toByteArray()
}

private fun ByteArray.centralOffset(): Int = indices.first { index ->
    index + 3 < size && this[index] == 0x50.toByte() && this[index + 1] == 0x4b.toByte() &&
        this[index + 2] == 1.toByte() && this[index + 3] == 2.toByte()
}

private fun ByteArray.putUInt(offset: Int, value: Long) {
    repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() }
}
