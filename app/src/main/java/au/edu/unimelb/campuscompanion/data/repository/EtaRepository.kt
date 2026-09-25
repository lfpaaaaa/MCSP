package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate

/** Travel-time estimates for departure reminders. */
interface EtaRepository {
    /**
     * Estimates the trip from [origin] to [destination]. When the routing service is unavailable,
     * the result is an offline straight-line walking estimate marked as approximate.
     */
    suspend fun estimate(
        origin: GeoPoint,
        destination: GeoPoint,
        mode: TravelMode
    ): Result<TravelEstimate>
}
