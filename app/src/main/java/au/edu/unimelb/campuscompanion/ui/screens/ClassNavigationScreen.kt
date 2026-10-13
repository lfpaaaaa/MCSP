package au.edu.unimelb.campuscompanion.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.navigation.*
import au.edu.unimelb.campuscompanion.push.Notifications
import au.edu.unimelb.campuscompanion.sensing.service.SensingForegroundService
import kotlinx.coroutines.delay

@Composable
fun ClassNavigationScreen(
    modifier: Modifier = Modifier,
    navigation: NavigationSession = AppRepositories.navigation
) {
    val context = LocalContext.current
    val state by navigation.state.collectAsState()
    val location by navigation.location.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }
    val fresh = location?.takeIf { now - it.timestampMillis in 0..30_000 && it.accuracyMeters <= 50 }
    fun enableTracking() {
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!precise || !Notifications.canNotify(context)) {
            permissionMessage = "Allow precise location and notifications in Settings to receive get-off reminders."
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, SensingForegroundService::class.java)
                .setAction(SensingForegroundService.ACTION_PRE_CLASS_MODE))
            navigation.start()
            permissionMessage = null
        } catch (_: Exception) {
            permissionMessage = "Location tracking could not start. Keep this app open and try again."
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { enableTracking() }
    val locationPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SensingForegroundService::class.java)
                .setAction(SensingForegroundService.ACTION_PRE_CLASS_MODE)) }
        } else permissionMessage = "Allow precise location in Settings to plan a route from where you are."
    }
    val destination = state.destination
    if (destination == null) {
        // The error says why a selected class's directions were closed, for example after it was removed.
        Text(state.error ?: "No reminder journey is selected. Open Home or Schedule to navigate with Google Maps.", modifier.padding(20.dp))
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(destination.title, style = MaterialTheme.typography.titleLarge)
        destination.activity?.let { activity ->
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                Text(activity, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(destination.buildingLabel, style = MaterialTheme.typography.titleMedium)
        Text(destination.locationLabel, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { openGoogleMaps(context, destination, state.mode) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open Google Maps")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TravelMode.entries.forEach { mode ->
                FilterChip(selected = state.mode == mode, enabled = !state.active && !state.loading,
                    onClick = { navigation.changeMode(mode) }, label = {
                        Text(when (mode) { TravelMode.Walking -> "Walk"; TravelMode.PublicTransport -> "Transit"; TravelMode.Driving -> "Drive" })
                    })
            }
        }
        if (fresh == null) {
            Text("Enable precise location to plan a route or receive get-off reminders.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Update location") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.active) {
            Button(onClick = { navigation.loadRoutes() }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                if (state.loading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (state.loading) "Finding routes…" else "Find Google Maps routes")
            }
            Text("Your current location and class destination are sent to Google to find a route.", style = MaterialTheme.typography.bodySmall)
            state.routes.forEachIndexed { index, route ->
                OutlinedButton(onClick = { navigation.selectRoute(index) }, modifier = Modifier.fillMaxWidth()) {
                    val lines = route.transitLegs.joinToString(" → ") { it.line }
                    Text("${if (index == state.routeIndex) "✓ " else ""}${(route.durationSeconds + 59) / 60} min · ${route.distanceMeters} m${if (lines.isNotBlank()) " · $lines" else ""}")
                }
            }
        }
        if (state.route != null) {
            Text("Google Maps", style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)
            state.route!!.transitLegs.forEachIndexed { index, leg ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${index + 1}. ${leg.line} ${leg.headsign}", style = MaterialTheme.typography.titleMedium)
                    Text("Board: ${leg.departureName}")
                    Text("Get off: ${leg.arrivalName}")
                } }
            }
        }
        if (state.active) {
            val leg = state.leg
            Text(when {
                state.progress.offRoute -> "You appear to be off the selected route. Check your line or end this trip and find a new route."
                state.progress.state == BoardingState.Alight -> "Get ready to get off at ${leg?.arrivalName}."
                state.progress.state == BoardingState.Riding -> "On ${leg?.line} · ${state.progress.remainingMeters?.let { "$it m to ${leg?.arrivalName}" } ?: "Locating your stop…"}"
                else -> "Waiting to board ${leg?.line} at ${leg?.departureName}."
            }, style = MaterialTheme.typography.titleMedium)
            if (state.progress.state == BoardingState.Waiting) {
                OutlinedButton(onClick = { navigation.confirmBoarding() }, Modifier.fillMaxWidth()) { Text("I'm on ${leg?.line}") }
            } else {
                Button(onClick = { navigation.nextLeg() }, Modifier.fillMaxWidth()) { Text("I've got off · Continue") }
            }
            OutlinedButton(onClick = { navigation.stop() }, Modifier.fillMaxWidth()) { Text("End trip") }
        } else if (state.route?.transitLegs?.isNotEmpty() == true) {
            // Routes being reloaded will replace the one on screen, so it cannot be started meanwhile.
            Button(onClick = {
                permissions.launch(buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray())
            }, Modifier.fillMaxWidth(), enabled = !state.loading) { Text("Start trip & get-off reminders") }
            Text("Confirm the line before boarding. Movement can suggest that you're riding, but cannot identify a specific vehicle.", style = MaterialTheme.typography.bodySmall)
        }
        permissionMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open settings") }
        }
        if (state.active) Text("Google Maps may suggest a different route. Get-off reminders continue to follow the route selected here.", style = MaterialTheme.typography.bodySmall)
        state.route?.instructions?.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
