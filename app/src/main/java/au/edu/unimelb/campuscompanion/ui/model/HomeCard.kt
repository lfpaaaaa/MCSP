package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ui.text.UiText
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
    /** Validated post-class occurrence; can coexist with urgent guidance for the next class. */
    val finishedSession: CourseSession?
) {
    val isUpdating: Boolean get() = state == null

    /** Calendar feeds can embed the room in location rather than the separate room field. */
    val destinationText: UiText
        get() = listOfNotNull(building?.name, session?.location, session?.room)
            .map(String::trim)
            .filter { it.isNotBlank() && it != "Location not provided" }
            .distinct()
            .joinToString(" · ")
            .let { if (it.isBlank()) UiText(R.string.home_location_missing) else UiText.Literal(it) }

    val statusLabel: UiText
        get() = when (state) {
            null -> UiText(R.string.home_status_updating)
            ContextState.NO_UPCOMING_CLASS -> UiText(R.string.home_status_not_today)
            ContextState.LONG_BREAK -> UiText(R.string.home_status_long_break)
            ContextState.UPCOMING -> UiText(R.string.home_status_upcoming)
            ContextState.LEAVE_SOON -> UiText(R.string.home_status_leave_soon)
            ContextState.LEAVE_NOW -> UiText(R.string.home_leave_now)
            ContextState.EN_ROUTE -> UiText(R.string.home_status_en_route)
            ContextState.RUNNING_LATE -> UiText(R.string.home_status_running_late)
            ContextState.ARRIVED -> UiText(R.string.home_status_arrived)
            ContextState.IN_CLASS -> UiText(R.string.home_status_in_class)
            // This pill belongs to the next class; the finished class remains available separately.
            ContextState.POST_CLASS -> if (session != null) UiText(R.string.home_status_upcoming) else UiText(R.string.home_status_post_class)
        }

    /** Reuses the existing pill colours only; [state] remains the full engine decision. */
    val statusTone: SessionStatus
        get() = when (state) {
            null, ContextState.LONG_BREAK, ContextState.UPCOMING -> SessionStatus.Upcoming
            ContextState.NO_UPCOMING_CLASS -> SessionStatus.Finished
            ContextState.LEAVE_SOON, ContextState.LEAVE_NOW, ContextState.RUNNING_LATE -> SessionStatus.LeaveSoon
            ContextState.EN_ROUTE -> SessionStatus.EnRoute
            ContextState.ARRIVED -> SessionStatus.Arrived
            ContextState.IN_CLASS -> SessionStatus.InProgress
            ContextState.POST_CLASS -> if (session != null) SessionStatus.Upcoming else SessionStatus.Finished
        }
}

/** List rows share the tracked card's exact label and colour; another occurrence cannot inherit them. */
fun HomeCardUiState.forSession(session: CourseSession): HomeCardUiState? =
    takeIf { this.session?.matchesTimetableSession(session) == true }

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

private fun countdownMinutes(now: ZonedDateTime, deadline: ZonedDateTime): Long {
    val remaining = Duration.between(now, deadline)
    if (remaining.isNegative || remaining.isZero) return 0
    return remaining.seconds / 60 + if (remaining.seconds % 60 != 0L || remaining.nano > 0) 1 else 0
}
