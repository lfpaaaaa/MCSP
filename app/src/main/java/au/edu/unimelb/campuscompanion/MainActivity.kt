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
import au.edu.unimelb.campuscompanion.auth.AuthViewModel
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.sensing.service.SensingForegroundService
import au.edu.unimelb.campuscompanion.ui.CampusCompanionApp

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

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

            if (
                fineGranted ||
                coarseGranted
            ) {

                Log.d(
                    "LocationPermission",
                    "Location permission granted"
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
                    "Location permission not granted"
                )
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

        } else {

            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
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

        /*
         * Do not attempt to start a location foreground
         * service until a location permission exists.
         */
        if (
            !fineGranted &&
            !coarseGranted
        ) {

            Log.w(
                "SensingService",
                "Cannot start sensing service without location permission"
            )

            return
        }

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

        ContextCompat.startForegroundService(
            this,
            serviceIntent
        )

        Log.d(
            "SensingService",
            "Requested PRE_CLASS foreground sensing mode"
        )
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