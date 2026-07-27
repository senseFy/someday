@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.domain.location

import saien.someday.domain.notes.NotesLocationInput
import kotlin.time.Instant

data class CapturedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val altitudeMeters: Double? = null,
    val capturedAt: Instant,
    val providerLabel: String? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90." }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180." }
    }

    fun toNotesLocationInput(placeText: String? = null): NotesLocationInput =
        NotesLocationInput(
            latitude = latitude,
            longitude = longitude,
            placeText = placeText?.takeIf { it.isNotBlank() },
            accuracyMeters = accuracyMeters,
            altitudeMeters = altitudeMeters,
            capturedAt = capturedAt,
        )
}

sealed interface LocationCaptureResult {
    data class Captured(
        val location: CapturedLocation,
    ) : LocationCaptureResult

    data class Denied(
        val reason: String,
    ) : LocationCaptureResult

    data class Unavailable(
        val reason: String,
    ) : LocationCaptureResult
}

interface LocationCaptureAdapter {
    fun captureCurrentLocation(): LocationCaptureResult
}

object UnavailableLocationCaptureAdapter : LocationCaptureAdapter {
    override fun captureCurrentLocation(): LocationCaptureResult =
        LocationCaptureResult.Unavailable("System location is not configured on this platform.")
}

class StaticLocationCaptureAdapter(
    private val result: LocationCaptureResult,
) : LocationCaptureAdapter {
    override fun captureCurrentLocation(): LocationCaptureResult = result
}
