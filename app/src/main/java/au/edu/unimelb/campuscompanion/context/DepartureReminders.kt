package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrend
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

/** Notification urgency is separate from the UI trip state (the urgent warning precedes lateness). */
enum class DepartureReminderStage { Prepare, LeaveNow }

/** One notification per class; the leave-now reminder replaces its earlier warning. */
data class DepartureReminder(
    val sessionId: String,
    val courseKey: String,
    val stage: DepartureReminderStage,
    val title: UiText,
    val text: UiText
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
    private val wasSent: (CourseSession, DepartureReminderStage) -> Boolean = { _, _ -> false },
    private val rememberSent: (CourseSession, DepartureReminderStage) -> Unit = { _, _ -> }
) {
    private val sent = mutableSetOf<Pair<String, DepartureReminderStage>>()

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
        val notApproaching = snapshot.destinationProgress == DistanceTrend.STABLE ||
            snapshot.destinationProgress == DistanceTrend.MOVING_AWAY
        if (snapshot.state == TravelState.ARRIVED || snapshot.destinationProgress == DistanceTrend.APPROACHING ||
            (snapshot.state == TravelState.EN_ROUTE && !notApproaching)) return null
        val now = snapshot.evaluatedAt ?: snapshot.minutesUntilClass?.let {
            session.start.toInstant().minusSeconds(it * 60)
        } ?: return null
        if (!now.isBefore(session.end.toInstant())) return null
        val leaveAt = session.start.toInstant().minusSeconds(
            estimate.durationMinutes * 60L + snapshot.weatherBufferMinutes * 60L
        )
        val secondsLeft = Duration.between(now, leaveAt).seconds
        val urgent = notApproaching && secondsLeft <= URGENT_LEAD_MINUTES * 60L
        val leaveNow = urgent || secondsLeft <= 0
        if (!leaveNow && secondsLeft > preference.leadMinutes.coerceIn(0, 60) * 60L) return null
        val stage = if (leaveNow) DepartureReminderStage.LeaveNow else DepartureReminderStage.Prepare
        val key = session.id to stage
        if (key in sent || wasSent(session, stage)) return null
        // An improved ETA must not produce an earlier warning after a leave-now notification.
        if (stage == DepartureReminderStage.Prepare &&
            ((session.id to DepartureReminderStage.LeaveNow) in sent || wasSent(session, DepartureReminderStage.LeaveNow))) return null
        val minutesLeft = ((secondsLeft.coerceAtLeast(0) + 59) / 60).toInt()
        val deadline = leaveAt.atZone(session.start.zone).format(timeFormatter)
        val reminder = DepartureReminder(
            sessionId = session.id,
            courseKey = session.reminderSeriesKey(),
            stage = stage,
            title = if (leaveNow) UiText(R.string.departure_leave_now, session.code)
                else UiText(R.string.departure_leave_within, minutesLeft, session.code),
            text = UiText.Joined(buildList {
                add(UiText(R.string.departure_class_starts, session.title.trimTrailingCommas(), session.startTime.format(timeFormatter)))
                add(UiText(if (estimate.isApproximate) R.string.departure_estimated_travel else R.string.departure_travel,
                    estimate.durationMinutes, estimate.mode.asTravelPhrase()))
                add(when {
                    urgent && secondsLeft > 0 -> UiText(R.string.departure_not_approaching)
                    leaveNow -> UiText(R.string.departure_avoid_delay)
                    else -> UiText(R.string.departure_leave_by, deadline, minutesLeft)
                })
                if (snapshot.weatherBufferMinutes > 0) add(UiText(R.string.departure_weather_buffer, snapshot.weatherBufferMinutes))
            })
        )
        if (!notify(reminder)) return null // Permission/channel disabled: retry when it becomes available.
        sent.add(key)
        rememberSent(session, stage)
        return reminder
    }

    private fun TravelMode.asTravelPhrase(): UiText = when (this) {
        TravelMode.Walking -> UiText(R.string.departure_on_foot)
        TravelMode.PublicTransport -> UiText(R.string.departure_by_public_transport)
        TravelMode.Driving -> UiText(R.string.departure_by_car)
    }

    private companion object {
        const val URGENT_LEAD_MINUTES = 5
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}
