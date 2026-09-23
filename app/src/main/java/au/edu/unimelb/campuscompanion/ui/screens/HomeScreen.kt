package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.components.CourseSessionRow
import au.edu.unimelb.campuscompanion.ui.components.GroupUpdateRow
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.components.TimetableUrlDialog
import au.edu.unimelb.campuscompanion.ui.model.TimetableState

@Composable
fun HomeScreen(
    timetableState: TimetableState,
    onTimetableUrlSave: suspend (String) -> Result<Unit>,
    modifier: Modifier = Modifier
) {
    var showTimetableDialog by rememberSaveable(
        timetableState.isConnected,
        timetableState.isLoading
    ) {
        mutableStateOf(!timetableState.isConnected && !timetableState.isLoading)
    }

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
                text = "Overview",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = when {
                    timetableState.isLoading -> "Checking your saved timetable URL."
                    timetableState.isConnected -> {
                        "Classes and course groups below come from your connected timetable."
                    }
                    else -> "Connect your timetable to load classes, reminders, and course groups."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when {
            timetableState.isLoading -> TimetableLoadingCard()
            !timetableState.isConnected -> TimetableSetupPrompt(
                errorMessage = timetableState.errorMessage,
                onConnect = { showTimetableDialog = true }
            )
            else -> {
                ImportSummaryCard(
                    detectedEventCount = timetableState.detectedEventCount,
                    groupCount = timetableState.groups.size
                )

                SectionHeader(title = "Upcoming classes")
                if (timetableState.sessions.isEmpty()) {
                    EmptyImportedSection(
                        text = "No classes for now."
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        timetableState.sessions.take(3).forEach { session ->
                            CourseSessionRow(session = session)
                        }
                    }
                }

                SectionHeader(title = "Course groups")
                if (timetableState.groups.isEmpty()) {
                    EmptyImportedSection(
                        text = "No groups are available to add."
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        timetableState.groups.take(2).forEach { group ->
                            GroupUpdateRow(group = group)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimetableLoadingCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator()
            Column {
                Text(
                    text = "Loading timetable",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Downloading and checking calendar events.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TimetableSetupPrompt(
    errorMessage: String?,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = null
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Connect your timetable",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = errorMessage ?: "Add the private MyTimetable subscription URL.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(onClick = onConnect) {
                Text(if (errorMessage == null) "Add URL" else "Retry")
            }
        }
    }
}

@Composable
private fun ImportSummaryCard(
    detectedEventCount: Int,
    groupCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null)
            Column {
                Text(
                    text = "Timetable connected",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "$detectedEventCount calendar events detected. $groupCount groups available.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun EmptyImportedSection(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
