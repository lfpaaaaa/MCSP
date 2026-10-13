package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrend
import au.edu.unimelb.campuscompanion.ui.model.trimTrailingCommas
import java.time.Duration
import au.edu.unimelb.campuscompanion.data.TravelMode
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

/** Reminder urgency is distinct from the student's current context state. */
enum class DepartureReminderStage { Prepare, LeaveNow }

/** A departure notification: what it says and which class it is about. */
data class DepartureReminder(
    val sessionId: String,
    val courseKey: String,
    val stage: DepartureReminderStage,
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
 * - The warning ("leave soon") and the late reminder are each delivered once per class occurrence
 *   and account, with sound. Only successful deliveries are remembered, so a denied notification
 *   is retried while it is still due.
 * - A delivered prompt that is still shown is updated silently when its details change (another
 *   building, travel time or title). One the student dismissed is not brought back.
 * - A prompt is withdrawn once the student is on the way, has arrived or is in class; when its class
 *   was removed, moved to another start or day, lost its venue or is no longer the next one; and
 *   when reminders are turned off for its course.
 * - Nothing changes while the engine works out a new result ([TravelSnapshot.pending]), and a result
 *   worked out with an earlier preparation lead than the course's current one is skipped: the engine
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
    private val wasSent: (String, CourseSession, DepartureReminderStage) -> Boolean = { _, _, _ -> false },
    private val rememberSent: (String, CourseSession, DepartureReminderStage) -> Unit = { _, _, _ -> }
) {
    private var remindedScope: ReminderScope? = null
    private val remindedStates = mutableSetOf<DepartureReminderStage>()

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
        if (snapshot.leadMinutes != preference.leadMinutes.coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES)) {
            return null
        }

        val key = session.departurePromptKey()
        val notApproaching = snapshot.destinationProgress == DistanceTrend.STABLE ||
            snapshot.destinationProgress == DistanceTrend.MOVING_AWAY
        if (snapshot.building == null || snapshot.state in setOf(ContextState.ARRIVED, ContextState.IN_CLASS,
                ContextState.NO_UPCOMING_CLASS) || snapshot.destinationProgress == DistanceTrend.APPROACHING ||
            (snapshot.state == ContextState.EN_ROUTE && !notApproaching)) {
            withdrawAllExcept(null)
            return null
        }
        val now = snapshot.evaluatedAt ?: snapshot.minutesUntilClass?.let {
            session.start.toInstant().minusSeconds(it * 60)
        } ?: return null
        if (!now.isBefore(session.end.toInstant())) {
            withdrawAllExcept(null)
            return null
        }
        withdrawAllExcept(key)
        // No ETA means no guessed warning. Keep an existing prompt until a fresh result is ready.
        val secondsLeft = secondsToDeparture(snapshot, now) ?: return null
        val urgent = snapshot.needsUrgentDeparture(now)
        val stage = when {
            urgent || secondsLeft <= 0 -> DepartureReminderStage.LeaveNow
            secondsLeft <= preference.leadMinutes.coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES) * 60L ->
                DepartureReminderStage.Prepare
            else -> null
        }
        if (stage != null && !delivered(userId, session, stage)) {
            val reminder = reminder(snapshot, session, stage, key)
            if (!notify(reminder)) return null
            remindedStates.add(stage)
            rememberSent(userId, session, stage)
            shownContent[key] = reminder.content
            return reminder
        }
        // Keep a delivered prompt current without bringing back one the student dismissed.
        refreshShown(snapshot, session, key, userId)
        return null
    }

    private fun delivered(userId: String, session: CourseSession, stage: DepartureReminderStage): Boolean =
        stage in remindedStates || wasSent(userId, session, stage) ||
            (stage == DepartureReminderStage.Prepare && urgentDelivered(userId, session))

    private fun urgentDelivered(userId: String, session: CourseSession): Boolean =
        DepartureReminderStage.LeaveNow in remindedStates || wasSent(userId, session, DepartureReminderStage.LeaveNow)

    /** Removes every shown prompt except [keep]'s; the others belong to other or earlier classes. */
    private fun withdrawAllExcept(keep: String?) {
        shade.shownKeys().filter { it != keep }.forEach(shade::withdraw)
        shownContent.keys.retainAll { it == keep }
    }

    /** Updates the shown prompt silently when what it says has changed. A dismissed one stays away. */
    private fun refreshShown(snapshot: TravelSnapshot, session: CourseSession, key: String, userId: String) {
        if (key !in shade.shownKeys()) return
        val stage = if (urgentDelivered(userId, session)) DepartureReminderStage.LeaveNow else DepartureReminderStage.Prepare
        val reminder = reminder(snapshot, session, stage, key)
        if (shownContent[key] == reminder.content) return
        shade.update(reminder)
        shownContent[key] = reminder.content
    }

    private fun secondsToDeparture(snapshot: TravelSnapshot, now: Instant): Long? =
        snapshot.departureTime()?.let { Duration.between(now, it).seconds }

    private fun reminder(snapshot: TravelSnapshot, session: CourseSession, stage: DepartureReminderStage, key: String): DepartureReminder {
        val now = snapshot.evaluatedAt ?: session.start.toInstant().minusSeconds((snapshot.minutesUntilClass ?: 0) * 60)
        val secondsLeft = secondsToDeparture(snapshot, now) ?: 0
        val minutesLeft = ((secondsLeft.coerceAtLeast(0) + 59) / 60).toInt()
        val leaveNow = stage == DepartureReminderStage.LeaveNow
        return DepartureReminder(
            sessionId = session.id,
            courseKey = session.reminderSeriesKey(),
            stage = stage,
            title = if (leaveNow) UiText(R.string.departure_leave_now, session.code)
                else UiText(R.string.departure_leave_within, minutesLeft, session.code),
            text = UiText.Joined(buildList {
                add(snapshot.building?.let {
                    UiText(R.string.departure_class_starts_at_building, session.title.trimTrailingCommas(), session.startTime.format(timeFormatter), it.name)
                } ?: UiText(R.string.departure_class_starts, session.title.trimTrailingCommas(), session.startTime.format(timeFormatter)))
                snapshot.estimate?.let { estimate ->
                    add(UiText(if (estimate.isApproximate) R.string.departure_estimated_travel else R.string.departure_travel,
                        estimate.durationMinutes, estimate.mode.asTravelPhrase()))
                }
                add(when {
                    leaveNow && secondsLeft > 0 && snapshot.destinationProgress in setOf(DistanceTrend.STABLE, DistanceTrend.MOVING_AWAY) ->
                        UiText(R.string.departure_not_approaching)
                    leaveNow -> UiText(R.string.departure_avoid_delay)
                    else -> UiText(R.string.departure_leave_by,
                        snapshot.departureTime()?.atZone(session.start.zone)?.format(timeFormatter).orEmpty(), minutesLeft)
                })
                if (snapshot.weatherBufferMinutes > 0) add(UiText(R.string.departure_weather_buffer, snapshot.weatherBufferMinutes))
            }),
            key = key
        )
    }

    private val DepartureReminder.content: Pair<UiText, UiText> get() = title to text

    private fun TravelMode.asTravelPhrase(): UiText = when (this) {
        TravelMode.Walking -> UiText(R.string.departure_on_foot)
        TravelMode.PublicTransport -> UiText(R.string.departure_by_public_transport)
        TravelMode.Driving -> UiText(R.string.departure_by_car)
    }

    private data class ReminderScope(val userId: String, val sessionId: String, val startsAt: Instant)

    private companion object {
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}

/** Physical deadline: preparation time is only the lead for the first warning. */
internal fun TravelSnapshot.departureTime(): Instant? = session?.let {
    ContextEngine.leaveBy(it.start.toInstant(), estimate?.durationMinutes, weatherBufferMinutes, entryAllowanceMinutes)
}

/** The same five-minute urgency is shown on Home and delivered as a notification. */
internal fun TravelSnapshot.needsUrgentDeparture(now: Instant): Boolean {
    val course = session ?: return false
    val deadline = departureTime() ?: return false
    return !pending && building != null && now.isBefore(course.end.toInstant()) &&
        state !in setOf(ContextState.ARRIVED, ContextState.IN_CLASS, ContextState.NO_UPCOMING_CLASS) &&
        destinationProgress in setOf(DistanceTrend.STABLE, DistanceTrend.MOVING_AWAY) &&
        !now.isBefore(deadline.minusSeconds(5 * 60L))
}
