package saien.someday.sync.selfhosted

import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSetupClient
import saien.someday.domain.settings.SelfHostedSetupInput
import saien.someday.domain.settings.SelfHostedSetupReason
import saien.someday.domain.settings.SelfHostedSetupResult
import saien.someday.domain.settings.SelfHostedSetupStatus
import saien.someday.domain.settings.authorityBindingId
import saien.someday.domain.settings.parseSelfHostedAuthorityBindingId
import saien.someday.sync.WorkspaceLifecycleCoordinator

class SelfHostedSetupService(
    private val transport: SelfHostedSyncTransport,
    private val sessionStore: SelfHostedSessionCredentialStore,
    private val activeWorkspaceSessionGuard: ActiveWorkspaceSessionGuard,
    private val workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator,
    private val localDeviceIdProvider: () -> String,
) : SelfHostedSetupClient {
    override fun setup(input: SelfHostedSetupInput): SelfHostedSetupResult =
        runCatching {
            val sanitized = input.sanitized()
            workspaceLifecycleCoordinator.exclusive {
                val requirement = activeWorkspaceSessionGuard.currentRequirement()
                if (requirement != null) {
                    if (sanitized.createAccount) {
                        return@exclusive SelfHostedSetupResult.failure(
                            SelfHostedSetupReason.AccountChangeBlocked,
                        )
                    }
                    val binding = parseSelfHostedAuthorityBindingId(requirement.authorityBindingId)
                        ?: return@exclusive SelfHostedSetupResult.failure(
                            SelfHostedSetupReason.AuthorityInvalid,
                        )
                    if (binding.endpoint != sanitized.endpoint) {
                        return@exclusive SelfHostedSetupResult.failure(
                            SelfHostedSetupReason.EndpointMismatch,
                        )
                    }
                    val credentials = SelfHostedSyncClient(
                        endpoint = binding.endpoint,
                        transport = transport,
                    ).loginAndReconnectBound(
                        email = sanitized.email,
                        password = sanitized.password,
                        deviceName = sanitized.deviceName,
                        platform = sanitized.platform,
                        expectedUserId = binding.authenticatedUserId,
                        stableDeviceId = requirement.localWriterDeviceId,
                    ).toCredentials()
                    activeWorkspaceSessionGuard.requireCompatible(credentials)
                    sessionStore.saveForAuthority(requirement.authorityBindingId, credentials)
                    sessionStore.save(credentials)
                    return@exclusive readyResult(
                        credentials,
                        SelfHostedSetupReason.BoundSessionRenewed,
                    )
                }

                // First setup owns the authority lock through account auth and
                // device registration, so first-epoch publication cannot bind
                // a different session in the middle of this operation.
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
                        localDeviceId = localDeviceIdProvider(),
                    )
                } else {
                    client.loginAndConnect(
                        email = sanitized.email,
                        password = sanitized.password,
                        deviceName = sanitized.deviceName,
                        platform = sanitized.platform,
                        localDeviceId = localDeviceIdProvider(),
                    )
                }
                val credentials = session.toCredentials()
                activeWorkspaceSessionGuard.requireCompatible(credentials)
                sessionStore.load()?.let { previous ->
                    sessionStore.saveForAuthority(previous.authorityBindingId, previous)
                }
                sessionStore.saveForAuthority(credentials.authorityBindingId, credentials)
                sessionStore.save(credentials)
                readyResult(
                    credentials,
                    SelfHostedSetupReason.Ready,
                )
            }
        }.getOrElse { failure ->
            SelfHostedSetupResult.failure(
                reason = SelfHostedSetupReason.Failed,
                diagnosticMessage =
                    "Self-hosted setup failed: ${failure.message ?: "unknown error"}; password/token values redacted.",
            )
        }

    private fun readyResult(
        credentials: saien.someday.domain.settings.SelfHostedSessionCredentials,
        reason: SelfHostedSetupReason,
    ): SelfHostedSetupResult = SelfHostedSetupResult.success(
        status = SelfHostedSetupStatus(ready = true, reason = reason),
        session = credentials.toSummary(),
    )
}
