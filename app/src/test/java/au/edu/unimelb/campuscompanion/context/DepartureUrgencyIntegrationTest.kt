package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.english
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
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderTiming
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardGuidance
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardState
import au.edu.unimelb.campuscompanion.ui.model.reminderTiming
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Default 10/5 policy through real freshness admission, GPS history, Home and notification delivery. */
class DepartureUrgencyIntegrationTest {
    @Test fun preparationAndGpsUrgencyShareTheLiveDeadlineAcrossAllSurfaces() = runBlocking {
        val trip = Trip()
        trip.prepare()
        val prepared = trip.snapshot
        val timing = trip.session.reminderTiming(prepared, 0) as CourseReminderTiming.Ready
        assertEquals(trip.session.start.minusMinutes(20), timing.leaveBy)
        assertEquals(trip.initialNow, timing.warningAt.toInstant())
        assertEquals(trip.initialNow.plusSeconds(300), timing.urgentAt.toInstant())
        assertEquals(listOf(ContextState.LEAVE_SOON), trip.shade.alerts.map { it.state })

        // Four positions spanning 30 seconds, still one second before the urgent boundary.
        trip.trace(listOf(1200.0, 1202.0, 1198.0, 1200.0), firstSecond = 269)
        assertEquals(ContextState.LEAVE_SOON, trip.snapshot.state)
        assertNull(trip.reminders.consider(trip.snapshot))
        trip.advance(1)
        trip.engine.refresh()
        assertEquals(ContextState.LEAVE_NOW, trip.snapshot.state)
        val urgent = trip.reminders.consider(trip.snapshot)!!
        assertEquals(ContextState.LEAVE_NOW, urgent.state)
        assertEquals(trip.shade.alerts.first().notificationId, urgent.notificationId)
        assertTrue(urgent.text.english().contains("not getting closer"))

        val home = buildHomeCardState(listOf(trip.session), trip.snapshot, trip.now.atZone(ZONE))
        assertEquals(ContextState.LEAVE_NOW, home.state)
        assertEquals("Leave now", home.statusLabel.english())
        assertEquals(timing.leaveBy, home.leaveBy)
        val guidance = buildHomeCardGuidance(home, trip.now.atZone(ZONE))
        assertEquals("Leave now", guidance.headline.english())
        assertTrue(guidance.detail!!.english().contains("not getting closer"))
        assertNotNull(guidance.departureLine)
        assertTrue(guidance.showTravelDetails)
    }

    @Test fun aSingleFixOrTimerReplaysCannotProveNoProgress() = runBlocking {
        val trip = Trip()
        trip.prepare()
        trip.advance(300)
        trip.fix()
        repeat(4) {
            trip.advance(5)
            trip.engine.refresh()
            assertEquals(ContextState.LEAVE_SOON, trip.snapshot.state)
            assertNull(trip.reminders.consider(trip.snapshot))
        }
        assertEquals(1, trip.shade.alerts.size)
    }

    @Test fun bothClearAndSlowProgressAvoidTheUrgentPrompt() = runBlocking {
        for (distances in listOf(
            listOf(1200.0, 1170.0, 1140.0, 1110.0),
            listOf(1200.0, 1192.0, 1184.0, 1176.0)
        )) {
            val trip = Trip()
            trip.prepare()
            trip.trace(distances)
            assertEquals(ContextState.LEAVE_SOON, trip.snapshot.state)
            assertNull(trip.reminders.consider(trip.snapshot))
            assertEquals(1, trip.shade.alerts.size)
        }
    }

    @Test fun sustainedTravelAwayFromTheBuildingTriggersTheUrgentPrompt() = runBlocking {
        val trip = Trip()
        trip.prepare()
        trip.trace(listOf(1200.0, 1230.0, 1260.0, 1290.0))
        assertEquals(ContextState.LEAVE_NOW, trip.snapshot.state)
        assertEquals(ContextState.LEAVE_NOW, trip.reminders.consider(trip.snapshot)?.state)
    }

    @Test fun staleGpsWithdrawsUrgencyAndRequiresNewHistory() = runBlocking {
        val trip = Trip()
        trip.prepare()
        trip.trace()
        assertNotNull(trip.reminders.consider(trip.snapshot))
        trip.advance(31)
        trip.engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, trip.snapshot.state)
        assertNull(trip.reminders.consider(trip.snapshot))
        assertTrue(trip.shade.shown.isEmpty())
        trip.fix()
        assertEquals(ContextState.LEAVE_SOON, trip.snapshot.state)
        trip.trace(firstSecond = 340)
        assertEquals(ContextState.LEAVE_NOW, trip.snapshot.state)
        assertNull(trip.reminders.consider(trip.snapshot))
        assertTrue(trip.shade.shown.isEmpty())
        assertEquals(2, trip.shade.alerts.size)
    }

    @Test fun aRejectedInaccurateFixCannotCompleteAnUrgentTrend() = runBlocking {
        val trip = Trip()
        trip.prepare()
        trip.trace(listOf(1200.0, 1200.0, 1200.0))
        trip.advance(10)
        assertFalse(trip.engine.updateEvidence(trip.evidence(accuracy = 100f)))
        trip.engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, trip.snapshot.state)
        assertNull(trip.reminders.consider(trip.snapshot))
    }

    @Test fun targetAccountAndSensingResetsCannotReuseStationaryHistory() = runBlocking {
        val resets = listOf<(Trip) -> Unit>(
            { it.engine.stopSensing(); it.engine.startSensing() },
            { it.engine.updateSessions(listOf(it.session), reset = true) },
            { it.session = it.session.copy(room = "PAR-104"); it.engine.updateSessions(listOf(it.session)) },
            { it.session = it.session.copy(start = it.session.start.minusSeconds(1)); it.engine.updateSessions(listOf(it.session)) }
        )
        for (reset in resets) {
            val trip = Trip()
            trip.prepare()
            trip.trace()
            assertEquals(ContextState.LEAVE_NOW, trip.snapshot.state)
            reset(trip)
            trip.advance(1)
            trip.fix()
            trip.engine.refresh()
            assertEquals(ContextState.LEAVE_SOON, trip.snapshot.state)
        }
    }

    @Test fun arrivalAndEstablishedTravelSuppressGpsUrgency() = runBlocking {
        val arrived = Trip()
        arrived.prepare()
        arrived.trace(List(4) { 0.0 })
        assertEquals(ContextState.ARRIVED, arrived.snapshot.state)
        assertNull(arrived.reminders.consider(arrived.snapshot))
        assertTrue(arrived.shade.shown.isEmpty())

        val travelling = Trip()
        travelling.prepare()
        travelling.advance(270)
        // Physical travel was already established from coherent progress and movement.
        val evidence = travelling.evidence().copy(
            enRoute = true,
            movement = MovementObservation(true, travelling.elapsed)
        )
        assertTrue(travelling.engine.updateEvidence(evidence))
        travelling.trace(firstSecond = 280) // Brief stop retains the established trip.
        assertEquals(ContextState.EN_ROUTE, travelling.snapshot.state)
        assertNull(travelling.reminders.consider(travelling.snapshot))
        assertTrue(travelling.shade.shown.isEmpty())
    }

    @Test fun urgentAndActualLatenessShareOneEscalationEvenAfterRestart() = runBlocking {
        val trip = Trip()
        trip.prepare()
        trip.trace()
        assertEquals(ContextState.LEAVE_NOW, trip.reminders.consider(trip.snapshot)?.state)
        trip.reminders = trip.coordinator()
        assertNull(trip.reminders.consider(trip.snapshot))
        trip.advance(301) // Now beyond the physical deadline: the same alert becomes Running late.
        trip.engine.refresh()
        assertEquals(ContextState.RUNNING_LATE, trip.snapshot.state)
        assertNull(trip.reminders.consider(trip.snapshot))
        assertEquals(2, trip.shade.alerts.size)
        assertEquals(ContextState.RUNNING_LATE, trip.shade.updates.last().state)
        trip.shade.dismiss(trip.session.departurePromptKey())
        trip.reminders = trip.coordinator()
        assertNull(trip.reminders.consider(trip.snapshot))
        assertTrue(trip.shade.shown.isEmpty())
    }

    @Test fun deniedUrgentDeliveryIsRetriedAndDisabledRemindersStaySilent() = runBlocking {
        val trip = Trip()
        trip.prepare()
        trip.trace()
        trip.shade.canNotify = false
        assertNull(trip.reminders.consider(trip.snapshot))
        trip.reminders = trip.coordinator()
        trip.shade.canNotify = true
        assertEquals(ContextState.LEAVE_NOW, trip.reminders.consider(trip.snapshot)?.state)
        assertEquals(2, trip.shade.alerts.size)
        trip.preference = trip.preference.copy(enabled = false)
        assertNull(trip.reminders.consider(trip.snapshot))
        assertTrue(trip.shade.shown.isEmpty())
    }

    @Test fun fiveMinuteGpsCheckIsIndependentOfAnExplicitZeroPreparationChoice() = runBlocking {
        val trip = Trip()
        trip.preference = CourseReminderPreference(preparationMinutes = 0)
        trip.fix()
        trip.engine.refresh()
        assertEquals(ContextState.UPCOMING, trip.snapshot.state)
        assertNull(trip.reminders.consider(trip.snapshot))
        trip.trace()
        assertEquals(ContextState.LEAVE_NOW, trip.reminders.consider(trip.snapshot)?.state)
        trip.advance(300)
        trip.engine.refresh()
        assertNull(trip.reminders.consider(trip.snapshot))
        assertEquals(1, trip.shade.alerts.size)
    }

    private class Trip {
        val initialNow: Instant = Instant.parse("2026-10-14T00:00:00Z")
        var now = initialNow
        var elapsed = 100_000_000_000L
        var preference = CourseReminderPreference()
        var session = CourseSession(
            id = "ten-five", code = "COMP90018", title = "Mobile computing", location = "Parkville",
            room = "PAR-160", start = now.plusSeconds(1800).atZone(ZONE),
            end = now.plusSeconds(5400).atZone(ZONE)
        )
        val engine = TravelEngine(
            eta = object : EtaRepository {
                override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode) =
                    Result.success(TravelEstimate(mode, 1200, 1500, EstimateSource.Routing, now))
            },
            buildings = FakeBuildingLookup(), preferences = { TravelPreferences() },
            reminderPreference = { preference }, clock = { now }, elapsedNanos = { elapsed }
        )
        val shade = FakeDepartureShade()
        private val history = mutableSetOf<Triple<String, String, ContextState>>()
        var reminders = coordinator()
        val snapshot get() = engine.snapshot.value

        init {
            engine.updateSessions(listOf(session))
            engine.startSensing()
            elapsed++
        }

        fun coordinator() = DepartureReminders(
            snapshots = engine.snapshot, preferences = { preference }, notify = shade::post, shade = shade,
            currentUserId = { "test-student" },
            wasSent = { user, session, state -> Triple(user, session.departurePromptKey(), state) in history },
            rememberSent = { user, session, state -> history += Triple(user, session.departurePromptKey(), state) }
        )

        suspend fun prepare() {
            fix()
            engine.refresh()
            assertEquals(ContextState.LEAVE_SOON, reminders.consider(snapshot)?.state)
        }

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
            elapsed += seconds * 1_000_000_000L
        }

        fun evidence(distance: Double = 1200.0, accuracy: Float = 5f): SensingEvidence {
            val point = FakeBuildingLookup.PETER_HALL.location
            return SensingEvidence(
                generation = engine.sensingTarget.value.generation,
                location = LocationSample(point.latitude - distance / 111_000.0, point.longitude,
                    accuracy, now.toEpochMilli(), elapsed)
            )
        }

        fun fix(distance: Double = 1200.0) { assertTrue(engine.updateEvidence(evidence(distance))) }

        fun trace(distances: List<Double> = List(4) { 1200.0 }, firstSecond: Long = 270) {
            advance(firstSecond - java.time.Duration.between(initialNow, now).seconds)
            distances.forEachIndexed { index, distance ->
                if (index > 0) advance(10)
                fix(distance)
            }
        }
    }

    private companion object { val ZONE: ZoneId = ZoneId.of("Australia/Melbourne") }
}
