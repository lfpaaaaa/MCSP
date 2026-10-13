package au.edu.unimelb.campuscompanion.sensing.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import au.edu.unimelb.campuscompanion.BuildConfig
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LocationTrackingMode {
    NORMAL,
    PRE_CLASS
}

class LocationTracker(context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var trackingGeneration = 0L
    private var activeCallback: LocationCallback? = null
    private var activeMode: LocationTrackingMode? = null
    private var lastObservationNanos = 0L
    private var lastRejectionLogNanos = 0L

    private val _location = MutableStateFlow<LocationSample?>(null)
    val location: StateFlow<LocationSample?> = _location.asStateFlow()

    private fun createLocationRequest(mode: LocationTrackingMode): LocationRequest = when (mode) {
        LocationTrackingMode.NORMAL -> LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            60_000L
        ).setMinUpdateIntervalMillis(30_000L).build()

        LocationTrackingMode.PRE_CLASS -> LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10_000L
        ).setMinUpdateIntervalMillis(5_000L).build()
    }

    @SuppressLint("MissingPermission")
    fun startTracking(mode: LocationTrackingMode = LocationTrackingMode.NORMAL) = onMain {
        if (activeCallback != null && activeMode == mode) return@onMain
        stopOnMain()
        val generation = trackingGeneration
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (generation != trackingGeneration || activeCallback !== this) return
                val nowNanos = SystemClock.elapsedRealtimeNanos()
                result.locations.forEach { fix ->
                    val measuredNanos = fix.elapsedRealtimeNanos
                    val sample = LocationSample(
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        accuracyMeters = if (fix.hasAccuracy()) fix.accuracy else Float.NaN,
                        timestampMillis = fix.time,
                        elapsedRealtimeNanos = measuredNanos
                    )
                    val reason = EvidenceFreshness.rejectionReason(sample, nowNanos, lastObservationNanos)
                    if (reason != null) {
                        if (BuildConfig.DEBUG && nowNanos - lastRejectionLogNanos >= 5_000_000_000L) {
                            lastRejectionLogNanos = nowNanos
                            Log.d("LocationTracker", "rejected reason=$reason, ageMs=${(nowNanos - measuredNanos) / 1_000_000L}, accuracyM=${sample.accuracyMeters}")
                        }
                        return@forEach
                    }
                    lastObservationNanos = measuredNanos
                    _location.value = sample
                }
            }
        }
        activeCallback = callback
        activeMode = mode
        try {
            fusedLocationClient.requestLocationUpdates(
                createLocationRequest(mode),
                callback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                onMain {
                    if (generation != trackingGeneration || activeCallback !== callback) {
                        // A registration may finish after its run has already been stopped.
                        fusedLocationClient.removeLocationUpdates(callback)
                    }
                }
            }.addOnFailureListener { error ->
                onMain {
                    if (generation == trackingGeneration && activeCallback === callback) {
                        stopOnMain()
                        Log.w("LocationTracker", "Location registration failed", error)
                    }
                }
            }
        } catch (error: RuntimeException) {
            stopOnMain()
            Log.w("LocationTracker", "Unable to start location tracking", error)
        }
    }

    fun stopTracking() = onMain { stopOnMain() }

    private fun stopOnMain() {
        trackingGeneration++
        val previousCallback = activeCallback
        activeCallback = null
        activeMode = null
        lastObservationNanos = 0L
        _location.value = null
        if (previousCallback != null) {
            try {
                fusedLocationClient.removeLocationUpdates(previousCallback)
                    .addOnFailureListener { error ->
                        Log.w("LocationTracker", "Location removal failed", error)
                    }
            } catch (error: RuntimeException) {
                Log.w("LocationTracker", "Unable to remove location updates", error)
            }
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) action() else mainHandler.post(action)
    }

}
