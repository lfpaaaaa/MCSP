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

/** Within this distance the arrow is replaced by a "near the building" message. */
const val COMPASS_NEAR_BUILDING_METERS = 75.0
/** The same two-minute location lifetime as the travel engine. */
const val COMPASS_LOCATION_MAX_AGE_MILLIS = 120_000L
const val COMPASS_LOCATION_MAX_ACCURACY_METERS = 50f
/** A heading older than this (elapsed realtime) is treated as missing. */
const val COMPASS_HEADING_MAX_AGE_NANOS = 5_000_000_000L
/** SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM; below it the user is asked to calibrate. */
const val COMPASS_MIN_MAGNETIC_ACCURACY = 2
/** Beyond this tilt the user is asked to hold the phone flat. */
const val COMPASS_MAX_TILT_DEGREES = 45f

fun LocationSample.isUsableForCompass(nowMillis: Long): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracyMeters in 0f..COMPASS_LOCATION_MAX_ACCURACY_METERS &&
        nowMillis - timestampMillis in 0..COMPASS_LOCATION_MAX_AGE_MILLIS

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
    if (GeoMath.distanceMeters(origin, destination) <= COMPASS_NEAR_BUILDING_METERS) return DestinationCompass.NearBuilding
    if (!sensorsAvailable) return DestinationCompass.SensorUnavailable
    if (heading == null || elapsedRealtimeNanos - heading.timestampNanos !in 0..COMPASS_HEADING_MAX_AGE_NANOS ||
        heading.displayRotation != displayRotation || !heading.headingDegrees.isFinite() || !declinationDegrees.isFinite()
    ) return DestinationCompass.WaitingForHeading
    if (heading.accuracy < COMPASS_MIN_MAGNETIC_ACCURACY) return DestinationCompass.Calibrate
    if (!heading.tiltDegrees.isFinite() || heading.tiltDegrees > COMPASS_MAX_TILT_DEGREES) return DestinationCompass.HoldFlat
    val bearing = bearingDegrees(origin.latitude, origin.longitude, destination.latitude, destination.longitude).toFloat()
    val trueHeading = heading.headingDegrees + declinationDegrees
    return DestinationCompass.Pointing(shortestCompassTurn(bearing - trueHeading), bearing)
}

/** Avoid a full-circle animation when a bearing crosses north (359° → 1°). */
fun nextCompassRotation(previous: Float, target: Float): Float = previous + shortestCompassTurn(target - previous)

private fun shortestCompassTurn(angle: Float): Float = ((angle % 360f + 540f) % 360f) - 180f
