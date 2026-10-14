package au.edu.unimelb.campuscompanion.ui.screens

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.isManualClass
import au.edu.unimelb.campuscompanion.ui.components.ClassEditorDialog
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

private enum class ScheduleView(val label: String) { List("List"), Week("Week") }
private val scheduleDateFormat = DateTimeFormatter.ofPattern("MM.dd EEE", Locale.ENGLISH)
private val scheduleTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val weekRangeFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val weekdayFormat = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

@Composable
fun ScheduleScreen(
    timetableState: TimetableState,
    onTimetableUrlSave: suspend (String) -> Result<Unit>,
    onTimetableRefresh: () -> Unit,
    onAddClass: suspend (CourseSession) -> Result<Unit>,
    onEditClass: suspend (CourseSession) -> Result<Unit>,
    onRemoveClass: suspend (String) -> Result<Unit>,
    onRestoreClass: suspend (String) -> Result<Unit>,
    onRestoreHiddenClasses: suspend () -> Result<Unit>,
    reminderCourseCount: Int,
    enabledReminderCount: Int,
    onOpenDepartureReminders: () -> Unit,
    onNavigate: (CourseSession) -> Unit,
    onOpenCompass: (CourseSession) -> Unit,
    modifier: Modifier = Modifier
) {
    var showTimetableDialog by rememberSaveable { mutableStateOf(false) }
    // The class being edited, or a request to add one; null while the editor is closed.
    var editorRequest by remember { mutableStateOf<ClassEditorRequest?>(null) }
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

    if (showTimetableDialog && timetableState.isLoaded) {
        TimetableUrlDialog(
            initialUrl = timetableState.url,
            onDismiss = { showTimetableDialog = false },
            onSave = onTimetableUrlSave
        )
    }
    editorRequest?.let { request ->
        val editing = request.session
        ClassEditorDialog(
            initial = editing,
            isEdited = editing != null && editing.id in timetableState.editedClassIds,
            onDismiss = { editorRequest = null },
            onSave = { session -> if (editing == null) onAddClass(session) else onEditClass(session) },
            onRemove = editing?.let { target -> suspend { onRemoveClass(target.id) } },
            onRestore = editing?.takeIf { !it.isManualClass }?.let { target -> suspend { onRestoreClass(target.id) } }
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
                Text("Timetable", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = when {
                        timetableState.isConnected -> timetableState.offlineNotice()
                            ?: "Browse your upcoming classes by list or week."
                        timetableState.hasTimetable -> "Classes you added by hand are shown below. Connect a timetable URL to import the rest."
                        else -> "No classes are shown until a timetable URL is verified."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item(key = "sync") { TimetableSyncStatus(timetableState, onRefresh = onTimetableRefresh) }
        when {
            timetableState.isLoading && !timetableState.isConnected -> item { LoadingTimetableState() }
            !timetableState.isLoaded -> Unit
            timetableState.hasTimetable -> {
                item(key = "reminders") {
                    DepartureReminderSettingRow(
                        courseCount = reminderCourseCount,
                        enabledCount = enabledReminderCount,
                        onClick = onOpenDepartureReminders
                    )
                }
                item(key = "edit") {
                    ScheduleEditRow(
                        hiddenClassCount = timetableState.hiddenClassCount,
                        onAddClass = { editorRequest = ClassEditorRequest(null) },
                        onRestoreHiddenClasses = onRestoreHiddenClasses
                    )
                }
                item(key = "view") {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ScheduleView.entries.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = view == option,
                                onClick = { view = option },
                                shape = SegmentedButtonDefaults.itemShape(index, ScheduleView.entries.size),
                                label = { Text(option.label) }
                            )
                        }
                    }
                }
                if (timetableState.sessions.isEmpty()) {
                    item { NoScheduleClasses("No classes for now.") }
                } else if (view == ScheduleView.List) {
                    item(key = "page-top") { SchedulePageControls(page, changePage) }
                    page.days.forEach { day ->
                        item(key = "date-${day.date}") { ScheduleDateHeader(day.date, today) }
                        items(day.sessions, key = { "session-${it.id}" }) { session ->
                            ScheduleSessionCard(session, onNavigate = { onNavigate(session) },
                                onOpenCompass = { onOpenCompass(session) },
                                onEdit = { editorRequest = ClassEditorRequest(session) })
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
                        item { NoScheduleClasses("No classes on this day.") }
                    }
                    items(daySessions, key = { "session-${it.id}" }) { session ->
                        ScheduleSessionCard(session, onNavigate = { onNavigate(session) },
                            onOpenCompass = { onOpenCompass(session) },
                            onEdit = { editorRequest = ClassEditorRequest(session) })
                    }
                }
            }
            else -> item {
                EmptyTimetableState(
                    errorMessage = timetableState.errorMessage,
                    onConnect = { showTimetableDialog = true },
                    onAddClass = { editorRequest = ClassEditorRequest(null) }
                )
            }
        }
    }
}

/** A request to open the class editor: [session] is the class to edit, or null to add one. */
private class ClassEditorRequest(val session: CourseSession?)

@Composable
private fun ScheduleEditRow(
    hiddenClassCount: Int,
    onAddClass: () -> Unit,
    onRestoreHiddenClasses: suspend () -> Result<Unit>
) {
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(onClick = onAddClass) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Add class", modifier = Modifier.padding(start = 6.dp))
        }
        if (hiddenClassCount > 0) {
            TextButton(
                enabled = !restoring,
                onClick = {
                    restoring = true
                    scope.launch {
                        try { onRestoreHiddenClasses() } finally { restoring = false }
                    }
                }
            ) {
                Text(if (hiddenClassCount == 1) "Show 1 hidden class" else "Show $hiddenClassCount hidden classes")
            }
        }
    }
}

@Composable
private fun SchedulePageControls(page: SchedulePage, onPageChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(enabled = page.index > 0, onClick = { onPageChange(page.index - 1) }) { Text("Previous") }
            Text(
                "Page ${page.index + 1} of ${page.pageCount}",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(enabled = page.index < page.pageCount - 1, onClick = { onPageChange(page.index + 1) }) {
                Text("Next")
            }
        }
        Text(
            "Classes ${page.firstSessionNumber}–${page.lastSessionNumber} of ${page.totalSessions}",
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
    val start = days.first().date
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(enabled = start > firstWeek, onClick = { onSelectDate(selectedDate.minusWeeks(1)) }) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous week")
            }
            Text(
                "${start.format(weekRangeFormat)} – ${days.last().date.format(weekRangeFormat)}",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(enabled = start < lastWeek, onClick = { onSelectDate(selectedDate.plusWeeks(1)) }) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Next week")
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
                                contentDescription = "${day.date.format(weekRangeFormat)}, ${day.date.format(weekdayFormat)}, ${day.sessions.size} classes"
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
                "${days.sumOf { it.sessions.size }} classes this week",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onSelectDate(today) }) { Text("Today") }
        }
    }
}

@Composable
private fun ScheduleSessionCard(
    session: CourseSession,
    onNavigate: () -> Unit,
    onOpenCompass: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${session.startTime.format(scheduleTimeFormat)}–${session.endTime.format(scheduleTimeFormat)}" +
                        if (session.end.toLocalDate() != session.startDate) " (${session.end.format(scheduleDateFormat)})" else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (session.isManualClass) {
                    Text(
                        "Added by hand",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit class")
                }
            }
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
            TextButton(onClick = onNavigate) { Text("Navigate with Google Maps") }
            TextButton(onClick = onOpenCompass) { Text("Direction to building") }
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
    onAddClass: () -> Unit,
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
            TextButton(onClick = onAddClass) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(text = "Add class", modifier = Modifier.padding(start = 8.dp))
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
                        "$enabledCount of $courseCount class reminders enabled"
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
