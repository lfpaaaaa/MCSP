package au.edu.unimelb.campuscompanion.sensing.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.BuildConfig
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.activity.ActivityRecognitionManager
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrendDetector
import au.edu.unimelb.campuscompanion.sensing.location.EnRouteFusionDetector
import au.edu.unimelb.campuscompanion.sensing.location.EvidenceFreshness
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceManager
import au.edu.unimelb.campuscompanion.sensing.location.LocationTracker
import au.edu.unimelb.campuscompanion.sensing.location.LocationTrackingMode
import au.edu.unimelb.campuscompanion.sensing.location.MovementObservation
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.sensing.location.SensingTarget
import au.edu.unimelb.campuscompanion.sensing.location.bearingDegrees
import au.edu.unimelb.campuscompanion.sensing.motion.GyroscopeDetector
import au.edu.unimelb.campuscompanion.sensing.motion.MotionDetector
import au.edu.unimelb.campuscompanion.sensing.orientation.CompassHeadingDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.abs

class SensingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "campus_companion_sensing"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "au.edu.unimelb.campuscompanion.action.START_SENSING"
        const val ACTION_RECONCILE = "au.edu.unimelb.campuscompanion.action.RECONCILE_SENSING"
        const val ACTION_STOP = "au.edu.unimelb.campuscompanion.action.STOP_SENSING"
        const val ACTION_NORMAL_MODE = "au.edu.unimelb.campuscompanion.action.NORMAL_MODE"
        const val ACTION_PRE_CLASS_MODE = "au.edu.unimelb.campuscompanion.action.PRE_CLASS_MODE"
        private const val NAVIGATION_REQUEST_NANOS = 2 * 60 * 1_000_000_000L
        private const val NAVIGATION_TRIP_NANOS = 4 * 60 * 60 * 1_000_000_000L
        private const val MODE_NOTIFICATION_INTERVAL_NANOS = 500_000_000L
    }

    // Service commands, sensor registrations and both collectors share the main dispatcher.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val travel = AppRepositories.travel
    private lateinit var locationTracker: LocationTracker
    private lateinit var motionDetector: MotionDetector
    private lateinit var gyroscopeDetector: GyroscopeDetector
    private lateinit var compassHeadingDetector: CompassHeadingDetector
    private lateinit var activityRecognitionManager: ActivityRecognitionManager
    private lateinit var geofenceManager: GeofenceManager
    private val distanceTrendDetector = DistanceTrendDetector()
    private val enRouteFusionDetector = EnRouteFusionDetector()

    private var target: SensingTarget? = null
    private var sensingRunning = false
    private var launchAuthorized = false
    private var activationRequested = false
    private var foregroundPromoted = false
    private var currentMode = LocationTrackingMode.NORMAL
    private var navigationRequestUntilNanos = 0L
    private var navigationTripStartedAtNanos = 0L
    private var policySnapshot = TravelSnapshot.PENDING
    private var observedAccount = false
    private var observedAccountId: String? = null
    private var skipModeResetLocation = false
    private var sensorActivatedAtNanos = 0L
    private var lastLocationNanos = 0L
    private var lastNotificationPublishedAtNanos = 0L
    private var pendingModeNotificationText: String? = null
    private var modeNotificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationTracker = LocationTracker(this)
        motionDetector = MotionDetector(this)
        gyroscopeDetector = GyroscopeDetector(this)
        compassHeadingDetector = CompassHeadingDetector(this)
        activityRecognitionManager = ActivityRecognitionManager(this)
        geofenceManager = GeofenceManager(this)

        serviceScope.launch {
            // PENDING network results do not remove the independently published class target.
            combine(travel.sensingTarget, travel.sensingSession, travel.snapshot,
                AppRepositories.sensingAccountEligible, AppRepositories.navigation.state
            ) { _, _, _, _, _ -> Unit }
                .combine(AppRepositories.sensingAccountReady) { _, _ -> Unit }
                .collect { reconcilePolicy() }
        }
        observeLocation()
        observeActivity()
        // Clock boundaries, expired navigation requests and revocation need no location callback.
        serviceScope.launch {
            while (true) {
                delay(5_000L)
                reconcilePolicy()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // A foreground launch must be promoted even if permissions change before delivery.
            // Coarse access permits location promotion, but this demo samples only precise fixes.
            // With all location access revoked, shortService permits terminal cleanup on API 34+.
            if (!foregroundPromoted) {
                val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    !hasAnyLocationPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                ServiceCompat.startForeground(this, NOTIFICATION_ID,
                    buildNotification("Preparing sensing..."), foregroundType)
                foregroundPromoted = true
                lastNotificationPublishedAtNanos = SystemClock.elapsedRealtimeNanos()
            }
            if (intent?.action == ACTION_STOP) {
                stopServiceRun()
                return START_NOT_STICKY
            }
            // Existing directions callers get a renewable, bounded acquisition request. A sticky
            // restart has no old command/override and selects the timetable policy afresh.
            when (intent?.action) {
                ACTION_PRE_CLASS_MODE -> navigationRequestUntilNanos =
                    SystemClock.elapsedRealtimeNanos() + NAVIGATION_REQUEST_NANOS
                ACTION_NORMAL_MODE -> navigationRequestUntilNanos = 0L
            }
            launchAuthorized = true
            activationRequested = true
            reconcilePolicy()
        } catch (exception: SecurityException) {
            Log.w("SensingService", "Sensing permission unavailable", exception)
            stopForPermissionLoss()
            return START_NOT_STICKY
        }
        return if (launchAuthorized) START_STICKY else START_NOT_STICKY
    }

    private fun reconcilePolicy() {
        // Only an already delivered visible-activity launch (or Android's sticky restart) can
        // authorize this instance. Observers never start another foreground service.
        if (!launchAuthorized) return
        try {
            if (!hasPreciseLocationPermission()) {
                stopForPermissionLoss()
                return
            }
            // Cached authentication/timetable restoration may finish after onStartCommand.
            if (!AppRepositories.sensingAccountReady.value) return
            if (!AppRepositories.sensingAccountEligible.value) {
                stopServiceRun()
                return
            }
            val accountId = AppRepositories.sensingAccounts.currentAccountId
            if (observedAccount && accountId != observedAccountId) {
                navigationRequestUntilNanos = 0L
                navigationTripStartedAtNanos = 0L
            }
            observedAccount = true
            observedAccountId = accountId
            val nextTarget = if (activationRequested) travel.startSensing() else travel.sensingTarget.value
            activationRequested = false
            synchronizeTarget(nextTarget)
            if (!nextTarget.active) {
                stopServiceRun()
                return
            }
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val navigating = AppRepositories.navigation.state.value.active
            if (!navigating) navigationTripStartedAtNanos = 0L
            else if (navigationTripStartedAtNanos == 0L) navigationTripStartedAtNanos = nowNanos
            val navigationOverride = nowNanos < navigationRequestUntilNanos ||
                (navigating && nowNanos - navigationTripStartedAtNanos < NAVIGATION_TRIP_NANOS)
            // A temporary PENDING result while routing refreshes cannot erase a resolved policy
            // for this generation. The pure policy also checks occurrence times and destination.
            val snapshot = travel.snapshot.value
            if (!snapshot.pending) policySnapshot = snapshot
            when (SensingPolicy.mode(
                accountEligible = AppRepositories.sensingAccountEligible.value,
                precisePermission = hasPreciseLocationPermission(),
                session = travel.sensingSession.value,
                target = nextTarget,
                snapshot = policySnapshot,
                now = Instant.now(),
                highRateOverride = navigationOverride
            )) {
                SensingMode.STOP -> stopServiceRun()
                SensingMode.NORMAL -> startMode(LocationTrackingMode.NORMAL)
                SensingMode.PRE_CLASS -> startMode(LocationTrackingMode.PRE_CLASS)
            }
        } catch (exception: SecurityException) {
            Log.w("SensingService", "Permission changed during sensing reconciliation", exception)
            stopForPermissionLoss()
        }
    }

    private fun startMode(mode: LocationTrackingMode) {
        if (sensingRunning && currentMode == mode) {
            // Adapters preserve live windows/subscriptions and can retry a failed registration.
            locationTracker.startTracking(mode)
            startHighRateSensorsIfNeeded()
            startActivityRecognitionIfAllowed()
            registerArrivalGeofenceIfAllowed()
            return
        }
        sensingRunning = false
        skipModeResetLocation = true
        locationTracker.stopTracking()
        resetSensorHistories()
        activityRecognitionManager.stop()
        currentMode = mode
        sensingRunning = true
        // Clear full fusion and departure memory, keeping current position/guidance and arrival.
        travel.clearSensingEvidence()
        locationTracker.startTracking(mode)
        startHighRateSensorsIfNeeded()
        startActivityRecognitionIfAllowed()
        registerArrivalGeofenceIfAllowed()
        updateModeNotification()
        if (BuildConfig.DEBUG) Log.d("SensingService", "mode=$mode, generation=${target?.generation}")
    }

    private fun startHighRateSensorsIfNeeded() {
        if (sensingRunning && currentMode == LocationTrackingMode.PRE_CLASS && sensorActivatedAtNanos == 0L) {
            sensorActivatedAtNanos = SystemClock.elapsedRealtimeNanos()
            motionDetector.start()
            gyroscopeDetector.start()
            compassHeadingDetector.start()
        }
    }

    private fun resetSensorHistories() {
        motionDetector.stop()
        gyroscopeDetector.stop()
        compassHeadingDetector.stop()
        sensorActivatedAtNanos = 0L
        distanceTrendDetector.reset()
        enRouteFusionDetector.reset()
    }

    private fun synchronizeTarget(next: SensingTarget) {
        if (target?.generation == next.generation) return
        if (BuildConfig.DEBUG) {
            Log.d(
                "SensingFusion",
                "Reset sensing generation=${target?.generation}->${next.generation}, active=${next.active}, " +
                    "target=${next.sessionId}/${next.building?.locCode}"
            )
        }
        target = next
        policySnapshot = TravelSnapshot.PENDING
        lastLocationNanos = 0L
        // A pending initial fix belongs to the old sensing period. Restart acquisition so a
        // pre-activation result cannot consume the new target's one-shot without locating it.
        sensingRunning = false
        locationTracker.stopTracking()
        resetSensorHistories()
        activityRecognitionManager.stop()
        removeArrivalGeofence()
        if (!next.active) {
            stopComponents()
            return
        }
    }

    private fun observeActivity() {
        serviceScope.launch {
            activityRecognitionManager.observations.collect {
                if (!sensingRunning || currentMode != LocationTrackingMode.PRE_CLASS) return@collect
                reconcilePolicy()
                if (!sensingRunning || currentMode != LocationTrackingMode.PRE_CLASS) return@collect
                val currentTarget = target?.takeIf { it.active } ?: return@collect
                // EXIT, STILL and registration interruption must invalidate the old bundle even
                // between GPS callbacks. Confirmed trips and last-known guidance survive this.
                enRouteFusionDetector.reset()
                travel.updateEvidence(SensingEvidence(generation = currentTarget.generation, location = null))
            }
        }
    }

    private fun observeLocation() {
        serviceScope.launch {
            locationTracker.location.collect { location ->
                if (!sensingRunning) return@collect
                if (!hasPreciseLocationPermission()) {
                    stopForPermissionLoss()
                    return@collect
                }
                // The target is independent of PENDING snapshots and network request completion.
                reconcilePolicy()
                if (!sensingRunning) return@collect
                val currentTarget = target ?: return@collect
                if (location == null) {
                    if (skipModeResetLocation) {
                        skipModeResetLocation = false
                        return@collect
                    }
                    distanceTrendDetector.reset()
                    enRouteFusionDetector.reset()
                    if (currentTarget.active) {
                        travel.updateEvidence(SensingEvidence(generation = currentTarget.generation, location = null))
                    }
                    return@collect
                }
                skipModeResetLocation = false
                val now = SystemClock.elapsedRealtimeNanos()
                if (!EvidenceFreshness.accepts(location, currentTarget, now, lastLocationNanos)) {
                    if (BuildConfig.DEBUG) {
                        val reason = if (!currentTarget.active) "inactive" else
                            EvidenceFreshness.rejectionReason(location, now, lastLocationNanos, currentTarget.activatedAtNanos)
                        Log.d("SensingFusion", "rejected reason=$reason, generation=${currentTarget.generation}, " +
                            "ageMs=${(now - location.elapsedRealtimeNanos) / 1_000_000L}, accuracyM=${location.accuracyMeters}")
                    }
                    return@collect
                }

                val point = GeoPoint(location.latitude, location.longitude)
                val highRate = currentMode == LocationTrackingMode.PRE_CLASS
                val movement = motionDetector.classification.value?.takeIf {
                    highRate && isFreshSensor(it.timestampNanos, currentTarget, now)
                }
                val rotation = gyroscopeDetector.classification.value?.takeIf {
                    highRate && isFreshSensor(it.timestampNanos, currentTarget, now)
                }
                val heading = compassHeadingDetector.sample.value?.takeIf {
                    highRate && isFreshSensor(it.timestampNanos, currentTarget, now) &&
                        it.headingDegrees.isFinite()
                }?.headingDegrees
                val activity = activityRecognitionManager.observation?.takeIf {
                    highRate && hasActivityPermission() && activityRecognitionManager.isAvailable &&
                        it.observedAtNanos > currentTarget.activatedAtNanos && it.observedAtNanos <= now
                }

                var verdict: Boolean? = null
                var progressConfirmed = false
                var distanceMeters: Int? = null
                var trendName = "unavailable"
                var headingDifference: Double? = null
                currentTarget.building?.takeIf { highRate }?.let { building ->
                    val distance = GeoMath.distanceMeters(point, building.location)
                    val trend = distanceTrendDetector.update(distance, location.elapsedRealtimeNanos)
                    distanceMeters = distance.toInt()
                    trendName = trend.name
                    val closing = distanceTrendDetector.closingMeters()
                    val bearing = bearingDegrees(
                        location.latitude, location.longitude,
                        building.location.latitude, building.location.longitude
                    )
                    headingDifference = heading?.let {
                        calculateHeadingDifference(it.toDouble(), bearing)
                    }
                    verdict = enRouteFusionDetector.update(
                        isMoving = movement?.isMoving,
                        isRotating = rotation?.isRotating,
                        distanceTrend = trend,
                        headingDifferenceDegrees = headingDifference,
                        closingMeters = closing,
                        observedAtNanos = location.elapsedRealtimeNanos,
                        activity = activity?.state
                    ).verdict
                    progressConfirmed = distanceTrendDetector.sustainedApproach() && closing >= 50.0
                }
                // Position and its current-target verdict reach the engine in one update.
                val stateBefore = travel.snapshot.value.state
                val accepted = travel.updateEvidence(
                    SensingEvidence(
                        generation = currentTarget.generation,
                        location = location,
                        movement = movement?.let { MovementObservation(it.isMoving, it.timestampNanos) },
                        enRoute = verdict,
                        progressConfirmed = progressConfirmed,
                        activity = activity
                    )
                )
                if (accepted) {
                    lastLocationNanos = location.elapsedRealtimeNanos
                    AppRepositories.navigation.updateLocation(location)
                    if (BuildConfig.DEBUG) {
                        val source = when {
                            activity?.state?.isMoving != null -> "gps+activity"
                            movement != null -> "gps+accelerometer"
                            else -> "gps"
                        }
                        val ageMillis = (now - location.elapsedRealtimeNanos) / 1_000_000L
                        val stateAfter = travel.snapshot.value.state
                        // One compact line per accepted GPS observation, with no raw coordinates.
                        Log.d(
                            "SensingFusion",
                            "accepted source=$source, ageMs=$ageMillis, generation=${currentTarget.generation}, " +
                                "target=${currentTarget.sessionId}/${currentTarget.building?.locCode}, " +
                                "distanceM=$distanceMeters, trend=$trendName, moving=${movement?.isMoving}, " +
                                "activity=${activity?.state}, activityAvailable=${activityRecognitionManager.isAvailable}, " +
                                "headingDelta=$headingDifference, headingFresh=${heading != null}, rotating=${rotation?.isRotating}, " +
                                "verdict=$verdict, progressConfirmed=$progressConfirmed, " +
                                "state=$stateBefore->$stateAfter"
                        )
                    }
                }
            }
        }
    }

    private fun isFreshSensor(observedAtNanos: Long, currentTarget: SensingTarget, now: Long): Boolean =
        observedAtNanos >= currentTarget.activatedAtNanos &&
            observedAtNanos >= sensorActivatedAtNanos &&
            EvidenceFreshness.isFresh(observedAtNanos, now, EvidenceFreshness.SENSOR_MAX_AGE_NANOS)

    private fun calculateHeadingDifference(headingDegrees: Double, bearingDegrees: Double): Double {
        var difference = (headingDegrees - bearingDegrees) % 360.0
        if (difference > 180.0) difference -= 360.0
        if (difference < -180.0) difference += 360.0
        return abs(difference)
    }

    private fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAnyLocationPermission(): Boolean = hasPreciseLocationPermission() ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startActivityRecognitionIfAllowed() {
        val currentTarget = target?.takeIf {
            it.active && sensingRunning && currentMode == LocationTrackingMode.PRE_CLASS
        } ?: return
        if (hasActivityPermission()) {
            try {
                activityRecognitionManager.start(maxOf(currentTarget.activatedAtNanos, sensorActivatedAtNanos))
            } catch (exception: SecurityException) {
                Log.w("SensingService", "Activity recognition unavailable; retaining walking sensors", exception)
            }
        } else {
            activityRecognitionManager.stop()
        }
    }

    private fun hasActivityPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun registerArrivalGeofenceIfAllowed() {
        geofenceManager.setTarget(target?.takeIf { sensingRunning && it.active })
    }

    private fun removeArrivalGeofence() {
        geofenceManager.setTarget(null)
    }

    private fun stopComponents() {
        sensingRunning = false
        runCatching { locationTracker.stopTracking() }
            .onFailure { Log.w("SensingService", "Location cleanup failed", it) }
        resetSensorHistories()
        // Remove the PendingIntent subscription even when this instance did not create it.
        runCatching { activityRecognitionManager.stop() }
            .onFailure { Log.w("SensingService", "Activity cleanup failed", it) }
        removeArrivalGeofence()
    }

    private fun stopSensing() {
        stopComponents()
        travel.stopSensing()
        target = travel.sensingTarget.value
        lastLocationNanos = 0L
    }

    private fun stopForPermissionLoss() {
        Log.w("SensingService", "Precise location unavailable; stopping sensing")
        stopServiceRun()
    }

    private fun stopServiceRun() {
        launchAuthorized = false
        activationRequested = false
        cancelPendingModeNotification()
        navigationRequestUntilNanos = 0L
        navigationTripStartedAtNanos = 0L
        AppRepositories.navigation.stop()
        stopSensing()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundPromoted = false
        stopSelf()
    }

    private fun updateModeNotification() = updateNotification(
        if (currentMode == LocationTrackingMode.PRE_CLASS) "Monitoring your trip to class"
        else "Background sensing — battery saving mode"
    )

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Campus Companion")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Monitoring should be visible immediately, including the resolved sampling mode.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        // Android can shed burst notification updates. Keep one pending latest mode, separate
        // from the mandatory immediate promotion, and publish at most once per 500 ms.
        pendingModeNotificationText = text
        if (modeNotificationJob?.isActive == true) return
        modeNotificationJob = serviceScope.launch {
            val remainingNanos = (MODE_NOTIFICATION_INTERVAL_NANOS -
                (SystemClock.elapsedRealtimeNanos() - lastNotificationPublishedAtNanos)).coerceAtLeast(0L)
            if (remainingNanos > 0L) delay((remainingNanos + 999_999L) / 1_000_000L)
            val latest = pendingModeNotificationText ?: return@launch
            pendingModeNotificationText = null
            modeNotificationJob = null
            // A stopped/replaced service must never publish a queued mode or promote itself again.
            if (!launchAuthorized || !sensingRunning || !foregroundPromoted ||
                !travel.sensingTarget.value.active || !AppRepositories.sensingAccountEligible.value) return@launch
            try {
                if (!hasPreciseLocationPermission()) {
                    stopForPermissionLoss()
                    return@launch
                }
                // Keep mode updates on the same service notification path and owner.
                ServiceCompat.startForeground(this@SensingForegroundService, NOTIFICATION_ID,
                    buildNotification(latest), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                lastNotificationPublishedAtNanos = SystemClock.elapsedRealtimeNanos()
            } catch (exception: SecurityException) {
                Log.w("SensingService", "Permission changed during notification publication", exception)
                stopForPermissionLoss()
            }
        }
    }

    private fun cancelPendingModeNotification() {
        pendingModeNotificationText = null
        modeNotificationJob?.cancel()
        modeNotificationJob = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Campus Companion sensing", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps location and motion sensing active" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        launchAuthorized = false
        activationRequested = false
        cancelPendingModeNotification()
        AppRepositories.navigation.stop()
        stopSensing()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
