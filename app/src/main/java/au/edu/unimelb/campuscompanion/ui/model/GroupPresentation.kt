package au.edu.unimelb.campuscompanion.ui.model

import java.time.ZonedDateTime
import au.edu.unimelb.campuscompanion.data.timetableGroupSpec

data class GroupChatPreferences(
    val foldedOverride: Boolean? = null,
    val muted: Boolean = false,
    val displayName: String = ""
)

fun CourseGroup.isAutomaticallyFolded(
    sessions: List<CourseSession>,
    now: ZonedDateTime
): Boolean {
    if (origin != GroupOrigin.Timetable) return false

    val courseSessions = sessions.filter {
        it.code.equals(courseCode, ignoreCase = true) &&
            (timetableKey == null || timetableKey.endsWith("|course") || it.timetableGroupSpec()?.key == timetableKey)
    }
    if (courseSessions.isEmpty()) return true

    return courseSessions.none { session ->
        session.end.isAfter(now.withZoneSameInstant(session.end.zone))
    }
}

fun CourseGroup.isFolded(
    preferences: GroupChatPreferences,
    sessions: List<CourseSession>,
    now: ZonedDateTime
): Boolean = preferences.foldedOverride ?: isAutomaticallyFolded(sessions, now)

fun foldedOverrideAfterEdit(
    existingOverride: Boolean?,
    initialFolded: Boolean,
    selectedFolded: Boolean
): Boolean? = if (selectedFolded == initialFolded) {
    existingOverride
} else {
    selectedFolded
}
