package au.edu.unimelb.campuscompanion.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.LocationManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import au.edu.unimelb.campuscompanion.context.BuildingResolver
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.TravelPreferencesStore
import au.edu.unimelb.campuscompanion.navigation.openGoogleMaps
import au.edu.unimelb.campuscompanion.navigation.preferredNavigationMode
import au.edu.unimelb.campuscompanion.navigation.toClassDestination
import au.edu.unimelb.campuscompanion.sensing.location.LocationTracker
import au.edu.unimelb.campuscompanion.sensing.location.LocationTrackingMode
import au.edu.unimelb.campuscompanion.sensing.orientation.CompassHeadingDetector
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.DestinationCompass
import au.edu.unimelb.campuscompanion.ui.model.destinationCompass
import au.edu.unimelb.campuscompanion.ui.model.isUsableForCompass
import au.edu.unimelb.campuscompanion.ui.model.nextCompassRotation
import au.edu.unimelb.campuscompanion.ui.profile.AppPermission
import au.edu.unimelb.campuscompanion.ui.profile.AppPermissions
import kotlinx.coroutines.delay

/**
 * A foreground-only compass toward the class building. It owns its own location and heading
 * updates while visible, so viewing a bearing never changes the sensing service or a selected
 * transit journey.
 */
@Composable
fun ClassCompassScreen(session: CourseSession?, loading: Boolean, modifier: Modifier = Modifier) {
    if (session == null) {
        Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator()
            } else {
                Text("This class is no longer in your timetable. Return to Schedule and choose a class.")
            }
        }
        return
    }
    val building = remember(session.location, session.room) { BuildingResolver.resolve(session, AppRepositories.buildings) }
    if (building == null) {
        Text(
            "This classroom could not be matched to a campus building. Check its location in your timetable.",
            modifier.padding(20.dp)
        )
        return
    }
    val destination = session.toClassDestination(building)
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val detector = remember(context, view) {
        CompassHeadingDetector(context.applicationContext) { view.display?.rotation ?: android.view.Surface.ROTATION_0 }
    }
    val heading by detector.sample.collectAsStateWithLifecycle()
    val tracker = remember(context) { LocationTracker(context.applicationContext) }
    val location by tracker.location.collectAsStateWithLifecycle()
    var precise by remember(context) {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var locationEnabled by remember { mutableStateOf(true) }
    val sensorsAvailable = remember(detector) { detector.isAvailable }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(detector, tracker, lifecycle, precise) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            detector.start()
            try {
                if (precise) tracker.startTracking(LocationTrackingMode.PRE_CLASS)
                while (true) {
                    now = System.currentTimeMillis()
                    elapsed = SystemClock.elapsedRealtimeNanos()
                    locationEnabled = LocationManagerCompat.isLocationEnabled(
                        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    )
                    delay(1_000)
                }
            } finally {
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
    val compass = destinationCompass(
        destination.point, fix, heading, sensorsAvailable, declination,
        currentTimeMillis, maxOf(elapsed, SystemClock.elapsedRealtimeNanos()), view.display?.rotation ?: android.view.Surface.ROTATION_0
    )
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(destination.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            destination.activity?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            Text(destination.buildingLabel, style = MaterialTheme.typography.titleMedium)
            Text(destination.locationLabel, style = MaterialTheme.typography.bodyMedium)
        }
        CompassIndicator(compass)
        Text(
            when {
                !precise -> "Allow precise location to point toward your teaching building."
                !locationEnabled -> "Turn on your phone's location to find the building direction."
                else -> compass.message
            },
            style = MaterialTheme.typography.bodyLarge
        )
        if (!precise) {
            Button(onClick = {
                if (permissionRequested && AppPermissions.isDeniedForGood(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    AppPermissions.openSettings(context, AppPermission.Location)
                } else {
                    permissionRequested = true
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }) { Text("Enable location") }
        } else if (!locationEnabled) {
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                Text("Enable location")
            }
        }
        Text(
            "Hold your phone flat with its top edge pointing ahead. The arrow points straight toward the building. " +
                "Use Google Maps for paths and walking directions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = {
            val mode = preferredNavigationMode(
                destination = destination.point,
                preferences = TravelPreferencesStore(context).load(),
                location = fix ?: AppRepositories.navigation.location.value,
                routeDistanceMeters = session.routeEstimate?.distanceMeters
            )
            openGoogleMaps(context, destination, mode)
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Navigate with Google Maps")
        }
    }
}

@Composable
internal fun CompassIndicator(compass: DestinationCompass, modifier: Modifier = Modifier) {
    var rotation by remember { mutableFloatStateOf(0f) }
    val target = (compass as? DestinationCompass.Pointing)?.relativeDegrees
    LaunchedEffect(target) { if (target != null) rotation = nextCompassRotation(rotation, target) }
    val animated by animateFloatAsState(rotation, tween(180), label = "compass bearing")
    Surface(
        modifier.size(200.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
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

private val DestinationCompass.message: String get() = when (this) {
    DestinationCompass.WaitingForLocation -> "Waiting for a recent, accurate location. Try moving to an open area."
    DestinationCompass.SensorUnavailable -> "This phone cannot provide compass directions. You can still navigate with Google Maps."
    DestinationCompass.WaitingForHeading -> "Waiting for compass readings. Hold your phone flat, with its screen facing up."
    DestinationCompass.Calibrate -> "Compass accuracy is low. Move away from magnets and metal, then move your phone in a figure eight."
    DestinationCompass.HoldFlat -> "Hold your phone flat with its screen facing up to see the direction."
    DestinationCompass.NearBuilding -> "You are near the teaching building. Check your room number and the building entrance."
    is DestinationCompass.Pointing -> when (directionSector) {
        0 -> "The building is ahead of you."
        1 -> "The building is ahead and to your right."
        2 -> "The building is to your right."
        3 -> "The building is behind you and to your right."
        4 -> "The building is behind you."
        5 -> "The building is behind you and to your left."
        6 -> "The building is to your left."
        else -> "The building is ahead and to your left."
    }
}
