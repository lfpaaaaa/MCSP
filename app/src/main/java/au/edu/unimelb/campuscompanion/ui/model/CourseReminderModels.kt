package au.edu.unimelb.campuscompanion.ui.model

import java.time.ZonedDateTime

const val DEFAULT_COURSE_REMINDER_LEAD_MINUTES = 10
const val MAX_COURSE_REMINDER_LEAD_MINUTES = 60

data class CourseReminderPreference(
    val enabled: Boolean = true,
    val leadMinutes: Int = DEFAULT_COURSE_REMINDER_LEAD_MINUTES
)

data class CourseReminderSeries(
    val key: String,
    val code: String,
    val title: String,
    val location: String,
    val room: String,
    val nextSession: CourseSession,
    val occurrenceCount: Int
)

fun CourseSession.reminderSeriesKey(): String {
    return "course:${code.normalizedReminderKey()}:${title.normalizedReminderKey()}"
}

private fun String.normalizedReminderKey(): String = trim()
    .lowercase()
    .replace(Regex("\\s+"), " ")

fun buildCourseReminderSeries(
    sessions: List<CourseSession>,
    now: ZonedDateTime? = null
): List<CourseReminderSeries> = sessions
    .groupBy(CourseSession::reminderSeriesKey)
    .mapNotNull { (key, occurrences) ->
        val ordered = occurrences.sortedBy { it.start.toInstant() }
        val representative = if (now == null) {
            ordered.firstOrNull()
        } else {
            ordered.firstOrNull { it.end.toInstant().isAfter(now.toInstant()) }
                ?: ordered.lastOrNull()
        } ?: return@mapNotNull null

        CourseReminderSeries(
            key = key,
            code = representative.code,
            title = representative.title,
            location = representative.location,
            room = representative.room,
            nextSession = representative,
            occurrenceCount = occurrences.size
        )
    }
    .sortedBy { it.nextSession.start.toInstant() }
