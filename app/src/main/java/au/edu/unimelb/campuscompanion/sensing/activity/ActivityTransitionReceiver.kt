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

            val activityName =
                when (event.activityType) {
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