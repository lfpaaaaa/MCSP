package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import au.edu.unimelb.campuscompanion.sensing.location.TravelState
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.RouteEstimate

/** What the app currently knows about the trip to the next class. */
data class TravelSnapshot(
    /** The class being tracked: the next one to start, or the one in progress. */
    val session: CourseSession? = null,
    /** Where the class is, when its location code is known. */
    val building: BuildingLocation? = null,
    val state: TravelState = TravelState.UPCOMING_CLASS,
    /** Travel time from the current position, or null when the position or the building is unknown. */
    val estimate: TravelEstimate? = null,
    /** Straight-line distance to the building in metres, when both positions are known. */
    val distanceMeters: Double? = null,
    /** Minutes until the class starts; negative once it has started. */
    val minutesUntilClass: Long? = null,
    /** Weather at the class's building, when it could be fetched. */
    val weather: WeatherSnapshot? = null,
    /** Extra minutes added to the departure buffer because of the weather. */
    val weatherBufferMinutes: Int = 0
) {
    /** The sessions with the tracked one carrying its travel time and route, for the screens. */
    fun applyTo(sessions: List<CourseSession>): List<CourseSession> {
        val tracked = session ?: return sessions
        val travel = estimate ?: return sessions
        val minutes = travel.durationMinutes
        val route = RouteEstimate(
            distanceMeters = travel.distanceMeters,
            walkingMinutes = minutes.takeIf { travel.mode == TravelMode.Walking },
            publicTransportMinutes = minutes.takeIf { travel.mode == TravelMode.PublicTransport },
            drivingMinutes = minutes.takeIf { travel.mode == TravelMode.Driving },
            isApproximate = travel.isApproximate
        )
        return sessions.map { current ->
            if (current.id == tracked.id) current.copy(etaMinutes = minutes, routeEstimate = route) else current
        }
    }
}
