package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.components.CampusIcons
import au.edu.unimelb.campuscompanion.ui.components.CourseSessionRow
import au.edu.unimelb.campuscompanion.ui.components.GroupUpdateRow
import au.edu.unimelb.campuscompanion.ui.components.MetricItem
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.components.StatusPill
import au.edu.unimelb.campuscompanion.ui.components.TimetableUrlDialog
import au.edu.unimelb.campuscompanion.ui.model.MockCampusData

@Composable
fun HomeScreen(
    timetableUrl: String,
    onTimetableUrlSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasTimetable = timetableUrl.isNotBlank()
    var showTimetableDialog by rememberSaveable(hasTimetable) {
        mutableStateOf(!hasTimetable)
    }
    val context = MockCampusData.currentContext

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Today",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = if (hasTimetable) {
                    "Your next class, route status, and group updates are ready."
                } else {
                    "Connect your timetable to see classes and departure reminders."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!hasTimetable) {
            TimetableSetupPrompt(
                onConnect = { showTimetableDialog = true }
            )
        }

        if (hasTimetable) {
            ElevatedCard(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = context.headline,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = context.detail,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        StatusPill(
                            label = "Action",
                            status = context.status
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        MetricItem(
                            icon = CampusIcons.Time,
                            label = "ETA",
                            value = "${context.etaMinutes} min",
                            modifier = Modifier.weight(1f)
                        )
                        MetricItem(
                            icon = CampusIcons.Location,
                            label = "To",
                            value = context.destinationLabel,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {}) {
                            Icon(Icons.Outlined.NearMe, contentDescription = null)
                            Text(
                                text = "Navigate",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        OutlinedButton(onClick = {}) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null)
                            Text(
                                text = "Open chat",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            SectionHeader(title = "Upcoming classes", actionLabel = "View all")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MockCampusData.sessions.take(2).forEach { session ->
                    CourseSessionRow(session = session)
                }
            }
        }

        SectionHeader(title = "Group updates", actionLabel = "Groups")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MockCampusData.groups.take(1).forEach { group ->
                GroupUpdateRow(group = group)
            }
        }

        if (hasTimetable) {
            Text(
                text = "Mock data is used until timetable, sensing, and Supabase layers are connected.",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TimetableSetupPrompt(
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Connect your timetable",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Add the MyTimetable subscription URL.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(onClick = onConnect) {
                Text("Add URL")
            }
        }
    }
}
