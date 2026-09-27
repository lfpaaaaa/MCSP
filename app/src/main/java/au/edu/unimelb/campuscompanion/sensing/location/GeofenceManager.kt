package au.edu.unimelb.campuscompanion.sensing.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import android.util.Log

class GeofenceManager(
    private val context: Context
) {

    companion object {
        const val ARRIVAL_RADIUS_METERS = 75f
    }

    private val geofencingClient =
        LocationServices.getGeofencingClient(context)

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent =
            Intent(
                context,
                GeofenceBroadcastReceiver::class.java
            )

        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    fun addArrivalGeofence(
        id: String,
        latitude: Double,
        longitude: Double
    ) {

        val geofence =
            Geofence.Builder()
                .setRequestId(id)
                .setCircularRegion(
                    latitude,
                    longitude,
                    ARRIVAL_RADIUS_METERS
                )
                .setExpirationDuration(
                    Geofence.NEVER_EXPIRE
                )
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER
                )
                .build()

        val request =
            GeofencingRequest.Builder()
                .setInitialTrigger(
                    GeofencingRequest.INITIAL_TRIGGER_ENTER
                )
                .addGeofence(geofence)
                .build()

        geofencingClient
            .addGeofences(
                request,
                geofencePendingIntent
            )
            .addOnSuccessListener {
                Log.d(
                    "Geofence",
                    "Geofence registration SUCCESS: $id"
                )
            }
            .addOnFailureListener { exception ->
                Log.e(
                    "Geofence",
                    "Geofence registration FAILED: $id",
                    exception
                )
            }
    }

    fun removeArrivalGeofence(id: String) {
        geofencingClient.removeGeofences(
            listOf(id)
        )
    }
}