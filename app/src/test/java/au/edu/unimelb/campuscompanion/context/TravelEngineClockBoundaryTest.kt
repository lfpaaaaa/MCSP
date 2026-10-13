package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TravelEngineClockBoundaryTest {
    private val beforeBoundary = Instant.parse("2026-10-13T00:00:00Z")
    private val elapsed = 100_000_000_000L
    private val firstBuilding = FakeBuildingLookup.PETER_HALL
    private val secondBuilding = FakeBuildingLookup.ALAN_GILBERT
    private val first = session("first", "PAR-160", -600, 1)
    private val second = session("second", "PAR-104", 120, 3_720)

    @Test
    fun aBoundaryBetweenClockReadsCannotPublishTheNextClassWithThePreviousDestination() = runBlocking {
        val clock = BoundaryClock(beforeBoundary)
        val requestedDestinations = mutableListOf<GeoPoint>()
        val engine = engine(clock, requestedDestinations)
        val originalTarget = engine.sensingTarget.value
        // Target publication does not depend on ETA completion or a populated snapshot.
        assertTrue(engine.snapshot.value.pending)
        assertEquals(first.id, originalTarget.sessionId)
        assertEquals(firstBuilding, originalTarget.building)
        assertTrue(engine.updateEvidence(locationEvidence(engine)))

        // A second wall-clock read sees the first class end. Destination/session capture must use
        // one time; the later publication must invalidate that request when the class has changed.
        clock.advanceOnSecondRead()
        engine.refresh()

        assertEquals(listOf(firstBuilding.location), requestedDestinations)
        assertEquals(second.id, engine.sensingTarget.value.sessionId)
        assertEquals(secondBuilding, engine.sensingTarget.value.building)
        assertTrue(engine.sensingTarget.value.generation > originalTarget.generation)
        assertTrue(engine.snapshot.value.pending)
    }

    @Test
    fun anAtomicEvidenceUpdateUsesOneTimeForItsTargetSessionAndDestination() {
        val clock = BoundaryClock(beforeBoundary)
        val engine = engine(clock, mutableListOf())
        clock.advanceOnSecondRead()

        assertTrue(engine.updateEvidence(locationEvidence(engine)))

        val snapshot = engine.snapshot.value
        val target = engine.sensingTarget.value
        assertEquals(first.id, snapshot.session?.id)
        assertEquals(target.sessionId, snapshot.session?.id)
        assertEquals(target.building, snapshot.building)
    }

    @Test
    fun ticksAdvanceTheTargetWhileThePreviousClassesEtaIsStillSuspended() = runBlocking {
        var now = beforeBoundary
        val requested = CompletableDeferred<GeoPoint>()
        val release = CompletableDeferred<Unit>()
        val routes = object : EtaRepository {
            override suspend fun estimate(
                origin: GeoPoint,
                destination: GeoPoint,
                mode: TravelMode
            ): Result<TravelEstimate> {
                requested.complete(destination)
                release.await()
                return Result.success(TravelEstimate(
                    mode = mode,
                    durationSeconds = 300,
                    distanceMeters = 500,
                    source = EstimateSource.Routing,
                    computedAt = beforeBoundary
                ))
            }
        }
        val engine = TravelEngine(
            routes,
            FakeBuildingLookup(),
            { TravelPreferences() },
            clock = { now },
            tickInterval = Duration.ofMillis(10),
            elapsedNanos = { elapsed }
        )
        engine.updateSessions(listOf(first, second))
        val originalTarget = engine.startSensing()
        assertTrue(engine.updateEvidence(locationEvidence(engine)))
        val runner = launch { engine.run() }
        try {
            assertEquals(firstBuilding.location, withTimeout(2_000) { requested.await() })
            now = beforeBoundary.plusSeconds(2)
            // No GPS callback or ETA completion accompanies the class boundary.
            val nextTarget = withTimeout(2_000) {
                engine.sensingTarget.first { it.sessionId == second.id }
            }
            assertFalse(release.isCompleted)
            assertTrue(nextTarget.generation > originalTarget.generation)
            assertEquals(secondBuilding, nextTarget.building)
            assertTrue(engine.snapshot.value.pending)

            release.complete(Unit)
            val snapshot = withTimeout(2_000) {
                engine.snapshot.first { it.session?.id == second.id }
            }
            assertEquals(secondBuilding, snapshot.building)
            assertNull(snapshot.estimate)
            assertFalse(snapshot.state == ContextState.ARRIVED)
        } finally {
            release.complete(Unit)
            runner.cancelAndJoin()
        }
    }

    private fun engine(clock: BoundaryClock, requestedDestinations: MutableList<GeoPoint>): TravelEngine {
        val routes = object : EtaRepository {
            override suspend fun estimate(
                origin: GeoPoint,
                destination: GeoPoint,
                mode: TravelMode
            ): Result<TravelEstimate> {
                requestedDestinations.add(destination)
                return Result.success(TravelEstimate(
                    mode = mode,
                    durationSeconds = 300,
                    distanceMeters = 500,
                    source = EstimateSource.Routing,
                    computedAt = beforeBoundary
                ))
            }
        }
        return TravelEngine(
            routes,
            FakeBuildingLookup(),
            { TravelPreferences() },
            clock = clock::now,
            elapsedNanos = { elapsed }
        ).also {
            it.updateSessions(listOf(first, second))
            it.startSensing()
        }
    }

    private fun locationEvidence(engine: TravelEngine) = SensingEvidence(
        generation = engine.sensingTarget.value.generation,
        location = LocationSample(
            firstBuilding.location.latitude,
            firstBuilding.location.longitude,
            accuracyMeters = 5f,
            timestampMillis = beforeBoundary.toEpochMilli(),
            elapsedRealtimeNanos = elapsed
        )
    )

    private fun session(id: String, room: String, startSeconds: Long, endSeconds: Long) = CourseSession(
        id = id,
        code = "COMP90018",
        title = id,
        location = "Parkville",
        room = room,
        start = beforeBoundary.plusSeconds(startSeconds).atZone(ZoneId.of("Australia/Melbourne")),
        end = beforeBoundary.plusSeconds(endSeconds).atZone(ZoneId.of("Australia/Melbourne"))
    )

    private class BoundaryClock(private val initial: Instant) {
        private var armed = false
        private var reads = 0

        fun advanceOnSecondRead() {
            armed = true
            reads = 0
        }

        fun now(): Instant {
            if (!armed) return initial
            reads++
            return if (reads == 1) initial else initial.plusSeconds(2)
        }
    }
}
