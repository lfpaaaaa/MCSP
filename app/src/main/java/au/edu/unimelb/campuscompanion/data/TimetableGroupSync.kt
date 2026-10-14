package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import java.time.Instant

/** The existing group-sync effect's inputs; a new account activation has a distinct session. */
data class TimetableGroupSyncKey(
    val session: TimetableSession?,
    val ownerId: String,
    val groups: List<CourseGroup>,
    val connected: Boolean,
    val cached: Boolean,
    val loading: Boolean,
    val lastSyncedAt: Instant?,
    val loaded: Boolean
) {
    val specs: List<TimetableGroupSpec> get() = groups.mapNotNull { it.timetableSpec }

    val eligible: Boolean get() = session != null && session.ownerId == ownerId && session.isActive &&
        loaded && connected && !cached && !loading && lastSyncedAt != null

    companion object {
        fun from(ownerId: String, session: TimetableSession?, state: TimetableState) = TimetableGroupSyncKey(
            session = session, ownerId = ownerId, groups = state.groups,
            connected = state.isConnected, cached = state.isCached, loading = state.isLoading,
            lastSyncedAt = state.lastSyncedAt, loaded = state.isLoaded
        )
    }
}
