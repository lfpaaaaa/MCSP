package au.edu.unimelb.campuscompanion.sensing.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.BuildConfig
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

class SensingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "campus_companion_sensing"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "au.edu.unimelb.campuscompanion.action.START_SENSING"
        const val ACTION_STOP = "au.edu.unimelb.campuscompanion.action.STOP_SENSING"
        const val ACTION_NORMAL_MODE = "au.edu.unimelb.campuscompanion.action.NORMAL_MODE"
        const val ACTION_PRE_CLASS_MODE = "au.edu.unimelb.campuscompanion.action.PRE_CLASS_MODE"
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
    private var geofenceBuilding: BuildingLocation? = null
    private var geofenceRequested = false
    private var sensingRunning = false
    private var activityRecognitionRequested = false
    private var currentMode = LocationTrackingMode.NORMAL
    private var sensorActivatedAtNanos = 0L
    private var lastLocationNanos = 0L

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
            travel.sensingTarget.collect { synchronizeTarget(it) }
        }
        observeLocation()
        // Revocation may stop location callbacks altogether, so check while the service is active.
        serviceScope.launch {
            while (true) {
                delay(5_000L)
                if (sensingRunning && !hasPreciseLocationPermission()) {
                    stopForPermissionLoss()
                } else if (sensingRunning && activityRecognitionRequested && !hasActivityPermission()) {
                    // Activity remains an optional adapter in Step 1; its loss keeps walking GPS active.
                    runCatching { activityRecognitionManager.stop() }
                        .onFailure { Log.w("SensingService", "Activity cleanup failed after revocation", it) }
                    activityRecognitionRequested = false
                    Log.w("SensingService", "Activity permission revoked; walking GPS remains active")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSensing()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasPreciseLocationPermission()) {
            stopForPermissionLoss()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Preparing sensing..."))
            val mode = if (intent?.action == ACTION_PRE_CLASS_MODE) {
                LocationTrackingMode.PRE_CLASS
            } else {
                LocationTrackingMode.NORMAL
            }
            startMode(mode)
            startActivityRecognitionIfAllowed()
            registerArrivalGeofenceIfAllowed()
        } catch (exception: SecurityException) {
            Log.w("SensingService", "Sensing permission unavailable", exception)
            stopForPermissionLoss()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startMode(mode: LocationTrackingMode) {
        synchronizeTarget(travel.startSensing())
        if (sensingRunning && currentMode == mode) {
            // The adapter is idempotent while registered, and retries an interrupted registration.
            locationTracker.startTracking(mode)
            updateModeNotification()
            return
        }
        locationTracker.stopTracking()
        resetSensorHistories()
        currentMode = mode
        sensingRunning = true
        // A mode change makes the old raw verdict unavailable while preserving advisory guidance.
        target?.takeIf { it.active }?.let {
            travel.updateEvidence(SensingEvidence(generation = it.generation, location = null))
        }
        locationTracker.startTracking(mode)
        startHighRateSensorsIfNeeded()
        updateModeNotification()
    }

    private fun startHighRateSensorsIfNeeded() {
        if (sensingRunning && currentMode == LocationTrackingMode.PRE_CLASS) {
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
        lastLocationNanos = 0L
        resetSensorHistories()
        removeArrivalGeofence()
        geofenceBuilding = next.building.takeIf { next.active }
        if (!next.active) {
            stopComponents()
            return
        }
        startHighRateSensorsIfNeeded()
        registerArrivalGeofenceIfAllowed()
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
                synchronizeTarget(travel.sensingTarget.value)
                val currentTarget = target ?: return@collect
                if (location == null) {
                    distanceTrendDetector.reset()
                    enRouteFusionDetector.reset()
                    if (currentTarget.active) {
                        travel.updateEvidence(SensingEvidence(generation = currentTarget.generation, location = null))
                    }
                    return@collect
                }
                val now = SystemClock.elapsedRealtimeNanos()
                if (!EvidenceFreshness.accepts(location, currentTarget, now, lastLocationNanos)) {
                    if (BuildConfig.DEBUG) {
                        Log.d("SensingFusion", "Rejected location for generation=${currentTarget.generation}")
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

                var verdict: Boolean? = null
                var progressConfirmed = false
                var distanceMeters: Int? = null
                var trendName = "unavailable"
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
                    val headingDifference = heading?.let {
                        calculateHeadingDifference(it.toDouble(), bearing)
                    }
                    verdict = enRouteFusionDetector.update(
                        isMoving = movement?.isMoving,
                        isRotating = rotation?.isRotating,
                        distanceTrend = trend,
                        headingDifferenceDegrees = headingDifference,
                        closingMeters = closing,
                        observedAtNanos = location.elapsedRealtimeNanos
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
                        progressConfirmed = progressConfirmed
                    )
                )
                if (accepted) {
                    lastLocationNanos = location.elapsedRealtimeNanos
                    AppRepositories.navigation.updateLocation(location)
                    if (BuildConfig.DEBUG) {
                        val source = if (movement != null) "gps+accelerometer" else "gps"
                        val ageMillis = (now - location.elapsedRealtimeNanos) / 1_000_000L
                        val stateAfter = travel.snapshot.value.state
                        // One compact line per accepted GPS observation, with no raw coordinates.
                        Log.d(
                            "SensingFusion",
                            "accepted source=$source, ageMs=$ageMillis, generation=${currentTarget.generation}, " +
                                "target=${currentTarget.sessionId}/${currentTarget.building?.locCode}, " +
                                "distanceM=$distanceMeters, trend=$trendName, moving=${movement?.isMoving}, " +
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

    private fun startActivityRecognitionIfAllowed() {
        if (hasActivityPermission() && !activityRecognitionRequested) {
            try {
                activityRecognitionManager.start()
                activityRecognitionRequested = true
            } catch (exception: SecurityException) {
                Log.w("SensingService", "Activity recognition unavailable; retaining walking sensors", exception)
            }
        }
    }

    private fun hasActivityPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    // Registration identity and receiver-driven arrival are completed in Step 3.
    private fun registerArrivalGeofenceIfAllowed() {
        if (!sensingRunning || geofenceRequested || !hasPreciseLocationPermission()) return
        val building = geofenceBuilding ?: return
        try {
            geofenceManager.addArrivalGeofence(
                id = building.locCode,
                latitude = building.location.latitude,
                longitude = building.location.longitude
            )
            geofenceRequested = true
        } catch (exception: SecurityException) {
            Log.w("SensingService", "Geofence unavailable; retaining foreground GPS", exception)
        }
    }

    private fun removeArrivalGeofence() {
        if (geofenceRequested) geofenceBuilding?.let { building ->
            runCatching { geofenceManager.removeArrivalGeofence(building.locCode) }
                .onFailure { Log.w("SensingService", "Geofence cleanup failed", it) }
        }
        geofenceRequested = false
        geofenceBuilding = null
    }

    private fun stopComponents() {
        sensingRunning = false
        runCatching { locationTracker.stopTracking() }
            .onFailure { Log.w("SensingService", "Location cleanup failed", it) }
        resetSensorHistories()
        // Remove the PendingIntent subscription even when this instance did not create it.
        runCatching { activityRecognitionManager.stop() }
            .onFailure { Log.w("SensingService", "Activity cleanup failed", it) }
        activityRecognitionRequested = false
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
        stopSensing()
        stopForeground(STOP_FOREGROUND_REMOVE)
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
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
        AppRepositories.navigation.stop()
        stopSensing()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
