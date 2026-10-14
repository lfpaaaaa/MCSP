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
import au.edu.unimelb.campuscompanion.sensing.location.EvidenceFreshness
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceEntryEvent
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceEntryResult
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceRejectionReason
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.sensing.location.SensingTarget
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.DEFAULT_COURSE_REMINDER_LEAD_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.MAX_COURSE_REMINDER_LEAD_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.MAX_COURSE_REMINDER_PREPARATION_MINUTES
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
    private val reminderPreference: ((CourseSession) -> CourseReminderPreference)? = null,
    private val reminderChanges: Flow<Unit> = emptyFlow(),
    private val config: ContextConfig = ContextConfig(),
    private val elapsedNanos: () -> Long = System::nanoTime
) {
    private val inputs = MutableStateFlow(Inputs())

    private val _snapshot = MutableStateFlow(TravelSnapshot.PENDING)

    /** The latest result, or [TravelSnapshot.PENDING] while the engine works out a new one. */
    val snapshot: StateFlow<TravelSnapshot> = _snapshot.asStateFlow()

    // Never hold this monitor across a network suspension. Timetable changes and result publication
    // use it together so a superseded request cannot restore either guidance or decision memory.
    private val inputLock = Any()
    private val refreshLock = Mutex()
    private val _sensingTarget = MutableStateFlow(SensingTarget(0, elapsedNanos(), false, null, null))
    /** Published before network work; Home's temporary PENDING result does not change this target. */
    val sensingTarget: StateFlow<SensingTarget> = _sensingTarget.asStateFlow()
    private val _sensingSession = MutableStateFlow<CourseSession?>(null)
    /** Timetable timing for the sensing policy, available even while Home/ETA is pending. */
    val sensingSession: StateFlow<CourseSession?> = _sensingSession.asStateFlow()
    private var decision: ContextDecision? = null

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
        // Reconcile the resolved destination now, before any new sensor evidence can arrive.
        // Room, title and time corrections in the same building keep physical arrival memory.
        val trackingChanged = reset || oldTracked?.id != tracked?.id
        if (trackingChanged) {
            decision = null
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
        reconcileTargetLocked(now, force = reset)
        // A retime outside the departure window must also invalidate raw departure evidence.
        if (!trackingChanged && tracked != null && oldTracked?.start?.toInstant() != tracked.start.toInstant()) {
            val leave = ContextEngine.leaveBy(tracked.start.toInstant(), snapshot.estimate?.durationMinutes,
                snapshot.leadMinutes + snapshot.weatherBufferMinutes, config.entryAllowanceMinutes)
            if (now.isBefore((leave ?: tracked.start.toInstant()).minusSeconds(config.earlyDepartureWindowMinutes * 60L))) {
                decision = decision?.let { it.copy(memory = it.memory.copy(enRoute = false, farthestMeters = null, closestMeters = null)) }
                advanceSensingLocked(clearGuidance = false)
            }
        }
    }

    /** Idempotent activation; explicit stop/restart creates a new period even at the same target. */
    fun startSensing(): SensingTarget = synchronized(inputLock) {
        reconcileTargetLocked(clock())
        if (!_sensingTarget.value.active) {
            inputs.value = inputs.value.copy(sensingManaged = true)
            advanceSensingLocked(active = true, clearGuidance = true)
        }
        _sensingTarget.value
    }

    fun stopSensing() = synchronized(inputLock) {
        if (_sensingTarget.value.active || !inputs.value.sensingManaged) {
            decision = null
            inputs.value = inputs.value.copy(sensingManaged = true, origin = null)
            advanceSensingLocked(active = false, clearGuidance = true)
            _snapshot.value = TravelSnapshot.PENDING
        }
    }

    /** NORMAL keeps guidance and physical arrival, but no high-rate trip classification/history. */
    fun clearSensingEvidence() = synchronized(inputLock) {
        val before = inputs.value
        if (!before.sensingManaged) return@synchronized
        inputs.value = before.copy(
            evidence = before.evidence?.copy(movement = null, activity = null, enRoute = null, progressConfirmed = false),
            signals = MotionSignals(), resetProgress = true
        )
        decision = decision?.let {
            it.copy(memory = it.memory.copy(enRoute = false, farthestMeters = null, closestMeters = null))
        }
        val now = clock()
        val tracked = ContextEngine.trackedSession(before.sessions, now)
        val current = _snapshot.value
        if (!current.pending && sameSession(current.session, tracked)) {
            publishLocked(tracked, _sensingTarget.value.building, current.estimate, current.weather,
                current.leadMinutes, current.preparationMinutes, current.weatherBufferMinutes, current.estimateOrigin, now)
        }
    }

    /** Atomically accepts a measured position and its target-scoped classification. */
    fun updateEvidence(value: SensingEvidence): Boolean = synchronized(inputLock) {
        val now = clock()
        reconcileTargetLocked(now)
        val target = _sensingTarget.value
        if (!target.active || value.generation != target.generation) return@synchronized false
        val before = inputs.value
        val location = value.location
        if (location != null && !EvidenceFreshness.accepts(location, target, elapsedNanos(), before.lastObservedNanos)) {
            return@synchronized false
        }
        val gap = location != null && before.lastObservedNanos > 0 &&
            location.elapsedRealtimeNanos - before.lastObservedNanos > EvidenceFreshness.LOCATION_MAX_AGE_NANOS
        inputs.value = before.copy(
            evidence = value,
            origin = location?.let { GeoPoint(it.latitude, it.longitude) } ?: before.origin,
            lastObservedNanos = location?.elapsedRealtimeNanos ?: before.lastObservedNanos,
            resetProgress = before.resetProgress || gap || location == null
        )
        // Routing and weather can suspend. Withdraw prompts immediately using existing guidance,
        // then re-read this same bundle when the network request eventually completes.
        val tracked = ContextEngine.trackedSession(inputs.value.sessions, now)
        if (tracked != null && target.building != null) {
            val previous = _snapshot.value.takeIf { sameSession(it.session, tracked) && it.building == target.building }
            val timing = previous?.let { ReminderTiming(it.leadMinutes, it.preparationMinutes) }
                ?: reminderTiming(tracked, inputs.value)
            publishLocked(tracked, target.building, previous?.estimate, previous?.weather,
                timing.leadMinutes, timing.preparationMinutes, previous?.weatherBufferMinutes ?: 0,
                previous?.estimateOrigin, now)
        }
        true
    }

    private fun advanceSensingLocked(
        active: Boolean = _sensingTarget.value.active,
        clearGuidance: Boolean,
        sessionId: String? = _sensingTarget.value.sessionId,
        building: BuildingLocation? = _sensingTarget.value.building
    ) {
        val target = _sensingTarget.value
        // Publish identity and generation together: observers never see a new venue in an old run.
        _sensingTarget.value = SensingTarget(target.generation + 1, elapsedNanos(), active, sessionId, building)
        val before = inputs.value
        val origin = if (clearGuidance && before.sensingManaged) null else before.origin
        // Retained guidance still orders position evidence: an older initial geofence trigger
        // must not override a newer away fix just because a retime reset the sensing period.
        inputs.value = before.copy(evidence = null,
            lastObservedNanos = if (origin != null) before.lastObservedNanos else 0, resetProgress = true,
            consumedGeofenceEntries = emptyMap(),
            origin = origin,
            signals = if (before.sensingManaged) MotionSignals() else MotionSignals(moving = before.signals.moving))
    }

    private fun reconcileTargetLocked(now: Instant, force: Boolean = false) {
        val tracked = ContextEngine.trackedSession(inputs.value.sessions, now)
        _sensingSession.value = tracked
        val building = tracked?.let { BuildingResolver.resolve(it, buildings) }
        val before = _sensingTarget.value
        if (force || before.sessionId != tracked?.id || before.building != building) {
            // An undone venue correction before a new decision keeps confirmed physical arrival
            // at the original building. It cannot be used at the interim building: ContextEngine
            // checks the memory's building. All departure evidence still starts from scratch.
            decision = decision?.takeIf {
                !force && it.memory.arrived && it.session?.id == tracked?.id &&
                    before.building != null && building != null
            }?.let { it.copy(memory = it.memory.copy(enRoute = false, farthestMeters = null, closestMeters = null)) }
            advanceSensingLocked(clearGuidance = true, sessionId = tracked?.id, building = building)
            _snapshot.value = TravelSnapshot.PENDING
        }
    }

    /** Deterministic fixture adapter. Production sensing must publish [updateEvidence]. */
    internal fun updateOrigin(value: GeoPoint?) = synchronized(inputLock) {
        check(!inputs.value.sensingManaged) { "Active sensing requires timestamped evidence" }
        inputs.value = inputs.value.copy(origin = value)
    }

    /** Deterministic fixture adapter; never mix separate callbacks with managed sensing. */
    internal fun updateMoving(value: Boolean) = synchronized(inputLock) {
        check(!inputs.value.sensingManaged) { "Active sensing requires timestamped evidence" }
        inputs.value = inputs.value.copy(signals = inputs.value.signals.copy(moving = value))
    }

    /** Fixture fusion adapter. Production verdicts belong to the location bundle. */
    internal fun updateEnRouteSignal(value: Boolean?) = synchronized(inputLock) {
        check(!inputs.value.sensingManaged) { "Active sensing requires timestamped evidence" }
        inputs.value = inputs.value.copy(signals = inputs.value.signals.copy(enRoute = value))
    }

    /** Fixture geofence adapter; production uses the scoped event overload. */
    internal fun reportGeofenceEntered(buildingCode: String) = synchronized(inputLock) {
        check(!inputs.value.sensingManaged) { "Active sensing requires scoped geofence events" }
        inputs.value = inputs.value.copy(signals = inputs.value.signals.copy(geofenceCode = buildingCode))
    }

    /** The receiver has already checked the ID against the current successful registration. */
    fun reportGeofenceEntered(event: GeofenceEntryEvent): GeofenceEntryResult = synchronized(inputLock) {
        val now = clock()
        reconcileTargetLocked(now)
        val target = _sensingTarget.value
        val before = inputs.value
        val observedNow = elapsedNanos()
        val trigger = event.triggerLocation
        fun ageMillis(at: Long): Long? = trigger?.elapsedRealtimeNanos?.takeIf { it > 0 && it <= at }
            ?.let { (at - it) / 1_000_000L }
        fun result(reason: GeofenceRejectionReason? = null) = GeofenceEntryResult(
            reason, ageMillis(observedNow), ageMillis(event.receivedAtNanos), trigger?.accuracyMeters,
            trigger != null && trigger.elapsedRealtimeNanos < target.activatedAtNanos
        )
        val building = target.building
        val scopeRejection = when {
            !target.active || !before.sensingManaged || building == null -> GeofenceRejectionReason.NO_ACTIVE_TARGET
            event.generation != target.generation -> GeofenceRejectionReason.WRONG_GENERATION
            event.buildingCode != building.locCode -> GeofenceRejectionReason.WRONG_BUILDING
            event.requestId.isBlank() -> GeofenceRejectionReason.INVALID_REQUEST_ID
            event.receivedAtNanos <= 0 || event.receivedAtNanos > observedNow -> GeofenceRejectionReason.INVALID_RECEIPT_TIME
            event.receivedAtNanos < target.activatedAtNanos -> GeofenceRejectionReason.RECEIPT_BEFORE_ACTIVATION
            observedNow - event.receivedAtNanos > EvidenceFreshness.LOCATION_MAX_AGE_NANOS -> GeofenceRejectionReason.RECEIPT_EXPIRED
            else -> null
        }
        if (scopeRejection != null) return@synchronized result(scopeRejection)
        requireNotNull(building)

        val eventObserved = trigger?.elapsedRealtimeNanos ?: event.receivedAtNanos
        val consumed = before.consumedGeofenceEntries[event.requestId]
        // A supplied measured fix distinguishes a later real re-entry from a duplicate delivery.
        // Without one, only the first entry per registration can be identified safely.
        if (consumed != null && (trigger == null || eventObserved <= consumed)) {
            return@synchronized result(GeofenceRejectionReason.DUPLICATE)
        }
        if (trigger != null) {
            // INITIAL_TRIGGER_ENTER may carry a recent fix taken before registration/activation.
            // Allow it only for the first entry of this successfully registered ID, under the
            // unchanged three-minute age bound. GPS admission still requires post-activation fixes.
            val triggerRejection = when {
                eventObserved <= 0 -> GeofenceRejectionReason.MISSING_TRIGGER_TIME
                eventObserved > event.receivedAtNanos -> GeofenceRejectionReason.TRIGGER_AFTER_RECEIPT
                observedNow - eventObserved > GEOFENCE_TRIGGER_MAX_AGE_NANOS -> GeofenceRejectionReason.TRIGGER_EXPIRED
                eventObserved < target.activatedAtNanos && consumed != null -> GeofenceRejectionReason.PRE_ACTIVATION_REENTRY
                !trigger.latitude.isFinite() || trigger.latitude !in -90.0..90.0 ||
                    !trigger.longitude.isFinite() || trigger.longitude !in -180.0..180.0 -> GeofenceRejectionReason.INVALID_POSITION
                !trigger.accuracyMeters.isFinite() || trigger.accuracyMeters < 0f -> GeofenceRejectionReason.INVALID_ACCURACY
                GeoMath.distanceMeters(GeoPoint(trigger.latitude, trigger.longitude), building.location) > config.leftBuildingMeters ->
                    GeofenceRejectionReason.OUTSIDE_EXIT_RADIUS
                else -> null
            }
            if (triggerRejection != null) return@synchronized result(triggerRejection)
        }
        // A delayed entry cannot override a newer accepted far-away fix, even once that GPS fix
        // expires. The origin remains scoped and timestamped for this contradiction check.
        val lastAway = before.origin?.let { GeoMath.distanceMeters(it, building.location) > config.leftBuildingMeters } == true
        if (lastAway && (trigger == null || before.lastObservedNanos >= eventObserved)) {
            return@synchronized result(GeofenceRejectionReason.NEWER_AWAY_FIX)
        }
        val tracked = ContextEngine.trackedSession(before.sessions, now)
            ?: return@synchronized result(GeofenceRejectionReason.NO_TRACKED_SESSION)

        inputs.value = before.copy(
            evidence = null, resetProgress = true,
            lastObservedNanos = maxOf(before.lastObservedNanos, eventObserved),
            consumedGeofenceEntries = before.consumedGeofenceEntries + (event.requestId to eventObserved)
        )
        val previous = _snapshot.value.takeIf { sameSession(it.session, tracked) && it.building == building }
        val timing = previous?.let { ReminderTiming(it.leadMinutes, it.preparationMinutes) }
            ?: reminderTiming(tracked, before)
        // Consume the entry in this decision only. Older GPS cannot immediately undo it on a tick;
        // subsequent fresh fixes still apply the normal 200 m exit hysteresis.
        publishLocked(tracked, building, previous?.estimate, previous?.weather,
            timing.leadMinutes, timing.preparationMinutes, previous?.weatherBufferMinutes ?: 0,
            previous?.estimateOrigin, now, geofenceEntry = true)
        result()
    }

    /** Minutes the user wants in hand on top of the travel time; set on the schedule screen. */
    fun updateLeadMinutes(value: Int) = synchronized(inputLock) {
        inputs.value = inputs.value.copy(leadMinutes = value.coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES))
    }

    /** Recomputes the snapshot whenever an input changes and at every tick, until cancelled. */
    suspend fun run() {
        val ticks = flow {
            while (true) {
                // This upstream flow keeps running while the conflated refresh awaits routing.
                // A class boundary must move the sensing target even without a new GPS callback.
                synchronized(inputLock) {
                    if (inputs.value.sessionsInitialized) reconcileTargetLocked(clock())
                }
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
        val startedAt = clock()
        val (captured, scope) = synchronized(inputLock) {
            if (!inputs.value.sessionsInitialized || request.timetableGeneration != inputs.value.timetableGeneration) return@withLock
            reconcileTargetLocked(startedAt)
            inputs.value to _sensingTarget.value
        }
        val tracked = ContextEngine.trackedSession(captured.sessions, startedAt)
        val destination = scope.building
        val timing = reminderTiming(tracked, captured)
        val from = captured.origin
        var estimate: TravelEstimate? = null
        var weatherNow: WeatherSnapshot? = null
        var weatherBuffer = 0
        // Expire physical evidence on ticks before awaiting network work, while retaining guidance.
        synchronized(inputLock) {
            val current = _snapshot.value
            if (captured.sensingManaged && tracked != null && sameSession(current.session, tracked) && !current.pending) {
                publishLocked(tracked, destination, current.estimate, current.weather, timing.leadMinutes,
                    timing.preparationMinutes,
                    current.weatherBufferMinutes, current.estimateOrigin, startedAt)
            }
        }
        if (tracked != null && destination != null && from != null) {
            val routingDistance = GeoMath.distanceMeters(from, destination.location)
            val mode = preferences().preferredMode(routingDistance.roundToInt())
            estimate = if (routingDistance <= MAX_ROUTED_DISTANCE_METERS) {
                eta.estimate(from, destination.location, mode).getOrNull()
            } else null
            if (!isCurrent(captured, scope)) return@withLock
            weatherNow = weather?.currentWeather(destination.location)?.getOrNull()
            weatherBuffer = WeatherBuffer.extraMinutes(weatherNow)
        }
        synchronized(inputLock) {
            if (!isCurrent(captured, scope)) return@synchronized
            val publishedAt = clock()
            if (!sameSession(tracked, ContextEngine.trackedSession(inputs.value.sessions, publishedAt))) {
                reconcileTargetLocked(publishedAt)
                _snapshot.value = TravelSnapshot.PENDING
                return@synchronized
            }
            publishLocked(tracked, destination, estimate, weatherNow, timing.leadMinutes, timing.preparationMinutes, weatherBuffer,
                from.takeIf { estimate != null }, publishedAt)
        }
    }

    private fun reminderTiming(tracked: CourseSession?, current: Inputs): ReminderTiming {
        val preference = tracked?.let { reminderPreference?.invoke(it) }
        val lead = preference?.let {
            if (it.enabled) it.leadMinutes.coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES) else 0
        } ?: current.leadMinutes
        val preparation = (preference?.preparationMinutes ?: config.leaveSoonWindowMinutes)
            .coerceIn(0, MAX_COURSE_REMINDER_PREPARATION_MINUTES)
        return ReminderTiming(lead, preparation)
    }

    /** Must run under inputLock: decision position and verdict always come from one current bundle. */
    private fun publishLocked(
        tracked: CourseSession?, destination: BuildingLocation?, estimate: TravelEstimate?,
        weatherNow: WeatherSnapshot?, lead: Int, preparation: Int, weatherBuffer: Int, estimateOrigin: GeoPoint?, now: Instant,
        geofenceEntry: Boolean = false
    ) {
        val current = inputs.value
        val target = _sensingTarget.value
        val observedNow = elapsedNanos()
        val evidence = current.evidence?.takeIf { target.active && it.generation == target.generation }
        val measured = evidence?.location?.takeIf {
            EvidenceFreshness.accepts(it, target, observedNow)
        }
        val movement = evidence?.movement?.takeIf {
            it.observedAtNanos >= target.activatedAtNanos &&
                EvidenceFreshness.isFresh(it.observedAtNanos, observedNow, EvidenceFreshness.SENSOR_MAX_AGE_NANOS)
        }
        // Transitions describe a state until EXIT/STILL or interruption, rather than a sample
        // that expires every few seconds. The adapter validates age at admission and clears it
        // on interruption; a fresh location is still required for every new physical decision.
        val activity = evidence?.activity?.takeIf {
            it.observedAtNanos > target.activatedAtNanos && it.observedAtNanos <= observedNow
        }
        val isMoving = activity?.state?.isMoving ?: movement?.isMoving
        val enRoute = evidence?.enRoute?.takeIf {
            measured != null && (it == false || isMoving == true)
        }
        val position = if (current.sensingManaged) measured?.let { GeoPoint(it.latitude, it.longitude) } else current.origin
        val distance = if (position != null && destination != null) GeoMath.distanceMeters(position, destination.location) else null
        // Expiry removes physical evidence, not the same-target last-known late guidance.
        // The routing origin is cleared on target/account replacement and explicit stop.
        val lastKnownAway = current.sensingManaged && current.origin != null && destination != null &&
            GeoMath.distanceMeters(current.origin, destination.location) > config.arrivalRadiusMeters
        val next = ContextEngine.decide(
            inputs = ContextInputs(
                now = now, sessions = current.sessions, travelMinutes = estimate?.durationMinutes,
                bufferMinutes = lead + weatherBuffer, distanceMeters = distance,
                isMoving = if (current.sensingManaged) measured != null && isMoving == true else current.signals.moving,
                enRouteSignal = if (current.sensingManaged) enRoute else current.signals.enRoute,
                insideGeofence = geofenceEntry || (!current.sensingManaged && destination != null && current.signals.geofenceCode == destination.locCode),
                buildingCode = destination?.locCode,
                progressConfirmed = !current.sensingManaged || evidence?.progressConfirmed == true,
                resetProgress = current.sensingManaged && (current.resetProgress || measured == null),
                lastKnownAway = lastKnownAway,
                preparationMinutes = preparation
            ), previous = decision, config = config
        )
        decision = next
        if (current.resetProgress) inputs.value = current.copy(resetProgress = false)
        _snapshot.value = if (tracked == null) {
            if (next.finishedSession == null) TravelSnapshot() else TravelSnapshot(state = next.state, finishedSession = next.finishedSession)
        } else TravelSnapshot(
            session = tracked, building = destination, state = next.state, estimate = estimate,
            distanceMeters = distance, minutesUntilClass = Duration.between(now, tracked.start.toInstant()).toMinutes(),
            weather = weatherNow, weatherBufferMinutes = weatherBuffer, leadMinutes = lead,
            entryAllowanceMinutes = config.entryAllowanceMinutes, leaveBy = next.leaveBy?.atZone(tracked.start.zone),
            finishedSession = next.finishedSession, estimateOrigin = estimateOrigin, preparationMinutes = preparation
        )
    }

    private fun isCurrent(value: Inputs, target: SensingTarget): Boolean = synchronized(inputLock) {
        value.timetableGeneration == inputs.value.timetableGeneration && target.generation == _sensingTarget.value.generation
    }

    private fun sameSession(first: CourseSession?, second: CourseSession?): Boolean =
        if (first == null) second == null else second != null && first.matchesTimetableSession(second)

    private data class MotionSignals(
        val moving: Boolean = false,
        val enRoute: Boolean? = null,
        val geofenceCode: String? = null
    )

    /** Capture both settings together so asynchronous snapshots identify the exact timing used. */
    private data class ReminderTiming(val leadMinutes: Int, val preparationMinutes: Int)

    private data class Inputs(
        val sessions: List<CourseSession> = emptyList(),
        val sessionsInitialized: Boolean = false,
        val origin: GeoPoint? = null,
        val signals: MotionSignals = MotionSignals(),
        val leadMinutes: Int = DEFAULT_LEAD_MINUTES,
        val timetableGeneration: Long = 0,
        val sensingManaged: Boolean = false,
        val evidence: SensingEvidence? = null,
        val lastObservedNanos: Long = 0,
        val resetProgress: Boolean = false,
        val consumedGeofenceEntries: Map<String, Long> = emptyMap()
    )

    companion object {
        /** Matches the default of the reminder slider on the schedule screen. */
        const val DEFAULT_LEAD_MINUTES = DEFAULT_COURSE_REMINDER_LEAD_MINUTES

        /** Beyond this straight-line distance no travel time is requested or shown. */
        const val MAX_ROUTED_DISTANCE_METERS = 150_000.0
        val TICK_INTERVAL: Duration = Duration.ofSeconds(30)
        const val GEOFENCE_TRIGGER_MAX_AGE_NANOS = 180_000_000_000L
    }
}
