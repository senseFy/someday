@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package saien.someday.sync.pairing

import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.toByteString
import saien.someday.data.crypto.AeadCiphertext
import saien.someday.data.crypto.CryptoResult
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.sync.StrictJsonV2

/**
 * A 128-bit capability used to find and decrypt one workspace invitation.
 *
 * Secret material is only exposed through explicit UI/protocol methods. In
 * particular, logging this value never prints the manual token.
 */
class WorkspacePairingToken private constructor(
    private val secret: ByteArray,
) {
    init {
        require(secret.size == SECRET_BYTES) { "Workspace pairing tokens must contain 128 bits." }
    }

    fun manualToken(): String {
        val data = CrockfordBase32.encode(secret)
        val checksum = CrockfordBase32.encode(checksumBytes(secret)).take(CHECKSUM_CHARS)
        return data + checksum
    }

    fun formattedManualToken(): String = manualToken().chunked(DISPLAY_GROUP_CHARS).joinToString(" ")

    fun qrPayload(): String = QR_PREFIX + manualToken()

    internal fun deriveMaterial(): WorkspacePairingKeyMaterial =
        WorkspacePairingKeyMaterial(
            inviteId = base64UrlNoPadding(
                hkdfSha256(
                    inputKeyMaterial = secret,
                    info = INVITE_ID_INFO,
                    outputBytes = INVITE_ID_BYTES,
                ),
            ),
            envelopeKey = hkdfSha256(
                inputKeyMaterial = secret,
                info = ENVELOPE_KEY_INFO,
                outputBytes = KEY_BYTES,
            ),
            stateKey = hkdfSha256(
                inputKeyMaterial = secret,
                info = STATE_KEY_INFO,
                outputBytes = KEY_BYTES,
            ),
        )

    override fun toString(): String = "WorkspacePairingToken(<redacted>)"

    companion object {
        const val SECRET_BYTES: Int = 16
        const val DATA_CHARS: Int = 26
        const val CHECKSUM_CHARS: Int = 2
        const val MANUAL_TOKEN_CHARS: Int = DATA_CHARS + CHECKSUM_CHARS
        const val DISPLAY_GROUP_CHARS: Int = 7
        const val QR_PREFIX: String = "SOMEDAY:PAIR:1:"

        private const val INVITE_ID_BYTES: Int = 16
        private const val KEY_BYTES: Int = 32
        private val CHECKSUM_DOMAIN =
            "someday.workspace-pairing.token-checksum.1\u0000".encodeToByteArray()
        private val HKDF_SALT =
            "someday.workspace-pairing.hkdf-sha256.1".encodeToByteArray()
        private val INVITE_ID_INFO =
            "someday.workspace-pairing.invite-id.1".encodeToByteArray()
        private val ENVELOPE_KEY_INFO =
            "someday.workspace-pairing.envelope-key.1".encodeToByteArray()
        private val STATE_KEY_INFO =
            "someday.workspace-pairing.state-key.1".encodeToByteArray()

        fun generate(crypto: SodiumWorkspaceCrypto): WorkspacePairingToken =
            WorkspacePairingToken(crypto.randomBytes(SECRET_BYTES))

        fun parse(input: String): WorkspacePairingToken? {
            if (input.any { it.code > 0x7f }) return null
            val trimmed = input.trim()
            val tokenText = if (trimmed.length >= QR_PREFIX.length &&
                trimmed.take(QR_PREFIX.length).uppercase() == QR_PREFIX
            ) {
                trimmed.drop(QR_PREFIX.length)
            } else {
                trimmed
            }
            val canonical = buildString(tokenText.length) {
                tokenText.forEach { char ->
                    when (char) {
                        ' ', '-' -> Unit
                        in 'a'..'z', in 'A'..'Z', in '0'..'9' -> append(
                            when (val upper = char.uppercaseChar()) {
                                'O' -> '0'
                                'I', 'L' -> '1'
                                else -> upper
                            },
                        )
                        else -> return null
                    }
                }
            }
            if (canonical.length != MANUAL_TOKEN_CHARS) return null
            val dataText = canonical.take(DATA_CHARS)
            val checksumText = canonical.takeLast(CHECKSUM_CHARS)
            val decoded = CrockfordBase32.decode(dataText) ?: return null
            if (decoded.size != SECRET_BYTES) return null
            val token = WorkspacePairingToken(decoded)
            return token.takeIf {
                it.manualToken().takeLast(CHECKSUM_CHARS) == checksumText
            }
        }

        internal fun fromSecretBytes(secret: ByteArray): WorkspacePairingToken =
            WorkspacePairingToken(secret.copyOf())

        private fun checksumBytes(secret: ByteArray): ByteArray =
            (CHECKSUM_DOMAIN + secret).toByteString().sha256().toByteArray()

        private fun hkdfSha256(
            inputKeyMaterial: ByteArray,
            info: ByteArray,
            outputBytes: Int,
        ): ByteArray {
            require(outputBytes in 1..32) { "This protocol derives at most one SHA-256 block." }
            val pseudorandomKey = inputKeyMaterial
                .toByteString()
                .hmacSha256(HKDF_SALT.toByteString())
            return (info + byteArrayOf(1))
                .toByteString()
                .hmacSha256(pseudorandomKey)
                .toByteArray()
                .copyOf(outputBytes)
        }
    }
}

internal class WorkspacePairingKeyMaterial(
    val inviteId: String,
    val envelopeKey: ByteArray,
    val stateKey: ByteArray,
) {
    override fun toString(): String =
        "WorkspacePairingKeyMaterial(inviteId=$inviteId, keys=<redacted>)"
}

data class WorkspacePairingAuthority(
    /**
     * A canonical, non-secret identity for the configured self-hosted authority.
     * It is authenticated as AAD and is intentionally not stored in the
     * envelope.
     */
    val binding: String,
) {
    init {
        require(binding.isNotBlank()) { "Pairing authority binding must not be blank." }
    }
}

data class EncodedWorkspacePairingEnvelope(
    val inviteId: String,
    val expiresAtEpochMillis: Long,
    val bytes: ByteArray,
    val digest: String,
) {
    override fun toString(): String =
        "EncodedWorkspacePairingEnvelope(inviteId=$inviteId, expiresAtEpochMillis=$expiresAtEpochMillis, " +
            "bytes=${bytes.size}, digest=$digest)"
}

sealed interface WorkspacePairingEnvelopeDecodeResult {
    data class Success(
        val packageData: WorkspaceJoinPackage,
        val envelopeDigest: String,
        val expiresAtEpochMillis: Long,
    ) : WorkspacePairingEnvelopeDecodeResult

    data object Expired : WorkspacePairingEnvelopeDecodeResult

    data object Invalid : WorkspacePairingEnvelopeDecodeResult
}

class WorkspacePairingEnvelopeCodec(
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
) {
    fun encode(
        token: WorkspacePairingToken,
        authority: WorkspacePairingAuthority,
        createdAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
        packageData: WorkspaceJoinPackage,
    ): EncodedWorkspacePairingEnvelope {
        require(createdAtEpochMillis >= 0) { "Pairing creation time must be non-negative." }
        require(expiresAtEpochMillis > createdAtEpochMillis) { "Pairing expiry must follow creation." }
        require(expiresAtEpochMillis - createdAtEpochMillis <= MAX_TTL_MILLIS) {
            "Pairing invitation lifetime must not exceed ten minutes."
        }
        validatePackage(packageData)
        val material = token.deriveMaterial()
        val plaintext = strictJson.encodeToString(
            WorkspacePairingPayload.serializer(),
            WorkspacePairingPayload(
                metadataJson = packageData.metadataJson,
                recoveryCode = packageData.recoveryCode,
                workspaceId = packageData.workspaceId,
                keyFingerprint = packageData.keyFingerprint,
            ),
        ).encodeToByteArray()
        require(plaintext.size <= MAX_PAYLOAD_BYTES) {
            "Workspace pairing payload exceeds the protocol limit."
        }
        val aad = associatedData(
            authority = authority,
            inviteId = material.inviteId,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
        val encrypted = crypto.encryptAead(
            key = material.envelopeKey,
            associatedData = aad,
            plaintext = plaintext,
        )
        val envelope = WorkspacePairingEnvelope(
            inviteId = material.inviteId,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            nonce = base64UrlNoPadding(encrypted.nonce),
            ciphertext = base64UrlNoPadding(encrypted.ciphertext),
        )
        val bytes = strictJson
            .encodeToString(WorkspacePairingEnvelope.serializer(), envelope)
            .encodeToByteArray()
        require(bytes.size <= MAX_ENVELOPE_BYTES) {
            "Workspace pairing envelope exceeds the protocol limit."
        }
        return EncodedWorkspacePairingEnvelope(
            inviteId = material.inviteId,
            expiresAtEpochMillis = expiresAtEpochMillis,
            bytes = bytes,
            digest = digest(bytes),
        )
    }

    fun decode(
        token: WorkspacePairingToken,
        authority: WorkspacePairingAuthority,
        envelopeBytes: ByteArray,
        nowEpochMillis: Long,
    ): WorkspacePairingEnvelopeDecodeResult {
        val envelope = decodeStrictEnvelope(envelopeBytes) ?: return WorkspacePairingEnvelopeDecodeResult.Invalid
        val material = token.deriveMaterial()
        if (envelope.format != ENVELOPE_FORMAT ||
            envelope.protocolVersion != PROTOCOL_VERSION ||
            envelope.inviteId != material.inviteId ||
            envelope.cipherSuite != CIPHER_SUITE ||
            envelope.keyDerivation != KEY_DERIVATION ||
            envelope.createdAtEpochMillis < 0 ||
            envelope.expiresAtEpochMillis <= envelope.createdAtEpochMillis ||
            envelope.expiresAtEpochMillis - envelope.createdAtEpochMillis > MAX_TTL_MILLIS
        ) {
            return WorkspacePairingEnvelopeDecodeResult.Invalid
        }
        if (nowEpochMillis >= envelope.expiresAtEpochMillis) {
            return WorkspacePairingEnvelopeDecodeResult.Expired
        }
        val nonce = decodeBase64UrlNoPadding(envelope.nonce)
            ?.takeIf { it.size == XCHACHA_NONCE_BYTES }
            ?: return WorkspacePairingEnvelopeDecodeResult.Invalid
        val ciphertext = decodeBase64UrlNoPadding(envelope.ciphertext)
            ?.takeIf { it.size >= POLY1305_TAG_BYTES }
            ?: return WorkspacePairingEnvelopeDecodeResult.Invalid
        val plaintext = when (
            val result = crypto.decryptAead(
                key = material.envelopeKey,
                associatedData = associatedData(
                    authority = authority,
                    inviteId = envelope.inviteId,
                    createdAtEpochMillis = envelope.createdAtEpochMillis,
                    expiresAtEpochMillis = envelope.expiresAtEpochMillis,
                ),
                ciphertext = AeadCiphertext(nonce = nonce, ciphertext = ciphertext),
            )
        ) {
            is CryptoResult.Success -> result.value
            CryptoResult.AuthenticationFailed,
            CryptoResult.InvalidCiphertext,
            -> return WorkspacePairingEnvelopeDecodeResult.Invalid
        }
        val payload = decodeStrictPayload(plaintext) ?: return WorkspacePairingEnvelopeDecodeResult.Invalid
        val packageData = WorkspaceJoinPackage(
            metadataJson = payload.metadataJson,
            recoveryCode = payload.recoveryCode,
            workspaceId = payload.workspaceId,
            keyFingerprint = payload.keyFingerprint,
        )
        return runCatching { validatePackage(packageData) }
            .fold(
                onSuccess = {
                    WorkspacePairingEnvelopeDecodeResult.Success(
                        packageData = packageData,
                        envelopeDigest = digest(envelopeBytes),
                        expiresAtEpochMillis = envelope.expiresAtEpochMillis,
                    )
                },
                onFailure = { WorkspacePairingEnvelopeDecodeResult.Invalid },
            )
    }

    private fun decodeStrictEnvelope(bytes: ByteArray): WorkspacePairingEnvelope? {
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
        if (runCatching {
                StrictJsonV2.requireValidObjectKeys(text, MAX_ENVELOPE_BYTES)
            }.isFailure
        ) {
            return null
        }
        val objectValue = try {
            strictJson.parseToJsonElement(text).jsonObject
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (objectValue.keys != ENVELOPE_FIELDS) return null
        if (objectValue["protocolVersion"] !is JsonPrimitive ||
            objectValue["protocolVersion"]?.jsonPrimitive?.isString != false ||
            objectValue["protocolVersion"]?.jsonPrimitive?.longOrNull == null
        ) {
            return null
        }
        return runCatching {
            strictJson.decodeFromJsonElement(WorkspacePairingEnvelope.serializer(), objectValue)
        }.getOrNull()
    }

    private fun decodeStrictPayload(bytes: ByteArray): WorkspacePairingPayload? {
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
        if (runCatching {
                StrictJsonV2.requireValidObjectKeys(text, MAX_PAYLOAD_BYTES)
            }.isFailure
        ) {
            return null
        }
        val objectValue: JsonObject = try {
            strictJson.parseToJsonElement(text).jsonObject
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (objectValue.keys != PAYLOAD_FIELDS || objectValue.values.any { it !is JsonPrimitive || !it.isString }) {
            return null
        }
        return runCatching {
            strictJson.decodeFromJsonElement(WorkspacePairingPayload.serializer(), objectValue)
        }.getOrNull()
    }

    private fun associatedData(
        authority: WorkspacePairingAuthority,
        inviteId: String,
        createdAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): ByteArray =
        listOf(
            ENVELOPE_FORMAT,
            PROTOCOL_VERSION.toString(),
            inviteId,
            createdAtEpochMillis.toString(),
            expiresAtEpochMillis.toString(),
            CIPHER_SUITE,
            KEY_DERIVATION,
            authority.binding,
        ).joinToString(separator = "\n").encodeToByteArray()

    private fun validatePackage(packageData: WorkspaceJoinPackage) {
        require(packageData.metadataJson.isNotBlank()) { "Workspace metadata must be present." }
        require(packageData.recoveryCode.isNotBlank()) { "Workspace recovery material must be present." }
        require(packageData.workspaceId.isNotBlank()) { "Workspace identifier must be present." }
        require(packageData.keyFingerprint.isNotBlank()) { "Workspace key fingerprint must be present." }
    }

    companion object {
        const val ENVELOPE_FORMAT: String = "someday.workspace-pairing"
        const val PROTOCOL_VERSION: Int = 1
        const val CIPHER_SUITE: String = "xchacha20-poly1305-ietf"
        const val KEY_DERIVATION: String = "hkdf-sha256"
        const val MAX_TTL_MILLIS: Long = 10 * 60 * 1_000L

        private const val XCHACHA_NONCE_BYTES: Int = 24
        private const val POLY1305_TAG_BYTES: Int = 16
        const val MAX_ENVELOPE_BYTES: Int = 64 * 1_024
        const val MAX_PAYLOAD_BYTES: Int = 47 * 1_024
        private val ENVELOPE_FIELDS = setOf(
            "format",
            "protocolVersion",
            "inviteId",
            "createdAtEpochMillis",
            "expiresAtEpochMillis",
            "cipherSuite",
            "keyDerivation",
            "nonce",
            "ciphertext",
        )
        private val PAYLOAD_FIELDS = setOf(
            "metadataJson",
            "recoveryCode",
            "workspaceId",
            "keyFingerprint",
        )
        private val strictJson = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
            isLenient = false
            allowStructuredMapKeys = false
        }

        fun digest(bytes: ByteArray): String =
            base64UrlNoPadding(bytes.toByteString().sha256().toByteArray())
    }
}

@Serializable
private data class WorkspacePairingEnvelope(
    val format: String = WorkspacePairingEnvelopeCodec.ENVELOPE_FORMAT,
    val protocolVersion: Int = WorkspacePairingEnvelopeCodec.PROTOCOL_VERSION,
    val inviteId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val cipherSuite: String = WorkspacePairingEnvelopeCodec.CIPHER_SUITE,
    val keyDerivation: String = WorkspacePairingEnvelopeCodec.KEY_DERIVATION,
    val nonce: String,
    val ciphertext: String,
)

@Serializable
private data class WorkspacePairingPayload(
    val metadataJson: String,
    val recoveryCode: String,
    val workspaceId: String,
    val keyFingerprint: String,
)

private object CrockfordBase32 {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(bytes: ByteArray): String {
        var buffer = 0
        var bits = 0
        return buildString((bytes.size * 8 + 4) / 5) {
            bytes.forEach { byte ->
                buffer = (buffer shl 8) or (byte.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    append(ALPHABET[(buffer shr bits) and 0x1f])
                    buffer = buffer and ((1 shl bits) - 1)
                }
            }
            if (bits > 0) {
                append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
            }
        }
    }

    fun decode(text: String): ByteArray? {
        var buffer = 0
        var bits = 0
        val output = mutableListOf<Byte>()
        text.forEach { char ->
            val value = ALPHABET.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output += ((buffer shr bits) and 0xff).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (bits > 0 && buffer != 0) return null
        return output.toByteArray()
    }
}

internal fun base64UrlNoPadding(bytes: ByteArray): String =
    Base64.UrlSafe.encode(bytes).trimEnd('=')

internal fun decodeBase64UrlNoPadding(value: String): ByteArray? {
    if (value.isEmpty() || value.any { it !in BASE64_URL_CHARS } || '=' in value) return null
    val padding = "=".repeat((4 - value.length % 4) % 4)
    val decoded = runCatching { Base64.UrlSafe.decode(value + padding) }.getOrNull() ?: return null
    return decoded.takeIf { base64UrlNoPadding(it) == value }
}

private const val BASE64_URL_CHARS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
