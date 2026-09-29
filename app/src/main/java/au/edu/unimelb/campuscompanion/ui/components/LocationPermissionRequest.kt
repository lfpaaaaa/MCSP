package au.edu.unimelb.campuscompanion.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun RequestLocationPermissionOnFirstUse() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
    }
    var shouldRequest by remember(context) {
        mutableStateOf(
            !context.hasLocationPermission() &&
                !preferences.getBoolean(KEY_LOCATION_PERMISSION_REQUESTED, false)
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        preferences.edit()
            .putBoolean(KEY_LOCATION_PERMISSION_REQUESTED, true)
            .apply()
        shouldRequest = false
    }

    LaunchedEffect(shouldRequest) {
        if (shouldRequest) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
}

private fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private const val PERMISSION_PREFERENCES = "permission_prompts"
private const val KEY_LOCATION_PERMISSION_REQUESTED = "location_permission_requested"
