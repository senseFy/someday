package saien.someday.sync.selfhosted

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

class AndroidSelfHostedSyncTransport(
    private val delegate: KtorSelfHostedSyncTransport = KtorSelfHostedSyncTransport(
        client = HttpClient(OkHttp) {
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
