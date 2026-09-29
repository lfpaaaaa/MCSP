package au.edu.unimelb.campuscompanion.sensing.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalDetectorTest {

    @Test
    fun distanceLessThan75m_returnsTrue() {
        val arrived = ArrivalDetector.hasArrived(50.0)

        assertTrue(arrived)
    }

    @Test
    fun distanceExactly75m_returnsTrue() {
        val arrived = ArrivalDetector.hasArrived(75.0)

        assertTrue(arrived)
    }

    @Test
    fun distanceGreaterThan75m_returnsFalse() {
        val arrived = ArrivalDetector.hasArrived(100.0)

        assertFalse(arrived)
    }

    @Test
    fun customArrivalRadius_worksCorrectly() {
        val arrived = ArrivalDetector.hasArrived(
            distanceMeters = 40.0,
            arrivalRadiusMeters = 30.0
        )

        assertFalse(arrived)
    }
}