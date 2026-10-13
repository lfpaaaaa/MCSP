package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.building.BuildingLookup
import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.data.repository.WeatherRepository
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrend
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.MAX_COURSE_REMINDER_LEAD_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.matchesTimetableSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Joins the timetable, the position sensors and the routing service into one picture of the trip
 * to the next class ([snapshot]):
 *
 * - the next class to start (or the one in progress) is tracked, and its building is looked up
 *   from the timetable location;
 * - the travel time from the current position comes from [EtaRepository], with the travel mode
 *   chosen by the user's [TravelPreferences];
 * - the context state (upcoming, leave soon, en route, arrived, in class, ...) and the leave-by
 *   time come from [ContextEngine], fed with the timetable, the travel time, the distance and the
 *   motion signals. The lead time set on the schedule screen controls the preparation warning, and
 *   rain or heat at the class's building ([WeatherBuffer]) advances the departure deadline.
 *
 * Inputs arrive through the update functions; [run] keeps the snapshot current.
 */
class TravelEngine(
    private val eta: EtaRepository,
    private val buildings: BuildingLookup,
    private val preferences: () -> TravelPreferences,
    private val weather: WeatherRepository? = null,
    private val clock: () -> Instant = Instant::now,
    private val tickInterval: Duration = TICK_INTERVAL,
    private val reminderPreference: ((CourseSession) -> CourseReminderPreference)? = null,
    private val reminderChanges: Flow<Unit> = emptyFlow(),
    private val config: ContextConfig = ContextConfig(),
    private val maxLocationAge: Duration = Duration.ofMinutes(2)
) {
    private val inputs = MutableStateFlow(Inputs())

    private val _snapshot = MutableStateFlow(TravelSnapshot.PENDING)

    /** The latest result, or [TravelSnapshot.PENDING] while the engine works out a new one. */
    val snapshot: StateFlow<TravelSnapshot> = _snapshot.asStateFlow()

    // Never hold this monitor across a network suspension. Timetable changes and result publication
    // use it together so a superseded request cannot restore either guidance or decision memory.
    private val inputLock = Any()
    private val refreshLock = Mutex()
    private var motionTarget: MotionTarget? = null
    private var decision: ContextDecision? = null
    private val progressTracker = DestinationProgressTracker()

    /** The timetable sessions; [reset] also starts a new account scope, even with identical classes. */
    fun updateSessions(value: List<CourseSession>, reset: Boolean = false) = synchronized(inputLock) {
        val before = inputs.value
        val sessions = value.toList()
        if (!reset && before.sessionsInitialized && before.sessions == sessions) return@synchronized
        val changed = reset || !before.sessionsInitialized || before.sessions.size != sessions.size ||
            before.sessions.zip(sessions).any { (old, current) -> !old.matchesTimetableSession(current) }
        val now = clock()
        val oldTracked = ContextEngine.trackedSession(before.sessions, now)
        val tracked = ContextEngine.trackedSession(sessions, now)
        // A different class (or account) starts a new trip here. A same-ID venue edit is checked
        // against its resolved building on the next refresh, before any evidence is used, so
        // room, title and time corrections in the same building keep the student's arrival.
        val trackingChanged = reset || oldTracked?.id != tracked?.id
        if (trackingChanged) {
            decision = null
            motionTarget = null
            progressTracker.reset()
        }
        val snapshot = _snapshot.value
        val finishedChanged = snapshot.finishedSession?.let { finished ->
            sessions.none { it.matchesTimetableSession(finished) }
        } == true
        if (reset || !sameSession(snapshot.session, tracked) || finishedChanged) {
            // Home binds the current class independently and shows Updating until a fresh result.
            _snapshot.value = TravelSnapshot.PENDING
        }
        inputs.value = before.copy(
            sessions = sessions,
            sessionsInitialized = true,
            timetableGeneration = before.timetableGeneration + if (changed) 1 else 0,
            signals = if (trackingChanged) before.signals.copy(enRoute = null, geofenceCode = null) else before.signals
        )
    }

    /** The current position, or null when it is unknown. */
    fun updateOrigin(value: GeoPoint?, recordedAt: Instant = clock(), accuracyMeters: Float = 50f) = synchronized(inputLock) {
        inputs.value = inputs.value.copy(origin = value?.let { OriginFix(it, recordedAt, accuracyMeters) })
    }

    /** Whether the motion sensors detect walking or other movement. */
    fun updateMoving(value: Boolean) = synchronized(inputLock) {
        inputs.value = inputs.value.copy(signals = inputs.value.signals.copy(moving = value))
    }

    /**
     * The sensing service's combined en-route verdict (moving, distance falling, heading towards the
     * building), or null when it is not available. Optional: without it the engine combines
     * [updateMoving] with its own distance check.
     */
    fun updateEnRouteSignal(value: Boolean?) = synchronized(inputLock) {
        inputs.value = inputs.value.copy(signals = inputs.value.signals.copy(enRoute = value))
    }

    /** The arrival geofence with this id (the building's location code) was entered. */
    fun reportGeofenceEntered(buildingCode: String) = synchronized(inputLock) {
        inputs.value = inputs.value.copy(signals = inputs.value.signals.copy(geofenceCode = buildingCode))
    }

    /** Minutes the user wants in hand on top of the travel time; set on the schedule screen. */
    fun updateLeadMinutes(value: Int) = synchronized(inputLock) {
        inputs.value = inputs.value.copy(leadMinutes = value.coerceAtLeast(0))
    }

    /** Recomputes the snapshot whenever an input changes and at every tick, until cancelled. */
    suspend fun run() {
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(tickInterval.toMillis())
            }
        }
        combine(inputs, merge(ticks, reminderChanges)) { current, _ -> current }
            .conflate()
            .collect { refresh(it) }
    }

    /** Recomputes the snapshot once from the current inputs. */
    suspend fun refresh() {
        refresh(inputs.value)
    }

    private suspend fun refresh(request: Inputs) = refreshLock.withLock {
        val inputs = synchronized(inputLock) {
            // Authentication restores the saved timetable after run() starts. Until then an
            // empty input is unknown, rather than a result that should withdraw surviving prompts.
            if (!this.inputs.value.sessionsInitialized) return@withLock
            if (request.timetableGeneration != this.inputs.value.timetableGeneration) return@withLock
            // A queued refresh must not replay motion evidence cleared by an earlier refresh.
            this.inputs.value
        }
        val now = clock()
        val tracked = ContextEngine.trackedSession(inputs.sessions, now)
        // The same occurrence can move building or gain/lose a resolvable venue without a new ID.
        val destination = tracked?.let { BuildingResolver.resolve(it, buildings) }
        val motion = synchronized(inputLock) {
            if (inputs.timetableGeneration != this.inputs.value.timetableGeneration) return@withLock
            val target = MotionTarget(tracked?.id, destination)
            // A new class, or the same class at another (or an unknown) building, is a new trip.
            // Room, title and time edits in the same building are not: arrival stays valid. The
            // check runs here rather than in updateSessions because evidence for a new building
            // cannot arrive earlier: the arrival geofence and en-route fusion follow the published
            // building. If evidence ever comes from elsewhere, keep a geofenceCode that matches it.
            val targetChanged = motionTarget?.let { it != target } == true
            if (targetChanged) {
                progressTracker.reset()
                decision = null
                _snapshot.value = TravelSnapshot.PENDING
                this.inputs.value = this.inputs.value.copy(
                    signals = this.inputs.value.signals.copy(enRoute = null, geofenceCode = null)
                )
            }
            // Track evidence scope before routing, including the first request that may never
            // publish because a class ends while its ETA/weather is pending.
            motionTarget = target
            // Clear old target evidence before suspending; new events during routing stay available
            // to the next refresh instead of being erased when this request publishes.
            this.inputs.value.signals
        }

        val courseReminder = tracked?.let { reminderPreference?.invoke(it) }
        // DepartureReminders applies the same limit when it checks which buffer a result used.
        val lead = courseReminder?.let {
            if (it.enabled) it.leadMinutes.coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES) else 0
        } ?: inputs.leadMinutes
        val fix = inputs.origin?.takeIf { it.isFreshAt(now) }
        val from = fix?.point
        var distance: Double? = null
        var estimate: TravelEstimate? = null
        var weatherNow: WeatherSnapshot? = null
        var weatherBuffer = 0
        if (tracked != null && destination != null && from != null) {
            distance = GeoMath.distanceMeters(from, destination.location)
            val mode = preferences().preferredMode(distance.roundToInt())
            // A position far from campus (another city, or an emulator's default location) gives no
            // useful travel time, and the routing quota is better kept for real trips.
            estimate = if (distance <= MAX_ROUTED_DISTANCE_METERS) {
                eta.estimate(from, destination.location, mode).getOrNull()
            } else {
                null
            }
            if (!isCurrent(inputs)) return@withLock
            // Weather is checked at the class, a public place; the repository rounds and caches it.
            weatherNow = weather?.currentWeather(destination.location)?.getOrNull()
            weatherBuffer = WeatherBuffer.extraMinutes(weatherNow)
        }

        synchronized(inputLock) {
            if (inputs.timetableGeneration != this.inputs.value.timetableGeneration) return@synchronized
            val publishedAt = clock()
            // Routing may suspend long enough for a location to expire.
            if (fix?.isFreshAt(publishedAt) != true) {
                estimate = null
                distance = null
            }
            val progress = if (distance != null && fix != null) {
                progressTracker.observe(distance!!, fix.accuracyMeters, fix.recordedAt, publishedAt)
            } else {
                progressTracker.reset()
                DistanceTrend.UNKNOWN
            }
            if (!sameSession(tracked, ContextEngine.trackedSession(this.inputs.value.sessions, publishedAt))) {
                // A class boundary crossed while routing: the next tick will compute the new target.
                _snapshot.value = TravelSnapshot.PENDING
                return@synchronized
            }
            val next = ContextEngine.decide(
                inputs = ContextInputs(
                    now = publishedAt,
                    sessions = inputs.sessions,
                    travelMinutes = estimate?.durationMinutes,
                    bufferMinutes = weatherBuffer,
                    distanceMeters = distance,
                    isMoving = when (progress) {
                        DistanceTrend.APPROACHING -> true
                        DistanceTrend.STABLE, DistanceTrend.MOVING_AWAY -> false
                        DistanceTrend.UNKNOWN -> motion.moving
                    },
                    enRouteSignal = when (progress) {
                        DistanceTrend.APPROACHING -> true
                        DistanceTrend.STABLE, DistanceTrend.MOVING_AWAY -> false
                        DistanceTrend.UNKNOWN -> motion.enRoute
                    },
                    insideGeofence = destination != null && motion.geofenceCode == destination.locCode,
                    buildingCode = destination?.locCode
                ),
                previous = decision,
                config = config.copy(leaveSoonWindowMinutes = lead)
            )
            decision = next

            if (tracked == null) {
                _snapshot.value = if (next.finishedSession == null) {
                    TravelSnapshot()
                } else {
                    TravelSnapshot(state = next.state, finishedSession = next.finishedSession)
                }
                return@synchronized
            }

            _snapshot.value = TravelSnapshot(
                session = tracked,
                building = destination,
                state = next.state,
                estimate = estimate,
                distanceMeters = distance,
                minutesUntilClass = Duration.between(publishedAt, tracked.start.toInstant()).toMinutes(),
                weather = weatherNow,
                weatherBufferMinutes = weatherBuffer,
                leadMinutes = lead,
                entryAllowanceMinutes = config.entryAllowanceMinutes,
                leaveBy = next.leaveBy?.atZone(tracked.start.zone),
                finishedSession = next.finishedSession,
                evaluatedAt = publishedAt,
                destinationProgress = progress
            )
        }
    }

    private fun isCurrent(value: Inputs): Boolean = synchronized(inputLock) {
        value.timetableGeneration == inputs.value.timetableGeneration
    }

    private fun sameSession(first: CourseSession?, second: CourseSession?): Boolean =
        if (first == null) second == null else second != null && first.matchesTimetableSession(second)

    private data class MotionSignals(
        val moving: Boolean = false,
        val enRoute: Boolean? = null,
        val geofenceCode: String? = null
    )

    /** What arrival and movement evidence is measured against: one class at one building. */
    private data class MotionTarget(val sessionId: String?, val destination: BuildingLocation?)

    private data class OriginFix(val point: GeoPoint, val recordedAt: Instant, val accuracyMeters: Float)

    private fun OriginFix.isFreshAt(now: Instant): Boolean {
        val age = Duration.between(recordedAt, now)
        return !age.isNegative && age <= maxLocationAge
    }

    private data class Inputs(
        val sessions: List<CourseSession> = emptyList(),
        val sessionsInitialized: Boolean = false,
        val origin: OriginFix? = null,
        val signals: MotionSignals = MotionSignals(),
        val leadMinutes: Int = DEFAULT_LEAD_MINUTES,
        val timetableGeneration: Long = 0
    )

    companion object {
        /** Matches the default of the reminder slider on the schedule screen. */
        const val DEFAULT_LEAD_MINUTES = 10

        /** Beyond this straight-line distance no travel time is requested or shown. */
        const val MAX_ROUTED_DISTANCE_METERS = 150_000.0
        val TICK_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
