@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package saien.someday.app.ios

import saien.someday.domain.location.CapturedLocation
import saien.someday.domain.location.LocationCaptureAdapter
import saien.someday.domain.location.LocationCaptureResult
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import kotlin.time.Clock

class IosLocationCaptureAdapter(
    private val locationManager: CLLocationManager = CLLocationManager(),
) : LocationCaptureAdapter {
    override fun captureCurrentLocation(): LocationCaptureResult {
        if (!CLLocationManager.locationServicesEnabled()) {
            return LocationCaptureResult.Unavailable("iOS location services are disabled.")
        }

        return when (CLLocationManager.authorizationStatus()) {
            kCLAuthorizationStatusAuthorizedAlways,
            kCLAuthorizationStatusAuthorizedWhenInUse,
            -> {
                val location = locationManager.location
                    ?: return LocationCaptureResult.Unavailable("No current iOS location is available yet.")
                LocationCaptureResult.Captured(location.toCapturedLocation())
            }

            kCLAuthorizationStatusNotDetermined -> {
                locationManager.requestWhenInUseAuthorization()
                LocationCaptureResult.Denied("iOS location permission has not been granted yet.")
            }

            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted,
            -> LocationCaptureResult.Denied("iOS location permission is denied or restricted.")

            else -> LocationCaptureResult.Unavailable("Unknown iOS location authorization status.")
        }
    }

    private fun CLLocation.toCapturedLocation(): CapturedLocation =
        coordinate.useContents {
            CapturedLocation(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = horizontalAccuracy.takeIf { it >= 0.0 },
                altitudeMeters = altitude.takeIf { verticalAccuracy >= 0.0 },
                capturedAt = Clock.System.now(),
                providerLabel = "ios-core-location",
            )
        }
}
