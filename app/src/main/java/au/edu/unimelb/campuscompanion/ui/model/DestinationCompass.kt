package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.bearingDegrees
import au.edu.unimelb.campuscompanion.sensing.orientation.CompassSample

sealed interface DestinationCompass {
    data object WaitingForLocation : DestinationCompass
    data object SensorUnavailable : DestinationCompass
    data object WaitingForHeading : DestinationCompass
    data object Calibrate : DestinationCompass
    data object HoldFlat : DestinationCompass
    data object NearBuilding : DestinationCompass
    data class Pointing(val relativeDegrees: Float, val bearingDegrees: Float) : DestinationCompass {
        /** Eight sectors, clockwise from the top of the display. */
        val directionSector: Int get() = (((relativeDegrees + 360f + 22.5f) % 360f) / 45f).toInt()
    }
}

fun LocationSample.isUsableForCompass(nowMillis: Long): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracyMeters in 0f..50f && nowMillis - timestampMillis in 0..120_000L

/** Both the building bearing and the heading use true north after magnetic declination correction. */
fun destinationCompass(
    destination: GeoPoint,
    location: LocationSample?,
    heading: CompassSample?,
    sensorsAvailable: Boolean,
    declinationDegrees: Float,
    nowMillis: Long,
    elapsedRealtimeNanos: Long,
    displayRotation: Int
): DestinationCompass {
    if (location == null || !location.isUsableForCompass(nowMillis)) return DestinationCompass.WaitingForLocation
    val origin = GeoPoint(location.latitude, location.longitude)
    if (GeoMath.distanceMeters(origin, destination) <= 75.0) return DestinationCompass.NearBuilding
    if (!sensorsAvailable) return DestinationCompass.SensorUnavailable
    if (heading == null || elapsedRealtimeNanos - heading.timestampNanos !in 0..5_000_000_000L ||
        heading.displayRotation != displayRotation || !heading.headingDegrees.isFinite() || !declinationDegrees.isFinite()
    ) return DestinationCompass.WaitingForHeading
    if (heading.accuracy < 2) return DestinationCompass.Calibrate
    if (!heading.tiltDegrees.isFinite() || heading.tiltDegrees > 45f) return DestinationCompass.HoldFlat
    val bearing = bearingDegrees(origin.latitude, origin.longitude, destination.latitude, destination.longitude).toFloat()
    val trueHeading = heading.headingDegrees + declinationDegrees
    return DestinationCompass.Pointing(shortestCompassTurn(bearing - trueHeading), bearing)
}

/** Avoid a full-circle animation when a bearing crosses north (359° → 1°). */
fun nextCompassRotation(previous: Float, target: Float): Float = previous + shortestCompassTurn(target - previous)

private fun shortestCompassTurn(angle: Float): Float = ((angle % 360f + 540f) % 360f) - 180f
