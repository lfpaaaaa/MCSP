package au.edu.unimelb.campuscompanion.sensing.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        if (!ActivityTransitionResult.hasResult(intent)) {
            return
        }

        val result =
            ActivityTransitionResult.extractResult(intent)
                ?: return

        result.transitionEvents.forEach { event ->
            if (event.activityType == DetectedActivity.IN_VEHICLE &&
                android.os.SystemClock.elapsedRealtimeNanos() - event.elapsedRealTimeNanos in 0..60_000_000_000L) {
                au.edu.unimelb.campuscompanion.data.AppRepositories.navigation.updateActivity(
                    event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                )
            }

            val activityName =
                when (event.activityType) {
                    DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
                    DetectedActivity.WALKING -> "WALKING"
                    DetectedActivity.RUNNING -> "RUNNING"
                    DetectedActivity.STILL -> "STILL"
                    else -> "OTHER"
                }

            val transitionName =
                when (event.transitionType) {
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER -> "ENTER"
                    ActivityTransition.ACTIVITY_TRANSITION_EXIT -> "EXIT"
                    else -> "UNKNOWN"
                }

            Log.d(
                "ActivityRecognition",
                "$transitionName $activityName"
            )
        }
    }
}