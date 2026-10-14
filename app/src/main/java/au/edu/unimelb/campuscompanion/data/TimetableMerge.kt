package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.context.ContextConfig
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.Instant
import java.time.ZoneId

/** Dormant adjustments stay stored, but only originals present in this import can activate them. */
fun TimetableSnapshot.mergedSessions(): List<CourseSession> {
    val adjustments = local.filter { it.kind != LocalClassKind.MANUAL }.associateBy { it.id }
    return (imported.mapNotNull { original ->
        when (val adjustment = adjustments[original.id]) {
            null -> original
            else -> when (adjustment.kind) {
                LocalClassKind.EDITED -> requireNotNull(adjustment.session)
                LocalClassKind.HIDDEN -> null
                LocalClassKind.MANUAL -> original
            }
        }
    } + local.filter { it.kind == LocalClassKind.MANUAL }.map { requireNotNull(it.session) })
        .sortedWith(compareBy<CourseSession> { it.start.toInstant() }.thenBy { it.id })
}

/** Keeps the engine's post-class input through reloads; expiry uses the same exclusive boundary. */
fun TimetableSnapshot.forDisplay(
    now: Instant,
    zone: ZoneId,
    postClassMinutes: Int = ContextConfig().postClassMinutes
): TimetableImport = TimetableImport(
    sessions = mergedSessions().filter { it.isRetainedForContext(now, postClassMinutes) }.map {
        it.copy(start = it.start.withZoneSameInstant(zone), end = it.end.withZoneSameInstant(zone),
            etaMinutes = null, routeEstimate = null)
    },
    groups = source?.groups.orEmpty().map { it.toTimetableGroup() },
    sourceEventCount = source?.sourceEventCount ?: 0
)

/** Retain exact originals only while their corrected occurrence can still be displayed. */
fun TimetableSnapshot.retainedOccurrences(now: Instant): List<RetainedOccurrence> = local
    .filter { it.kind == LocalClassKind.EDITED && requireNotNull(it.session).end.toInstant().isAfter(now) }
    .map { RetainedOccurrence(it.id, requireNotNull(it.originalStart)) }
