package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec

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
    val etaMinutes: Int? = null,
    val routeEstimate: RouteEstimate? = null,
    val activity: String? = null
) {
    val startDate: LocalDate get() = start.toLocalDate()
    val startTime: LocalTime get() = start.toLocalTime()
    val endTime: LocalTime get() = end.toLocalTime()

    fun statusAt(now: ZonedDateTime = ZonedDateTime.now(start.zone)): SessionStatus {
        val localNow = now.withZoneSameInstant(start.zone)
        return when {
            !localNow.isBefore(end) -> SessionStatus.Finished
            !localNow.isBefore(start) -> SessionStatus.InProgress
            startDate != localNow.toLocalDate() -> SessionStatus.Upcoming
            !localNow.isBefore(departureReminderTime(DEFAULT_REMINDER_LEAD_MINUTES)) ->
                SessionStatus.LeaveSoon
            else -> SessionStatus.Upcoming
        }
    }

    private companion object {
        const val DEFAULT_REMINDER_LEAD_MINUTES = 10
    }
}

data class RouteEstimate(
    val distanceMeters: Int,
    val walkingMinutes: Int? = null,
    val publicTransportMinutes: Int? = null,
    val drivingMinutes: Int? = null
)

fun CourseSession.departureReminderTime(leadMinutes: Int): ZonedDateTime {
    require(leadMinutes >= 0) { "Lead time cannot be negative" }
    val safeEtaMinutes = (etaMinutes ?: 0).coerceAtLeast(0)
    return start.minusMinutes((safeEtaMinutes + leadMinutes).toLong())
}

enum class SessionStatus {
    Upcoming,
    LeaveSoon,
    EnRoute,
    Arrived,
    InProgress,
    Finished
}

enum class GroupOrigin {
    Timetable,
    CreatedByUser,
    Joined
}

data class CourseGroup(
    val id: String,
    val courseCode: String,
    val name: String,
    val members: Int,
    val unreadCount: Int,
    val latestMessage: String,
    val latestFileName: String?,
    val privateContentEnabled: Boolean,
    val origin: GroupOrigin = GroupOrigin.Timetable,
    val joinCode: String? = null,
    val timetableKey: String? = null,
    val timetableSlot: String? = null,
    val timetableSpec: TimetableGroupSpec? = null
)

data class TimetableState(
    val url: String = "",
    val sessions: List<CourseSession> = emptyList(),
    val groups: List<CourseGroup> = emptyList(),
    val detectedEventCount: Int = 0,
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val groupSyncError: String? = null,
    val isSyncingGroups: Boolean = false
)
