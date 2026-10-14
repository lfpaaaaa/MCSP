package au.edu.unimelb.campuscompanion.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.LocationManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.context.BuildingResolver
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.navigation.openGoogleMaps
import au.edu.unimelb.campuscompanion.navigation.toClassDestination
import au.edu.unimelb.campuscompanion.sensing.orientation.CompassHeadingDetector
import au.edu.unimelb.campuscompanion.sensing.location.LocationTracker
import au.edu.unimelb.campuscompanion.sensing.location.LocationTrackingMode
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.DestinationCompass
import au.edu.unimelb.campuscompanion.ui.model.destinationCompass
import au.edu.unimelb.campuscompanion.ui.model.isUsableForCompass
import au.edu.unimelb.campuscompanion.ui.model.nextCompassRotation
import au.edu.unimelb.campuscompanion.ui.profile.AppPermission
import au.edu.unimelb.campuscompanion.ui.profile.AppPermissions
import au.edu.unimelb.campuscompanion.ui.text.asString
import kotlinx.coroutines.delay

/** A foreground-only compass; viewing a bearing never changes the selected transit journey. */
@Composable
fun ClassCompassScreen(session: CourseSession?, loading: Boolean, modifier: Modifier = Modifier) {
    if (session == null) {
        Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text(stringResource(R.string.compass_class_unavailable))
        }
        return
    }
    val building = remember(session.location, session.room) { BuildingResolver.resolve(session, AppRepositories.buildings) }
    if (building == null) {
        Text(stringResource(R.string.compass_building_unavailable), modifier.padding(20.dp))
        return
    }
    val destination = session.toClassDestination(building)
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detector = remember(context, view) { CompassHeadingDetector(context) { view.display?.rotation ?: Surface.ROTATION_0 } }
    val heading by detector.sample.collectAsStateWithLifecycle()
    val tracker = remember(context) { LocationTracker(context.applicationContext) }
    val location by tracker.location.collectAsStateWithLifecycle()
    var precise by remember(context) { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    ) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var locationEnabled by remember { mutableStateOf(true) }
    var sensorsAvailable by remember(detector) { mutableStateOf(detector.isAvailable) }
    var trackingFailed by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(detector, tracker, lifecycle, precise, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            var active = true
            trackingFailed = false
            sensorsAvailable = detector.start()
            try {
                if (precise) {
                    runCatching {
                        tracker.startTracking(LocationTrackingMode.PRE_CLASS).addOnFailureListener {
                            if (active) trackingFailed = true
                        }
                    }.onFailure { trackingFailed = true }
                }
                while (true) {
                    now = System.currentTimeMillis()
                    elapsed = SystemClock.elapsedRealtimeNanos()
                    locationEnabled = LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                    delay(1_000)
                }
            } finally {
                active = false
                detector.stop()
                tracker.stopTracking()
            }
        }
    }
    // A location can arrive between ticker updates; compare it with the current wall clock.
    val currentTimeMillis = maxOf(now, System.currentTimeMillis())
    val fix = location?.takeIf { precise && locationEnabled && it.isUsableForCompass(currentTimeMillis) }
    val declination = remember(fix?.latitude, fix?.longitude, fix?.timestampMillis) {
        fix?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, it.timestampMillis).declination } ?: 0f
    }
    val compass = destinationCompass(destination.point, fix, heading, sensorsAvailable, declination,
        currentTimeMillis, maxOf(elapsed, SystemClock.elapsedRealtimeNanos()), view.display?.rotation ?: Surface.ROTATION_0)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(destination.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            destination.activity?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            Text(destination.buildingLabel.asString(), style = MaterialTheme.typography.titleMedium)
            Text(destination.locationLabel, style = MaterialTheme.typography.bodyMedium)
        }
        CompassIndicator(compass)
        Text(stringResource(when {
            !precise -> R.string.compass_location_permission
            !locationEnabled -> R.string.compass_location_off
            trackingFailed && fix == null -> R.string.compass_tracking_failed
            else -> compass.messageId
        }), style = MaterialTheme.typography.bodyLarge)
        if (!precise) {
            Button(onClick = {
                if (permissionRequested && AppPermissions.isDeniedForGood(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    AppPermissions.openSettings(context, AppPermission.Location)
                } else {
                    permissionRequested = true
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }) { Text(stringResource(R.string.compass_enable_location)) }
        } else if (!locationEnabled) {
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                Text(stringResource(R.string.compass_enable_location))
            }
        } else if (trackingFailed) {
            OutlinedButton(onClick = { retry++ }) { Text(stringResource(R.string.action_retry)) }
        }
        Text(stringResource(R.string.compass_straight_direction), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { openGoogleMaps(context, destination, TravelMode.Walking) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.campus_components_navigate_with_google_maps))
        }
    }
}

@Composable
internal fun CompassIndicator(compass: DestinationCompass, modifier: Modifier = Modifier) {
    var rotation by remember { mutableFloatStateOf(0f) }
    val target = (compass as? DestinationCompass.Pointing)?.relativeDegrees
    LaunchedEffect(target) { if (target != null) rotation = nextCompassRotation(rotation, target) }
    val animated by animateFloatAsState(rotation, tween(180), label = "compass bearing")
    Surface(modifier.size(200.dp), shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (compass) {
                    is DestinationCompass.Pointing -> Icons.Filled.Navigation
                    DestinationCompass.NearBuilding -> Icons.Outlined.CheckCircle
                    else -> Icons.Outlined.Info
                },
                contentDescription = null,
                modifier = Modifier.size(100.dp).then(if (target != null) Modifier.rotate(animated) else Modifier),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private val DestinationCompass.messageId: Int get() = when (this) {
    DestinationCompass.WaitingForLocation -> R.string.compass_waiting_location
    DestinationCompass.SensorUnavailable -> R.string.compass_no_sensor
    DestinationCompass.WaitingForHeading -> R.string.compass_waiting_heading
    DestinationCompass.Calibrate -> R.string.compass_calibrate
    DestinationCompass.HoldFlat -> R.string.compass_hold_flat
    DestinationCompass.NearBuilding -> R.string.compass_near_building
    is DestinationCompass.Pointing -> when (directionSector) {
        0 -> R.string.compass_ahead
        1 -> R.string.compass_ahead_right
        2 -> R.string.compass_right
        3 -> R.string.compass_behind_right
        4 -> R.string.compass_behind
        5 -> R.string.compass_behind_left
        6 -> R.string.compass_left
        else -> R.string.compass_ahead_left
    }
}
