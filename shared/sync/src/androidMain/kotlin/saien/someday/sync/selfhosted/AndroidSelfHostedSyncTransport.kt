package saien.someday.sync.selfhosted

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

class AndroidSelfHostedSyncTransport(
    private val delegate: KtorSelfHostedSyncTransport = KtorSelfHostedSyncTransport(
        client = HttpClient(OkHttp) {
            configureSelfHostedHttpClient()
        },
    ),
) : SelfHostedSyncTransport by delegate,
    SelfHostedWorkspaceRecoveryTransport by delegate,
    SelfHostedSyncTransportV2 by delegate,
    SelfHostedMediaTransportV3 by delegate {
    fun close() {
        delegate.close()
    }
}
