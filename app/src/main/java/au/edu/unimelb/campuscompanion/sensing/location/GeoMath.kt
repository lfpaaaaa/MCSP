package au.edu.unimelb.campuscompanion.sensing.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val EARTH_RADIUS_METERS = 6_371_000.0

fun bearingDegrees(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val deltaLon = Math.toRadians(lon2 - lon1)

    val y = sin(deltaLon) * cos(lat2Rad)
    val x =
        cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)

    val bearing = Math.toDegrees(atan2(y, x))

    return (bearing + 360.0) % 360.0
}
