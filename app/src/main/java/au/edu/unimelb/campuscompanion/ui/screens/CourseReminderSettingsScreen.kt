package au.edu.unimelb.campuscompanion.ui.screens

import au.edu.unimelb.campuscompanion.ui.model.trimTrailingCommas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderSeries
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderTiming
import au.edu.unimelb.campuscompanion.ui.model.MAX_COURSE_REMINDER_LEAD_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.MAX_COURSE_REMINDER_PREPARATION_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.reminderTiming
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val courseTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM - h:mm a")
private val departureTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
private val departureClockFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val departureDateFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
fun CourseReminderSettingsScreen(
    courses: List<CourseReminderSeries>,
    preferences: Map<String, CourseReminderPreference>,
    onPreferenceChange: (String, CourseReminderPreference) -> Unit,
    modifier: Modifier = Modifier,
    travelSnapshot: TravelSnapshot = TravelSnapshot()
) {
    var selectedCourseKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCourse = courses.firstOrNull { it.key == selectedCourseKey }

    selectedCourse?.let { course ->
        CourseReminderDialog(
            course = course,
            preference = preferences[course.key] ?: CourseReminderPreference(),
            travelSnapshot = travelSnapshot,
            onPreferenceChange = { updated ->
                onPreferenceChange(course.key, updated)
            },
            onDismiss = { selectedCourseKey = null }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        if (courses.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "No courses available",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Connect a timetable with course events first.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    text = "CLASS REMINDERS",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Text(
                    text = "Control lecture, tutorial and workshop reminders separately. Changes are saved automatically.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(courses, key = CourseReminderSeries::key) { course ->
                val preference = preferences[course.key] ?: CourseReminderPreference()
                ListItem(
                    modifier = Modifier.clickable { selectedCourseKey = course.key },
                    leadingContent = {
                        Icon(
                            imageVector = if (preference.enabled) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                            contentDescription = null,
                            tint = if (preference.enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    headlineContent = {
                        Text(
                            text = course.displayName,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(course.title.trimTrailingCommas())
                            Text(
                                text = course.summaryLine(preference),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingContent = {
                        Switch(
                            modifier = Modifier.semantics { contentDescription = "${course.displayName} reminders" },
                            checked = preference.enabled,
                            onCheckedChange = { enabled ->
                                onPreferenceChange(
                                    course.key,
                                    preference.copy(enabled = enabled)
                                )
                            }
                        )
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
}

@Composable
private fun CourseReminderDialog(
    course: CourseReminderSeries,
    preference: CourseReminderPreference,
    travelSnapshot: TravelSnapshot,
    onPreferenceChange: (CourseReminderPreference) -> Unit,
    onDismiss: () -> Unit
) {
    val session = course.nextSession
    val timing = session.reminderTiming(
        travelSnapshot,
        preference.leadMinutes,
        preference.preparationMinutes
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(course.displayName) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = course.title.trimTrailingCommas(),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = session.start.format(courseTimeFormatter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val destination = listOf(course.room, course.location)
                        .filter(String::isNotBlank)
                        .joinToString(" - ")
                    if (destination.isNotBlank()) {
                        Text(
                            text = destination,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Departure reminder",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        modifier = Modifier.semantics { contentDescription = "${course.displayName} reminders" },
                        checked = preference.enabled,
                        onCheckedChange = { enabled ->
                            onPreferenceChange(preference.copy(enabled = enabled))
                        }
                    )
                }

                if (preference.enabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Arrival buffer: ${preference.leadMinutes} minutes",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "How early do you want to arrive before class? This moves your leave-by time earlier. Weather and time to reach the classroom may add more.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            modifier = Modifier.semantics {
                                contentDescription = "${course.displayName} arrival buffer"
                            },
                            value = preference.leadMinutes.toFloat(),
                            onValueChange = { value ->
                                onPreferenceChange(
                                    preference.copy(
                                        leadMinutes = (value / 5f).roundToInt() * 5
                                    )
                                )
                            },
                            valueRange = 0f..MAX_COURSE_REMINDER_LEAD_MINUTES.toFloat(),
                            steps = MAX_COURSE_REMINDER_LEAD_MINUTES / 5 - 1
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Prepare before departure: ${preference.preparationMinutes} minutes",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "How much time do you need to get ready before leaving? This moves the warning earlier without changing your leave-by time. Choose 0 to be warned at leave-by.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            modifier = Modifier.semantics {
                                contentDescription = "${course.displayName} prepare before departure"
                            },
                            value = preference.preparationMinutes.toFloat(),
                            onValueChange = { value ->
                                onPreferenceChange(
                                    preference.copy(
                                        preparationMinutes = (value / 5f).roundToInt() * 5
                                    )
                                )
                            },
                            valueRange = 0f..MAX_COURSE_REMINDER_PREPARATION_MINUTES.toFloat(),
                            steps = MAX_COURSE_REMINDER_PREPARATION_MINUTES / 5 - 1
                        )
                    }

                    Text(
                        text = "Prepare-to-leave warnings are skipped when you’re already heading to class or have arrived.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

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
                            Column(
                                modifier = Modifier.padding(start = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                when (timing) {
                                    is CourseReminderTiming.Ready -> {
                                        Text(
                                            text = "Leave by ${timing.leaveBy.format(departureClockFormatter)}",
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        if (timing.leaveBy.toLocalDate() != session.start.toLocalDate()) {
                                            Text(
                                                text = timing.leaveBy.format(departureDateFormatter),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        Text(
                                            text = "Prepare to leave: ${timing.warningAt.format(departureTimeFormatter)}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "Travel time: ${timing.travelMinutes} min. Extra allowances are included in the departure time.",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    CourseReminderTiming.Updating -> {
                                        Text("Updating live times…", style = MaterialTheme.typography.titleMedium)
                                        Text("Applying your arrival buffer and preparation time.", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    CourseReminderTiming.MissingTravelTime -> {
                                        Text("Waiting for live travel time", style = MaterialTheme.typography.titleMedium)
                                        Text("Departure and reminder times will appear when a travel estimate is available.", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    CourseReminderTiming.NotTracked -> {
                                        Text("Live timing unavailable", style = MaterialTheme.typography.titleMedium)
                                        Text("Live times are shown for your current or next class.", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

private fun CourseReminderSeries.summaryLine(preference: CourseReminderPreference): String {
    val recurrence = if (occurrenceCount == 1) "1 class" else "$occurrenceCount classes"
    val reminder = if (preference.enabled) {
        "${preference.leadMinutes} min arrival buffer · ${preference.preparationMinutes} min preparation"
    } else {
        "Off"
    }
    return "${nextSession.start.format(courseTimeFormatter)} - $recurrence - $reminder"
}
