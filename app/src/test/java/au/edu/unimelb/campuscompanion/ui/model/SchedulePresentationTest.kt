package au.edu.unimelb.campuscompanion.ui.model

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePresentationTest {
    private val monday = LocalDate.of(2026, 10, 12)
    private val zone = ZoneId.of("Australia/Melbourne")

    @Test
    fun allClassesRemainReachableAcrossTenClassPages() {
        val sessions = (0..24).map { session("class-$it", monday.plusDays(it.toLong())) }
        val first = buildSchedulePage(sessions.reversed(), 0)
        val pages = (0 until first.pageCount).map { buildSchedulePage(sessions.reversed(), it) }

        assertEquals(3, first.pageCount)
        assertEquals(listOf(10, 10, 5), pages.map { it.days.sumOf { day -> day.sessions.size } })
        assertEquals(sessions.map { it.id }, pages.flatMap { it.days }.flatMap { it.sessions }.map { it.id })
        assertEquals(21, pages.last().firstSessionNumber)
        assertEquals(25, pages.last().lastSessionNumber)
    }

    @Test
    fun sessionsOnTheSameDayHaveOneDateHeadingAndAreInTimeOrder() {
        val nextDay = session("next-day", monday.plusDays(1), 9)
        val early = session("early", monday, 10)
        val late = session("late", monday, 11)
        val page = buildSchedulePage(listOf(nextDay, late, early), 0)

        assertEquals(listOf(monday, monday.plusDays(1)), page.days.map { it.date })
        assertEquals(listOf("early", "late"), page.days.first().sessions.map { it.id })
        assertEquals(listOf("next-day"), page.days.last().sessions.map { it.id })
    }

    @Test
    fun busyDaysStillRespectThePageLimitAndContinueWithADateHeading() {
        val sessions = (0..10).map { session("class-$it", monday, it) }
        val first = buildSchedulePage(sessions, 0)
        val second = buildSchedulePage(sessions, 1)

        assertEquals(10, first.days.single().sessions.size)
        assertEquals(monday, second.days.single().date)
        assertEquals(listOf("class-10"), second.days.single().sessions.map { it.id })
    }

    @Test
    fun refreshClampsOldPageIndexesAndHandlesAnEmptyTimetable() {
        val sessions = (0..10).map { session("class-$it", monday.plusDays(it.toLong())) }
        assertEquals(1, buildSchedulePage(sessions, 90).index)
        assertEquals(0, buildSchedulePage(sessions, -1).index)
        val empty = buildSchedulePage(emptyList(), 90)
        assertEquals(0, empty.index)
        assertEquals(1, empty.pageCount)
        assertEquals(0, empty.firstSessionNumber)
        assertEquals(0, empty.lastSessionNumber)
        assertTrue(empty.days.isEmpty())
    }

    @Test
    fun exactlyTenClassesDoNotCreateAnEmptySecondPage() {
        val sessions = (0..9).map { session("class-$it", monday.plusDays(it.toLong())) }
        val page = buildSchedulePage(sessions, 1)
        assertEquals(1, page.pageCount)
        assertEquals(0, page.index)
        assertEquals(10, page.lastSessionNumber)
    }

    @Test
    fun weekIncludesMondayThroughSundayAndEmptyDaysButNotAdjacentWeeks() {
        val sessions = listOf(
            session("previous", monday.minusDays(1)),
            session("monday", monday),
            session("sunday", monday.plusDays(6)),
            session("next", monday.plusDays(7))
        )
        val week = buildScheduleWeek(sessions, monday.plusDays(6))

        assertEquals((0L..6L).map(monday::plusDays), week.map { it.date })
        assertEquals(listOf("monday", "sunday"), week.flatMap { it.sessions }.map { it.id })
        assertTrue(week[1].sessions.isEmpty())
    }

    @Test
    fun weekViewDoesNotLimitABusyDayToTenClasses() {
        val sessions = (0..11).map { session("class-$it", monday, it) }
        val week = buildScheduleWeek(sessions.reversed(), monday)
        assertEquals(sessions.map { it.id }, week.first().sessions.map { it.id })
    }

    @Test
    fun weekNavigationHandlesYearBoundaries() {
        val newYear = LocalDate.of(2027, 1, 1)
        val week = buildScheduleWeek(listOf(session("new-year", newYear)), newYear)
        assertEquals(LocalDate.of(2026, 12, 28), week.first().date)
        assertEquals(LocalDate.of(2027, 1, 3), week.last().date)
        assertEquals(listOf("new-year"), week[4].sessions.map { it.id })
    }

    private fun session(id: String, date: LocalDate, hour: Int = 10): CourseSession {
        val start = date.atTime(hour, 0).atZone(zone)
        return CourseSession(id, "COMP10001", "Computing", "PAR-160", "101", start, start.plusHours(1))
    }
}
