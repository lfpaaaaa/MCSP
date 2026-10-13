package au.edu.unimelb.campuscompanion.sensing.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.data.AppRepositories
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Short initial-state acquisition, rather than a permanent sampling subscription. */
data class ActivityRecognitionConfig(
    val seedIntervalMillis: Long = 5_000L,
    val seedTimeoutMillis: Long = 20_000L,
    val minimumSnapshotConfidence: Int = 50,
    val maximumEventAgeNanos: Long = 60_000_000_000L
) {
    init {
        require(seedIntervalMillis > 0L)
        require(seedTimeoutMillis > 0L)
        require(minimumSnapshotConfidence in 0..100)
        require(maximumEventAgeNanos >= 0L)
    }
}

/** All registration, broadcast and observation state is serialized on Main. */
class ActivityRecognitionManager(
    context: Context,
    private val config: ActivityRecognitionConfig = ActivityRecognitionConfig()
) {
    private val appContext = context.applicationContext
    private val client = ActivityRecognition.getClient(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reducer = ActivityObservationReducer(
        config.maximumEventAgeNanos,
        config.minimumSnapshotConfidence
    )
    private val mutableObservations = MutableStateFlow<ActivityObservation?>(null)
    val observations: StateFlow<ActivityObservation?> = mutableObservations.asStateFlow()
    private var active: Registration? = null

    @get:MainThread
    val isAvailable: Boolean
        get() {
            checkPermissionWhileActive()
            return active?.transitionRegistered == true
        }

    @get:MainThread
    val observation: ActivityObservation?
        get() = if (isAvailable) mutableObservations.value else null

    fun start(activatedAtNanos: Long) = onMain {
        require(activatedAtNanos >= 0L)
        if (!hasPermission()) {
            stopOnMain()
            return@onMain
        }
        if (active?.activatedAtNanos == activatedAtNanos) return@onMain
        stopOnMain()
        ActivityRegistrationCleanup.removeOrphans(appContext)
        reducer.start(activatedAtNanos)
        val token = UUID.randomUUID().toString()
        val registration = Registration(
            activatedAtNanos,
            "${appContext.packageName}.activity.TRANSITIONS.$token",
            "${appContext.packageName}.activity.SEED.$token"
        )
        active = registration
        ActivityTransitionReceiver.attach(this)
        requestTransitions(registration)
        if (active === registration) requestSeed(registration)
    }

    fun stop() = onMain { stopOnMain() }

    @SuppressLint("MissingPermission")
    private fun requestTransitions(registration: Registration) {
        ActivityRegistrationCleanup.remember(appContext, registration.transitionAction)
        val transitions = listOf(
            DetectedActivity.WALKING, DetectedActivity.RUNNING,
            DetectedActivity.IN_VEHICLE, DetectedActivity.STILL
        ).flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .map { transition ->
                    ActivityTransition.Builder().setActivityType(type)
                        .setActivityTransition(transition).build()
                }
        }
        try {
            client.requestActivityTransitionUpdates(
                ActivityTransitionRequest(transitions), registration.transitionIntent
            ).addOnSuccessListener {
                onMain {
                    if (active !== registration) {
                        removeTransitions(registration)
                    } else if (!hasPermission()) {
                        stopOnMain()
                    } else {
                        registration.transitionRegistered = true
                        publishObservation()
                        Log.d(TAG, "Activity transitions registered")
                    }
                }
            }.addOnFailureListener { error ->
                onMain {
                    if (active === registration) stopOnMain()
                    Log.w(TAG, "Activity transition registration failed", error)
                }
            }
        } catch (error: RuntimeException) {
            if (active === registration) stopOnMain()
            Log.w(TAG, "Unable to register activity transitions", error)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestSeed(registration: Registration) {
        ActivityRegistrationCleanup.remember(appContext, registration.seedAction)
        registration.seedRequested = true
        val timeout = Runnable {
            if (active === registration) {
                checkPermissionWhileActive()
                finishSeed(registration)
            }
        }
        registration.seedTimeout = timeout
        // Bound the attempt from dispatch, including a slow registration completion.
        mainHandler.postDelayed(timeout, config.seedTimeoutMillis)
        try {
            client.requestActivityUpdates(config.seedIntervalMillis, registration.seedIntent)
                .addOnSuccessListener {
                    onMain {
                        if (active !== registration || registration.seedFinished) {
                            removeSeed(registration)
                        } else if (!hasPermission()) {
                            stopOnMain()
                        }
                    }
                }.addOnFailureListener { error ->
                    onMain {
                        checkPermissionWhileActive()
                        finishSeed(registration)
                        // Sampling is optional; failure must not remove a working transition stream.
                        Log.w(TAG, "Initial activity acquisition failed", error)
                    }
                }
        } catch (error: RuntimeException) {
            checkPermissionWhileActive()
            finishSeed(registration)
            Log.w(TAG, "Unable to acquire initial activity", error)
        }
    }

    @MainThread
    internal fun acceptTransitions(action: String?, events: List<MeasuredActivityTransition>) {
        checkPermissionWhileActive()
        val registration = active ?: return
        if (action != registration.transitionAction) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val previous = reducer.observation
        reducer.acceptBatch(events, nowNanos)
        if (reducer.observation != previous) updateNavigation(nowNanos)
        if (reducer.observation != null) finishSeed(registration)
        publishObservation()
    }

    @MainThread
    internal fun acceptSnapshot(action: String?, state: UserActivityState, confidence: Int, measuredMillis: Long) {
        checkPermissionWhileActive()
        val registration = active ?: return
        if (action != registration.seedAction || registration.seedFinished ||
            measuredMillis <= 0L || measuredMillis > Long.MAX_VALUE / 1_000_000L
        ) return
        val measuredNanos = measuredMillis * 1_000_000L
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (!reducer.acceptSnapshot(state, confidence, measuredNanos, nowNanos)) return
        updateNavigation(nowNanos)
        finishSeed(registration)
        publishObservation()
    }

    private fun publishObservation() {
        mutableObservations.value = if (active?.transitionRegistered == true) reducer.observation else null
    }

    private fun updateNavigation(nowNanos: Long) {
        val observation = reducer.observation ?: return
        AppRepositories.navigation.updateActivity(
            observation.state == UserActivityState.IN_VEHICLE,
            System.currentTimeMillis() - (nowNanos - observation.observedAtNanos) / 1_000_000L
        )
    }

    @MainThread
    internal fun ownsAction(action: String?): Boolean = active?.let {
        action == it.transitionAction || (action == it.seedAction && !it.seedFinished)
    } == true

    private fun checkPermissionWhileActive() {
        if (active != null && !hasPermission()) stopOnMain()
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACTIVITY_RECOGNITION
        } else {
            "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun stopOnMain() {
        val previous = active
        active = null
        reducer.stop()
        mutableObservations.value = null
        ActivityTransitionReceiver.detach(this)
        if (previous != null) {
            AppRepositories.navigation.updateActivity(false)
            finishSeed(previous)
            removeTransitions(previous)
        }
    }

    private fun finishSeed(registration: Registration) {
        if (registration.seedFinished) return
        registration.seedFinished = true
        registration.seedTimeout?.let(mainHandler::removeCallbacks)
        registration.seedTimeout = null
        if (registration.seedRequested) removeSeed(registration)
    }

    private fun removeTransitions(registration: Registration) {
        ActivityRegistrationCleanup.remove(appContext, registration.transitionAction, registration.transitionIntent)
    }

    private fun removeSeed(registration: Registration) {
        ActivityRegistrationCleanup.remove(appContext, registration.seedAction, registration.seedIntent)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) action() else mainHandler.post(action)
    }

    private inner class Registration(
        val activatedAtNanos: Long,
        val transitionAction: String,
        val seedAction: String
    ) {
        val transitionIntent = createIntent(transitionAction, 1)
        val seedIntent = createIntent(seedAction, 2)
        var transitionRegistered = false
        var seedRequested = false
        var seedFinished = false
        var seedTimeout: Runnable? = null
    }

    private fun createIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCode,
        Intent(appContext, ActivityTransitionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    private companion object {
        const val TAG = "ActivityRecognition"
    }
}
