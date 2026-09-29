package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupMember
import au.edu.unimelb.campuscompanion.data.model.GroupSummary
import au.edu.unimelb.campuscompanion.data.remote.GroupMemberRow
import au.edu.unimelb.campuscompanion.data.remote.GroupRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow
import au.edu.unimelb.campuscompanion.data.remote.dataResult
import au.edu.unimelb.campuscompanion.data.remote.remoteCall
import au.edu.unimelb.campuscompanion.data.remote.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * [GroupRepository] that loads groups from [remote]. The group list is kept in memory and is
 * updated after each refresh, new group, join and leave.
 *
 * @param currentUserId returns the signed-in user's id, or null when nobody is signed in.
 */
class DefaultGroupRepository(
    private val remote: GroupRemoteDataSource,
    private val currentUserId: () -> String?
) : GroupRepository {

    private val groups = MutableStateFlow<List<GroupSummary>?>(null)

    /** Emits after the first successful [refresh], then after every change. */
    override fun observeMyGroups(): Flow<List<GroupSummary>> = groups.filterNotNull()

    override suspend fun refresh(): Result<Unit> = dataResult {
        groups.value = remote.fetchMyGroups()
            .map(GroupSummaryRow::toModel)
            .sortedByDescending { it.latestActivityAt ?: it.group.createdAt }
    }

    override suspend fun createGroup(name: String, courseCode: String?): Result<Group> {
        val input = NewGroupInput.parse(name, courseCode).getOrElse { return Result.failure(it) }
        return dataResult { remote.createGroup(input.name, input.courseCode).toModel() }
            .onSuccess { refresh() }
    }

    /** Loads the member list once. The flow fails with a [DataError] when the list cannot be loaded. */
    override fun observeMembers(groupId: String): Flow<List<GroupMember>> = flow {
        emit(remoteCall { remote.fetchMembers(groupId).map(GroupMemberRow::toModel) })
    }

    override suspend fun leaveGroup(groupId: String): Result<Unit> {
        val userId = currentUserId() ?: return Result.failure(DataError.Unauthenticated())
        return dataResult {
            if (remote.deleteMembership(groupId, userId) == 0) {
                throw DataError.NotFound()
            }
            groups.update { list -> list?.filterNot { it.group.id == groupId } }
        }
    }
}
