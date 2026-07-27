@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package saien.someday.sync.causality.v2

import saien.someday.data.crypto.AeadCiphertext
import saien.someday.data.crypto.CryptoResult
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.crypto.WorkspaceSubkey
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString

const val ENCRYPTED_WORKSPACE_OBJECT_SCHEMA_V2: Int = 1
const val WORKSPACE_CIPHER_SUITE_V2: String = "xchacha20-poly1305-ietf"
const val MAX_ENTITY_CIPHERTEXT_BYTES_V2: Int = MAX_WORKSPACE_ENTITY_PLAINTEXT_BYTES_V2 + 16
const val MAX_CONTROL_CIPHERTEXT_BYTES_V2: Int = 16 * 1_024 * 1_024

val CONTROL_OBJECT_TYPES_SYSTEM_V2: Set<String> = setOf(
    "sync_epoch_pointer_v2",
    "sync_checkpoint_manifest_v2",
    "sync_checkpoint_chunk_v2",
    "webdav_writer_manifest_v2",
    "webdav_log_segment_v2",
)

@Serializable
data class EncryptedWorkspaceObjectV2(
    val outerSchemaVersion: Int = ENCRYPTED_WORKSPACE_OBJECT_SCHEMA_V2,
    val contractId: String = SYNC_V2_CONTRACT_ID,
    val schemaSetVersion: String = SYNC_V2_SCHEMA_SET_VERSION,
    val keySetVersion: String = SYNC_KEY_SET_VERSION_V2,
    val syncEpochId: String,
    val objectType: String,
    val objectId: String,
    val objectDigest: String,
    val mutationId: String?,
    val writerDeviceId: String,
    val cipherSuite: String = WORKSPACE_CIPHER_SUITE_V2,
    val nonceBase64: String,
    val ciphertextBase64: String,
    val ciphertextDigest: String,
)

enum class EncryptedWorkspaceObjectErrorCodeV2(val wireValue: String) {
    MALFORMED_JSON("malformed_json"),
    UNKNOWN_OUTER_FIELD("unknown_outer_field"),
    DUPLICATE_JSON_KEY("duplicate_json_key"),
    INCOMPATIBLE_CONTRACT("incompatible_contract"),
    UNKNOWN_OBJECT_TYPE("unknown_object_type"),
    INVALID_OUTER_IDENTITY("invalid_outer_identity"),
    INVALID_BASE64("invalid_base64"),
    INVALID_NONCE_LENGTH("invalid_nonce_length"),
    CIPHERTEXT_TOO_LARGE("ciphertext_too_large"),
    CIPHERTEXT_DIGEST_MISMATCH("ciphertext_digest_mismatch"),
    AUTHENTICATION_FAILED("authentication_failed"),
}

data class EncryptedWorkspaceObjectErrorV2(
    val code: EncryptedWorkspaceObjectErrorCodeV2,
    val safeMessage: String,
)

sealed interface EncryptedWorkspaceObjectDecodeResultV2 {
    data class Decoded(
        val outer: EncryptedWorkspaceObjectV2,
        val plaintext: ByteArray,
    ) : EncryptedWorkspaceObjectDecodeResultV2 {
        override fun toString(): String =
            "Decoded(objectType=${outer.objectType}, objectId=${outer.objectId}, plaintextBytes=${plaintext.size})"
    }

    data class Rejected(val error: EncryptedWorkspaceObjectErrorV2) : EncryptedWorkspaceObjectDecodeResultV2
}

class WorkspaceObjectCipherV2(
    private val workspaceKey: WorkspaceMasterKey,
    private val materializer: CanonicalWorkspaceCausalityMaterializerV2,
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
) {
    fun encryptEntity(
        version: WorkspaceEntityVersionV2,
        mutationId: String,
        writerDeviceId: String,
        plaintext: ByteArray,
    ): EncryptedWorkspaceObjectV2 {
        require(plaintext.size <= MAX_WORKSPACE_ENTITY_PLAINTEXT_BYTES_V2)
        require(UUID_V4_PATTERN_SYSTEM_V2.matches(mutationId))
        return encrypt(
            syncEpochId = version.syncEpochId,
            objectType = WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2,
            objectId = version.versionId,
            objectDigest = version.objectDigest,
            mutationId = mutationId,
            writerDeviceId = writerDeviceId,
            plaintext = plaintext,
        )
    }

    fun encryptControl(
        syncEpochId: String,
        objectType: String,
        objectId: String,
        writerDeviceId: String,
        plaintext: ByteArray,
    ): EncryptedWorkspaceObjectV2 {
        require(objectType in CONTROL_OBJECT_TYPES_SYSTEM_V2)
        require(plaintext.size <= MAX_CONTROL_CIPHERTEXT_BYTES_V2 - 16)
        return encrypt(
            syncEpochId = syncEpochId,
            objectType = objectType,
            objectId = objectId,
            objectDigest = controlDigest(objectType, objectId, plaintext),
            mutationId = null,
            writerDeviceId = writerDeviceId,
            plaintext = plaintext,
        )
    }

    fun reencryptReplica(
        original: EncryptedWorkspaceObjectV2,
        healthyWriterDeviceId: String,
        plaintext: ByteArray,
    ): EncryptedWorkspaceObjectV2 = encrypt(
        syncEpochId = original.syncEpochId,
        objectType = original.objectType,
        objectId = original.objectId,
        objectDigest = original.objectDigest,
        mutationId = original.mutationId,
        writerDeviceId = healthyWriterDeviceId,
        plaintext = plaintext,
    )

    fun controlDigest(objectType: String, objectId: String, payload: ByteArray): String {
        val canonicalPayload = DeterministicCborV2.decode(payload)
        val bytes = DeterministicCborV2.encode(
            cborMap(
                "domain" to cborText("someday-system-v2-control-digest-v2"),
                "objectType" to cborText(objectType),
                "objectId" to cborText(objectId),
                "payload" to canonicalPayload,
            ),
        )
        return "cd2:hmac-sha256:${materializer.objectKeyHmac(bytes).toByteString().hex()}"
    }

    fun decrypt(outer: EncryptedWorkspaceObjectV2): EncryptedWorkspaceObjectDecodeResultV2 {
        validateOuter(outer)?.let { return EncryptedWorkspaceObjectDecodeResultV2.Rejected(it) }
        val nonce = decodeCanonicalBase64(outer.nonceBase64)
            ?: return rejected(EncryptedWorkspaceObjectErrorCodeV2.INVALID_BASE64, "Outer nonce is not canonical Base64.")
        val ciphertext = decodeCanonicalBase64(outer.ciphertextBase64)
            ?: return rejected(EncryptedWorkspaceObjectErrorCodeV2.INVALID_BASE64, "Outer ciphertext is not canonical Base64.")
        if (nonce.size != XCHACHA_NONCE_BYTES_V2) {
            return rejected(EncryptedWorkspaceObjectErrorCodeV2.INVALID_NONCE_LENGTH, "Outer nonce has the wrong size.")
        }
        val maxCiphertext = if (outer.objectType == WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2) {
            MAX_ENTITY_CIPHERTEXT_BYTES_V2
        } else {
            MAX_CONTROL_CIPHERTEXT_BYTES_V2
        }
        if (ciphertext.size > maxCiphertext) {
            return rejected(EncryptedWorkspaceObjectErrorCodeV2.CIPHERTEXT_TOO_LARGE, "Outer ciphertext exceeds its object limit.")
        }
        if (outer.ciphertextDigest != ciphertextDigest(nonce, ciphertext)) {
            return rejected(EncryptedWorkspaceObjectErrorCodeV2.CIPHERTEXT_DIGEST_MISMATCH, "Ciphertext transport checksum does not match.")
        }
        val plaintext = when (val result = crypto.decryptAead(
            key = crypto.deriveSubkey(workspaceKey, WorkspaceSubkey.OBJECTS),
            associatedData = associatedDataBytes(outer),
            ciphertext = AeadCiphertext(nonce, ciphertext),
        )) {
            is CryptoResult.Success -> result.value
            CryptoResult.AuthenticationFailed,
            CryptoResult.InvalidCiphertext,
            -> return rejected(EncryptedWorkspaceObjectErrorCodeV2.AUTHENTICATION_FAILED, "Workspace object authentication failed.")
        }
        return EncryptedWorkspaceObjectDecodeResultV2.Decoded(outer, plaintext)
    }

    fun encodeJson(outer: EncryptedWorkspaceObjectV2): String {
        require(validateOuter(outer) == null)
        return JSON_V2.encodeToString(outer)
    }

    fun decodeJson(encoded: String): Result<EncryptedWorkspaceObjectV2> {
        if (encoded.utf8SizeV2() > MAX_OUTER_JSON_BYTES_V2) {
            return Result.failure(IllegalArgumentException("Encrypted V2 outer JSON exceeds the framing limit."))
        }
        if (hasDuplicateTopLevelJsonKeysV2(encoded)) {
            return Result.failure(IllegalArgumentException("Encrypted V2 outer JSON contains a duplicate key."))
        }
        val value = try {
            JSON_V2.decodeFromString<EncryptedWorkspaceObjectV2>(encoded)
        } catch (error: SerializationException) {
            return Result.failure(error)
        } catch (error: IllegalArgumentException) {
            return Result.failure(error)
        }
        return validateOuter(value)?.let { Result.failure(IllegalArgumentException(it.code.wireValue)) }
            ?: Result.success(value)
    }

    private fun encrypt(
        syncEpochId: String,
        objectType: String,
        objectId: String,
        objectDigest: String,
        mutationId: String?,
        writerDeviceId: String,
        plaintext: ByteArray,
    ): EncryptedWorkspaceObjectV2 {
        val metadata = EncryptedWorkspaceObjectV2(
            syncEpochId = syncEpochId,
            objectType = objectType,
            objectId = objectId,
            objectDigest = objectDigest,
            mutationId = mutationId,
            writerDeviceId = writerDeviceId,
            nonceBase64 = "pending",
            ciphertextBase64 = "pending",
            ciphertextDigest = "ct2:sha256:${"0".repeat(64)}",
        )
        validateOuterIdentity(metadata)?.let { throw IllegalArgumentException(it.safeMessage) }
        val encrypted = crypto.encryptAead(
            key = crypto.deriveSubkey(workspaceKey, WorkspaceSubkey.OBJECTS),
            associatedData = associatedDataBytes(metadata),
            plaintext = plaintext,
        )
        return metadata.copy(
            nonceBase64 = Base64.encode(encrypted.nonce),
            ciphertextBase64 = Base64.encode(encrypted.ciphertext),
            ciphertextDigest = ciphertextDigest(encrypted.nonce, encrypted.ciphertext),
        )
    }

    /** Canonical AAD is part of the frozen cross-platform wire contract. */
    internal fun associatedDataBytes(value: EncryptedWorkspaceObjectV2): ByteArray = DeterministicCborV2.encode(
        cborMap(
            "outerSchemaVersion" to cborInt(value.outerSchemaVersion.toLong()),
            "contractId" to cborText(value.contractId),
            "schemaSetVersion" to cborText(value.schemaSetVersion),
            "keySetVersion" to cborText(value.keySetVersion),
            "syncEpochId" to cborText(value.syncEpochId),
            "objectType" to cborText(value.objectType),
            "objectId" to cborText(value.objectId),
            "objectDigest" to cborText(value.objectDigest),
            "mutationId" to cborNullableText(value.mutationId),
            "writerDeviceId" to cborText(value.writerDeviceId),
            "cipherSuite" to cborText(value.cipherSuite),
        ),
    )

    private fun ciphertextDigest(nonce: ByteArray, ciphertext: ByteArray): String {
        val bytes = DeterministicCborV2.encode(
            cborMap(
                "domain" to cborText("someday-system-v2-ciphertext-digest-v2"),
                "nonce" to CborValueV2.ByteString(nonce),
                "ciphertext" to CborValueV2.ByteString(ciphertext),
            ),
        )
        return "ct2:sha256:${bytes.toByteString().sha256().hex()}"
    }

    private fun validateOuter(value: EncryptedWorkspaceObjectV2): EncryptedWorkspaceObjectErrorV2? {
        validateOuterIdentity(value)?.let { return it }
        if (!CIPHERTEXT_DIGEST_PATTERN_SYSTEM_V2.matches(value.ciphertextDigest)) {
            return EncryptedWorkspaceObjectErrorV2(
                EncryptedWorkspaceObjectErrorCodeV2.INVALID_OUTER_IDENTITY,
                "Outer ciphertext digest has an unsupported form.",
            )
        }
        return null
    }

    private fun validateOuterIdentity(value: EncryptedWorkspaceObjectV2): EncryptedWorkspaceObjectErrorV2? {
        if (value.outerSchemaVersion != ENCRYPTED_WORKSPACE_OBJECT_SCHEMA_V2 ||
            value.contractId != SYNC_V2_CONTRACT_ID ||
            value.schemaSetVersion != SYNC_V2_SCHEMA_SET_VERSION ||
            value.keySetVersion != SYNC_KEY_SET_VERSION_V2 ||
            value.cipherSuite != WORKSPACE_CIPHER_SUITE_V2
        ) {
            return EncryptedWorkspaceObjectErrorV2(
                EncryptedWorkspaceObjectErrorCodeV2.INCOMPATIBLE_CONTRACT,
                "Outer object belongs to an unsupported V2 contract, key set, or cipher suite.",
            )
        }
        if (value.objectType != WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2 && value.objectType !in CONTROL_OBJECT_TYPES_SYSTEM_V2) {
            return EncryptedWorkspaceObjectErrorV2(
                EncryptedWorkspaceObjectErrorCodeV2.UNKNOWN_OBJECT_TYPE,
                "Outer object type is not allowed by the V2 contract.",
            )
        }
        val isEntity = value.objectType == WORKSPACE_ENTITY_VERSION_OBJECT_TYPE_V2
        val digestValid = if (isEntity) OBJECT_DIGEST_PATTERN_SYSTEM_V2.matches(value.objectDigest)
        else CONTROL_DIGEST_PATTERN_SYSTEM_V2.matches(value.objectDigest)
        val mutationValid = if (isEntity) value.mutationId?.let(UUID_V4_PATTERN_SYSTEM_V2::matches) == true
        else value.mutationId == null
        if (!UUID_V4_PATTERN_SYSTEM_V2.matches(value.syncEpochId) ||
            !value.objectId.isWholeProductProtocolIdentifierV2() ||
            !UUID_V4_PATTERN_SYSTEM_V2.matches(value.writerDeviceId) ||
            !digestValid || !mutationValid
        ) {
            return EncryptedWorkspaceObjectErrorV2(
                EncryptedWorkspaceObjectErrorCodeV2.INVALID_OUTER_IDENTITY,
                "Outer identity fields violate the V2 protocol grammar.",
            )
        }
        return null
    }

    private fun decodeCanonicalBase64(value: String): ByteArray? {
        if (value.isEmpty() || value.any(Char::isWhitespace)) return null
        return runCatching { Base64.decode(value) }.getOrNull()?.takeIf { Base64.encode(it) == value }
    }

    private fun rejected(
        code: EncryptedWorkspaceObjectErrorCodeV2,
        message: String,
    ): EncryptedWorkspaceObjectDecodeResultV2.Rejected =
        EncryptedWorkspaceObjectDecodeResultV2.Rejected(EncryptedWorkspaceObjectErrorV2(code, message))

    private companion object {
        const val XCHACHA_NONCE_BYTES_V2: Int = 24
        const val MAX_OUTER_JSON_BYTES_V2: Int = 24 * 1_024 * 1_024
        val JSON_V2 = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}

/** The V2 outer envelope is a flat JSON object, so duplicate-key detection can
 * be strict without implementing another semantic JSON codec. */
internal fun hasDuplicateTopLevelJsonKeysV2(json: String): Boolean {
    var index = 0
    fun whitespace() {
        while (index < json.length && json[index].isWhitespace()) index++
    }
    fun string(): String? {
        if (index >= json.length || json[index] != '"') return null
        index++
        val output = StringBuilder()
        while (index < json.length) {
            val char = json[index++]
            when (char) {
                '"' -> return output.toString()
                '\\' -> {
                    if (index >= json.length) return null
                    val escaped = json[index++]
                    if (escaped == 'u') {
                        if (index + 4 > json.length) return null
                        val value = json.substring(index, index + 4).toIntOrNull(16) ?: return null
                        output.append(value.toChar())
                        index += 4
                    } else {
                        output.append(escaped)
                    }
                }
                else -> output.append(char)
            }
        }
        return null
    }
    fun skipValue(): Boolean {
        whitespace()
        if (index >= json.length) return false
        if (json[index] == '"') return string() != null
        val start = index
        while (index < json.length && json[index] !in charArrayOf(',', '}')) index++
        return index > start
    }
    whitespace()
    if (index >= json.length || json[index++] != '{') return false
    val keys = mutableSetOf<String>()
    while (true) {
        whitespace()
        if (index < json.length && json[index] == '}') return false
        val key = string() ?: return false
        if (!keys.add(key)) return true
        whitespace()
        if (index >= json.length || json[index++] != ':') return false
        if (!skipValue()) return false
        whitespace()
        if (index >= json.length) return false
        when (json[index++]) {
            '}' -> return false
            ',' -> Unit
            else -> return false
        }
    }
}
