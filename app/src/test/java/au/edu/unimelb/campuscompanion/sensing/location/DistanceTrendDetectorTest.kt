package au.edu.unimelb.campuscompanion.sensing.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceTrendDetectorTest {
    @Test
    fun fourDistinctObservationsAreRequired() {
        val detector = DistanceTrendDetector()
        assertEquals(DistanceTrend.UNKNOWN, detector.update(500.0, seconds(1)))
        assertEquals(DistanceTrend.UNKNOWN, detector.update(490.0, seconds(2)))
        assertEquals(DistanceTrend.UNKNOWN, detector.update(480.0, seconds(3)))
        repeat(10) { assertEquals(DistanceTrend.UNKNOWN, detector.update(470.0, seconds(3))) }
        assertFalse(detector.sustainedApproach())

        assertEquals(DistanceTrend.APPROACHING, detector.update(470.0, seconds(4)))
        assertFalse(detector.sustainedApproach())
        assertEquals(DistanceTrend.APPROACHING, detector.update(460.0, seconds(5)))
        assertFalse(detector.sustainedApproach())
        assertEquals(DistanceTrend.APPROACHING, detector.update(450.0, seconds(6)))
        assertTrue(detector.sustainedApproach())
    }

    @Test
    fun slowSustainedWalkingAccumulatesBeyondTheFourSampleWindow() {
        val detector = DistanceTrendDetector()
        repeat(12) { index -> detector.update(500.0 - index * 5, seconds(index.toLong() + 1)) }

        assertEquals(DistanceTrend.APPROACHING, detector.getCurrentTrend())
        assertEquals(50.0, detector.closingMeters(), 0.001)
        assertTrue(detector.sustainedApproach())
    }

    @Test
    fun singleJumpWithoutSustainedProgressIsNotApproaching() {
        val detector = DistanceTrendDetector()
        val observations = listOf(500.0, 500.0, 500.0, 400.0, 400.0, 400.0, 400.0)
        observations.forEachIndexed { index, distance ->
            detector.update(distance, seconds(index.toLong() + 1))
            assertFalse(detector.sustainedApproach())
            assertEquals(0.0, detector.closingMeters(), 0.001)
        }
    }

    @Test
    fun largeJumpDoesNotSupplyTheConservativeClosingThreshold() {
        val detector = DistanceTrendDetector()
        listOf(500.0, 490.0, 480.0, 380.0, 370.0, 360.0).forEachIndexed { index, distance ->
            detector.update(distance, seconds(index.toLong() + 1))
        }

        assertEquals(DistanceTrend.APPROACHING, detector.getCurrentTrend())
        assertEquals(40.0, detector.closingMeters(), 0.001)
    }

    @Test
    fun substantialReboundBreaksApproachAndClosingHistory() {
        val detector = DistanceTrendDetector()
        listOf(500.0, 480.0, 460.0, 440.0, 420.0, 400.0).forEachIndexed { index, distance ->
            detector.update(distance, seconds(index.toLong() + 1))
        }
        assertTrue(detector.sustainedApproach())

        assertEquals(DistanceTrend.STABLE, detector.update(480.0, seconds(7)))
        assertFalse(detector.sustainedApproach())
        assertEquals(0.0, detector.closingMeters(), 0.001)
    }

    @Test
    fun stationaryAndMovingAwayHaveDistinctTrends() {
        val detector = DistanceTrendDetector()
        listOf(500.0, 501.0, 500.0, 499.0).forEachIndexed { index, distance ->
            detector.update(distance, seconds(index.toLong() + 1))
        }
        assertEquals(DistanceTrend.STABLE, detector.getCurrentTrend())

        detector.reset()
        listOf(500.0, 510.0, 520.0, 530.0).forEachIndexed { index, distance ->
            detector.update(distance, seconds(index.toLong() + 1))
        }
        assertEquals(DistanceTrend.MOVING_AWAY, detector.getCurrentTrend())
        assertEquals(0.0, detector.closingMeters(), 0.001)
    }

    @Test
    fun serviceFallbackRequiresThreeApproachWindowsEvenAfterFiftyMeters() {
        val detector = DistanceTrendDetector()
        listOf(500.0, 470.0, 440.0, 410.0).forEachIndexed { index, distance ->
            detector.update(distance, seconds(index.toLong() + 1))
        }
        assertTrue(detector.closingMeters() >= 50.0)
        assertFalse(detector.sustainedApproach() && detector.closingMeters() >= 50.0)
        repeat(5) { detector.update(410.0, seconds(4)) }
        assertFalse(detector.sustainedApproach())
        detector.update(380.0, seconds(5))
        assertFalse(detector.sustainedApproach() && detector.closingMeters() >= 50.0)
        detector.update(350.0, seconds(6))
        assertTrue(detector.sustainedApproach() && detector.closingMeters() >= 50.0)
    }

    @Test
    fun gapOverThirtySecondsRestartsWarmup() {
        val detector = DistanceTrendDetector()
        listOf(500.0, 490.0, 480.0, 470.0).forEachIndexed { index, distance ->
            detector.update(distance, seconds(index.toLong() + 1))
        }

        assertEquals(DistanceTrend.UNKNOWN, detector.update(460.0, seconds(35)))
        assertFalse(detector.sustainedApproach())
        assertEquals(0.0, detector.closingMeters(), 0.001)
        assertEquals(DistanceTrend.UNKNOWN, detector.update(450.0, seconds(36)))
        assertEquals(DistanceTrend.UNKNOWN, detector.update(440.0, seconds(37)))
        assertEquals(DistanceTrend.APPROACHING, detector.update(430.0, seconds(38)))
    }

    @Test
    fun replayAndInvalidDistanceCannotAddProgress() {
        val detector = DistanceTrendDetector()
        listOf(500.0, 490.0, 480.0, 470.0).forEachIndexed { index, distance ->
            detector.update(distance, seconds(index.toLong() + 1))
        }
        val closing = detector.closingMeters()
        detector.update(0.0, seconds(3))
        detector.update(Double.NaN, seconds(5))
        detector.update(-1.0, seconds(6))
        assertEquals(closing, detector.closingMeters(), 0.001)
        assertEquals(DistanceTrend.APPROACHING, detector.getCurrentTrend())
    }

    private fun seconds(value: Long) = value * 1_000_000_000L
}
