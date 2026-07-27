@file:OptIn(ExperimentalUnsignedTypes::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package saien.someday.data.crypto

import kotlin.io.encoding.Base64

class WorkspaceMasterKey private constructor(
    private val keyBytes: ByteArray,
    val fingerprint: String,
) {
    init {
        require(keyBytes.size == WORKSPACE_KEY_BYTES) { "Workspace keys must be $WORKSPACE_KEY_BYTES bytes." }
        require(fingerprint.isNotBlank()) { "Workspace key fingerprint must be present." }
    }

    internal fun rawBytesCopy(): ByteArray = keyBytes.copyOf()

    internal fun copy(): WorkspaceMasterKey = WorkspaceMasterKey(keyBytes.copyOf(), fingerprint)

    override fun toString(): String = "WorkspaceMasterKey(fingerprint=$fingerprint, material=<redacted>)"

    companion object {
        const val WORKSPACE_KEY_BYTES = 32

        internal fun fromBytes(
            keyBytes: ByteArray,
            fingerprint: String,
        ): WorkspaceMasterKey = WorkspaceMasterKey(keyBytes.copyOf(), fingerprint)
    }
}

class RecoveryMaterial private constructor(
    private val code: String,
) {
    fun revealForUserConfirmation(): String = code

    override fun toString(): String = "RecoveryMaterial(<redacted>)"

    companion object {
        internal fun fromCode(code: String): RecoveryMaterial {
            require(code.isNotBlank()) { "Recovery material must not be blank." }
            return RecoveryMaterial(code)
        }
    }
}

class RecoveryMaterialDisplaySession(
    private val recoveryMaterial: RecoveryMaterial,
) {
    private var revealed: Boolean = false

    fun revealForExplicitUserConfirmation(): String {
        revealed = true
        return recoveryMaterial.revealForUserConfirmation()
    }

    fun visibleRecoveryMaterial(): String? =
        if (revealed) {
            recoveryMaterial.revealForUserConfirmation()
        } else {
            null
        }

    fun dismiss() {
        revealed = false
    }

    fun verify(
        candidate: String,
        verifier: (String) -> Boolean,
    ): Boolean =
        verifier(candidate)

    override fun toString(): String =
        "RecoveryMaterialDisplaySession(visible=$revealed, recoveryMaterial=<redacted>)"
}

data class FirstRunWorkspaceSetup(
    val state: WorkspaceUnlockState.Unlocked,
    val recoveryMaterial: RecoveryMaterial,
    val secureStorageAlias: String,
    val metadataJson: String,
)

sealed interface WorkspaceJoinPackageResult {
    data class Created(
        val metadataJson: String,
        val recoveryMaterial: RecoveryMaterial,
        val workspaceId: String,
        val keyFingerprint: String,
    ) : WorkspaceJoinPackageResult

    data class Failed(
        val reason: WorkspaceUnlockFailure,
    ) : WorkspaceJoinPackageResult
}

sealed interface WorkspaceUnlockState {
    data object Uninitialized : WorkspaceUnlockState

    data class Locked(
        val workspaceId: String,
        val secureStorageAvailable: Boolean,
    ) : WorkspaceUnlockState

    data class Unlocked(
        val workspaceId: String,
        val keyFingerprint: String,
    ) : WorkspaceUnlockState
}

sealed interface WorkspaceUnlockResult {
    data class Unlocked(
        val state: WorkspaceUnlockState.Unlocked,
    ) : WorkspaceUnlockResult

    data class Failed(
        val reason: WorkspaceUnlockFailure,
    ) : WorkspaceUnlockResult
}

sealed interface WorkspaceRestoreResult {
    data class Restored(
        val state: WorkspaceUnlockState.Unlocked,
        val secureStorageAlias: String,
    ) : WorkspaceRestoreResult

    data class Failed(
        val reason: WorkspaceUnlockFailure,
    ) : WorkspaceRestoreResult
}

enum class WorkspaceUnlockFailure {
    NO_WORKSPACE,
    WORKSPACE_ALREADY_EXISTS,
    SECURE_STORAGE_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    INVALID_METADATA,
}

enum class WorkspaceSubkey(
    val subkeyId: UInt,
    val context: String,
) {
    OBJECTS(1U, "SMDYOBJ1"),
    METADATA(2U, "SMDYMTA1"),
    AUTHENTICATION(3U, "SMDYAUTH"),
    WRAPPING(4U, "SMDYWRP1"),
    /** Protocol-frozen base key for sync-key-set-v2 convergence-key expansion. */
    SYNC_V2_CONVERGENCE(5U, "SMDYS2CV"),
    /** Protocol-frozen base key for sync-key-set-v2 object-digest-key expansion. */
    SYNC_V2_OBJECT_DIGEST(6U, "SMDYS2OD"),
}

data class RecoveryKdfPolicy(
    val opsLimit: ULong,
    val memLimit: Int,
    val algorithm: Int,
) {
    companion object
}

sealed interface CryptoResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : CryptoResult<T>

    data object AuthenticationFailed : CryptoResult<Nothing>

    data object InvalidCiphertext : CryptoResult<Nothing>
}

data class AeadCiphertext(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun toString(): String = "AeadCiphertext(nonceBytes=${nonce.size}, ciphertextBytes=${ciphertext.size})"
}

internal fun ByteArray.toUByteArrayCopy(): UByteArray =
    UByteArray(size) { index -> this[index].toUByte() }

internal fun UByteArray.toByteArrayCopy(): ByteArray =
    ByteArray(size) { index -> this[index].toByte() }

internal fun ByteArray.base64(): String = Base64.encode(this)

internal fun String.decodeBase64Bytes(): ByteArray = Base64.decode(this)

internal fun ByteArray.hex(): String =
    joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

internal fun WorkspaceMasterKey.rawKeyBase64ForTest(): String = rawBytesCopy().base64()

internal fun WorkspaceMasterKey.rawKeyHexForTest(): String = rawBytesCopy().hex()
