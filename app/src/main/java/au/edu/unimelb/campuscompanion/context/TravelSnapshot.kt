package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.ZonedDateTime

/** What the app currently knows about the trip to the next class. */
data class TravelSnapshot(
    /** The class being tracked: the next one to start, or the one in progress. */
    val session: CourseSession? = null,
    /** Where the class is, when its location code is known. */
    val building: BuildingLocation? = null,
    /** The context state from [ContextEngine]. */
    val state: ContextState = ContextState.NO_UPCOMING_CLASS,
    /** Travel time from the current position, or null when the position or the building is unknown. */
    val estimate: TravelEstimate? = null,
    /** Straight-line distance to the building in metres, when both positions are known. */
    val distanceMeters: Double? = null,
    /** Minutes until the class starts; negative once it has started. */
    val minutesUntilClass: Long? = null,
    /** Weather at the class's building, when it could be fetched. */
    val weather: WeatherSnapshot? = null,
    /** Extra minutes added to the departure buffer because of the weather. */
    val weatherBufferMinutes: Int = 0,
    /** The student's own buffer, set on the schedule screen. */
    val leadMinutes: Int = 0,
    /** Minutes allowed to get from the building entrance to the room. */
    val entryAllowanceMinutes: Int = 0,
    /** When to leave: start - travel - buffers - entry allowance. Null when the travel time is unknown. */
    val leaveBy: ZonedDateTime? = null,
    /** The class that finished in the last few minutes, if any; shown while the state is POST_CLASS. */
    val finishedSession: CourseSession? = null
) {
    /** The sessions with the tracked one carrying its travel time, for the screens. */
    fun applyTo(sessions: List<CourseSession>): List<CourseSession> {
        val tracked = session ?: return sessions
        val minutes = estimate?.durationMinutes ?: return sessions
        return sessions.map { if (it.id == tracked.id) it.copy(etaMinutes = minutes) else it }
    }
}
