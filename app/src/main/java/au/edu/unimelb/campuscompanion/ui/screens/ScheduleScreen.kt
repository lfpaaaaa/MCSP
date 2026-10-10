package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.components.CourseSessionRow
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.components.TimetableUrlDialog
import au.edu.unimelb.campuscompanion.ui.model.TimetableState

private const val MAX_VISIBLE_SESSIONS = 50

@Composable
fun ScheduleScreen(
    timetableState: TimetableState,
    onTimetableUrlSave: suspend (String) -> Result<Unit>,
    reminderCourseCount: Int,
    enabledReminderCount: Int,
    onOpenDepartureReminders: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                DepartureReminderSettingRow(
                    courseCount = reminderCourseCount,
                    enabledCount = enabledReminderCount,
                    onClick = onOpenDepartureReminders
                )

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
private fun DepartureReminderSettingRow(
    courseCount: Int,
    enabledCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
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
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Departure reminders",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (courseCount == 0) {
                        "No courses available"
                    } else {
                        "$enabledCount of $courseCount courses enabled"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Open departure reminder settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
