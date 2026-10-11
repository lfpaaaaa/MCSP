package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.context.ContextEngine
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import java.time.Duration
import java.time.ZonedDateTime

/** Home's view of the engine result, without recalculating its state or departure deadline. */
data class HomeCardUiState(
    val session: CourseSession?,
    /** Null while the engine is catching up with the timetable or the selected class. */
    val state: ContextState?,
    val estimate: TravelEstimate?,
    val building: BuildingLocation?,
    /** Straight-line distance; the estimate carries the route distance separately. */
    val distanceMeters: Double?,
    val leaveBy: ZonedDateTime?,
    /** Rounded up while positive, zero at/past the deadline, or null without a deadline. */
    val departureCountdownMinutes: Long?,
    val leadMinutes: Int,
    val weatherBufferMinutes: Int,
    val entryAllowanceMinutes: Int,
    val finishedSession: CourseSession?
) {
    val isUpdating: Boolean get() = state == null

    val statusLabel: String
        get() = when (state) {
            null -> "Updating"
            ContextState.NO_UPCOMING_CLASS -> "Not today"
            ContextState.LONG_BREAK -> "Long break"
            ContextState.UPCOMING -> "Upcoming"
            ContextState.LEAVE_SOON -> "Leave soon"
            ContextState.EN_ROUTE -> "En route"
            ContextState.RUNNING_LATE -> "Running late"
            ContextState.ARRIVED -> "Arrived"
            ContextState.IN_CLASS -> "In class"
            // This pill belongs to the next class; the finished class remains available separately.
            ContextState.POST_CLASS -> if (session != null) "Upcoming" else "Post class"
        }

    /** Reuses the existing pill colours only; [state] remains the full engine decision. */
    val statusTone: SessionStatus
        get() = when (state) {
            null, ContextState.LONG_BREAK, ContextState.UPCOMING -> SessionStatus.Upcoming
            ContextState.NO_UPCOMING_CLASS -> SessionStatus.Finished
            ContextState.LEAVE_SOON, ContextState.RUNNING_LATE -> SessionStatus.LeaveSoon
            ContextState.EN_ROUTE -> SessionStatus.EnRoute
            ContextState.ARRIVED -> SessionStatus.Arrived
            ContextState.IN_CLASS -> SessionStatus.InProgress
            ContextState.POST_CLASS -> if (session != null) SessionStatus.Upcoming else SessionStatus.Finished
        }
}

/**
 * Reuses the engine's session-selection rule, then binds its snapshot to the current timetable.
 * A previous class or a changed destination must not supply another class's guidance or travel.
 * [now] is explicit so countdowns and class transitions can be tested with a fixed clock.
 */
fun buildHomeCardState(
    sessions: List<CourseSession>,
    snapshot: TravelSnapshot,
    now: ZonedDateTime
): HomeCardUiState {
    val session = ContextEngine.trackedSession(sessions, now.toInstant())
    // The engine owns the post-class window; only bind a finished occurrence still in this timetable.
    val finishedSession = snapshot.finishedSession?.let { finished ->
        sessions.firstOrNull { it.matchesTimetableSession(finished) && !it.end.isAfter(now) }
    }
    val matches = if (session != null) {
        snapshot.session?.matchesTimetableSession(session) == true
    } else {
        snapshot.session == null && when {
            snapshot.finishedSession != null -> finishedSession != null
            else -> snapshot.state == ContextState.NO_UPCOMING_CLASS
        }
    }
    val current = snapshot.takeIf { matches }
    return HomeCardUiState(
        session = session,
        state = current?.state,
        estimate = current?.estimate,
        building = current?.building,
        distanceMeters = current?.distanceMeters,
        leaveBy = current?.leaveBy,
        departureCountdownMinutes = current?.leaveBy?.let { countdownMinutes(now, it) },
        leadMinutes = current?.leadMinutes ?: 0,
        weatherBufferMinutes = current?.weatherBufferMinutes ?: 0,
        entryAllowanceMinutes = current?.entryAllowanceMinutes ?: 0,
        finishedSession = finishedSession.takeIf { matches }
    )
}

/** ETA and route fields are deliberately excluded: applyTo() enriches those for other screens. */
private fun CourseSession.matchesTimetableSession(other: CourseSession): Boolean =
    id == other.id && code == other.code && activity == other.activity &&
        start.toInstant() == other.start.toInstant() && end.toInstant() == other.end.toInstant() &&
        location == other.location && room == other.room

private fun countdownMinutes(now: ZonedDateTime, deadline: ZonedDateTime): Long {
    val remaining = Duration.between(now, deadline)
    if (remaining.isNegative || remaining.isZero) return 0
    return remaining.seconds / 60 + if (remaining.seconds % 60 != 0L || remaining.nano > 0) 1 else 0
}
