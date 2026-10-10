package au.edu.unimelb.campuscompanion.ui.screens

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
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DirectionsTransit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.ui.about.RoutingCredit
import au.edu.unimelb.campuscompanion.ui.components.CourseSessionRow
import au.edu.unimelb.campuscompanion.ui.components.GroupUpdateRow
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.components.StatusPill
import au.edu.unimelb.campuscompanion.ui.components.displayName
import au.edu.unimelb.campuscompanion.ui.components.TimetableUrlDialog
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val homeTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val nextClassDateFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM")

@Composable
fun HomeScreen(
    timetableState: TimetableState,
    travelPreferences: TravelPreferences,
    onTimetableUrlSave: suspend (String) -> Result<Unit>,
    onOpenGroup: (CourseGroup) -> Unit,
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
                val now = ZonedDateTime.now()
                val agenda = buildHomeAgenda(
                    sessions = timetableState.sessions,
                    now = now
                )
                val nextClassIsToday = agenda.nextClass?.let { nextClass ->
                    val localNow = now.withZoneSameInstant(nextClass.start.zone)
                    nextClass.startDate == localNow.toLocalDate()
                } == true

                if (agenda.nextClass != null) {
                    NextClassCard(
                        session = agenda.nextClass,
                        travelPreferences = travelPreferences,
                        now = now
                    )
                }

                SectionHeader(title = "Today's classes")
                if (agenda.todayClasses.isEmpty()) {
                    EmptyImportedSection(
                        text = if (nextClassIsToday) {
                            "No other classes today."
                        } else {
                            "Your timetable has no classes remaining today."
                        }
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        agenda.todayClasses.forEach { session ->
                            CourseSessionRow(session = session)
                        }
                    }
                }

                ImportSummaryCard(
                    detectedEventCount = timetableState.detectedEventCount,
                    groupCount = timetableState.groups.size,
                    notice = timetableState.offlineNotice()
                )

                SectionHeader(title = "Course groups")
                if (timetableState.groups.isEmpty()) {
                    EmptyImportedSection(
                        text = "No groups are available to add."
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        timetableState.groups.take(2).forEach { group ->
                            GroupUpdateRow(
                                group = group,
                                onClick = { onOpenGroup(group) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextClassCard(
    session: CourseSession,
    travelPreferences: TravelPreferences,
    now: ZonedDateTime,
    modifier: Modifier = Modifier
) {
    val status = session.statusAt(now)
    val travel = selectTravelSummary(session, travelPreferences)
    val locationText = listOf(session.location, session.room)
        .filter(String::isNotBlank)
        .joinToString(" - ")

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "NEXT CLASS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.code,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${session.startDate.format(nextClassDateFormatter)}  ${session.startTime.format(homeTimeFormatter)} - ${session.endTime.format(homeTimeFormatter)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                StatusPill(
                    label = status.displayName(),
                    status = status
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = locationText,
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TravelMetric(
                    icon = Icons.Outlined.Route,
                    label = "Distance",
                    value = travel.distanceMeters?.let(::formatTravelDistance) ?: "Waiting for location",
                    modifier = Modifier.weight(1f)
                )
                TravelMetric(
                    icon = travel.mode?.icon() ?: Icons.Outlined.AccessTime,
                    label = (travel.mode?.displayName ?: "Travel time") + if (travel.isApproximate) " (approx.)" else "",
                    value = travel.durationMinutes?.let { if (travel.isApproximate) "~$it min" else "$it min" } ?: "Route pending",
                    modifier = Modifier.weight(1f)
                )
            }
            RoutingCredit()
        }
    }
}

@Composable
private fun TravelMetric(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal data class HomeAgenda(
    val nextClass: CourseSession?,
    val todayClasses: List<CourseSession>
)

internal fun buildHomeAgenda(
    sessions: List<CourseSession>,
    now: ZonedDateTime
): HomeAgenda {
    val upcomingSessions = sessions
        .filter { session ->
            val localNow = now.withZoneSameInstant(session.start.zone)
            session.end.isAfter(localNow)
        }
        .sortedBy { it.start.toInstant() }
    val nextClass = upcomingSessions.firstOrNull()
    val remainingToday = upcomingSessions.filter { session ->
        val localNow = now.withZoneSameInstant(session.start.zone)
        session.startDate == localNow.toLocalDate() && session.id != nextClass?.id
    }

    return HomeAgenda(
        nextClass = nextClass,
        todayClasses = remainingToday
    )
}

internal data class SelectedTravelSummary(
    val distanceMeters: Int?,
    val mode: TravelMode?,
    val durationMinutes: Int?,
    /** The time is a straight-line estimate, shown with a tilde and labelled as approximate. */
    val isApproximate: Boolean = false
)

internal fun selectTravelSummary(
    session: CourseSession,
    preferences: TravelPreferences
): SelectedTravelSummary {
    val estimate = session.routeEstimate
        ?: return SelectedTravelSummary(null, null, session.etaMinutes)
    val minutesByMode = listOf(
        TravelMode.Walking to estimate.walkingMinutes,
        TravelMode.PublicTransport to estimate.publicTransportMinutes,
        TravelMode.Driving to estimate.drivingMinutes
    )
    val preferred = preferences.preferredMode(estimate.distanceMeters)
    // The travel engine only times the mode it chose; show that mode rather than a label without a time.
    val (mode, minutes) = minutesByMode.firstOrNull { (mode, minutes) -> mode == preferred && minutes != null }
        ?: minutesByMode.firstOrNull { (_, minutes) -> minutes != null }
        ?: (preferred to session.etaMinutes)

    return SelectedTravelSummary(
        distanceMeters = estimate.distanceMeters,
        mode = mode,
        durationMinutes = minutes,
        isApproximate = estimate.isApproximate
    )
}

private fun formatTravelDistance(distanceMeters: Int): String {
    if (distanceMeters < 1_000) return "$distanceMeters m"
    val roundedTenths = (distanceMeters / 100f).roundToInt()
    return if (roundedTenths % 10 == 0) {
        "${roundedTenths / 10} km"
    } else {
        "${roundedTenths / 10}.${roundedTenths % 10} km"
    }
}

private fun TravelMode.icon(): ImageVector = when (this) {
    TravelMode.Walking -> Icons.AutoMirrored.Outlined.DirectionsWalk
    TravelMode.PublicTransport -> Icons.Outlined.DirectionsTransit
    TravelMode.Driving -> Icons.Outlined.DirectionsCar
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
    notice: String?,
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
                if (notice != null) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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
