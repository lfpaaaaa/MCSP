package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/** Tuning for [ContextEngine]. The defaults are first guesses, to adjust after testing on campus. */
data class ContextConfig(
    /** Minutes from the building entrance to the room, added to the departure buffer. */
    val entryAllowanceMinutes: Int = 0,
    /** How long before the leave-by time the state becomes [ContextState.LEAVE_SOON]. */
    val leaveSoonWindowMinutes: Int = 5,
    /** How long before the leave-by time walking towards the class counts as setting off for it. */
    val earlyDepartureWindowMinutes: Int = 45,
    /** Time left before the leave-by time that counts as a long break (after an earlier class today). */
    val longBreakMinutes: Int = 60,
    /** How long a finished class stays as [ContextState.POST_CLASS]. */
    val postClassMinutes: Int = 15,
    /** How long after the start a student who is elsewhere counts as running late rather than absent. */
    val lateGraceMinutes: Int = 15,
    /** Distance from the building that counts as arrived; matches the arrival geofence. */
    val arrivalRadiusMeters: Double = 75.0,
    /** Distance at which an arrival stops counting, so GPS jitter at the door does not flip the state. */
    val leftBuildingMeters: Double = 200.0,
    /** How much closer to the building a walking student must get to count as en route. */
    val closingMeters: Double = 50.0,
    /** How far back from their closest point an en-route student must go to stop counting as en route. */
    val turnedBackMeters: Double = 150.0
)

/** Everything the engine looks at for one decision. */
data class ContextInputs(
    val now: Instant,
    /** The whole timetable; the engine picks the class to track with [ContextEngine.trackedSession]. */
    val sessions: List<CourseSession>,
    /** Travel time from the current position to the tracked class's building, or null when unknown. */
    val travelMinutes: Int?,
    /** Extra minutes on top of the travel time: the student's buffer plus the weather buffer. */
    val bufferMinutes: Int,
    /** Straight-line distance to the tracked class's building, or null when the position or building is unknown. */
    val distanceMeters: Double?,
    /** The phone detects walking or other movement. */
    val isMoving: Boolean,
    /**
     * The sensing service's combined verdict (moving, distance falling, heading towards the building),
     * or null when it does not provide one. Without it the engine checks walking and distance itself.
     */
    val enRouteSignal: Boolean? = null,
    /** The arrival geofence of the tracked class's building has been entered. */
    val insideGeofence: Boolean = false
)

/** What the engine remembers about the trip to one class between updates. */
data class ContextMemory(
    val sessionId: String? = null,
    val arrived: Boolean = false,
    val enRoute: Boolean = false,
    /** Farthest distance from the building since the departure window opened, while not en route. */
    val farthestMeters: Double? = null,
    /** Closest distance to the building since setting off. */
    val closestMeters: Double? = null
)

/** One decision of the engine. Pass it back as `previous` with the next inputs. */
data class ContextDecision(
    val state: ContextState,
    /** The class the state is about: the one in progress, or else the next one to start. */
    val session: CourseSession?,
    /** The class that finished in the last [ContextConfig.postClassMinutes] minutes, if any. */
    val finishedSession: CourseSession?,
    /** When to leave to arrive with the buffer in hand, or null when the travel time is unknown. */
    val leaveBy: Instant?,
    val memory: ContextMemory
)

/**
 * The context state machine. It decides what the student is doing about their next class from
 * the timetable, the clock, the travel time and the position and motion sensors.
 *
 * Departure time: leave by = class start - travel time - buffer (student's minutes plus weather)
 * - entry allowance.
 *
 * [decide] is a pure function of its inputs and the previous decision, so each rule can be unit
 * tested with a fixed clock. [TravelEngine] gathers the inputs and keeps the previous decision.
 */
object ContextEngine {

    /** The class to track: the one in progress, or else the next one to start. */
    fun trackedSession(sessions: List<CourseSession>, now: Instant): CourseSession? =
        sessions
            .filter { it.end.toInstant().isAfter(now) }
            .minByOrNull { it.start.toInstant() }

    /** The class that finished most recently, if it finished less than [window] ago. */
    fun recentlyFinished(sessions: List<CourseSession>, now: Instant, window: Duration): CourseSession? =
        sessions
            .filter { session ->
                val end = session.end.toInstant()
                !end.isAfter(now) && Duration.between(end, now) < window
            }
            .maxByOrNull { it.end.toInstant() }

    /** Class start minus travel time, buffer and entry allowance; null when the travel time is unknown. */
    fun leaveBy(start: Instant, travelMinutes: Int?, bufferMinutes: Int, entryAllowanceMinutes: Int): Instant? {
        if (travelMinutes == null) {
            return null
        }
        val total = travelMinutes.coerceAtLeast(0) +
            bufferMinutes.coerceAtLeast(0) +
            entryAllowanceMinutes.coerceAtLeast(0)
        return start.minus(minutes(total))
    }

    fun decide(
        inputs: ContextInputs,
        previous: ContextDecision? = null,
        config: ContextConfig = ContextConfig()
    ): ContextDecision {
        val now = inputs.now
        val session = trackedSession(inputs.sessions, now)
        val finished = recentlyFinished(inputs.sessions, now, minutes(config.postClassMinutes))

        if (session == null) {
            return ContextDecision(
                state = if (finished != null) ContextState.POST_CLASS else ContextState.NO_UPCOMING_CLASS,
                session = null,
                finishedSession = finished,
                leaveBy = null,
                memory = ContextMemory()
            )
        }

        val start = session.start.toInstant()
        val leaveBy = leaveBy(start, inputs.travelMinutes, inputs.bufferMinutes, config.entryAllowanceMinutes)
        val departureWindowOpens = (leaveBy ?: start).minus(minutes(config.earlyDepartureWindowMinutes))
        val inDepartureWindow = !now.isBefore(departureWindowOpens)

        // Memory belongs to one class: a new class starts with a clean slate.
        val memory = updateMemory(
            previous = previous?.memory?.takeIf { it.sessionId == session.id } ?: ContextMemory(session.id),
            inputs = inputs,
            inDepartureWindow = inDepartureWindow,
            config = config
        )

        val travelMinutes = inputs.travelMinutes
        val state = when {
            !now.isBefore(start) -> stateDuringClass(memory, inputs, start, config)
            memory.arrived && inDepartureWindow -> ContextState.ARRIVED
            memory.enRoute -> ContextState.EN_ROUTE
            travelMinutes != null && now.plus(minutes(travelMinutes)).isAfter(start) -> ContextState.RUNNING_LATE
            leaveBy != null && !now.isBefore(leaveBy.minus(minutes(config.leaveSoonWindowMinutes))) ->
                ContextState.LEAVE_SOON
            finished != null -> ContextState.POST_CLASS
            !startsToday(session, now) -> ContextState.NO_UPCOMING_CLASS
            hadClassEarlierToday(inputs.sessions, session, now) &&
                Duration.between(now, leaveBy ?: start) >= minutes(config.longBreakMinutes) -> ContextState.LONG_BREAK
            else -> ContextState.UPCOMING
        }

        return ContextDecision(
            state = state,
            session = session,
            finishedSession = finished,
            leaveBy = leaveBy,
            memory = memory
        )
    }

    private fun stateDuringClass(
        memory: ContextMemory,
        inputs: ContextInputs,
        start: Instant,
        config: ContextConfig
    ): ContextState {
        val withinGrace = Duration.between(start, inputs.now) < minutes(config.lateGraceMinutes)
        return when {
            memory.arrived -> ContextState.IN_CLASS
            withinGrace && memory.enRoute -> ContextState.EN_ROUTE
            // Known to be away from the building (otherwise memory.arrived would be set).
            withinGrace && inputs.distanceMeters != null -> ContextState.RUNNING_LATE
            // Position unknown, or long past the start: assume the student is in class.
            else -> ContextState.IN_CLASS
        }
    }

    /** Updates the arrival and en-route memory with the latest position and motion. */
    private fun updateMemory(
        previous: ContextMemory,
        inputs: ContextInputs,
        inDepartureWindow: Boolean,
        config: ContextConfig
    ): ContextMemory {
        val distance = inputs.distanceMeters
        val stillNearBuilding = distance == null || distance <= config.leftBuildingMeters
        val arrived = when {
            distance != null && distance <= config.arrivalRadiusMeters -> true
            inputs.insideGeofence && stillNearBuilding -> true
            previous.arrived && stillNearBuilding -> true
            else -> false
        }
        if (arrived) {
            return previous.copy(arrived = true, enRoute = false, farthestMeters = null, closestMeters = null)
        }
        if (distance == null) {
            // Nothing new about the trip; keep what was known.
            return previous.copy(arrived = false)
        }

        if (previous.enRoute) {
            val closest = min(previous.closestMeters ?: distance, distance)
            return if (distance - closest >= config.turnedBackMeters) {
                // Walking away from the class: no longer on the way.
                previous.copy(arrived = false, enRoute = false, farthestMeters = distance, closestMeters = null)
            } else {
                // Stopping at a crossing or a tram stop keeps the student en route.
                previous.copy(arrived = false, closestMeters = closest)
            }
        }

        if (!inDepartureWindow) {
            // Travel long before the class (coming to campus in the morning) is not this trip.
            return previous.copy(arrived = false, farthestMeters = distance)
        }

        val farthest = max(previous.farthestMeters ?: distance, distance)
        val setOff = inputs.enRouteSignal == true ||
            (inputs.isMoving && farthest - distance >= config.closingMeters)
        return if (setOff) {
            previous.copy(arrived = false, enRoute = true, farthestMeters = null, closestMeters = distance)
        } else {
            previous.copy(arrived = false, farthestMeters = farthest)
        }
    }

    private fun startsToday(session: CourseSession, now: Instant): Boolean =
        session.startDate == today(session, now)

    private fun hadClassEarlierToday(sessions: List<CourseSession>, session: CourseSession, now: Instant): Boolean {
        val today = today(session, now)
        return sessions.any { other ->
            other.id != session.id &&
                !other.end.toInstant().isAfter(now) &&
                other.end.withZoneSameInstant(session.start.zone).toLocalDate() == today
        }
    }

    private fun today(session: CourseSession, now: Instant): LocalDate =
        now.atZone(session.start.zone).toLocalDate()

    private fun minutes(value: Int): Duration = Duration.ofMinutes(value.toLong())
}
