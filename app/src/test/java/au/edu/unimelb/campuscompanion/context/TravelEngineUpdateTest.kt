package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.data.repository.WeatherRepository
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Timetable edits keep occurrence IDs, so every travel result must follow the edited definition. */
class TravelEngineUpdateTest {
    @Test
    fun sameIdBuildingEditClearsTheOldSnapshotAndRefreshesEveryDestinationDependentField() = runBlocking<Unit> {
        val fixture = Fixture()
        val engine = fixture.engine
        engine.updateSessions(listOf(fixture.lecture))
        engine.updateOrigin(HOME)
        engine.refresh()
        val before = engine.snapshot.value
        assertEquals(15, before.estimate?.durationMinutes)
        assertEquals(5, before.weatherBufferMinutes)
        assertEquals(fixture.lecture.start.minusMinutes(20), before.leaveBy)

        val edited = fixture.lecture.copy(location = "Alan Gilbert", room = "PAR-104-G01")
        engine.updateSessions(listOf(edited))
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        engine.refresh()
        val after = engine.snapshot.value

        assertEquals(edited, after.session)
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, after.building)
        assertEquals(listOf(PETER_HALL, ALAN_GILBERT), fixture.eta.requests.map { it.destination })
        assertEquals(listOf(PETER_HALL, ALAN_GILBERT), fixture.weather.requests)
        assertEquals(GeoMath.distanceMeters(HOME, ALAN_GILBERT), after.distanceMeters!!, 0.001)
        assertFalse(before.distanceMeters == after.distanceMeters)
        assertEquals(7, after.estimate?.durationMinutes)
        assertEquals(2.6, after.weather!!.precipitationMillimetres, 0.0)
        assertEquals(10, after.weatherBufferMinutes)
        assertEquals(edited.start.minusMinutes(17), after.leaveBy)
    }

    @Test
    fun changingAKnownVenueToAnUnknownOrOnlineVenueClearsAllTravelFields() = runBlocking<Unit> {
        listOf("Unlisted teaching venue", "Online").forEach { venue ->
            val fixture = Fixture()
            val engine = fixture.engine
            engine.updateSessions(listOf(fixture.lecture))
            engine.updateOrigin(PETER_HALL)
            engine.refresh()
            assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)

            val edited = fixture.lecture.copy(location = venue, room = venue)
            engine.updateSessions(listOf(edited))
            assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
            engine.refresh()
            val after = engine.snapshot.value

            assertEquals(edited, after.session)
            assertNull(after.building)
            assertNull(after.estimate)
            assertNull(after.distanceMeters)
            assertNull(after.weather)
            assertEquals(0, after.weatherBufferMinutes)
            assertNull(after.leaveBy)
            assertEquals(ContextState.UPCOMING, after.state)
            assertEquals(1, fixture.eta.requests.size)
            assertEquals(1, fixture.weather.requests.size)
            assertNull(after.applyTo(listOf(edited)).single().etaMinutes)
            assertNull(after.applyTo(listOf(edited)).single().routeEstimate)
        }
    }

    @Test
    fun changingAnUnknownVenueToAKnownVenueStartsTravelGuidanceForTheSameId() = runBlocking<Unit> {
        val fixture = Fixture()
        val engine = fixture.engine
        engine.updateSessions(listOf(fixture.lecture.copy(location = "Online", room = "Online")))
        engine.updateOrigin(HOME)
        engine.refresh()
        assertNull(engine.snapshot.value.building)
        assertTrue(fixture.eta.requests.isEmpty())

        engine.updateSessions(listOf(fixture.lecture))
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        engine.refresh()

        val after = engine.snapshot.value
        assertEquals(FakeBuildingLookup.PETER_HALL, after.building)
        assertEquals(15, after.estimate?.durationMinutes)
        assertEquals(fixture.lecture.start.minusMinutes(20), after.leaveBy)
        assertEquals(listOf(PETER_HALL), fixture.weather.requests)
    }

    @Test
    fun aRoomOnlyEditInTheSameBuildingRefreshesClassDetailsAndKeepsArrival() = runBlocking<Unit> {
        val fixture = Fixture()
        val engine = fixture.engine
        engine.updateSessions(listOf(fixture.lecture))
        engine.updateOrigin(PETER_HALL)
        engine.refresh()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)

        val edited = fixture.lecture.copy(room = "PAR-160-201")
        engine.updateOrigin(null)
        engine.updateSessions(listOf(edited))
        // The published snapshot names the old room, so Home shows Updating until the refresh.
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        engine.refresh()

        val after = engine.snapshot.value
        assertEquals(edited, after.session)
        assertEquals(FakeBuildingLookup.PETER_HALL, after.building)
        // Neither the student nor the building moved, so the arrival still holds.
        assertEquals(ContextState.ARRIVED, after.state)
        assertNull(after.estimate)
        assertNull(after.leaveBy)
    }

    @Test
    fun aStartTimeEditReplacesTheOldDeadlineAndArrivalHistory() = runBlocking<Unit> {
        val fixture = Fixture()
        val engine = fixture.engine
        engine.updateSessions(listOf(fixture.lecture))
        engine.updateOrigin(PETER_HALL)
        engine.refresh()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)

        val edited = fixture.lecture.copy(start = fixture.lecture.start.plusMinutes(10))
        engine.updateOrigin(HOME)
        engine.updateSessions(listOf(edited))
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        engine.refresh()

        assertEquals(edited, engine.snapshot.value.session)
        assertEquals(40L, engine.snapshot.value.minutesUntilClass)
        assertEquals(edited.start.minusMinutes(20), engine.snapshot.value.leaveBy)
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)
    }

    @Test
    fun shorteningTheTrackedClassEndSelectsTheNextClassAndItsDestination() = runBlocking<Unit> {
        val fixture = Fixture()
        val engine = fixture.engine
        val tutorial = fixture.lecture.copy(
            id = "tutorial",
            room = "PAR-104-G01",
            start = fixture.lecture.end.plusMinutes(10),
            end = fixture.lecture.end.plusMinutes(70)
        )
        fixture.now = Instant.parse("2026-10-05T01:55:00Z")
        engine.updateSessions(listOf(fixture.lecture, tutorial))
        engine.updateOrigin(PETER_HALL)
        engine.refresh()
        assertEquals(fixture.lecture, engine.snapshot.value.session)

        val finished = fixture.lecture.copy(end = fixture.lecture.end.minusMinutes(10))
        engine.updateSessions(listOf(finished, tutorial))
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        engine.refresh()

        val after = engine.snapshot.value
        assertEquals(tutorial, after.session)
        assertEquals(finished, after.finishedSession)
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, after.building)
        assertEquals(tutorial.start.minusMinutes(17), after.leaveBy)
        assertEquals(ContextState.LEAVE_SOON, after.state)
    }

    @Test
    fun aVenueEditClearsTheOldEnRouteVerdictBeforeTheFirstFixAtTheNewDestination() = runBlocking<Unit> {
        val fixture = Fixture()
        val engine = fixture.engine
        engine.updateSessions(listOf(fixture.lecture))
        engine.updateOrigin(HOME)
        engine.updateEnRouteSignal(true)
        engine.refresh()
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)

        val edited = fixture.lecture.copy(room = "PAR-104-G01")
        engine.updateOrigin(null)
        engine.updateSessions(listOf(edited))
        engine.refresh()
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)
        assertNull(engine.snapshot.value.distanceMeters)

        engine.updateOrigin(HOME)
        engine.refresh()
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)
    }

    @Test
    fun changingVenueAwayAndBackWithoutGpsDoesNotReuseTheOldGeofenceEntry() = runBlocking<Unit> {
        val fixture = Fixture()
        val engine = fixture.engine
        engine.updateSessions(listOf(fixture.lecture))
        engine.reportGeofenceEntered(FakeBuildingLookup.PETER_HALL.locCode)
        engine.refresh()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)

        // The engine tracks the class at Alan Gilbert, which clears the Peter Hall evidence.
        engine.updateSessions(listOf(fixture.lecture.copy(room = "PAR-104-G01")))
        engine.refresh()
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, engine.snapshot.value.building)
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)

        engine.updateSessions(listOf(fixture.lecture))
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        engine.refresh()

        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)
        assertNull(engine.snapshot.value.distanceMeters)
    }

    @Test
    fun globalMovingEvidenceStillBuildsAFreshDistanceTrendAfterAVenueEdit() = runBlocking<Unit> {
        val fixture = Fixture()
        val engine = fixture.engine
        engine.updateSessions(listOf(fixture.lecture))
        engine.updateOrigin(HOME)
        engine.updateMoving(true)
        engine.refresh()

        engine.updateSessions(listOf(fixture.lecture.copy(room = "PAR-104-G01")))
        engine.refresh()
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)

        // A fresh measurement closer to Alan Gilbert uses the existing global moving signal.
        engine.updateOrigin(GeoPoint(-37.8090, 144.9630))
        engine.refresh()
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
    }

    @Test
    fun etaAndRouteEnrichmentKeepsThePublishedSnapshotAndExistingDistanceTrend() = runBlocking<Unit> {
        val fixture = Fixture()
        val engine = fixture.engine
        engine.updateSessions(listOf(fixture.lecture))
        engine.updateOrigin(HOME)
        engine.updateMoving(true)
        engine.refresh()
        val before = engine.snapshot.value
        val enriched = before.applyTo(listOf(fixture.lecture)).single()
        assertEquals(15, enriched.etaMinutes)
        assertTrue(enriched.routeEstimate != null)

        engine.updateSessions(listOf(enriched))
        assertEquals(before, engine.snapshot.value)
        engine.updateOrigin(GeoPoint(-37.8090, 144.9630))
        engine.refresh()
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
        assertEquals(enriched, engine.snapshot.value.session)

        // Another enriched update must also preserve the trip while GPS is temporarily absent.
        val enrichedAgain = engine.snapshot.value.applyTo(listOf(enriched)).single()
        engine.updateSessions(listOf(enrichedAgain))
        engine.updateOrigin(null)
        engine.refresh()
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
    }

    @Test
    fun accountResetClearsIdenticalClassesArrivalAndTargetSignals() = runBlocking<Unit> {
        val fixture = Fixture()
        val engine = fixture.engine
        engine.updateSessions(listOf(fixture.lecture))
        engine.updateEnRouteSignal(true)
        engine.reportGeofenceEntered(FakeBuildingLookup.PETER_HALL.locCode)
        engine.refresh()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)

        engine.updateSessions(listOf(fixture.lecture), reset = true)
        assertEquals(TravelSnapshot.PENDING, engine.snapshot.value)
        engine.refresh()
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)

        engine.updateOrigin(HOME)
        engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
    }

    private class Fixture {
        var now: Instant = Instant.parse("2026-10-05T00:30:00Z")
        val lecture = CourseSession(
            id = "lecture",
            code = "COMP90018",
            title = "Mobile Computing Lecture",
            location = "Parkville Campus",
            room = "PAR-160-B117",
            start = Instant.parse("2026-10-05T01:00:00Z").atZone(ZoneId.of("Australia/Melbourne")),
            end = Instant.parse("2026-10-05T02:00:00Z").atZone(ZoneId.of("Australia/Melbourne")),
            activity = "Lecture"
        )
        val eta = DestinationEtaRepository { now }
        val weather = DestinationWeatherRepository { now }
        val engine = TravelEngine(
            eta = eta,
            buildings = FakeBuildingLookup(),
            preferences = { TravelPreferences() },
            weather = weather,
            clock = { now }
        )
    }

    private class DestinationEtaRepository(private val clock: () -> Instant) : EtaRepository {
        data class Request(val origin: GeoPoint, val destination: GeoPoint)
        val requests = mutableListOf<Request>()

        override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): Result<TravelEstimate> {
            requests += Request(origin, destination)
            val minutes = if (destination == ALAN_GILBERT) 7 else 15
            return Result.success(
                TravelEstimate(
                    mode = mode,
                    durationSeconds = minutes * 60L,
                    distanceMeters = if (destination == ALAN_GILBERT) 800 else 1_500,
                    source = EstimateSource.Routing,
                    computedAt = clock()
                )
            )
        }
    }

    private class DestinationWeatherRepository(private val clock: () -> Instant) : WeatherRepository {
        val requests = mutableListOf<GeoPoint>()

        override suspend fun currentWeather(location: GeoPoint): Result<WeatherSnapshot> {
            requests += location
            return Result.success(
                WeatherSnapshot(
                    temperatureCelsius = 16.0,
                    precipitationMillimetres = if (location == ALAN_GILBERT) 2.6 else 0.4,
                    precipitationProbabilityPercent = 10,
                    observedAt = clock()
                )
            )
        }
    }

    private companion object {
        val HOME = GeoPoint(-37.8100, 144.9630)
        val PETER_HALL = FakeBuildingLookup.PETER_HALL.location
        val ALAN_GILBERT = FakeBuildingLookup.ALAN_GILBERT.location
    }
}
