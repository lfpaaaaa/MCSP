package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrend
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class DestinationProgressTrackerTest {
    private val tracker = DestinationProgressTracker()
    private val start = Instant.parse("2026-10-12T00:00:00Z")

    @Test fun stationaryGpsJitterIsNotMistakenForTravel() {
        assertEquals(DistanceTrend.STABLE, track(1200.0, 1204.0, 1196.0, 1202.0))
    }
    @Test fun consecutivePositionsTowardTheBuildingAreApproaching() {
        assertEquals(DistanceTrend.APPROACHING, track(1200.0, 1170.0, 1140.0, 1110.0))
    }
    @Test fun movementAwayFromTheBuildingIsNotEnRoute() {
        assertEquals(DistanceTrend.MOVING_AWAY, track(1200.0, 1230.0, 1260.0, 1290.0))
    }
    @Test fun aSinglePositionOrRepeatedTimestampCannotProveTheUserStopped() {
        repeat(4) { index ->
            assertEquals(DistanceTrend.UNKNOWN, tracker.observe(1200.0, 5f, start, start.plusSeconds(index * 10L)))
        }
    }
    @Test fun shortBurstsWaitForEnoughObservationTime() {
        repeat(4) { index ->
            val time = start.plusSeconds(index.toLong())
            assertEquals(DistanceTrend.UNKNOWN, tracker.observe(1200.0, 5f, time, time))
        }
    }
    @Test fun lowAccuracyLocationsDoNotEstablishADirection() {
        assertEquals(DistanceTrend.STABLE, track(1200.0, 1200.0, 1200.0, 1200.0))
        val time = start.plusSeconds(40)
        assertEquals(DistanceTrend.UNKNOWN, tracker.observe(1100.0, 100f, time, time))
        assertEquals(DistanceTrend.UNKNOWN, tracker.observe(1100.0, 5f, time.plusSeconds(10), time.plusSeconds(10)))
    }
    @Test fun staleAndFuturePositionsClearEarlierEvidence() {
        track(1200.0, 1170.0, 1140.0, 1110.0)
        assertEquals(DistanceTrend.UNKNOWN, tracker.observe(1110.0, 5f, start.plusSeconds(30), start.plusSeconds(121)))
        assertEquals(DistanceTrend.UNKNOWN, tracker.observe(1110.0, 5f, start.plusSeconds(140), start.plusSeconds(130)))
    }
    @Test fun aGapInTrackingDoesNotLookLikeOneContinuousTrip() {
        track(1200.0, 1170.0, 1140.0, 1110.0)
        val time = start.plusSeconds(300)
        assertEquals(DistanceTrend.UNKNOWN, tracker.observe(900.0, 5f, time, time))
    }
    @Test fun aNewDestinationStartsWithNoMovementHistory() {
        track(1200.0, 1170.0, 1140.0, 1110.0)
        tracker.reset()
        val time = start.plusSeconds(40)
        assertEquals(DistanceTrend.UNKNOWN, tracker.observe(500.0, 5f, time, time))
    }
    @Test fun stoppingEventuallyReplacesTheEarlierApproachingTrend() {
        track(1200.0, 1170.0, 1140.0, 1110.0)
        var result = DistanceTrend.UNKNOWN
        for (seconds in 40L..150L step 10) {
            val time = start.plusSeconds(seconds)
            result = tracker.observe(1110.0, 5f, time, time)
        }
        assertEquals(DistanceTrend.STABLE, result)
    }
    @Test fun locationAccuracyIsIncludedInTheProgressThreshold() {
        var result = DistanceTrend.UNKNOWN
        listOf(1200.0, 1180.0, 1160.0, 1140.0).forEachIndexed { index, distance ->
            val time = start.plusSeconds(index * 10L)
            result = tracker.observe(distance, 40f, time, time)
        }
        assertEquals(DistanceTrend.UNKNOWN, result)
    }
    @Test fun batterySavingMinuteUpdatesCanStillEstablishTheTrend() {
        var result = DistanceTrend.UNKNOWN
        repeat(4) { index ->
            val time = start.plusSeconds(index * 60L)
            result = tracker.observe(1200.0, 5f, time, time)
        }
        assertEquals(DistanceTrend.STABLE, result)
    }
    @Test fun outOfOrderFixCannotOverwriteTheLatestProgress() {
        track(1200.0, 1170.0, 1140.0, 1110.0)
        assertEquals(DistanceTrend.APPROACHING, tracker.observe(1500.0, 5f, start.plusSeconds(10), start.plusSeconds(30)))
    }

    @Test fun oneGpsJumpDoesNotEstablishTravelAwayFromTheClass() {
        assertEquals(DistanceTrend.UNKNOWN, track(1200.0, 1200.0, 1200.0, 1500.0))
    }

    @Test fun slowConsistentProgressIsNotMistakenForStandingStill() {
        assertEquals(DistanceTrend.UNKNOWN, track(1200.0, 1192.0, 1184.0, 1176.0))
        tracker.reset()
        assertEquals(DistanceTrend.UNKNOWN, track(1200.0, 1197.0, 1194.0, 1191.0))
    }

    private fun track(vararg distances: Double): DistanceTrend {
        var result = DistanceTrend.UNKNOWN
        distances.forEachIndexed { index, distance ->
            val time = start.plusSeconds(index * 10L)
            result = tracker.observe(distance, 5f, time, time)
        }
        return result
    }
}
