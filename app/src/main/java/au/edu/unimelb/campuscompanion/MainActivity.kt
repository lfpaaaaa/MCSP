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
import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.activity.ActivityRecognitionManager
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceManager
import au.edu.unimelb.campuscompanion.sensing.location.LocationTracker
import au.edu.unimelb.campuscompanion.sensing.location.LocationTrackingMode
import au.edu.unimelb.campuscompanion.sensing.motion.MotionDetector
import au.edu.unimelb.campuscompanion.ui.CampusCompanionApp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    private lateinit var locationTracker: LocationTracker
    private lateinit var motionDetector: MotionDetector
    private lateinit var geofenceManager: GeofenceManager
    private lateinit var activityRecognitionManager: ActivityRecognitionManager

    /** The building of the next class, which is where the arrival geofence is. */
    private var geofenceBuilding: BuildingLocation? = null

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
                geofenceBuilding?.let(::registerArrivalGeofence)
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

        activityRecognitionManager =
            ActivityRecognitionManager(this)

        // ---------------------------------------------------------
        // Sensors -> travel engine
        //
        // The engine (AppRepositories.travel) joins the timetable, the
        // position and the routing service; the screens read its snapshot.
        // ---------------------------------------------------------

        val travel = AppRepositories.travel

        lifecycleScope.launch {

            locationTracker.location.collectLatest { location ->

                if (location == null) {
                    return@collectLatest
                }

                travel.updateOrigin(
                    GeoPoint(
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
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

                travel.updateMoving(isMoving)

                Log.d(
                    "MotionDetector",
                    "isMoving=$isMoving"
                )
            }
        }

        // ---------------------------------------------------------
        // Travel engine -> arrival geofence
        // ---------------------------------------------------------

        lifecycleScope.launch {

            travel.snapshot.collectLatest { snapshot ->

                val building = snapshot.building

                if (building?.locCode != geofenceBuilding?.locCode) {
                    geofenceBuilding?.let { previous ->
                        geofenceManager.removeArrivalGeofence(previous.locCode)
                    }
                    geofenceBuilding = building

                    if (building != null && hasFineLocationPermission()) {
                        registerArrivalGeofence(building)
                    }
                }

                Log.d(
                    "TravelEngine",
                    "session=${snapshot.session?.code}, " +
                            "building=${building?.name}, " +
                            "distance=${snapshot.distanceMeters?.toInt()}m, " +
                            "eta=${snapshot.estimate?.durationMinutes}min, " +
                            "minutesUntilClass=${snapshot.minutesUntilClass}, " +
                            "state=${snapshot.state}"
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

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermissionIfNeeded() {

        val fineGranted = hasFineLocationPermission()

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

            geofenceBuilding?.let(::registerArrivalGeofence)
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

    private fun registerArrivalGeofence(building: BuildingLocation) {

        geofenceManager.addArrivalGeofence(
            id = building.locCode,
            latitude = building.location.latitude,
            longitude = building.location.longitude
        )

        Log.d(
            "Geofence",
            "Registering arrival geofence: ${building.locCode}"
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
