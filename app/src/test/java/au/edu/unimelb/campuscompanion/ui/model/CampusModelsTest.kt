package au.edu.unimelb.campuscompanion.ui.model

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class CampusModelsTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")

    @Test
    fun classOnMondayStaysUpcomingWhenNowIsFridayEvenWithLargeEta() {
        val friday = ZonedDateTime.of(2026, 10, 2, 17, 0, 0, 0, melbourne)
        val monday = ZonedDateTime.of(2026, 10, 5, 9, 0, 0, 0, melbourne)
        val session = session(start = monday, etaMinutes = 5_000)

        assertEquals(SessionStatus.Upcoming, session.statusAt(friday))
    }

    @Test
    fun classLaterTodayStaysUpcomingBeforeDepartureTime() {
        val start = ZonedDateTime.of(2026, 10, 2, 15, 0, 0, 0, melbourne)
        val session = session(start = start, etaMinutes = 30)

        assertEquals(SessionStatus.Upcoming, session.statusAt(start.minusMinutes(41)))
    }

    @Test
    fun classLaterTodayChangesToLeaveSoonAtDepartureTime() {
        val start = ZonedDateTime.of(2026, 10, 2, 15, 0, 0, 0, melbourne)
        val session = session(start = start, etaMinutes = 30)

        assertEquals(SessionStatus.LeaveSoon, session.statusAt(start.minusMinutes(40)))
    }

    @Test
    fun classThatHasStartedIsInProgress() {
        val start = ZonedDateTime.of(2026, 10, 2, 15, 0, 0, 0, melbourne)
        val session = session(start = start, etaMinutes = 30)

        assertEquals(SessionStatus.InProgress, session.statusAt(start.plusMinutes(5)))
    }

    private fun session(start: ZonedDateTime, etaMinutes: Int): CourseSession = CourseSession(
        id = "session",
        code = "COMP90018",
        title = "Mobile Computing Systems Programming",
        location = "Parkville Campus",
        room = "PAR-160",
        start = start,
        end = start.plusHours(1),
        etaMinutes = etaMinutes
    )
}
