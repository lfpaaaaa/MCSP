package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A successful import, independent of live route estimates and server group membership. */
data class SavedTimetable(
    val url: String,
    val timetable: TimetableImport? = null,
    val savedAt: Instant? = null
)

interface TimetablePersistence {
    fun load(userId: String): SavedTimetable
    fun save(userId: String, url: String, timetable: TimetableImport, savedAt: Instant)
    fun clear(userId: String)
}

/** Versioned disk format. Private subscription URLs never leave app-private storage. */
internal object TimetableCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(url: String, timetable: TimetableImport, savedAt: Instant): String =
        json.encodeToString(Snapshot(
            url = url,
            savedAt = savedAt.toString(),
            sourceEventCount = timetable.sourceEventCount,
            sessions = timetable.sessions.map { session ->
                Session(session.id, session.code, session.title, session.location, session.room,
                    session.start.toString(), session.end.toString(), session.activity)
            },
            groups = timetable.groups.mapNotNull { it.timetableSpec }
        ))

    fun decode(value: String?, expectedUrl: String): SavedTimetable? = runCatching {
        if (value == null || expectedUrl.isBlank()) return null
        val snapshot = json.decodeFromString<Snapshot>(value)
        if (snapshot.version != 1 || snapshot.url != expectedUrl) return null
        val sessions = snapshot.sessions.map { session ->
            CourseSession(session.id, session.code, session.title, session.location, session.room,
                ZonedDateTime.parse(session.start), ZonedDateTime.parse(session.end), activity = session.activity)
        }
        val groups = snapshot.groups.map { spec ->
            CourseGroup(id = spec.key, courseCode = spec.courseCode, name = spec.name,
                members = 0, unreadCount = 0, latestMessage = "Added from your connected timetable.",
                latestFileName = null, privateContentEnabled = false,
                timetableKey = spec.key, timetableSlot = spec.slotLabel(), timetableSpec = spec)
        }
        SavedTimetable(snapshot.url, TimetableImport(sessions, groups, snapshot.sourceEventCount),
            Instant.parse(snapshot.savedAt))
    }.getOrNull()

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val url: String,
        val savedAt: String,
        val sourceEventCount: Int,
        val sessions: List<Session>,
        val groups: List<TimetableGroupSpec>
    )

    @Serializable
    private data class Session(
        val id: String, val code: String, val title: String, val location: String, val room: String,
        val start: String, val end: String, val activity: String? = null
    )
}

/** Re-evaluate dates at startup; yesterday's classes and travel estimates must not appear as upcoming. */
internal fun TimetableImport.forDisplay(now: Instant, zone: ZoneId): TimetableImport = copy(
    sessions = sessions.filter { it.end.toInstant().isAfter(now) }
        .sortedBy { it.start.toInstant() }
        .map { it.copy(start = it.start.withZoneSameInstant(zone), end = it.end.withZoneSameInstant(zone),
            etaMinutes = null, routeEstimate = null) }
)
