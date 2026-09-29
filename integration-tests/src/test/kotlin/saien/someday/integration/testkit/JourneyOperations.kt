package saien.someday.integration.testkit

import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import saien.someday.domain.settings.WorkspaceJoinAuthorityCapture
import saien.someday.domain.settings.WorkspaceJoinPackage
import saien.someday.domain.settings.WorkspaceJoinResult
import saien.someday.domain.settings.authorityBindingId

/** Mechanical setup still uses the production authenticated pairing and replacement path. */
internal fun TestDevice.replaceWorkspaceFrom(inviter: TestDevice) {
    val created = inviter.pairing.createInvitation()
    assertTrue(created.success, created.diagnosticMessage ?: created.reason.name)
    val joined = pairing.joinWithToken(
        assertNotNull(created.invitation).revealManualToken(),
        replaceExistingWorkspace = true,
    )
    assertTrue(joined.success, joined.diagnosticMessage ?: joined.reason.name)
}

/**
 * Test-only restoration of an already exported fixture package, including an
 * old workspace whose source installation has since joined another workspace.
 * The export's account identity/incarnation is supplied explicitly, never
 * inferred from later discovery; the receiving installation keeps its writer.
 */
internal fun TestDevice.restoreKnownWorkspacePackage(
    packageData: WorkspaceJoinPackage,
    sourceAuthorityBindingId: String,
    sourceAccountIncarnation: String,
): WorkspaceJoinResult = services.workspaceLifecycleCoordinator.exclusive {
    val credentials = assertNotNull(sessionStore.load())
    require(
        credentials.authorityBindingId == sourceAuthorityBindingId &&
            credentials.accountIncarnation == sourceAccountIncarnation,
    )
    val guard = services.activeWorkspaceSessionGuard
    val previousRequirement = guard.currentRequirement()
    val previousWorkspace = guard.capturePreviousWorkspace()
    guard.requireCapturedReplacement(credentials, previousRequirement, previousWorkspace)
    val capturedPackage = WorkspaceJoinPackage(
        packageData.metadataJson,
        packageData.recoveryCode,
        packageData.workspaceId,
        packageData.keyFingerprint,
        WorkspaceJoinAuthorityCapture(
            credentials.authorityBindingId,
            credentials.deviceId,
            sourceAccountIncarnation,
            previousWorkspace?.first,
            previousWorkspace?.second,
        ),
    )
    services.workspaceLifecycleCoordinator.productAccess {
        workspaceJoiner.join(capturedPackage, replaceExistingWorkspace = true)
    }
}

internal fun TestDevice.assertSuccessfulSync() {
    val result = services.manualSyncRunner.run()
    assertTrue(result.success, result.diagnosticMessage ?: result.reason.name)
}
