package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.timetableGroupSpec

/** Repository summaries in Home order, with optional context for the first row. */
data class HomeGroupUpdates(
    val featuredGroup: CourseGroup?,
    val rows: List<CourseGroup>,
    val contextLabel: String?
)

/**
 * [finishedSession] must come from [HomeCardUiState.finishedSession]. The engine owns its window.
 * Matching uses remote timetable keys, which need not have a local timetable spec attached.
 */
fun buildHomeGroupUpdates(
    finishedSession: CourseSession?,
    groups: List<CourseGroup>,
    /** Joined groups in Home order, including automatic folds but excluding explicit folds. */
    postClassCandidates: List<CourseGroup> = groups
): HomeGroupUpdates {
    val uniqueGroups = groups.distinctBy(CourseGroup::id)
    val featured = finishedSession?.let { finished ->
        val canonicalKey = "${finished.code}|course"
        val slotKey = finished.timetableGroupSpec()?.key
        (postClassCandidates + uniqueGroups).distinctBy(CourseGroup::id).mapNotNull { group ->
            val rank = when {
                group.timetableKey == canonicalKey -> 0
                slotKey != null && group.timetableKey == slotKey -> 1
                group.timetableKey == null && group.origin != GroupOrigin.Timetable &&
                    group.courseCode.equals(finished.code, ignoreCase = true) -> 2
                else -> return@mapNotNull null
            }
            group to rank
        }.minByOrNull { it.second }?.first
    }
    return HomeGroupUpdates(
        featuredGroup = featured,
        rows = (listOfNotNull(featured) + uniqueGroups).distinctBy(CourseGroup::id).take(2),
        contextLabel = finishedSession?.takeIf { featured != null }?.let { "After ${it.code}" }
    )
}

/**
 * The caller supplies repository memberships, not unjoined timetable placeholders.
 * Automatic folding still applies on Groups; only a matching candidate can surface on Home.
 */
fun homePostClassCandidates(
    joinedGroups: List<CourseGroup>,
    preferences: Map<String, GroupChatPreferences>
): List<CourseGroup> = joinedGroups.filter { preferences[it.id]?.foldedOverride != true }
