package au.edu.unimelb.campuscompanion.sensing.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.MainThread
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/** Forwards only to the active in-process sensing owner; a broadcast never starts a service. */
class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = activeManager
        if (manager == null) {
            ActivityRegistrationCleanup.removeOrphans(context.applicationContext)
            ActivityRegistrationCleanup.removeObsoleteBroadcast(context.applicationContext, intent.action)
            return
        }
        if (!manager.ownsAction(intent.action)) {
            ActivityRegistrationCleanup.removeObsoleteBroadcast(context.applicationContext, intent.action)
            return
        }
        try {
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent) ?: return
                val events = result.transitionEvents.mapNotNull { event ->
                    val transition = when (event.transitionType) {
                        ActivityTransition.ACTIVITY_TRANSITION_ENTER -> ActivityTransitionKind.ENTER
                        ActivityTransition.ACTIVITY_TRANSITION_EXIT -> ActivityTransitionKind.EXIT
                        else -> return@mapNotNull null
                    }
                    MeasuredActivityTransition(event.activityType.toUserActivity(), transition, event.elapsedRealTimeNanos)
                }
                manager.acceptTransitions(intent.action, events)
            }
            if (ActivityRecognitionResult.hasResult(intent)) {
                val result = ActivityRecognitionResult.extractResult(intent) ?: return
                val activity = result.mostProbableActivity
                manager.acceptSnapshot(
                    intent.action, activity.type.toUserActivity(), activity.confidence,
                    result.elapsedRealtimeMillis
                )
            }
        } catch (error: RuntimeException) {
            Log.w("ActivityRecognition", "Unable to read activity broadcast", error)
        }
    }

    companion object {
        private var activeManager: ActivityRecognitionManager? = null

        @MainThread
        internal fun attach(manager: ActivityRecognitionManager) {
            if (activeManager !== manager) activeManager?.stop()
            activeManager = manager
        }

        @MainThread
        internal fun detach(manager: ActivityRecognitionManager) {
            if (activeManager === manager) activeManager = null
        }
    }
}

private fun Int.toUserActivity(): UserActivityState = when (this) {
    DetectedActivity.WALKING -> UserActivityState.WALKING
    DetectedActivity.RUNNING -> UserActivityState.RUNNING
    DetectedActivity.ON_FOOT -> UserActivityState.ON_FOOT
    DetectedActivity.IN_VEHICLE -> UserActivityState.IN_VEHICLE
    DetectedActivity.STILL -> UserActivityState.STILL
    else -> UserActivityState.UNKNOWN
}
