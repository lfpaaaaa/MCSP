package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderSeries
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderTiming
import au.edu.unimelb.campuscompanion.ui.model.MAX_COURSE_REMINDER_LEAD_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.reminderTiming
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val courseTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM - h:mm a")
private val departureTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

@Composable
fun CourseReminderSettingsScreen(
    courses: List<CourseReminderSeries>,
    preferences: Map<String, CourseReminderPreference>,
    onPreferenceChange: (String, CourseReminderPreference) -> Unit,
    modifier: Modifier = Modifier,
    travelSnapshot: TravelSnapshot = TravelSnapshot()
) {
    val textResources = textResources()
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
    val timing = session.reminderTiming(travelSnapshot, preference.leadMinutes)
    val notificationTime = (timing as? CourseReminderTiming.Ready)?.warningAt
    val preparationMessage = if (preference.leadMinutes == 0) {
        textResources.getString(R.string.course_reminder_settings_leave_when_the_reminder_arrives)
    } else {
        textResources.getString(R.string.course_reminder_settings_prepare_to_leave_within_min, preference.leadMinutes)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(course.displayName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

                SettingSwitchRow(
                    title = textResources.getString(R.string.course_reminder_settings_departure_reminder),
                    description = textResources.getString(R.string.copy_reminders, course.displayName),
                    checked = preference.enabled,
                    onCheckedChange = { onPreferenceChange(preference.copy(enabled = it)) }
                )

                if (preference.enabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = textResources.getString(R.string.course_reminder_settings_prepare_min_before_departure, preference.leadMinutes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            modifier = Modifier.semantics {
                                contentDescription = textResources.getString(R.string.a11y_preparation_time)
                                stateDescription = textResources.getString(R.string.course_reminder_settings_prepare_min_before_departure, preference.leadMinutes)
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
                            steps = 11
                        )
                        Text(
                            text = textResources.getString(R.string.departure_urgent_explanation),
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
                            Column(
                                modifier = Modifier.padding(start = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = notificationTime?.let { textResources.getString(R.string.reminder_prepare_at, it.format(departureTimeFormatter)) }
                                        ?: textResources.getString(R.string.course_reminder_settings_calculated_from_your_live_route),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (timing is CourseReminderTiming.Ready) {
                                    Text(textResources.getString(R.string.home_leave_by, timing.leaveBy.format(departureTimeFormatter)))
                                }
                                Text(
                                    text = if (timing !is CourseReminderTiming.Ready) {
                                        textResources.getString(R.string.course_reminder_settings_live_travel_time_will_be_included, preparationMessage)
                                    } else {
                                        textResources.getString(R.string.course_reminder_settings_travel_time_min, preparationMessage, timing.travelMinutes)
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
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

private fun CourseReminderSeries.summaryLine(preference: CourseReminderPreference, textResources: android.content.res.Resources): String {
    val recurrence = textResources.getQuantityString(R.plurals.class_count, occurrenceCount, occurrenceCount)
    val reminder = if (preference.enabled) {
        textResources.getString(R.string.reminder_min_early, preference.leadMinutes)
    } else {
        textResources.getString(R.string.reminder_off)
    }
    return textResources.getString(R.string.copy_label, nextSession.start.format(courseTimeFormatter), recurrence, reminder)
}
