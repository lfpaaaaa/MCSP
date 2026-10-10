package au.edu.unimelb.campuscompanion.ui.model

import java.time.ZonedDateTime
import java.util.Locale

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
) {
    val displayName: String get() = listOfNotNull(
        code, nextSession.reminderActivity()?.replaceFirstChar { it.titlecase(Locale.ROOT) }
    ).joinToString(" · ")
}

/** Every recurrence of one activity shares a switch, independently of the other activity types. */
fun CourseSession.reminderSeriesKey(): String = reminderActivity()?.let {
    val subject = code.normalizedReminderKey().takeUnless { code.isBlank() || code.equals("EVENT", true) }
        ?: legacyReminderSeriesKey()
    "activity:$subject:$it"
} ?: legacyReminderSeriesKey()

fun CourseSession.legacyReminderSeriesKey(): String =
    "course:${code.normalizedReminderKey()}:${title.normalizedReminderKey()}"

fun CourseSession.reminderActivity(): String? = activity?.normalizedReminderKey()?.takeIf { it.isNotBlank() }
    ?: Regex("(?i)\\b(lecture|tutorial|workshop)(?:[\\s_:#-]*[A-Z]?\\d+)?\\b")
        .find(title)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

private fun String.normalizedReminderKey(): String = trim()
    .lowercase(Locale.ROOT)
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
