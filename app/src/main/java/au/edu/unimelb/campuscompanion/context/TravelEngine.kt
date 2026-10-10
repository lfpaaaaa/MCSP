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
import au.edu.unimelb.campuscompanion.sensing.location.TravelStateManager
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
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
 * - the travel state (upcoming, leave soon, en route, arrived) comes from [TravelStateManager],
 *   fed with the distance, the minutes until the class, the travel time and whether the user is
 *   moving. The lead time set on the schedule screen is the buffer before departure, and rain or
 *   heat at the class's building ([WeatherBuffer]) adds to it.
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
    private val reminderChanges: Flow<Unit> = emptyFlow()
) {
    private val sessions = MutableStateFlow<List<CourseSession>>(emptyList())
    private val origin = MutableStateFlow<GeoPoint?>(null)
    private val moving = MutableStateFlow(false)
    private val leadMinutes = MutableStateFlow(DEFAULT_LEAD_MINUTES)

    private val _snapshot = MutableStateFlow(TravelSnapshot())
    val snapshot: StateFlow<TravelSnapshot> = _snapshot.asStateFlow()

    private val lock = Mutex()
    private var trackedSessionId: String? = null
    private var building: BuildingLocation? = null
    private var stateManager = TravelStateManager()

    /** The timetable sessions; an empty list stops tracking. */
    fun updateSessions(value: List<CourseSession>) {
        sessions.value = value
    }

    /** The current position, or null when it is unknown. */
    fun updateOrigin(value: GeoPoint?) {
        origin.value = value
    }

    fun updateMoving(value: Boolean) {
        moving.value = value
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
        combine(sessions, origin, moving, leadMinutes, merge(ticks, reminderChanges)) { sessions, origin, moving, lead, _ ->
            Inputs(sessions, origin, moving, lead)
        }
            .conflate()
            .collect { refresh(it) }
    }

    /** Recomputes the snapshot once from the current inputs. */
    suspend fun refresh() {
        refresh(Inputs(sessions.value, origin.value, moving.value, leadMinutes.value))
    }

    private suspend fun refresh(inputs: Inputs) = lock.withLock {
        val now = clock()
        val tracked = inputs.sessions
            .filter { it.end.toInstant().isAfter(now) }
            .minByOrNull { it.start.toInstant() }

        if (tracked?.id != trackedSessionId) {
            trackedSessionId = tracked?.id
            building = tracked?.let { BuildingResolver.resolve(it, buildings) }
            // Every class starts as upcoming; the previous class's state does not carry over.
            stateManager = TravelStateManager()
        }
        if (tracked == null) {
            _snapshot.value = TravelSnapshot()
            return@withLock
        }

        val minutesUntilClass = Duration.between(now, tracked.start.toInstant()).toMinutes()
        val courseReminder = reminderPreference?.invoke(tracked)
        val lead = courseReminder?.let { if (it.enabled) it.leadMinutes.coerceIn(0, 60) else 0 }
            ?: inputs.leadMinutes
        val destination = building
        val from = inputs.origin
        var distance: Double? = null
        var estimate: TravelEstimate? = null
        var weatherNow: WeatherSnapshot? = null
        var weatherBuffer = 0
        if (destination != null && from != null) {
            distance = GeoMath.distanceMeters(from, destination.location)
            val mode = preferences().preferredMode(distance.roundToInt())
            // A position far from campus (another city, or an emulator's default location) gives no
            // useful travel time, and the routing quota is better kept for real trips.
            estimate = if (distance <= MAX_ROUTED_DISTANCE_METERS) {
                eta.estimate(from, destination.location, mode).getOrNull()
            } else {
                null
            }
            // Weather is checked at the class, a public place; the repository rounds and caches it.
            weatherNow = weather?.currentWeather(destination.location)?.getOrNull()
            weatherBuffer = WeatherBuffer.extraMinutes(weatherNow)
            stateManager.update(
                distanceMeters = distance,
                minutesUntilClass = minutesUntilClass.coerceIn(MINUTES_RANGE).toInt(),
                estimatedTravelMinutes = estimate?.durationMinutes,
                isMoving = inputs.moving,
                bufferMinutes = lead + weatherBuffer
            )
        }

        _snapshot.value = TravelSnapshot(
            session = tracked,
            building = destination,
            state = stateManager.state.value,
            estimate = estimate,
            distanceMeters = distance,
            minutesUntilClass = minutesUntilClass,
            weather = weatherNow,
            weatherBufferMinutes = weatherBuffer
        )
    }

    private data class Inputs(
        val sessions: List<CourseSession>,
        val origin: GeoPoint?,
        val moving: Boolean,
        val leadMinutes: Int
    )

    companion object {
        /** Matches the default of the reminder slider on the schedule screen. */
        const val DEFAULT_LEAD_MINUTES = 10

        /** Beyond this straight-line distance no travel time is requested or shown. */
        const val MAX_ROUTED_DISTANCE_METERS = 150_000.0
        val TICK_INTERVAL: Duration = Duration.ofSeconds(30)
        private val MINUTES_RANGE = -100_000L..100_000L
    }
}
