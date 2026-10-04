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
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
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
 *   motion signals. The lead time set on the schedule screen is the buffer before departure, and
 *   rain or heat at the class's building ([WeatherBuffer]) adds to it.
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
    private val config: ContextConfig = ContextConfig()
) {
    private val sessions = MutableStateFlow<List<CourseSession>>(emptyList())
    private val origin = MutableStateFlow<GeoPoint?>(null)
    private val signals = MutableStateFlow(MotionSignals())
    private val leadMinutes = MutableStateFlow(DEFAULT_LEAD_MINUTES)

    private val _snapshot = MutableStateFlow(TravelSnapshot())
    val snapshot: StateFlow<TravelSnapshot> = _snapshot.asStateFlow()

    private val lock = Mutex()
    private var trackedSessionId: String? = null
    private var building: BuildingLocation? = null
    private var decision: ContextDecision? = null

    /** The timetable sessions; an empty list stops tracking. */
    fun updateSessions(value: List<CourseSession>) {
        sessions.value = value
    }

    /** The current position, or null when it is unknown. */
    fun updateOrigin(value: GeoPoint?) {
        origin.value = value
    }

    /** Whether the motion sensors detect walking or other movement. */
    fun updateMoving(value: Boolean) {
        signals.update { it.copy(moving = value) }
    }

    /**
     * The sensing service's combined en-route verdict (moving, distance falling, heading towards the
     * building), or null when it is not available. Optional: without it the engine combines
     * [updateMoving] with its own distance check.
     */
    fun updateEnRouteSignal(value: Boolean?) {
        signals.update { it.copy(enRoute = value) }
    }

    /** The arrival geofence with this id (the building's location code) was entered. */
    fun reportGeofenceEntered(buildingCode: String) {
        signals.update { it.copy(geofenceCode = buildingCode) }
    }

    /** Minutes the user wants in hand on top of the travel time; set on the schedule screen. */
    fun updateLeadMinutes(value: Int) {
        leadMinutes.value = value.coerceAtLeast(0)
    }

    /** Recomputes the snapshot whenever an input changes and at every tick, until cancelled. */
    suspend fun run() {
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(tickInterval.toMillis())
            }
        }
        combine(sessions, origin, signals, leadMinutes, ticks) { sessions, origin, signals, lead, _ ->
            Inputs(sessions, origin, signals, lead)
        }
            .conflate()
            .collect { refresh(it) }
    }

    /** Recomputes the snapshot once from the current inputs. */
    suspend fun refresh() {
        refresh(Inputs(sessions.value, origin.value, signals.value, leadMinutes.value))
    }

    private suspend fun refresh(inputs: Inputs) = lock.withLock {
        val now = clock()
        val tracked = ContextEngine.trackedSession(inputs.sessions, now)

        if (tracked?.id != trackedSessionId) {
            trackedSessionId = tracked?.id
            building = tracked?.let { BuildingResolver.resolve(it, buildings) }
        }

        val destination = building
        val from = inputs.origin
        var distance: Double? = null
        var estimate: TravelEstimate? = null
        var weatherNow: WeatherSnapshot? = null
        var weatherBuffer = 0
        if (tracked != null && destination != null && from != null) {
            distance = GeoMath.distanceMeters(from, destination.location)
            val mode = preferences().preferredMode(distance.roundToInt())
            estimate = eta.estimate(from, destination.location, mode).getOrNull()
            // Weather is checked at the class, a public place; the repository rounds and caches it.
            weatherNow = weather?.currentWeather(destination.location)?.getOrNull()
            weatherBuffer = WeatherBuffer.extraMinutes(weatherNow)
        }

        // The engine keeps its own memory per class, so a new class starts as a clean slate.
        val next = ContextEngine.decide(
            inputs = ContextInputs(
                now = now,
                sessions = inputs.sessions,
                travelMinutes = estimate?.durationMinutes,
                bufferMinutes = inputs.leadMinutes + weatherBuffer,
                distanceMeters = distance,
                isMoving = inputs.signals.moving,
                enRouteSignal = inputs.signals.enRoute,
                insideGeofence = destination != null && inputs.signals.geofenceCode == destination.locCode
            ),
            previous = decision,
            config = config
        )
        decision = next

        if (tracked == null) {
            _snapshot.value = if (next.finishedSession == null) {
                TravelSnapshot()
            } else {
                TravelSnapshot(state = next.state, finishedSession = next.finishedSession)
            }
            return@withLock
        }

        _snapshot.value = TravelSnapshot(
            session = tracked,
            building = destination,
            state = next.state,
            estimate = estimate,
            distanceMeters = distance,
            minutesUntilClass = Duration.between(now, tracked.start.toInstant()).toMinutes(),
            weather = weatherNow,
            weatherBufferMinutes = weatherBuffer,
            leadMinutes = inputs.leadMinutes,
            entryAllowanceMinutes = config.entryAllowanceMinutes,
            leaveBy = next.leaveBy?.atZone(tracked.start.zone),
            finishedSession = next.finishedSession
        )
    }

    private data class MotionSignals(
        val moving: Boolean = false,
        val enRoute: Boolean? = null,
        val geofenceCode: String? = null
    )

    private data class Inputs(
        val sessions: List<CourseSession>,
        val origin: GeoPoint?,
        val signals: MotionSignals,
        val leadMinutes: Int
    )

    companion object {
        /** Matches the default of the reminder slider on the schedule screen. */
        const val DEFAULT_LEAD_MINUTES = 10
        val TICK_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
