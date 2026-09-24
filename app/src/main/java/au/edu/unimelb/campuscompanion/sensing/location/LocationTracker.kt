package au.edu.unimelb.campuscompanion.sensing.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LocationTrackingMode {
    NORMAL,
    PRE_CLASS
}

class LocationTracker(
    context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _location = MutableStateFlow<LocationSample?>(null)
    val location: StateFlow<LocationSample?> = _location.asStateFlow()

    private fun createLocationRequest(
        mode: LocationTrackingMode
    ): LocationRequest {
        return when (mode) {
            LocationTrackingMode.NORMAL ->
                LocationRequest.Builder(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    60_000L
                )
                    .setMinUpdateIntervalMillis(30_000L)
                    .build()

            LocationTrackingMode.PRE_CLASS ->
                LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    10_000L
                )
                    .setMinUpdateIntervalMillis(5_000L)
                    .build()
        }
    }

    private val locationCallback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val androidLocation = result.lastLocation ?: return

                if (androidLocation.accuracy > 50f) {
                    return
                }

                _location.value =
                    LocationSample(
                        latitude = androidLocation.latitude,
                        longitude = androidLocation.longitude,
                        accuracyMeters = androidLocation.accuracy,
                        timestampMillis = androidLocation.time
                    )
            }
        }

    @SuppressLint("MissingPermission")
    fun startTracking(
        mode: LocationTrackingMode = LocationTrackingMode.NORMAL
    ) {
        fusedLocationClient.requestLocationUpdates(
            createLocationRequest(mode),
            locationCallback,
            null
        )
    }

    fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}

