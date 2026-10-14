package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

/** The account's durable timetable, before expiry, display-zone or travel projection. */
data class TimetableSnapshot(
    val source: TimetableSource? = null,
    val imported: List<CourseSession> = emptyList(),
    val local: List<LocalClass> = emptyList()
)

data class TimetableSource(
    val url: String,
    val lastSyncedAt: Instant? = null,
    val sourceEventCount: Int = 0,
    val groups: List<TimetableGroupSpec> = emptyList()
)

enum class LocalClassKind { MANUAL, EDITED, HIDDEN }

/** Classes added by hand carry this prefix, so screens can tell them from imported ones. */
const val MANUAL_CLASS_ID_PREFIX = "manual:"

/** A fresh identity for a class added by hand. */
fun newManualClassId(): String = MANUAL_CLASS_ID_PREFIX + UUID.randomUUID()

/** True for a class the user added by hand rather than imported from the timetable URL. */
val CourseSession.isManualClass: Boolean get() = id.startsWith(MANUAL_CLASS_ID_PREFIX)

/** An edit keeps the imported identity and the first original start, even after subsequent edits. */
data class LocalClass(
    val id: String,
    val kind: LocalClassKind,
    val session: CourseSession? = null,
    val originalStart: ZonedDateTime? = null
) {
    internal fun validate() {
        require(id.isNotBlank()) { "A local class must have an ID." }
        when (kind) {
            LocalClassKind.MANUAL -> {
                require(id.startsWith(MANUAL_CLASS_ID_PREFIX)) { "Manual class IDs must start with manual:." }
                val suffix = id.removePrefix(MANUAL_CLASS_ID_PREFIX)
                require(UUID.fromString(suffix).toString().equals(suffix, ignoreCase = true)) {
                    "Manual class IDs must contain a UUID."
                }
                requireNotNull(session) { "A manual class requires its full payload." }
                require(originalStart == null) { "A manual class has no imported anchor." }
            }
            LocalClassKind.EDITED -> {
                requireNotNull(session) { "An edited class requires its full payload." }
                requireNotNull(originalStart) { "An edited class requires its original start." }
            }
            LocalClassKind.HIDDEN -> {
                require(session == null) { "A hidden row has no corrected payload." }
            }
        }
        require(session == null || session.id == id) { "Local payload and row IDs must match." }
    }
}
