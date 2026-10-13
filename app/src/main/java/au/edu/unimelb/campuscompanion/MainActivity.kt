package au.edu.unimelb.campuscompanion

import android.Manifest
import android.app.AlertDialog
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
import androidx.lifecycle.Lifecycle
import au.edu.unimelb.campuscompanion.auth.AuthViewModel
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.push.NotificationTaps
import au.edu.unimelb.campuscompanion.sensing.service.SensingForegroundService
import au.edu.unimelb.campuscompanion.sensing.location.BackgroundLocationRequest
import au.edu.unimelb.campuscompanion.sensing.location.locationPermissionState
import au.edu.unimelb.campuscompanion.ui.CampusCompanionApp
import au.edu.unimelb.campuscompanion.ui.profile.AppPermission
import au.edu.unimelb.campuscompanion.ui.profile.AppPermissions

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val permissionPreferences by lazy { getSharedPreferences("permission_prompts", MODE_PRIVATE) }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("LocationPermission", "Optional background location granted=$granted")
        startSensingForegroundService()
    }

    // ---------------------------------------------------------
    // Notification permission
    // Android 13 / API 33+
    // ---------------------------------------------------------

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                Log.d(
                    "NotificationPermission",
                    "Notification permission granted"
                )

            } else {

                Log.w(
                    "NotificationPermission",
                    "Notification permission not granted"
                )
            }

            requestLocationPermissionIfNeeded()
        }

    // ---------------------------------------------------------
    // Location permission
    // ---------------------------------------------------------

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[
                    Manifest.permission.ACCESS_FINE_LOCATION
                ] == true

            val coarseGranted =
                permissions[
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ] == true

            if (fineGranted) {

                Log.d(
                    "LocationPermission",
                    "Precise location permission granted"
                )

                /*
                 * Location permission is available.
                 *
                 * We can now safely start the location
                 * foreground service.
                 */
                startSensingForegroundService()

            } else {

                Log.w(
                    "LocationPermission",
                    if (coarseGranted) "Approximate location: timetable-only guidance" else "Location permission not granted"
                )
                stopSensingForPermissionLoss()
            }

            /*
             * Continue permission chain.
             */
            requestActivityRecognitionPermissionIfNeeded()
        }

    // ---------------------------------------------------------
    // Activity Recognition permission
    // ---------------------------------------------------------

    private val activityRecognitionPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                Log.d(
                    "ActivityRecognition",
                    "Activity recognition permission granted"
                )

                /*
                 * Restart/update service after the permission
                 * becomes available so the service can register
                 * activity transitions.
                 */
                startSensingForegroundService()

            } else {

                Log.w(
                    "ActivityRecognition",
                    "Activity recognition permission not granted"
                )
            }
            explainBackgroundArrivalIfNeeded()
        }

    // ---------------------------------------------------------
    // onCreate
    // ---------------------------------------------------------

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        // ---------------------------------------------------------
        // Existing Deep Link logic
        // ---------------------------------------------------------

        SupabaseProvider.handleDeepLink(
            intent
        )

        if (
            savedInstanceState == null
        ) {

            AppRepositories
                .joinLinks
                .offer(
                    intent?.dataString
                )

            NotificationTaps.offer(intent)
        }

        // ---------------------------------------------------------
        // Runtime permission chain
        // ---------------------------------------------------------

        /*
         * Permission flow:
         *
         * Notification
         *      ↓
         * Location
         *      ↓
         * Foreground Service
         *      ↓
         * Activity Recognition
         */
        requestNotificationPermissionIfNeeded()

        // ---------------------------------------------------------
        // UI
        // ---------------------------------------------------------

        enableEdgeToEdge()

        setContent {

            CampusCompanionApp(
                authViewModel =
                    authViewModel
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // A grant or revocation in Settings is not delivered to a runtime-permission launcher.
        if (locationPermissionState(this).canStartSensing) {
            startSensingForegroundService()
        } else {
            stopSensingForPermissionLoss()
        }
    }

    // ---------------------------------------------------------
    // Notification permission
    // ---------------------------------------------------------

    private fun requestNotificationPermissionIfNeeded() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {

            requestLocationPermissionIfNeeded()

            return
        }

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) ==
                    PackageManager.PERMISSION_GRANTED

        if (granted) {

            Log.d(
                "NotificationPermission",
                "Notification permission already granted"
            )

            requestLocationPermissionIfNeeded()

        } else {

            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
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
            ) ==
                    PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED

        if (
            fineGranted ||
            coarseGranted
        ) {

            Log.d(
                "LocationPermission",
                "Location permission already granted"
            )

            /*
             * The service now owns all sensing.
             */
            startSensingForegroundService()

            /*
             * Continue permission chain.
             */
            requestActivityRecognitionPermissionIfNeeded()

        } else if (!permissionPreferences.getBoolean("location_permission_requested", false)) {

            permissionPreferences.edit().putBoolean("location_permission_requested", true).apply()
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            // A declined request remains optional; Profile can request it again or open Settings.
            requestActivityRecognitionPermissionIfNeeded()
        }
    }

    // ---------------------------------------------------------
    // Activity Recognition permission
    // ---------------------------------------------------------

    private fun requestActivityRecognitionPermissionIfNeeded() {

        /*
         * Runtime ACTIVITY_RECOGNITION permission
         * is required from Android 10 / API 29.
         */
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {

            startSensingForegroundService()
            explainBackgroundArrivalIfNeeded()

            return
        }

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) ==
                    PackageManager.PERMISSION_GRANTED

        if (granted) {

            Log.d(
                "ActivityRecognition",
                "Activity recognition permission already granted"
            )

            startSensingForegroundService()
            explainBackgroundArrivalIfNeeded()

        } else {

            activityRecognitionPermissionLauncher.launch(
                Manifest.permission.ACTIVITY_RECOGNITION
            )
        }
    }

    // ---------------------------------------------------------
    // Start foreground sensing service
    // ---------------------------------------------------------

    private fun startSensingForegroundService() {
        if (!locationPermissionState(this).canStartSensing) {

            Log.w(
                "SensingService",
                "Precise location unavailable; using timetable-only guidance"
            )

            return
        }
        // Permission callbacks can finish as the activity leaves the foreground.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        val serviceIntent =
            Intent(
                this,
                SensingForegroundService::class.java
            ).apply {

                /*
                 * Temporary testing mode.
                 *
                 * PRE_CLASS:
                 * - high-frequency GPS
                 * - accelerometer ON
                 * - gyroscope ON
                 * - compass ON
                 *
                 * Later the app can send NORMAL_MODE
                 * when high-frequency sensing is not needed.
                 */
                action =
                    SensingForegroundService.ACTION_PRE_CLASS_MODE
            }

        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (exception: SecurityException) {
            Log.w("SensingService", "Location permission changed before service start", exception)
            stopSensingForPermissionLoss()
            return
        } catch (exception: IllegalStateException) {
            Log.w("SensingService", "Foreground sensing start unavailable", exception)
            return
        }

        Log.d(
            "SensingService",
            "Requested PRE_CLASS foreground sensing mode"
        )
    }

    private fun stopSensingForPermissionLoss() {
        stopService(Intent(this, SensingForegroundService::class.java))
        // Do not leave an old precise ETA looking like approximate-location support.
        AppRepositories.travel.stopSensing()
    }

    private fun explainBackgroundArrivalIfNeeded() {
        val location = locationPermissionState(this)
        if (!location.fine || location.canMonitorBackgroundArrival ||
            permissionPreferences.getBoolean("background_arrival_explained", false) || isFinishing) return
        permissionPreferences.edit().putBoolean("background_arrival_explained", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Optional background arrival")
            .setMessage(AppPermissions.backgroundExplanation(this))
            .setNegativeButton("Not now") { _, _ -> }
            .setPositiveButton(if (location.backgroundRequest == BackgroundLocationRequest.Settings) "Open Settings" else "Continue") { _, _ ->
                when (locationPermissionState(this).backgroundRequest) {
                    BackgroundLocationRequest.RuntimePermission ->
                        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    BackgroundLocationRequest.Settings ->
                        AppPermissions.openSettings(this, AppPermission.BackgroundArrival)
                    else -> Unit
                }
            }
            .show()
    }

    // ---------------------------------------------------------
    // Deep Links
    // ---------------------------------------------------------

    override fun onNewIntent(
        intent: Intent
    ) {

        super.onNewIntent(
            intent
        )

        setIntent(
            intent
        )

        SupabaseProvider.handleDeepLink(
            intent
        )

        AppRepositories
            .joinLinks
            .offer(
                intent.dataString
            )

        NotificationTaps.offer(intent)
    }

    // ---------------------------------------------------------
    // Important
    // ---------------------------------------------------------

    /*
     * There is intentionally NO sensing cleanup in onDestroy().
     *
     * MainActivity no longer owns:
     *
     * - LocationTracker
     * - MotionDetector
     * - GyroscopeDetector
     * - CompassHeadingDetector
     *
     * The Foreground Service owns them and must continue
     * running when the Activity goes to the background.
     */
}
