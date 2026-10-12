package au.edu.unimelb.campuscompanion.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardGuidance
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardState
import au.edu.unimelb.campuscompanion.ui.model.buildHomeGroupUpdates
import au.edu.unimelb.campuscompanion.ui.theme.CampusCompanionTheme
import java.time.ZoneId
import java.time.ZonedDateTime

private val previewStart = ZonedDateTime.of(2026, 10, 12, 15, 0, 0, 0, ZoneId.of("Australia/Melbourne"))
private val previewSession = CourseSession(
    id = "home-preview", code = "COMP90018", title = "Mobile Computing Systems Programming",
    location = "Parkville Campus", room = "PAR-160", start = previewStart, end = previewStart.plusHours(1)
)

@Preview(name = "Leave soon", widthDp = 360, showBackground = true)
@Preview(name = "Leave soon - dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Leave soon - large text", widthDp = 320, fontScale = 1.5f)
@Composable
private fun LeaveSoonPreview() = PreviewHomeCard(ContextState.LEAVE_SOON, previewStart.minusMinutes(35))

@Preview(name = "On your way", widthDp = 360, showBackground = true)
@Composable
private fun EnRoutePreview() = PreviewHomeCard(ContextState.EN_ROUTE, previewStart.minusMinutes(20))

@Preview(name = "Arrived", widthDp = 360, showBackground = true)
@Composable
private fun ArrivedPreview() = PreviewHomeCard(ContextState.ARRIVED, previewStart.minusMinutes(6))

@Preview(name = "In class", widthDp = 360, showBackground = true)
@Composable
private fun InClassPreview() = PreviewHomeCard(ContextState.IN_CLASS, previewStart.plusMinutes(5))

@Preview(name = "No travel time", widthDp = 360, showBackground = true)
@Preview(name = "No travel time - large text", widthDp = 320, fontScale = 2f, showBackground = true)
@Composable
private fun MissingTravelPreview() = PreviewHomeCard(ContextState.UPCOMING, previewStart.minusMinutes(35), hasTravel = false)

@Preview(name = "No upcoming class", widthDp = 360, showBackground = true)
@Composable
private fun NoClassPreview() {
    val card = buildHomeCardState(emptyList(), TravelSnapshot(), previewStart)
    CampusCompanionTheme {
        Surface { Column(Modifier.padding(16.dp)) { HomeDayCard(buildHomeCardGuidance(card, previewStart)) } }
    }
}

@Composable
private fun PreviewHomeCard(state: ContextState, now: ZonedDateTime, hasTravel: Boolean = true) {
    val snapshot = TravelSnapshot(
        session = previewSession,
        state = state,
        estimate = if (hasTravel) TravelEstimate(
            mode = TravelMode.Walking, durationSeconds = 25 * 60L, distanceMeters = 1_500,
            source = EstimateSource.StraightLine, computedAt = now.toInstant()
        ) else null,
        leaveBy = previewStart.minusMinutes(30).takeIf { hasTravel },
        leadMinutes = 3,
        weatherBufferMinutes = 1,
        entryAllowanceMinutes = 1
    )
    val card = buildHomeCardState(listOf(previewSession), snapshot, now)
    CampusCompanionTheme {
        Surface {
            Column(Modifier.padding(16.dp)) {
                NextClassCard(previewSession, card, buildHomeCardGuidance(card, now), onNavigate = {})
            }
        }
    }
}

private val previewPostClassNow = previewSession.end.plusMinutes(5)
private val previewPostClassGroup = CourseGroup(
    id = "home-preview-comp90018-group",
    courseCode = "COMP90018",
    name = "Course group",
    members = 24,
    unreadCount = 3,
    latestMessage = "Alex: The project proposal is ready to review.",
    latestFileName = "proposal.pdf",
    timetableKey = "COMP90018|course"
)
private val previewOtherGroup = CourseGroup(
    id = "home-preview-elen90095-group",
    courseCode = "ELEN90095",
    name = "Course group",
    members = 18,
    unreadCount = 1,
    latestMessage = "Sam: See you at the next lecture.",
    latestFileName = null,
    timetableKey = "ELEN90095|course"
)

@Preview(name = "After COMP90018 - updates", widthDp = 360, heightDp = 800, showBackground = true)
@Preview(
    name = "After COMP90018 - dark",
    widthDp = 360,
    heightDp = 800,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Preview(
    name = "After COMP90018 - large text",
    widthDp = 320,
    heightDp = 800,
    fontScale = 2f,
    showBackground = true
)
@Composable
private fun PostClassUpdatesPreview() = PreviewPostClassHome()

@Preview(name = "After COMP90018 - no updates", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun PostClassEmptyUpdatesPreview() = PreviewPostClassHome(hasUpdates = false)

@Preview(name = "Urgent next class and finished group", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun UrgentNextClassWithPostClassUpdatesPreview() = PreviewPostClassHome(hasUrgentNextClass = true)

@Composable
private fun PreviewPostClassHome(hasUpdates: Boolean = true, hasUrgentNextClass: Boolean = false) {
    val nextSession = if (hasUrgentNextClass) previewSession.copy(
        id = "home-preview-next-class",
        code = "COMP90024",
        title = "Cluster and Cloud Computing",
        room = "PAR-193",
        start = previewPostClassNow.plusMinutes(15),
        end = previewPostClassNow.plusMinutes(75)
    ) else null
    val snapshot = TravelSnapshot(
        session = nextSession,
        state = if (hasUrgentNextClass) ContextState.RUNNING_LATE else ContextState.POST_CLASS,
        finishedSession = previewSession,
        estimate = nextSession?.let {
            TravelEstimate(
                mode = TravelMode.Walking,
                durationSeconds = 25 * 60L,
                distanceMeters = 1_500,
                source = EstimateSource.StraightLine,
                computedAt = previewPostClassNow.toInstant()
            )
        },
        leaveBy = nextSession?.start?.minusMinutes(30),
        leadMinutes = 3,
        entryAllowanceMinutes = 2
    )
    val card = buildHomeCardState(listOfNotNull(previewSession, nextSession), snapshot, previewPostClassNow)
    val finishedGroup = if (hasUpdates) previewPostClassGroup else previewPostClassGroup.copy(
        unreadCount = 0,
        latestMessage = "No messages yet.",
        latestFileName = null
    )
    val groups = if (hasUpdates) listOf(previewOtherGroup, finishedGroup) else listOf(finishedGroup)
    val updates = buildHomeGroupUpdates(card.finishedSession, groups)
    val guidance = buildHomeCardGuidance(card, previewPostClassNow)
    CampusCompanionTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (nextSession != null) {
                    NextClassCard(nextSession, card, guidance, onNavigate = {})
                } else {
                    HomeDayCard(guidance)
                }
                HomeCourseGroupUpdates(updates, onOpenGroup = {})
            }
        }
    }
}
