package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.sensing.activity.ActivityObservation
import au.edu.unimelb.campuscompanion.sensing.activity.UserActivityState
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrendDetector
import au.edu.unimelb.campuscompanion.sensing.location.EnRouteFusionDetector
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.MovementObservation
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Real activity/progress fusion through the engine and the existing reminder coordinator. */
class ActivityEvidenceReminderTest {
    @Test
    fun runningAndOnFootWithReliableHeadingWithdrawBeforeFiftyMetersWithoutWalkingSteps() = runBlocking {
        for (activity in listOf(UserActivityState.RUNNING, UserActivityState.ON_FOOT)) {
            assertProgressWithdraws(activity, headingDifference = 0.0, moving = false, sampleCount = 6)
        }
    }

    @Test
    fun runningAndOnFootWithoutCompassUseConservativeProgressAndRetainedActivity() = runBlocking {
        for (activity in listOf(UserActivityState.RUNNING, UserActivityState.ON_FOOT)) {
            assertProgressWithdraws(activity, headingDifference = null, moving = null, sampleCount = 9)
        }
    }

    @Test
    fun pocketHeadingAndPhoneRotationUseThePedestrianFallback() = runBlocking {
        assertProgressWithdraws(UserActivityState.RUNNING, headingDifference = 180.0, moving = null, sampleCount = 9)
        assertProgressWithdraws(UserActivityState.ON_FOOT, headingDifference = 0.0, moving = null,
            sampleCount = 9, rotating = true)
    }

    @Test
    fun vehicleWithNoAccelerometerOrCompassWithdrawsOnlyAfterConfirmedProgress() = runBlocking {
        assertProgressWithdraws(UserActivityState.IN_VEHICLE, headingDifference = null,
            moving = null, sampleCount = 6)
    }

    @Test
    fun activityAloneCannotWithdrawAShownPrompt() = runBlocking {
        val trip = ActivityTrip(UserActivityState.IN_VEHICLE, headingDifference = null)
        trip.showPrompt()
        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(SensingEvidence(
            generation = trip.engine.sensingTarget.value.generation,
            location = null,
            activity = trip.activity
        )))
        trip.engine.refresh()
        trip.assertPromptStillShown()
        assertNull(trip.engine.snapshot.value.distanceMeters)
    }

    @Test
    fun stationaryAwayJitterAndSingleOutlierTracesCannotWithdrawThePrompt() = runBlocking {
        val traces = listOf(
            List(9) { 0.0 },
            List(9) { -(it + 1) * 8.0 },
            listOf(1.0, -1.0, 2.0, -2.0, 1.0, 0.0, -1.0, 2.0, 0.0),
            listOf(80.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        )
        for (progress in traces) {
            val trip = ActivityTrip(UserActivityState.IN_VEHICLE, headingDifference = null)
            trip.showPrompt()
            for (meters in progress) {
                trip.advance(10)
                assertTrue(trip.engine.updateEvidence(trip.observe(meters)))
                trip.assertPromptStillShown()
            }
        }
    }

    @Test
    fun stillOverridesFreshAccelerometerMovementWhenStartingTravel() = runBlocking {
        val trip = ActivityTrip(UserActivityState.STILL, headingDifference = 0.0, moving = true)
        trip.showPrompt()
        for (index in 1..9) {
            trip.advance(10)
            assertTrue(trip.engine.updateEvidence(trip.observe(index * 8.0)))
            trip.assertPromptStillShown()
        }
    }

    @Test
    fun stillCannotAuthorizeAPositiveVerdictEvenWithFreshWalkingSteps() = runBlocking {
        val trip = ActivityTrip(UserActivityState.STILL, headingDifference = 0.0, moving = true)
        trip.showPrompt()
        trip.advance(10)
        val conflicting = trip.observe(80.0).copy(enRoute = true, progressConfirmed = true)
        assertTrue(trip.engine.updateEvidence(conflicting))
        trip.assertPromptStillShown()
    }

    @Test
    fun unknownOrUnavailableActivityUsesFreshAccelerometerProgress() = runBlocking {
        for (activity in listOf(UserActivityState.UNKNOWN, null)) {
            assertProgressWithdraws(activity, headingDifference = null, moving = true, sampleCount = 9)
        }
    }

    @Test
    fun unknownActivityAndExpiredAccelerometerCannotWithdrawThePrompt() = runBlocking {
        val trip = ActivityTrip(UserActivityState.UNKNOWN, headingDifference = null, moving = true)
        trip.showPrompt()
        for (index in 1..9) {
            trip.advance(10)
            val observation = trip.observe(index * 8.0).copy(
                movement = MovementObservation(true, trip.elapsed - 6_000_000_000L)
            )
            assertTrue(trip.engine.updateEvidence(observation))
            trip.assertPromptStillShown()
        }
    }

    @Test
    fun authoritativeFalseBlocksActivityFallbackButUnavailableFusionPermitsIt() = runBlocking {
        val trip = ActivityTrip(UserActivityState.RUNNING, headingDifference = null)
        trip.showPrompt()
        for (index in 1..9) {
            trip.advance(10)
            val observation = trip.observe(index * 8.0).copy(enRoute = false)
            assertTrue(trip.engine.updateEvidence(observation))
            trip.assertPromptStillShown()
        }
        trip.advance(10)
        val observation = trip.observe(80.0).copy(enRoute = null)
        assertTrue(observation.progressConfirmed)
        assertTrue(trip.engine.updateEvidence(observation))
        trip.assertPromptWithdrawn()
    }

    @Test
    fun briefStillStopRetainsTravelAndAtLeast150MeterRetreatEndsItWithoutRepeatingPrompt() = runBlocking {
        val trip = ActivityTrip(UserActivityState.IN_VEHICLE, headingDifference = null)
        trip.showPrompt()
        for (index in 1..5) {
            trip.advance(10)
            assertTrue(trip.engine.updateEvidence(trip.observe(index * 8.0)))
        }
        trip.assertPromptWithdrawn()
        val closest = trip.engine.snapshot.value.distanceMeters!!
        trip.activity = ActivityObservation(UserActivityState.STILL, trip.elapsed)
        for (index in 1..4) {
            trip.advance(10)
            assertTrue(trip.engine.updateEvidence(trip.observe(40.0)))
            trip.assertPromptWithdrawn()
        }
        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(trip.observe(-120.0)))
        assertTrue(trip.engine.snapshot.value.distanceMeters!! - closest >= 150.0)
        assertEquals(ContextState.LEAVE_SOON, trip.engine.snapshot.value.state)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        assertTrue(trip.shade.shown.isEmpty())
        assertEquals(1, trip.shade.alerts.size)
    }

    @Test
    fun retainedVehicleTransitionSurvivesSixtySecondsButExpiredGpsCannotStartTravel() = runBlocking {
        val trip = ActivityTrip(UserActivityState.IN_VEHICLE, headingDifference = null)
        val initial = trip.showPrompt()
        trip.advance(70)
        trip.engine.refresh()
        trip.assertPromptStillShown()
        assertNull(trip.engine.snapshot.value.distanceMeters)
        assertFalse(trip.engine.updateEvidence(initial.copy(enRoute = true, progressConfirmed = true)))

        // No further ENTER event: the activity remains current, but GPS history must restart.
        for (index in 1..6) {
            trip.advance(10)
            assertTrue(trip.engine.updateEvidence(trip.observe(index * 8.0)))
            if (index < 6) trip.assertPromptStillShown() else trip.assertPromptWithdrawn()
        }
        assertTrue(trip.elapsed - trip.activity!!.observedAtNanos > 60_000_000_000L)
    }

    @Test
    fun activityFromBeforeActivationAtActivationOrInTheFutureCannotAuthorizeTravel() = runBlocking {
        for (invalidTime in listOf<(ActivityTrip) -> Long>(
            { it.engine.sensingTarget.value.activatedAtNanos - 1 },
            { it.engine.sensingTarget.value.activatedAtNanos },
            { it.elapsed + 1 }
        )) {
            val trip = ActivityTrip(null, headingDifference = 0.0)
            trip.showPrompt()
            for (index in 1..6) {
                trip.advance(10)
                val invalid = ActivityObservation(UserActivityState.RUNNING, invalidTime(trip))
                // The engine independently guards the activity bundled with a detector verdict.
                assertTrue(trip.engine.updateEvidence(trip.observe(index * 8.0, activity = invalid)))
                trip.assertPromptStillShown()
            }
        }
    }

    @Test
    fun stopAccountResetAndAToBToARejectOldGenerationAndOldActivity() = runBlocking {
        for (reset in listOf<(ActivityTrip) -> Unit>(
            { it.engine.stopSensing(); it.engine.startSensing() },
            { it.engine.updateSessions(listOf(it.lecture), reset = true) },
            {
                it.engine.updateSessions(listOf(it.lecture.copy(room = "PAR-104")))
                it.engine.updateSessions(listOf(it.lecture))
            }
        )) {
            val trip = ActivityTrip(UserActivityState.RUNNING, headingDifference = 0.0)
            val old = trip.showPrompt()
            trip.advance(1)
            reset(trip)
            trip.resetDetectors()
            val current = trip.observe(0.0)
            assertFalse(trip.engine.updateEvidence(current.copy(generation = old.generation,
                enRoute = true, progressConfirmed = true)))
            assertTrue(trip.engine.updateEvidence(current))
            trip.engine.refresh()
            trip.assertPromptStillShown()
            for (index in 1..6) {
                trip.advance(10)
                assertTrue(trip.engine.updateEvidence(trip.observe(index * 8.0)))
                trip.assertPromptStillShown()
            }
        }
    }

    private suspend fun assertProgressWithdraws(
        activity: UserActivityState?, headingDifference: Double?, moving: Boolean?, sampleCount: Int,
        rotating: Boolean? = if (headingDifference == null) null else false
    ) {
        val trip = ActivityTrip(activity, headingDifference, moving, rotating)
        val initial = trip.showPrompt()
        var latest = initial
        for (index in 1 until sampleCount) {
            trip.advance(10)
            latest = trip.observe(index * 8.0)
            assertTrue(trip.engine.updateEvidence(latest))
            if (index < sampleCount - 1) trip.assertPromptStillShown() else trip.assertPromptWithdrawn()
        }
        if (sampleCount == 6) {
            assertEquals(true, latest.enRoute)
            assertFalse(latest.progressConfirmed)
        } else {
            assertNull(latest.enRoute)
            assertTrue(latest.progressConfirmed)
        }
        assertFalse(trip.engine.updateEvidence(initial))
        trip.engine.refresh()
        trip.assertPromptWithdrawn()
        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(trip.observe(1_200.0)))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        assertTrue(trip.shade.shown.isEmpty())
        assertEquals(1, trip.shade.alerts.size)
    }

    private class ActivityTrip(
        activityState: UserActivityState?,
        private val headingDifference: Double?,
        private val moving: Boolean? = null,
        private val rotating: Boolean? = if (headingDifference == null) null else false
    ) {
        private var now = Instant.parse("2026-10-13T00:00:00Z")
        var elapsed = 100_000_000_000L
            private set
        private val building = FakeBuildingLookup.PETER_HALL
        private val trend = DistanceTrendDetector()
        private val fusion = EnRouteFusionDetector()
        var activity: ActivityObservation? = null
        val lecture = CourseSession(
            id = "activity-lecture", code = "COMP90018", title = "Mobile Computing lecture",
            location = "Parkville Campus", room = "PAR-160",
            start = now.plusSeconds(25 * 60).atZone(ZoneId.of("Australia/Melbourne")),
            end = now.plusSeconds(85 * 60).atZone(ZoneId.of("Australia/Melbourne"))
        )
        val engine = TravelEngine(
            eta = object : EtaRepository {
                override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode) =
                    Result.success(TravelEstimate(mode, 15 * 60L, 1_500, EstimateSource.Routing, now))
            },
            buildings = FakeBuildingLookup(),
            preferences = { TravelPreferences(walkingThresholdMeters = 5_000) },
            clock = { now },
            elapsedNanos = { elapsed }
        )
        val shade = FakeDepartureShade()
        val reminders = DepartureReminders(
            snapshots = engine.snapshot,
            preferences = { CourseReminderPreference(leadMinutes = 10) },
            notify = shade::post,
            shade = shade
        )

        init {
            engine.updateLeadMinutes(10)
            engine.updateSessions(listOf(lecture))
            engine.startSensing()
            elapsed += 1 // Raw activity must be observed strictly after this registration started.
            activity = activityState?.let { ActivityObservation(it, elapsed) }
        }

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
            elapsed += seconds * 1_000_000_000L
        }

        fun resetDetectors() {
            trend.reset()
            fusion.reset()
        }

        suspend fun showPrompt(): SensingEvidence {
            val initial = observe(0.0)
            assertTrue(engine.updateEvidence(initial))
            engine.refresh()
            assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
            assertNotNull(reminders.consider(engine.snapshot.value))
            assertEquals(setOf(lecture.departurePromptKey()), shade.shownKeys())
            return initial
        }

        fun assertPromptStillShown() {
            assertEquals(ContextState.LEAVE_SOON, engine.snapshot.value.state)
            assertNull(reminders.consider(engine.snapshot.value))
            assertEquals(setOf(lecture.departurePromptKey()), shade.shownKeys())
            assertTrue(shade.withdrawn.isEmpty())
            assertEquals(1, shade.alerts.size)
        }

        fun assertPromptWithdrawn() {
            assertEquals(ContextState.EN_ROUTE, engine.snapshot.value.state)
            assertNull(reminders.consider(engine.snapshot.value))
            assertTrue(shade.shown.isEmpty())
            assertEquals(listOf(lecture.departurePromptKey()), shade.withdrawn)
            assertEquals(1, shade.alerts.size)
        }

        fun observe(progressMeters: Double, activity: ActivityObservation? = this.activity): SensingEvidence {
            val point = GeoPoint(
                latitude = building.location.latitude - (1_200.0 - progressMeters) / 111_000.0,
                longitude = building.location.longitude
            )
            val location = LocationSample(point.latitude, point.longitude, 10f, now.toEpochMilli(), elapsed)
            val distanceTrend = trend.update(GeoMath.distanceMeters(point, building.location), elapsed)
            val closing = trend.closingMeters()
            val verdict = fusion.update(
                isMoving = moving,
                isRotating = rotating,
                distanceTrend = distanceTrend,
                headingDifferenceDegrees = headingDifference,
                closingMeters = closing,
                observedAtNanos = elapsed,
                activity = activity?.state
            ).verdict
            return SensingEvidence(
                generation = engine.sensingTarget.value.generation,
                location = location,
                movement = moving?.let { MovementObservation(it, elapsed) },
                enRoute = verdict,
                progressConfirmed = trend.sustainedApproach() && closing >= 50.0,
                activity = activity
            )
        }
    }
}
