@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.app.ios

import saien.someday.domain.location.CapturedLocation
import saien.someday.ui.grantedLocationSmokeLog as sharedGrantedLocationSmokeLog
import saien.someday.ui.sharedUiStartupLog
import kotlin.time.Instant

object IosShellEntrypoint {
    const val platformName: String = "ios"

    fun startupLog(): String = sharedUiStartupLog(platformName)

    fun grantedLocationSmokeLog(): String =
        sharedGrantedLocationSmokeLog(
            platform = platformName,
            capturedLocation = CapturedLocation(
                latitude = 37.3349,
                longitude = -122.00902,
                accuracyMeters = 6.0,
                altitudeMeters = 18.0,
                capturedAt = Instant.parse("2026-05-22T12:00:00Z"),
                providerLabel = "ios-test-provider",
            ),
        )
}
