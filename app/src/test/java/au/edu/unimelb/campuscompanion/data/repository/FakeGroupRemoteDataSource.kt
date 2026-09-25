package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.remote.GroupMemberRow
import au.edu.unimelb.campuscompanion.data.remote.GroupRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.GroupRow
import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow
import au.edu.unimelb.campuscompanion.data.remote.InviteRow

/** Records every call and returns canned rows. Set [failure] to make every call fail. */
class FakeGroupRemoteDataSource : GroupRemoteDataSource {
    var failure: DataError? = null
    var summaries: List<GroupSummaryRow> = emptyList()
    var members: List<GroupMemberRow> = emptyList()
    var deletedRowCount = 1
    var inviteToken = "a".repeat(64)
    var inviteExpiresAt = "2026-09-25T06:10:00+00:00"

    var summaryFetches = 0
        private set
    val createdGroups = mutableListOf<Pair<String, String?>>()
    val deletedMemberships = mutableListOf<Pair<String, String>>()
    val joinedTokens = mutableListOf<String>()

    override suspend fun fetchMyGroups(): List<GroupSummaryRow> {
        failIfRequested()
        summaryFetches++
        return summaries
    }

    override suspend fun createGroup(name: String, courseCode: String?): GroupRow {
        failIfRequested()
        createdGroups += name to courseCode
        return groupRow(id = "new-group", name = name, courseCode = courseCode)
    }

    override suspend fun fetchMembers(groupId: String): List<GroupMemberRow> {
        failIfRequested()
        return members
    }

    override suspend fun deleteMembership(groupId: String, userId: String): Int {
        failIfRequested()
        deletedMemberships += groupId to userId
        return deletedRowCount
    }

    override suspend fun createInvite(groupId: String): InviteRow {
        failIfRequested()
        return InviteRow(token = inviteToken, expiresAt = inviteExpiresAt)
    }

    override suspend fun joinWithToken(token: String): GroupRow {
        failIfRequested()
        joinedTokens += token
        return groupRow(id = "joined-group", name = "Library study session")
    }

    private fun failIfRequested() {
        failure?.let { throw it }
    }
}

fun groupRow(id: String, name: String = "Group $id", courseCode: String? = null) = GroupRow(
    id = id,
    name = name,
    courseCode = courseCode,
    createdBy = "user-1",
    createdAt = "2026-09-20T00:00:00+00:00"
)

fun summaryRow(id: String, latestActivityAt: String? = null, role: String = "member") = GroupSummaryRow(
    id = id,
    name = "Group $id",
    createdBy = "user-1",
    createdAt = "2026-09-20T00:00:00+00:00",
    myRole = role,
    memberCount = 2,
    latestActivityAt = latestActivityAt
)
