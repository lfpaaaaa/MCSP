package au.edu.unimelb.campuscompanion.sensing.service

import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.FakeBuildingLookup
import au.edu.unimelb.campuscompanion.context.TravelEngine
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SensingAccountLifecycleTest {
    private val now = Instant.parse("2026-10-13T00:00:00Z")
    private var elapsed = 100_000_000_000L
    private val building = FakeBuildingLookup.PETER_HALL
    private val home = GeoPoint(-37.8100, 144.9630)
    private val lecture = CourseSession(
        id = "lecture", code = "COMP90018", title = "Lecture", location = "Parkville",
        room = "PAR-160", start = now.plusSeconds(25 * 60).atZone(ZoneId.of("Australia/Melbourne")),
        end = now.plusSeconds(85 * 60).atZone(ZoneId.of("Australia/Melbourne"))
    )
    private val engine = TravelEngine(
        eta = object : EtaRepository {
            override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode) =
                Result.success(TravelEstimate(mode, 15 * 60L, 1500, EstimateSource.Routing, now))
        },
        buildings = FakeBuildingLookup(), preferences = { TravelPreferences() },
        clock = { now }, elapsedNanos = { elapsed }
    )
    private val accounts = SensingAccountLifecycle(engine)

    private fun evidence(point: GeoPoint = home) = SensingEvidence(
        generation = engine.sensingTarget.value.generation,
        location = LocationSample(point.latitude, point.longitude, 10f, now.toEpochMilli(), elapsed)
    )

    private fun begin(userId: String = "A", sessions: List<CourseSession> = listOf(lecture)) {
        accounts.replaceAccount(userId, sessions)
        engine.startSensing()
    }

    @Test
    fun unresolvedAccountIsNotEligibleAndCannotPublishATimetableOrStartARun() {
        assertFalse(accounts.ready.value)
        assertFalse(accounts.eligible.value)
        assertNull(accounts.currentAccountId)
        assertFalse(accounts.updateTimetable("A", listOf(lecture)))
        assertTrue(accounts.currentSessions.isEmpty())
        assertFalse(engine.sensingTarget.value.active)
        assertNull(engine.sensingSession.value)
    }

    @Test
    fun resolvedAccountLoadsTimetableBeforeBecomingEligibleWithoutLaunchingSensing() {
        accounts.replaceAccount("A", listOf(lecture))
        assertTrue(accounts.ready.value)
        assertTrue(accounts.eligible.value)
        assertEquals("A", accounts.currentAccountId)
        assertEquals(listOf(lecture), accounts.currentSessions)
        assertEquals(lecture, engine.sensingSession.value)
        assertFalse(engine.sensingTarget.value.active)
        val target = engine.startSensing()
        assertTrue(target.active)
        assertEquals(lecture.id, target.sessionId)
        assertEquals(building, target.building)
    }

    @Test
    fun initialSignedOutResolutionIsReadyButIneligibleWithNoTimetable() {
        accounts.replaceAccount(null, listOf(lecture))
        assertTrue(accounts.ready.value)
        assertFalse(accounts.eligible.value)
        assertTrue(accounts.currentSessions.isEmpty())
        assertNull(engine.sensingSession.value)
        assertFalse(engine.sensingTarget.value.active)
        assertFalse(accounts.updateTimetable("A", listOf(lecture)))
    }

    @Test
    fun sameAccountResolutionAndIdenticalTimetableUpdatesPreserveArrivalAndGeneration() = runBlocking {
        begin()
        assertTrue(engine.updateEvidence(evidence(building.location)))
        engine.refresh()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
        val target = engine.sensingTarget.value
        val snapshot = engine.snapshot.value
        repeat(3) {
            accounts.replaceAccount("A", listOf(lecture))
            assertTrue(accounts.updateTimetable("A", listOf(lecture.copy())))
            assertEquals(target, engine.sensingTarget.value)
            assertEquals(snapshot, engine.snapshot.value)
        }
        // A temporary auth initialization causes no replacement; the established account/run
        // remains intact until the adapter receives an actual signed-out or replacement account.
        assertTrue(accounts.ready.value)
        assertTrue(accounts.eligible.value)
    }

    @Test
    fun accountAToBToAWithIdenticalClassesRejectsEveryPreviousEvidenceScope() = runBlocking {
        begin()
        assertTrue(engine.updateEvidence(evidence(building.location)))
        engine.refresh()
        assertEquals(ContextState.ARRIVED, engine.snapshot.value.state)
        val firstA = evidence(building.location)
        val firstGeneration = firstA.generation

        elapsed += 1_000_000_000L
        accounts.replaceAccount("B", listOf(lecture))
        assertTrue(engine.sensingTarget.value.active)
        assertTrue(engine.sensingTarget.value.generation > firstGeneration)
        assertFalse(engine.updateEvidence(firstA))
        assertNull(engine.snapshot.value.estimate)
        assertTrue(engine.updateEvidence(evidence(home)))
        engine.refresh()
        assertFalse(engine.snapshot.value.state == ContextState.ARRIVED)
        val fromB = evidence(building.location)

        elapsed += 1_000_000_000L
        accounts.replaceAccount("A", listOf(lecture))
        assertTrue(engine.sensingTarget.value.generation > fromB.generation)
        assertFalse(engine.updateEvidence(firstA))
        assertFalse(engine.updateEvidence(fromB))
        assertTrue(engine.updateEvidence(evidence(home)))
        engine.refresh()
        assertFalse(engine.snapshot.value.state == ContextState.ARRIVED)
        assertEquals("A", accounts.currentAccountId)
    }

    @Test
    fun signOutStopsRunAndClearsGuidanceEvidenceAndTimetableImmediately() = runBlocking {
        begin()
        val oldEvidence = evidence(home)
        assertTrue(engine.updateEvidence(oldEvidence))
        engine.refresh()
        assertTrue(engine.snapshot.value.estimate != null)
        assertTrue(engine.snapshot.value.leaveBy != null)
        accounts.replaceAccount(null, emptyList())
        assertTrue(accounts.ready.value)
        assertFalse(accounts.eligible.value)
        assertNull(accounts.currentAccountId)
        assertTrue(accounts.currentSessions.isEmpty())
        assertFalse(engine.sensingTarget.value.active)
        assertNull(engine.sensingSession.value)
        assertFalse(engine.updateEvidence(oldEvidence))
        assertNull(engine.snapshot.value.estimate)
        assertNull(engine.snapshot.value.leaveBy)
        assertNull(engine.snapshot.value.distanceMeters)
        assertNull(engine.snapshot.value.estimateOrigin)
        assertFalse(accounts.updateTimetable("A", listOf(lecture)))
        engine.refresh()
        assertEquals(ContextState.NO_UPCOMING_CLASS, engine.snapshot.value.state)
    }

    @Test
    fun disposedAccountsTimetableCannotReplaceTheCurrentAccountsDestination() {
        begin()
        val tutorial = lecture.copy(id = "tutorial", room = "PAR-104")
        accounts.replaceAccount("B", listOf(tutorial))
        val currentTarget = engine.sensingTarget.value
        assertFalse(accounts.updateTimetable("A", listOf(lecture)))
        assertEquals(listOf(tutorial), accounts.currentSessions)
        assertEquals(tutorial, engine.sensingSession.value)
        assertEquals(currentTarget, engine.sensingTarget.value)
        assertEquals(FakeBuildingLookup.ALAN_GILBERT, currentTarget.building)
        assertTrue(accounts.updateTimetable("B", listOf(tutorial.copy(title = "Edited tutorial"))))
        assertEquals("Edited tutorial", engine.sensingSession.value?.title)
    }

    @Test
    fun repeatedSignedOutResolutionDoesNotAdvanceAlreadyStoppedGeneration() {
        begin()
        accounts.replaceAccount(null, emptyList())
        val stopped = engine.sensingTarget.value
        repeat(3) { accounts.replaceAccount(null, emptyList()) }
        assertEquals(stopped, engine.sensingTarget.value)
        assertFalse(accounts.eligible.value)
    }
}
