package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.sensing.location.TravelState
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.flow.Flow
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A departure notification: what it says and which class it is about. */
data class DepartureReminder(
    val sessionId: String,
    val state: TravelState,
    val title: String,
    val text: String
) {
    /** One notification per class, so a later reminder for the same class replaces the earlier one. */
    val notificationId: Int get() = sessionId.hashCode()
}

/**
 * Turns the travel engine's snapshots into departure notifications: one when a class reaches
 * "leave soon" and one more if the user is running late, never repeated for the same class.
 * Reminders can be switched off in the travel preferences.
 */
class DepartureReminders(
    private val snapshots: Flow<TravelSnapshot>,
    private val preferences: () -> TravelPreferences,
    private val notify: (DepartureReminder) -> Unit
) {
    private var remindedSessionId: String? = null
    private val remindedStates = mutableSetOf<TravelState>()

    suspend fun run() {
        snapshots.collect { snapshot -> consider(snapshot) }
    }

    /** Sends a reminder for [snapshot] when it is due; returns it, or null when nothing was sent. */
    fun consider(snapshot: TravelSnapshot): DepartureReminder? {
        val session = snapshot.session
        if (session == null) {
            remindedSessionId = null
            remindedStates.clear()
            return null
        }
        if (session.id != remindedSessionId) {
            remindedSessionId = session.id
            remindedStates.clear()
        }
        if (snapshot.state !in REMINDED_STATES) return null
        if (!preferences().remindersEnabled) return null
        if (!remindedStates.add(snapshot.state)) return null

        val reminder = DepartureReminder(
            sessionId = session.id,
            state = snapshot.state,
            title = when (snapshot.state) {
                TravelState.RUNNING_LATE -> "Running late for ${session.code}"
                else -> "Time to leave for ${session.code}"
            },
            text = describe(snapshot, session)
        )
        notify(reminder)
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

    private companion object {
        val REMINDED_STATES = setOf(TravelState.SHOULD_LEAVE_SOON, TravelState.RUNNING_LATE)
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}
