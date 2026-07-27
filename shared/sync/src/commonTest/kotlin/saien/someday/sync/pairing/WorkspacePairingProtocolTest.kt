package saien.someday.sync.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.domain.settings.WorkspaceJoinPackage

class WorkspacePairingProtocolTest {
    @Test
    fun tokenGoldenVectorAndNormalization() {
        val token = WorkspacePairingToken.fromSecretBytes(ByteArray(16) { it.toByte() })

        assertEquals("000G40R40M30E209185GR38E1WRJ", token.manualToken())
        assertEquals("000G40R 40M30E2 09185GR 38E1WRJ", token.formattedManualToken())
        assertEquals("SOMEDAY:PAIR:1:000G40R40M30E209185GR38E1WRJ", token.qrPayload())
        assertEquals(token.manualToken(), WorkspacePairingToken.parse(token.formattedManualToken())?.manualToken())
        assertEquals(token.manualToken(), WorkspacePairingToken.parse(token.qrPayload().lowercase())?.manualToken())
        assertTrue(token.toString().contains("<redacted>"))
        assertNull(WorkspacePairingToken.parse(token.manualToken().dropLast(1) + "0"))
        assertNull(WorkspacePairingToken.parse("000G40R40M30E209185GR38E1W3\u2603"))
    }

    @Test
    fun hkdfGoldenVector() {
        val material = WorkspacePairingToken
            .fromSecretBytes(ByteArray(16) { it.toByte() })
            .deriveMaterial()

        assertEquals("f4Yo0a1Q85vFPJvFGsQYwg", material.inviteId)
        assertEquals(
            "d946242a3d6f6eb43bcaa5fa8d7046910deeed24e32519f8dfdf5b59515db0bf",
            material.envelopeKey.toHex(),
        )
        assertEquals(
            "78150e109eb945bbbdbea114fa20c7cdaaf19c4c045d8ac2b8b7a318e9926eec",
            material.stateKey.toHex(),
        )
        assertTrue(material.toString().contains("<redacted>"))
    }

    @Test
    fun envelopeRoundTripBindsProfileAndAuthority() {
        val crypto = SodiumWorkspaceCrypto()
        val token = WorkspacePairingToken.fromSecretBytes(ByteArray(16) { (it + 3).toByte() })
        val codec = WorkspacePairingEnvelopeCodec(crypto)
        val authority = WorkspacePairingAuthority(
            remoteProfile = WorkspacePairingRemoteProfile.WebDav,
            binding = "https://dav.example.test|alice|someday",
        )
        val packageData = WorkspaceJoinPackage(
            metadataJson = """{"workspaceId":"workspace-a"}""",
            recoveryCode = "SOMEDAY-RECOVERY",
            workspaceId = "workspace-a",
            keyFingerprint = "fingerprint-a",
        )
        val encoded = codec.encode(
            token = token,
            authority = authority,
            createdAtEpochMillis = 1_000,
            expiresAtEpochMillis = 601_000,
            packageData = packageData,
        )

        val decoded = assertIs<WorkspacePairingEnvelopeDecodeResult.Success>(
            codec.decode(
                token = token,
                authority = authority,
                envelopeBytes = encoded.bytes,
                nowEpochMillis = 2_000,
            ),
        )
        assertEquals(packageData, decoded.packageData)
        assertEquals(encoded.digest, decoded.envelopeDigest)
        assertIs<WorkspacePairingEnvelopeDecodeResult.Invalid>(
            codec.decode(
                token = token,
                authority = authority.copy(binding = authority.binding + "-other"),
                envelopeBytes = encoded.bytes,
                nowEpochMillis = 2_000,
            ),
        )
        assertIs<WorkspacePairingEnvelopeDecodeResult.Invalid>(
            codec.decode(
                token = token,
                authority = WorkspacePairingAuthority(
                    WorkspacePairingRemoteProfile.SelfHosted,
                    authority.binding,
                ),
                envelopeBytes = encoded.bytes,
                nowEpochMillis = 2_000,
            ),
        )
        assertIs<WorkspacePairingEnvelopeDecodeResult.Expired>(
            codec.decode(
                token = token,
                authority = authority,
                envelopeBytes = encoded.bytes,
                nowEpochMillis = 601_000,
            ),
        )

        val duplicateFormat = encoded.bytes
            .decodeToString()
            .replaceFirst(
                "{",
                """{"format":"${WorkspacePairingEnvelopeCodec.ENVELOPE_FORMAT}",""",
            )
            .encodeToByteArray()
        assertIs<WorkspacePairingEnvelopeDecodeResult.Invalid>(
            codec.decode(
                token = token,
                authority = authority,
                envelopeBytes = duplicateFormat,
                nowEpochMillis = 2_000,
            ),
        )
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
