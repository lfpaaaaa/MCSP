package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.components.TimetableSyncStatus
import au.edu.unimelb.campuscompanion.ui.components.TimetableUrlDialog
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.ScheduleDay
import au.edu.unimelb.campuscompanion.ui.model.SchedulePage
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.model.buildSchedulePage
import au.edu.unimelb.campuscompanion.ui.model.buildScheduleWeek
import au.edu.unimelb.campuscompanion.ui.model.reminderActivity
import au.edu.unimelb.campuscompanion.ui.model.scheduleWeekStart
import au.edu.unimelb.campuscompanion.ui.model.trimTrailingCommas
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private enum class ScheduleView(val label: Int) { List(R.string.schedule_view_list), Week(R.string.schedule_view_week) }
private val scheduleDateFormat = DateTimeFormatter.ofPattern("MM.dd EEE", Locale.ENGLISH)
private val scheduleTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val weekRangeFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val weekdayFormat = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

@Composable
fun ScheduleScreen(
    timetableState: TimetableState,
    onTimetableUrlSave: suspend (String) -> Result<Unit>,
    onTimetableRefresh: () -> Unit,
    reminderCourseCount: Int,
    enabledReminderCount: Int,
    onOpenDepartureReminders: () -> Unit,
    onNavigate: (CourseSession) -> Unit,
    modifier: Modifier = Modifier
) {
    val textResources = textResources()
    var showTimetableDialog by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(ScheduleView.List) }
    var pageIndex by rememberSaveable(timetableState.url) { mutableIntStateOf(0) }
    val today = LocalDate.now(timetableState.sessions.firstOrNull()?.start?.zone ?: ZoneId.systemDefault())
    // A null selection follows the first available class while the saved timetable loads.
    var selectedDayEpoch by rememberSaveable(timetableState.url) { mutableStateOf<Long?>(null) }
    val firstClassDate = timetableState.sessions.minOfOrNull { it.startDate } ?: today
    val lastClassDate = timetableState.sessions.maxOfOrNull { it.startDate } ?: today
    val firstWeek = scheduleWeekStart(minOf(today, firstClassDate))
    val lastWeek = scheduleWeekStart(maxOf(today, lastClassDate))
    val selectedDate = (selectedDayEpoch?.let(LocalDate::ofEpochDay) ?: firstClassDate)
        .coerceIn(firstWeek, lastWeek.plusDays(6))
    val page = remember(timetableState.sessions, pageIndex) {
        buildSchedulePage(timetableState.sessions, pageIndex)
    }
    val week = remember(timetableState.sessions, selectedDate) {
        buildScheduleWeek(timetableState.sessions, selectedDate)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val changePage: (Int) -> Unit = { requested ->
        pageIndex = requested
        scope.launch { listState.scrollToItem(3) }
    }
    LaunchedEffect(page.index) { pageIndex = page.index }
    LaunchedEffect(selectedDate) {
        if (selectedDayEpoch != null) selectedDayEpoch = selectedDate.toEpochDay()
    }

    if (showTimetableDialog) {
        TimetableUrlDialog(
            initialUrl = timetableState.url,
            onDismiss = { showTimetableDialog = false },
            onSave = onTimetableUrlSave
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(textResources.getString(R.string.profile_timetable), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = if (timetableState.isConnected) {
                        timetableState.offlineNotice()?.resolve(textResources) ?: textResources.getString(R.string.schedule_browse_your_upcoming_classes_by_list_or_week)
                    } else {
                        textResources.getString(R.string.schedule_no_classes_are_shown_until_a_timetable_url)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item(key = "sync") { TimetableSyncStatus(timetableState, onRefresh = onTimetableRefresh) }
        when {
            timetableState.isLoading && !timetableState.isConnected -> item { LoadingTimetableState() }
            timetableState.isConnected -> {
                item(key = "reminders") {
                    DepartureReminderSettingRow(
                        courseCount = reminderCourseCount,
                        enabledCount = enabledReminderCount,
                        onClick = onOpenDepartureReminders
                    )
                }
                item(key = "view") {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ScheduleView.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = view == option,
                                onClick = { view = option },
                                shape = SegmentedButtonDefaults.itemShape(index, ScheduleView.entries.size),
                                label = { Text(textResources.getString(option.label)) }
                            )
                        }
                    }
                }
                if (timetableState.sessions.isEmpty()) {
                    item { NoScheduleClasses(textResources.getString(R.string.schedule_no_classes_for_now)) }
                } else if (view == ScheduleView.List) {
                    item(key = "page-top") { SchedulePageControls(page, changePage) }
                    page.days.forEach { day ->
                        item(key = "date-${day.date}") { ScheduleDateHeader(day.date, today) }
                        items(day.sessions, key = { "session-${it.id}" }) { session ->
                            ScheduleSessionCard(session, onNavigate = { onNavigate(session) })
                        }
                    }
                    if (page.pageCount > 1) {
                        item(key = "page-bottom") { SchedulePageControls(page, changePage) }
                    }
                } else {
                    item(key = "week") {
                        ScheduleWeekSelector(
                            days = week,
                            selectedDate = selectedDate,
                            firstWeek = firstWeek,
                            lastWeek = lastWeek,
                            today = today,
                            onSelectDate = { selectedDayEpoch = it.toEpochDay() }
                        )
                    }
                    item(key = "selected-date") { ScheduleDateHeader(selectedDate, today) }
                    val daySessions = week.first { it.date == selectedDate }.sessions
                    if (daySessions.isEmpty()) {
                        item { NoScheduleClasses(textResources.getString(R.string.schedule_no_classes_on_this_day)) }
                    }
                    items(daySessions, key = { "session-${it.id}" }) { session ->
                        ScheduleSessionCard(session, onNavigate = { onNavigate(session) })
                    }
                }
            }
            else -> item {
                EmptyTimetableState(
                    errorMessage = timetableState.errorMessage?.resolve(textResources),
                    onConnect = { showTimetableDialog = true }
                )
            }
        }
    }
}

@Composable
private fun SchedulePageControls(page: SchedulePage, onPageChange: (Int) -> Unit) {
    val textResources = textResources()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(enabled = page.index > 0, onClick = { onPageChange(page.index - 1) }) { Text(textResources.getString(R.string.document_attachment_preview_previous)) }
            Text(
                textResources.getString(R.string.schedule_page_of, page.index + 1, page.pageCount),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(enabled = page.index < page.pageCount - 1, onClick = { onPageChange(page.index + 1) }) {
                Text(textResources.getString(R.string.document_attachment_preview_next))
            }
        }
        Text(
            textResources.getString(R.string.schedule_classes_of, page.firstSessionNumber, page.lastSessionNumber, page.totalSessions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScheduleDateHeader(date: LocalDate, today: LocalDate) {
    Text(
        text = date.format(scheduleDateFormat) + if (date.year != today.year) " · ${date.year}" else "",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
private fun ScheduleWeekSelector(
    days: List<ScheduleDay>,
    selectedDate: LocalDate,
    firstWeek: LocalDate,
    lastWeek: LocalDate,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit
) {
    val textResources = textResources()
    val start = days.first().date
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(enabled = start > firstWeek, onClick = { onSelectDate(selectedDate.minusWeeks(1)) }) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = textResources.getString(R.string.schedule_previous_week))
            }
            Text(
                textResources.getString(R.string.schedule_week_date_range, start.format(weekRangeFormat), days.last().date.format(weekRangeFormat)),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(enabled = start < lastWeek, onClick = { onSelectDate(selectedDate.plusWeeks(1)) }) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = textResources.getString(R.string.schedule_next_week))
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val dayWidth = ((maxWidth - 24.dp) / 7).coerceAtLeast(44.dp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                days.forEach { day ->
                    val selected = selectedDate == day.date
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(dayWidth)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onSelectDate(day.date) })
                            .semantics {
                                contentDescription = textResources.getQuantityString(R.plurals.day_class_count, day.sessions.size, day.date.format(weekRangeFormat), day.date.format(weekdayFormat), day.sessions.size)
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(day.date.format(weekdayFormat), style = MaterialTheme.typography.labelMedium)
                            Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (day.sessions.isEmpty()) "–" else day.sessions.size.toString(),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                textResources.getQuantityString(R.plurals.week_class_count, days.sumOf { it.sessions.size }, days.sumOf { it.sessions.size }),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onSelectDate(today) }) { Text(textResources.getString(R.string.campus_components_today)) }
        }
    }
}

@Composable
private fun ScheduleSessionCard(session: CourseSession, onNavigate: () -> Unit) {
    val textResources = textResources()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${session.startTime.format(scheduleTimeFormat)}–${session.endTime.format(scheduleTimeFormat)}" +
                    if (session.end.toLocalDate() != session.startDate) " (${session.end.format(scheduleDateFormat)})" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = listOfNotNull(
                    session.code,
                    session.reminderActivity()?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
                ).joinToString(" · "),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                session.title.trimTrailingCommas(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val location = listOf(session.location, session.room).filter(String::isNotBlank).joinToString(" - ")
            if (location.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onNavigate) { Text(textResources.getString(R.string.campus_components_navigate_with_google_maps)) }
        }
    }
}

@Composable
private fun NoScheduleClasses(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Text(message, modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingTimetableState(modifier: Modifier = Modifier) {
    val textResources = textResources()
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
                text = textResources.getString(R.string.schedule_downloading_and_checking_calendar_events),
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
    val textResources = textResources()
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
                text = textResources.getString(R.string.profile_no_timetable_connected),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = errorMessage
                    ?: textResources.getString(R.string.schedule_add_your_timetable_subscription_url_to_load_real),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onConnect) {
                Icon(Icons.Outlined.Link, contentDescription = null)
                Text(
                    text = if (errorMessage == null) textResources.getString(R.string.schedule_connect_url) else textResources.getString(R.string.schedule_check_url),
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
    val textResources = textResources()
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
                    text = textResources.getString(R.string.campus_companion_app_departure_reminders),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (courseCount == 0) {
                        textResources.getString(R.string.course_reminder_settings_no_courses_available)
                    } else {
                        textResources.getQuantityString(R.plurals.enabled_reminder_count, courseCount, enabledCount, courseCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = textResources.getString(R.string.schedule_open_departure_reminder_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
