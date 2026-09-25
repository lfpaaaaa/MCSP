package au.edu.unimelb.campuscompanion.data.geo

import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    /** Mean radius of the Earth in metres. */
    const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle distance in metres between two points, using the haversine formula. */
    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLat = toLat - fromLat
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(deltaLat / 2).pow(2) + cos(fromLat) * cos(toLat) * sin(deltaLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
