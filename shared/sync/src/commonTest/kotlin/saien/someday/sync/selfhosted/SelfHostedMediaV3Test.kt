package saien.someday.sync.selfhosted

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okio.Buffer
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.domain.media.MediaAssetId

class SelfHostedMediaV3Test {
    private val crypto = SodiumWorkspaceCrypto()
    private val key = crypto.workspaceKeyFromBytes(ByteArray(32) { it.toByte() })
    private val cipher = SelfHostedMediaCipherV3(key, crypto)
    private val mediaId = MediaAssetId.fromCanonicalValue("0123456789abcdef".repeat(4))

    @Test
    fun singleObjectIsDeterministicAuthenticatedAndWorkspaceScoped() {
        val plaintext = ByteArray(2_501) { (it * 17).toByte() }
        val first = cipher.prepare(WORKSPACE, mediaId, "image/png", 64, 48, plaintext, "diagram.png")
        val replay = cipher.prepare(WORKSPACE, mediaId, "image/png", 64, 48, plaintext, "diagram.png")

        assertContentEquals(first.encryptedBytes, replay.encryptedBytes)
        val restored = cipher.decrypt(WORKSPACE, mediaId, first.encryptedBytes).getOrThrow()
        assertEquals(first.metadata, restored.metadata)
        assertContentEquals(plaintext, restored.plaintextBytes)

        val otherWorkspace = "workspace-${"f".repeat(32)}"
        val scoped = cipher.prepare(otherWorkspace, mediaId, "image/png", 64, 48, plaintext, "diagram.png")
        assertNotEquals(first.encryptedSha256, scoped.encryptedSha256)
        assertTrue(cipher.decrypt(otherWorkspace, mediaId, first.encryptedBytes).isFailure)

        val tampered = first.encryptedBytes.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertTrue(cipher.decrypt(WORKSPACE, mediaId, tampered).isFailure)
    }

    @Test
    fun canonicalFirstReleaseBoundsAreEnforced() {
        assertFailsWith<IllegalArgumentException> {
            cipher.prepare(WORKSPACE, mediaId, "image/png", 4_000, 4_000, byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.prepare(
                WORKSPACE,
                mediaId,
                "image/png",
                64,
                48,
                ByteArray(SYSTEM_V3_MEDIA_MAX_PLAINTEXT_BYTES + 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.prepare("not-a-workspace", mediaId, "image/png", 1, 1, byteArrayOf(1))
        }
    }

    @Test
    fun serviceUploadsOneObjectAndExactReplayUsesHead() {
        val transport = InMemoryMediaTransportV3()
        val service = SelfHostedMediaServiceV3(transport, cipher)
        val plaintext = ByteArray(3_073) { (it * 11).toByte() }

        val first = service.uploadSource(
            "https://sync.example", "token", WORKSPACE, mediaId, "image/webp", 320, 240,
            plaintext.size.toLong(), Buffer().write(plaintext), "photo.webp",
        )
        assertEquals(SelfHostedMediaUploadSummaryV3(1, 0), first.summary)
        val replay = service.uploadSource(
            "https://sync.example", "token", WORKSPACE, mediaId, "image/webp", 320, 240,
            plaintext.size.toLong(), Buffer().write(plaintext), "photo.webp",
        )
        assertEquals(first.encryptedObjectSha256, replay.encryptedObjectSha256)
        assertEquals(SelfHostedMediaUploadSummaryV3(0, 1), replay.summary)
        assertEquals(1, transport.puts)

        val fetched = service.fetchObject("https://sync.example", "token", WORKSPACE, mediaId)
        assertContentEquals(plaintext, fetched.plaintextBytes)
        assertEquals(1, transport.gets)
    }

    companion object {
        internal const val WORKSPACE = "workspace-0123456789abcdef0123456789abcdef"
    }
}

internal class InMemoryMediaTransportV3 : SelfHostedMediaTransportV3 {
    private val objects = linkedMapOf<Pair<String, String>, SelfHostedMediaRemoteObjectV3>()
    val writeEvents = mutableListOf<String>()
    var puts = 0
    var gets = 0

    override fun systemV3Capabilities(endpoint: String, accessToken: String) =
        SelfHostedSystemV3CapabilitiesResponse(
            SYSTEM_V3_CONTRACT_ID,
            3,
            "self-hosted",
            SelfHostedSystemV3EntityDagCapabilities(
                "someday-system-v2",
                "/sync/v3/workspaces/{workspaceId}/entities",
                "workspace-entity-schema-set-v2",
                true,
                2,
                "sync-key-set-v2",
                "opaque",
                100,
                500,
                SYSTEM_V3_ENTITY_MAX_ENCODED_BODY_BYTES,
                true,
                false,
            ),
            SelfHostedSystemV3MediaCapabilities(
                SYSTEM_V3_MEDIA_CONTRACT_ID,
                "/sync/v3/workspaces/{workspaceId}/media",
                1,
                SYSTEM_V3_MEDIA_CIPHER_SUITE,
                SYSTEM_V3_MEDIA_CIPHERTEXT_MODE,
                SYSTEM_V3_MEDIA_MAX_PLAINTEXT_BYTES,
                SYSTEM_V3_MEDIA_MAX_CIPHERTEXT_BYTES,
                true,
                true,
            ),
        )

    override fun putMediaObject(
        endpoint: String,
        accessToken: String,
        workspaceId: String,
        mediaId: String,
        prepared: SelfHostedPreparedMediaObjectV3,
    ): SelfHostedMediaPutResponseV3 {
        val key = workspaceId to mediaId
        val existing = objects[key]
        if (existing != null && existing.ciphertextSha256 != prepared.encryptedSha256) {
            return SelfHostedMediaPutResponseV3(false, error = "immutable_media_mismatch")
        }
        if (existing == null) {
            objects[key] = SelfHostedMediaRemoteObjectV3(
                prepared.encryptedBytes.size,
                prepared.encryptedSha256,
                prepared.encryptedBytes.copyOf(),
            )
            puts++
            writeEvents += "object:$workspaceId:$mediaId"
        }
        return SelfHostedMediaPutResponseV3(true, idempotentReplay = existing != null)
    }

    override fun headMediaObject(endpoint: String, accessToken: String, workspaceId: String, mediaId: String) =
        objects[workspaceId to mediaId]?.let {
            SelfHostedMediaRemoteHeadV3(it.ciphertextBytes, it.ciphertextSha256)
        }

    override fun getMediaObject(endpoint: String, accessToken: String, workspaceId: String, mediaId: String):
        SelfHostedMediaRemoteObjectV3 {
        gets++
        return checkNotNull(objects[workspaceId to mediaId])
    }

    fun dropRemoteAsset(workspaceId: String, mediaId: String) {
        objects.remove(workspaceId to mediaId)
    }

    fun corrupt(workspaceId: String, mediaId: String) {
        val current = checkNotNull(objects[workspaceId to mediaId])
        val bytes = current.bytes.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        objects[workspaceId to mediaId] = SelfHostedMediaRemoteObjectV3(
            bytes.size,
            selfHostedMediaSha256(bytes),
            bytes,
        )
    }
}
