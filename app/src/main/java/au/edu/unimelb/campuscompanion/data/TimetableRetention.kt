package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.context.ContextConfig
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.Duration
import java.time.Instant

/** Keep the engine's post-class input through reloads, with the same exclusive expiry boundary. */
internal fun CourseSession.isRetainedForContext(
    now: Instant,
    postClassMinutes: Int = ContextConfig().postClassMinutes
): Boolean {
    val end = end.toInstant()
    return end.isAfter(now) || Duration.between(end, now) <
        Duration.ofMinutes(postClassMinutes.coerceAtLeast(0).toLong())
}
