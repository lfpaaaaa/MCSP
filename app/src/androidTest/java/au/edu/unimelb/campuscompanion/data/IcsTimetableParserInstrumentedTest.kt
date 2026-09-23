package au.edu.unimelb.campuscompanion.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class IcsTimetableParserInstrumentedTest {
    @Test
    fun parsesCalendarWithEmbeddedMelbourneTimezoneOnAndroid() {
        val calendar = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Campus Companion//Android Parser Test//EN
            BEGIN:VTIMEZONE
            TZID:Australia/Melbourne
            BEGIN:STANDARD
            DTSTART:19700405T030000
            TZOFFSETFROM:+1100
            TZOFFSETTO:+1000
            TZNAME:AEST
            RRULE:FREQ=YEARLY;BYMONTH=4;BYDAY=1SU
            END:STANDARD
            BEGIN:DAYLIGHT
            DTSTART:19701004T020000
            TZOFFSETFROM:+1000
            TZOFFSETTO:+1100
            TZNAME:AEDT
            RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=1SU
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:comp90018-android-test
            DTSTAMP:20260920T000000Z
            DTSTART;TZID=Australia/Melbourne:20260924T150000
            DTEND;TZID=Australia/Melbourne:20260924T160000
            SUMMARY:COMP90018 Mobile Computing Systems Programming
            LOCATION:PAR-160
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val melbourne = ZoneId.of("Australia/Melbourne")

        val result = IcsTimetableParser().parse(
            bytes = calendar.toByteArray(),
            now = ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, melbourne),
            displayZone = melbourne
        )

        assertEquals(1, result.sourceEventCount)
        assertEquals(1, result.sessions.size)
        assertEquals("COMP90018", result.sessions.single().code)
        assertEquals(LocalTime.of(15, 0), result.sessions.single().startTime)
    }
}
