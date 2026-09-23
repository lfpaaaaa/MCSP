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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.components.TimetableUrlDialog
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.model.departureReminderTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val reminderTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private const val MAX_VISIBLE_SESSIONS = 50

@Composable
fun ScheduleScreen(
    timetableState: TimetableState,
    onTimetableUrlSave: suspend (String) -> Result<Unit>,
    onTimetableUrlRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var remindersEnabled by rememberSaveable { mutableStateOf(true) }
    var leadMinutes by rememberSaveable { mutableIntStateOf(10) }
    var showTimetableDialog by rememberSaveable { mutableStateOf(false) }

    if (showTimetableDialog) {
        TimetableUrlDialog(
            initialUrl = timetableState.url,
            onDismiss = { showTimetableDialog = false },
            onSave = onTimetableUrlSave
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Timetable",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = if (timetableState.isConnected) {
                    "Upcoming sessions parsed from your calendar subscription."
                } else {
                    "No classes are shown until a timetable URL is verified."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when {
            timetableState.isLoading -> LoadingTimetableState()
            timetableState.isConnected -> {
                TimetableConnectionCard(
                    url = timetableState.url,
                    detectedEventCount = timetableState.detectedEventCount,
                    onChange = { showTimetableDialog = true },
                    onRemove = onTimetableUrlRemove
                )

                timetableState.sessions.firstOrNull()?.let { nextSession ->
                    DepartureReminderSettings(
                        session = nextSession,
                        enabled = remindersEnabled,
                        leadMinutes = leadMinutes,
                        onEnabledChange = { remindersEnabled = it },
                        onLeadMinutesChange = { leadMinutes = it }
                    )
                }

                SectionHeader(title = "Upcoming sessions")
                if (timetableState.sessions.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Text(
                            text = "No classes for now.",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        timetableState.sessions.take(MAX_VISIBLE_SESSIONS).forEach { session ->
                            CourseSessionRow(session = session)
                        }
                        val hiddenCount = timetableState.sessions.size - MAX_VISIBLE_SESSIONS
                        if (hiddenCount > 0) {
                            Text(
                                text = "$hiddenCount later sessions are also connected.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            else -> EmptyTimetableState(
                errorMessage = timetableState.errorMessage,
                onConnect = { showTimetableDialog = true }
            )
        }
    }
}

@Composable
private fun LoadingTimetableState(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Text(
                text = "Downloading and checking calendar events...",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun EmptyTimetableState(
    errorMessage: String?,
    onConnect: () -> Unit,
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
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = "No timetable connected",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = errorMessage
                    ?: "Add your timetable subscription URL to load real calendar events.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onConnect) {
                Icon(Icons.Outlined.Link, contentDescription = null)
                Text(
                    text = if (errorMessage == null) "Connect URL" else "Check URL",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun TimetableConnectionCard(
    url: String,
    detectedEventCount: Int,
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
                        text = "Calendar verified",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "$host - $detectedEventCount calendar events detected",
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
    val notificationTime = session.etaMinutes?.let {
        session.departureReminderTime(leadMinutes)
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
                        text = session.etaMinutes?.let { "Travel $it min" }
                            ?: "Travel time pending",
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
                                text = if (notificationTime == null) {
                                    "Waiting for route ETA"
                                } else {
                                    "Notification time"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = notificationTime?.format(reminderTimeFormatter)
                                    ?: "Connect travel context to calculate",
                                style = if (notificationTime == null) {
                                    MaterialTheme.typography.bodyMedium
                                } else {
                                    MaterialTheme.typography.titleLarge
                                }
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
