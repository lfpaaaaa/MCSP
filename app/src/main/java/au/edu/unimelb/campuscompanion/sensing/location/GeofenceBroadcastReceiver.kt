package au.edu.unimelb.campuscompanion.sensing.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        val geofencingEvent =
            GeofencingEvent.fromIntent(intent)
                ?: return

        if (geofencingEvent.hasError()) {
            Log.e(
                "Geofence",
                "Geofence error code=${geofencingEvent.errorCode}"
            )
            return
        }

        val transition =
            geofencingEvent.geofenceTransition

        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {

            val triggeredGeofences =
                geofencingEvent.triggeringGeofences
                    ?: emptyList()

            triggeredGeofences.forEach { geofence ->
                Log.d(
                    "Geofence",
                    "ARRIVED at ${geofence.requestId}"
                )
            }
        }
    }
}