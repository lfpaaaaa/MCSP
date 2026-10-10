package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec

import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupMember
import au.edu.unimelb.campuscompanion.data.model.GroupSummary
import kotlinx.coroutines.flow.Flow

/** Course groups the signed-in user belongs to. */
interface GroupRepository {
    suspend fun setMyNickname(groupId: String, nickname: String): Result<String>

    suspend fun syncTimetableGroups(specs: List<TimetableGroupSpec>): Result<Unit>

    /** Emits the cached list first and then every change, most recent activity first. */
    fun observeMyGroups(): Flow<List<GroupSummary>>

    /** Fetches the latest group list from the server. */
    suspend fun refresh(): Result<Unit>

    /** Creates a group with the signed-in user as its owner. */
    suspend fun createGroup(name: String, courseCode: String?): Result<Group>

    fun observeMembers(groupId: String): Flow<List<GroupMember>>

    suspend fun transferAndLeave(groupId: String, newOwnerId: String): Result<Unit>

    suspend fun dissolveGroup(groupId: String): Result<Unit>

    suspend fun leaveGroup(groupId: String): Result<Unit>

    companion object {
        const val MAX_NAME_LENGTH = 60

        /** University subject codes such as COMP90018. */
        val COURSE_CODE_PATTERN = Regex("^[A-Z]{4}\\d{5}$")
    }
}
