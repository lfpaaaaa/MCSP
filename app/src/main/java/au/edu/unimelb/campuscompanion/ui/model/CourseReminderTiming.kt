package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.context.ContextConfig
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import java.time.ZonedDateTime

sealed interface CourseReminderTiming {
    data class Ready(
        val leaveBy: ZonedDateTime,
        val warningAt: ZonedDateTime,
        val travelMinutes: Int
    ) : CourseReminderTiming

    data object NotTracked : CourseReminderTiming
    data object Updating : CourseReminderTiming
    data object MissingTravelTime : CourseReminderTiming
}

/** Preview the engine's live deadline only for this occurrence and the currently selected buffer. */
fun CourseSession.reminderTiming(
    snapshot: TravelSnapshot,
    arrivalBufferMinutes: Int
): CourseReminderTiming {
    val tracked = snapshot.session
    if (tracked == null || tracked.id != id || tracked.start.toInstant() != start.toInstant()) {
        return CourseReminderTiming.NotTracked
    }
    if (snapshot.leadMinutes != arrivalBufferMinutes) return CourseReminderTiming.Updating
    val leaveBy = snapshot.leaveBy ?: return CourseReminderTiming.MissingTravelTime
    val estimate = snapshot.estimate ?: return CourseReminderTiming.MissingTravelTime
    return CourseReminderTiming.Ready(
        leaveBy = leaveBy,
        warningAt = leaveBy.minusMinutes(ContextConfig().leaveSoonWindowMinutes.toLong()),
        travelMinutes = estimate.durationMinutes
    )
}
