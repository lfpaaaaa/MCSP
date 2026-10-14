package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.MovementObservation
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TravelEngineSensingLifecycleTest {
    private var now = Instant.parse("2026-10-13T00:00:00Z")
    private var elapsed = 100_000_000_000L
    private val building = FakeBuildingLookup.PETER_HALL
    private val away = GeoPoint(-37.8100, 144.9630)
    private val zone = ZoneId.of("Australia/Melbourne")
    private val lecture = CourseSession("lecture", "COMP90018", "Lecture", "Parkville", "PAR-160",
        now.plusSeconds(25 * 60).atZone(zone), now.plusSeconds(85 * 60).atZone(zone))
    private val routes = object : EtaRepository {
        override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode) =
            Result.success(TravelEstimate(mode, 15 * 60, 1500, EstimateSource.Routing, now))
    }
    private fun engine(eta: EtaRepository = routes) = TravelEngine(eta, FakeBuildingLookup(),
        { TravelPreferences() }, clock = { now }, elapsedNanos = { elapsed }).also {
        it.updateLeadMinutes(10); it.updateSessions(listOf(lecture)); it.startSensing()
    }
    private fun observe(engine: TravelEngine, point: GeoPoint, enRoute: Boolean? = null): Boolean =
        engine.updateEvidence(SensingEvidence(engine.sensingTarget.value.generation,
            LocationSample(point.latitude, point.longitude, 10f, now.toEpochMilli(), elapsed),
            MovementObservation(true, elapsed), enRoute, progressConfirmed = true))
    private fun advance(seconds: Long) {
        now = now.plusSeconds(seconds); elapsed += seconds * 1_000_000_000L
    }

    @Test fun normalClearsTripAndRawFusionWithoutLosingGuidanceOrOrigin() = runBlocking {
        val engine = engine()
        observe(engine, away, true)
        engine.refresh()
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
        val deadline = engine.snapshot.value.leaveBy
        val generation = engine.sensingTarget.value.generation
        engine.clearSensingEvidence()
        engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
        assertEquals(deadline, engine.snapshot.value.leaveBy)
        assertEquals(away, engine.snapshot.value.estimateOrigin)
        assertEquals(generation, engine.sensingTarget.value.generation)
        assertNotNull(engine.snapshot.value.distanceMeters)
    }

    @Test fun normalKeepsConfirmedArrivalWhenPositionExpires() = runBlocking {
        val engine = engine()
        observe(engine, building.location)
        engine.refresh()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
        advance(40)
        engine.clearSensingEvidence()
        engine.refresh()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
        assertNull(engine.snapshot.value.distanceMeters)
        assertNotNull(engine.snapshot.value.estimate)
    }

    @Test fun repeatedStartKeepsTripGenerationAndArrival() {
        val engine = engine()
        observe(engine, building.location)
        val before = engine.sensingTarget.value
        repeat(3) { assertEquals(before, engine.startSensing()) }
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
    }

    @Test fun nextOccurrenceInSameBuildingGetsIndependentTimingAndNoArrival() = runBlocking {
        val engine = engine()
        val second = lecture.copy(id = "second", start = lecture.end.plusMinutes(20), end = lecture.end.plusMinutes(80))
        engine.updateSessions(listOf(lecture, second))
        observe(engine, building.location)
        val first = engine.sensingTarget.value
        advance(85 * 60)
        engine.refresh()
        assertEquals(second, engine.sensingSession.value)
        assertEquals(second.id, engine.sensingTarget.value.sessionId)
        assertTrue(engine.sensingTarget.value.generation > first.generation)
        assertEquals(first.building, engine.sensingTarget.value.building)
        assertNotEquals(ContextState.ARRIVED, engine.snapshot.value.state)
        assertNull(engine.snapshot.value.distanceMeters)
        assertNull(engine.snapshot.value.estimate)
    }

    @Test fun pendingNetworkAndSameBuildingCorrectionKeepIndependentTiming() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : EtaRepository {
            override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): Result<TravelEstimate> {
                started.complete(Unit); release.await()
                return routes.estimate(origin, destination, mode)
            }
        }
        val engine = engine(delayed)
        observe(engine, away)
        val request = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        started.await()
        val correction = lecture.copy(room = "PAR-160-201", title = "Updated lecture")
        val target = engine.sensingTarget.value
        engine.updateSessions(listOf(correction))
        assertTrue(engine.snapshot.value.pending)
        assertEquals(correction, engine.sensingSession.value)
        assertEquals(target, engine.sensingTarget.value)
        release.complete(Unit); request.await()
        assertTrue(engine.snapshot.value.pending)
        assertEquals(correction, engine.sensingSession.value)
    }
}
