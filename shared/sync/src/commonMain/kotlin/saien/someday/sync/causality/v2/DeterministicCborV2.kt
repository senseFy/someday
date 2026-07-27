package saien.someday.sync.causality.v2

import okio.Buffer

internal sealed interface CborValueV2 {
    data class Integer(val value: Long) : CborValueV2

    data class ByteString(val value: ByteArray) : CborValueV2 {
        override fun equals(other: Any?): kotlin.Boolean =
            other is ByteString && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()
    }

    data class TextString(val value: String) : CborValueV2

    data class Array(val values: List<CborValueV2>) : CborValueV2

    data class Map(val entries: List<Pair<CborValueV2, CborValueV2>>) : CborValueV2

    /** Protocol numeric fields use float64 exclusively; shorter CBOR floats are rejected. */
    data class Float64(val value: Double) : CborValueV2

    data class Boolean(val value: kotlin.Boolean) : CborValueV2

    data object Null : CborValueV2
}

internal object DeterministicCborV2 {
    fun encode(value: CborValueV2): ByteArray {
        val buffer = Buffer()
        writeValue(buffer, value)
        return buffer.readByteArray()
    }

    /**
     * Decodes the protocol-owned deterministic subset and verifies that the
     * input itself is canonical. This is deliberately not a permissive CBOR
     * reader: tags, indefinite lengths, duplicate/unsorted keys, non-minimal
     * integers, float16/32, trailing bytes, and unsupported simple values fail.
     */
    fun decode(bytes: ByteArray): CborValueV2 {
        require(bytes.isNotEmpty()) { "Deterministic CBOR input must not be empty." }
        val reader = DeterministicCborReaderV2(bytes)
        val value = reader.readValue(depth = 0)
        require(reader.isAtEnd()) { "Deterministic CBOR input contains trailing bytes." }
        require(encode(value).contentEquals(bytes)) { "CBOR input is not in deterministic form." }
        return value
    }

    private fun writeValue(
        buffer: Buffer,
        value: CborValueV2,
    ) {
        when (value) {
            is CborValueV2.Integer -> writeInteger(buffer, value.value)
            is CborValueV2.ByteString -> {
                writeTypeAndArgument(buffer, majorType = 2, argument = value.value.size.toLong())
                buffer.write(value.value)
            }
            is CborValueV2.TextString -> {
                val bytes = value.value.encodeToByteArray()
                writeTypeAndArgument(buffer, majorType = 3, argument = bytes.size.toLong())
                buffer.write(bytes)
            }
            is CborValueV2.Array -> {
                writeTypeAndArgument(buffer, majorType = 4, argument = value.values.size.toLong())
                value.values.forEach { writeValue(buffer, it) }
            }
            is CborValueV2.Map -> writeMap(buffer, value.entries)
            is CborValueV2.Float64 -> {
                require(value.value.isFinite()) { "Protocol CBOR floats must be finite." }
                buffer.writeByte(0xfb)
                buffer.writeLong(normalizeNegativeZero(value.value).toBits())
            }
            is CborValueV2.Boolean -> buffer.writeByte(if (value.value) 0xf5 else 0xf4)
            CborValueV2.Null -> buffer.writeByte(0xf6)
        }
    }

    private fun writeInteger(
        buffer: Buffer,
        value: Long,
    ) {
        if (value >= 0) {
            writeTypeAndArgument(buffer, majorType = 0, argument = value)
        } else {
            writeTypeAndArgument(buffer, majorType = 1, argument = -1L - value)
        }
    }

    private fun writeMap(
        buffer: Buffer,
        entries: List<Pair<CborValueV2, CborValueV2>>,
    ) {
        val encodedEntries = entries.map { (key, value) ->
            EncodedMapEntryV2(
                encodedKey = encode(key),
                value = value,
            )
        }.sortedWith { left, right -> compareUnsignedBytes(left.encodedKey, right.encodedKey) }

        encodedEntries.zipWithNext().forEach { (left, right) ->
            require(!left.encodedKey.contentEquals(right.encodedKey)) {
                "Deterministic CBOR map contains a duplicate encoded key."
            }
        }

        writeTypeAndArgument(buffer, majorType = 5, argument = encodedEntries.size.toLong())
        encodedEntries.forEach { entry ->
            buffer.write(entry.encodedKey)
            writeValue(buffer, entry.value)
        }
    }

    private fun writeTypeAndArgument(
        buffer: Buffer,
        majorType: Int,
        argument: Long,
    ) {
        require(majorType in 0..7) { "CBOR major type is out of range." }
        require(argument >= 0) { "CBOR argument must be non-negative." }
        val prefix = majorType shl 5
        when {
            argument < 24 -> buffer.writeByte(prefix or argument.toInt())
            argument <= 0xff -> {
                buffer.writeByte(prefix or 24)
                buffer.writeByte(argument.toInt())
            }
            argument <= 0xffff -> {
                buffer.writeByte(prefix or 25)
                buffer.writeShort(argument.toInt())
            }
            argument <= 0xffff_ffffL -> {
                buffer.writeByte(prefix or 26)
                buffer.writeInt(argument.toInt())
            }
            else -> {
                buffer.writeByte(prefix or 27)
                buffer.writeLong(argument)
            }
        }
    }

    private fun compareUnsignedBytes(
        left: ByteArray,
        right: ByteArray,
    ): Int {
        val lengthComparison = left.size.compareTo(right.size)
        if (lengthComparison != 0) return lengthComparison
        val commonSize = minOf(left.size, right.size)
        for (index in 0 until commonSize) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }
}

private class DeterministicCborReaderV2(
    private val bytes: ByteArray,
) {
    private var offset: Int = 0
    private var valueCount: Int = 0

    fun isAtEnd(): Boolean = offset == bytes.size

    fun readValue(depth: Int): CborValueV2 {
        require(depth <= MAX_CBOR_DEPTH_V2) { "Deterministic CBOR nesting is too deep." }
        valueCount += 1
        require(valueCount <= MAX_CBOR_VALUES_V2) { "Deterministic CBOR contains too many values." }
        val initial = readUnsignedByte()
        val major = initial ushr 5
        val additional = initial and 0x1f
        return when (major) {
            0 -> CborValueV2.Integer(readArgument(additional))
            1 -> {
                val argument = readArgument(additional)
                CborValueV2.Integer(-1L - argument)
            }
            2 -> {
                val length = readLength(additional)
                CborValueV2.ByteString(readBytes(length))
            }
            3 -> {
                val length = readLength(additional)
                val text = readBytes(length).decodeToString(throwOnInvalidSequence = true)
                CborValueV2.TextString(text)
            }
            4 -> {
                val size = readLength(additional)
                requireCollectionFitsValueBudget(size, valuesPerEntry = 1)
                CborValueV2.Array(List(size) { readValue(depth + 1) })
            }
            5 -> readMap(additional, depth)
            6 -> throw IllegalArgumentException("CBOR tags are outside the V2 protocol subset.")
            7 -> readSimple(additional)
            else -> error("Unreachable CBOR major type.")
        }
    }

    private fun readMap(additional: Int, depth: Int): CborValueV2.Map {
        val size = readLength(additional)
        requireCollectionFitsValueBudget(size, valuesPerEntry = 2)
        val entries = ArrayList<Pair<CborValueV2, CborValueV2>>(size)
        var previousKeyBytes: ByteArray? = null
        repeat(size) {
            val keyStart = offset
            val key = readValue(depth + 1)
            require(key is CborValueV2.TextString) { "V2 protocol maps require text keys." }
            val keyBytes = bytes.copyOfRange(keyStart, offset)
            previousKeyBytes?.let { previous ->
                require(compareUnsignedBytesV2(previous, keyBytes) < 0) {
                    "Deterministic CBOR map keys are duplicated or out of order."
                }
            }
            previousKeyBytes = keyBytes
            entries += key to readValue(depth + 1)
        }
        return CborValueV2.Map(entries)
    }

    /** Rejects hostile declared sizes before List/ArrayList allocate their backing storage. */
    private fun requireCollectionFitsValueBudget(size: Int, valuesPerEntry: Int) {
        require(size <= (MAX_CBOR_VALUES_V2 - valueCount) / valuesPerEntry) {
            "Deterministic CBOR contains too many values."
        }
    }

    private fun readSimple(additional: Int): CborValueV2 = when (additional) {
        20 -> CborValueV2.Boolean(false)
        21 -> CborValueV2.Boolean(true)
        22 -> CborValueV2.Null
        27 -> {
            val bits = readLongBits()
            val value = Double.fromBits(bits)
            require(value.isFinite()) { "Protocol CBOR floats must be finite." }
            require(bits != Long.MIN_VALUE) { "Negative floating-point zero is not canonical." }
            CborValueV2.Float64(value)
        }
        31 -> throw IllegalArgumentException("Indefinite CBOR values are outside the V2 protocol subset.")
        else -> throw IllegalArgumentException("Unsupported CBOR simple or float value.")
    }

    private fun readLength(additional: Int): Int {
        val value = readArgument(additional)
        require(value <= Int.MAX_VALUE.toLong()) { "CBOR collection is too large." }
        require(value <= bytes.size.toLong()) { "CBOR collection length exceeds its input." }
        return value.toInt()
    }

    private fun readArgument(additional: Int): Long = when {
        additional < 24 -> additional.toLong()
        additional == 24 -> readUnsignedByte().also {
            require(it >= 24) { "CBOR integer/length is not minimally encoded." }
        }.toLong()
        additional == 25 -> readUnsignedShort().also {
            require(it > 0xff) { "CBOR integer/length is not minimally encoded." }
        }.toLong()
        additional == 26 -> readUnsignedInt().also {
            require(it > 0xffffL) { "CBOR integer/length is not minimally encoded." }
        }
        additional == 27 -> readNonNegativeLong().also {
            require(it > 0xffff_ffffL) { "CBOR integer/length is not minimally encoded." }
        }
        else -> throw IllegalArgumentException("Indefinite or reserved CBOR argument is unsupported.")
    }

    private fun readUnsignedByte(): Int {
        require(offset < bytes.size) { "Unexpected end of CBOR input." }
        return bytes[offset++].toInt() and 0xff
    }

    private fun readUnsignedShort(): Int =
        (readUnsignedByte() shl 8) or readUnsignedByte()

    private fun readUnsignedInt(): Long =
        (readUnsignedByte().toLong() shl 24) or
            (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 8) or
            readUnsignedByte().toLong()

    private fun readNonNegativeLong(): Long {
        var value = 0L
        repeat(8) { index ->
            val next = readUnsignedByte().toLong()
            if (index == 0) {
                require(next < 0x80) { "CBOR unsigned value exceeds signed protocol range." }
            }
            value = (value shl 8) or next
        }
        return value
    }

    private fun readLongBits(): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or readUnsignedByte().toLong() }
        return value
    }

    private fun readBytes(length: Int): ByteArray {
        require(length >= 0 && offset <= bytes.size - length) { "Unexpected end of CBOR input." }
        val result = bytes.copyOfRange(offset, offset + length)
        offset += length
        return result
    }

    private companion object {
        const val MAX_CBOR_DEPTH_V2: Int = 64
        const val MAX_CBOR_VALUES_V2: Int = 200_000
    }
}

private fun compareUnsignedBytesV2(left: ByteArray, right: ByteArray): Int {
    val lengthComparison = left.size.compareTo(right.size)
    if (lengthComparison != 0) return lengthComparison
    val commonSize = minOf(left.size, right.size)
    for (index in 0 until commonSize) {
        val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}

internal fun normalizeNegativeZero(value: Double): Double =
    if (value == 0.0) 0.0 else value

private data class EncodedMapEntryV2(
    val encodedKey: ByteArray,
    val value: CborValueV2,
)

internal fun cborText(value: String): CborValueV2 = CborValueV2.TextString(value)

internal fun cborNullableText(value: String?): CborValueV2 =
    value?.let(::cborText) ?: CborValueV2.Null

internal fun cborInt(value: Long): CborValueV2 = CborValueV2.Integer(value)

internal fun cborBoolean(value: Boolean): CborValueV2 = CborValueV2.Boolean(value)

internal fun cborFloat64(value: Double): CborValueV2 =
    CborValueV2.Float64(normalizeNegativeZero(value))

internal fun cborArray(values: Iterable<CborValueV2>): CborValueV2 =
    CborValueV2.Array(values.toList())

internal fun cborMap(vararg entries: Pair<String, CborValueV2>): CborValueV2.Map =
    CborValueV2.Map(entries.map { (key, value) -> cborText(key) to value })
