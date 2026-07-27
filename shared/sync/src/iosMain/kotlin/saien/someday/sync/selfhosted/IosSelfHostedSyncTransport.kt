package saien.someday.sync.selfhosted

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

class IosSelfHostedSyncTransport(
    private val delegate: KtorSelfHostedSyncTransport = KtorSelfHostedSyncTransport(
        client = HttpClient(Darwin) {
            followRedirects = false
        },
    ),
) : SelfHostedSyncTransport by delegate, SelfHostedSyncTransportV2 by delegate
