package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.english

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.data.repository.WeatherRepository
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelEngineStaleResultTest {
    private var now = Instant.parse("2026-10-12T00:00:00Z")
    private val eta = ControlledEta { now }
    private val weather = RecordingWeather { now }
    private val engine = engine(weather)
    private val lecture = session("lecture", startsIn = 25, endsIn = 85, room = "PAR-160")

    @Test
    fun aVenueEditDuringEtaDiscardsOldTravelAndSkipsItsWeather() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        engine.updateEnRouteSignal(true)
        val oldRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        val oldRequest = eta.next()
        val edited = lecture.copy(room = "PAR-104")

        engine.updateSessions(listOf(edited))
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        oldRequest.complete()
        withTimeout(TIMEOUT_MS) { oldRefresh.await() }

        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        assertTrue(weather.requests.isEmpty())
        refreshAndComplete(engine)
        assertEquals(edited, engine.snapshot.value.session)
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, engine.snapshot.value.building)
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
        assertEquals(listOf(FakeBuildingLookup.ALAN_GILBERT.location), weather.requests)
    }

    @Test
    fun aVenueEditDuringWeatherDiscardsTheOldWeatherDeadlineAndDecision() = runBlocking<Unit> {
        val delayedWeather = ControlledWeather { now }
        val engine = engine(delayedWeather)
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        engine.updateEnRouteSignal(true)
        val oldRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        eta.next().complete()
        val oldWeather = delayedWeather.next()
        val edited = lecture.copy(room = "PAR-104")

        engine.updateSessions(listOf(edited))
        oldWeather.complete(precipitation = 0.8)
        withTimeout(TIMEOUT_MS) { oldRefresh.await() }
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)

        val freshRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        val freshEta = eta.next()
        assertEquals(FakeBuildingLookup.ALAN_GILBERT.location, freshEta.destination)
        freshEta.complete()
        val freshWeather = delayedWeather.next()
        assertEquals(FakeBuildingLookup.ALAN_GILBERT.location, freshWeather.location)
        freshWeather.complete(precipitation = 0.0)
        withTimeout(TIMEOUT_MS) { freshRefresh.await() }

        assertEquals(edited, engine.snapshot.value.session)
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, engine.snapshot.value.building)
        assertEquals(0.0, engine.snapshot.value.weather?.precipitationMillimetres)
        assertEquals(0, engine.snapshot.value.weatherBufferMinutes)
        assertEquals(edited.start.minusMinutes(15), engine.snapshot.value.leaveBy)
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
    }

    @Test
    fun aSameBuildingRoomCorrectionDuringEtaDiscardsTheOldResultButKeepsArrival() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(FakeBuildingLookup.PETER_HALL.location)
        refreshAndComplete(engine)
        // Indoor GPS drifts beyond the arrival radius but within the leaving radius.
        engine.updateOrigin(NEAR_PETER_HALL)
        refreshAndComplete(engine)
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)

        val oldRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        val oldRequest = eta.next()
        val corrected = lecture.copy(room = "PAR-160-201")
        engine.updateSessions(listOf(corrected))
        oldRequest.complete()
        withTimeout(TIMEOUT_MS) { oldRefresh.await() }
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)

        refreshAndComplete(engine)
        assertEquals(corrected, engine.snapshot.value.session)
        assertEquals(FakeBuildingLookup.PETER_HALL, engine.snapshot.value.building)
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
    }

    @Test
    fun deletingTheTrackedClassWhileEtaIsPendingCannotRestoreIt() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        val oldRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        val oldRequest = eta.next()

        engine.updateSessions(emptyList())
        oldRequest.complete()
        withTimeout(TIMEOUT_MS) { oldRefresh.await() }

        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        assertTrue(weather.requests.isEmpty())
        engine.refresh()
        assertEquals(TravelSnapshot(), engine.snapshot.value)
    }

    @Test
    fun deletingAndReaddingAnIdenticalOccurrenceStillInvalidatesThePendingGeneration() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(NEAR_PETER_HALL)
        engine.reportGeofenceEntered(FakeBuildingLookup.PETER_HALL.locCode)
        val oldRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        val oldRequest = eta.next()

        engine.updateSessions(emptyList())
        engine.updateSessions(listOf(lecture))
        oldRequest.complete()
        withTimeout(TIMEOUT_MS) { oldRefresh.await() }

        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        assertTrue(weather.requests.isEmpty())
        refreshAndComplete(engine)
        // Outside the arrival radius: the abandoned request's geofence/memory cannot mark arrival.
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
    }

    @Test
    fun anAccountResetInvalidatesPendingTravelEvenWithIdenticalClasses() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(NEAR_PETER_HALL)
        engine.reportGeofenceEntered(FakeBuildingLookup.PETER_HALL.locCode)
        val oldRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        val oldRequest = eta.next()

        engine.updateSessions(listOf(lecture), reset = true)
        oldRequest.complete()
        withTimeout(TIMEOUT_MS) { oldRefresh.await() }

        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        assertTrue(weather.requests.isEmpty())
        refreshAndComplete(engine)
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
    }

    @Test
    fun theRunPipelineComputesTheEditedVenueWithoutSendingAnOldVenueReminder() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        val reminderSubscribed = CompletableDeferred<Unit>()
        val sent = mutableListOf<DepartureReminder>()
        val delivered = Channel<DepartureReminder>(Channel.UNLIMITED)
        val reminders = DepartureReminders(
            snapshots = engine.snapshot.onSubscription { reminderSubscribed.complete(Unit) },
            preferences = { CourseReminderPreference() },
            notify = { reminder ->
                sent += reminder
                delivered.trySend(reminder)
                true
            }
        )
        val reminderJob = launch(start = CoroutineStart.UNDISPATCHED) { reminders.run() }
        val engineJob = launch(start = CoroutineStart.UNDISPATCHED) { engine.run() }
        try {
            withTimeout(TIMEOUT_MS) { reminderSubscribed.await() }
            val oldRequest = eta.next()
            val edited = lecture.copy(room = "PAR-104")
            engine.updateSessions(listOf(edited))
            oldRequest.complete()

            val freshRequest = eta.next()
            assertEquals(FakeBuildingLookup.ALAN_GILBERT.location, freshRequest.destination)
            assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
            assertTrue(sent.isEmpty())
            freshRequest.complete()
            val reminder = withTimeout(TIMEOUT_MS) { delivered.receive() }

            assertEquals(edited, engine.snapshot.value.session)
            assertEquals(FakeBuildingLookup.ALAN_GILBERT, engine.snapshot.value.building)
            assertEquals(listOf(FakeBuildingLookup.ALAN_GILBERT.location), weather.requests)
            assertTrue(reminder.text.english().contains(FakeBuildingLookup.ALAN_GILBERT.name))
            assertFalse(reminder.text.english().contains(FakeBuildingLookup.PETER_HALL.name))
            assertEquals(listOf(reminder), sent)
        } finally {
            engineJob.cancelAndJoin()
            reminderJob.cancelAndJoin()
        }
    }

    @Test
    fun aClockTargetChangeClearsOldEvidenceBeforeRoutingAndKeepsNewGeofenceEvents() = runBlocking<Unit> {
        val first = session("first", startsIn = 5, endsIn = 10, room = "PAR-160")
        val second = session("second", startsIn = 60, endsIn = 120, room = "PAR-104")
        engine.updateSessions(listOf(first, second))
        engine.updateOrigin(NEAR_ALAN_GILBERT)
        engine.updateEnRouteSignal(true)
        refreshAndComplete(engine)
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)

        now = now.plus(Duration.ofMinutes(26))
        engine.updateOrigin(NEAR_ALAN_GILBERT)
        val nextRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        val nextRequest = eta.next()
        assertEquals(FakeBuildingLookup.ALAN_GILBERT.location, nextRequest.destination)
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        engine.reportGeofenceEntered(FakeBuildingLookup.ALAN_GILBERT.locCode)
        nextRequest.complete()
        withTimeout(TIMEOUT_MS) { nextRefresh.await() }

        // This refresh captured its motion before routing: neither old A nor new B evidence leaks.
        assertEquals(second, engine.snapshot.value.session)
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)
        refreshAndComplete(engine)
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
    }

    @Test
    fun aQueuedRefreshReadsNewSignalsInsteadOfReplayingItsCapturedInputs() = runBlocking<Unit> {
        val first = session("first", startsIn = 5, endsIn = 10, room = "PAR-160")
        val second = session("second", startsIn = 60, endsIn = 120, room = "PAR-104")
        engine.updateSessions(listOf(first, second))
        engine.updateOrigin(NEAR_ALAN_GILBERT)
        engine.updateEnRouteSignal(true)
        refreshAndComplete(engine)
        now = now.plus(Duration.ofMinutes(26))
        engine.updateOrigin(NEAR_ALAN_GILBERT)

        val nextRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        val nextRequest = eta.next()
        val queuedRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        engine.reportGeofenceEntered(FakeBuildingLookup.ALAN_GILBERT.locCode)
        nextRequest.complete()
        withTimeout(TIMEOUT_MS) { nextRefresh.await() }
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)

        val queuedRequest = eta.next()
        assertEquals(FakeBuildingLookup.ALAN_GILBERT.location, queuedRequest.destination)
        queuedRequest.complete()
        withTimeout(TIMEOUT_MS) { queuedRefresh.await() }
        assertEquals(second, engine.snapshot.value.session)
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
    }

    @Test
    fun crossingAClassBoundaryDuringTheFirstEtaRequestCannotPublishOrCarryOldEvidence() = runBlocking<Unit> {
        val first = session("first", startsIn = 5, endsIn = 10, room = "PAR-160")
        val second = session("second", startsIn = 60, endsIn = 120, room = "PAR-104")
        engine.updateSessions(listOf(first, second))
        engine.updateOrigin(NEAR_ALAN_GILBERT)
        engine.updateEnRouteSignal(true)
        val firstRefresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        val firstRequest = eta.next()
        assertEquals(FakeBuildingLookup.PETER_HALL.location, firstRequest.destination)

        now = now.plus(Duration.ofMinutes(26))
        engine.updateOrigin(NEAR_ALAN_GILBERT)
        firstRequest.complete()
        withTimeout(TIMEOUT_MS) { firstRefresh.await() }

        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        refreshAndComplete(engine)
        assertEquals(second, engine.snapshot.value.session)
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, engine.snapshot.value.building)
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)
    }

    @Test
    fun aPositionThatExpiresWhileRoutingCannotPublishAStaleTravelTime() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        val request = eta.next()
        now = now.plus(Duration.ofMinutes(3))
        request.complete()
        withTimeout(TIMEOUT_MS) { refresh.await() }

        assertEquals(lecture, engine.snapshot.value.session)
        org.junit.Assert.assertNull(engine.snapshot.value.estimate)
        org.junit.Assert.assertNull(engine.snapshot.value.distanceMeters)
        assertEquals(au.edu.unimelb.campuscompanion.sensing.location.DistanceTrend.UNKNOWN,
            engine.snapshot.value.destinationProgress)
    }

    private fun engine(weather: WeatherRepository) = TravelEngine(
        eta = eta,
        buildings = FakeBuildingLookup(),
        preferences = { TravelPreferences() },
        weather = weather,
        clock = { now },
        tickInterval = Duration.ofHours(1)
    )

    private suspend fun CoroutineScope.refreshAndComplete(engine: TravelEngine) {
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        eta.next().complete()
        withTimeout(TIMEOUT_MS) { refresh.await() }
    }

    private fun session(id: String, startsIn: Long, endsIn: Long, room: String) = CourseSession(
        id = id,
        code = "COMP90018",
        title = "Class $id",
        activity = "lecture",
        location = "Parkville Campus",
        room = room,
        start = ZonedDateTime.ofInstant(now.plus(Duration.ofMinutes(startsIn)), MELBOURNE),
        end = ZonedDateTime.ofInstant(now.plus(Duration.ofMinutes(endsIn)), MELBOURNE)
    )

    private class ControlledEta(private val clock: () -> Instant) : EtaRepository {
        private val requests = Channel<Request>(Channel.UNLIMITED)

        override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): Result<TravelEstimate> {
            val request = Request(destination, mode, clock())
            requests.send(request)
            return Result.success(request.answer.await())
        }

        suspend fun next(): Request = withTimeout(TIMEOUT_MS) { requests.receive() }

        class Request(val destination: GeoPoint, private val mode: TravelMode, private val now: Instant) {
            val answer = CompletableDeferred<TravelEstimate>()

            fun complete() {
                answer.complete(TravelEstimate(
                    mode = mode,
                    durationSeconds = 15 * 60L,
                    distanceMeters = 1_500,
                    source = EstimateSource.Routing,
                    computedAt = now
                ))
            }
        }
    }

    private class ControlledWeather(private val clock: () -> Instant) : WeatherRepository {
        private val requests = Channel<Request>(Channel.UNLIMITED)

        override suspend fun currentWeather(location: GeoPoint): Result<WeatherSnapshot> {
            val request = Request(location, clock())
            requests.send(request)
            return Result.success(request.answer.await())
        }

        suspend fun next(): Request = withTimeout(TIMEOUT_MS) { requests.receive() }

        class Request(val location: GeoPoint, private val now: Instant) {
            val answer = CompletableDeferred<WeatherSnapshot>()

            fun complete(precipitation: Double) {
                answer.complete(weatherAt(now, precipitation))
            }
        }
    }

    private class RecordingWeather(private val clock: () -> Instant) : WeatherRepository {
        val requests = mutableListOf<GeoPoint>()

        override suspend fun currentWeather(location: GeoPoint): Result<WeatherSnapshot> {
            requests += location
            return Result.success(weatherAt(clock(), precipitation = 0.0))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        val MELBOURNE: ZoneId = ZoneId.of("Australia/Melbourne")
        val HOME = GeoPoint(-37.8100, 144.9630)
        // About 100 m away, beyond arrival radius but within geofence hysteresis range.
        val NEAR_PETER_HALL = GeoPoint(-37.8003, 144.9608)
        val NEAR_ALAN_GILBERT = GeoPoint(-37.8022, 144.9587)

        fun weatherAt(now: Instant, precipitation: Double) = WeatherSnapshot(
            temperatureCelsius = 16.0,
            precipitationMillimetres = precipitation,
            precipitationProbabilityPercent = 10,
            observedAt = now
        )
    }
}
