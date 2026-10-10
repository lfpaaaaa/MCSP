package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One timetabled class, with the travel time to its building once it is known. */
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

/** Travel times to a class's building by mode, from the routing service or the straight-line fallback. */
data class RouteEstimate(
    val distanceMeters: Int,
    val walkingMinutes: Int? = null,
    val publicTransportMinutes: Int? = null,
    val drivingMinutes: Int? = null,
    /** True when the time was worked out from the straight-line distance because routing was unavailable. */
    val isApproximate: Boolean = false
)

/** When to leave for this class: its start minus the travel time and [leadMinutes]. */
fun CourseSession.departureReminderTime(leadMinutes: Int): ZonedDateTime {
    require(leadMinutes >= 0) { "Lead time cannot be negative" }
    val safeEtaMinutes = (etaMinutes ?: 0).coerceAtLeast(0)
    return start.minusMinutes((safeEtaMinutes + leadMinutes).toLong())
}

/** Where the user is relative to a class, as the Home card shows it. */
enum class SessionStatus {
    Upcoming,
    LeaveSoon,
    EnRoute,
    Arrived,
    InProgress,
    Finished
}

/** How a group came to be in the user's list. */
enum class GroupOrigin {
    Timetable,
    CreatedByUser,
    Joined
}

/** A group as the Groups screen and the Home card show it. */
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

/** The timetable as the screens show it, with the state of the download and of the group sync. */
data class TimetableState(
    val url: String = "",
    val sessions: List<CourseSession> = emptyList(),
    val groups: List<CourseGroup> = emptyList(),
    val detectedEventCount: Int = 0,
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val groupSyncError: String? = null,
    val isSyncingGroups: Boolean = false,
    /** When the sessions shown come from the copy saved on the device rather than a fresh download. */
    val savedAt: Instant? = null,
    /** Why the last download failed, while the saved copy is shown instead. */
    val refreshError: String? = null
) {
    /** One line for the screens when the saved copy is shown because the download failed, else null. */
    fun offlineNotice(zone: ZoneId = ZoneId.systemDefault()): String? {
        val saved = savedAt ?: return null
        if (refreshError == null) return null
        val formatter = DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mm a", Locale.ENGLISH)
        return "Could not refresh the timetable. Showing the copy saved on ${saved.atZone(zone).format(formatter)}."
    }
}
