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
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Last-known late guidance is advisory; it cannot supply physical travel or arrival evidence. */
class TravelEngineLateGuidanceTest {
    @Test
    fun fortySecondGpsGapKeepsTheLatePromptAndFreshFixDoesNotNeedToRepostIt() = runBlocking {
        val trip = Trip()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.engine.refresh()
        assertEquals(ContextState.RUNNING_LATE, trip.engine.snapshot.value.state)
        assertNotNull(trip.reminders.consider(trip.engine.snapshot.value))
        val key = trip.shade.shownKeys().single()
        val leaveBy = trip.engine.snapshot.value.leaveBy

        trip.advance(40)
        trip.engine.refresh()
        val expired = trip.engine.snapshot.value
        assertEquals(ContextState.RUNNING_LATE, expired.state)
        assertNull(expired.distanceMeters)
        assertNotNull(expired.estimate)
        assertEquals(leaveBy, expired.leaveBy)
        assertNull(trip.reminders.consider(expired))
        assertEquals(setOf(key), trip.shade.shownKeys())
        assertTrue(trip.shade.withdrawn.isEmpty())

        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        assertEquals(ContextState.RUNNING_LATE, trip.engine.snapshot.value.state)
        assertNotNull(trip.engine.snapshot.value.distanceMeters)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        assertEquals(setOf(key), trip.shade.shownKeys())
        assertEquals(1, trip.shade.alerts.size)
        assertTrue(trip.shade.withdrawn.isEmpty())
    }

    @Test
    fun losingTheCurrentBundleDuringAModeOrActivityChangeKeepsSameTargetLateGuidance() = runBlocking {
        val trip = Trip()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.engine.refresh()
        trip.reminders.consider(trip.engine.snapshot.value)
        val shown = trip.shade.shownKeys()
        trip.advance(1)
        assertTrue(trip.engine.updateEvidence(SensingEvidence(trip.engine.sensingTarget.value.generation, location = null)))
        assertEquals(ContextState.RUNNING_LATE, trip.engine.snapshot.value.state)
        assertNull(trip.engine.snapshot.value.distanceMeters)
        trip.reminders.consider(trip.engine.snapshot.value)
        assertEquals(shown, trip.shade.shownKeys())
        assertTrue(trip.shade.withdrawn.isEmpty())
    }

    @Test
    fun unknownPositionFromTheBeginningRetainsTheScheduleDerivedInClassAssumption() = runBlocking {
        val trip = Trip()
        trip.engine.refresh()
        assertEquals(ContextState.IN_CLASS, trip.engine.snapshot.value.state)
        assertNull(trip.engine.snapshot.value.distanceMeters)
        assertNull(trip.engine.snapshot.value.estimate)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        assertTrue(trip.shade.shownKeys().isEmpty())
    }

    @Test
    fun lastKnownAwayDoesNotExtendRunningLatePastTheFifteenMinuteGrace() = runBlocking {
        val trip = Trip()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.engine.refresh()
        trip.reminders.consider(trip.engine.snapshot.value)
        val key = trip.shade.shownKeys().single()
        trip.advance(10 * 60)
        trip.engine.refresh()
        assertEquals(ContextState.IN_CLASS, trip.engine.snapshot.value.state)
        assertNull(trip.engine.snapshot.value.distanceMeters)
        trip.reminders.consider(trip.engine.snapshot.value)
        assertEquals(listOf(key), trip.shade.withdrawn)
        assertTrue(trip.shade.shownKeys().isEmpty())
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        assertEquals(ContextState.IN_CLASS, trip.engine.snapshot.value.state)
    }

    @Test
    fun stopAndRestartClearLastKnownAwayAndItsRoutingEstimate() = runBlocking {
        val trip = Trip()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.engine.refresh()
        val oldEvidence = trip.evidence()
        trip.engine.stopSensing()
        trip.advance(1)
        trip.engine.startSensing()
        assertFalse(trip.engine.updateEvidence(oldEvidence))
        trip.engine.refresh()
        assertEquals(ContextState.IN_CLASS, trip.engine.snapshot.value.state)
        assertNull(trip.engine.snapshot.value.distanceMeters)
        assertNull(trip.engine.snapshot.value.estimate)
    }

    @Test
    fun targetReplacementAndAccountResetClearThePreviousTargetsLateAdvisory() = runBlocking {
        val trip = Trip()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.engine.refresh()
        trip.engine.updateSessions(listOf(trip.lecture.copy(room = "PAR-104")))
        trip.engine.refresh()
        assertEquals(ContextState.IN_CLASS, trip.engine.snapshot.value.state)
        assertNull(trip.engine.snapshot.value.estimate)
        assertNull(trip.engine.snapshot.value.distanceMeters)

        trip.advance(1)
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.engine.refresh()
        assertEquals(ContextState.RUNNING_LATE, trip.engine.snapshot.value.state)
        trip.engine.updateSessions(listOf(trip.lecture.copy(room = "PAR-104")), reset = true)
        trip.engine.refresh()
        assertEquals(ContextState.IN_CLASS, trip.engine.snapshot.value.state)
        assertNull(trip.engine.snapshot.value.estimate)
        assertNull(trip.engine.snapshot.value.distanceMeters)
    }

    @Test
    fun expiredMovementAndPositiveFusionCannotUseTheAdvisoryPositionToEstablishTravel() = runBlocking {
        val trip = Trip()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.engine.refresh()
        trip.advance(40)
        val stalePositive = trip.evidence().copy(
            location = trip.evidence().location!!.copy(elapsedRealtimeNanos = trip.elapsed - 40_000_000_000L),
            movement = MovementObservation(true, trip.elapsed), enRoute = true, progressConfirmed = true
        )
        assertFalse(trip.engine.updateEvidence(stalePositive))
        trip.engine.refresh()
        assertEquals(ContextState.RUNNING_LATE, trip.engine.snapshot.value.state)
        assertNull(trip.engine.snapshot.value.distanceMeters)
    }

    private class Trip {
        var now = Instant.parse("2026-10-13T00:00:00Z")
        var elapsed = 100_000_000_000L
        private val home = GeoPoint(-37.8100, 144.9630)
        val lecture = CourseSession(
            id = "late-lecture", code = "COMP90018", title = "Lecture", location = "Parkville", room = "PAR-160",
            start = now.minusSeconds(5 * 60).atZone(ZoneId.of("Australia/Melbourne")),
            end = now.plusSeconds(55 * 60).atZone(ZoneId.of("Australia/Melbourne"))
        )
        val shade = FakeDepartureShade()
        val reminders = DepartureReminders(emptyFlow(), { CourseReminderPreference() }, shade::post, shade = shade)
        private val routes = object : EtaRepository {
            override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode) =
                Result.success(TravelEstimate(mode, 15 * 60L, 1500, EstimateSource.Routing, now))
        }
        val engine = TravelEngine(routes, FakeBuildingLookup(), { TravelPreferences() }, clock = { now }, elapsedNanos = { elapsed })
            .also { it.updateSessions(listOf(lecture)); it.startSensing() }

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
            elapsed += seconds * 1_000_000_000L
        }

        fun evidence() = SensingEvidence(
            engine.sensingTarget.value.generation,
            LocationSample(home.latitude, home.longitude, 10f, now.toEpochMilli(), elapsed),
            MovementObservation(false, elapsed)
        )
    }
}
