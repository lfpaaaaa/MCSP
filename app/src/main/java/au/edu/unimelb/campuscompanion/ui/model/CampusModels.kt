package au.edu.unimelb.campuscompanion.ui.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class CourseSession(
    val id: String,
    val code: String,
    val title: String,
    val location: String,
    val room: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val etaMinutes: Int? = null
) {
    val startDate: LocalDate get() = start.toLocalDate()
    val startTime: LocalTime get() = start.toLocalTime()
    val endTime: LocalTime get() = end.toLocalTime()

    fun statusAt(now: ZonedDateTime = ZonedDateTime.now(start.zone)): SessionStatus {
        val localNow = now.withZoneSameInstant(start.zone)
        return when {
            !localNow.isBefore(end) -> SessionStatus.Finished
            !localNow.isBefore(start) -> SessionStatus.InProgress
            Duration.between(localNow, start).toMinutes() <= (etaMinutes ?: 0) + 10L -> {
                SessionStatus.LeaveSoon
            }
            else -> SessionStatus.Upcoming
        }
    }
}

fun CourseSession.departureReminderTime(leadMinutes: Int): ZonedDateTime {
    require(leadMinutes >= 0) { "Lead time cannot be negative" }
    return start.minusMinutes(((etaMinutes ?: 0) + leadMinutes).toLong())
}

enum class SessionStatus {
    Upcoming,
    LeaveSoon,
    EnRoute,
    Arrived,
    InProgress,
    Finished
}

data class CourseGroup(
    val id: String,
    val courseCode: String,
    val name: String,
    val members: Int,
    val unreadCount: Int,
    val latestMessage: String,
    val latestFileName: String?,
    val privateContentEnabled: Boolean
)

data class TimetableState(
    val url: String = "",
    val sessions: List<CourseSession> = emptyList(),
    val groups: List<CourseGroup> = emptyList(),
    val detectedEventCount: Int = 0,
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
