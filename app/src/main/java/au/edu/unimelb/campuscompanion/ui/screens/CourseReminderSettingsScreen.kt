package au.edu.unimelb.campuscompanion.ui.screens

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.push.DepartureNotifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.OutlinedButton
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
    val textResources = textResources()
    val context = LocalContext.current
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
        item(key = "notification-settings") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = textResources.getString(R.string.departure_notification_style),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { DepartureNotifier.openSettings(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(textResources.getString(R.string.departure_notification_settings))
                }
            }
        }
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
                            text = textResources.getString(R.string.course_reminder_settings_no_courses_available),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = textResources.getString(R.string.course_reminder_settings_connect_a_timetable_with_course_events_first),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    text = textResources.getString(R.string.label_class_reminders),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Text(
                    text = textResources.getString(R.string.course_reminder_settings_control_lecture_tutorial_and_workshop_reminders_separately_changes),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(courses, key = CourseReminderSeries::key) { course ->
                val preference = preferences[course.key] ?: CourseReminderPreference()
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = textResources.getString(R.string.a11y_edit_reminder, course.displayName)) { selectedCourseKey = course.key },
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
                                text = course.summaryLine(preference, textResources),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingContent = {
                        Switch(
                            modifier = Modifier.semantics { contentDescription = textResources.getString(R.string.copy_reminders, course.displayName) },
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
    val textResources = textResources()
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
                        text = textResources.getString(R.string.course_reminder_settings_departure_reminder),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        modifier = Modifier.semantics { contentDescription = textResources.getString(R.string.copy_reminders, course.displayName) },
                        checked = preference.enabled,
                        onCheckedChange = { enabled ->
                            onPreferenceChange(preference.copy(enabled = enabled))
                        }
                    )
                }

                if (preference.enabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = textResources.getString(R.string.context_reminder_arrival_buffer, preference.leadMinutes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = textResources.getString(R.string.context_reminder_arrival_explanation),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            modifier = Modifier.semantics {
                                contentDescription = textResources.getString(R.string.context_reminder_arrival_a11y, course.displayName)
                                stateDescription = textResources.getString(R.string.context_reminder_arrival_buffer, preference.leadMinutes)
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
                            text = textResources.getString(R.string.context_reminder_preparation, preference.preparationMinutes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = textResources.getString(R.string.context_reminder_preparation_explanation),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            modifier = Modifier.semantics {
                                contentDescription = textResources.getString(R.string.context_reminder_preparation_a11y, course.displayName)
                                stateDescription = textResources.getString(R.string.context_reminder_preparation, preference.preparationMinutes)
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
                        text = textResources.getString(R.string.context_reminder_suppression),
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
                                            text = textResources.getString(R.string.home_leave_by, timing.leaveBy.format(departureClockFormatter)),
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
                                            text = textResources.getString(R.string.context_reminder_prepare_at, timing.warningAt.format(departureTimeFormatter)),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = textResources.getString(R.string.context_reminder_travel_time, timing.travelMinutes),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    CourseReminderTiming.Updating -> {
                                        Text(textResources.getString(R.string.context_reminder_updating), style = MaterialTheme.typography.titleMedium)
                                        Text(textResources.getString(R.string.context_reminder_applying), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    CourseReminderTiming.MissingTravelTime -> {
                                        Text(textResources.getString(R.string.context_reminder_waiting), style = MaterialTheme.typography.titleMedium)
                                        Text(textResources.getString(R.string.context_reminder_waiting_detail), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    CourseReminderTiming.NotTracked -> {
                                        Text(textResources.getString(R.string.context_reminder_unavailable), style = MaterialTheme.typography.titleMedium)
                                        Text(textResources.getString(R.string.context_reminder_unavailable_detail), style = MaterialTheme.typography.bodyMedium)
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
                Text(textResources.getString(R.string.course_reminder_settings_done))
            }
        }
    )
}

private fun CourseReminderSeries.summaryLine(
    preference: CourseReminderPreference,
    textResources: android.content.res.Resources
): String {
    val recurrence = textResources.getQuantityString(R.plurals.class_count, occurrenceCount, occurrenceCount)
    val reminder = if (preference.enabled) {
        textResources.getString(R.string.context_reminder_summary, preference.leadMinutes, preference.preparationMinutes)
    } else {
        textResources.getString(R.string.reminder_off)
    }
    return textResources.getString(R.string.copy_label, nextSession.start.format(courseTimeFormatter), recurrence, reminder)
}
