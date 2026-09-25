package au.edu.unimelb.campuscompanion.data.geo

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StraightLineEstimatorTest {
    @Test
    fun walksTheStraightLineAtAveragePace() {
        val now = Instant.parse("2026-09-25T04:00:00Z")

        val estimate = StraightLineEstimator.estimate(GeoPoint(0.0, 0.0), GeoPoint(0.01, 0.0), now)

        assertEquals(1_112, estimate.distanceMeters)
        assertEquals(856L, estimate.durationSeconds)
        assertEquals(15, estimate.durationMinutes)
        assertEquals(TravelMode.Walking, estimate.mode)
        assertEquals(EstimateSource.StraightLine, estimate.source)
        assertTrue(estimate.isApproximate)
        assertEquals(now, estimate.computedAt)
    }
}
