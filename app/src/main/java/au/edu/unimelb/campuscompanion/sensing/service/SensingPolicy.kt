package au.edu.unimelb.campuscompanion.sensing.service

import au.edu.unimelb.campuscompanion.context.ContextConfig
import au.edu.unimelb.campuscompanion.context.ContextEngine
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.sensing.location.SensingTarget
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.Instant

enum class SensingMode { STOP, NORMAL, PRE_CLASS }

/** The service follows timetable time, rather than the screen's loading state. */
object SensingPolicy {
    fun mode(
        accountEligible: Boolean,
        precisePermission: Boolean,
        session: CourseSession?,
        target: SensingTarget,
        snapshot: TravelSnapshot,
        now: Instant,
        highRateOverride: Boolean = false,
        config: ContextConfig = ContextConfig()
    ): SensingMode {
        if (!accountEligible || !precisePermission || !target.active) return SensingMode.STOP
        if (highRateOverride) return SensingMode.PRE_CLASS
        if (session == null || session.id != target.sessionId || !now.isBefore(session.end.toInstant())) {
            return SensingMode.NORMAL
        }
        val start = session.start.toInstant()
        // Same-building room/title edits can retain the timing for sampling while Home updates.
        // Retimed classes, new buildings and accounts must bootstrap their own guidance.
        val guidance = snapshot.takeIf {
            val resolved = it.session
            !it.pending && resolved != null && resolved.id == session.id && resolved.code == session.code &&
                resolved.start.toInstant() == start && resolved.end.toInstant() == session.end.toInstant() &&
                it.building == target.building
        }
        if (!now.isBefore(start)) {
            return if (now.isBefore(start.plusSeconds(config.lateGraceMinutes * 60L)) ||
                guidance?.state == ContextState.EN_ROUTE || guidance?.state == ContextState.RUNNING_LATE) {
                SensingMode.PRE_CLASS
            } else SensingMode.NORMAL
        }
        if (guidance?.state == ContextState.EN_ROUTE || guidance?.state == ContextState.ARRIVED) {
            return SensingMode.PRE_CLASS
        }
        val windowOpens = ContextEngine.departureWindowOpensAt(start, guidance?.leaveBy?.toInstant(), config)
        return if (!now.isBefore(windowOpens)) SensingMode.PRE_CLASS else SensingMode.NORMAL
    }
}
