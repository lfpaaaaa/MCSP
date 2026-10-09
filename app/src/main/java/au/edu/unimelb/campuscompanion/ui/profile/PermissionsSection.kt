package au.edu.unimelb.campuscompanion.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The app's permissions as the system reports them, re-read whenever the screen comes back to the
 * foreground. Tapping a row asks for the permission when the system still allows a dialog, and
 * opens the app's system settings otherwise; a granted row opens the settings too.
 */
@Composable
fun PermissionsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var statuses by remember(context) { mutableStateOf(AppPermissions.read(context)) }
    var requested by remember { mutableStateOf<AppPermission?>(null) }

    val requestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        statuses = AppPermissions.read(context)
        val permission = requested ?: return@rememberLauncherForActivityResult
        requested = null
        val nothingGranted = results.isNotEmpty() && results.values.none { it }
        if (nothingGranted && results.keys.all { AppPermissions.isDeniedForGood(context, it) }) {
            AppPermissions.openSettings(context, permission)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                statuses = AppPermissions.read(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        statuses.forEach { status ->
            PermissionRow(
                status = status,
                onClick = {
                    val missing = status.permission.runtimePermissions
                        .filterNot { AppPermissions.isGranted(context, it) }
                    if (status.granted || missing.isEmpty()) {
                        AppPermissions.openSettings(context, status.permission)
                    } else {
                        requested = status.permission
                        requestLauncher.launch(missing.toTypedArray())
                    }
                }
            )
        }
        PrivacyNote()
    }
}

@Composable
private fun PermissionRow(
    status: PermissionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = if (status.granted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = status.permission.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = status.permission.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = status.permission.purpose,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (status.granted) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                        contentDescription = if (status.granted) "Allowed" else "Not allowed",
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = status.detail,
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Change",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PrivacyNote(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Where your location goes",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = "Your position is used on this phone. To work out a travel time, the app sends the " +
                    "start and end points to its own server, which rounds them to about 110 m before " +
                    "asking the routing service and keeps a count of requests, not the positions. " +
                    "Your location history never leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun AppPermission.icon(): ImageVector = when (this) {
    AppPermission.Location -> Icons.Outlined.LocationOn
    AppPermission.Notifications -> Icons.Outlined.Notifications
    AppPermission.Camera -> Icons.Outlined.PhotoCamera
    AppPermission.PhysicalActivity -> Icons.AutoMirrored.Outlined.DirectionsWalk
}
