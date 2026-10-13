package au.edu.unimelb.campuscompanion.sensing.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** The demo accepts precise fixes only; coarse permission cannot enable its sensing service. */
data class LocationPermissionState(
    val fine: Boolean,
    val coarse: Boolean,
    val background: Boolean,
    val sdkInt: Int
) {
    val foregroundGranted: Boolean get() = fine || coarse
    val canStartSensing: Boolean get() = fine
    val canMonitorBackgroundArrival: Boolean get() = fine && (sdkInt < 29 || background)

    val backgroundRequest: BackgroundLocationRequest
        get() = when {
            !fine -> BackgroundLocationRequest.PreciseForegroundRequired
            sdkInt < 29 || background -> BackgroundLocationRequest.AlreadyAllowed
            sdkInt == 29 -> BackgroundLocationRequest.RuntimePermission
            else -> BackgroundLocationRequest.Settings
        }
}

enum class BackgroundLocationRequest {
    PreciseForegroundRequired,
    AlreadyAllowed,
    RuntimePermission,
    Settings
}

fun locationPermissionState(context: Context): LocationPermissionState = LocationPermissionState(
    fine = context.has(Manifest.permission.ACCESS_FINE_LOCATION),
    coarse = context.has(Manifest.permission.ACCESS_COARSE_LOCATION),
    background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        context.has(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    sdkInt = Build.VERSION.SDK_INT
)

fun hasLocationPermission(context: Context): Boolean = locationPermissionState(context).foregroundGranted

private fun Context.has(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
