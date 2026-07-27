@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.app.android

import saien.someday.domain.location.CapturedLocation
import saien.someday.ui.grantedLocationSmokeLog as sharedGrantedLocationSmokeLog
import saien.someday.ui.sharedUiStartupLog
import kotlin.time.Instant

object AndroidShellEntrypoint {
    const val platformName: String = "android"

    fun startupLog(): String = sharedUiStartupLog(platformName)

    fun grantedLocationSmokeLog(): String =
        sharedGrantedLocationSmokeLog(
            platform = platformName,
            capturedLocation = CapturedLocation(
                latitude = 37.4219999,
                longitude = -122.0840575,
                accuracyMeters = 5.0,
                altitudeMeters = 12.0,
                capturedAt = Instant.parse("2026-05-22T12:00:00Z"),
                providerLabel = "android-test-provider",
            ),
        )
}
