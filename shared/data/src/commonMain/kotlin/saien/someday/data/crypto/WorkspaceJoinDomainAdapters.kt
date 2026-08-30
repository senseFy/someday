package saien.someday.data.crypto

import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.domain.settings.WorkspaceJoinPackageProvider
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.WorkspaceJoiner
import saien.someday.domain.settings.WorkspacePairingReason

fun WorkspaceKeyRepository.workspaceJoinPackageProvider(): WorkspaceJoinPackageProvider =
    WorkspaceJoinPackageProvider {
        when (val result = createWorkspaceJoinPackage()) {
            is WorkspaceJoinPackageResult.Created -> WorkspaceJoinResult.success(
                reason = WorkspacePairingReason.PackageCreated,
                packageData = WorkspaceJoinPackage(
                    metadataJson = result.metadataJson,
                    recoveryCode = result.recoveryMaterial.revealForUserConfirmation(),
                    workspaceId = result.workspaceId,
                    keyFingerprint = result.keyFingerprint,
                ),
            )
            is WorkspaceJoinPackageResult.Failed -> WorkspaceJoinResult.failure(result.reason.workspacePairingReason())
        }
    }

fun WorkspaceKeyRepository.workspaceRecoveryPackageProvider(): WorkspaceJoinPackageProvider =
    WorkspaceJoinPackageProvider {
        when (val result = createWorkspaceRecoveryPackage()) {
            is WorkspaceJoinPackageResult.Created -> WorkspaceJoinResult.success(
                reason = WorkspacePairingReason.PackageCreated,
                packageData = WorkspaceJoinPackage(
                    metadataJson = result.metadataJson,
                    recoveryCode = result.recoveryMaterial.revealForUserConfirmation(),
                    workspaceId = result.workspaceId,
                    keyFingerprint = result.keyFingerprint,
                ),
            )
            is WorkspaceJoinPackageResult.Failed -> WorkspaceJoinResult.failure(result.reason.workspacePairingReason())
        }
    }

fun WorkspaceKeyRepository.workspaceJoiner(
    deviceName: String,
    platform: String,
    beforeWorkspaceReplacement: () -> Boolean,
    afterWorkspaceReplacement: (WorkspaceJoinPackage, WorkspaceMasterKey, String) -> Boolean,
    afterWorkspaceReplacementCommitted: () -> Unit,
): WorkspaceJoiner =
    WorkspaceJoiner { packageData, replaceExistingWorkspace ->
        if (!replaceExistingWorkspace) {
            return@WorkspaceJoiner WorkspaceJoinResult.failure(
                WorkspacePairingReason.ReplacementConfirmationRequired,
            )
        }
        val result = runCatching {
            joinWorkspaceFromDomain(
                packageData = packageData,
                deviceName = deviceName,
                platform = platform,
                beforeWorkspaceReplacement = {
                    check(beforeWorkspaceReplacement()) {
                        "The current local workspace changed while joining."
                    }
                },
                afterWorkspaceReplacement = { key, workspaceId ->
                    check(afterWorkspaceReplacement(packageData, key, workspaceId)) {
                        "The joined workspace could not be bound to the authenticated account."
                    }
                },
            )
        }.getOrElse {
            return@WorkspaceJoiner WorkspaceJoinResult.failure(WorkspacePairingReason.ReplacementFailed)
        }
        if (result.success) {
            // Database and secure-key replacement have committed. Filesystem
            // cleanup is best-effort and must never turn that commit into a
            // user-visible pairing failure.
            runCatching(afterWorkspaceReplacementCommitted)
        }
        result
    }

private fun WorkspaceKeyRepository.joinWorkspaceFromDomain(
    packageData: WorkspaceJoinPackage,
    deviceName: String,
    platform: String,
    beforeWorkspaceReplacement: () -> Unit,
    afterWorkspaceReplacement: (WorkspaceMasterKey, String) -> Unit,
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
            beforeMetadataReplacement = beforeWorkspaceReplacement,
            afterMetadataReplacement = afterWorkspaceReplacement,
        )
    ) {
        is WorkspaceRestoreResult.Restored -> WorkspaceJoinResult.success(
            reason = WorkspacePairingReason.Joined,
        )
        is WorkspaceRestoreResult.Failed -> WorkspaceJoinResult.failure(result.reason.workspacePairingReason())
    }

private fun WorkspaceUnlockFailure.workspacePairingReason(): WorkspacePairingReason =
    when (this) {
        WorkspaceUnlockFailure.NO_WORKSPACE ->
            WorkspacePairingReason.WorkspaceLocked
        WorkspaceUnlockFailure.WORKSPACE_ALREADY_EXISTS ->
            WorkspacePairingReason.ReplacementConfirmationRequired
        WorkspaceUnlockFailure.SECURE_STORAGE_UNAVAILABLE ->
            WorkspacePairingReason.WorkspaceLocked
        WorkspaceUnlockFailure.AUTHENTICATION_FAILED ->
            WorkspacePairingReason.VerificationFailed
        WorkspaceUnlockFailure.INVALID_METADATA ->
            WorkspacePairingReason.VerificationFailed
    }
