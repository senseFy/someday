package saien.someday.sync.causality.v2

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SyncEpochKeyDerivationV2Test {
    @Test
    fun syncKeySetV2MatchesCrossPlatformGoldenVector() {
        val crypto = SodiumWorkspaceCrypto()
        val workspaceKey = crypto.workspaceKeyFromBytes(ByteArray(32) { it.toByte() })
        val derivation = SyncEpochKeyDerivationV2(crypto)

        val first = derivation.derive(workspaceKey, EPOCH_ONE)
        val replay = derivation.derive(workspaceKey, EPOCH_ONE)
        val nextEpoch = derivation.derive(workspaceKey, EPOCH_TWO)

        assertEquals(EXPECTED_CONVERGENCE_KEY_HEX, first.convergenceKey.hex())
        assertEquals(EXPECTED_OBJECT_DIGEST_KEY_HEX, first.objectDigestKey.hex())
        assertEquals(first.convergenceKey.hex(), replay.convergenceKey.hex())
        assertEquals(first.objectDigestKey.hex(), replay.objectDigestKey.hex())
        assertFalse(first.convergenceKey.contentEquals(first.objectDigestKey))
        assertFalse(first.convergenceKey.contentEquals(nextEpoch.convergenceKey))
        assertFalse(first.objectDigestKey.contentEquals(nextEpoch.objectDigestKey))
    }

    private companion object {
        const val EPOCH_ONE = "11111111-1111-4111-8111-111111111111"
        const val EPOCH_TWO = "22222222-2222-4222-8222-222222222222"
        const val EXPECTED_CONVERGENCE_KEY_HEX = "7c024947fec6f47c2e03dbbe8e2ff35f3e6707a824b71ab2aa276c957bd6130a"
        const val EXPECTED_OBJECT_DIGEST_KEY_HEX = "2b0e7c6520449de72c7a6f817fdec69abfdab09174b8bed8ae5c269997c766c0"
    }
}

private fun ByteArray.hex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
