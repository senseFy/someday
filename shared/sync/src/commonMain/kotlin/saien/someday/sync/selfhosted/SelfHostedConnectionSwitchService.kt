package saien.someday.sync.selfhosted

import saien.someday.data.crypto.WorkspaceKeyRepository
import saien.someday.data.local.SqlDelightLocalDataRepository
import saien.someday.data.settings.ClientSettingsRepository
import saien.someday.domain.settings.SelfHostedConnectionSwitchResult
import saien.someday.domain.settings.SelfHostedConnectionSwitcher
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.authorityBindingId
import saien.someday.domain.settings.resetBoundWorkspaceForConnectionSwitch
import saien.someday.domain.settings.resetUnboundSelfHostedConnection
import saien.someday.sync.WorkspaceLifecycleCoordinator
import saien.someday.sync.causality.v2.ensureWorkspaceLocalDraftV2

/**
 * Safely releases the current self-hosted authority before the UI accepts a
 * different server or account.
 *
 * An unbound draft keeps its local contents. Once publication has bound the
 * workspace, switching creates a fresh workspace instead of rebinding its DAG
 * and writer identity to another authority.
 */
class SelfHostedConnectionSwitchService(
    private val localRepository: SqlDelightLocalDataRepository,
    private val settingsRepository: ClientSettingsRepository,
    private val workspaceKeyRepository: WorkspaceKeyRepository,
    private val sessionStore: SelfHostedSessionCredentialStore,
    private val activeWorkspaceSessionGuard: ActiveWorkspaceSessionGuard,
    private val workspaceLifecycleCoordinator: WorkspaceLifecycleCoordinator,
    private val discardLocalWorkspaceForReplacement: () -> Boolean,
    private val finalizeLocalWorkspaceReplacement: () -> Unit,
    private val deviceName: String,
    private val platform: String,
) : SelfHostedConnectionSwitcher {
    override fun switchConnection(): SelfHostedConnectionSwitchResult =
        runCatching {
            workspaceLifecycleCoordinator.exclusive {
                workspaceLifecycleCoordinator.productAccess {
                    switchConnectionLocked()
                }
            }
        }.getOrElse {
            SelfHostedConnectionSwitchResult.failure()
        }

    private fun switchConnectionLocked(): SelfHostedConnectionSwitchResult {
        val previousSession = sessionStore.load()
        val workspaceBound = activeWorkspaceSessionGuard.currentRequirement() != null

        // Clearing first makes an interruption recover as a missing-session
        // reauthentication flow instead of resurrecting an old authority over
        // a newly committed workspace.
        return try {
            sessionStore.clear()
            if (workspaceBound) {
                replaceBoundWorkspace()
                runCatching(finalizeLocalWorkspaceReplacement)
                SelfHostedConnectionSwitchResult.switched(workspaceReplaced = true)
            } else {
                localRepository.database.transaction {
                    val current = settingsRepository.load()
                    settingsRepository.saveLocalSnapshot(current.resetUnboundSelfHostedConnection())
                }
                SelfHostedConnectionSwitchResult.switched(workspaceReplaced = false)
            }
        } catch (failure: Throwable) {
            previousSession?.let { credentials ->
                runCatching {
                    sessionStore.save(credentials)
                    sessionStore.saveForAuthority(credentials.authorityBindingId, credentials)
                }
            }
            throw failure
        }
    }

    private fun replaceBoundWorkspace() {
        workspaceKeyRepository.replaceWithFreshWorkspace(
            deviceName = deviceName,
            platform = platform,
            beforeMetadataReplacement = {
                check(discardLocalWorkspaceForReplacement()) {
                    "The current local workspace changed while switching self-hosted authority."
                }
            },
            afterMetadataReplacement = { workspaceKey, _ ->
                val current = settingsRepository.load()
                settingsRepository.saveLocalSnapshot(current.resetBoundWorkspaceForConnectionSwitch())
                ensureWorkspaceLocalDraftV2(
                    localRepository = localRepository,
                    settingsRepository = settingsRepository,
                    workspaceKey = workspaceKey,
                )
            },
        )
    }
}
