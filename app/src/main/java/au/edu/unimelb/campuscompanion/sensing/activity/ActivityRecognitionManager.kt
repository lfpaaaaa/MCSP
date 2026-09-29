package au.edu.unimelb.campuscompanion.sensing.activity

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

class ActivityRecognitionManager(
    private val context: Context
) {

    private val activityRecognitionClient =
        ActivityRecognition.getClient(context)

    private val pendingIntent: PendingIntent by lazy {

        val intent =
            Intent(
                context,
                ActivityTransitionReceiver::class.java
            )

        PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    fun start() {

        val transitions =
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(
                        DetectedActivity.WALKING
                    )
                    .setActivityTransition(
                        ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    )
                    .build(),

                ActivityTransition.Builder()
                    .setActivityType(
                        DetectedActivity.WALKING
                    )
                    .setActivityTransition(
                        ActivityTransition.ACTIVITY_TRANSITION_EXIT
                    )
                    .build(),

                ActivityTransition.Builder()
                    .setActivityType(
                        DetectedActivity.STILL
                    )
                    .setActivityTransition(
                        ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    )
                    .build(),

                ActivityTransition.Builder()
                    .setActivityType(
                        DetectedActivity.RUNNING
                    )
                    .setActivityTransition(
                        ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    )
                    .build()
            )

        val request =
            ActivityTransitionRequest(transitions)

        activityRecognitionClient
            .requestActivityTransitionUpdates(
                request,
                pendingIntent
            )
            .addOnSuccessListener {
                Log.d(
                    "ActivityRecognition",
                    "Activity transition registration SUCCESS"
                )
            }
            .addOnFailureListener { exception ->
                Log.e(
                    "ActivityRecognition",
                    "Activity transition registration FAILED",
                    exception
                )
            }
    }

    fun stop() {
        activityRecognitionClient
            .removeActivityTransitionUpdates(
                pendingIntent
            )
    }
}