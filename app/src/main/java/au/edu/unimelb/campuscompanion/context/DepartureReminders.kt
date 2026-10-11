package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A departure notification: what it says and which class it is about. */
data class DepartureReminder(
    val sessionId: String,
    val courseKey: String,
    val state: ContextState,
    val title: String,
    val text: String
) {
    /** One notification per class, so a later reminder for the same class replaces the earlier one. */
    val notificationId: Int get() = sessionId.hashCode()
}

/**
 * Turns the travel engine's snapshots into departure notifications: one when a class reaches
 * "leave soon" and one more if the user is running late. Only successful deliveries are remembered,
 * so denied notifications can be retried while the context still calls for a departure reminder.
 * Each notification reads the saved preference of its own course, including in the background.
 */
class DepartureReminders(
    private val snapshots: Flow<TravelSnapshot>,
    private val preferences: (CourseSession) -> CourseReminderPreference,
    private val notify: (DepartureReminder) -> Boolean,
    private val preferenceChanges: Flow<Unit> = flowOf(Unit),
    private val cancelCourse: (String) -> Unit = {},
    private val currentUserId: () -> String? = { "" },
    private val wasSent: (String, CourseSession, ContextState) -> Boolean = { _, _, _ -> false },
    private val rememberSent: (String, CourseSession, ContextState) -> Unit = { _, _, _ -> }
) {
    private var remindedScope: ReminderScope? = null
    private val remindedStates = mutableSetOf<ContextState>()

    suspend fun run() {
        combine(snapshots, preferenceChanges) { snapshot, _ -> snapshot }
            .collect { snapshot -> consider(snapshot) }
    }

    /** Sends a reminder for [snapshot] when it is due; returns it, or null when nothing was sent. */
    fun consider(snapshot: TravelSnapshot): DepartureReminder? {
        val session = snapshot.session
        val userId = currentUserId()
        if (session == null || userId == null) {
            remindedScope = null
            remindedStates.clear()
            return null
        }
        val scope = ReminderScope(userId, session.id, session.start.toInstant())
        if (scope != remindedScope) {
            remindedScope = scope
            remindedStates.clear()
        }
        if (!preferences(session).enabled) {
            cancelCourse(session.reminderSeriesKey())
            return null
        }
        if (snapshot.state !in REMINDED_STATES) return null
        if (snapshot.state in remindedStates || wasSent(userId, session, snapshot.state)) return null
        // An improved ETA must not replace a delivered late reminder with a weaker departure warning.
        if (snapshot.state == ContextState.LEAVE_SOON &&
            (ContextState.RUNNING_LATE in remindedStates || wasSent(userId, session, ContextState.RUNNING_LATE))) {
            return null
        }

        val reminder = DepartureReminder(
            sessionId = session.id,
            courseKey = session.reminderSeriesKey(),
            state = snapshot.state,
            title = when (snapshot.state) {
                ContextState.RUNNING_LATE -> "Running late for ${session.code}"
                else -> "Time to leave for ${session.code}"
            },
            text = describe(snapshot, session)
        )
        if (!notify(reminder)) return null
        remindedStates.add(snapshot.state)
        rememberSent(userId, session, snapshot.state)
        return reminder
    }

    private fun describe(snapshot: TravelSnapshot, session: CourseSession): String {
        val startsAt = session.startTime.format(timeFormatter)
        val place = snapshot.building?.name?.let { " in $it" }.orEmpty()
        val sentences = mutableListOf("${session.title} starts at $startsAt$place.")
        snapshot.estimate?.let { estimate ->
            sentences += "About ${estimate.durationMinutes} min ${estimate.mode.asTravelPhrase()}."
        }
        if (snapshot.weatherBufferMinutes > 0) {
            sentences += "Allow ${snapshot.weatherBufferMinutes} min more for the weather."
        }
        return sentences.joinToString(" ")
    }

    private fun TravelMode.asTravelPhrase(): String = when (this) {
        TravelMode.Walking -> "on foot"
        TravelMode.PublicTransport -> "by public transport"
        TravelMode.Driving -> "by car"
    }

    private data class ReminderScope(val userId: String, val sessionId: String, val startsAt: Instant)

    private companion object {
        val REMINDED_STATES = setOf(ContextState.LEAVE_SOON, ContextState.RUNNING_LATE)
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}
