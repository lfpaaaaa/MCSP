package au.edu.unimelb.campuscompanion.ui.profile

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.push.Notifications
import au.edu.unimelb.campuscompanion.sensing.location.LocationPermissionState
import au.edu.unimelb.campuscompanion.sensing.location.locationPermissionState

/** A permission the app asks for, with the reason shown on the Profile screen. */
enum class AppPermission(val title: String, val purpose: String) {
    Location(
        title = "Location",
        purpose = "Precise location provides travel times, departure guidance and GPS arrival around class times."
    ),
    BackgroundArrival(
        title = "Background arrival",
        purpose = "Optional location access lets Android detect arrival near your class building while the app is closed."
    ),
    Notifications(
        title = "Notifications",
        purpose = "Departure reminders and new messages in your groups."
    ),
    Camera(
        title = "Camera",
        purpose = "Photos for the group chat and scanning invitation QR codes."
    ),
    PhysicalActivity(
        title = "Physical activity",
        purpose = "Helps confirm walking, running or riding toward class so departure prompts can be withdrawn."
    );

    /** The runtime permissions to request for this entry, or none when the system handles it. */
    val runtimePermissions: List<String>
        get() = when (this) {
            Location -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            BackgroundArrival -> if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                emptyList()
            }
            Notifications -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyList()
            }
            Camera -> listOf(Manifest.permission.CAMERA)
            PhysicalActivity -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                listOf(Manifest.permission.ACTIVITY_RECOGNITION)
            } else {
                emptyList()
            }
        }
}

/** What the system currently allows for one [AppPermission]. */
data class PermissionStatus(
    val permission: AppPermission,
    val granted: Boolean,
    /** One line describing the state, such as "Precise location" or "Not allowed". */
    val detail: String
)

/** Reads the real permission state and opens the system pages where it can be changed. */
object AppPermissions {

    fun read(context: Context): List<PermissionStatus> {
        val locationState = locationPermissionState(context)
        return listOf(
            location(fine = locationState.fine, coarse = locationState.coarse),
            backgroundArrival(locationState),
            notifications(allowed = Notifications.canNotify(context)),
            camera(granted = context.has(Manifest.permission.CAMERA)),
            physicalActivity(
                granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    context.has(Manifest.permission.ACTIVITY_RECOGNITION)
            )
        )
    }

    internal fun location(fine: Boolean, coarse: Boolean) = PermissionStatus(
        permission = AppPermission.Location,
        granted = fine || coarse,
        detail = when {
            fine -> "Precise location"
            coarse -> "Approximate location only; timetable-only guidance, without fresh travel times or arrival detection"
            else -> "Not allowed; timetable-only guidance, without travel times or arrival detection"
        }
    )

    internal fun backgroundArrival(location: LocationPermissionState) = PermissionStatus(
        permission = AppPermission.BackgroundArrival,
        granted = location.canMonitorBackgroundArrival,
        detail = when {
            !location.fine && location.background && location.sdkInt >= 29 ->
                "Background access allowed; precise location is needed for arrival monitoring"
            !location.fine -> "Precise foreground location is needed first"
            location.sdkInt < 29 -> "Allowed with precise location on this Android version"
            location.background -> "Allowed all the time with precise location"
            else -> "Not allowed; GPS arrival stays available while foreground sensing is running"
        }
    )

    internal fun notifications(allowed: Boolean) = PermissionStatus(
        permission = AppPermission.Notifications,
        granted = allowed,
        detail = if (allowed) "Allowed" else "Not allowed; reminders and message alerts stay silent"
    )

    internal fun camera(granted: Boolean) = PermissionStatus(
        permission = AppPermission.Camera,
        granted = granted,
        detail = if (granted) "Allowed" else "Asked for when you first take a photo or scan a code"
    )

    internal fun physicalActivity(granted: Boolean) = PermissionStatus(
        permission = AppPermission.PhysicalActivity,
        granted = granted,
        detail = if (granted) "Allowed" else "Not allowed; motion sensors provide the walking fallback"
    )

    /** Separate education before the optional background request; foreground access comes first. */
    fun backgroundExplanation(context: Context): String {
        val state = locationPermissionState(context)
        if (!state.fine) {
            return "Background arrival monitoring needs precise location. Allow precise location first. " +
                "With approximate location, Campus Companion provides timetable-only guidance."
        }
        val settingsInstruction = if (state.sdkInt >= 30) {
            val option = context.packageManager.backgroundPermissionOptionLabel
            "In Settings, open Permissions, then Location, and choose “$option”. "
        } else {
            "Choose “Allow all the time” in the next Android permission dialog. "
        }
        return "Campus Companion uses background location to detect arrival near your class building " +
            "while the app is closed. $settingsInstruction" +
            "This is optional. You can keep using timetable guidance and GPS arrival while foreground sensing is running."
    }

    /** True when the system has granted [permission] (a `Manifest.permission` name) to this app. */
    fun isGranted(context: Context, permission: String): Boolean = context.has(permission)

    /**
     * After a request for [permission] was refused: true when the system will not show its
     * dialog again (the user chose "Don't allow" twice), so only the settings page can help.
     */
    fun isDeniedForGood(context: Context, permission: String): Boolean {
        val activity = context.findActivity() ?: return true
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    /** Opens the system page for this app's notifications, or its permissions for everything else. */
    fun openSettings(context: Context, permission: AppPermission) {
        val intent = if (permission == AppPermission.Notifications) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (notFound: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun Context.has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
