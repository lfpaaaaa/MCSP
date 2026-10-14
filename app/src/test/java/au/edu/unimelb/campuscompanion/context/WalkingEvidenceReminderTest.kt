package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
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

/** Real walking policy, atomic engine inputs, and notification withdrawal in the same pipeline. */
class WalkingEvidenceReminderTest {
    @Test
    fun continuousWalkingAndHeadingWithdrawThePromptBeforeFiftyMeters() = runBlocking {
        assertWalkingWithdrawsPrompt(headingDifference = 0.0, samplesUntilConfirmed = 6)
    }

    @Test
    fun missingCompassUsesConfirmedFiftyMeterProgressToWithdrawThePrompt() = runBlocking {
        assertWalkingWithdrawsPrompt(headingDifference = null, samplesUntilConfirmed = 9)
    }

    private suspend fun assertWalkingWithdrawsPrompt(headingDifference: Double?, samplesUntilConfirmed: Int) {
        val trip = WalkingTrip(headingDifference)
        val initial = trip.observe(trip.pointAfterWalking(0.0))
        assertTrue(trip.engine.updateEvidence(initial))
        trip.engine.refresh()
        val warning = trip.engine.snapshot.value
        assertEquals(ContextState.LEAVE_SOON, warning.state)
        assertNotNull(trip.reminders.consider(warning))
        val promptKey = trip.lecture.departurePromptKey()
        assertEquals(setOf(promptKey), trip.shade.shownKeys())

        // Movement stays true throughout; each measured location and its sensor observations are new.
        var latest = initial
        for (index in 1 until samplesUntilConfirmed) {
            trip.advance(10)
            latest = trip.observe(trip.pointAfterWalking(index * 8.0))
            assertTrue(trip.engine.updateEvidence(latest))
            val snapshot = trip.engine.snapshot.value
            assertEquals(
                if (index == samplesUntilConfirmed - 1) ContextState.EN_ROUTE else ContextState.LEAVE_SOON,
                snapshot.state
            )
            assertNull(trip.reminders.consider(snapshot))
        }
        if (headingDifference != null) {
            assertEquals(true, latest.enRoute)
            assertFalse(latest.progressConfirmed) // Full fusion confirms before the conservative 50 m path.
        } else {
            assertNull(latest.enRoute)
            assertTrue(latest.progressConfirmed)
        }
        assertEquals(listOf(promptKey), trip.shade.withdrawn)
        assertTrue(trip.shade.shown.isEmpty())
        assertEquals(1, trip.shade.alerts.size)

        // A delayed/replayed far fix cannot replace the coherent current evidence or restore a prompt.
        assertFalse(trip.engine.updateEvidence(initial))
        trip.engine.refresh()
        assertEquals(ContextState.EN_ROUTE, trip.engine.snapshot.value.state)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        assertTrue(trip.shade.shown.isEmpty())
        assertEquals(1, trip.shade.alerts.size)

        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(trip.observe(FakeBuildingLookup.PETER_HALL.location)))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        assertTrue(trip.shade.shown.isEmpty())
    }

    private class WalkingTrip(private val headingDifference: Double?) {
        private var now = Instant.parse("2026-10-13T00:00:00Z")
        private var elapsed = 100_000_000_000L
        private val building = FakeBuildingLookup.PETER_HALL
        private val trend = DistanceTrendDetector()
        private val fusion = EnRouteFusionDetector()
        val lecture = CourseSession(
            id = "walking-lecture", code = "COMP90018", title = "Mobile Computing lecture",
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
        }

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
            elapsed += seconds * 1_000_000_000L
        }

        fun pointAfterWalking(meters: Double) = GeoPoint(
            latitude = building.location.latitude - (1_200.0 - meters) / 111_000.0,
            longitude = building.location.longitude
        )

        fun observe(point: GeoPoint): SensingEvidence {
            val location = LocationSample(point.latitude, point.longitude, 10f, now.toEpochMilli(), elapsed)
            val distanceTrend = trend.update(GeoMath.distanceMeters(point, building.location), elapsed)
            val closing = trend.closingMeters()
            val verdict = fusion.update(
                isMoving = true,
                isRotating = false,
                distanceTrend = distanceTrend,
                headingDifferenceDegrees = headingDifference,
                closingMeters = closing,
                observedAtNanos = location.elapsedRealtimeNanos
            ).verdict
            // Match the foreground service's measured bundle and conservative fallback gate.
            return SensingEvidence(
                generation = engine.sensingTarget.value.generation,
                location = location,
                movement = MovementObservation(true, elapsed),
                enRoute = verdict,
                progressConfirmed = trend.sustainedApproach() && closing >= 50.0
            )
        }
    }
}
