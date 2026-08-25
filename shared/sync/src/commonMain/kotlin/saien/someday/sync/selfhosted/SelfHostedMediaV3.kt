package saien.someday.sync.selfhosted

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.Source
import okio.buffer
import okio.ByteString.Companion.toByteString
import saien.someday.data.crypto.AeadCiphertext
import saien.someday.data.crypto.CryptoResult
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.crypto.WorkspaceSubkey
import saien.someday.domain.media.MAX_MEDIA_ASSET_PIXEL_COUNT
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.media.isSafeOriginalFileName
import saien.someday.sync.StrictJsonV2

const val SYSTEM_V3_CONTRACT_ID: String = "someday-system-v3"
const val SYSTEM_V3_MEDIA_CONTRACT_ID: String = "someday-system-v3-media-v1"
const val SYSTEM_V3_MEDIA_OBJECT_SCHEMA_VERSION: Int = 1
const val SYSTEM_V3_MEDIA_MAX_PLAINTEXT_BYTES: Int = 4 * 1024 * 1024
const val SYSTEM_V3_MEDIA_MAX_METADATA_BYTES: Int = 4 * 1024
const val SYSTEM_V3_MEDIA_MAX_CIPHERTEXT_BYTES: Int =
    SYSTEM_V3_MEDIA_MAX_PLAINTEXT_BYTES + SYSTEM_V3_MEDIA_MAX_METADATA_BYTES + 4 + 40
const val SYSTEM_V3_ENTITY_MAX_ENCODED_BODY_BYTES: Int = 16 * 1024 * 1024
const val SYSTEM_V3_MEDIA_CIPHER_SUITE: String = "xchacha20-poly1305-ietf"
const val SYSTEM_V3_MEDIA_CIPHERTEXT_MODE: String = "deterministic-single-object-v1"
const val SYSTEM_V3_MEDIA_OBJECT_CONTENT_TYPE: String = "application/vnd.someday.media-object.v1"
const val SYSTEM_V3_MEDIA_CIPHERTEXT_SHA256_HEADER: String = "X-Someday-Media-Ciphertext-Sha256"
const val SYSTEM_V3_MEDIA_CIPHERTEXT_BYTES_HEADER: String = "X-Someday-Media-Ciphertext-Bytes"

private val WORKSPACE_ID = Regex("^workspace-[0-9a-f]{32}$")

fun requireSystemV3WorkspaceId(workspaceId: String): String = workspaceId.also {
    require(WORKSPACE_ID.matches(it)) { "Workspace id is not canonical." }
}

@Serializable
data class SelfHostedSystemV3CapabilitiesResponse(
    val contractId: String,
    val semanticProtocolVersion: Int,
    val authorityMode: String,
    val entityDag: SelfHostedSystemV3EntityDagCapabilities,
    val media: SelfHostedSystemV3MediaCapabilities,
) {
    fun validate() {
        require(contractId == SYSTEM_V3_CONTRACT_ID)
        require(semanticProtocolVersion == 3)
        require(authorityMode == "self-hosted")
        entityDag.validate()
        media.validate()
    }
}

@Serializable
data class SelfHostedSystemV3EntityDagCapabilities(
    val subsystemContractId: String,
    val apiBasePath: String,
    val schemaSetVersion: String,
    val encrypted: Boolean,
    val semanticProtocolVersion: Int,
    val keySetVersion: String,
    val metadataPrivacyMode: String,
    val maxPushObjects: Int,
    val maxPullUnits: Int,
    val maxEncodedBodyBytes: Int,
    val supportsCheckpoints: Boolean,
    val parentIndexedMetadata: Boolean,
) {
    fun validate() {
        require(subsystemContractId == "someday-system-v2")
        require(apiBasePath == "/sync/v3/workspaces/{workspaceId}/entities")
        require(schemaSetVersion == "workspace-entity-schema-set-v2")
        require(encrypted)
        require(semanticProtocolVersion == 2)
        require(keySetVersion == "sync-key-set-v2")
        require(metadataPrivacyMode == "opaque")
        require(maxPushObjects in 1..100)
        require(maxPullUnits in 1..500)
        require(maxEncodedBodyBytes in 1..SYSTEM_V3_ENTITY_MAX_ENCODED_BODY_BYTES)
        require(supportsCheckpoints)
        require(!parentIndexedMetadata)
    }
}

internal fun SelfHostedSystemV3CapabilitiesResponse.toInternalEntityV2Capabilities():
    SelfHostedV2CapabilitiesResponse {
    validate()
    return SelfHostedV2CapabilitiesResponse(
        profile = "self-hosted-v2",
        contractId = entityDag.subsystemContractId,
        semanticProtocolVersion = entityDag.semanticProtocolVersion,
        schemaSetVersion = entityDag.schemaSetVersion,
        keySetVersion = entityDag.keySetVersion,
        metadataPrivacyMode = entityDag.metadataPrivacyMode,
        maxPushObjects = entityDag.maxPushObjects,
        maxPullUnits = entityDag.maxPullUnits,
        maxEncodedBodyBytes = entityDag.maxEncodedBodyBytes,
        supportsCheckpoints = entityDag.supportsCheckpoints,
        parentIndexedMetadata = entityDag.parentIndexedMetadata,
    )
}

@Serializable
data class SelfHostedSystemV3MediaCapabilities(
    val subsystemContractId: String,
    val apiBasePath: String,
    val objectSchemaVersion: Int,
    val cipherSuite: String,
    val ciphertextMode: String,
    val maxPlaintextBytes: Int,
    val maxCiphertextBytes: Int,
    val supportsHead: Boolean,
    val immutablePut: Boolean,
) {
    fun validate() {
        require(subsystemContractId == SYSTEM_V3_MEDIA_CONTRACT_ID)
        require(apiBasePath == "/sync/v3/workspaces/{workspaceId}/media")
        require(objectSchemaVersion == SYSTEM_V3_MEDIA_OBJECT_SCHEMA_VERSION)
        require(cipherSuite == SYSTEM_V3_MEDIA_CIPHER_SUITE)
        require(ciphertextMode == SYSTEM_V3_MEDIA_CIPHERTEXT_MODE)
        require(maxPlaintextBytes == SYSTEM_V3_MEDIA_MAX_PLAINTEXT_BYTES)
        require(maxCiphertextBytes == SYSTEM_V3_MEDIA_MAX_CIPHERTEXT_BYTES)
        require(supportsHead && immutablePut)
    }
}

@Serializable
data class SelfHostedMediaPutResponseV3(
    val stored: Boolean,
    val idempotentReplay: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SelfHostedMediaObjectMetadataV3(
    val objectSchemaVersion: Int = SYSTEM_V3_MEDIA_OBJECT_SCHEMA_VERSION,
    val contractId: String = SYSTEM_V3_MEDIA_CONTRACT_ID,
    val mediaId: String,
    val mediaType: String,
    val originalFileName: String? = null,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val plaintextBytes: Int,
    val plaintextSha256: String,
) {
    fun validate() {
        require(objectSchemaVersion == SYSTEM_V3_MEDIA_OBJECT_SCHEMA_VERSION)
        require(contractId == SYSTEM_V3_MEDIA_CONTRACT_ID)
        MediaAssetId.fromCanonicalValue(mediaId)
        require(mediaType in SUPPORTED_MEDIA_TYPES)
        require(originalFileName == null || isSafeOriginalFileName(originalFileName))
        require(pixelWidth > 0 && pixelHeight > 0)
        require(pixelWidth.toLong() * pixelHeight <= MAX_MEDIA_ASSET_PIXEL_COUNT)
        require(plaintextBytes in 1..SYSTEM_V3_MEDIA_MAX_PLAINTEXT_BYTES)
        require(MEDIA_SHA256.matches(plaintextSha256))
    }
}

data class SelfHostedPreparedMediaObjectV3(
    val metadata: SelfHostedMediaObjectMetadataV3,
    val encryptedBytes: ByteArray,
    val encryptedSha256: String,
) {
    init {
        metadata.validate()
        require(encryptedBytes.size in MEDIA_ENCRYPTION_OVERHEAD_BYTES + 5..SYSTEM_V3_MEDIA_MAX_CIPHERTEXT_BYTES)
        require(encryptedSha256 == sha256(encryptedBytes))
    }
}

data class SelfHostedDecryptedMediaObjectV3(
    val metadata: SelfHostedMediaObjectMetadataV3,
    val plaintextBytes: ByteArray,
    val encryptedSha256: String,
)

data class SelfHostedMediaRemoteObjectV3(
    val ciphertextBytes: Int,
    val ciphertextSha256: String,
    val bytes: ByteArray,
) {
    init {
        require(ciphertextBytes == bytes.size)
        require(ciphertextBytes in MEDIA_ENCRYPTION_OVERHEAD_BYTES + 5..SYSTEM_V3_MEDIA_MAX_CIPHERTEXT_BYTES)
        require(ciphertextSha256 == sha256(bytes))
    }
}

data class SelfHostedMediaRemoteHeadV3(
    val ciphertextBytes: Int,
    val ciphertextSha256: String,
) {
    init {
        require(ciphertextBytes in MEDIA_ENCRYPTION_OVERHEAD_BYTES + 5..SYSTEM_V3_MEDIA_MAX_CIPHERTEXT_BYTES)
        require(MEDIA_SHA256.matches(ciphertextSha256))
    }
}

interface SelfHostedMediaTransportV3 {
    fun systemV3Capabilities(endpoint: String, accessToken: String): SelfHostedSystemV3CapabilitiesResponse

    fun putMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
        prepared: SelfHostedPreparedMediaObjectV3,
    ): SelfHostedMediaPutResponseV3

    fun headMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
    ): SelfHostedMediaRemoteHeadV3?

    fun getMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
    ): SelfHostedMediaRemoteObjectV3
}

class RefreshingSelfHostedMediaTransportV3(
    private val delegate: SelfHostedMediaTransportV3,
    private val sessionExecutor: RefreshingSelfHostedSessionExecutor,
    private val authenticatedUserId: String,
) : SelfHostedMediaTransportV3 {
    override fun systemV3Capabilities(endpoint: String, accessToken: String) =
        authorized(endpoint, accessToken) { delegate.systemV3Capabilities(endpoint, it) }

    override fun putMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
        prepared: SelfHostedPreparedMediaObjectV3,
    ) = authorized(endpoint, accessToken) {
        delegate.putMediaObject(endpoint, it, workspaceId, mediaId, prepared)
    }

    override fun headMediaObject(endpoint: String, accessToken: String, workspaceId: String, mediaId: String) =
        authorized(endpoint, accessToken) { delegate.headMediaObject(endpoint, it, workspaceId, mediaId) }

    override fun getMediaObject(endpoint: String, accessToken: String, workspaceId: String, mediaId: String) =
        authorized(endpoint, accessToken) { delegate.getMediaObject(endpoint, it, workspaceId, mediaId) }

    private fun <T> authorized(endpoint: String, suppliedToken: String, request: (String) -> T): T =
        sessionExecutor.authorized(endpoint, authenticatedUserId, suppliedToken, request)
}

class SelfHostedMediaCipherV3(
    workspaceKey: WorkspaceMasterKey,
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
) {
    private val mediaKeyRoot: ByteArray = crypto.deriveSubkey(workspaceKey, WorkspaceSubkey.OBJECTS)

    fun prepare(
        workspaceId: String,
        mediaId: MediaAssetId,
        mediaType: String,
        pixelWidth: Int,
        pixelHeight: Int,
        plaintext: ByteArray,
        originalFileName: String? = null,
    ): SelfHostedPreparedMediaObjectV3 {
        requireSystemV3WorkspaceId(workspaceId)
        require(plaintext.size in 1..SYSTEM_V3_MEDIA_MAX_PLAINTEXT_BYTES)
        val metadata = SelfHostedMediaObjectMetadataV3(
            mediaId = mediaId.value,
            mediaType = mediaType,
            originalFileName = originalFileName,
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            plaintextBytes = plaintext.size,
            plaintextSha256 = sha256(plaintext),
        ).also(SelfHostedMediaObjectMetadataV3::validate)
        val encodedMetadata = MEDIA_JSON.encodeToString(metadata).encodeToByteArray()
        require(encodedMetadata.size in 1..SYSTEM_V3_MEDIA_MAX_METADATA_BYTES)
        val envelope = Buffer()
            .writeInt(encodedMetadata.size)
            .write(encodedMetadata)
            .write(plaintext)
            .readByteArray()
        val key = mediaKey(workspaceId, mediaId.value)
        val encrypted = crypto.encryptAeadWithNonce(
            key,
            deterministicNonce(key, workspaceId, mediaId.value, envelope),
            objectAssociatedData(workspaceId, mediaId.value, envelope.size),
            envelope,
        ).framed()
        return SelfHostedPreparedMediaObjectV3(metadata, encrypted, sha256(encrypted))
    }

    fun decrypt(
        workspaceId: String,
        mediaId: MediaAssetId,
        encrypted: ByteArray,
    ): Result<SelfHostedDecryptedMediaObjectV3> = runCatching {
        requireSystemV3WorkspaceId(workspaceId)
        require(encrypted.size in MEDIA_ENCRYPTION_OVERHEAD_BYTES + 5..SYSTEM_V3_MEDIA_MAX_CIPHERTEXT_BYTES)
        val framed = encrypted.unframe()
        val key = mediaKey(workspaceId, mediaId.value)
        val envelopeBytes = encrypted.size - MEDIA_ENCRYPTION_OVERHEAD_BYTES
        val envelope = when (val result = crypto.decryptAead(
            key,
            objectAssociatedData(workspaceId, mediaId.value, envelopeBytes),
            framed,
        )) {
            is CryptoResult.Success -> result.value
            CryptoResult.AuthenticationFailed,
            CryptoResult.InvalidCiphertext,
            -> error("Media object authentication failed.")
        }
        require(framed.nonce.contentEquals(deterministicNonce(key, workspaceId, mediaId.value, envelope))) {
            "Media object uses non-canonical ciphertext."
        }
        val buffer = Buffer().write(envelope)
        val metadataBytes = buffer.readInt()
        require(metadataBytes in 1..SYSTEM_V3_MEDIA_MAX_METADATA_BYTES)
        require(metadataBytes.toLong() <= buffer.size)
        val encoded = buffer.readByteArray(metadataBytes.toLong()).decodeToString(throwOnInvalidSequence = true)
        StrictJsonV2.requireValidObjectKeys(encoded, SYSTEM_V3_MEDIA_MAX_METADATA_BYTES)
        val metadata = MEDIA_JSON.decodeFromString<SelfHostedMediaObjectMetadataV3>(encoded).also {
            require(it.mediaId == mediaId.value)
            it.validate()
        }
        val plaintext = buffer.readByteArray()
        require(plaintext.size == metadata.plaintextBytes)
        require(sha256(plaintext) == metadata.plaintextSha256)
        SelfHostedDecryptedMediaObjectV3(metadata, plaintext, sha256(encrypted))
    }

    private fun mediaKey(workspaceId: String, mediaId: String): ByteArray {
        MediaAssetId.fromCanonicalValue(mediaId)
        val context = Buffer().writeUtf8(MEDIA_KEY_DOMAIN).writeByte(0)
            .writeUtf8(workspaceId).writeByte(0).writeUtf8(mediaId).readByteString()
        return context.hmacSha256(mediaKeyRoot.toByteString()).toByteArray()
    }
}

data class SelfHostedMediaUploadSummaryV3(val uploadedObjects: Int, val reusedObjects: Int)

data class SelfHostedMediaSourceUploadResultV3(
    val encryptedObjectSha256: String,
    val summary: SelfHostedMediaUploadSummaryV3,
)

class SelfHostedMediaServiceV3(
    private val transport: SelfHostedMediaTransportV3,
    private val cipher: SelfHostedMediaCipherV3,
) {
    fun uploadSource(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: MediaAssetId,
        mediaType: String,
        pixelWidth: Int,
        pixelHeight: Int,
        plaintextBytes: Long,
        source: Source,
        originalFileName: String? = null,
    ): SelfHostedMediaSourceUploadResultV3 {
        requireSystemV3WorkspaceId(workspaceId)
        require(plaintextBytes in 1..SYSTEM_V3_MEDIA_MAX_PLAINTEXT_BYTES.toLong())
        val buffered = source.buffer()
        val plaintext = buffered.readByteArray(plaintextBytes)
        require(buffered.exhausted()) { "Media source contains more bytes than declared." }
        val prepared = cipher.prepare(
            workspaceId,
            mediaId,
            mediaType,
            pixelWidth,
            pixelHeight,
            plaintext,
            originalFileName,
        )
        transport.systemV3Capabilities(endpoint, accessToken).also(SelfHostedSystemV3CapabilitiesResponse::validate)
        val existing = transport.headMediaObject(endpoint, accessToken, workspaceId, mediaId.value)
        if (existing != null) {
            require(existing.ciphertextBytes == prepared.encryptedBytes.size &&
                existing.ciphertextSha256 == prepared.encryptedSha256
            ) { "Existing immutable media object differs from the deterministic local ciphertext." }
            return SelfHostedMediaSourceUploadResultV3(
                prepared.encryptedSha256,
                SelfHostedMediaUploadSummaryV3(uploadedObjects = 0, reusedObjects = 1),
            )
        }
        val result = transport.putMediaObject(endpoint, accessToken, workspaceId, mediaId.value, prepared)
        require(result.stored) { result.error ?: "Media object upload was rejected." }
        val confirmed = transport.headMediaObject(endpoint, accessToken, workspaceId, mediaId.value)
            ?: error("Uploaded media object is not remotely reachable.")
        require(confirmed.ciphertextBytes == prepared.encryptedBytes.size &&
            confirmed.ciphertextSha256 == prepared.encryptedSha256
        ) { "Uploaded immutable media object does not match the deterministic ciphertext." }
        return SelfHostedMediaSourceUploadResultV3(
            prepared.encryptedSha256,
            SelfHostedMediaUploadSummaryV3(
                uploadedObjects = if (result.idempotentReplay) 0 else 1,
                reusedObjects = if (result.idempotentReplay) 1 else 0,
            ),
        )
    }

    fun headObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: MediaAssetId,
    ): SelfHostedMediaRemoteHeadV3? {
        requireSystemV3WorkspaceId(workspaceId)
        transport.systemV3Capabilities(endpoint, accessToken).also(SelfHostedSystemV3CapabilitiesResponse::validate)
        return transport.headMediaObject(endpoint, accessToken, workspaceId, mediaId.value)
    }

    fun fetchObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: MediaAssetId,
    ): SelfHostedDecryptedMediaObjectV3 {
        requireSystemV3WorkspaceId(workspaceId)
        transport.systemV3Capabilities(endpoint, accessToken).also(SelfHostedSystemV3CapabilitiesResponse::validate)
        val remote = transport.getMediaObject(endpoint, accessToken, workspaceId, mediaId.value)
        return cipher.decrypt(workspaceId, mediaId, remote.bytes).getOrThrow().also {
            require(it.encryptedSha256 == remote.ciphertextSha256)
        }
    }
}

private fun objectAssociatedData(workspaceId: String, mediaId: String, plaintextBytes: Int): ByteArray = Buffer()
    .writeUtf8(MEDIA_AAD_DOMAIN)
    .writeByte(0)
    .writeUtf8(workspaceId)
    .writeByte(0)
    .writeUtf8(mediaId)
    .writeInt(plaintextBytes)
    .readByteArray()

private fun deterministicNonce(
    key: ByteArray,
    workspaceId: String,
    mediaId: String,
    plaintext: ByteArray,
): ByteArray = Buffer()
    .writeUtf8(MEDIA_NONCE_DOMAIN)
    .writeByte(0)
    .writeUtf8(workspaceId)
    .writeByte(0)
    .writeUtf8(mediaId)
    .write(plaintext.toByteString().sha256())
    .readByteString()
    .hmacSha256(key.toByteString())
    .toByteArray()
    .copyOf(MEDIA_NONCE_BYTES)

private fun AeadCiphertext.framed(): ByteArray = nonce + ciphertext

private fun ByteArray.unframe(): AeadCiphertext {
    require(size > MEDIA_ENCRYPTION_OVERHEAD_BYTES)
    return AeadCiphertext(copyOfRange(0, MEDIA_NONCE_BYTES), copyOfRange(MEDIA_NONCE_BYTES, size))
}

internal fun selfHostedMediaSha256(bytes: ByteArray): String = sha256(bytes)

private fun sha256(bytes: ByteArray): String = "sha256:${bytes.toByteString().sha256().hex()}"

private val MEDIA_JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = true
    isLenient = false
    coerceInputValues = false
}
private val MEDIA_SHA256 = Regex("^sha256:[0-9a-f]{64}$")
private val SUPPORTED_MEDIA_TYPES = setOf("image/jpeg", "image/png", "image/webp")
private const val MEDIA_KEY_DOMAIN = "someday-system-v3-media-key-v1"
private const val MEDIA_AAD_DOMAIN = "someday-system-v3-media-object-aad-v1"
private const val MEDIA_NONCE_DOMAIN = "someday-system-v3-media-object-nonce-v1"
private const val MEDIA_NONCE_BYTES = 24
private const val MEDIA_TAG_BYTES = 16
private const val MEDIA_ENCRYPTION_OVERHEAD_BYTES = MEDIA_NONCE_BYTES + MEDIA_TAG_BYTES
