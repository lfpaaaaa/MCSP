package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.RouteEstimate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TravelSnapshotTest {
    private val start = ZonedDateTime.of(2026, 10, 12, 11, 0, 0, 0, ZoneId.of("Australia/Melbourne"))
    private val lecture = CourseSession(
        id = "comp90018-lecture",
        code = "COMP90018",
        title = "Mobile Computing Systems Programming",
        location = "Parkville",
        room = "PAR-160",
        activity = "lecture",
        start = start,
        end = start.plusHours(1)
    )
    private val travel = TravelEstimate(
        mode = TravelMode.Walking,
        durationSeconds = 20 * 60L,
        distanceMeters = 1_400,
        source = EstimateSource.Routing,
        computedAt = start.minusHours(1).toInstant()
    )
    private val snapshot = TravelSnapshot(session = lecture, estimate = travel)

    @Test
    fun staleTravelDoesNotEnrichSameIdClassesAfterTimetableEdits() {
        val changed = listOf(
            lecture.copy(code = "SWEN90014"),
            lecture.copy(activity = "workshop"),
            lecture.copy(location = "Southbank"),
            lecture.copy(room = "PAR-200"),
            lecture.copy(start = start.plusMinutes(5)),
            lecture.copy(end = lecture.end.plusMinutes(5))
        )

        for (updated in changed) {
            val applied = snapshot.applyTo(listOf(updated))

            assertSame("Old travel must leave the edited occurrence unchanged", updated, applied.single())
        }
    }

    @Test
    fun onlyTheMatchingOccurrenceIsEnrichedWhenAStableIdAppearsMoreThanOnce() {
        val nextWeek = lecture.copy(start = start.plusWeeks(1), end = lecture.end.plusWeeks(1))
        val applied = snapshot.applyTo(listOf(nextWeek, lecture))

        assertSame(nextWeek, applied[0])
        assertEquals(20, applied[1].etaMinutes)
        assertEquals(1_400, applied[1].routeEstimate?.distanceMeters)
        assertEquals(20, applied[1].routeEstimate?.walkingMinutes)
    }

    @Test
    fun currentDisplayAndTimeZoneArePreservedWhileTravelEnrichmentIsUpdated() {
        val utc = ZoneId.of("UTC")
        val displayed = lecture.copy(
            title = "Updated course display name",
            start = start.withZoneSameInstant(utc),
            end = lecture.end.withZoneSameInstant(utc),
            etaMinutes = 99,
            routeEstimate = RouteEstimate(distanceMeters = 9_999, walkingMinutes = 99)
        )

        val applied = snapshot.applyTo(listOf(displayed)).single()

        assertEquals(displayed.copy(
            etaMinutes = 20,
            routeEstimate = RouteEstimate(distanceMeters = 1_400, walkingMinutes = 20)
        ), applied)
    }
}
