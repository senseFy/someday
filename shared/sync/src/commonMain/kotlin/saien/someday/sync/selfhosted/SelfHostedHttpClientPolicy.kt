package saien.someday.sync.selfhosted

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpTimeout

internal const val SELF_HOSTED_CONNECT_TIMEOUT_MILLIS: Long = 10_000
internal const val SELF_HOSTED_SOCKET_TIMEOUT_MILLIS: Long = 60_000
internal const val SELF_HOSTED_REQUEST_TIMEOUT_MILLIS: Long = 120_000

/**
 * Applies one timeout policy to every Ktor-backed self-hosted transport.
 *
 * The connect budget is used by engines that expose a separate connection
 * phase. The socket budget limits a continuous period without network I/O,
 * while the request budget caps one HTTP exchange. A multi-batch sync receives
 * a fresh request budget for each batch.
 */
internal fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureSelfHostedHttpClient() {
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = SELF_HOSTED_CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = SELF_HOSTED_SOCKET_TIMEOUT_MILLIS
        requestTimeoutMillis = SELF_HOSTED_REQUEST_TIMEOUT_MILLIS
    }
}
