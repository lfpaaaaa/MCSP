package au.edu.unimelb.campuscompanion.ui.model

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

/** Preview the engine's live deadline only for this occurrence and the currently selected preparation lead. */
fun CourseSession.reminderTiming(
    snapshot: TravelSnapshot,
    preparationMinutes: Int
): CourseReminderTiming {
    val tracked = snapshot.session
    if (tracked == null || !tracked.matchesTimetableSession(this)) {
        return CourseReminderTiming.NotTracked
    }
    if (snapshot.leadMinutes != preparationMinutes) return CourseReminderTiming.Updating
    val leaveBy = snapshot.leaveBy ?: return CourseReminderTiming.MissingTravelTime
    val estimate = snapshot.estimate ?: return CourseReminderTiming.MissingTravelTime
    return CourseReminderTiming.Ready(
        leaveBy = leaveBy,
        warningAt = leaveBy.minusMinutes(preparationMinutes.coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES).toLong()),
        travelMinutes = estimate.durationMinutes
    )
}
