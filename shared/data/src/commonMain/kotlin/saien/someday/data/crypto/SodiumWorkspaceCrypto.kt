@file:OptIn(ExperimentalUnsignedTypes::class)

package saien.someday.data.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AeadCorrupedOrTamperedDataException
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.aead.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.kdf.Kdf
import com.ionspin.kotlin.crypto.pwhash.PasswordHash
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_MEMLIMIT_MIN
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_OPSLIMIT_MIN
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_SALTBYTES
import com.ionspin.kotlin.crypto.pwhash.crypto_pwhash_argon2id_ALG_ARGON2ID13
import com.ionspin.kotlin.crypto.util.LibsodiumRandom

class SodiumWorkspaceCrypto internal constructor(
    internal val recoveryKdfPolicy: RecoveryKdfPolicy,
) {
    /** Production callers always use the protocol-frozen v1 KDF profile. */
    constructor() : this(RecoveryKdfPolicy.protocolV1())

    init {
        ensureInitialized()
    }

    fun generateWorkspaceKey(): WorkspaceMasterKey =
        workspaceKeyFromBytes(randomBytes(WorkspaceMasterKey.WORKSPACE_KEY_BYTES))

    fun workspaceKeyFromBytes(keyBytes: ByteArray): WorkspaceMasterKey =
        WorkspaceMasterKey.fromBytes(
            keyBytes = keyBytes,
            fingerprint = Hash.sha256(keyBytes.toUByteArrayCopy()).toByteArrayCopy().hex().take(32),
        )

    fun randomBytes(size: Int): ByteArray = LibsodiumRandom.buf(size).toByteArrayCopy()

    fun generateRecoveryMaterial(): RecoveryMaterial =
        RecoveryMaterial.fromCode(
            "SOMEDAY-" + randomBytes(RECOVERY_ENTROPY_BYTES)
                .hex()
                .uppercase()
                .chunked(4)
                .joinToString("-"),
        )

    fun deriveSubkey(
        workspaceKey: WorkspaceMasterKey,
        subkey: WorkspaceSubkey,
    ): ByteArray {
        require(subkey.context.length == 8) { "Libsodium KDF context must be exactly eight bytes." }
        return Kdf.deriveFromKey(
            subkeyId = subkey.subkeyId,
            subkeyLength = WorkspaceMasterKey.WORKSPACE_KEY_BYTES,
            context = subkey.context,
            masterKey = workspaceKey.rawBytesCopy().toUByteArrayCopy(),
        ).toByteArrayCopy()
    }

    fun deriveRecoveryWrappingKey(
        recoveryMaterial: String,
        salt: ByteArray,
        policy: RecoveryKdfPolicy,
    ): ByteArray {
        require(salt.size == crypto_pwhash_SALTBYTES) { "Recovery KDF salt must be $crypto_pwhash_SALTBYTES bytes." }
        return PasswordHash.pwhash(
            outputLength = WorkspaceMasterKey.WORKSPACE_KEY_BYTES,
            password = normalizeRecoveryMaterial(recoveryMaterial),
            salt = salt.toUByteArrayCopy(),
            opsLimit = policy.opsLimit,
            memLimit = policy.memLimit,
            algorithm = policy.algorithm,
        ).toByteArrayCopy()
    }

    fun encryptAead(
        key: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): AeadCiphertext = encryptAeadWithNonce(
        key,
        randomBytes(crypto_aead_xchacha20poly1305_ietf_NPUBBYTES),
        associatedData,
        plaintext,
    )

    /**
     * Encrypts with an explicitly supplied XChaCha20 nonce. The caller must derive a unique nonce
     * for every distinct plaintext under [key]; this method rejects every non-canonical size.
     */
    fun encryptAeadWithNonce(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): AeadCiphertext {
        require(key.size == WorkspaceMasterKey.WORKSPACE_KEY_BYTES) { "AEAD key must be 32 bytes." }
        require(nonce.size == crypto_aead_xchacha20poly1305_ietf_NPUBBYTES) {
            "XChaCha20-Poly1305 nonces must be $crypto_aead_xchacha20poly1305_ietf_NPUBBYTES bytes."
        }
        val ciphertext = AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
            message = plaintext.toUByteArrayCopy(),
            associatedData = associatedData.toUByteArrayCopy(),
            nonce = nonce.toUByteArrayCopy(),
            key = key.toUByteArrayCopy(),
        ).toByteArrayCopy()
        return AeadCiphertext(nonce = nonce.copyOf(), ciphertext = ciphertext)
    }

    fun decryptAead(
        key: ByteArray,
        associatedData: ByteArray,
        ciphertext: AeadCiphertext,
    ): CryptoResult<ByteArray> {
        if (key.size != WorkspaceMasterKey.WORKSPACE_KEY_BYTES) {
            return CryptoResult.AuthenticationFailed
        }
        if (ciphertext.nonce.size != crypto_aead_xchacha20poly1305_ietf_NPUBBYTES) {
            return CryptoResult.AuthenticationFailed
        }
        return try {
            CryptoResult.Success(
                AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                    ciphertextAndTag = ciphertext.ciphertext.toUByteArrayCopy(),
                    associatedData = associatedData.toUByteArrayCopy(),
                    nonce = ciphertext.nonce.toUByteArrayCopy(),
                    key = key.toUByteArrayCopy(),
                ).toByteArrayCopy(),
            )
        } catch (_: AeadCorrupedOrTamperedDataException) {
            CryptoResult.AuthenticationFailed
        } catch (_: RuntimeException) {
            CryptoResult.AuthenticationFailed
        }
    }

    private fun ensureInitialized() {
        if (LibsodiumInitializer.isInitialized()) return

        var callbackCompleted = false
        LibsodiumInitializer.initializeWithCallback {
            callbackCompleted = true
        }
        check(callbackCompleted || LibsodiumInitializer.isInitialized()) {
            "Libsodium initialization did not complete synchronously for this target."
        }
    }

    companion object {
        const val RECOVERY_ENTROPY_BYTES = 16
    }
}

internal fun RecoveryKdfPolicy.Companion.protocolV1(): RecoveryKdfPolicy =
    RecoveryKdfPolicy(
        opsLimit = RECOVERY_KDF_V1_OPS_LIMIT,
        memLimit = RECOVERY_KDF_V1_MEM_LIMIT,
        algorithm = crypto_pwhash_argon2id_ALG_ARGON2ID13,
    )

internal fun RecoveryKdfPolicy.Companion.forTests(): RecoveryKdfPolicy =
    RecoveryKdfPolicy(
        opsLimit = crypto_pwhash_OPSLIMIT_MIN,
        memLimit = crypto_pwhash_MEMLIMIT_MIN,
        algorithm = crypto_pwhash_argon2id_ALG_ARGON2ID13,
    )

internal const val RECOVERY_KDF_V1_OPS_LIMIT: ULong = 2UL
internal const val RECOVERY_KDF_V1_MEM_LIMIT: Int = 67_108_864

internal fun normalizeRecoveryMaterial(input: String): String =
    input
        .trim()
        .uppercase()
        .filter { it.isLetterOrDigit() }
