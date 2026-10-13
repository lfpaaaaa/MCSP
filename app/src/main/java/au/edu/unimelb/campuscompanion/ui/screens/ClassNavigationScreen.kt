package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
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
fun ClassNavigationScreen(modifier: Modifier = Modifier) {
    val textResources = textResources()
    val context = LocalContext.current
    val navigation = AppRepositories.navigation
    val state by navigation.state.collectAsState()
    val location by navigation.location.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(5_000) } }
    val fresh = location?.takeIf { now - it.timestampMillis in 0..30_000 && it.accuracyMeters <= 50 }
    fun enableTracking() {
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!precise || !Notifications.canNotify(context)) {
            permissionMessage = textResources.getString(R.string.class_navigation_allow_precise_location_and_notifications_in_settings_to)
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, SensingForegroundService::class.java)
                .setAction(SensingForegroundService.ACTION_PRE_CLASS_MODE))
            navigation.start()
            permissionMessage = null
        } catch (_: Exception) {
            permissionMessage = textResources.getString(R.string.class_navigation_location_tracking_could_not_start_keep_this_app)
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { enableTracking() }
    val locationPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SensingForegroundService::class.java)
                .setAction(SensingForegroundService.ACTION_PRE_CLASS_MODE)) }
        } else permissionMessage = textResources.getString(R.string.class_navigation_allow_precise_location_in_settings_to_plan_a)
    }
    val destination = state.destination
    if (destination == null) {
        Text(textResources.getString(R.string.class_navigation_no_reminder_journey_is_selected_open_home_or), modifier.padding(20.dp))
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(destination.title, style = MaterialTheme.typography.titleLarge)
        destination.activity?.let { activity ->
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                Text(activity, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(destination.buildingLabel.resolve(textResources), style = MaterialTheme.typography.titleMedium)
        Text(destination.locationLabel, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { openGoogleMaps(context, destination, state.mode) }, modifier = Modifier.fillMaxWidth()) {
            Text(textResources.getString(R.string.class_navigation_open_google_maps))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TravelMode.entries.forEach { mode ->
                FilterChip(selected = state.mode == mode, enabled = !state.active && !state.loading,
                    onClick = { navigation.changeMode(mode) }, label = {
                        Text(when (mode) { TravelMode.Walking -> textResources.getString(R.string.class_navigation_walk); TravelMode.PublicTransport -> textResources.getString(R.string.class_navigation_transit); TravelMode.Driving -> textResources.getString(R.string.class_navigation_drive) })
                    })
            }
        }
        if (fresh == null) {
            Text(textResources.getString(R.string.class_navigation_enable_precise_location_to_plan_a_route_or), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text(textResources.getString(R.string.class_navigation_update_location)) }
        }
        state.error?.let { Text(it.resolve(textResources), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (!state.active) {
            Button(onClick = { navigation.loadRoutes() }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                if (state.loading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (state.loading) textResources.getString(R.string.class_navigation_finding_routes) else textResources.getString(R.string.class_navigation_find_google_maps_routes))
            }
            Text(textResources.getString(R.string.class_navigation_your_current_location_and_class_destination_are_sent), style = MaterialTheme.typography.bodySmall)
            state.routes.forEachIndexed { index, route ->
                OutlinedButton(onClick = { navigation.selectRoute(index) }, modifier = Modifier.fillMaxWidth()) {
                    val lines = route.transitLegs.joinToString(" → ") { it.line }
                    Text(textResources.getString(R.string.class_navigation_min_m, if (index == state.routeIndex) "✓ " else "", (route.durationSeconds + 59) / 60, route.distanceMeters, if (lines.isNotBlank()) " · $lines" else ""))
                }
            }
        }
        if (state.route != null) {
            Text(textResources.getString(R.string.class_navigation_google_maps), style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)
            state.route!!.transitLegs.forEachIndexed { index, leg ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(textResources.getString(R.string.route_leg_heading, index + 1, leg.line, leg.headsign), style = MaterialTheme.typography.titleMedium)
                    Text(textResources.getString(R.string.class_navigation_board, leg.departureName))
                    Text(textResources.getString(R.string.class_navigation_get_off, leg.arrivalName))
                } }
            }
        }
        if (state.active) {
            val leg = state.leg
            Text(when {
                state.progress.offRoute -> textResources.getString(R.string.class_navigation_you_appear_to_be_off_the_selected_route)
                state.progress.state == BoardingState.Alight -> textResources.getString(R.string.class_navigation_get_ready_to_get_off_at, leg?.arrivalName)
                state.progress.state == BoardingState.Riding -> textResources.getString(R.string.class_navigation_on, leg?.line, state.progress.remainingMeters?.let { textResources.getString(R.string.copy_m_to, it, leg?.arrivalName) } ?: textResources.getString(R.string.route_locating_your_stop))
                else -> textResources.getString(R.string.class_navigation_waiting_to_board_at, leg?.line, leg?.departureName)
            }, style = MaterialTheme.typography.titleMedium)
            if (state.progress.state == BoardingState.Waiting) {
                OutlinedButton(onClick = { navigation.confirmBoarding() }, Modifier.fillMaxWidth()) { Text(textResources.getString(R.string.copy_i_m_on, leg?.line)) }
            } else {
                Button(onClick = { navigation.nextLeg() }, Modifier.fillMaxWidth()) { Text(textResources.getString(R.string.class_navigation_i_ve_got_off_continue)) }
            }
            OutlinedButton(onClick = { navigation.stop() }, Modifier.fillMaxWidth()) { Text(textResources.getString(R.string.class_navigation_end_trip)) }
        } else if (state.route?.transitLegs?.isNotEmpty() == true) {
            Button(onClick = {
                permissions.launch(buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray())
            }, Modifier.fillMaxWidth()) { Text(textResources.getString(R.string.class_navigation_start_trip_get_off_reminders)) }
            Text(textResources.getString(R.string.class_navigation_confirm_the_line_before_boarding_movement_can_suggest), style = MaterialTheme.typography.bodySmall)
        }
        permissionMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text(textResources.getString(R.string.class_navigation_open_settings)) }
        }
        if (state.active) Text(textResources.getString(R.string.class_navigation_google_maps_may_suggest_a_different_route_get), style = MaterialTheme.typography.bodySmall)
        state.route?.instructions?.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
