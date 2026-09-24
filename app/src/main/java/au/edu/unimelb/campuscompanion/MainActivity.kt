package au.edu.unimelb.campuscompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.auth.AuthViewModel
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.sensing.location.LocationTracker
import au.edu.unimelb.campuscompanion.ui.CampusCompanionApp
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import au.edu.unimelb.campuscompanion.sensing.location.distanceMeters
import au.edu.unimelb.campuscompanion.sensing.location.bearingDegrees
import au.edu.unimelb.campuscompanion.sensing.location.LocationTrackingMode

class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    private lateinit var locationTracker: LocationTracker

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val fineGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            val coarseGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (fineGranted || coarseGranted) {
                locationTracker.startTracking()
                //locationTracker.startTracking(
                //    LocationTrackingMode.PRE_CLASS
                //)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationTracker = LocationTracker(this)

        lifecycleScope.launch {
            locationTracker.location.collectLatest { location ->
                if (location != null) {

                    val targetLat = -37.7963
                    val targetLon = 144.9614

                    val distance =
                        distanceMeters(
                            location.latitude,
                            location.longitude,
                            targetLat,
                            targetLon
                        )

                    val bearing =
                        bearingDegrees(
                            location.latitude,
                            location.longitude,
                            targetLat,
                            targetLon
                        )

                    Log.d(
                        "LocationTracker",
                        "lat=${location.latitude}, " +
                                "lon=${location.longitude}, " +
                                "accuracy=${location.accuracyMeters}, " +
                                "distance=${distance.toInt()}m, " +
                                "bearing=${bearing.toInt()}°"
                    )
                }
            }
        }

        SupabaseProvider.handleDeepLink(intent)

        requestLocationPermissionIfNeeded()

        enableEdgeToEdge()

        setContent {
            CampusCompanionApp(
                authViewModel = authViewModel
            )
        }
    }

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

        if (!fineGranted && !coarseGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            locationTracker.startTracking()
            //locationTracker.startTracking(
            //    LocationTrackingMode.PRE_CLASS
            //)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)
        SupabaseProvider.handleDeepLink(intent)
    }

    override fun onDestroy() {
        locationTracker.stopTracking()
        super.onDestroy()
    }
}