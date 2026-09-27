package au.edu.unimelb.campuscompanion.sensing.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {


    @Test
    fun bearing_isWithinValidRange() {
        val bearing = bearingDegrees(
            -37.7963,
            144.9614,
            -37.8000,
            144.9650
        )

        assertTrue(bearing >= 0.0)
        assertTrue(bearing < 360.0)
    }
}
