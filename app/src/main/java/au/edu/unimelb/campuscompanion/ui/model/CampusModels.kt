package au.edu.unimelb.campuscompanion.ui.model

import java.time.LocalTime

data class CourseSession(
    val id: String,
    val code: String,
    val title: String,
    val location: String,
    val room: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val etaMinutes: Int,
    val status: SessionStatus
)

fun CourseSession.departureReminderTime(leadMinutes: Int): LocalTime {
    require(leadMinutes >= 0) { "Lead time cannot be negative" }
    return startTime.minusMinutes((etaMinutes + leadMinutes).toLong())
}

enum class SessionStatus {
    Upcoming,
    LeaveSoon,
    EnRoute,
    Arrived,
    Finished
}

data class CampusContext(
    val headline: String,
    val detail: String,
    val locationLabel: String,
    val destinationLabel: String,
    val etaMinutes: Int,
    val bufferMinutes: Int,
    val status: SessionStatus
)

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

object MockCampusData {
    val currentContext = CampusContext(
        headline = "Leave in 5 min",
        detail = "COMP90018 starts at 3:00 PM in PAR-160.",
        locationLabel = "Baillieu Library",
        destinationLabel = "PAR-160",
        etaMinutes = 30,
        bufferMinutes = 5,
        status = SessionStatus.LeaveSoon
    )

    val sessions = listOf(
        CourseSession(
            id = "comp90018-today",
            code = "COMP90018",
            title = "Mobile Computing Systems Programming",
            location = "PAR-160",
            room = "Room 160",
            startTime = LocalTime.of(15, 0),
            endTime = LocalTime.of(16, 0),
            etaMinutes = 30,
            status = SessionStatus.LeaveSoon
        ),
        CourseSession(
            id = "swen90014-today",
            code = "SWEN90014",
            title = "Masters Software Engineering Project",
            location = "Doug McDonell Building",
            room = "G06",
            startTime = LocalTime.of(17, 15),
            endTime = LocalTime.of(18, 15),
            etaMinutes = 11,
            status = SessionStatus.Upcoming
        ),
        CourseSession(
            id = "info90002-tomorrow",
            code = "INFO90002",
            title = "Database Systems and Information Modelling",
            location = "Arts West",
            room = "Forum Theatre",
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
            etaMinutes = 18,
            status = SessionStatus.Upcoming
        )
    )

    val groups = listOf(
        CourseGroup(
            id = "comp90018-group",
            courseCode = "COMP90018",
            name = "Team 02",
            members = 4,
            unreadCount = 3,
            latestMessage = "Cedric uploaded the first Supabase schema draft.",
            latestFileName = "proposal.pdf",
            privateContentEnabled = true
        ),
        CourseGroup(
            id = "swen90014-group",
            courseCode = "SWEN90014",
            name = "Workshop partners",
            members = 5,
            unreadCount = 0,
            latestMessage = "Meeting notes are ready for review.",
            latestFileName = "notes-week-6.docx",
            privateContentEnabled = false
        )
    )
}
