package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceEntryEvent
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceRejectionReason
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** A retained routing position must retain its ordering timestamp across a sensing reset. */
class TravelEngineRetimeEvidenceTest {
    @Test
    fun sameIdRetimeRejectsOlderInsideTriggerAndCannotCreateDelayedArrival() = runBlocking {
        val trip = Trip()
        val inside = trip.sample(trip.building.location)
        trip.advance(10)
        trip.acceptAway()
        trip.advance(1)
        val generation = trip.engine.sensingTarget.value.generation

        trip.retimeTwoHoursLater()

        assertTrue(trip.engine.sensingTarget.value.generation > generation)
        trip.advance(1)
        val result = trip.engine.reportGeofenceEntered(trip.entry(inside))
        trip.engine.refresh()
        assertEquals(ContextState.UPCOMING, trip.engine.snapshot.value.state)
        trip.advance(120 * 60L)
        trip.engine.refresh()
        val later = trip.engine.snapshot.value
        assertEquals(trip.away, later.estimateOrigin)
        assertNull(later.distanceMeters)
        assertEquals(GeofenceRejectionReason.NEWER_AWAY_FIX, result.rejectionReason)
        assertFalse(result.accepted)
        assertNotEquals(ContextState.ARRIVED, later.state)
    }

    @Test
    fun sameInstantDisplayZoneChangeKeepsGenerationAndRejectsOlderInsideTrigger() = runBlocking {
        val trip = Trip(startMinutes = 145)
        val inside = trip.sample(trip.building.location)
        trip.advance(10)
        trip.acceptAway()
        trip.advance(1)
        val generation = trip.engine.sensingTarget.value.generation
        val sydney = ZoneId.of("Australia/Sydney")
        val changedZone = trip.lecture.copy(
            start = trip.lecture.start.withZoneSameInstant(sydney),
            end = trip.lecture.end.withZoneSameInstant(sydney)
        )
        assertEquals(trip.lecture.start.toInstant(), changedZone.start.toInstant())

        trip.engine.updateSessions(listOf(changedZone))

        val nextGeneration = trip.engine.sensingTarget.value.generation
        trip.advance(1)
        val result = trip.engine.reportGeofenceEntered(trip.entry(inside))
        trip.advance(120 * 60L)
        trip.engine.refresh()
        assertEquals(generation, nextGeneration)
        assertEquals(GeofenceRejectionReason.NEWER_AWAY_FIX, result.rejectionReason)
        assertNotEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun withoutRetimeOlderInsideTriggerIsRejectedEvenAfterAwayGpsExpires() = runBlocking {
        val trip = Trip()
        val inside = trip.sample(trip.building.location)
        trip.advance(10)
        trip.acceptAway()
        trip.advance(40)
        trip.engine.refresh()
        assertNull(trip.engine.snapshot.value.distanceMeters)

        val result = trip.engine.reportGeofenceEntered(trip.entry(inside))

        assertEquals(GeofenceRejectionReason.NEWER_AWAY_FIX, result.rejectionReason)
        assertFalse(result.accepted)
        assertNotEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun retimeStillAllowsAGenuinelyNewerInsideTrigger() = runBlocking {
        val trip = Trip()
        trip.advance(10)
        trip.acceptAway()
        trip.advance(1)
        trip.retimeTwoHoursLater()
        trip.advance(1)

        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(trip.sample(trip.building.location))).accepted)

        trip.advance(120 * 60L)
        trip.engine.refresh()
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun sameBuildingRetimePreservesPhysicalArrivalWhenGpsExpires() = runBlocking {
        val trip = Trip()
        trip.accept(trip.building.location)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        trip.advance(1)
        // Indoor drift outside the entry radius stays inside the arrival hysteresis radius.
        val indoorDrift = GeoPoint(-37.8005, 144.9608)
        trip.accept(indoorDrift)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)

        trip.retimeTwoHoursLater()
        trip.advance(120 * 60L)
        trip.engine.refresh()

        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        assertEquals(indoorDrift, trip.engine.snapshot.value.estimateOrigin)
        assertNull(trip.engine.snapshot.value.distanceMeters)
    }

    @Test
    fun differentOccurrenceRejectsOldRegistrationAndClearsGuidance() = runBlocking {
        val trip = Trip()
        trip.advance(10)
        trip.acceptAway()
        val oldEntry = trip.entry(trip.sample(trip.building.location))
        trip.advance(1)

        trip.engine.updateSessions(listOf(trip.lecture.copy(
            id = "different-occurrence", start = trip.lecture.start.plusHours(2), end = trip.lecture.end.plusHours(2)
        )))

        trip.advance(1)
        val result = trip.engine.reportGeofenceEntered(oldEntry.copy(receivedAtNanos = trip.elapsed))
        assertEquals(GeofenceRejectionReason.WRONG_GENERATION, result.rejectionReason)
        trip.engine.refresh()
        assertNull(trip.engine.snapshot.value.estimateOrigin)
        assertNotEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun sensingRestartClearsPositionAndOrderingTimestampTogether() = runBlocking {
        val trip = Trip()
        val inside = trip.sample(trip.building.location)
        trip.advance(10)
        trip.acceptAway()
        val generation = trip.engine.sensingTarget.value.generation
        trip.advance(1)

        trip.engine.stopSensing()
        trip.engine.startSensing()
        trip.engine.refresh()

        assertTrue(trip.engine.sensingTarget.value.generation > generation)
        assertNull(trip.engine.snapshot.value.estimateOrigin)
        assertNull(trip.engine.snapshot.value.distanceMeters)
        // With the old guidance discarded, the new registration can admit an initial trigger
        // measured before activation under the usual three-minute geofence freshness limit.
        val result = trip.engine.reportGeofenceEntered(trip.entry(inside))
        assertTrue(result.accepted)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun sensingRestartClearsConfirmedPhysicalArrival() = runBlocking {
        val trip = Trip()
        trip.accept(trip.building.location)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        trip.advance(1)

        trip.engine.stopSensing()
        trip.engine.startSensing()
        trip.engine.refresh()

        assertNull(trip.engine.snapshot.value.estimateOrigin)
        assertNotEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    private class Trip(startMinutes: Long = 25) {
        var now = Instant.parse("2026-10-14T00:00:00Z")
        var elapsed = 100_000_000_000L
        val building = FakeBuildingLookup.PETER_HALL
        val away = GeoPoint(-37.8100, 144.9630)
        val lecture = CourseSession(
            id = "retimed-lecture", code = "COMP90018", title = "Lecture", location = "Parkville", room = "PAR-160",
            start = now.plusSeconds(startMinutes * 60).atZone(ZoneId.of("Australia/Melbourne")),
            end = now.plusSeconds((startMinutes + 60) * 60).atZone(ZoneId.of("Australia/Melbourne"))
        )
        val engine = TravelEngine(
            object : EtaRepository {
                override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode) =
                    Result.success(TravelEstimate(mode, 15 * 60L, 1500, EstimateSource.Routing, now))
            }, FakeBuildingLookup(), { TravelPreferences() }, clock = { now }, elapsedNanos = { elapsed }
        ).also { it.updateSessions(listOf(lecture)); it.startSensing() }

        fun advance(seconds: Long) { now = now.plusSeconds(seconds); elapsed += seconds * 1_000_000_000L }
        fun sample(point: GeoPoint) = LocationSample(point.latitude, point.longitude, 10f, now.toEpochMilli(), elapsed)
        suspend fun accept(point: GeoPoint) {
            assertTrue(engine.updateEvidence(SensingEvidence(engine.sensingTarget.value.generation, sample(point))))
            engine.refresh()
        }
        suspend fun acceptAway() {
            accept(away)
            assertEquals(away, engine.snapshot.value.estimateOrigin)
            assertTrue(requireNotNull(engine.snapshot.value.distanceMeters) > 1000)
        }
        fun retimeTwoHoursLater() = engine.updateSessions(listOf(lecture.copy(
            start = lecture.start.plusHours(2), end = lecture.end.plusHours(2)
        )))
        fun entry(trigger: LocationSample) = GeofenceEntryEvent(
            "current-successful-registration", engine.sensingTarget.value.generation, building.locCode, elapsed, trigger
        )
    }
}
