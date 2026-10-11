package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.sensing.location.TravelState
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import au.edu.unimelb.campuscompanion.ui.model.trimTrailingCommas
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One notification per class; the leave-now reminder replaces its earlier warning. */
data class DepartureReminder(
    val sessionId: String,
    val courseKey: String,
    val state: TravelState,
    val title: String,
    val text: String
) {
    val notificationId: Int get() = sessionId.hashCode()
}

/** Computes the reminder from this location's ETA, not from a sticky motion/UI state. */
class DepartureReminders(
    private val snapshots: Flow<TravelSnapshot>,
    private val preferences: (CourseSession) -> CourseReminderPreference,
    private val notify: (DepartureReminder) -> Boolean,
    private val preferenceChanges: Flow<Unit> = flowOf(Unit),
    private val cancelCourse: (String) -> Unit = {},
    private val wasSent: (CourseSession, TravelState) -> Boolean = { _, _ -> false },
    private val rememberSent: (CourseSession, TravelState) -> Unit = { _, _ -> }
) {
    private val sent = mutableSetOf<Pair<String, TravelState>>()

    suspend fun run() {
        combine(snapshots, preferenceChanges) { snapshot, _ -> snapshot }.collect(::consider)
    }

    fun consider(snapshot: TravelSnapshot): DepartureReminder? {
        val session = snapshot.session ?: run { sent.clear(); return null }
        val preference = preferences(session)
        if (!preference.enabled) {
            cancelCourse(session.reminderSeriesKey())
            return null
        }
        val estimate = snapshot.estimate ?: return null
        // Without fresh GPS the travel engine publishes no estimate, so no reminder is guessed.
        if (snapshot.state == TravelState.ARRIVED || snapshot.state == TravelState.EN_ROUTE) return null
        val now = snapshot.evaluatedAt ?: snapshot.minutesUntilClass?.let {
            session.start.toInstant().minusSeconds(it * 60)
        } ?: return null
        if (!now.isBefore(session.end.toInstant())) return null
        val leaveAt = session.start.toInstant().minusSeconds(
            estimate.durationMinutes * 60L + snapshot.weatherBufferMinutes * 60L
        )
        val secondsLeft = Duration.between(now, leaveAt).seconds
        if (secondsLeft > preference.leadMinutes.coerceIn(0, 60) * 60L) return null
        val state = if (secondsLeft <= 0) TravelState.RUNNING_LATE else TravelState.SHOULD_LEAVE_SOON
        val key = session.id to state
        if (key in sent || wasSent(session, state)) return null
        // An improved ETA must not produce an earlier warning after a leave-now notification.
        if (state == TravelState.SHOULD_LEAVE_SOON &&
            ((session.id to TravelState.RUNNING_LATE) in sent || wasSent(session, TravelState.RUNNING_LATE))) return null
        val minutesLeft = ((secondsLeft.coerceAtLeast(0) + 59) / 60).toInt()
        val deadline = leaveAt.atZone(session.start.zone).format(timeFormatter)
        val reminder = DepartureReminder(
            sessionId = session.id,
            courseKey = session.reminderSeriesKey(),
            state = state,
            title = if (secondsLeft <= 0) "Leave now for ${session.code}" else "Leave within $minutesLeft min · ${session.code}",
            text = buildString {
                append("${session.title.trimTrailingCommas()} starts at ${session.startTime.format(timeFormatter)}. ")
                append("${if (estimate.isApproximate) "Estimated travel time" else "Travel time"}: ${estimate.durationMinutes} min ${estimate.mode.asTravelPhrase()}. ")
                append(if (secondsLeft <= 0) "Leave now to avoid further delay." else "Leave by $deadline (within $minutesLeft min).")
                if (snapshot.weatherBufferMinutes > 0) append(" Includes ${snapshot.weatherBufferMinutes} min extra for weather.")
            }
        )
        if (!notify(reminder)) return null // Permission/channel disabled: retry when it becomes available.
        sent.add(key)
        rememberSent(session, state)
        return reminder
    }

    private fun TravelMode.asTravelPhrase(): String = when (this) {
        TravelMode.Walking -> "on foot"
        TravelMode.PublicTransport -> "by public transport"
        TravelMode.Driving -> "by car"
    }

    private companion object {
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}
