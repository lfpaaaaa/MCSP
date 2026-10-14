package au.edu.unimelb.campuscompanion.ui.profile

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ui.text.UiText
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
enum class AppPermission(val title: UiText, val purpose: UiText) {
    Location(
        title = UiText(R.string.permission_location),
        purpose = UiText(R.string.context_permission_location_purpose)
    ),
    BackgroundArrival(
        title = UiText(R.string.context_permission_background_arrival),
        purpose = UiText(R.string.context_permission_background_purpose)
    ),
    Notifications(
        title = UiText(R.string.permission_notifications),
        purpose = UiText(R.string.permission_departure_reminders_and_new_messages_in_your_groups)
    ),
    Camera(
        title = UiText(R.string.group_chat_camera),
        purpose = UiText(R.string.permission_photos_for_the_group_chat_and_scanning_invitation)
    ),
    Microphone(
        title = UiText(R.string.permission_microphone),
        purpose = UiText(R.string.permission_record_audio_to_share_in_a_group_recording)
    ),
    PhysicalActivity(
        title = UiText(R.string.permission_physical_activity),
        purpose = UiText(R.string.context_permission_activity_purpose)
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
            Microphone -> listOf(Manifest.permission.RECORD_AUDIO)
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
    val detail: UiText
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
            microphone(granted = context.has(Manifest.permission.RECORD_AUDIO)),
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
            fine -> UiText(R.string.permission_precise_location)
            coarse -> UiText(R.string.context_permission_approximate)
            else -> UiText(R.string.context_permission_location_denied)
        }
    )

    internal fun backgroundArrival(location: LocationPermissionState) = PermissionStatus(
        permission = AppPermission.BackgroundArrival,
        granted = location.canMonitorBackgroundArrival,
        detail = when {
            !location.fine && location.background && location.sdkInt >= 29 ->
                UiText(R.string.context_permission_background_needs_precise)
            !location.fine -> UiText(R.string.context_permission_foreground_first)
            location.sdkInt < 29 -> UiText(R.string.context_permission_legacy_background)
            location.background -> UiText(R.string.context_permission_background_allowed)
            else -> UiText(R.string.context_permission_background_denied)
        }
    )

    internal fun notifications(allowed: Boolean) = PermissionStatus(
        permission = AppPermission.Notifications,
        granted = allowed,
        detail = if (allowed) UiText(R.string.permissions_section_allowed) else UiText(R.string.permission_not_allowed_reminders_and_message_alerts_stay_silent)
    )

    internal fun camera(granted: Boolean) = PermissionStatus(
        permission = AppPermission.Camera,
        granted = granted,
        detail = if (granted) UiText(R.string.permissions_section_allowed) else UiText(R.string.permission_asked_for_when_you_first_take_a_photo)
    )

    internal fun microphone(granted: Boolean) = PermissionStatus(
        permission = AppPermission.Microphone,
        granted = granted,
        detail = if (granted) UiText(R.string.permissions_section_allowed) else UiText(R.string.permission_asked_for_when_you_start_a_recording)
    )

    internal fun physicalActivity(granted: Boolean) = PermissionStatus(
        permission = AppPermission.PhysicalActivity,
        granted = granted,
        detail = if (granted) UiText(R.string.permissions_section_allowed) else UiText(R.string.context_permission_activity_denied)
    )

    /** Separate education before the optional background request; foreground access comes first. */
    fun backgroundExplanation(context: Context): String {
        val state = locationPermissionState(context)
        if (!state.fine) return context.getString(R.string.context_background_explanation_precise)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getString(R.string.context_background_explanation_settings,
                context.packageManager.backgroundPermissionOptionLabel)
        } else {
            context.getString(R.string.context_background_explanation_runtime)
        }
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
