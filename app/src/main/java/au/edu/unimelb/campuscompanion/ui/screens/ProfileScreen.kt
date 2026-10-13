package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import androidx.compose.ui.res.stringResource
import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DirectionsTransit
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.auth.AuthenticatedUser
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.ui.about.AboutSection
import au.edu.unimelb.campuscompanion.ui.components.IconTextLine
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.components.TimetableSyncStatus
import au.edu.unimelb.campuscompanion.ui.components.TimetableUrlDialog
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.profile.PermissionsSection
import kotlin.math.roundToInt

@Composable
fun ProfileScreen(
    user: AuthenticatedUser,
    onEditProfile: () -> Unit,
    timetableState: TimetableState,
    travelPreferences: TravelPreferences,
    onTimetableUrlSave: suspend (String) -> Result<Unit>,
    onTimetableRefresh: () -> Unit,
    onTimetableUrlRemove: () -> Unit,
    onTravelPreferencesChange: (TravelPreferences) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textResources = textResources()
    var walkingThresholdKm by rememberSaveable(travelPreferences.walkingThresholdMeters) {
        mutableFloatStateOf(travelPreferences.walkingThresholdMeters / 1_000f)
    }
    var longerDistanceMode by rememberSaveable(travelPreferences.longerDistanceMode) {
        mutableStateOf(travelPreferences.longerDistanceMode)
    }
    var showTimetableDialog by rememberSaveable { mutableStateOf(false) }
    var showRemoveTimetableDialog by rememberSaveable { mutableStateOf(false) }

    if (showTimetableDialog) {
        TimetableUrlDialog(
            initialUrl = timetableState.url,
            onDismiss = { showTimetableDialog = false },
            onSave = onTimetableUrlSave
        )
    }

    if (showRemoveTimetableDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveTimetableDialog = false },
            title = { Text(textResources.getString(R.string.profile_remove_timetable_url)) },
            text = {
                Text(textResources.getString(R.string.profile_courses_departure_reminders_and_automatically_joined_groups_will))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onTimetableUrlRemove()
                        showRemoveTimetableDialog = false
                    }
                ) {
                    Text(textResources.getString(R.string.chat_attachments_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveTimetableDialog = false }) {
                    Text(textResources.getString(R.string.action_cancel))
                }
            }
        )
    }

    fun saveTravelPreferences(
        thresholdKm: Float = walkingThresholdKm,
        mode: TravelMode = longerDistanceMode
    ) {
        onTravelPreferencesChange(
            travelPreferences.copy(
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
                au.edu.unimelb.campuscompanion.ui.profile.ProfileAvatar(user.profileName, user.avatarUrl, Modifier.size(72.dp))
                Text(
                    text = user.profileName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = user.email ?: textResources.getString(R.string.auths_signed_in_with, user.provider.displayName.resolve(textResources)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                IconTextLine(
                    icon = Icons.Outlined.Route,
                    text = textResources.getString(R.string.copy_account, user.provider.displayName.resolve(textResources)),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                OutlinedButton(onClick = onEditProfile) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(textResources.getString(R.string.campus_companion_app_edit_profile), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        SectionHeader(title = textResources.getString(R.string.profile_travel_preferences))
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

        SectionHeader(title = textResources.getString(R.string.profile_timetable))
        TimetableSettingsCard(
            timetableState = timetableState,
            onAddOrChange = { showTimetableDialog = true },
            onRemove = { showRemoveTimetableDialog = true },
            onRefresh = onTimetableRefresh
        )

        SectionHeader(title = textResources.getString(R.string.profile_permissions))
        PermissionsSection()

        SectionHeader(title = textResources.getString(R.string.profile_about))
        AboutSection()

        SectionHeader(title = textResources.getString(R.string.profile_account))
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = textResources.getString(R.string.profile_sign_out),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun TimetableSettingsCard(
    timetableState: TimetableState,
    onAddOrChange: () -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textResources = textResources()
    val hasSavedUrl = timetableState.url.isNotBlank()
    val host = remember(timetableState.url) {
        Uri.parse(timetableState.url).host ?: textResources.getString(R.string.profile_calendar_subscription)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = textResources.getString(R.string.profile_timetable_url),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when {
                            timetableState.isLoading && !timetableState.isConnected -> textResources.getString(R.string.profile_checking_saved_url)
                            timetableState.isCached -> textResources.getString(R.string.profile_saved_timetable_from, host)
                            timetableState.isConnected -> {
                                timetableState.offlineNotice()?.resolve(textResources)
                                    ?: textResources.getQuantityString(R.plurals.connected_event_count, timetableState.detectedEventCount, host, timetableState.detectedEventCount)
                            }
                            timetableState.errorMessage != null -> timetableState.errorMessage.resolve(textResources)
                            else -> textResources.getString(R.string.profile_no_timetable_connected)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TimetableSyncStatus(timetableState, onRefresh)

            if (hasSavedUrl) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddOrChange) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = textResources.getString(R.string.permissions_section_change),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    TextButton(onClick = onRemove) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = textResources.getString(R.string.chat_attachments_remove),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            } else {
                Button(onClick = onAddOrChange) {
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = textResources.getString(R.string.home_add_url),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
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
    val textResources = textResources()
    val walkingDistanceLabel = formatDistance(walkingThresholdKm)
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
                        text = textResources.getString(R.string.profile_walking_range),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = textResources.getString(R.string.profile_walk_when_the_destination_is_within, formatDistance(walkingThresholdKm)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Slider(
                modifier = Modifier.semantics {
                    contentDescription = textResources.getString(R.string.profile_walking_range)
                    stateDescription = walkingDistanceLabel
                },
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
                    text = textResources.getString(R.string.profile_0_5_km),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDistance(walkingThresholdKm),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = textResources.getString(R.string.profile_3_km),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = textResources.getString(R.string.profile_for_longer_trips),
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
                            label = { Text(mode.displayName.resolve(textResources)) }
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
                        text = textResources.getString(R.string.profile_current_route_rule),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = textResources.getString(R.string.profile_up_to_walking, formatDistance(walkingThresholdKm)),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = textResources.getString(R.string.profile_over, formatDistance(walkingThresholdKm), longerDistanceMode.displayName.resolve(textResources)),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun formatDistance(distanceKm: Float): String {
    return if (distanceKm == distanceKm.toInt().toFloat()) {
        stringResource(R.string.label_km, distanceKm.toInt())
    } else {
        stringResource(R.string.label_km, distanceKm)
    }
}

private fun TravelMode.icon(): ImageVector = when (this) {
    TravelMode.Walking -> Icons.AutoMirrored.Outlined.DirectionsWalk
    TravelMode.PublicTransport -> Icons.Outlined.DirectionsTransit
    TravelMode.Driving -> Icons.Outlined.DirectionsCar
}
