package au.edu.unimelb.campuscompanion.sensing.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnRouteFusionDetectorTest {
    @Test
    fun alignedHeadingConfirmsEarlierThanConservativeProgress() {
        val detector = EnRouteFusionDetector()
        assertNull(update(detector, 1, closing = 8.0).verdict)
        assertNull(update(detector, 2, closing = 12.0).verdict)
        assertEquals(true, update(detector, 3, closing = 16.0).verdict)
        assertTrue(detector.isEnRoute())
    }

    @Test
    fun missingAndMismatchedCompassRemainUnavailableBeforeFiftyMeters() {
        listOf(null, 90.0, Double.NaN, -10.0).forEach { heading ->
            val detector = EnRouteFusionDetector()
            repeat(5) { index ->
                assertNull(update(detector, index.toLong() + 1, heading = heading, closing = 40.0).verdict)
            }
            assertFalse(detector.isEnRoute())
        }
    }

    @Test
    fun missingOrPocketHeadingCanConfirmAfterSustainedProgress() {
        listOf(null, 120.0).forEach { heading ->
            val detector = EnRouteFusionDetector()
            assertNull(update(detector, 1, heading = heading, closing = 49.9).verdict)
            assertNull(update(detector, 2, heading = heading, closing = 50.0).verdict)
            assertNull(update(detector, 3, heading = heading, closing = 55.0).verdict)
            assertEquals(true, update(detector, 4, heading = heading, closing = 60.0).verdict)
        }
    }

    @Test
    fun rotationAndUnavailableGyroscopeRequireConservativeProgress() {
        listOf(true, null).forEach { rotation ->
            val detector = EnRouteFusionDetector()
            repeat(3) { index ->
                assertNull(update(detector, index.toLong() + 1, rotating = rotation, closing = 10.0).verdict)
            }
            assertNull(update(detector, 4, rotating = rotation, closing = 50.0).verdict)
            assertNull(update(detector, 5, rotating = rotation, closing = 55.0).verdict)
            assertEquals(true, update(detector, 6, rotating = rotation, closing = 60.0).verdict)
        }
    }

    @Test
    fun headingBoundaryIsInclusive() {
        val detector = EnRouteFusionDetector()
        update(detector, 1, heading = 60.0)
        update(detector, 2, heading = 60.0)
        assertEquals(true, update(detector, 3, heading = 60.0).verdict)
    }

    @Test
    fun stationaryAndMovingAwayNeedThreeNegativeObservations() {
        val detector = EnRouteFusionDetector()
        assertNull(update(detector, 1, moving = false, trend = DistanceTrend.STABLE).verdict)
        assertNull(update(detector, 2, moving = false, trend = DistanceTrend.STABLE).verdict)
        assertEquals(false, update(detector, 3, moving = false, trend = DistanceTrend.STABLE).verdict)

        detector.reset()
        assertNull(update(detector, 1, trend = DistanceTrend.MOVING_AWAY).verdict)
        assertNull(update(detector, 2, trend = DistanceTrend.MOVING_AWAY).verdict)
        assertEquals(false, update(detector, 3, trend = DistanceTrend.MOVING_AWAY).verdict)
    }

    @Test
    fun warmupAndUnavailableMovementAreNotNegative() {
        val detector = EnRouteFusionDetector()
        repeat(5) { index ->
            assertNull(update(detector, index.toLong() + 1, moving = false, trend = DistanceTrend.UNKNOWN).verdict)
        }
        repeat(5) { index ->
            assertNull(update(detector, index.toLong() + 6, moving = null, closing = 60.0).verdict)
        }
    }

    @Test
    fun duplicateAndOutOfOrderObservationsDoNotAdvanceConfirmations() {
        val detector = EnRouteFusionDetector()
        update(detector, 1)
        repeat(5) { assertNull(update(detector, 1).verdict) }
        assertNull(update(detector, 0).verdict)
        assertNull(update(detector, 2).verdict)
        assertEquals(true, update(detector, 3).verdict)
    }

    @Test
    fun gapRestartsConfirmationInsteadOfCombiningSeparateWalks() {
        val detector = EnRouteFusionDetector()
        update(detector, 1)
        update(detector, 2)
        assertNull(update(detector, 33).verdict)
        assertNull(update(detector, 34).verdict)
        assertEquals(true, update(detector, 35).verdict)
    }

    @Test
    fun unavailableObservationClearsFusionAuthorityAndStreaks() {
        val detector = EnRouteFusionDetector()
        update(detector, 1)
        update(detector, 2)
        assertEquals(true, update(detector, 3).verdict)

        assertNull(update(detector, 4, moving = null).verdict)
        assertFalse(detector.isEnRoute())
        assertNull(update(detector, 5).verdict)
        assertNull(update(detector, 6).verdict)
        assertEquals(true, update(detector, 7).verdict)
    }

    @Test
    fun continuousUnchangedMovementStillConfirmsFromNewLocations() {
        val trend = DistanceTrendDetector()
        val fusion = EnRouteFusionDetector()
        var result: EnRouteFusionResult? = null
        repeat(6) { index ->
            val time = index.toLong() + 1
            result = update(
                fusion,
                time,
                trend = trend.update(500.0 - index * 5, time * 1_000_000_000L),
                closing = trend.closingMeters()
            )
        }
        assertEquals(true, result?.verdict)
    }

    @Test
    fun isolatedGpsOutlierCannotConfirmMissingCompassFallback() {
        val trend = DistanceTrendDetector()
        val fusion = EnRouteFusionDetector()
        listOf(500.0, 500.0, 500.0, 400.0, 400.0, 400.0, 400.0).forEachIndexed { index, distance ->
            val time = index.toLong() + 1
            val currentTrend = trend.update(distance, time * 1_000_000_000L)
            assertFalse(update(fusion, time, trend = currentTrend, heading = null, closing = trend.closingMeters()).verdict == true)
        }
    }

    private fun update(
        detector: EnRouteFusionDetector,
        seconds: Long,
        moving: Boolean? = true,
        rotating: Boolean? = false,
        trend: DistanceTrend = DistanceTrend.APPROACHING,
        heading: Double? = 0.0,
        closing: Double = 10.0
    ) = detector.update(moving, rotating, trend, heading, closing, seconds * 1_000_000_000L)
}
