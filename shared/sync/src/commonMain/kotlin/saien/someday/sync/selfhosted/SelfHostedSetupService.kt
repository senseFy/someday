package saien.someday.sync.selfhosted

import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SelfHostedSetupResult
import saien.someday.domain.settings.SelfHostedSetupStatus
import saien.someday.domain.settings.selfHostedV2AuthorityBindingId

class SelfHostedSetupService(
    private val transport: SelfHostedSyncTransport,
    private val sessionStore: SelfHostedSessionCredentialStore,
) : SelfHostedSetupClient {
    override fun setup(input: SelfHostedSetupInput): SelfHostedSetupResult =
        runCatching {
            val sanitized = input.sanitized()
            val client = SelfHostedSyncClient(
                endpoint = sanitized.endpoint,
                transport = transport,
            )
            val session = if (sanitized.createAccount) {
                client.registerAndConnect(
                    email = sanitized.email,
                    password = sanitized.password,
                    deviceName = sanitized.deviceName,
                    platform = sanitized.platform,
                )
            } else {
                client.loginAndConnect(
                    email = sanitized.email,
                    password = sanitized.password,
                    deviceName = sanitized.deviceName,
                    platform = sanitized.platform,
                )
            }
            val credentials = session.toCredentials()
            sessionStore.load()?.let { previous ->
                sessionStore.saveForAuthority(selfHostedV2AuthorityBindingId(previous.endpoint), previous)
            }
            sessionStore.save(credentials)
            sessionStore.saveForAuthority(selfHostedV2AuthorityBindingId(credentials.endpoint), credentials)
            SelfHostedSetupResult.success(
                status = SelfHostedSetupStatus(
                    ready = true,
                    message = "Self-hosted account session and device are ready; password/token values redacted.",
                ),
                session = credentials.toSummary(),
            )
        }.getOrElse { failure ->
            SelfHostedSetupResult.failure(
                "Self-hosted setup failed: ${failure.message ?: "unknown error"}; password/token values redacted.",
            )
        }
}
