package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrend
import au.edu.unimelb.campuscompanion.sensing.location.TravelState
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.MAX_COURSE_REMINDER_LEAD_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import java.time.Instant
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
    val notificationId: Int get() = key.hashCode()
}

/** The departure prompt of this class occurrence: one per class id and start. */
fun CourseSession.departurePromptKey(): String = "$id@${start.toInstant()}"

/**
 * The departure prompts in the notification shade, beyond posting one with sound (the `notify` of
 * [DepartureReminders]). On Android this is `push.DepartureNotifier`.
 */
interface DepartureShade {
    /** Keys of the departure prompts the student can see now; older app versions used other keys. */
    fun shownKeys(): Set<String>

    /** Replaces the content of a prompt that is still shown, without sound or vibration. */
    fun update(reminder: DepartureReminder)

    /** Removes the prompt with this key. */
    fun withdraw(key: String)

    companion object {
        /** No access to the shade: prompts are only posted, never updated or withdrawn. */
        val None: DepartureShade = object : DepartureShade {
            override fun shownKeys(): Set<String> = emptySet()
            override fun update(reminder: DepartureReminder) = Unit
            override fun withdraw(key: String) = Unit
        }
    }
}

/**
 * Turns the travel engine's snapshots into departure prompts and keeps the shade in line with them.
 * At most one prompt is wanted at a time: the tracked class's, from the moment it is time to leave
 * until the student sets off or arrives, or the class stops being the next one.
 *
 * - The warning ("leave soon") and the late reminder are each delivered once per class occurrence
 *   and account, with sound. Only successful deliveries are remembered, so a denied notification
 *   is retried while it is still due.
 * - A delivered prompt that is still shown is updated silently when its details change (another
 *   building, travel time or title). One the student dismissed is not brought back.
 * - A prompt is withdrawn once the student is on the way, has arrived or is in class; when its class
 *   was removed, moved to another start or day, lost its venue or is no longer the next one; and
 *   when reminders are turned off for its course.
 * - Nothing changes while the engine works out a new result ([TravelSnapshot.pending]), and a result
 *   worked out with an earlier arrival buffer than the course's current one is skipped: the engine
 *   is already recomputing it.
 *
 * Each notification reads the saved preference of its own course, including in the background.
 * Signing out clears the shade separately, since "no current user" can also mean that the session
 * is still being restored.
 */
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
        combine(snapshots, preferenceChanges) { snapshot, _ -> snapshot }
            .collect { snapshot -> consider(snapshot) }
    }

    /** Brings the shade in line with [snapshot]; returns the reminder delivered with sound, or null. */
    fun consider(snapshot: TravelSnapshot): DepartureReminder? {
        if (snapshot.pending) return null
        val session = snapshot.session
        val userId = currentUserId()
        if (session == null || userId == null) {
            remindedScope = null
            remindedStates.clear()
            // No class to leave for: whatever is still shown belongs to an earlier or removed class.
            if (userId != null) withdrawAllExcept(null)
            return null
        }
        val scope = ReminderScope(userId, session.id, session.start.toInstant())
        if (scope != remindedScope) {
            remindedScope = scope
            remindedStates.clear()
        }
        val preference = preferences(session)
        if (!preference.enabled) {
            withdrawAllExcept(null)
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

    private data class ReminderScope(val userId: String, val sessionId: String, val startsAt: Instant)

    private companion object {
        const val URGENT_LEAD_MINUTES = 5
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}
