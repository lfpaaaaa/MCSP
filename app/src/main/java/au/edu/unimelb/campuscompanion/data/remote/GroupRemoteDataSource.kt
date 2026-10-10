package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec

/**
 * Server calls behind the group and invite repositories. Implementations throw
 * [au.edu.unimelb.campuscompanion.data.DataError] when a call fails.
 */
interface GroupRemoteDataSource {
    suspend fun syncTimetableGroups(specs: List<TimetableGroupSpec>): List<GroupRow>

    /** Groups of the signed-in user with member counts, unread counts and latest activity. */
    suspend fun transferAndLeave(groupId: String, newOwnerId: String)

    suspend fun dissolveGroup(groupId: String)

    suspend fun fetchMyGroups(): List<GroupSummaryRow>

    /** Creates a group with the signed-in user as its owner. */
    suspend fun createGroup(name: String, courseCode: String?): GroupRow

    /** Names and avatars of the members of a group that the signed-in user belongs to. */
    suspend fun fetchMembers(groupId: String): List<GroupMemberRow>

    /** Removes [userId] from [groupId] and returns the number of memberships removed. */
    suspend fun deleteMembership(groupId: String, userId: String): Int

    /** Issues a short-lived invite for a group that the signed-in user belongs to. */
    suspend fun createInvite(groupId: String): InviteRow

    /** Joins the group behind an invite token and returns that group. */
    suspend fun joinWithToken(token: String): GroupRow
}
