package saien.someday.data.crypto

import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.domain.settings.WorkspaceJoinPackageProvider
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspaceJoiner

fun WorkspaceKeyRepository.workspaceJoinPackageProvider(): WorkspaceJoinPackageProvider =
    WorkspaceJoinPackageProvider {
        when (val result = createWorkspaceJoinPackage()) {
            is WorkspaceJoinPackageResult.Created -> WorkspaceJoinResult.success(
                message = "Workspace join package created. Keep the recovery code private.",
                packageData = WorkspaceJoinPackage(
                    metadataJson = result.metadataJson,
                    recoveryCode = result.recoveryMaterial.revealForUserConfirmation(),
                    workspaceId = result.workspaceId,
                    keyFingerprint = result.keyFingerprint,
                ),
            )
            is WorkspaceJoinPackageResult.Failed -> WorkspaceJoinResult.failure(result.reason.workspaceJoinMessage())
        }
    }

/**
 * @param localV2KeyBoundStatePresent required. When true, refuse joining. Must report any
 *   preparing/active/blocked/read-only local V2 epoch: replacing only the master key leaves
 *   the DAG bound to the prior workspace key. There is intentionally no default false —
 *   callers must wire a real check.
 */
fun WorkspaceKeyRepository.workspaceJoiner(
    deviceName: String,
    platform: String,
    localV2KeyBoundStatePresent: () -> Boolean,
): WorkspaceJoiner =
    WorkspaceJoiner { packageData ->
        if (localV2KeyBoundStatePresent()) {
            return@WorkspaceJoiner WorkspaceJoinResult.failure(
                "This device already has local Sync V2 history for the current workspace key. " +
                    "Clear local app data before joining another workspace. " +
                    "Replacing only the workspace key cannot rebind the local DAG.",
            )
        }
        joinWorkspaceFromDomain(
            packageData = packageData,
            deviceName = deviceName,
            platform = platform,
        )
    }

private fun WorkspaceKeyRepository.joinWorkspaceFromDomain(
    packageData: WorkspaceJoinPackage,
    deviceName: String,
    platform: String,
): WorkspaceJoinResult =
    when (
        val result = restoreWorkspaceFromRecovery(
            metadataJson = packageData.metadataJson,
            recoveryMaterial = packageData.recoveryCode,
            deviceName = deviceName,
            platform = platform,
            replaceExistingWorkspace = true,
            expectedWorkspaceId = packageData.workspaceId,
            expectedKeyFingerprint = packageData.keyFingerprint,
        )
    ) {
        is WorkspaceRestoreResult.Restored -> WorkspaceJoinResult.success(
            message = "Joined workspace ${result.state.workspaceId}. This device can now decrypt the shared workspace; run Sync to pull notes.",
        )
        is WorkspaceRestoreResult.Failed -> WorkspaceJoinResult.failure(result.reason.workspaceJoinMessage())
    }

private fun WorkspaceUnlockFailure.workspaceJoinMessage(): String =
    when (this) {
        WorkspaceUnlockFailure.NO_WORKSPACE ->
            "Create or join a workspace before creating a join package."
        WorkspaceUnlockFailure.WORKSPACE_ALREADY_EXISTS ->
            "This device already has a workspace. Confirm replacement before joining another workspace."
        WorkspaceUnlockFailure.SECURE_STORAGE_UNAVAILABLE ->
            "Secure storage is unavailable; unlock this workspace before creating or joining a package."
        WorkspaceUnlockFailure.AUTHENTICATION_FAILED ->
            "Workspace join failed. Check the recovery code and metadata; secrets redacted."
        WorkspaceUnlockFailure.INVALID_METADATA ->
            "Workspace join failed. The metadata package is invalid."
    }
