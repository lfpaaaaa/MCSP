package au.edu.unimelb.campuscompanion.ui.model

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableOccurrenceTest {
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

    @Test
    fun timetableEditsInvalidateTheOccurrenceEvenWhenTheIdIsUnchanged() {
        val changed = listOf(
            lecture.copy(id = "comp90018-tutorial"),
            lecture.copy(code = "SWEN90014"),
            lecture.copy(activity = "workshop"),
            lecture.copy(activity = null),
            lecture.copy(location = "Southbank"),
            lecture.copy(room = "PAR-200"),
            lecture.copy(start = start.plusMinutes(5)),
            lecture.copy(end = lecture.end.plusMinutes(5))
        )

        for (updated in changed) {
            assertFalse("Changed timetable facts must invalidate guidance: $updated", lecture.matchesTimetableSession(updated))
            assertFalse(updated.matchesTimetableSession(lecture))
        }
    }

    @Test
    fun titleAndTravelEnrichmentDoNotChangeTheOccurrence() {
        val enriched = lecture.copy(
            title = "Updated course display name",
            etaMinutes = 20,
            routeEstimate = RouteEstimate(distanceMeters = 1_400, walkingMinutes = 20)
        )

        assertTrue(lecture.matchesTimetableSession(enriched))
        assertTrue(enriched.matchesTimetableSession(lecture))
    }

    @Test
    fun timeZonesMatchWhenBothOccurrenceInstantsAreEqual() {
        val utc = ZoneId.of("UTC")
        val sameOccurrence = lecture.copy(
            start = start.withZoneSameInstant(utc),
            end = lecture.end.withZoneSameInstant(utc)
        )

        assertTrue(lecture.matchesTimetableSession(sameOccurrence))
        assertTrue(sameOccurrence.matchesTimetableSession(lecture))
        assertFalse(lecture.matchesTimetableSession(sameOccurrence.copy(end = sameOccurrence.end.plusSeconds(1))))
    }
}
