package saien.someday.data.serialization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Rejects duplicate object names and malformed framing before JSON decoding can normalize them. */
object StrictJsonFraming {
    fun requireValidObjectKeys(input: String, maxUtf8Bytes: Int) {
        require(maxUtf8Bytes > 0)
        require(input.encodeToByteArray().size <= maxUtf8Bytes) { "JSON exceeds its configured body limit." }
        Parser(input).parse()
    }

    /** Structural v1 check shared by recovery publication and cryptographic restore. */
    fun isStrictWorkspaceRecoveryMetadata(input: String, maxUtf8Bytes: Int): Boolean {
        if (runCatching { requireValidObjectKeys(input, maxUtf8Bytes) }.isFailure) return false
        val root = runCatching { JSON.parseToJsonElement(input).jsonObject }.getOrNull() ?: return false
        if (root.keys != RECOVERY_METADATA_FIELDS ||
            !root.hasStringFields(RECOVERY_METADATA_STRING_FIELDS) ||
            !root.hasIntegerFields(RECOVERY_METADATA_INTEGER_FIELDS)
        ) {
            return false
        }
        val verifier = root["verifier"] as? JsonObject ?: return false
        val recovery = root["recovery"] as? JsonObject ?: return false
        return verifier.keys == RECOVERY_VERIFIER_FIELDS &&
            verifier.hasStringFields(RECOVERY_VERIFIER_FIELDS) &&
            recovery.keys == RECOVERY_WRAPPER_FIELDS &&
            recovery.hasStringFields(RECOVERY_WRAPPER_STRING_FIELDS) &&
            recovery.hasIntegerFields(RECOVERY_WRAPPER_INTEGER_FIELDS)
    }

    private fun JsonObject.hasStringFields(fields: Set<String>): Boolean =
        fields.all { field ->
            val value = this[field] as? JsonPrimitive
            value != null && value.isString
        }

    private fun JsonObject.hasIntegerFields(fields: Set<String>): Boolean =
        fields.all { field ->
            val value = this[field] as? JsonPrimitive
            value != null && !value.isString && value.intOrNull != null
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
    private val RECOVERY_METADATA_FIELDS = setOf(
        "format",
        "version",
        "workspaceId",
        "createdAt",
        "keyAlgorithm",
        "recoveryKdf",
        "keyLengthBytes",
        "keyFingerprint",
        "verifier",
        "recovery",
    )
    private val RECOVERY_METADATA_STRING_FIELDS = setOf(
        "format",
        "workspaceId",
        "createdAt",
        "keyAlgorithm",
        "recoveryKdf",
        "keyFingerprint",
    )
    private val RECOVERY_METADATA_INTEGER_FIELDS = setOf("version", "keyLengthBytes")
    private val RECOVERY_VERIFIER_FIELDS = setOf("nonce", "ciphertext")
    private val RECOVERY_WRAPPER_FIELDS = setOf(
        "salt",
        "nonce",
        "ciphertext",
        "opsLimit",
        "memLimit",
        "algorithm",
    )
    private val RECOVERY_WRAPPER_STRING_FIELDS = setOf("salt", "nonce", "ciphertext", "opsLimit")
    private val RECOVERY_WRAPPER_INTEGER_FIELDS = setOf("memLimit", "algorithm")
    private val JSON = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }
}
