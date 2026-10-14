package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import kotlin.math.roundToInt

/** Choose the mode at tap time, even when the selected class has no ETA yet. */
fun preferredNavigationMode(
    destination: GeoPoint,
    preferences: TravelPreferences,
    location: LocationSample?,
    routeDistanceMeters: Int? = null,
    nowMillis: Long = System.currentTimeMillis()
): TravelMode {
    // Use the same two-minute location lifetime as the travel engine and compass.
    val current = location?.takeIf {
        nowMillis - it.timestampMillis in 0..120_000L &&
            it.accuracyMeters in 0f..50f &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
    }
    val distance = current?.let {
        GeoMath.distanceMeters(GeoPoint(it.latitude, it.longitude), destination).roundToInt()
    } ?: routeDistanceMeters?.takeIf { it >= 0 }
    // An old ETA for another mode must not override the user's current preference.
    return distance?.let(preferences::preferredMode) ?: preferences.longerDistanceMode
}
