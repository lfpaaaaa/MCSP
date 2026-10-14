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
import au.edu.unimelb.campuscompanion.sensing.activity.ActivityObservation
import au.edu.unimelb.campuscompanion.sensing.activity.UserActivityState
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrendDetector
import au.edu.unimelb.campuscompanion.sensing.location.EnRouteFusionDetector
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.MovementObservation
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.HomeCardUiState
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardGuidance
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Fixed-clock acceptance through production detectors, measured evidence, Home and reminders. */
class Step6JourneyAcceptanceTest {
    @Test
    fun walkingCompletesTheHomeAndReminderJourneyUsingFreshMovementSamples() = runBlocking<Unit> {
        assertCompleteJourney(JourneyMode.WALKING)
    }

    @Test
    fun runningCompletesTheHomeAndReminderJourneyWithoutWalkingSteps() = runBlocking<Unit> {
        assertCompleteJourney(JourneyMode.RUNNING)
    }

    @Test
    fun vehicleCompletesTheHomeAndReminderJourneyWithoutCompassOrRepeatedEnterEvents() = runBlocking<Unit> {
        assertCompleteJourney(JourneyMode.VEHICLE)
    }

    @Test
    fun turnBackAndSensingRestartKeepLeaveSoonDeduplicatedButAllowANewLateReminder() = runBlocking<Unit> {
        val trip = Journey(JourneyMode.VEHICLE)
        trip.showDeparturePrompt()
        trip.approachUntilConfirmed()
        trip.assertState(ContextState.EN_ROUTE, "En route")
        assertTrue(trip.shade.shown.isEmpty())
        val closest = trip.engine.snapshot.value.distanceMeters!!

        trip.advance(10)
        trip.accept(trip.observe(-120.0))
        assertTrue(trip.engine.snapshot.value.distanceMeters!! - closest >= 150.0)
        trip.assertState(ContextState.LEAVE_SOON, "Leave soon")
        assertTrue(trip.shade.shown.isEmpty())
        assertEquals(1, trip.shade.alerts.size)

        // Recreating the coordinator uses persisted delivery receipts. Raw sensing resets must
        // not make the already delivered and withdrawn departure warning eligible again.
        trip.engine.stopSensing()
        trip.advance(1)
        trip.engine.startSensing()
        trip.resetDetectors()
        trip.recreateReminderCoordinator()
        trip.accept(trip.observe(-120.0))
        trip.engine.refresh()
        trip.assertState(ContextState.LEAVE_SOON, "Leave soon")
        assertTrue(trip.shade.shown.isEmpty())
        assertEquals(1, trip.shade.alerts.size)

        // 15 minutes before class is still on time; the next second is newly RUNNING_LATE.
        trip.advanceTo(trip.lecture.start.toInstant().minusSeconds(15 * 60))
        trip.accept(trip.observe(-120.0))
        trip.engine.refresh()
        trip.assertState(ContextState.LEAVE_SOON, "Leave soon")
        assertTrue(trip.shade.shown.isEmpty())
        trip.advance(1)
        trip.accept(trip.observe(-120.0))
        trip.assertState(ContextState.RUNNING_LATE, "Running late")
        assertEquals(setOf(trip.key), trip.shade.shownKeys())
        assertEquals(listOf(ContextState.LEAVE_SOON, ContextState.RUNNING_LATE), trip.shade.alerts.map { it.state })
        assertEquals(trip.shade.alerts.first().notificationId, trip.shade.alerts.last().notificationId)

        trip.recreateReminderCoordinator()
        trip.assertState(ContextState.RUNNING_LATE, "Running late")
        assertEquals(2, trip.shade.alerts.size)
        trip.advance(10)
        trip.accept(trip.observe(1_200.0))
        trip.assertState(ContextState.ARRIVED, "Arrived")
        assertTrue(trip.shade.shown.isEmpty())
        assertEquals(listOf(trip.key, trip.key), trip.shade.withdrawn)
    }

    @Test
    fun measuredTravelAndArrivalUpdateHomeAndWithdrawPromptWhileEtaAndWeatherAreSuspended() = runBlocking<Unit> {
        val trip = Journey(JourneyMode.WALKING)
        trip.showDeparturePrompt()
        trip.delayNextEtaAndWeather()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { trip.engine.refresh() }
        try {
            withTimeout(2_000) { trip.etaStarted.await() }
            trip.approachUntilConfirmed()
            val travelling = trip.assertState(ContextState.EN_ROUTE, "En route")
            assertEquals("On your way", buildHomeCardGuidance(travelling, trip.now.atZone(ZONE)).headline)
            assertTrue(trip.shade.shown.isEmpty())
            assertEquals(listOf(trip.key), trip.shade.withdrawn)
            assertFalse(refresh.isCompleted)
            assertFalse(trip.etaRelease.isCompleted)

            trip.etaRelease.complete(Unit)
            withTimeout(2_000) { trip.weatherStarted.await() }
            trip.advance(10)
            trip.accept(trip.observe(1_200.0))
            trip.assertState(ContextState.ARRIVED, "Arrived")
            assertFalse(refresh.isCompleted)
            assertFalse(trip.weatherRelease.isCompleted)

            trip.weatherRelease.complete(Unit)
            withTimeout(2_000) { refresh.await() }
            val arrived = trip.assertState(ContextState.ARRIVED, "Arrived")
            assertEquals(0.0, arrived.distanceMeters!!, 0.01)
            assertEquals(trip.pointAfterProgress(0.0), trip.engine.snapshot.value.estimateOrigin)
            assertTrue(trip.shade.shown.isEmpty())
            assertEquals(1, trip.shade.alerts.size)
        } finally {
            trip.etaRelease.complete(Unit)
            trip.weatherRelease.complete(Unit)
            refresh.cancelAndJoin()
        }
    }

    private suspend fun assertCompleteJourney(mode: JourneyMode) {
        val trip = Journey(mode)
        val firstEvidence = trip.showDeparturePrompt()
        val deadline = trip.engine.snapshot.value.leaveBy
        trip.approachUntilConfirmed()
        val travelling = trip.assertState(ContextState.EN_ROUTE, "En route")
        assertEquals("On your way", buildHomeCardGuidance(travelling, trip.now.atZone(ZONE)).headline)
        assertEquals(listOf(trip.key), trip.shade.withdrawn)
        assertTrue(trip.shade.shown.isEmpty())

        // Temporary signal loss does not manufacture a new physical decision or undo a trip.
        trip.advance(180)
        trip.engine.refresh()
        val withoutSignals = trip.assertState(ContextState.EN_ROUTE, "En route")
        assertNull(withoutSignals.distanceMeters)
        assertNotNull(withoutSignals.estimate)
        assertEquals(deadline, withoutSignals.leaveBy)
        assertFalse(trip.engine.updateEvidence(firstEvidence))
        trip.assertState(ContextState.EN_ROUTE, "En route")

        trip.advance(10)
        trip.accept(trip.observe(1_200.0))
        val arrived = trip.assertState(ContextState.ARRIVED, "Arrived")
        assertEquals(0.0, arrived.distanceMeters!!, 0.01)
        assertFalse(buildHomeCardGuidance(arrived, trip.now.atZone(ZONE)).showTravelDetails)

        trip.advanceTo(trip.lecture.start.toInstant())
        trip.engine.refresh()
        val inClass = trip.assertState(ContextState.IN_CLASS, "In class")
        assertEquals(trip.lecture, inClass.session)
        assertTrue(buildHomeCardGuidance(inClass, trip.now.atZone(ZONE)).headline.startsWith("In class until "))

        trip.advanceTo(trip.lecture.end.toInstant())
        trip.engine.refresh()
        val postClass = trip.assertState(ContextState.POST_CLASS, "Post class")
        assertNull(postClass.session)
        assertEquals(trip.lecture, postClass.finishedSession)
        assertEquals("COMP90018 just finished", buildHomeCardGuidance(postClass, trip.now.atZone(ZONE)).headline)

        trip.advanceTo(trip.lecture.end.toInstant().plusSeconds(15 * 60 - 1))
        trip.engine.refresh()
        assertEquals(trip.lecture, trip.assertState(ContextState.POST_CLASS, "Post class").finishedSession)
        trip.advance(1)
        trip.engine.refresh()
        val expired = trip.assertState(ContextState.NO_UPCOMING_CLASS, "Not today")
        assertNull(expired.finishedSession)
        assertEquals("No more classes today", buildHomeCardGuidance(expired, trip.now.atZone(ZONE)).headline)
        assertTrue(trip.shade.shown.isEmpty())
        assertEquals(1, trip.shade.alerts.size)
        assertEquals(listOf(trip.key), trip.shade.withdrawn)
    }

    private enum class JourneyMode(val activity: UserActivityState?, val movement: Boolean?, val heading: Double?) {
        WALKING(null, true, 0.0),
        RUNNING(UserActivityState.RUNNING, null, 0.0),
        VEHICLE(UserActivityState.IN_VEHICLE, null, null)
    }

    private class Journey(private val mode: JourneyMode) {
        var now: Instant = Instant.parse("2026-10-13T00:00:00Z")
            private set
        private var elapsed = 100_000_000_000L
        private val building = FakeBuildingLookup.PETER_HALL
        private val trend = DistanceTrendDetector()
        private val fusion = EnRouteFusionDetector()
        private val preference = CourseReminderPreference(leadMinutes = 10)
        private val receipts = mutableSetOf<Triple<String, String, ContextState>>()
        private var delayNetwork = false
        private val activity: ActivityObservation?
        val etaStarted = CompletableDeferred<Unit>()
        val etaRelease = CompletableDeferred<Unit>()
        val weatherStarted = CompletableDeferred<Unit>()
        val weatherRelease = CompletableDeferred<Unit>()
        val lecture = CourseSession(
            id = "step6-${mode.name.lowercase()}-lecture", code = "COMP90018", title = "Mobile Computing lecture",
            location = "Parkville Campus", room = "PAR-160",
            start = now.plusSeconds(25 * 60).atZone(ZONE), end = now.plusSeconds(85 * 60).atZone(ZONE)
        )
        val key = lecture.departurePromptKey()
        val engine = TravelEngine(
            eta = object : EtaRepository {
                override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): Result<TravelEstimate> {
                    if (delayNetwork) {
                        etaStarted.complete(Unit)
                        etaRelease.await()
                    }
                    return Result.success(TravelEstimate(mode, 15 * 60L, 1_500, EstimateSource.Routing, now))
                }
            },
            buildings = FakeBuildingLookup(),
            preferences = { TravelPreferences(walkingThresholdMeters = 5_000) },
            weather = object : WeatherRepository {
                override suspend fun currentWeather(location: GeoPoint): Result<WeatherSnapshot> {
                    if (delayNetwork) {
                        weatherStarted.complete(Unit)
                        weatherRelease.await()
                    }
                    return Result.success(WeatherSnapshot(16.0, 0.0, 0, now))
                }
            },
            clock = { now },
            reminderPreference = { preference },
            elapsedNanos = { elapsed }
        )
        val shade = FakeDepartureShade()
        private var reminders = newReminderCoordinator()

        init {
            engine.updateSessions(listOf(lecture))
            engine.startSensing()
            elapsed += 1 // A retained ENTER observation must belong to this sensing period.
            activity = mode.activity?.let { ActivityObservation(it, elapsed) }
        }

        fun delayNextEtaAndWeather() { delayNetwork = true }

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
            elapsed += seconds * 1_000_000_000L
        }

        fun advanceTo(time: Instant) { advance(Duration.between(now, time).seconds) }

        fun resetDetectors() {
            trend.reset()
            fusion.reset()
        }

        fun recreateReminderCoordinator() { reminders = newReminderCoordinator() }

        private fun newReminderCoordinator() = DepartureReminders(
            snapshots = engine.snapshot,
            preferences = { preference },
            notify = shade::post,
            shade = shade,
            currentUserId = { "step6-student" },
            wasSent = { user, session, state -> Triple(user, session.departurePromptKey(), state) in receipts },
            rememberSent = { user, session, state -> receipts += Triple(user, session.departurePromptKey(), state) }
        )

        suspend fun showDeparturePrompt(): SensingEvidence {
            val evidence = observe(0.0)
            accept(evidence)
            engine.refresh()
            val home = assertState(ContextState.LEAVE_SOON, "Leave soon")
            assertEquals(lecture.start.minusMinutes(25), home.leaveBy)
            assertEquals(0L, home.departureCountdownMinutes)
            assertEquals(setOf(key), shade.shownKeys())
            assertEquals(listOf(ContextState.LEAVE_SOON), shade.alerts.map { it.state })
            return evidence
        }

        fun approachUntilConfirmed() {
            // Four-location trend, then three confirmations: one fresh bundle per observation.
            for (index in 1..5) {
                advance(10)
                val evidence = observe(index * 8.0)
                accept(evidence)
                assertState(
                    if (index == 5) ContextState.EN_ROUTE else ContextState.LEAVE_SOON,
                    if (index == 5) "En route" else "Leave soon"
                )
                if (index == 5) {
                    assertEquals(true, evidence.enRoute)
                    assertFalse(evidence.progressConfirmed)
                }
            }
        }

        fun assertState(expected: ContextState, status: String): HomeCardUiState {
            val snapshot = engine.snapshot.value
            assertEquals(expected, snapshot.state)
            val home = buildHomeCardState(listOf(lecture), snapshot, now.atZone(ZONE))
            assertFalse(home.isUpdating)
            assertEquals(expected, home.state)
            assertEquals(status, home.statusLabel)
            reminders.consider(snapshot)
            return home
        }

        fun accept(evidence: SensingEvidence) { assertTrue(engine.updateEvidence(evidence)) }

        fun pointAfterProgress(meters: Double) = GeoPoint(
            latitude = building.location.latitude - (1_200.0 - meters) / 111_000.0,
            longitude = building.location.longitude
        )

        fun observe(progressMeters: Double): SensingEvidence {
            val point = pointAfterProgress(progressMeters)
            val location = LocationSample(point.latitude, point.longitude, 10f, now.toEpochMilli(), elapsed)
            val distanceTrend = trend.update(GeoMath.distanceMeters(point, building.location), elapsed)
            val closing = trend.closingMeters()
            val verdict = fusion.update(
                isMoving = mode.movement,
                isRotating = mode.heading?.let { false },
                distanceTrend = distanceTrend,
                headingDifferenceDegrees = mode.heading,
                closingMeters = closing,
                observedAtNanos = elapsed,
                activity = activity?.state
            ).verdict
            return SensingEvidence(
                generation = engine.sensingTarget.value.generation,
                location = location,
                movement = mode.movement?.let { MovementObservation(it, elapsed) },
                enRoute = verdict,
                progressConfirmed = trend.sustainedApproach() && closing >= 50.0,
                activity = activity
            )
        }
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Australia/Melbourne")
    }
}
