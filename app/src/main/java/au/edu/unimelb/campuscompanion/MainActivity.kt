package au.edu.unimelb.campuscompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import au.edu.unimelb.campuscompanion.auth.AuthViewModel
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.building.BuildingLocationRepository
import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.activity.ActivityRecognitionManager
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceManager
import au.edu.unimelb.campuscompanion.sensing.location.LocationTracker
import au.edu.unimelb.campuscompanion.sensing.location.LocationTrackingMode
import au.edu.unimelb.campuscompanion.sensing.location.TravelStateManager
import au.edu.unimelb.campuscompanion.sensing.location.bearingDegrees
import au.edu.unimelb.campuscompanion.sensing.motion.MotionDetector
import au.edu.unimelb.campuscompanion.ui.CampusCompanionApp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    private lateinit var locationTracker: LocationTracker
    private lateinit var motionDetector: MotionDetector
    private lateinit var geofenceManager: GeofenceManager
    private lateinit var buildingRepository: BuildingLocationRepository
    private lateinit var activityRecognitionManager: ActivityRecognitionManager

    private val travelStateManager =
        TravelStateManager()

    private var latestIsMoving: Boolean = false

    // ---------------------------------------------------------
    // Location permission
    // ---------------------------------------------------------

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            val coarseGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (fineGranted || coarseGranted) {
                locationTracker.startTracking(
                    LocationTrackingMode.PRE_CLASS
                )
            }

            if (fineGranted) {
                registerArrivalGeofence()
            } else {
                Log.w(
                    "Geofence",
                    "Fine location permission not granted; geofence not registered"
                )
            }
        }

    // ---------------------------------------------------------
    // Activity Recognition permission
    // IMPORTANT: this must be a class property, not inside onCreate()
    // ---------------------------------------------------------

    private val activityRecognitionPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                activityRecognitionManager.start()

                Log.d(
                    "ActivityRecognition",
                    "Activity recognition permission granted"
                )
            } else {
                Log.w(
                    "ActivityRecognition",
                    "Activity recognition permission not granted"
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---------------------------------------------------------
        // Initialise sensing components
        // ---------------------------------------------------------

        locationTracker =
            LocationTracker(this)

        motionDetector =
            MotionDetector(this)

        motionDetector.start()

        geofenceManager =
            GeofenceManager(this)

        buildingRepository =
            BuildingLocationRepository(this)

        activityRecognitionManager =
            ActivityRecognitionManager(this)

        // ---------------------------------------------------------
        // Temporary target building
        // ---------------------------------------------------------

        val building =
            buildingRepository.findByLocationCode("PAR-160")

        Log.d(
            "BuildingRepository",
            if (building != null) {
                "Found: ${building.name}, " +
                        "code=${building.locCode}, " +
                        "lat=${building.location.latitude}, " +
                        "lon=${building.location.longitude}, " +
                        "address=${building.address}"
            } else {
                "PAR-160 not found"
            }
        )

        // ---------------------------------------------------------
        // GPS / location pipeline
        // ---------------------------------------------------------

        lifecycleScope.launch {

            locationTracker.location.collectLatest { location ->

                if (location == null || building == null) {
                    return@collectLatest
                }

                val currentPoint =
                    GeoPoint(
                        latitude = location.latitude,
                        longitude = location.longitude
                    )

                val distance =
                    GeoMath.distanceMeters(
                        from = currentPoint,
                        to = building.location
                    )

                travelStateManager.update(
                    distanceMeters = distance,
                    isMoving = latestIsMoving
                )

                val state =
                    travelStateManager.state.value

                val bearing =
                    bearingDegrees(
                        location.latitude,
                        location.longitude,
                        building.location.latitude,
                        building.location.longitude
                    )

                Log.d(
                    "LocationTracker",
                    "building=${building.name}, " +
                            "lat=${location.latitude}, " +
                            "lon=${location.longitude}, " +
                            "accuracy=${location.accuracyMeters}, " +
                            "distance=${distance.toInt()}m, " +
                            "bearing=${bearing.toInt()}°, " +
                            "state=$state"
                )
            }
        }

        // ---------------------------------------------------------
        // Accelerometer raw samples
        // ---------------------------------------------------------

        lifecycleScope.launch {

            motionDetector.motionSample.collectLatest { sample ->

                if (sample == null) {
                    return@collectLatest
                }

                Log.d(
                    "MotionDetector",
                    "x=${sample.x}, " +
                            "y=${sample.y}, " +
                            "z=${sample.z}, " +
                            "magnitude=${sample.magnitude}"
                )
            }
        }

        // ---------------------------------------------------------
        // Accelerometer movement classifier
        // ---------------------------------------------------------

        lifecycleScope.launch {

            motionDetector.isMoving.collectLatest { isMoving ->

                latestIsMoving = isMoving

                Log.d(
                    "MotionDetector",
                    "isMoving=$isMoving"
                )
            }
        }

        // ---------------------------------------------------------
        // Existing app logic
        // ---------------------------------------------------------

        SupabaseProvider.handleDeepLink(intent)

        if (savedInstanceState == null) {
            AppRepositories.joinLinks.offer(
                intent?.dataString
            )
        }

        // ---------------------------------------------------------
        // Runtime permissions / sensing startup
        // ---------------------------------------------------------

        requestLocationPermissionIfNeeded()

        requestActivityRecognitionPermissionIfNeeded()

        // ---------------------------------------------------------
        // UI
        // ---------------------------------------------------------

        enableEdgeToEdge()

        setContent {
            CampusCompanionApp(
                authViewModel = authViewModel
            )
        }
    }

    // ---------------------------------------------------------
    // Location permission
    // ---------------------------------------------------------

    private fun requestLocationPermissionIfNeeded() {

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {

            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )

        } else {

            // PRE_CLASS is currently used for higher-frequency
            // location testing.
            locationTracker.startTracking(
                LocationTrackingMode.PRE_CLASS
            )

            registerArrivalGeofence()
        }

        // Approximate location can still support basic tracking,
        // but we do not register the geofence without fine location.
        if (!fineGranted && coarseGranted) {
            locationTracker.startTracking(
                LocationTrackingMode.PRE_CLASS
            )
        }
    }

    // ---------------------------------------------------------
    // Geofence registration
    // ---------------------------------------------------------

    private fun registerArrivalGeofence() {

        val building =
            buildingRepository.findByLocationCode("PAR-160")
                ?: run {
                    Log.e(
                        "Geofence",
                        "PAR-160 not found; geofence not registered"
                    )
                    return
                }

        geofenceManager.addArrivalGeofence(
            id = building.locCode,
            latitude = building.location.latitude,
            longitude = building.location.longitude
        )

        Log.d(
            "Geofence",
            "Registering arrival geofence: " +
                    "${building.locCode}, " +
                    "lat=${building.location.latitude}, " +
                    "lon=${building.location.longitude}"
        )
    }

    // ---------------------------------------------------------
    // Activity Recognition permission
    // ---------------------------------------------------------

    private fun requestActivityRecognitionPermissionIfNeeded() {

        /*
         * ACTIVITY_RECOGNITION became a runtime permission
         * from Android 10 / API 29.
         */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {

            activityRecognitionManager.start()

            Log.d(
                "ActivityRecognition",
                "Android below API 29; starting without runtime permission"
            )

            return
        }

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED

        if (granted) {

            activityRecognitionManager.start()

        } else {

            activityRecognitionPermissionLauncher.launch(
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        }
    }

    // ---------------------------------------------------------
    // Deep links
    // ---------------------------------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)

        SupabaseProvider.handleDeepLink(intent)

        AppRepositories.joinLinks.offer(
            intent.dataString
        )
    }

    // ---------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------

    override fun onDestroy() {

        locationTracker.stopTracking()

        motionDetector.stop()

        activityRecognitionManager.stop()

        super.onDestroy()
    }
}