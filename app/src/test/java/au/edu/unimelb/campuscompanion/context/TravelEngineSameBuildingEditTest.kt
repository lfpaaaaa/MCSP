package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.english

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Arrival is entered within 75 m of the building and kept until 200 m, because indoor GPS is often
 * that far off. A student sitting in the building must therefore keep their arrival through edits
 * that move neither them nor the building; otherwise the engine reports them as running late or
 * due to leave, and the reminder history (keyed by class ID and start) lets that alert through.
 * A trip under way is kept while the new departure window is open and ends when it is not.
 */
class TravelEngineSameBuildingEditTest {
    @Test
    fun aRoomCorrectionDuringClassKeepsInClassAndSendsNoReminder() = runBlocking<Unit> {
        assertArrivalSurvives(now = FIVE_MINUTES_IN, edited = lecture.copy(room = "PAR-160-201"),
            expected = ContextState.IN_CLASS)
    }

    @Test
    fun anEndTimeExtensionDuringClassKeepsInClassAndSendsNoReminder() = runBlocking<Unit> {
        assertArrivalSurvives(now = FIVE_MINUTES_IN, edited = lecture.copy(end = lecture.end.plusMinutes(10)),
            expected = ContextState.IN_CLASS)
    }

    @Test
    fun aRoomCorrectionBeforeClassKeepsArrivedAndSendsNoReminder() = runBlocking<Unit> {
        assertArrivalSurvives(now = FIVE_MINUTES_BEFORE, edited = lecture.copy(room = "PAR-160-201"),
            expected = ContextState.ARRIVED)
    }

    @Test
    fun aLaterStartInTheSameBuildingKeepsArrivedAndSendsNoReminder() = runBlocking<Unit> {
        val moved = lecture.copy(start = lecture.start.plusMinutes(10), end = lecture.end.plusMinutes(10))
        assertArrivalSurvives(now = FIVE_MINUTES_BEFORE, edited = moved, expected = ContextState.ARRIVED)
    }

    @Test
    fun undoingAVenueEditBeforeTheEngineRefreshesKeepsArrivalAndSendsNoReminder() = runBlocking<Unit> {
        val engine = engine(FIVE_MINUTES_BEFORE)
        arriveThenDrift(engine)
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)

        // The engine never tracked the other building, so the student's evidence is unchanged.
        engine.updateSessions(listOf(lecture.copy(room = "PAR-104-G01")))
        engine.updateSessions(listOf(lecture))
        engine.refresh()

        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
        assertNull(reminders().consider(engine.snapshot.value))
    }

    @Test
    fun aClassMovedTenMinutesLaterWhileWalkingStaysEnRouteAndSendsNoReminder() = runBlocking<Unit> {
        val engine = engine(WALKING_AT_11_47, etaMinutes = 10)
        walkTowardsTheBuilding(engine)

        val later = lecture.copy(start = lecture.start.plusMinutes(10), end = lecture.end.plusMinutes(10))
        engine.updateSessions(listOf(later))
        engine.updateOrigin(STILL_WALKING)
        engine.refresh()

        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
        assertNull(reminders().consider(engine.snapshot.value))
    }

    @Test
    fun aClassMovedToTomorrowWhileWalkingEndsTheTripAndSendsNoReminder() = runBlocking<Unit> {
        val engine = engine(WALKING_AT_11_47, etaMinutes = 10)
        walkTowardsTheBuilding(engine)

        val tomorrow = lecture.copy(start = lecture.start.plusDays(1), end = lecture.end.plusDays(1))
        engine.updateSessions(listOf(tomorrow))
        engine.updateOrigin(STILL_WALKING)
        engine.refresh()

        assertEquals(ContextState.NO_UPCOMING_CLASS, engine.snapshot.value.state)
        assertNull(reminders().consider(engine.snapshot.value))
    }

    @Test
    fun aMoveToAnotherBuildingStillStartsAFreshTripFromTheOldOne() = runBlocking<Unit> {
        val engine = engine(FIVE_MINUTES_BEFORE)
        arriveThenDrift(engine)
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)

        engine.updateSessions(listOf(lecture.copy(room = "PAR-104-G01")))
        engine.refresh()

        val after = engine.snapshot.value
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, after.building)
        assertEquals(ContextState.LEAVE_SOON, after.state)
        val reminder = reminders().consider(after)
        assertTrue(reminder?.text?.english().orEmpty().contains(FakeBuildingLookup.ALAN_GILBERT.name))
        assertFalse(reminder?.text?.english().orEmpty().contains(FakeBuildingLookup.PETER_HALL.name))
    }

    private suspend fun assertArrivalSurvives(now: Instant, edited: CourseSession, expected: ContextState) {
        val engine = engine(now)
        arriveThenDrift(engine)
        assertEquals(expected, engine.snapshot.value.state)

        engine.updateSessions(listOf(edited))
        engine.refresh()

        val after = engine.snapshot.value
        assertEquals(edited, after.session)
        assertEquals(FakeBuildingLookup.PETER_HALL, after.building)
        assertEquals(expected, after.state)
        val reminder = reminders().consider(after)
        assertNull("unexpected reminder: ${reminder?.title}", reminder)
    }

    /** Arrives at the entrance, then indoor GPS reads ~120 m from the building centroid. */
    private suspend fun arriveThenDrift(engine: TravelEngine) {
        engine.updateSessions(listOf(lecture))
        engine.updateOrigin(FakeBuildingLookup.PETER_HALL.location)
        engine.refresh()
        engine.updateOrigin(INDOOR_DRIFT)
        engine.refresh()
    }

    /** Sets off ~900 m from Peter Hall and walks ~70 m closer, so the engine reports EN_ROUTE. */
    private suspend fun walkTowardsTheBuilding(engine: TravelEngine) {
        engine.updateSessions(listOf(lecture))
        engine.updateMoving(true)
        engine.updateOrigin(GeoPoint(-37.8075, 144.9608))
        engine.refresh()
        engine.updateOrigin(GeoPoint(-37.80685, 144.9608))
        engine.refresh()
        assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
    }

    private fun engine(now: Instant, etaMinutes: Int = 3) = TravelEngine(
        eta = FixedWalk(etaMinutes),
        buildings = FakeBuildingLookup(),
        preferences = { TravelPreferences() },
        clock = { now },
        tickInterval = Duration.ofHours(1)
    )

    private fun reminders() = DepartureReminders(
        snapshots = emptyFlow(),
        preferences = { CourseReminderPreference() },
        notify = { true }
    )

    private class FixedWalk(private val minutes: Int) : EtaRepository {
        override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode) =
            Result.success(TravelEstimate(mode, minutes * 60L, 200, EstimateSource.Routing, Instant.EPOCH))
    }

    private companion object {
        val MELBOURNE: ZoneId = ZoneId.of("Australia/Melbourne")
        val lecture = CourseSession(
            id = "lecture",
            code = "COMP90018",
            title = "Mobile Computing Lecture",
            location = "Parkville Campus",
            room = "PAR-160-B117",
            start = Instant.parse("2026-10-05T01:00:00Z").atZone(MELBOURNE),
            end = Instant.parse("2026-10-05T02:00:00Z").atZone(MELBOURNE),
            activity = "Lecture"
        )
        val FIVE_MINUTES_BEFORE: Instant = Instant.parse("2026-10-05T00:55:00Z")
        val FIVE_MINUTES_IN: Instant = Instant.parse("2026-10-05T01:05:00Z")
        /** 11:47, with a 10-minute walk the student is due to leave (leave by 11:40). */
        val WALKING_AT_11_47: Instant = Instant.parse("2026-10-05T00:47:00Z")
        val INDOOR_DRIFT = GeoPoint(-37.8005, 144.9608)
        val STILL_WALKING = GeoPoint(-37.8066, 144.9608) // ~800 m from Peter Hall
    }
}
