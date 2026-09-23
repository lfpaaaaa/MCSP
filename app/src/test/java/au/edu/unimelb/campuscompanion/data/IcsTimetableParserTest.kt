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
}
