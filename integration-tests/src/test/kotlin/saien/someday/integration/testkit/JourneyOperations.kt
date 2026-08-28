package saien.someday.integration.testkit

import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mechanical setup shared by journeys that are not themselves testing pairing. */
internal fun TestDevice.adoptWorkspaceFrom(inviter: TestDevice) {
    val packageResult = inviter.workspaceJoinPackageProvider.createPackage()
    assertTrue(
        packageResult.success,
        packageResult.diagnosticMessage ?: packageResult.reason.name,
    )
    val joined = workspaceJoiner.join(assertNotNull(packageResult.packageData))
    assertTrue(joined.success, joined.diagnosticMessage ?: joined.reason.name)
}

internal fun TestDevice.assertSuccessfulSync() {
    val result = services.manualSyncRunner.run()
    assertTrue(result.success, result.diagnosticMessage ?: result.reason.name)
}
