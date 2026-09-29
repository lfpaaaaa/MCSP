package au.edu.unimelb.campuscompanion.data.geo

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Offline fallback for travel times: the straight-line distance covered at an average speed for
 * the travel mode. The result is marked approximate, because real routes are longer than the
 * straight line and public transport depends on the timetable.
 */
object StraightLineEstimator {
    /** Average walking speed in metres per second. */
    const val WALKING_SPEED_METERS_PER_SECOND = 1.3

    /** Door-to-door average for public transport in the city, including walking to stops and waiting. */
    const val TRANSIT_SPEED_METERS_PER_SECOND = 5.0

    /** Average driving speed on city streets, including traffic lights and parking. */
    const val DRIVING_SPEED_METERS_PER_SECOND = 8.0

    fun estimate(
        origin: GeoPoint,
        destination: GeoPoint,
        now: Instant = Instant.now(),
        mode: TravelMode = TravelMode.Walking
    ): TravelEstimate {
        val distance = GeoMath.distanceMeters(origin, destination)
        return TravelEstimate(
            mode = mode,
            durationSeconds = ceil(distance / speedFor(mode)).toLong(),
            distanceMeters = distance.roundToInt(),
            source = EstimateSource.StraightLine,
            computedAt = now
        )
    }

    private fun speedFor(mode: TravelMode): Double = when (mode) {
        TravelMode.Walking -> WALKING_SPEED_METERS_PER_SECOND
        TravelMode.PublicTransport -> TRANSIT_SPEED_METERS_PER_SECOND
        TravelMode.Driving -> DRIVING_SPEED_METERS_PER_SECOND
    }
}
