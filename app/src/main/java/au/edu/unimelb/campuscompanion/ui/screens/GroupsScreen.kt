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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.components.GroupUpdateRow
import au.edu.unimelb.campuscompanion.ui.components.QuickActionChip
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.model.TimetableState

@Composable
fun GroupsScreen(
    timetableState: TimetableState,
    onOpenTimetableSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Course groups",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = when {
                    timetableState.isLoading -> "Checking your timetable for course codes."
                    timetableState.isConnected -> {
                        "Groups are created from subjects detected in your timetable."
                    }
                    else -> "Connect your timetable before course groups are added."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when {
            timetableState.isLoading -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text("Detecting subjects...")
                    }
                }
            }
            timetableState.isConnected && timetableState.groups.isNotEmpty() -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Text(
                            text = "Scan QR",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = {},
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(
                            text = "Create",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickActionChip(
                        label = "Find group",
                        icon = Icons.Outlined.Search,
                        onClick = {}
                    )
                    QuickActionChip(
                        label = "NFC join",
                        icon = Icons.Outlined.Nfc,
                        onClick = {}
                    )
                }

                SectionHeader(title = "Detected course groups")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    timetableState.groups.forEach { group ->
                        GroupUpdateRow(group = group)
                    }
                }
            }
            else -> EmptyGroupsState(
                timetableConnected = timetableState.isConnected,
                errorMessage = timetableState.errorMessage,
                onOpenTimetableSetup = onOpenTimetableSetup
            )
        }
    }
}

@Composable
private fun EmptyGroupsState(
    timetableConnected: Boolean,
    errorMessage: String?,
    onOpenTimetableSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = "No course groups yet",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = when {
                    timetableConnected -> {
                        "No groups are available to add."
                    }
                    errorMessage != null -> errorMessage
                    else -> {
                        "Add your timetable URL on Home. Groups for detected courses will appear automatically."
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!timetableConnected) {
                Button(onClick = onOpenTimetableSetup) {
                    Text("Go to Home")
                }
            }
        }
    }
}
