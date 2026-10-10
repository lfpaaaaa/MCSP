package au.edu.unimelb.campuscompanion.ui.model

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseReminderModelsTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")

    @Test
    fun recurringOccurrencesWithDifferentCalendarUidsBecomeOneCourseRow() {
        val first = session(
            id = "calendar-event-1@1790917200000",
            start = dateTime(2026, 10, 2, 13, 0)
        )
        val second = session(
            id = "calendar-event-2@1791522000000",
            start = dateTime(2026, 10, 9, 13, 0)
        )

        val courses = buildCourseReminderSeries(listOf(first, second))

        assertEquals(1, courses.size)
        assertEquals(2, courses.single().occurrenceCount)
        assertEquals(first, courses.single().nextSession)
    }

    @Test
    fun separateClassSeriesForTheSameSubjectRemainSeparate() {
        val lecture = session(
            id = "comp90018-lecture@1790917200000",
            start = dateTime(2026, 10, 2, 13, 0),
            title = "Mobile Computing Systems Programming, Lecture1"
        )
        val tutorial = session(
            id = "comp90018-tutorial@1791003600000",
            start = dateTime(2026, 10, 3, 13, 0),
            title = "Mobile Computing Systems Programming, Tutorial1"
        )

        val courses = buildCourseReminderSeries(listOf(lecture, tutorial))

        assertEquals(2, courses.size)
    }

    @Test
    fun remindersAreEnabledWithTenMinutePreparationByDefault() {
        val preference = CourseReminderPreference()

        assertTrue(preference.enabled)
        assertEquals(10, preference.leadMinutes)
    }

    @Test
    fun twoPmClassWithThirtyMinuteTripAndTenMinuteLeadRemindsAtOneTwenty() {
        val session = session(
            id = "comp90018-lecture@1790917200000",
            start = dateTime(2026, 10, 2, 14, 0),
            etaMinutes = 30
        )

        assertEquals(
            dateTime(2026, 10, 2, 13, 20),
            session.departureReminderTime(leadMinutes = 10)
        )
    }

    @Test
    fun parsedActivitiesWithTheSameCleanedTitleHaveSeparateSwitches() {
        val base = session("one", dateTime(2026, 10, 12, 11, 0), title = "Mobile Computing")
        val courses = buildCourseReminderSeries(listOf(
            base.copy(activity = "lecture"),
            base.copy(id = "two", activity = "tutorial"),
            base.copy(id = "three", activity = "workshop"),
            base.copy(id = "four", activity = "tutorial", start = base.start.plusWeeks(1), end = base.end.plusWeeks(1))
        ))
        assertEquals(3, courses.size)
        assertEquals(setOf("COMP90018 · Lecture", "COMP90018 · Tutorial", "COMP90018 · Workshop"), courses.map { it.displayName }.toSet())
        assertEquals(2, courses.single { it.nextSession.activity == "tutorial" }.occurrenceCount)
    }

    @Test
    fun reminderKeysSurviveTitleAndRoomChangesButStayWithinTheSubject() {
        val base = session("one", dateTime(2026, 10, 12, 11, 0)).copy(activity = "tutorial")
        assertEquals(base.reminderSeriesKey(), base.copy(title = "Renamed subject", room = "PAR-104").reminderSeriesKey())
        assertTrue(base.reminderSeriesKey() != base.copy(code = "COMP90015").reminderSeriesKey())
        assertTrue(base.copy(code = "EVENT", title = "First event").reminderSeriesKey() !=
            base.copy(code = "EVENT", title = "Second event").reminderSeriesKey())
    }

    private fun session(
        id: String,
        start: ZonedDateTime,
        etaMinutes: Int? = null,
        title: String = "Mobile Computing Systems Programming, Tutorial1"
    ) = CourseSession(
        id = id,
        code = "COMP90018",
        title = title,
        location = "Parkville Campus",
        room = "PAR-160",
        start = start,
        end = start.plusHours(1),
        etaMinutes = etaMinutes
    )

    private fun dateTime(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): ZonedDateTime = ZonedDateTime.of(
        year,
        month,
        day,
        hour,
        minute,
        0,
        0,
        melbourne
    )
}
