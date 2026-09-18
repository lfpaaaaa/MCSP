package au.edu.unimelb.campuscompanion.ui.screens

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
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.components.CourseSessionRow
import au.edu.unimelb.campuscompanion.ui.components.QuickActionChip
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.components.TimetableUrlDialog
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.MockCampusData
import au.edu.unimelb.campuscompanion.ui.model.departureReminderTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val reminderTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    timetableUrl: String,
    onTimetableUrlSave: (String) -> Unit,
    onTimetableUrlRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var remindersEnabled by rememberSaveable { mutableStateOf(true) }
    var leadMinutes by rememberSaveable { mutableIntStateOf(10) }
    var showTimetableDialog by rememberSaveable { mutableStateOf(false) }
    val nextSession = MockCampusData.sessions.first()

    if (showTimetableDialog) {
        TimetableUrlDialog(
            initialUrl = timetableUrl,
            onDismiss = { showTimetableDialog = false },
            onSave = { url ->
                onTimetableUrlSave(url)
                showTimetableDialog = false
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {}) {
                Icon(Icons.Outlined.Add, contentDescription = "Add class")
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Timetable",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Import a university calendar or keep classes updated manually.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("Today") }
                )
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text("Week") }
                )
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text("Manual") }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionChip(
                    label = if (timetableUrl.isBlank()) "Connect URL" else "Change URL",
                    icon = Icons.Outlined.Link,
                    onClick = { showTimetableDialog = true }
                )
                QuickActionChip(
                    label = "Edit classes",
                    icon = Icons.Outlined.EditCalendar,
                    onClick = {}
                )
            }

            if (timetableUrl.isNotBlank()) {
                TimetableConnectionCard(
                    url = timetableUrl,
                    onChange = { showTimetableDialog = true },
                    onRemove = onTimetableUrlRemove
                )
            }

            DepartureReminderSettings(
                session = nextSession,
                enabled = remindersEnabled,
                leadMinutes = leadMinutes,
                onEnabledChange = { remindersEnabled = it },
                onLeadMinutesChange = { leadMinutes = it }
            )

            SectionHeader(title = "Next sessions")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MockCampusData.sessions.forEach { session ->
                    CourseSessionRow(session = session)
                }
            }
        }
    }
}

@Composable
private fun TimetableConnectionCard(
    url: String,
    onChange: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val host = remember(url) { Uri.parse(url).host ?: "Calendar subscription" }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Timetable URL saved",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onChange) {
                    Text("Change")
                }
                TextButton(onClick = onRemove) {
                    Text("Remove")
                }
            }
        }
    }
}

@Composable
private fun DepartureReminderSettings(
    session: CourseSession,
    enabled: Boolean,
    leadMinutes: Int,
    onEnabledChange: (Boolean) -> Unit,
    onLeadMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val notificationTime = session.departureReminderTime(leadMinutes)

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = "Departure reminder",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "For ${session.code} at ${session.startTime.format(reminderTimeFormatter)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }

            if (enabled) {
                Text(
                    text = "Remind me $leadMinutes min before I need to leave",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = leadMinutes.toFloat(),
                    onValueChange = { value ->
                        onLeadMinutesChange((value / 5f).roundToInt() * 5)
                    },
                    valueRange = 0f..30f,
                    steps = 5
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Travel ${session.etaMinutes} min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Class ${session.startTime.format(reminderTimeFormatter)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AccessTime,
                            contentDescription = null
                        )
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(
                                text = "Notification time",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = notificationTime.format(reminderTimeFormatter),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Departure alerts are off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
