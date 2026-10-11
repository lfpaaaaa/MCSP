package au.edu.unimelb.campuscompanion.ui.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

internal const val SCHEDULE_PAGE_SIZE = 10

internal data class ScheduleDay(val date: LocalDate, val sessions: List<CourseSession>)

internal data class SchedulePage(
    val index: Int,
    val pageCount: Int,
    val totalSessions: Int,
    val days: List<ScheduleDay>
) {
    val firstSessionNumber: Int get() = if (totalSessions == 0) 0 else index * SCHEDULE_PAGE_SIZE + 1
    val lastSessionNumber: Int get() = (index * SCHEDULE_PAGE_SIZE + SCHEDULE_PAGE_SIZE).coerceAtMost(totalSessions)
}

/** Page sessions before grouping, so even a busy day cannot exceed the ten-class limit. */
internal fun buildSchedulePage(sessions: List<CourseSession>, requestedPage: Int): SchedulePage {
    val pageCount = ((sessions.size + SCHEDULE_PAGE_SIZE - 1) / SCHEDULE_PAGE_SIZE).coerceAtLeast(1)
    val index = requestedPage.coerceIn(0, pageCount - 1)
    val days = sessions.orderedForSchedule()
        .drop(index * SCHEDULE_PAGE_SIZE)
        .take(SCHEDULE_PAGE_SIZE)
        .groupBy { it.startDate }
        .map { (date, classes) -> ScheduleDay(date, classes) }
    return SchedulePage(index, pageCount, sessions.size, days)
}

internal fun scheduleWeekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** Always includes all seven days; the calendar view has no ten-class limit. */
internal fun buildScheduleWeek(sessions: List<CourseSession>, selectedDate: LocalDate): List<ScheduleDay> {
    val start = scheduleWeekStart(selectedDate)
    val byDate = sessions.orderedForSchedule().groupBy { it.startDate }
    return (0L..6L).map { offset ->
        val date = start.plusDays(offset)
        ScheduleDay(date, byDate[date].orEmpty())
    }
}

private fun List<CourseSession>.orderedForSchedule(): List<CourseSession> =
    sortedWith(compareBy<CourseSession> { it.startDate }.thenBy { it.start.toInstant() }.thenBy { it.id })
