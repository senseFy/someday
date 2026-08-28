package saien.someday.sync.selfhosted

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

class IosSelfHostedSyncTransport(
    private val delegate: KtorSelfHostedSyncTransport = KtorSelfHostedSyncTransport(
        client = HttpClient(Darwin) {
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
        },
    ),
) : SelfHostedSyncTransport by delegate,
    SelfHostedSyncTransportV2 by delegate,
    SelfHostedMediaTransportV3 by delegate {
    fun close() {
        delegate.close()
    }
}
