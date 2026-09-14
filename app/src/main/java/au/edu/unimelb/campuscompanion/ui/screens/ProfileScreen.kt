package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DirectionsTransit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.TravelPreferencesStore
import au.edu.unimelb.campuscompanion.ui.components.IconTextLine
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import kotlin.math.roundToInt

@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val travelPreferencesStore = remember(context) { TravelPreferencesStore(context) }
    val savedTravelPreferences = remember(travelPreferencesStore) {
        travelPreferencesStore.load()
    }
    var walkingThresholdKm by rememberSaveable {
        mutableFloatStateOf(savedTravelPreferences.walkingThresholdMeters / 1_000f)
    }
    var longerDistanceMode by rememberSaveable {
        mutableStateOf(savedTravelPreferences.longerDistanceMode)
    }

    fun saveTravelPreferences(
        thresholdKm: Float = walkingThresholdKm,
        mode: TravelMode = longerDistanceMode
    ) {
        travelPreferencesStore.save(
            TravelPreferences(
                walkingThresholdMeters = (thresholdKm * 1_000).roundToInt(),
                longerDistanceMode = mode
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Zan Liang",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "University of Melbourne student",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                IconTextLine(
                    icon = Icons.Outlined.Route,
                    text = "Context reminders enabled",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        SectionHeader(title = "Travel preferences")
        TravelPreferencesCard(
            walkingThresholdKm = walkingThresholdKm,
            longerDistanceMode = longerDistanceMode,
            onWalkingThresholdChange = { walkingThresholdKm = it },
            onWalkingThresholdChangeFinished = { saveTravelPreferences() },
            onLongerDistanceModeChange = { mode ->
                longerDistanceMode = mode
                saveTravelPreferences(mode = mode)
            }
        )

        SectionHeader(title = "Permissions")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingRow(
                icon = Icons.Outlined.LocationOn,
                title = "Location during pre-class window",
                detail = "Used for ETA, arrival, and reminder suppression.",
                checked = true
            )
            SettingRow(
                icon = Icons.Outlined.Notifications,
                title = "Smart notifications",
                detail = "Leave-time alerts with route explanation.",
                checked = true
            )
            SettingRow(
                icon = Icons.Outlined.Fingerprint,
                title = "Biometric private content",
                detail = "Protect recordings and private files.",
                checked = false
            )
            SettingRow(
                icon = Icons.Outlined.Security,
                title = "Keep raw GPS history on device",
                detail = "Only derived context should be synced.",
                checked = true
            )
        }
    }
}

@Composable
private fun TravelPreferencesCard(
    walkingThresholdKm: Float,
    longerDistanceMode: TravelMode,
    onWalkingThresholdChange: (Float) -> Unit,
    onWalkingThresholdChangeFinished: () -> Unit,
    onLongerDistanceModeChange: (TravelMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val longerDistanceOptions = listOf(
        TravelMode.PublicTransport,
        TravelMode.Driving
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.DirectionsWalk,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = "Walking range",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Walk when the destination is within ${formatDistance(walkingThresholdKm)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Slider(
                value = walkingThresholdKm,
                onValueChange = { value ->
                    onWalkingThresholdChange((value * 2f).roundToInt() / 2f)
                },
                onValueChangeFinished = onWalkingThresholdChangeFinished,
                valueRange = 0.5f..3f,
                steps = 4
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "0.5 km",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDistance(walkingThresholdKm),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "3 km",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "For longer trips",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    longerDistanceOptions.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = longerDistanceMode == mode,
                            onClick = { onLongerDistanceModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = longerDistanceOptions.size
                            ),
                            icon = {
                                Icon(
                                    imageVector = mode.icon(),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            label = { Text(mode.displayName) }
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Current route rule",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = "Up to ${formatDistance(walkingThresholdKm)}: Walking",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Over ${formatDistance(walkingThresholdKm)}: ${longerDistanceMode.displayName}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun formatDistance(distanceKm: Float): String {
    return if (distanceKm == distanceKm.toInt().toFloat()) {
        "${distanceKm.toInt()} km"
    } else {
        "${distanceKm} km"
    }
}

private fun TravelMode.icon(): ImageVector = when (this) {
    TravelMode.Walking -> Icons.AutoMirrored.Outlined.DirectionsWalk
    TravelMode.PublicTransport -> Icons.Outlined.DirectionsTransit
    TravelMode.Driving -> Icons.Outlined.DirectionsCar
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    detail: String,
    checked: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = {}
            )
        }
    }
}
