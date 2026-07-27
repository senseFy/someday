package saien.someday.sync

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Strict framing check applied before kotlinx.serialization can collapse duplicate names. */
internal object StrictJsonV2 {
    fun requireValidObjectKeys(input: String, maxUtf8Bytes: Int) {
        require(maxUtf8Bytes > 0)
        require(input.encodeToByteArray().size <= maxUtf8Bytes) { "JSON response exceeds the negotiated V2 body limit." }
        Parser(input).parse()
    }

    private class Parser(private val input: String) {
        private var index = 0

        fun parse() {
            skipWhitespace()
            value(0)
            skipWhitespace()
            require(index == input.length) { "Malformed JSON framing." }
        }

        private fun value(depth: Int) {
            require(depth <= MAX_DEPTH) { "JSON framing exceeds the nesting limit." }
            skipWhitespace()
            require(index < input.length) { "Unexpected end of JSON framing." }
            when (input[index]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringToken()
                else -> primitive()
            }
        }

        private fun objectValue(depth: Int) {
            index++
            skipWhitespace()
            if (consume('}')) return
            val names = mutableSetOf<String>()
            while (true) {
                skipWhitespace()
                val encodedName = stringToken()
                val name = JSON.decodeFromString<String>(encodedName)
                require(names.add(name)) { "JSON framing contains a duplicate object key." }
                skipWhitespace()
                require(consume(':')) { "Malformed JSON object framing." }
                value(depth)
                skipWhitespace()
                if (consume('}')) return
                require(consume(',')) { "Malformed JSON object framing." }
            }
        }

        private fun arrayValue(depth: Int) {
            index++
            skipWhitespace()
            if (consume(']')) return
            while (true) {
                value(depth)
                skipWhitespace()
                if (consume(']')) return
                require(consume(',')) { "Malformed JSON array framing." }
            }
        }

        private fun stringToken(): String {
            val start = index
            require(consume('"')) { "Malformed JSON string framing." }
            while (index < input.length) {
                when (val character = input[index++]) {
                    '"' -> return input.substring(start, index)
                    '\\' -> {
                        require(index < input.length) { "Malformed JSON escape." }
                        if (input[index++] == 'u') {
                            require(index + 4 <= input.length) { "Malformed JSON unicode escape." }
                            repeat(4) { require(input[index++].digitToIntOrNull(16) != null) }
                        }
                    }
                    else -> require(character.code >= 0x20) { "Unescaped JSON control character." }
                }
            }
            error("Unterminated JSON string.")
        }

        private fun primitive() {
            val start = index
            while (index < input.length && input[index] !in DELIMITERS) index++
            require(index > start) { "Malformed JSON primitive." }
        }

        private fun consume(character: Char): Boolean =
            if (index < input.length && input[index] == character) {
                index++
                true
            } else {
                false
            }

        private fun skipWhitespace() {
            while (index < input.length && input[index] in WHITESPACE) index++
        }
    }

    private const val MAX_DEPTH = 64
    private val WHITESPACE = charArrayOf(' ', '\t', '\r', '\n')
    private val DELIMITERS = charArrayOf(',', '}', ']', ' ', '\t', '\r', '\n')
    private val JSON = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }
}
