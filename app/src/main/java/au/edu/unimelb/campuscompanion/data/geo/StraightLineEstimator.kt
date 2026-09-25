package au.edu.unimelb.campuscompanion.data.geo

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Offline fallback for travel times: the straight-line distance walked at an average pace. */
object StraightLineEstimator {
    /** Average walking speed in metres per second. */
    const val WALKING_SPEED_METERS_PER_SECOND = 1.3

    fun estimate(
        origin: GeoPoint,
        destination: GeoPoint,
        now: Instant = Instant.now()
    ): TravelEstimate {
        val distance = GeoMath.distanceMeters(origin, destination)
        return TravelEstimate(
            mode = TravelMode.Walking,
            durationSeconds = ceil(distance / WALKING_SPEED_METERS_PER_SECOND).toLong(),
            distanceMeters = distance.roundToInt(),
            source = EstimateSource.StraightLine,
            computedAt = now
        )
    }
}
