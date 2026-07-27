package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.crypto.WorkspaceMasterKey
import saien.someday.data.crypto.WorkspaceSubkey
import okio.ByteString.Companion.toByteString

const val SYNC_KEY_SET_VERSION_V2: String = "sync-key-set-v2"

class SyncEpochKeysV2(
    convergenceKey: ByteArray,
    objectDigestKey: ByteArray,
) {
    val convergenceKey: ByteArray = convergenceKey.copyOf()
    val objectDigestKey: ByteArray = objectDigestKey.copyOf()
}

/**
 * Wire-frozen derivation for an epoch's immutable v2 semantic keys.
 *
 * The libsodium KDF subkeys are stable workspace-scoped base keys. HMAC then
 * expands each base with deterministic CBOR containing the epoch and purpose.
 * Rewrapping a workspace key therefore leaves an existing epoch unchanged,
 * while a new epoch gets a disjoint key set.
 */
class SyncEpochKeyDerivationV2(
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
) {
    fun derive(
        workspaceKey: WorkspaceMasterKey,
        syncEpochId: String,
        keySetVersion: String = SYNC_KEY_SET_VERSION_V2,
    ): SyncEpochKeysV2 {
        require(syncEpochId.isValidProtocolIdentifierV2()) { "V2 sync epoch id has an invalid shape." }
        require(keySetVersion == SYNC_KEY_SET_VERSION_V2) { "Unsupported v2 sync key-set version." }

        return SyncEpochKeysV2(
            convergenceKey = expand(
                workspaceKey = workspaceKey,
                subkey = WorkspaceSubkey.SYNC_V2_CONVERGENCE,
                syncEpochId = syncEpochId,
                keySetVersion = keySetVersion,
                purpose = "workspace-convergence-key",
            ),
            objectDigestKey = expand(
                workspaceKey = workspaceKey,
                subkey = WorkspaceSubkey.SYNC_V2_OBJECT_DIGEST,
                syncEpochId = syncEpochId,
                keySetVersion = keySetVersion,
                purpose = "workspace-object-digest-key",
            ),
        )
    }

    private fun expand(
        workspaceKey: WorkspaceMasterKey,
        subkey: WorkspaceSubkey,
        syncEpochId: String,
        keySetVersion: String,
        purpose: String,
    ): ByteArray {
        val baseKey = crypto.deriveSubkey(workspaceKey, subkey)
        val input = DeterministicCborV2.encode(
            cborMap(
                "domain" to cborText("someday-sync-epoch-key-derivation-v2"),
                "keySetVersion" to cborText(keySetVersion),
                "purpose" to cborText(purpose),
                "syncEpochId" to cborText(syncEpochId),
            ),
        )
        return input.toByteString().hmacSha256(baseKey.toByteString()).toByteArray()
    }
}

internal fun String.isValidProtocolIdentifierV2(maxBytes: Int = 256): Boolean =
    isNotBlank() && encodeToByteArray().size <= maxBytes && all { character ->
        character.isLetterOrDigit() || character == '-' || character == '_' || character == ':' || character == '.'
    }
