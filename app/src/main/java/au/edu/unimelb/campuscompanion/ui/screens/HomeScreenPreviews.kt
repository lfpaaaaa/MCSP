package au.edu.unimelb.campuscompanion.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardGuidance
import au.edu.unimelb.campuscompanion.ui.model.buildHomeCardState
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
