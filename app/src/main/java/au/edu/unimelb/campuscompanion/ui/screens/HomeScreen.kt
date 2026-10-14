package au.edu.unimelb.campuscompanion.ui.screens

import au.edu.unimelb.campuscompanion.ui.model.trimTrailingCommas
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.ui.about.DataSources
import au.edu.unimelb.campuscompanion.ui.about.RoutingCredit
import au.edu.unimelb.campuscompanion.ui.components.CourseSessionRow
import au.edu.unimelb.campuscompanion.ui.components.GroupUpdateRow
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.components.StatusPill
import au.edu.unimelb.campuscompanion.ui.components.TimetableSyncStatus
import au.edu.unimelb.campuscompanion.ui.components.TimetableUrlDialog
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.HomeCardUiState
import au.edu.unimelb.campuscompanion.ui.model.HomeCardGuidance
import au.edu.unimelb.campuscompanion.ui.model.HomeGroupUpdates
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardState
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardGuidance
import au.edu.unimelb.campuscompanion.ui.model.buildHomeGroupUpdates
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val homeTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val nextClassDateFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM")

@Composable
fun HomeScreen(
    timetableState: TimetableState,
    travelSnapshot: TravelSnapshot,
    onTimetableUrlSave: suspend (String) -> Result<Unit>,
    onTimetableRefresh: () -> Unit,
    onOpenGroup: (CourseGroup) -> Unit,
    onNavigate: (CourseSession) -> Unit,
    postClassGroupCandidates: List<CourseGroup> = timetableState.groups,
    modifier: Modifier = Modifier
) {
    var showTimetableDialog by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = ZonedDateTime.now()
                delay(30_000)
            }
        }
    }
    LaunchedEffect(timetableState.hasTimetable, timetableState.isLoading, timetableState.isLoaded) {
        if (timetableState.isLoaded && !timetableState.hasTimetable && !timetableState.isLoading) showTimetableDialog = true
    }

    if (showTimetableDialog && timetableState.isLoaded) {
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
                    !timetableState.isLoaded && !timetableState.isLoading -> "Your saved timetable could not be loaded."
                    !timetableState.isLoaded -> "Loading your saved timetable."
                    timetableState.isCached -> "Classes below are from your saved timetable."
                    timetableState.isLoading && !timetableState.isConnected -> "Checking your saved timetable URL."
                    timetableState.isConnected -> {
                        "Classes and course groups below come from your connected timetable."
                    }
                    timetableState.hasTimetable -> "Classes below were added by hand. Connect a timetable URL to import the rest."
                    else -> "Connect your timetable to load classes, reminders, and course groups."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TimetableSyncStatus(timetableState, onRefresh = onTimetableRefresh)

        when {
            timetableState.isLoading && !timetableState.isConnected -> TimetableLoadingCard()
            !timetableState.isLoaded -> Unit
            !timetableState.hasTimetable -> TimetableSetupPrompt(
                errorMessage = timetableState.errorMessage,
                onConnect = { showTimetableDialog = true }
            )
            else -> {
                val homeCard = buildHomeCardState(timetableState.sessions, travelSnapshot, now)
                val guidance = buildHomeCardGuidance(homeCard, now)
                val groupUpdates = buildHomeGroupUpdates(
                    finishedSession = homeCard.finishedSession,
                    groups = timetableState.groups,
                    postClassCandidates = postClassGroupCandidates
                )
                val agenda = buildHomeAgenda(
                    sessions = timetableState.sessions,
                    now = now
                )
                val nextClassIsToday = agenda.nextClass?.let { nextClass ->
                    val localNow = now.withZoneSameInstant(nextClass.start.zone)
                    nextClass.startDate == localNow.toLocalDate()
                } == true

                val cardSession = homeCard.session
                if (cardSession != null) {
                    NextClassCard(
                        session = cardSession,
                        card = homeCard,
                        guidance = guidance,
                        onNavigate = { onNavigate(cardSession) }
                    )
                } else {
                    HomeDayCard(guidance = guidance)
                }

                if (groupUpdates.featuredGroup != null) {
                    HomeCourseGroupUpdates(groupUpdates, onOpenGroup)
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
                            CourseSessionRow(session = session, onNavigate = { onNavigate(session) })
                        }
                    }
                }

                ImportSummaryCard(
                    detectedEventCount = timetableState.detectedEventCount,
                    groupCount = timetableState.groups.size,
                    notice = timetableState.offlineNotice()
                )

                if (groupUpdates.featuredGroup == null) {
                    HomeCourseGroupUpdates(groupUpdates, onOpenGroup)
                }
            }
        }
    }
}

@Composable
internal fun HomeCourseGroupUpdates(
    updates: HomeGroupUpdates,
    onOpenGroup: (CourseGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "Course groups")
        updates.contextLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (updates.rows.isEmpty()) {
            EmptyImportedSection(text = "No groups are available to add.")
        } else {
            updates.rows.forEach { group ->
                GroupUpdateRow(group = group, onClick = { onOpenGroup(group) })
            }
        }
    }
}

@Composable
internal fun NextClassCard(
    session: CourseSession,
    card: HomeCardUiState,
    guidance: HomeCardGuidance,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val travel = card.estimate

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = guidance.cardLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(
                    label = card.statusLabel,
                    status = card.statusTone
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = session.code,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = session.title.trimTrailingCommas(),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${session.startDate.format(nextClassDateFormatter)}  ${session.startTime.format(homeTimeFormatter)} - ${session.endTime.format(homeTimeFormatter)}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = card.destinationText,
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            HomeGuidanceText(guidance)
            if (guidance.showTravelDetails) {
                TravelMetric(
                    icon = travel?.mode?.icon() ?: Icons.Outlined.AccessTime,
                    label = "Travel time",
                    value = travel?.let { estimate ->
                        "${if (estimate.isApproximate) "~" else ""}${estimate.durationMinutes} min · ${estimate.mode.displayName}"
                    } ?: guidance.travelUnavailableMessage.orEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )
                guidance.bufferExplanation?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (travel?.isApproximate == true) {
                    Text("Approximate travel time · live route unavailable", style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = onNavigate, modifier = Modifier.fillMaxWidth()) { Text("Navigate with Google Maps") }
            if (guidance.showTravelDetails) {
                when (travel?.source) {
                    EstimateSource.GoogleMaps -> Text("Google Maps", style = MaterialTheme.typography.bodySmall)
                    EstimateSource.Routing -> {
                        RoutingCredit()
                        if (travel?.mode == TravelMode.PublicTransport) {
                            HomeSourceCredit("Public transport data sources", DataSources.TRANSITOUS_SOURCES_URL)
                        }
                    }
                    else -> Unit
                }
            }
            if (guidance.showWeatherCredit) {
                HomeSourceCredit(DataSources.OPEN_METEO_CREDIT, DataSources.OPEN_METEO_URL)
            }
        }
    }
}

@Composable
internal fun HomeDayCard(
    guidance: HomeCardGuidance,
    modifier: Modifier = Modifier
) {
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
            Text(guidance.cardLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            HomeGuidanceText(guidance)
        }
    }
}

@Composable
private fun HomeGuidanceText(guidance: HomeCardGuidance) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(guidance.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        guidance.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        guidance.departureLine?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HomeSourceCredit(label: String, url: String) {
    val links = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
    Text(
        buildAnnotatedString { withLink(LinkAnnotation.Url(url, links)) { append(label) } },
        style = MaterialTheme.typography.bodySmall
    )
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
                style = MaterialTheme.typography.titleSmall
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
