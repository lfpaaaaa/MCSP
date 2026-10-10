package au.edu.unimelb.campuscompanion.ui.chat

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the chat list shows: the session's items, with a label wherever the day changes. */
sealed interface TimelineRow {
    val key: String

    data class Day(val label: String) : TimelineRow {
        override val key: String get() = "day:$label"
    }

    data class Entry(val item: ChatTimelineItem) : TimelineRow {
        override val key: String get() = item.id
    }
}

// The app's text is English, so the labels do not follow the device language either.
private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** Groups [items] by the day they were created, in the device's time zone. */
fun timelineRows(
    items: List<ChatTimelineItem>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone)
): List<TimelineRow> {
    val rows = ArrayList<TimelineRow>(items.size + 4)
    var currentDay: LocalDate? = null
    items.forEach { item ->
        val day = item.createdAt.atZone(zone).toLocalDate()
        if (day != currentDay) {
            currentDay = day
            rows += TimelineRow.Day(dayLabel(day, today))
        }
        rows += TimelineRow.Entry(item)
    }
    return rows
}

internal fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(dayFormatter)
}
