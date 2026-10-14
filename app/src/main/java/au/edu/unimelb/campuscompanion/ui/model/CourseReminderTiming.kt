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

/** Show live times only after the engine has applied both choices to this occurrence. */
fun CourseSession.reminderTiming(
    snapshot: TravelSnapshot,
    arrivalBufferMinutes: Int,
    preparationMinutes: Int = DEFAULT_COURSE_REMINDER_PREPARATION_MINUTES
): CourseReminderTiming {
    val tracked = snapshot.session
    if (tracked == null || !tracked.matchesTimetableSession(this)) {
        return CourseReminderTiming.NotTracked
    }
    if (snapshot.leadMinutes != arrivalBufferMinutes || snapshot.preparationMinutes != preparationMinutes) {
        return CourseReminderTiming.Updating
    }
    val leaveBy = snapshot.leaveBy ?: return CourseReminderTiming.MissingTravelTime
    val estimate = snapshot.estimate ?: return CourseReminderTiming.MissingTravelTime
    return CourseReminderTiming.Ready(
        leaveBy = leaveBy,
        warningAt = leaveBy.minusMinutes(preparationMinutes.toLong()),
        travelMinutes = estimate.durationMinutes
    )
}
