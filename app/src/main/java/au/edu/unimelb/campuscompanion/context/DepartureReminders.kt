package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.ui.model.trimTrailingCommas
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.MAX_COURSE_REMINDER_LEAD_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.MAX_COURSE_REMINDER_PREPARATION_MINUTES
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
    val title: UiText,
    val text: UiText,
    /**
     * The prompt this reminder belongs to ([CourseSession.departurePromptKey]): a late reminder
     * replaces the departure warning of the same class, and a class moved to a new start gets a new
     * prompt.
     */
    val key: String
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
 * - Preparation and urgency are each sounded once per class occurrence and account. Actual
 *   lateness silently updates an already delivered urgent alert instead of sounding a third time.
 *   Only successful deliveries are remembered, so a denied notification
 *   is retried while it is still due.
 * - A delivered prompt that is still shown is updated silently when its details change (another
 *   building, travel time or title). One the student dismissed is not brought back.
 * - A prompt is withdrawn once the student is on the way, has arrived or is in class; when its class
 *   was removed, moved to another start or day, lost its venue or is no longer the next one; and
 *   when reminders are turned off for its course.
 * - Nothing changes while the engine works out a new result ([TravelSnapshot.pending]), and a result
 *   worked out with earlier arrival or preparation settings is skipped: the engine
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
    private val shade: DepartureShade = DepartureShade.None,
    private val currentUserId: () -> String? = { "" },
    private val wasSent: (String, CourseSession, ContextState) -> Boolean = { _, _, _ -> false },
    private val rememberSent: (String, CourseSession, ContextState) -> Unit = { _, _, _ -> }
) {
    private var remindedScope: ReminderScope? = null
    private val remindedStates = mutableSetOf<ContextState>()

    /** The title and text of the prompts this instance posted or updated, to skip empty updates. */
    private val shownContent = mutableMapOf<String, Pair<UiText, UiText>>()

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
        if (snapshot.leadMinutes != preference.leadMinutes.coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES) ||
            snapshot.preparationMinutes != preference.preparationMinutes.coerceIn(0, MAX_COURSE_REMINDER_PREPARATION_MINUTES)) {
            return null
        }

        val key = session.departurePromptKey()
        val state = snapshot.state
        if (state in REMINDED_STATES && !delivered(userId, session, state)) {
            withdrawAllExcept(key)
            val reminder = reminder(snapshot, session, state, key)
            if (!notify(reminder)) return null
            remindedStates.add(state)
            rememberSent(userId, session, state)
            shownContent[key] = reminder.content
            return reminder
        }
        if (state !in REMINDED_STATES && (state !in STILL_AHEAD || snapshot.building == null)) {
            // On the way, arrived, in class or not today; or the venue is no longer known.
            withdrawAllExcept(null)
            return null
        }
        if (sent(userId, session, ContextState.LEAVE_NOW) && !lateDelivered(userId, session) &&
            state != ContextState.LEAVE_NOW && state != ContextState.RUNNING_LATE) {
            // The fresh no-progress evidence disappeared or the deadline moved later.
            // Do not keep an unsupported urgent claim, or sound the weaker preparation alert.
            withdrawAllExcept(null)
            return null
        }
        // The class is still ahead and was already prompted: keep that prompt, with current details.
        withdrawAllExcept(key)
        refreshShown(snapshot, session, key, userId)
        return null
    }

    /** Urgency and lateness share one audible escalation; neither allows a weaker reminder later. */
    private fun delivered(userId: String, session: CourseSession, state: ContextState): Boolean =
        sent(userId, session, state) || urgentDelivered(userId, session)

    private fun sent(userId: String, session: CourseSession, state: ContextState): Boolean =
        state in remindedStates || wasSent(userId, session, state)

    private fun urgentDelivered(userId: String, session: CourseSession): Boolean =
        sent(userId, session, ContextState.LEAVE_NOW) || lateDelivered(userId, session)

    private fun lateDelivered(userId: String, session: CourseSession): Boolean =
        sent(userId, session, ContextState.RUNNING_LATE)

    /** Removes every shown prompt except [keep]'s; the others belong to other or earlier classes. */
    private fun withdrawAllExcept(keep: String?) {
        shade.shownKeys().filter { it != keep }.forEach(shade::withdraw)
        shownContent.keys.retainAll { it == keep }
    }

    /** Updates the shown prompt silently when what it says has changed. A dismissed one stays away. */
    private fun refreshShown(snapshot: TravelSnapshot, session: CourseSession, key: String, userId: String) {
        if (key !in shade.shownKeys()) return
        val state = when {
            lateDelivered(userId, session) ||
                (snapshot.state == ContextState.RUNNING_LATE && urgentDelivered(userId, session)) -> ContextState.RUNNING_LATE
            urgentDelivered(userId, session) -> ContextState.LEAVE_NOW
            else -> ContextState.LEAVE_SOON
        }
        val reminder = reminder(snapshot, session, state, key)
        if (shownContent[key] == reminder.content) return
        shade.update(reminder)
        shownContent[key] = reminder.content
    }

    private fun reminder(snapshot: TravelSnapshot, session: CourseSession, state: ContextState, key: String) =
        DepartureReminder(
            sessionId = session.id,
            courseKey = session.reminderSeriesKey(),
            state = state,
            title = when (state) {
                ContextState.RUNNING_LATE -> UiText(R.string.departure_running_late, session.code)
                ContextState.LEAVE_NOW -> UiText(R.string.departure_leave_now, session.code)
                else -> UiText(R.string.departure_prepare_to_leave, session.code)
            },
            text = describe(snapshot, session, state),
            key = key
        )

    private val DepartureReminder.content: Pair<UiText, UiText> get() = title to text

    private fun describe(snapshot: TravelSnapshot, session: CourseSession, state: ContextState): UiText =
        UiText.Joined(buildList {
            val startsAt = session.startTime.format(timeFormatter)
            add(snapshot.building?.let {
                UiText(R.string.departure_class_starts_at_building, session.title.trimTrailingCommas(), startsAt, it.name)
            } ?: UiText(R.string.departure_class_starts, session.title.trimTrailingCommas(), startsAt))
            if (state == ContextState.LEAVE_NOW) add(UiText(R.string.departure_not_approaching))
            if (state == ContextState.LEAVE_SOON || state == ContextState.LEAVE_NOW) {
                snapshot.leaveBy?.let {
                    add(UiText(R.string.departure_leave_by_time, it.withZoneSameInstant(session.start.zone).format(timeFormatter)))
                }
            }
            snapshot.estimate?.let { estimate ->
                add(UiText(if (estimate.isApproximate) R.string.departure_estimated_travel else R.string.departure_about_travel,
                    estimate.durationMinutes, estimate.mode.asTravelPhrase()))
            }
            if (snapshot.weatherBufferMinutes > 0) {
                add(UiText(R.string.departure_allow_weather, snapshot.weatherBufferMinutes))
            }
        })

    private fun TravelMode.asTravelPhrase(): UiText = when (this) {
        TravelMode.Walking -> UiText(R.string.departure_on_foot)
        TravelMode.PublicTransport -> UiText(R.string.departure_by_public_transport)
        TravelMode.Driving -> UiText(R.string.departure_by_car)
    }

    private data class ReminderScope(val userId: String, val sessionId: String, val startsAt: Instant)

    private companion object {
        val REMINDED_STATES = setOf(ContextState.LEAVE_SOON, ContextState.LEAVE_NOW, ContextState.RUNNING_LATE)

        /** The class is still ahead and the student has not set off: a prompt already shown stays. */
        val STILL_AHEAD = setOf(ContextState.UPCOMING, ContextState.LONG_BREAK, ContextState.POST_CLASS)
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}
