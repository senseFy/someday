@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.app.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import saien.someday.domain.location.CapturedLocation
import saien.someday.domain.location.LocationCaptureAdapter
import saien.someday.domain.location.LocationCaptureResult
import kotlin.time.Clock
import kotlin.time.Instant

class AndroidLocationCaptureAdapter(
    context: Context,
) : LocationCaptureAdapter {
    private val appContext = context.applicationContext

    override fun captureCurrentLocation(): LocationCaptureResult {
        if (!hasGrantedLocationPermission()) {
            return LocationCaptureResult.Denied("Android fine/coarse location permission is not granted.")
        }

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return LocationCaptureResult.Unavailable("Android LocationManager is unavailable.")

        val location = manager.latestKnownLocation()
            ?: return LocationCaptureResult.Unavailable("No last-known Android location is available yet.")

        return LocationCaptureResult.Captured(location.toCapturedLocation())
    }

    private fun hasGrantedLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun LocationManager.latestKnownLocation(): Location? =
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).firstNotNullOfOrNull { provider ->
            runCatching { getLastKnownLocation(provider) }.getOrNull()
        }

    private fun Location.toCapturedLocation(): CapturedLocation =
        CapturedLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
            altitudeMeters = if (hasAltitude()) altitude else null,
            capturedAt = if (time > 0L) {
                Instant.fromEpochMilliseconds(time)
            } else {
                Clock.System.now()
            },
            providerLabel = provider,
        )
}
