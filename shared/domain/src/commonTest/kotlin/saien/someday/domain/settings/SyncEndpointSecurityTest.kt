package saien.someday.domain.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncEndpointSecurityTest {
    @Test
    fun plaintextHttpIsLimitedToTrueLoopbackHosts() {
        assertTrue(isSecureSyncEndpoint("https://sync.example.test"))
        assertTrue(isSecureSyncEndpoint("http://localhost:3180"))
        assertTrue(isSecureSyncEndpoint("http://api.localhost:3180"))
        assertTrue(isSecureSyncEndpoint("http://127.9.8.7:3180"))
        assertTrue(isSecureSyncEndpoint("http://[::1]:3180"))

        assertFalse(isSecureSyncEndpoint("http://sync.example.test"))
        assertFalse(isSecureSyncEndpoint("http://localhost.example.test"))
        assertFalse(isSecureSyncEndpoint("http://10.0.2.2:3180"))
        assertFalse(isSecureSyncEndpoint("http://user@localhost:3180"))
        assertFalse(isSecureSyncEndpoint("http://[::1].example.test"))
        assertFalse(isSecureSyncEndpoint("http://[::1]suffix"))
        assertFalse(isSecureSyncEndpoint("http://[::1"))
        assertFalse(isSecureSyncEndpoint("http://localhost:not-a-port"))
        assertFalse(isSecureSyncEndpoint("http://localhost:65536"))
        assertFalse(isSecureSyncEndpoint("ftp://localhost"))
    }

    @Test
    fun providerValidationRejectsNonLoopbackPlaintextBeforeCredentialsAreUsed() {
        assertTrue(
            WebDavConnectionInput(
                endpoint = "http://dav.example.test",
                username = "alice",
                password = "redacted-test-value",
            ).validate().any { "requires HTTPS" in it },
        )
        assertTrue(
            SelfHostedSetupInput(
                endpoint = "http://sync.example.test",
                email = "alice@example.test",
                password = "redacted-test-password",
                deviceName = "test",
                platform = "test",
                createAccount = false,
            ).validate().any { "requires HTTPS" in it },
        )
    }

    @Test
    fun authorityCredentialEncodingBindsEndpointWithoutExposingItInTheBindingSecret() {
        val binding = webDavV2AuthorityBindingId("https://dav.example.test/", "someday")
        val original = WebDavAuthorityCredentials(
            binding,
            "https://dav.example.test",
            "alice",
            "/someday/",
            "test-secret-not-for-logs",
        )

        val decoded = assertNotNull(decodeWebDavAuthorityCredentials(original.encodeForSecureStorage()))

        assertEquals(original, decoded)
        assertEquals("webdav-log-v2|https://dav.example.test|/someday/", binding)
        assertFalse(original.encodeForSecureStorage().contains(original.secret))

        val rotatedCredential = original.copy(secret = "rotated-test-secret")
        assertEquals(binding, rotatedCredential.authorityBindingId)
        assertEquals(
            rotatedCredential,
            decodeWebDavAuthorityCredentials(rotatedCredential.encodeForSecureStorage()),
        )
        assertFalse(rotatedCredential.encodeForSecureStorage().contains(rotatedCredential.secret))
    }
}
