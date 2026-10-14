package au.edu.unimelb.campuscompanion.sensing.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import au.edu.unimelb.campuscompanion.BuildConfig
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LocationTrackingMode {
    NORMAL,
    PRE_CLASS
}

class LocationTracker internal constructor(
    private val fusedLocationClient: FusedLocationProviderClient
) {
    constructor(context: Context) : this(LocationServices.getFusedLocationProviderClient(context))

    private val mainHandler = Handler(Looper.getMainLooper())
    private var trackingGeneration = 0L
    private var activeCallback: LocationCallback? = null
    private var activeMode: LocationTrackingMode? = null
    private var lastObservationNanos = 0L
    private var lastRejectionLogNanos = 0L
    private var trackingStartedAtNanos = 0L
    private var initialFixCancellation: CancellationTokenSource? = null
    private var initialFixTimeout: Runnable? = null

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
        val alreadyLocated = _location.value?.let {
            EvidenceFreshness.rejectionReason(it, SystemClock.elapsedRealtimeNanos()) == null
        } == true
        stopOnMain()
        val generation = trackingGeneration
        trackingStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (generation != trackingGeneration || activeCallback !== this) return
                val nowNanos = SystemClock.elapsedRealtimeNanos()
                for (fix in result.locations) {
                    if (generation != trackingGeneration || activeCallback !== this) return
                    acceptLocation(fix, nowNanos)
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
            if (mode == LocationTrackingMode.NORMAL && !alreadyLocated &&
                generation == trackingGeneration && activeCallback === callback && _location.value == null) {
                requestInitialFix(generation, callback)
            }
        } catch (error: RuntimeException) {
            stopOnMain()
            Log.w("LocationTracker", "Unable to start location tracking", error)
        }
    }

    fun stopTracking() = onMain { stopOnMain() }

    /** Both delivery paths use the same measured-time, ordering and accuracy checks. */
    private fun acceptLocation(fix: Location, nowNanos: Long): Boolean {
        val sample = LocationSample(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = if (fix.hasAccuracy()) fix.accuracy else Float.NaN,
            timestampMillis = fix.time,
            elapsedRealtimeNanos = fix.elapsedRealtimeNanos
        )
        val reason = EvidenceFreshness.rejectionReason(
            sample, nowNanos, lastObservationNanos, trackingStartedAtNanos
        )
        if (reason != null) {
            if (BuildConfig.DEBUG && nowNanos - lastRejectionLogNanos >= 5_000_000_000L) {
                lastRejectionLogNanos = nowNanos
                Log.d("LocationTracker", "rejected reason=$reason, ageMs=${(nowNanos - sample.elapsedRealtimeNanos) / 1_000_000L}, accuracyM=${sample.accuracyMeters}")
            }
            return false
        }
        lastObservationNanos = sample.elapsedRealtimeNanos
        // Invalidate the one-shot before publishing: collectors can replace or stop this run.
        cancelInitialFix("location_available")
        _location.value = sample
        return true
    }

    @SuppressLint("MissingPermission")
    private fun requestInitialFix(generation: Long, callback: LocationCallback) {
        val cancellation = CancellationTokenSource()
        initialFixCancellation = cancellation
        val deadline = SystemClock.elapsedRealtimeNanos() + INITIAL_FIX_TIMEOUT_MILLIS * 1_000_000L
        fun isCurrent() = initialFixCancellation === cancellation &&
            trackingGeneration == generation && activeCallback === callback
        val timeout = Runnable {
            if (isCurrent()) cancelInitialFix("timeout")
        }
        initialFixTimeout = timeout
        mainHandler.postDelayed(timeout, INITIAL_FIX_TIMEOUT_MILLIS)
        try {
            // NORMAL remains balanced; this single fresh request only bootstraps its first fix.
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0L)
                .setDurationMillis(INITIAL_FIX_TIMEOUT_MILLIS)
                .build()
            if (BuildConfig.DEBUG) Log.d("LocationTracker", "Initial high-accuracy request started, generation=$generation")
            fusedLocationClient.getCurrentLocation(request, cancellation.token)
                .addOnSuccessListener { fix ->
                    onMain {
                        if (!isCurrent()) return@onMain
                        val now = SystemClock.elapsedRealtimeNanos()
                        cancelInitialFix(if (fix == null) "unavailable" else "completed")
                        if (fix != null && now < deadline) acceptLocation(fix, now)
                    }
                }
                .addOnFailureListener { error ->
                    onMain {
                        if (!isCurrent()) return@onMain
                        cancelInitialFix("failed")
                        Log.w("LocationTracker", "Initial location unavailable; keeping regular updates", error)
                    }
                }
                .addOnCanceledListener {
                    onMain { if (isCurrent()) cancelInitialFix("cancelled") }
                }
        } catch (error: RuntimeException) {
            if (isCurrent()) cancelInitialFix("failed")
            Log.w("LocationTracker", "Unable to request initial location; keeping regular updates", error)
        }
    }

    private fun cancelInitialFix(reason: String) {
        val cancellation = initialFixCancellation
        initialFixCancellation = null
        initialFixTimeout?.let(mainHandler::removeCallbacks)
        initialFixTimeout = null
        cancellation?.cancel()
        if (cancellation != null && BuildConfig.DEBUG) {
            Log.d("LocationTracker", "Initial high-accuracy request ended: $reason")
        }
    }

    private fun stopOnMain() {
        trackingGeneration++
        cancelInitialFix("stopped")
        val previousCallback = activeCallback
        activeCallback = null
        activeMode = null
        lastObservationNanos = 0L
        trackingStartedAtNanos = 0L
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

    private companion object {
        const val INITIAL_FIX_TIMEOUT_MILLIS = 20_000L
    }
}
