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

/** A permission the app asks for, with the reason shown on the Profile screen. */
enum class AppPermission(val title: String, val purpose: String) {
    Location(
        title = "Location",
        purpose = "How far you are from your next class and when you have arrived, read on this phone " +
            "around class times."
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
        purpose = "Tells walking from riding, so travel times use the right mode."
    );

    /** The runtime permissions to request for this entry, or none when the system handles it. */
    val runtimePermissions: List<String>
        get() = when (this) {
            Location -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
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

    fun read(context: Context): List<PermissionStatus> = listOf(
        location(
            fine = context.has(Manifest.permission.ACCESS_FINE_LOCATION),
            coarse = context.has(Manifest.permission.ACCESS_COARSE_LOCATION)
        ),
        notifications(allowed = Notifications.canNotify(context)),
        camera(granted = context.has(Manifest.permission.CAMERA)),
        physicalActivity(
            granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                context.has(Manifest.permission.ACTIVITY_RECOGNITION)
        )
    )

    internal fun location(fine: Boolean, coarse: Boolean) = PermissionStatus(
        permission = AppPermission.Location,
        granted = fine || coarse,
        detail = when {
            fine -> "Precise location"
            coarse -> "Approximate location only; precise location makes arrival detection reliable"
            else -> "Not allowed; the next-class card cannot show travel times"
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
        detail = if (granted) "Allowed" else "Not allowed; travel times assume you are walking"
    )

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
