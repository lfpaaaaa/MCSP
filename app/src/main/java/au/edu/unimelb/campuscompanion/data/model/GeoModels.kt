package au.edu.unimelb.campuscompanion.data.model

import au.edu.unimelb.campuscompanion.data.TravelMode
import java.time.Instant

/** A WGS84 coordinate in decimal degrees. */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90: $latitude" }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180: $longitude" }
    }
}

/** Where a travel estimate came from, so the UI can label approximate values. */
enum class EstimateSource {
    /** Returned by the online routing service. */
    Routing,

    /** Computed on the device from the straight-line distance when routing is unavailable. */
    StraightLine
}

/** Estimated travel time between two points for one travel mode. */
data class TravelEstimate(
    val mode: TravelMode,
    val durationSeconds: Long,
    val distanceMeters: Int,
    val source: EstimateSource,
    val computedAt: Instant
) {
    init {
        require(durationSeconds >= 0) { "Duration cannot be negative" }
        require(distanceMeters >= 0) { "Distance cannot be negative" }
    }

    /** Duration rounded up to whole minutes, matching how departure times are shown. */
    val durationMinutes: Int
        get() = ((durationSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt()

    val isApproximate: Boolean
        get() = source == EstimateSource.StraightLine

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
    }
}
