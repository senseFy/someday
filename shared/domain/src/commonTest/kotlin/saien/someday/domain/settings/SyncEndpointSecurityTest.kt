package saien.someday.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
        assertFalse(isSecureSyncEndpoint("https://user:password@sync.example.test"))
        assertFalse(isSecureSyncEndpoint("https://sync.example.test/base-path"))
        assertFalse(isSecureSyncEndpoint("https://sync.example.test?token=redacted"))
        assertFalse(isSecureSyncEndpoint("https://sync.example.test#fragment"))
        assertFalse(isSecureSyncEndpoint("https://sync.example.test:0443"))
        assertFalse(isSecureSyncEndpoint("https://sync_example.test"))
    }

    @Test
    fun providerValidationRejectsNonLoopbackPlaintextBeforeCredentialsAreUsed() {
        assertTrue(
            SelfHostedSetupInput(
                endpoint = "http://sync.example.test",
                email = "alice@example.test",
                password = "redacted-test-password",
                deviceName = "test",
                platform = "test",
                createAccount = false,
            ).validate().contains(SelfHostedSetupValidationIssue.HttpsRequired),
        )
    }

    @Test
    fun selfHostedAuthorityBindsCanonicalEndpointAndAuthenticatedAccount() {
        assertEquals(
            selfHostedAuthorityBindingId("HTTPS://Sync.Example.Test:443/", "user-1"),
            selfHostedAuthorityBindingId("https://sync.example.test", "user-1"),
        )
        assertNotEquals(
            selfHostedAuthorityBindingId("https://sync.example.test", "user-1"),
            selfHostedAuthorityBindingId("https://sync.example.test", "user-2"),
        )
        assertNotEquals(
            selfHostedAuthorityBindingId("https://sync-a.example.test", "user-1"),
            selfHostedAuthorityBindingId("https://sync-b.example.test", "user-1"),
        )
        assertEquals(
            "https://sync.example.test",
            normalizeSelfHostedEndpoint(" HTTPS://Sync.Example.Test:443/ "),
        )
        assertEquals(
            SelfHostedAuthorityBinding("https://sync.example.test", "user-用户"),
            parseSelfHostedAuthorityBindingId(
                selfHostedAuthorityBindingId("https://sync.example.test", "user-用户"),
            ),
        )
        assertEquals(null, parseSelfHostedAuthorityBindingId("self-hosted|4:http|6:user-1"))
    }
}
