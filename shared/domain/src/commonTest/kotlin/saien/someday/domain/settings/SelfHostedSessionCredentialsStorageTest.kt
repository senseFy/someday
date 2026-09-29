@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package saien.someday.domain.settings

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfHostedSessionCredentialsStorageTest {
    @Test
    fun secureStorageEncodingIsSingleLineAndRoundTripsEveryField() {
        val credentials = credentials()

        val encoded = credentials.encodeForSecureStorage()

        assertTrue(encoded.startsWith("self-hosted-session-v4:"))
        assertFalse(encoded.contains('\n'))
        assertFalse(encoded.contains('\r'))
        assertTrue(encoded.all { it.code in 0x20..0x7e })
        assertEquals(credentials, decodeSelfHostedSessionCredentials(encoded))
    }

    @Test
    fun legacyMultilinePayloadStillDecodes() {
        val credentials = credentials()

        assertEquals(credentials, decodeSelfHostedSessionCredentials(legacyEncoding(credentials)))
    }

    @Test
    fun oldSingleLineEnvelopeRetainsG0WithoutInventingProtocolEvidence() {
        val old = "self-hosted-session-v3:${Base64.encode(legacyEncoding(credentials()).encodeToByteArray())}"
        val restored = decodeSelfHostedSessionCredentials(old)
        assertEquals(INITIAL_ACCOUNT_INCARNATION, restored?.accountIncarnation)
        assertNull(restored?.accountProtocolVersion)
        assertEquals(credentials(), restored)
    }

    @Test
    fun issuedIncarnationAndProtocolEvidenceSurviveSecureStorageReopening() {
        val issued = credentials().copy(
            accountIncarnation = "11111111-1111-4111-8111-111111111111",
            accountProtocolVersion = 1,
        )
        assertEquals(issued, decodeSelfHostedSessionCredentials(issued.encodeForSecureStorage()))
        val g0Protocol1 = credentials().copy(accountProtocolVersion = 1)
        assertEquals(g0Protocol1, decodeSelfHostedSessionCredentials(g0Protocol1.encodeForSecureStorage()))
        assertFalse(issued.toString().contains(issued.accessToken))
        assertFalse(issued.toString().contains(issued.refreshToken))
    }

    @Test
    fun invalidNewCredentialMetadataNeverFallsBackToLegacyG0() {
        val issued = credentials().copy(accountProtocolVersion = 1)
        val payload = Base64.decode(issued.encodeForSecureStorage().substringAfter(':')).decodeToString().split('\n')
        fun malformed(index: Int, value: String): String {
            val fields = payload.toMutableList()
            fields[index] = Base64.encode(value.encodeToByteArray())
            return "self-hosted-session-v4:${Base64.encode(fields.joinToString("\n").encodeToByteArray())}"
        }
        assertNull(decodeSelfHostedSessionCredentials(malformed(9, "not-an-incarnation")))
        assertNull(decodeSelfHostedSessionCredentials(malformed(10, "2")))
        assertNull(decodeSelfHostedSessionCredentials("self-hosted-session-v5:${issued.encodeForSecureStorage().substringAfter(':')}"))
        assertNull(decodeSelfHostedSessionCredentials("self-hosted-session-v4:${Base64.encode(legacyEncoding(issued).encodeToByteArray())}"))
    }

    @Test
    fun blankOptionalDeviceLabelsRetainTheirFieldPositions() {
        val credentials = credentials().copy(deviceName = "", devicePlatform = "")

        assertEquals(credentials, decodeSelfHostedSessionCredentials(credentials.encodeForSecureStorage()))
        assertEquals(credentials, decodeSelfHostedSessionCredentials(legacyEncoding(credentials)))
    }

    @Test
    fun malformedEnvelopeFailsClosed() {
        assertNull(decodeSelfHostedSessionCredentials("self-hosted-session-v3:not-base64"))
        assertNull(
            decodeSelfHostedSessionCredentials(
                "self-hosted-session-v3:${Base64.encode(byteArrayOf(0xc3.toByte(), 0x28))}",
            ),
        )
    }

    private fun credentials() = SelfHostedSessionCredentials(
        endpoint = "https://sync.example.test",
        userId = "user-123",
        userEmail = "person@example.test",
        deviceId = "device-456",
        deviceName = "MacBook 日本語\nSecond line",
        devicePlatform = "desktop",
        accessToken = "access-token:\nwith-delimiters",
        refreshToken = "refresh-token:\nwith-delimiters",
    )

    private fun legacyEncoding(credentials: SelfHostedSessionCredentials): String =
        listOf(
            "self-hosted-session-v2",
            credentials.endpoint,
            credentials.userId,
            credentials.userEmail,
            credentials.deviceId,
            credentials.deviceName,
            credentials.devicePlatform,
            credentials.accessToken,
            credentials.refreshToken,
        ).joinToString(separator = "\n") { Base64.encode(it.encodeToByteArray()) }
}
