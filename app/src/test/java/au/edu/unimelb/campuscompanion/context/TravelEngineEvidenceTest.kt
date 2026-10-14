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

class TravelEngineEvidenceTest {
    private var now = Instant.parse("2026-10-13T00:00:00Z")
    private var elapsed = 100_000_000_000L
    private val building = FakeBuildingLookup.PETER_HALL
    private val home = GeoPoint(-37.8100, 144.9630)
    private val closer = GeoPoint(-37.8080, 144.9630)
    private val lecture = CourseSession(
        id = "lecture", code = "COMP90018", title = "Lecture", location = "Parkville",
        room = "PAR-160", start = now.plusSeconds(25 * 60).atZone(ZoneId.of("Australia/Melbourne")),
        end = now.plusSeconds(85 * 60).atZone(ZoneId.of("Australia/Melbourne"))
    )
    private val eta = object : EtaRepository {
        override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode) =
            Result.success(estimate(mode))
    }

    private fun estimate(mode: TravelMode = TravelMode.Walking) = TravelEstimate(
        mode = mode, durationSeconds = 15 * 60, distanceMeters = 1500,
        source = EstimateSource.Routing, computedAt = now
    )

    private fun engine(routes: EtaRepository = eta, weather: WeatherRepository? = null) = TravelEngine(
        routes, FakeBuildingLookup(), { TravelPreferences() }, weather, clock = { now }, elapsedNanos = { elapsed }
    ).also { it.updateLeadMinutes(10); it.updateSessions(listOf(lecture)); it.startSensing() }

    private fun evidence(engine: TravelEngine, point: GeoPoint = home, moving: Boolean = true,
        verdict: Boolean? = null, progress: Boolean = false, observed: Long = elapsed,
        motionObserved: Long = elapsed, accuracy: Float = 10f, generation: Long = engine.sensingTarget.value.generation
    ) = SensingEvidence(generation,
        LocationSample(point.latitude, point.longitude, accuracy, now.toEpochMilli(), observed),
        MovementObservation(moving, motionObserved), verdict, progress)

    private fun advance(seconds: Long) { elapsed += seconds * 1_000_000_000; now = now.plusSeconds(seconds) }

    @Test fun targetIsAvailableBeforeRoutingAndPendingRoomEditsDoNotRestartIt() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val routes = object : EtaRepository {
            override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): Result<TravelEstimate> {
                requested.complete(Unit); release.await(); return Result.success(estimate(mode))
            }
        }
        val engine = engine(routes)
        val target = engine.sensingTarget.value
        assertEquals(building, target.building)
        assertEquals("lecture", target.sessionId)
        engine.updateEvidence(evidence(engine))
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        requested.await()
        engine.updateSessions(listOf(lecture.copy(room = "PAR-160-201")))
        assertTrue(engine.snapshot.value.pending)
        assertEquals(target, engine.sensingTarget.value)
        release.complete(Unit); refresh.await()
        assertTrue(engine.snapshot.value.pending)
    }

    @Test fun oldFutureInaccurateAndReplayedFixesCannotEstablishTravelOrArrival() = runBlocking {
        val engine = engine()
        advance(40)
        val target = engine.sensingTarget.value
        val bad = listOf(
            evidence(engine, building.location, verdict = true, observed = elapsed - 31_000_000_000),
            evidence(engine, building.location, verdict = true, observed = elapsed + 1),
            evidence(engine, building.location, verdict = true, accuracy = Float.NaN),
            evidence(engine, building.location, verdict = true, accuracy = 51f),
            evidence(engine, building.location, verdict = true, observed = target.activatedAtNanos - 1)
        )
        bad.forEach { assertFalse(engine.updateEvidence(it)) }
        assertTrue(engine.updateEvidence(evidence(engine, observed = elapsed - 10_000_000_000)))
        assertFalse(engine.updateEvidence(evidence(engine, building.location, verdict = true, observed = elapsed - 10_000_000_000)))
        assertFalse(engine.updateEvidence(evidence(engine, building.location, verdict = true, observed = elapsed - 11_000_000_000)))
        engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
    }

    @Test fun atomicNegativeBlocksFallbackWhileUnavailableFusionPermitsConfirmedProgress() {
        val engine = engine()
        engine.updateEvidence(evidence(engine))
        advance(10)
        engine.updateEvidence(evidence(engine, closer, verdict = false, progress = true))
        assertEquals(ContextState.UPCOMING, engine.snapshot.value.state)
        advance(10)
        engine.updateEvidence(evidence(engine, closer, progress = true))
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
    }

    @Test fun oneJumpAndStaleMovementCannotLatchFallback() {
        val engine = engine()
        engine.updateEvidence(evidence(engine))
        advance(10)
        engine.updateEvidence(evidence(engine, closer))
        assertFalse(engine.snapshot.value.state == ContextState.EN_ROUTE)
        advance(10)
        engine.updateEvidence(evidence(engine, closer, verdict = true, progress = true, motionObserved = elapsed - 6_000_000_000))
        assertFalse(engine.snapshot.value.state == ContextState.EN_ROUTE)
    }

    @Test fun staleGapClearsUnconfirmedProgressButKeepsEstablishedTripAndGuidance() = runBlocking {
        val engine = engine()
        engine.updateEvidence(evidence(engine))
        engine.refresh()
        val deadline = engine.snapshot.value.leaveBy
        advance(31)
        engine.updateEvidence(evidence(engine, closer, progress = true))
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
        advance(10)
        engine.updateEvidence(evidence(engine, closer, verdict = true))
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
        advance(180)
        engine.refresh()
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
        assertEquals(deadline, engine.snapshot.value.leaveBy)
        assertNull(engine.snapshot.value.distanceMeters)
        assertNotNull(engine.snapshot.value.estimate)
    }

    @Test fun expiryOnTickCannotNewlyArriveButKeepsLeaveByAfterThreeMinutes() = runBlocking {
        val engine = engine()
        engine.updateEvidence(evidence(engine, moving = false))
        engine.refresh()
        val deadline = engine.snapshot.value.leaveBy
        advance(180)
        engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
        assertEquals(deadline, engine.snapshot.value.leaveBy)
        assertNull(engine.snapshot.value.distanceMeters)
        assertFalse(engine.updateEvidence(evidence(engine, building.location, observed = elapsed - 31_000_000_000)))
    }

    @Test fun targetAccountAndStopRestartRejectOldGenerationIncludingAToBToA() {
        val engine = engine()
        val old = evidence(engine, building.location, verdict = true)
        engine.updateSessions(listOf(lecture.copy(room = "PAR-104")))
        engine.updateSessions(listOf(lecture))
        assertFalse(engine.updateEvidence(old))
        val beforeAccount = evidence(engine, building.location)
        engine.updateSessions(listOf(lecture), reset = true)
        assertFalse(engine.updateEvidence(beforeAccount))
        val beforeStop = evidence(engine, building.location)
        engine.stopSensing()
        assertFalse(engine.updateEvidence(beforeStop))
        advance(1); engine.startSensing()
        assertFalse(engine.updateEvidence(beforeStop))
        assertNull(engine.snapshot.value.estimate)
        assertTrue(engine.updateEvidence(evidence(engine)))
        assertFalse(engine.snapshot.value.state == ContextState.ARRIVED)
    }

    @Test fun sameBuildingCorrectionKeepsArrivalButRetimeOutsideWindowClearsDepartureEvidence() = runBlocking {
        val engine = engine()
        engine.updateEvidence(evidence(engine, building.location))
        engine.refresh()
        engine.updateSessions(listOf(lecture.copy(room = "PAR-160-201")))
        advance(10)
        engine.updateEvidence(evidence(engine, GeoPoint(-37.8005, 144.9608)))
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
        engine.updateSessions(listOf(lecture.copy(start = lecture.start.plusDays(1), end = lecture.end.plusDays(1))))
        engine.refresh()
        assertEquals(ContextState.NO_UPCOMING_CLASS, engine.snapshot.value.state)
    }

    @Test fun evidenceDuringDelayedEtaAndWeatherWithdrawsPromptAndUsesCurrentDistance() = runBlocking {
        val etaRelease = CompletableDeferred<Unit>()
        val weatherRelease = CompletableDeferred<Unit>()
        val etaStarted = CompletableDeferred<Unit>()
        val weatherStarted = CompletableDeferred<Unit>()
        val routes = object : EtaRepository {
            var calls = 0
            override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): Result<TravelEstimate> {
                if (++calls > 1) { etaStarted.complete(Unit); etaRelease.await() }
                return Result.success(estimate(mode))
            }
        }
        val weather = object : WeatherRepository {
            var calls = 0
            override suspend fun currentWeather(location: GeoPoint): Result<WeatherSnapshot> {
                if (++calls > 1) { weatherStarted.complete(Unit); weatherRelease.await() }
                return Result.success(WeatherSnapshot(16.0, 0.0, 0, now))
            }
        }
        val engine = engine(routes, weather)
        engine.updateEvidence(evidence(engine)); engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { engine.refresh() }
        etaStarted.await()
        advance(10)
        engine.updateEvidence(evidence(engine, closer, verdict = true))
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
        etaRelease.complete(Unit); weatherStarted.await()
        advance(10)
        engine.updateEvidence(evidence(engine, building.location))
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
        weatherRelease.complete(Unit); refresh.await()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
        assertEquals(GeoMath.distanceMeters(building.location, building.location), engine.snapshot.value.distanceMeters!!, 0.01)
        assertEquals(home, engine.snapshot.value.estimateOrigin)
    }
}
