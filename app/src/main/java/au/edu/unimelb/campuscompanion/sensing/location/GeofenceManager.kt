package au.edu.unimelb.campuscompanion.sensing.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GeofenceRegistrationStatus {
    REGISTERING, REGISTERED, DENIED, UNAVAILABLE, FAILED, REMOVING, REMOVED
}

data class GeofenceRegistrationState(
    val status: GeofenceRegistrationStatus,
    val requestId: String? = null,
    val generation: Long? = null,
    val buildingCode: String? = null,
    val detail: String? = null
)

/** Actual asynchronous API completion, including cleanup of a superseded registration. */
data class GeofenceOperationOutcome(
    val operation: GeofenceRegistrationOperation,
    val status: GeofenceRegistrationStatus,
    val current: Boolean,
    val detail: String? = null
)

/** Registration commands, completion callbacks and broadcast ownership run on Main. */
class GeofenceManager(context: Context) {
    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getGeofencingClient(appContext) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controller = GeofenceRegistrationController()
    private val mutableRegistration = MutableStateFlow(
        GeofenceRegistrationState(GeofenceRegistrationStatus.REMOVED)
    )
    val registration: StateFlow<GeofenceRegistrationState> = mutableRegistration.asStateFlow()
    private val mutableOutcomes = MutableSharedFlow<GeofenceOperationOutcome>(extraBufferCapacity = 16)
    val outcomes: SharedFlow<GeofenceOperationOutcome> = mutableOutcomes.asSharedFlow()
    private var requestedTarget: SensingTarget? = null
    private var unavailable: GeofenceRegistrationState? = null
    private var addFailure: Pair<String, GeofenceRegistrationState>? = null
    private var cleanupFailure: GeofenceRegistrationState? = null

    /** Repeated calls preserve a working registration and recheck revocation or a later grant. */
    fun setTarget(target: SensingTarget?) = onMain {
        requestedTarget = target?.takeIf { it.active && it.building != null }
        refreshPermissions()
        controller.retryCleanup()
        cleanupFailure = null
        publishState()
        drain()
    }

    fun stop() = setTarget(null)

    @MainThread
    internal fun entry(requestId: String, receivedAtNanos: Long, location: LocationSample?): GeofenceEntryEvent? {
        refreshPermissions()
        val current = controller.currentRegistration(requestId)
        if (current == null) {
            Log.d(TAG, "Rejected entry id=$requestId: no current successful registration")
            publishState()
            drain()
            return null
        }
        return GeofenceEntryEvent(
            current.requestId, current.generation, current.buildingCode, receivedAtNanos, location
        )
    }

    private fun refreshPermissions() {
        val target = requestedTarget
        val permissions = locationPermissionState(appContext)
        unavailable = when {
            target == null -> null
            !permissions.fine ->
                targetState(GeofenceRegistrationStatus.DENIED, "Precise location is required")
            !permissions.canMonitorBackgroundArrival ->
                targetState(GeofenceRegistrationStatus.DENIED, "Background location is required")
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) != ConnectionResult.SUCCESS ->
                targetState(GeofenceRegistrationStatus.UNAVAILABLE, "Google Play services are unavailable")
            else -> null
        }
        controller.setTarget(target.takeIf { unavailable == null })
        if (target != null) {
            GeofenceBroadcastReceiver.attach(this)
        } else {
            // Cleanup callbacks keep their owner alive without reclaiming a newer receiver owner.
            GeofenceBroadcastReceiver.detach(this)
        }
    }

    @SuppressLint("MissingPermission")
    private fun drain() {
        val operation = controller.nextOperation() ?: return
        publishState()
        try {
            val task: Task<Void> = when (operation) {
                GeofenceRegistrationOperation.RemoveOrphans -> orphanCleanupTask(appContext)
                is GeofenceRegistrationOperation.Remove ->
                    client.removeGeofences(listOf(operation.registration.requestId))
                is GeofenceRegistrationOperation.Add -> {
                    val registration = operation.registration
                    val geofence = Geofence.Builder()
                        .setRequestId(registration.requestId)
                        .setCircularRegion(registration.latitude, registration.longitude, ARRIVAL_RADIUS_METERS)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                        .build()
                    val request = GeofencingRequest.Builder()
                        .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                        .addGeofence(geofence)
                        .build()
                    client.addGeofences(request, pendingIntent(appContext))
                }
            }
            task.addOnSuccessListener { onMain { complete(operation, null) } }
                .addOnFailureListener { error -> onMain { complete(operation, error) } }
        } catch (error: RuntimeException) {
            complete(operation, error)
        }
    }

    private fun complete(operation: GeofenceRegistrationOperation, error: Exception?) {
        // A grant/revocation may occur while the Play services task is in flight.
        refreshPermissions()
        controller.complete(operation, error == null)
        val status = when {
            error != null -> failureStatus(error)
            operation is GeofenceRegistrationOperation.Add -> GeofenceRegistrationStatus.REGISTERED
            else -> GeofenceRegistrationStatus.REMOVED
        }
        val current = operation is GeofenceRegistrationOperation.Add &&
            controller.currentRegistration(operation.registration.requestId) != null
        val detail = error?.message
        mutableOutcomes.tryEmit(GeofenceOperationOutcome(operation, status, current, detail))
        if (error == null) {
            when (operation) {
                is GeofenceRegistrationOperation.Add -> Log.d(
                    TAG, "Geofence registration succeeded id=${operation.registration.requestId} " +
                        "generation=${operation.registration.generation} current=$current"
                )
                is GeofenceRegistrationOperation.Remove ->
                    Log.d(TAG, "Geofence removal succeeded id=${operation.registration.requestId}")
                GeofenceRegistrationOperation.RemoveOrphans -> Log.d(TAG, "Orphan geofences removed")
            }
        } else {
            val failed = targetState(status, detail)
            if (operation is GeofenceRegistrationOperation.Add) {
                addFailure = operation.registration.requestId to failed
            } else {
                cleanupFailure = failed
            }
            Log.w(TAG, "Geofence ${operation.javaClass.simpleName} failed: $status", error)
        }
        publishState()
        // Superseded success is now obsolete, so removal is selected before any new add.
        drain()
    }

    private fun publishState() {
        mutableRegistration.value = unavailable ?: when {
            controller.registered != null -> targetState(GeofenceRegistrationStatus.REGISTERED)
            controller.hasFailedCleanup -> cleanupFailure ?: targetState(GeofenceRegistrationStatus.FAILED)
            requestedTarget == null -> targetState(
                if (controller.hasPendingCleanup) GeofenceRegistrationStatus.REMOVING else GeofenceRegistrationStatus.REMOVED
            )
            addFailure?.first == controller.desired?.requestId -> requireNotNull(addFailure).second
            else -> targetState(GeofenceRegistrationStatus.REGISTERING)
        }
        if (requestedTarget == null && !controller.hasPendingCleanup && controller.inFlight == null) {
            GeofenceBroadcastReceiver.detach(this)
        }
    }

    private fun targetState(status: GeofenceRegistrationStatus, detail: String? = null): GeofenceRegistrationState =
        GeofenceRegistrationState(
            status, controller.desired?.requestId, requestedTarget?.generation,
            requestedTarget?.building?.locCode, detail
        )

    private fun failureStatus(error: Exception): GeofenceRegistrationStatus = when {
        error is SecurityException -> GeofenceRegistrationStatus.DENIED
        error is ApiException && error.statusCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
            GeofenceRegistrationStatus.UNAVAILABLE
        else -> GeofenceRegistrationStatus.FAILED
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) action() else mainHandler.post(action)
    }

    companion object {
        const val ARRIVAL_RADIUS_METERS = 75f
        private const val TAG = "Geofence"
        private var orphanRemovalTask: Task<Void>? = null

        internal fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, GeofenceBroadcastReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        /** A process restarted by a persistent broadcast must not revive an earlier sensing run. */
        internal fun removeOrphans(context: Context) {
            try {
                orphanCleanupTask(context)
                    .addOnSuccessListener { Log.d(TAG, "Unowned geofences removed") }
                    .addOnFailureListener { Log.w(TAG, "Unowned geofence cleanup failed", it) }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to remove unowned geofences", error)
            }
        }

        /** Share a process-start broadcast cleanup with a concurrently starting sensing owner. */
        @MainThread
        private fun orphanCleanupTask(context: Context): Task<Void> {
            orphanRemovalTask?.takeUnless { it.isComplete }?.let { return it }
            val task = LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
            orphanRemovalTask = task
            task.addOnCompleteListener {
                if (orphanRemovalTask === task) orphanRemovalTask = null
            }
            return task
        }
    }
}
