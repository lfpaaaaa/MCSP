package au.edu.unimelb.campuscompanion.ui.model

/**
 * Whether these are the same timetable occurrence for context and travel guidance.
 * A stable ID alone is insufficient after editing a class's venue, time or activity.
 * Compare times by instant so display-zone changes do not invalidate guidance.
 * Title is display text; ETA and route are transient enrichment, not timetable facts.
 */
fun CourseSession.matchesTimetableSession(other: CourseSession): Boolean =
    id == other.id && code == other.code && activity == other.activity &&
        start.toInstant() == other.start.toInstant() && end.toInstant() == other.end.toInstant() &&
        location == other.location && room == other.room
