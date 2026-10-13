package au.edu.unimelb.campuscompanion

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import au.edu.unimelb.campuscompanion.auth.AuthViewModel
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.push.NotificationTaps
import au.edu.unimelb.campuscompanion.sensing.location.BackgroundLocationRequest
import au.edu.unimelb.campuscompanion.sensing.location.locationPermissionState
import au.edu.unimelb.campuscompanion.sensing.service.SensingForegroundService
import au.edu.unimelb.campuscompanion.ui.CampusCompanionApp
import au.edu.unimelb.campuscompanion.ui.profile.AppPermission
import au.edu.unimelb.campuscompanion.ui.profile.AppPermissions
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()
    private val permissionPreferences by lazy { getSharedPreferences("permission_prompts", MODE_PRIVATE) }
    private var permissionChainComplete = false
    private var firstUsePermissionInFlight = false

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("LocationPermission", "Optional background location granted=$granted")
        reconcileSensingService()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        firstUsePermissionInFlight = false
        Log.d("NotificationPermission", "Notification permission granted=$granted")
        // Notifications remain optional: refusal does not block precise foreground sensing.
        requestLocationPermissionIfNeeded()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        firstUsePermissionInFlight = false
        val location = locationPermissionState(this)
        Log.d("LocationPermission", "Precise=${location.fine}, approximate=${location.coarse}")
        reconcileSensingService()
        requestActivityRecognitionPermissionIfNeeded()
    }

    private val activityRecognitionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        firstUsePermissionInFlight = false
        Log.d("ActivityRecognition", "Activity recognition permission granted=$granted")
        // A reconcile retries registrations without restarting an already authorized run.
        reconcileSensingService()
        permissionChainComplete = true
        explainBackgroundArrivalIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SupabaseProvider.handleDeepLink(intent)
        if (savedInstanceState == null) {
            AppRepositories.joinLinks.offer(intent?.dataString)
            NotificationTaps.offer(intent)
        }

        // MainActivity owns automatic first-use prompts; Profile retains explicit requests.
        // A restored launcher will deliver the outstanding result once STARTED; wait for it
        // before proceeding to the next permission, including after rotation during a dialog.
        firstUsePermissionInFlight = savedInstanceState?.getBoolean("first_use_permission_in_flight") ?: false
        if (!firstUsePermissionInFlight) requestNotificationPermissionIfNeeded()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // A saved session/timetable can become ready after onResume. The service itself
                // observes changes while running; only a visible Activity may initiate its start.
                combine(AppRepositories.sensingAccountEligible, AppRepositories.sensingAccountReady) { _, _ -> Unit }
                    .collect { reconcileSensingService() }
            }
        }

        enableEdgeToEdge()
        setContent { CampusCompanionApp(authViewModel = authViewModel) }
    }

    override fun onResume() {
        super.onResume()
        // Settings grants/revocations and explicit Profile requests do not use our launchers.
        reconcileSensingService()
        if (permissionChainComplete) explainBackgroundArrivalIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("first_use_permission_in_flight", firstUsePermissionInFlight)
        super.onSaveInstanceState(outState)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            AppPermissions.isGranted(this, Manifest.permission.POST_NOTIFICATIONS) ||
            wasRequested("notification_permission_requested")) {
            requestLocationPermissionIfNeeded()
        } else {
            markRequested("notification_permission_requested")
            firstUsePermissionInFlight = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        if (locationPermissionState(this).foregroundGranted || wasRequested("location_permission_requested")) {
            reconcileSensingService()
            requestActivityRecognitionPermissionIfNeeded()
        } else {
            markRequested("location_permission_requested")
            firstUsePermissionInFlight = true
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun requestActivityRecognitionPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            AppPermissions.isGranted(this, Manifest.permission.ACTIVITY_RECOGNITION) ||
            wasRequested("activity_permission_requested")) {
            reconcileSensingService()
            permissionChainComplete = true
            explainBackgroundArrivalIfNeeded()
        } else {
            markRequested("activity_permission_requested")
            firstUsePermissionInFlight = true
            activityRecognitionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun wasRequested(key: String): Boolean = permissionPreferences.getBoolean(key, false)
    private fun markRequested(key: String) { permissionPreferences.edit().putBoolean(key, true).apply() }

    private fun reconcileSensingService() {
        // A callback can arrive after the Activity leaves the foreground. It must not initiate
        // a location foreground-service start; readiness is rechecked at the next visible start.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val precise = locationPermissionState(this).canStartSensing
        // Initial auth restoration is not sign-out: an authorized sticky service may be waiting.
        if (precise && !AppRepositories.sensingAccountReady.value) return
        if (!precise || !AppRepositories.sensingAccountEligible.value) {
            stopService(Intent(this, SensingForegroundService::class.java))
            // Clear old precise guidance immediately, before asynchronous Service destruction.
            AppRepositories.travel.stopSensing()
            return
        }
        val serviceIntent = Intent(this, SensingForegroundService::class.java)
            .setAction(SensingForegroundService.ACTION_RECONCILE)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (exception: SecurityException) {
            Log.w("SensingService", "Location permission changed before service start", exception)
            stopService(serviceIntent)
            AppRepositories.travel.stopSensing()
        } catch (exception: IllegalStateException) {
            Log.w("SensingService", "Foreground sensing start unavailable", exception)
        }
    }

    private fun explainBackgroundArrivalIfNeeded() {
        val location = locationPermissionState(this)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !location.fine ||
            location.canMonitorBackgroundArrival || wasRequested("background_arrival_explained") || isFinishing) return
        markRequested("background_arrival_explained")
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SupabaseProvider.handleDeepLink(intent)
        AppRepositories.joinLinks.offer(intent.dataString)
        NotificationTaps.offer(intent)
    }

    // The service owns sensing and continues during an authorized screen-off trip.
    // Activity destruction therefore does not stop it.
}
