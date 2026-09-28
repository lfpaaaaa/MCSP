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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.building.BuildingLocationRepository
import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.activity.ActivityRecognitionManager
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrend
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrendDetector
import au.edu.unimelb.campuscompanion.sensing.location.EnRouteFusionDetector
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceManager
import au.edu.unimelb.campuscompanion.sensing.location.LocationTracker
import au.edu.unimelb.campuscompanion.sensing.location.LocationTrackingMode
import au.edu.unimelb.campuscompanion.sensing.location.TravelStateManager
import au.edu.unimelb.campuscompanion.sensing.location.bearingDegrees
import au.edu.unimelb.campuscompanion.sensing.motion.GyroscopeDetector
import au.edu.unimelb.campuscompanion.sensing.motion.MotionDetector
import au.edu.unimelb.campuscompanion.sensing.orientation.CompassHeadingDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class SensingForegroundService : Service() {

    companion object {

        private const val CHANNEL_ID =
            "campus_companion_sensing"

        private const val NOTIFICATION_ID =
            1001

        const val ACTION_START =
            "au.edu.unimelb.campuscompanion.action.START_SENSING"

        const val ACTION_STOP =
            "au.edu.unimelb.campuscompanion.action.STOP_SENSING"

        const val ACTION_NORMAL_MODE =
            "au.edu.unimelb.campuscompanion.action.NORMAL_MODE"

        const val ACTION_PRE_CLASS_MODE =
            "au.edu.unimelb.campuscompanion.action.PRE_CLASS_MODE"

        private const val TARGET_BUILDING_CODE =
            "PAR-160"
    }

    // ---------------------------------------------------------
    // Coroutine scope owned by the Service
    // ---------------------------------------------------------

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.Default
        )

    // ---------------------------------------------------------
    // Sensing components
    // ---------------------------------------------------------

    private lateinit var locationTracker:
            LocationTracker

    private lateinit var motionDetector:
            MotionDetector

    private lateinit var gyroscopeDetector:
            GyroscopeDetector

    private lateinit var compassHeadingDetector:
            CompassHeadingDetector

    private lateinit var activityRecognitionManager:
            ActivityRecognitionManager

    private lateinit var geofenceManager:
            GeofenceManager

    private lateinit var buildingRepository:
            BuildingLocationRepository

    // ---------------------------------------------------------
    // Fusion components
    // ---------------------------------------------------------

    private val travelStateManager =
        TravelStateManager()

    private val distanceTrendDetector =
        DistanceTrendDetector()

    private val enRouteFusionDetector =
        EnRouteFusionDetector()

    // ---------------------------------------------------------
    // Latest sensor state
    // ---------------------------------------------------------

    private var latestIsMoving =
        false

    private var latestIsRotating =
        false

    private var latestHeadingDegrees: Float? =
        null

    private var latestDistanceTrend =
        DistanceTrend.UNKNOWN

    private var latestStableEnRoute =
        false

    // ---------------------------------------------------------
    // Current sensing mode
    // ---------------------------------------------------------

    private var currentMode =
        LocationTrackingMode.NORMAL

    // ---------------------------------------------------------
    // onCreate
    // ---------------------------------------------------------

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()

        // ---------------------------------------------------------
        // Initialise sensing
        // ---------------------------------------------------------

        locationTracker =
            LocationTracker(this)

        motionDetector =
            MotionDetector(this)

        gyroscopeDetector =
            GyroscopeDetector(this)

        compassHeadingDetector =
            CompassHeadingDetector(this)

        activityRecognitionManager =
            ActivityRecognitionManager(this)

        geofenceManager =
            GeofenceManager(this)

        buildingRepository =
            BuildingLocationRepository(this)

        // ---------------------------------------------------------
        // Start collectors
        // ---------------------------------------------------------

        observeMotion()

        observeGyroscope()

        observeCompass()

        observeLocation()

        Log.d(
            "SensingService",
            "Foreground sensing service created"
        )
    }

    // ---------------------------------------------------------
    // Commands
    // ---------------------------------------------------------

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        /*
         * Promote to foreground immediately.
         */
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                "Preparing sensing..."
            )
        )

        when (
            intent?.action
        ) {

            ACTION_STOP -> {

                Log.d(
                    "SensingService",
                    "STOP command received"
                )

                stopSensing()

                stopForeground(
                    STOP_FOREGROUND_REMOVE
                )

                stopSelf()

                return START_NOT_STICKY
            }

            ACTION_NORMAL_MODE -> {

                startNormalMode()
            }

            ACTION_PRE_CLASS_MODE -> {

                startPreClassMode()
            }

            else -> {

                /*
                 * Safe default.
                 */
                startNormalMode()
            }
        }

        startActivityRecognitionIfAllowed()

        registerArrivalGeofenceIfAllowed()

        return START_STICKY
    }

    // ---------------------------------------------------------
    // NORMAL battery mode
    // ---------------------------------------------------------

    private fun startNormalMode() {

        currentMode =
            LocationTrackingMode.NORMAL

        Log.d(
            "SensingService",
            "Starting NORMAL sensing mode"
        )

        /*
         * Battery policy:
         *
         * NORMAL
         * - lower-frequency location
         * - accelerometer OFF
         * - gyroscope OFF
         * - compass OFF
         */

        locationTracker.stopTracking()

        locationTracker.startTracking(
            LocationTrackingMode.NORMAL
        )

        motionDetector.stop()

        gyroscopeDetector.stop()

        compassHeadingDetector.stop()

        /*
         * Old high-frequency fusion history should not
         * affect a future PRE_CLASS session.
         */
        latestIsMoving =
            false

        latestIsRotating =
            false

        latestHeadingDegrees =
            null

        latestDistanceTrend =
            DistanceTrend.UNKNOWN

        latestStableEnRoute =
            false

        distanceTrendDetector.reset()

        enRouteFusionDetector.reset()

        updateNotification(
            "Background sensing — battery saving mode"
        )
    }

    // ---------------------------------------------------------
    // PRE_CLASS battery mode
    // ---------------------------------------------------------

    private fun startPreClassMode() {

        currentMode =
            LocationTrackingMode.PRE_CLASS

        Log.d(
            "SensingService",
            "Starting PRE_CLASS sensing mode"
        )

        /*
         * PRE_CLASS
         * - high-frequency location
         * - accelerometer ON
         * - gyroscope ON
         * - compass ON
         *
         * These signals are needed for EN_ROUTE fusion.
         */

        locationTracker.stopTracking()

        locationTracker.startTracking(
            LocationTrackingMode.PRE_CLASS
        )

        motionDetector.start()

        gyroscopeDetector.start()

        compassHeadingDetector.start()

        updateNotification(
            "Monitoring your trip to class"
        )
    }

    // ---------------------------------------------------------
    // Accelerometer collector
    // ---------------------------------------------------------

    private fun observeMotion() {

        serviceScope.launch {

            motionDetector
                .isMoving
                .collectLatest { isMoving ->

                    latestIsMoving =
                        isMoving

                    Log.d(
                        "SensingService",
                        "Motion: isMoving=$isMoving"
                    )
                }
        }
    }

    // ---------------------------------------------------------
    // Gyroscope collector
    // ---------------------------------------------------------

    private fun observeGyroscope() {

        serviceScope.launch {

            gyroscopeDetector
                .isRotating
                .collectLatest { isRotating ->

                    latestIsRotating =
                        isRotating

                    Log.d(
                        "SensingService",
                        "Gyroscope: isRotating=$isRotating"
                    )
                }
        }
    }

    // ---------------------------------------------------------
    // Compass collector
    // ---------------------------------------------------------

    private fun observeCompass() {

        serviceScope.launch {

            compassHeadingDetector
                .headingDegrees
                .collectLatest { heading ->

                    if (
                        heading == null
                    ) {
                        return@collectLatest
                    }

                    latestHeadingDegrees =
                        heading

                    Log.d(
                        "SensingService",
                        "Compass: heading=${heading.toInt()}°"
                    )
                }
        }
    }

    // ---------------------------------------------------------
    // Location + fusion collector
    // ---------------------------------------------------------

    private fun observeLocation() {

        val building =
            buildingRepository
                .findByLocationCode(
                    TARGET_BUILDING_CODE
                )

        if (
            building == null
        ) {

            Log.e(
                "SensingService",
                "$TARGET_BUILDING_CODE not found"
            )

            return
        }

        Log.d(
            "SensingService",
            "Target building: " +
                    "${building.name}, " +
                    "code=${building.locCode}"
        )

        serviceScope.launch {

            locationTracker
                .location
                .collectLatest { location ->

                    if (
                        location == null
                    ) {
                        return@collectLatest
                    }

                    // -------------------------------------------------
                    // Current location
                    // -------------------------------------------------

                    val currentPoint =
                        GeoPoint(
                            latitude =
                                location.latitude,
                            longitude =
                                location.longitude
                        )

                    // -------------------------------------------------
                    // Distance
                    // -------------------------------------------------

                    val distance =
                        GeoMath.distanceMeters(
                            from =
                                currentPoint,
                            to =
                                building.location
                        )

                    // -------------------------------------------------
                    // Travel state
                    // -------------------------------------------------

                    travelStateManager.update(
                        distanceMeters =
                            distance,
                        isMoving =
                            latestIsMoving
                    )

                    val state =
                        travelStateManager
                            .state
                            .value

                    // -------------------------------------------------
                    // Distance trend
                    // -------------------------------------------------

                    latestDistanceTrend =
                        if (
                            currentMode ==
                            LocationTrackingMode.PRE_CLASS
                        ) {

                            distanceTrendDetector.update(
                                distanceMeters =
                                    distance
                            )

                        } else {

                            /*
                             * Distance trend fusion is not needed
                             * in battery-saving NORMAL mode.
                             */
                            DistanceTrend.UNKNOWN
                        }

                    // -------------------------------------------------
                    // Bearing
                    // -------------------------------------------------

                    val bearing =
                        bearingDegrees(
                            location.latitude,
                            location.longitude,
                            building.location.latitude,
                            building.location.longitude
                        )

                    // -------------------------------------------------
                    // Heading difference
                    // -------------------------------------------------

                    val heading =
                        latestHeadingDegrees

                    val headingDifference =
                        if (
                            heading != null
                        ) {

                            calculateHeadingDifference(
                                headingDegrees =
                                    heading.toDouble(),
                                bearingDegrees =
                                    bearing
                            )

                        } else {

                            null
                        }

                    // -------------------------------------------------
                    // EN_ROUTE fusion
                    // -------------------------------------------------

                    if (
                        currentMode ==
                        LocationTrackingMode.PRE_CLASS
                    ) {

                        val fusionResult =
                            enRouteFusionDetector.update(
                                isMoving =
                                    latestIsMoving,
                                isRotating =
                                    latestIsRotating,
                                distanceTrend =
                                    latestDistanceTrend,
                                headingDifferenceDegrees =
                                    headingDifference
                            )

                        latestStableEnRoute =
                            fusionResult.stableEnRoute

                        Log.d(
                            "SensingFusion",
                            "distance=${distance.toInt()}m, " +
                                    "trend=$latestDistanceTrend, " +
                                    "bearing=${bearing.toInt()}°, " +
                                    "heading=" +
                                    "${heading?.toInt() ?: "unknown"}°, " +
                                    "headingDifference=" +
                                    "${headingDifference?.toInt() ?: "unknown"}°, " +
                                    "moving=$latestIsMoving, " +
                                    "rotating=$latestIsRotating, " +
                                    "rawEnRoute=${fusionResult.rawEnRoute}, " +
                                    "stableEnRoute=${fusionResult.stableEnRoute}, " +
                                    "state=$state"
                        )

                    } else {

                        Log.d(
                            "SensingFusion",
                            "NORMAL mode: " +
                                    "distance=${distance.toInt()}m, " +
                                    "state=$state"
                        )
                    }
                }
        }
    }

    // ---------------------------------------------------------
    // Heading difference
    // ---------------------------------------------------------

    private fun calculateHeadingDifference(
        headingDegrees: Double,
        bearingDegrees: Double
    ): Double {

        var difference =
            headingDegrees -
                    bearingDegrees

        while (
            difference >
            180.0
        ) {

            difference -=
                360.0
        }

        while (
            difference <
            -180.0
        ) {

            difference +=
                360.0
        }

        return abs(
            difference
        )
    }

    // ---------------------------------------------------------
    // Activity Recognition
    // ---------------------------------------------------------

    private fun startActivityRecognitionIfAllowed() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            activityRecognitionManager.start()

            return
        }

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) ==
                    PackageManager.PERMISSION_GRANTED

        if (granted) {

            activityRecognitionManager.start()

            Log.d(
                "SensingService",
                "Activity Recognition started"
            )

        } else {

            Log.w(
                "SensingService",
                "Activity Recognition permission unavailable"
            )
        }
    }

    // ---------------------------------------------------------
    // Arrival geofence
    // ---------------------------------------------------------

    private fun registerArrivalGeofenceIfAllowed() {

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED

        if (
            !fineGranted
        ) {

            Log.w(
                "SensingService",
                "Fine location unavailable; geofence not registered"
            )

            return
        }

        val building =
            buildingRepository
                .findByLocationCode(
                    TARGET_BUILDING_CODE
                )
                ?: return

        geofenceManager
            .addArrivalGeofence(
                id =
                    building.locCode,
                latitude =
                    building.location.latitude,
                longitude =
                    building.location.longitude
            )

        Log.d(
            "SensingService",
            "Arrival geofence registered: ${building.locCode}"
        )
    }

    // ---------------------------------------------------------
    // Notification
    // ---------------------------------------------------------

    private fun buildNotification(
        text: String
    ): Notification {

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "Campus Companion"
            )
            .setContentText(
                text
            )
            .setSmallIcon(
                R.mipmap.ic_launcher
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    private fun updateNotification(
        text: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                text
            )
        )
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Campus Companion sensing",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Keeps location and motion sensing active"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    // ---------------------------------------------------------
    // Stop sensing
    // ---------------------------------------------------------

    private fun stopSensing() {

        Log.d(
            "SensingService",
            "Stopping all sensing"
        )

        locationTracker.stopTracking()

        motionDetector.stop()

        gyroscopeDetector.stop()

        compassHeadingDetector.stop()

        activityRecognitionManager.stop()

        distanceTrendDetector.reset()

        enRouteFusionDetector.reset()

        latestIsMoving =
            false

        latestIsRotating =
            false

        latestHeadingDegrees =
            null

        latestDistanceTrend =
            DistanceTrend.UNKNOWN

        latestStableEnRoute =
            false
    }

    // ---------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------

    override fun onDestroy() {

        stopSensing()

        serviceScope.cancel()

        Log.d(
            "SensingService",
            "Foreground sensing service destroyed"
        )

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}