package au.edu.unimelb.campuscompanion.data.geo

import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {
    @Test
    fun distanceToTheSamePointIsZero() {
        val point = GeoPoint(-37.7983, 144.9610)

        assertEquals(0.0, GeoMath.distanceMeters(point, point), 1e-9)
    }

    @Test
    fun oneDegreeOfLatitudeMatchesTheEarthRadius() {
        val distance = GeoMath.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))

        assertEquals(111_194.93, distance, 0.01)
    }

    @Test
    fun distanceIsSymmetric() {
        val flindersStreet = GeoPoint(-37.8183, 144.9671)
        val melbourneCentral = GeoPoint(-37.8100, 144.9630)

        val there = GeoMath.distanceMeters(flindersStreet, melbourneCentral)
        val back = GeoMath.distanceMeters(melbourneCentral, flindersStreet)

        assertEquals(there, back, 1e-6)
        assertEquals(990.7, there, 0.5)
    }
}
