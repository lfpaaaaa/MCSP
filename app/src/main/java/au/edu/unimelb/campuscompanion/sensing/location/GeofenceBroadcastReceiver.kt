package au.edu.unimelb.campuscompanion.sensing.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.data.AppRepositories
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/** Broadcasts never start sampling or revive a target from an earlier process. */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = activeManager
        if (manager == null) {
            GeofenceManager.removeOrphans(context.applicationContext)
            Log.d(TAG, "Rejected entry: sensing has no active registration owner")
            return
        }
        try {
            val event = GeofencingEvent.fromIntent(intent) ?: return
            if (event.hasError()) {
                Log.w(TAG, "Geofence delivery failed code=${event.errorCode}")
                manager.deliveryFailed(event.errorCode)
                return
            }
            if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
            val receivedAt = SystemClock.elapsedRealtimeNanos()
            val location = event.triggeringLocation?.let {
                LocationSample(
                    it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else Float.NaN,
                    it.time, it.elapsedRealtimeNanos
                )
            }
            event.triggeringGeofences.orEmpty().forEach { geofence ->
                val entry = manager.entry(geofence.requestId, receivedAt, location)
                if (entry == null) {
                    val triggerAge = location?.elapsedRealtimeNanos?.takeIf { it > 0 && it <= receivedAt }
                        ?.let { (receivedAt - it) / 1_000_000L }
                    Log.d(TAG, "Geofence entry rejected id=${geofence.requestId} source=geofence " +
                        "reason=no_current_registration triggerAgeMs=$triggerAge accuracyM=${location?.accuracyMeters}")
                    return@forEach
                }
                val before = AppRepositories.travel.snapshot.value.state
                val result = AppRepositories.travel.reportGeofenceEntered(entry)
                val after = AppRepositories.travel.snapshot.value.state
                val arrivalStates = setOf(ContextState.ARRIVED, ContextState.IN_CLASS)
                val established = result.accepted && before !in arrivalStates && after in arrivalStates
                Log.d(
                    TAG, "Geofence entry delivered id=${entry.requestId} generation=${entry.generation} " +
                        "building=${entry.buildingCode} source=geofence accepted=${result.accepted} " +
                        "reason=${result.rejectionReason?.logValue ?: "accepted"} triggerAgeMs=${result.triggerAgeMillis} " +
                        "triggerAgeAtReceiptMs=${result.triggerAgeAtReceiptMillis} accuracyM=${result.triggerAccuracyMeters} " +
                        "preActivation=${result.triggerBeforeActivation} " +
                        "state=$before->$after established=$established"
                )
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to process geofence broadcast", error)
        }
    }

    companion object {
        private const val TAG = "Geofence"
        private var activeManager: GeofenceManager? = null

        @MainThread
        internal fun attach(manager: GeofenceManager) {
            if (activeManager !== manager) activeManager?.stop()
            activeManager = manager
        }

        @MainThread
        internal fun detach(manager: GeofenceManager) {
            if (activeManager === manager) activeManager = null
        }
    }
}
