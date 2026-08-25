package saien.someday.sync

import saien.someday.sync.selfhosted.JdkSelfHostedSyncTransport
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SelfHostedTransportTlsFailureV2Test {
    @Test
    fun certificateAndHostnameFailuresBlockSelfHostedTokensBeforeHttpDispatch() {
        val fixture = LocalTlsFixture()
        try {
            val untrustedFailure = assertFails {
                JdkSelfHostedSyncTransport().v2Capabilities(
                    "https://localhost:${fixture.port}",
                    "certificate-token-sentinel",
                )
            }
            assertTrue(untrustedFailure.hasTlsHandshakeCause())
            assertEquals(0, fixture.httpDispatches.get())

            val trustedClient = HttpClient.newBuilder()
                .sslContext(fixture.clientTrustingOnlyFixtureCertificate())
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            val hostnameFailure = assertFails {
                JdkSelfHostedSyncTransport(trustedClient).v2Capabilities(
                    "https://127.0.0.1:${fixture.port}",
                    "hostname-token-sentinel",
                )
            }
            assertTrue(hostnameFailure.hasTlsHandshakeCause())
            assertEquals(0, fixture.httpDispatches.get())
        } finally {
            fixture.close()
        }
    }

    private fun Throwable.hasTlsHandshakeCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is SSLHandshakeException }

    private class LocalTlsFixture {
        private val password = "changeit".toCharArray()
        private val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(Base64.getDecoder().decode(TEST_SERVER_PKCS12_BASE64)), password)
        }
        private val serverContext = SSLContext.getInstance("TLS").apply {
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
            init(keyManagers.keyManagers, null, SecureRandom())
        }
        val httpDispatches = AtomicInteger(0)
        private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverContext)
            createContext("/") { exchange ->
                httpDispatches.incrementAndGet()
                exchange.requestBody.use { it.readBytes() }
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        val port: Int
            get() = server.address.port

        fun clientTrustingOnlyFixtureCertificate(): SSLContext = SSLContext.getInstance("TLS").apply {
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            init(null, trustManagers.trustManagers, SecureRandom())
        }

        fun close() {
            server.stop(0)
        }
    }

    private companion object {
        // Test-only PKCS#12: self-signed certificate for wrong.example. The
        // private key protects no data and exists only to exercise the real
        // JDK TLS verifier against an untrusted cert and a hostname mismatch.
        const val TEST_SERVER_PKCS12_BASE64 =
            "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"
    }
}
