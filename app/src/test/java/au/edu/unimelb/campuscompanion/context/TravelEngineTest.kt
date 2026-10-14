package au.edu.unimelb.campuscompanion.context

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
import au.edu.unimelb.campuscompanion.ui.model.RouteEstimate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class TravelEngineTest {
    private var now: Instant = Instant.parse("2026-10-05T00:00:00Z")
    private val eta = RecordingEtaRepository { now }
    private var preferences = TravelPreferences()
    private val weather = FixedWeatherRepository { now }
    private val engine = TravelEngine(
        eta = eta,
        buildings = FakeBuildingLookup(),
        preferences = { preferences },
        weather = weather,
        clock = { now }
    ).also { it.updateLeadMinutes(10) }

    private val lecture = session("lecture", startsIn = Duration.ofMinutes(60), room = "PAR-160")
    private val tutorial = session("tutorial", startsIn = Duration.ofHours(3), room = "PAR-104")

    @Test
    fun theNextClassGetsATravelTime() = runBlocking<Unit> {
        engine.updateSessions(listOf(tutorial, lecture))
        engine.updateOrigin(HOME)

        engine.refresh()
        val snapshot = engine.snapshot.value

        assertEquals("lecture", snapshot.session?.id)
        assertEquals(FakeBuildingLookup.PETER_HALL, snapshot.building)
        assertEquals(15, snapshot.estimate?.durationMinutes)
        assertEquals(60L, snapshot.minutesUntilClass)
        assertEquals(ContextState.UPCOMING, snapshot.state)
        // 15 min travel + this fixture's explicit 10 min arrival buffer before the start.
        assertEquals(lecture.start.minusMinutes(25), snapshot.leaveBy)
        val applied = snapshot.applyTo(listOf(lecture, tutorial))
        assertEquals(listOf(15, null), applied.map { it.etaMinutes })
        // 1.2 km is beyond the walking threshold, so the default public transport mode was timed.
        assertEquals(
            RouteEstimate(distanceMeters = 1_500, publicTransportMinutes = 15),
            applied.first().routeEstimate
        )
        assertNull(applied[1].routeEstimate)
    }

    @Test
    fun aStraightLineFallbackIsPassedOnAsApproximate() = runBlocking<Unit> {
        eta.source = EstimateSource.StraightLine
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)

        engine.refresh()
        val applied = engine.snapshot.value.applyTo(listOf(lecture))

        assertEquals(true, applied.single().routeEstimate?.isApproximate)
    }

    @Test
    fun theTripMovesThroughLeaveSoonEnRouteAndArrived() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)

        now = now.plus(Duration.ofMinutes(36))
        engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)

        // Walking on the spot is not setting off.
        engine.updateMoving(true)
        engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)

        // About 110 m closer to Peter Hall while walking.
        engine.updateOrigin(GeoPoint(-37.8090, 144.9628))
        engine.refresh()
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)

        engine.updateOrigin(GeoPoint(-37.7996, 144.9610))
        engine.refresh()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
    }

    @Test
    fun theLeadTimeFromTheScheduleScreenIsTheBuffer() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        engine.updateLeadMinutes(0)

        now = now.plus(Duration.ofMinutes(36))
        engine.refresh()
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)
        assertEquals(0, engine.snapshot.value.leadMinutes)

        now = now.plus(Duration.ofMinutes(9))
        engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
        assertEquals(lecture.start.minusMinutes(15), engine.snapshot.value.leaveBy)
    }

    @Test
    fun rainAtTheClassMakesTheReminderEarlier() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        weather.precipitationMillimetres = 0.4

        now = now.plus(Duration.ofMinutes(31))
        engine.refresh()
        val snapshot = engine.snapshot.value

        // 29 minutes to go: travel 15 + lead 10 would still be fine, but rain adds 5.
        assertEquals(5, snapshot.weatherBufferMinutes)
        assertEquals(0.4, snapshot.weather?.precipitationMillimetres)
        assertEquals(ContextState.LEAVE_SOON, snapshot.state)
        assertEquals(lecture.start.minusMinutes(30), snapshot.leaveBy)
        assertEquals(listOf(FakeBuildingLookup.PETER_HALL.location), weather.requests)
    }

    @Test
    fun anUnknownBuildingGivesNoTravelTime() = runBlocking<Unit> {
        val elsewhere = session("elsewhere", startsIn = Duration.ofMinutes(30), room = "Online")
        engine.updateSessions(listOf(elsewhere))
        engine.updateOrigin(HOME)

        engine.refresh()
        val snapshot = engine.snapshot.value

        assertEquals("elsewhere", snapshot.session?.id)
        assertNull(snapshot.building)
        assertNull(snapshot.estimate)
        assertEquals(listOf(null), snapshot.applyTo(listOf(elsewhere)).map { it.etaMinutes })
        assertTrue(eta.requests.isEmpty())
    }

    @Test
    fun aPositionFarFromCampusGivesNoTravelTime() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(GeoPoint(37.4220, -122.0841)) // an emulator's default location

        engine.refresh()
        val snapshot = engine.snapshot.value

        assertEquals(FakeBuildingLookup.PETER_HALL, snapshot.building)
        assertTrue((snapshot.distanceMeters ?: 0.0) > TravelEngine.MAX_ROUTED_DISTANCE_METERS)
        assertNull(snapshot.estimate)
        assertTrue(eta.requests.isEmpty())
    }

    @Test
    fun withoutAPositionOnlyTheClassIsKnown() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))

        engine.refresh()
        val snapshot = engine.snapshot.value

        assertEquals("lecture", snapshot.session?.id)
        assertEquals(FakeBuildingLookup.PETER_HALL, snapshot.building)
        assertNull(snapshot.estimate)
        assertNull(snapshot.leaveBy)
        assertTrue(eta.requests.isEmpty())
    }

    @Test
    fun theStateStartsAgainForTheNextClass() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture, tutorial))
        engine.updateOrigin(FakeBuildingLookup.PETER_HALL.location)
        engine.refresh()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)

        now = now.plus(Duration.ofMinutes(121))
        engine.refresh()
        val snapshot = engine.snapshot.value

        assertEquals("tutorial", snapshot.session?.id)
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, snapshot.building)
        // The lecture has just finished; being at Peter Hall does not count as arriving at the tutorial.
        assertEquals(ContextState.POST_CLASS, snapshot.state)
        assertEquals("lecture", snapshot.finishedSession?.id)

        now = now.plus(Duration.ofMinutes(15))
        engine.refresh()
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)
    }

    @Test
    fun aClassInProgressIsInClass() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(FakeBuildingLookup.PETER_HALL.location)

        now = now.plus(Duration.ofMinutes(65))
        engine.refresh()

        assertEquals(ContextState.IN_CLASS, engine.snapshot.value.state)
    }

    @Test
    fun theSensingServicesEnRouteSignalCounts() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        now = now.plus(Duration.ofMinutes(36))
        engine.refresh()

        engine.updateEnRouteSignal(true)
        engine.refresh()

        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
    }

    @Test
    fun enteringTheArrivalGeofenceCountsAsArrived() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        // About 100 m from Peter Hall: outside the 75 m radius.
        engine.updateOrigin(GeoPoint(-37.8003, 144.9608))
        engine.refresh()
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)

        engine.reportGeofenceEntered(FakeBuildingLookup.PETER_HALL.locCode)
        engine.refresh()

        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
    }

    @Test
    fun nothingIsTrackedWithoutClasses() = runBlocking<Unit> {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)
        engine.refresh()

        engine.updateSessions(emptyList())
        engine.refresh()

        assertEquals(TravelSnapshot(), engine.snapshot.value)
    }

    @Test
    fun theTravelModeFollowsThePreferences() = runBlocking<Unit> {
        preferences = TravelPreferences(walkingThresholdMeters = 500, longerDistanceMode = TravelMode.Driving)
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(HOME)

        engine.refresh()

        assertEquals(listOf(TravelMode.Driving), eta.requests.map { it.mode })
    }

    @Test
    fun eachClassUsesItsOwnLeadTimeWithoutAScreenUpdatingGlobalPreferences() = runBlocking<Unit> {
        val perCourse = TravelEngine(
            eta = eta, buildings = FakeBuildingLookup(), preferences = { preferences }, clock = { now },
            reminderPreference = { CourseReminderPreference(leadMinutes = if (it.id == lecture.id) 0 else 30) }
        )
        perCourse.updateSessions(listOf(lecture, tutorial))
        perCourse.updateOrigin(HOME)
        now = now.plus(Duration.ofMinutes(36))
        perCourse.refresh()
        assertEquals(ContextState.UPCOMING, perCourse.snapshot.value.state)

        now = now.plus(Duration.ofMinutes(120))
        perCourse.refresh()
        assertEquals(tutorial.id, perCourse.snapshot.value.session?.id)
        assertEquals(ContextState.LEAVE_SOON, perCourse.snapshot.value.state)
    }

    private fun session(id: String, startsIn: Duration, room: String): CourseSession {
        val start = ZonedDateTime.ofInstant(now.plus(startsIn), ZoneId.of("Australia/Melbourne"))
        return CourseSession(
            id = id,
            code = "COMP90018",
            title = "Class $id",
            location = "Parkville Campus",
            room = room,
            start = start,
            end = start.plusHours(1)
        )
    }

    private companion object {
        /** About 1.2 km south of Peter Hall. */
        val HOME = GeoPoint(-37.8100, 144.9630)
    }
}

/** Answers the set rainfall and records where it was asked. */
private class FixedWeatherRepository(private val clock: () -> Instant) : WeatherRepository {
    val requests = mutableListOf<GeoPoint>()
    var precipitationMillimetres = 0.0

    override suspend fun currentWeather(location: GeoPoint): Result<WeatherSnapshot> {
        requests += location
        return Result.success(
            WeatherSnapshot(
                temperatureCelsius = 16.0,
                precipitationMillimetres = precipitationMillimetres,
                precipitationProbabilityPercent = 10,
                observedAt = clock()
            )
        )
    }
}

/** Always answers 15 minutes and records what was asked. */
private class RecordingEtaRepository(private val clock: () -> Instant) : EtaRepository {
    data class Request(val origin: GeoPoint, val destination: GeoPoint, val mode: TravelMode)

    val requests = mutableListOf<Request>()
    var source = EstimateSource.Routing

    override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): Result<TravelEstimate> {
        requests += Request(origin, destination, mode)
        return Result.success(
            TravelEstimate(
                mode = mode,
                durationSeconds = 15 * 60,
                distanceMeters = 1_500,
                source = source,
                computedAt = clock()
            )
        )
    }
}
