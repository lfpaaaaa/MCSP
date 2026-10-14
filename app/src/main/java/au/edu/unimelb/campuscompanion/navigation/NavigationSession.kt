package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
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
    val error: UiText? = null
) {
    val route get() = routes.getOrNull(routeIndex)
    val leg get() = route?.transitLegs?.getOrNull(legIndex)
}

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

    @Synchronized fun open(destination: ClassDestination, mode: TravelMode = TravelMode.PublicTransport) {
        if (state.value.destination == destination) {
            if (state.value.mode != mode) changeMode(mode)
            return
        }
        stop()
        mutableState.value = NavigationState(destination = destination, mode = mode)
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
            mutableState.value = before.copy(error = UiText(R.string.route_waiting_for_a_recent_precise_location_enable_location))
            return
        }
        val version = ++generation
        mutableState.value = before.copy(loading = true, error = null)
        request = scope.launch {
            try {
                val routes = repository.routes(GeoPoint(fix.latitude, fix.longitude), destination.point, before.mode)
                synchronized(this@NavigationSession) {
                    if (generation == version) mutableState.value = before.copy(routes = routes, loadedAt = System.currentTimeMillis(), error = if (routes.isEmpty()) UiText(R.string.route_no_route_found) else null)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                synchronized(this@NavigationSession) {
                    if (generation == version) mutableState.value = before.copy(error = (error as? RouteException)?.userMessage ?: UiText(R.string.route_could_not_load_routes_check_your_connection_and))
                }
            }
        }
    }
    @Synchronized fun selectRoute(index: Int) {
        if (!state.value.active && index in state.value.routes.indices) mutableState.value = state.value.copy(routeIndex = index)
    }
    @Synchronized fun start(): Boolean {
        val current = state.value
        val leg = current.route?.transitLegs?.firstOrNull() ?: return false
        if (System.currentTimeMillis() - current.loadedAt > 10 * 60 * 1000) {
            mutableState.value = current.copy(routes = emptyList(), error = UiText(R.string.route_this_route_is_out_of_date_find_routes))
            return false
        }
        if (leg.path.size < 2) {
            mutableState.value = current.copy(error = UiText(R.string.route_this_route_has_no_tracking_geometry_open_google))
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
            mutableState.value = state.value.copy(error = UiText(R.string.route_transit_complete_follow_the_walking_directions_to_your))
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
