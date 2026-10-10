package au.edu.unimelb.campuscompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class IcsTimetableParserTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")
    private val parser = IcsTimetableParser()

    @Test
    fun acceptsValidCalendarWithNoEvents() {
        val calendar = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Campus Companion//Parser Test//EN
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val result = parser.parse(
            bytes = calendar.toByteArray(),
            now = ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, melbourne),
            displayZone = melbourne
        )

        assertEquals(0, result.sourceEventCount)
        assertTrue(result.sessions.isEmpty())
        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun parsesRecurringClassesAndBuildsCourseGroups() {
        val calendar = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Campus Companion//Parser Test//EN
            BEGIN:VEVENT
            UID:comp90018-lecture
            DTSTAMP:20260920T000000Z
            DTSTART;TZID=Australia/Melbourne:20260924T150000
            DTEND;TZID=Australia/Melbourne:20260924T160000
            RRULE:FREQ=WEEKLY;COUNT=3
            SUMMARY:COMP90018 - Mobile Computing Systems Programming
            LOCATION:Parkville Campus\, PAR-160
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val result = parser.parse(
            bytes = calendar.toByteArray(),
            now = ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, melbourne),
            displayZone = melbourne
        )

        assertEquals(1, result.sourceEventCount)
        assertEquals(3, result.sessions.size)
        assertEquals("COMP90018", result.sessions.first().code)
        assertEquals("Mobile Computing Systems Programming", result.sessions.first().title)
        assertEquals(LocalDate.of(2026, 9, 24), result.sessions.first().startDate)
        assertEquals(LocalTime.of(15, 0), result.sessions.first().startTime)
        assertEquals("Parkville Campus", result.sessions.first().location)
        assertEquals("PAR-160", result.sessions.first().room)
        assertEquals(listOf("COMP90018"), result.groups.map { it.courseCode })
    }

    @Test
    fun validCalendarCanConnectWhenAllSessionsAreInThePast() {
        val calendar = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Campus Companion//Parser Test//EN
            BEGIN:VEVENT
            UID:swen90014-workshop
            DTSTAMP:20260101T000000Z
            DTSTART:20260110T000000Z
            DTEND:20260110T010000Z
            SUMMARY:SWEN90014 Masters Software Engineering Project
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val result = parser.parse(
            bytes = calendar.toByteArray(),
            now = ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, melbourne),
            displayZone = melbourne
        )

        assertEquals(1, result.sourceEventCount)
        assertTrue(result.sessions.isEmpty())
        assertEquals(listOf("SWEN90014"), result.groups.map { it.courseCode })
    }
    private fun event(uid: String, summary: String = "COMP90018 - Mobile Computing - Tutorial 01",
                      time: String = "150000", place: String = "Parkville Campus\\, PAR-160",
                      description: String = "", date: String = "20260924"): String = """
        BEGIN:VEVENT
        UID:$uid
        DTSTAMP:20260920T000000Z
        DTSTART;TZID=Australia/Melbourne:$date${time}
        DTEND;TZID=Australia/Melbourne:${date}${if (time == "150000") "160000" else "170000"}
        RRULE:FREQ=WEEKLY;COUNT=3
        SUMMARY:$summary
        LOCATION:$place
        DESCRIPTION:$description
        END:VEVENT
    """.trimIndent().replace("$date${time}", "${date}T$time")
        .replace("DTEND;TZID=Australia/Melbourne:$date", "DTEND;TZID=Australia/Melbourne:${date}T")

    private fun parseEvents(vararg events: String, zone: ZoneId = melbourne): TimetableImport = parser.parse(
        ("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Test//EN\n" + events.joinToString("\n") + "\nEND:VCALENDAR")
            .replace("\n", "\r\n").toByteArray(),
        ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, melbourne), zone
    )

    @Test fun recurringTutorialAddsOneSmallGroupAlongsideCourseGroup() {
        val result = parseEvents(event("one"))
        assertEquals(3, result.sessions.size)
        assertEquals(listOf("Mobile Computing", "COMP90018-tutorial"), result.groups.map { it.name })
        assertEquals("COMP90018|tutorial|4|15:00|16:00|parkville campus,par-160", result.groups.last().timetableKey)
    }

    @Test fun workshopsUseWorkshopSuffixAndDescriptionActivity() {
        val result = parseEvents(event("one", summary = "COMP90018 - Mobile Computing",
            description = "Activity Type: Workshop"))
        assertEquals("COMP90018-workshop", result.groups.last().name)
    }

    @Test fun matchingClassesIgnoreUidCaseWhitespaceAndDeviceTimezone() {
        val first = parseEvents(event("student-a"))
        val second = parseEvents(event("student-b", place = "  PARKVILLE   CAMPUS\\,PAR-160  "), zone = ZoneId.of("UTC"))
        assertEquals(first.groups.last().timetableKey, second.groups.last().timetableKey)
        // The recurrence crosses daylight saving but keeps a single wall-clock slot.
        assertEquals(2, second.groups.size)
    }

    @Test fun sameNameWithDifferentTimeOrRoomStaysSeparate() {
        val result = parseEvents(event("one"), event("two", time = "160000"),
            event("three", place = "Parkville Campus\\, PAR-161"))
        assertEquals(4, result.groups.size)
        assertEquals(4, result.groups.map { it.timetableKey }.distinct().size)
    }

    @Test fun duplicatedWeeklyEventsDoNotDuplicateGroup() {
        val result = parseEvents(event("one"), event("two", date = "20261001"))
        assertEquals(2, result.groups.size)
    }

    @Test fun missingLocationAndAllDayActivitiesDoNotGuessSmallGroup() {
        assertEquals(1, parseEvents(event("one", place = "")).groups.size)
        val allDay = event("two").replace("DTSTART;TZID=Australia/Melbourne:20260924T150000", "DTSTART;VALUE=DATE:20260924")
            .replace("DTEND;TZID=Australia/Melbourne:20260924T160000", "DTEND;VALUE=DATE:20260925")
        assertEquals(1, parseEvents(allDay).groups.size)
    }

    @Test fun activityInParenthesesOrUnderscoreSuffixIsRecognized() {
        assertEquals("COMP90018-workshop",
            parseEvents(event("one", summary = "COMP90018 - Mobile Computing (Workshop)")).groups.last().name)
        assertEquals("COMP90018-tutorial",
            parseEvents(event("two", summary = "COMP90018_Mobile Computing_Tutorial_01")).groups.last().name)
    }

    @Test fun descriptiveCourseTitleWinsOverActivityOnlySummary() {
        val result = parseEvents(event("one", summary = "COMP90018 - Tutorial 01"),
            event("two", summary = "COMP90018 - Mobile Computing - Lecture"))
        assertEquals(listOf("Mobile Computing", "COMP90018-tutorial"), result.groups.map { it.name })
    }

    @Test fun floatingCampusTimesDoNotFollowDeviceTimezone() {
        val floating = event("one").replace(";TZID=Australia/Melbourne", "")
        assertEquals(parseEvents(floating).groups.last().timetableKey,
            parseEvents(floating, zone = ZoneId.of("UTC")).groups.last().timetableKey)
    }

}
