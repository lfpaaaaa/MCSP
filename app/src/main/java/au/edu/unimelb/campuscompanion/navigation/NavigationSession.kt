package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NavigationState(
    val destination: ClassDestination? = null,
    val mode: TravelMode = TravelMode.PublicTransport,
    val loading: Boolean = false,
    val loadedAt: Long = 0,
    val routes: List<GoogleRoute> = emptyList(),
    val routeIndex: Int = 0,
    val active: Boolean = false,
    val legIndex: Int = 0,
    val progress: StopProgress = StopProgress(),
    val error: String? = null
) {
    val route get() = routes.getOrNull(routeIndex)
    val leg get() = route?.transitLegs?.getOrNull(legIndex)
}

/** Also covers a class that has finished: the timetable lists upcoming classes only. */
private const val CLASS_REMOVED = "This class is no longer among your upcoming classes, so its directions " +
    "were closed. Open Home or Schedule to choose a class."
private const val VENUE_UNKNOWN =
    "This class no longer has a campus building to route to, so its directions were closed."
private const val CLASS_MOVED = "This class moved to another building. Find routes again before starting a trip."

/** Shared with the foreground location service, so leaving the navigation screen keeps reminders alive. */
class NavigationSession(
    private val repository: GoogleRouteSource,
    private val scope: CoroutineScope,
    private val onAlert: (TransitLeg) -> Unit,
    private val onStop: () -> Unit,
    private val onActiveChanged: (Boolean) -> Unit = {}
) {
    private val mutableState = MutableStateFlow(NavigationState())
    val state = mutableState.asStateFlow()
    private val mutableLocation = MutableStateFlow<LocationSample?>(null)
    val location = mutableLocation.asStateFlow()
    private var request: Job? = null
    private var generation = 0
    private var journey: TransitJourney? = null
    private var startedAt = 0L
    private var vehicleAt = 0L
    private var inVehicle = false

    @Synchronized fun open(destination: ClassDestination) {
        if (state.value.destination == destination) return
        stop()
        mutableState.value = NavigationState(destination = destination)
    }

    /**
     * Keeps the selected class current after a timetable change. [sessions] is the whole timetable;
     * [destinationOf] gives a class's destination, or null when its building is unknown.
     * - Only its labels changed (title, room, activity): they are updated, and the routes and any
     *   trip in progress stay, since they lead to the same place.
     * - It moved to another building: the routes and any trip lead to the old one, so both end.
     * - It was removed or has finished, or its building is no longer known: the selection ends,
     *   with the reason.
     */
    @Synchronized fun followTimetable(sessions: List<CourseSession>, destinationOf: (CourseSession) -> ClassDestination?) {
        val current = state.value.destination ?: return
        val session = sessions.firstOrNull { it.id == current.sessionId }
        val updated = session?.let(destinationOf)
        when {
            updated == null -> {
                stop()
                mutableState.value = NavigationState(
                    mode = state.value.mode,
                    error = if (session == null) CLASS_REMOVED else VENUE_UNKNOWN
                )
            }
            updated.point != current.point || updated.buildingCode != current.buildingCode -> {
                stop()
                mutableState.value = NavigationState(destination = updated, mode = state.value.mode, error = CLASS_MOVED)
            }
            updated != current -> mutableState.value = state.value.copy(destination = updated)
        }
    }
    @Synchronized fun changeMode(mode: TravelMode) {
        if (state.value.active) return
        generation++
        request?.cancel()
        mutableState.value = state.value.copy(mode = mode, routes = emptyList(), routeIndex = 0, loading = false, error = null)
    }
    @Synchronized fun loadRoutes(now: Long = System.currentTimeMillis()) {
        val before = state.value
        if (before.active || before.loading) return
        val destination = before.destination ?: return
        val fix = location.value
        if (fix == null || now - fix.timestampMillis !in 0..30_000 || fix.accuracyMeters > 50) {
            mutableState.value = before.copy(error = "Waiting for a recent precise location. Enable location and try again.")
            return
        }
        val version = ++generation
        mutableState.value = before.copy(loading = true, error = null)
        request = scope.launch {
            // Results go into the current state: the class's labels may have been corrected meanwhile.
            try {
                val routes = repository.routes(GeoPoint(fix.latitude, fix.longitude), destination.point, before.mode)
                synchronized(this@NavigationSession) {
                    if (generation == version) mutableState.value = state.value.copy(
                        loading = false, routes = routes, routeIndex = 0, loadedAt = System.currentTimeMillis(),
                        error = if (routes.isEmpty()) "No route found." else null
                    )
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                synchronized(this@NavigationSession) {
                    if (generation == version) mutableState.value = state.value.copy(
                        loading = false,
                        error = error.message?.takeIf { error is IllegalStateException } ?: "Could not load routes. Check your connection and try again."
                    )
                }
            }
        }
    }
    @Synchronized fun selectRoute(index: Int) {
        if (!state.value.active && index in state.value.routes.indices) mutableState.value = state.value.copy(routeIndex = index)
    }
    @Synchronized fun start(): Boolean {
        val current = state.value
        // Already tracking: starting again would reset the progress made on this trip.
        if (current.active) return true
        // New routes are on their way and will replace the ones on screen.
        if (current.loading) return false
        val leg = current.route?.transitLegs?.firstOrNull() ?: return false
        if (System.currentTimeMillis() - current.loadedAt > 10 * 60 * 1000) {
            mutableState.value = current.copy(routes = emptyList(), error = "This route is out of date. Find routes again before starting.")
            return false
        }
        if (leg.path.size < 2) {
            mutableState.value = current.copy(error = "This route has no tracking geometry. Open Google Maps for navigation.")
            return false
        }
        startedAt = System.currentTimeMillis()
        journey = TransitJourney(leg)
        mutableState.value = current.copy(active = true, legIndex = 0, progress = StopProgress(), error = null)
        onActiveChanged(true)
        return true
    }
    @Synchronized fun confirmBoarding() {
        if (!state.value.active) return
        journey?.confirmBoarding()
        mutableState.value = state.value.copy(progress = state.value.progress.copy(state = BoardingState.Riding))
    }
    @Synchronized fun nextLeg() {
        val current = state.value
        if (!current.active) return
        val nextIndex = current.legIndex + 1
        val leg = current.route?.transitLegs?.getOrNull(nextIndex)
        onStop()
        if (leg == null) {
            stop()
            mutableState.value = state.value.copy(error = "Transit complete. Follow the walking directions to your class.")
        } else {
            journey = TransitJourney(leg)
            mutableState.value = current.copy(legIndex = nextIndex, progress = StopProgress())
        }
    }
    @Synchronized fun updateActivity(vehicle: Boolean, now: Long = System.currentTimeMillis()) {
        inVehicle = vehicle
        vehicleAt = now
    }
    @Synchronized fun updateLocation(sample: LocationSample, now: Long = System.currentTimeMillis()) {
        mutableLocation.value = sample
        val current = state.value
        if (!current.active) return
        if (now - startedAt > 4 * 60 * 60 * 1000L) { stop(); return }
        val progress = journey?.update(sample, now, inVehicle && now - vehicleAt < 10 * 60 * 1000) ?: return
        mutableState.value = current.copy(progress = progress)
        if (progress.alert) current.leg?.let(onAlert)
    }
    @Synchronized fun stop() {
        generation++
        request?.cancel()
        journey = null
        mutableState.value = state.value.copy(active = false, loading = false, progress = StopProgress())
        onStop()
        onActiveChanged(false)
    }
    @Synchronized fun clear() {
        stop()
        mutableLocation.value = null
        mutableState.value = NavigationState()
    }
}
