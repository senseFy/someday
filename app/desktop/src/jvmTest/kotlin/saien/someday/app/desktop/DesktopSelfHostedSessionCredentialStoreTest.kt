package saien.someday.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.decodeSelfHostedSessionCredentials
import saien.someday.domain.settings.encodeForSecureStorage

class DesktopSelfHostedSessionCredentialStoreTest {
    @Test
    fun currentSingleLineKeychainOutputPassesThrough() {
        val encoded = credentials().encodeForSecureStorage()

        assertEquals(encoded, normalizeSelfHostedKeychainOutput("$encoded\n"))
    }

    @Test
    fun hexadecimalLegacyKeychainOutputIsRecovered() {
        val credentials = credentials()
        val securityOutput = legacyFixture.encodeToByteArray().joinToString(separator = "") { byte ->
            byte.toUByte().toString(radix = 16).padStart(2, '0')
        }

        val normalized = normalizeSelfHostedKeychainOutput("$securityOutput\n")

        assertEquals(legacyFixture, normalized)
        assertEquals(credentials, normalized?.let(::decodeSelfHostedSessionCredentials))
    }

    @Test
    fun unrelatedOrEmptyKeychainOutputRemainsFailClosed() {
        val unrelated = normalizeSelfHostedKeychainOutput("deadbeef\n")

        assertEquals("deadbeef", unrelated)
        assertNull(unrelated?.let(::decodeSelfHostedSessionCredentials))
        assertNull(normalizeSelfHostedKeychainOutput("\n"))
    }

    private fun credentials() = SelfHostedSessionCredentials(
        endpoint = "https://sync.example.test",
        userId = "user-123",
        userEmail = "person@example.test",
        deviceId = "device-456",
        deviceName = "MacBook",
        devicePlatform = "desktop",
        accessToken = "access-token",
        refreshToken = "refresh-token",
    )

    private companion object {
        val legacyFixture =
            """c2VsZi1ob3N0ZWQtc2Vzc2lvbi12Mg==
            |aHR0cHM6Ly9zeW5jLmV4YW1wbGUudGVzdA==
            |dXNlci0xMjM=
            |cGVyc29uQGV4YW1wbGUudGVzdA==
            |ZGV2aWNlLTQ1Ng==
            |TWFjQm9vaw==
            |ZGVza3RvcA==
            |YWNjZXNzLXRva2Vu
            |cmVmcmVzaC10b2tlbg==""".trimMargin()
    }
}
