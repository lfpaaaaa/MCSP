package au.edu.unimelb.campuscompanion.data.fake

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupMember
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.model.GroupSummary
import au.edu.unimelb.campuscompanion.data.repository.GroupRepository
import au.edu.unimelb.campuscompanion.data.repository.NewGroupInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/** In-memory [GroupRepository] with sample data, for building and testing screens offline. */
class FakeGroupRepository(
    private val currentUserId: String = FakeData.CURRENT_USER_ID,
    private val currentUserName: String = FakeData.CURRENT_USER_NAME,
    private val latencyMillis: Long = FakeData.DEFAULT_LATENCY_MILLIS,
    private val clock: () -> Instant = Instant::now
) : GroupRepository {

    private val summaries = MutableStateFlow(FakeData.groupSummaries(clock()))
    private val members = MutableStateFlow(FakeData.members(clock()))
    private val otherGroups = listOf(FakeData.joinableGroup(clock())).associateBy { it.id }

    override fun observeMyGroups(): Flow<List<GroupSummary>> =
        summaries.map { list ->
            list.sortedByDescending { it.latestActivityAt ?: it.group.createdAt }
        }

    override suspend fun refresh(): Result<Unit> {
        delay(latencyMillis)
        return Result.success(Unit)
    }

    override suspend fun createGroup(name: String, courseCode: String?): Result<Group> {
        val input = NewGroupInput.parse(name, courseCode).getOrElse { return Result.failure(it) }
        delay(latencyMillis)
        val now = clock()
        val group = Group(
            id = UUID.randomUUID().toString(),
            name = input.name,
            courseCode = input.courseCode,
            privateContentEnabled = false,
            createdBy = currentUserId,
            createdAt = now
        )
        members.update { byGroup ->
            byGroup + (group.id to listOf(currentMember(GroupRole.Owner, now)))
        }
        summaries.update { list ->
            list + GroupSummary(
                group = group,
                myRole = GroupRole.Owner,
                memberCount = 1,
                unreadCount = 0,
                latestMessagePreview = null,
                latestActivityAt = now,
                latestFileName = null
            )
        }
        return Result.success(group)
    }

    override fun observeMembers(groupId: String): Flow<List<GroupMember>> =
        members.map { byGroup ->
            byGroup[groupId].orEmpty().sortedWith(
                compareBy<GroupMember> { it.role != GroupRole.Owner }.thenBy { it.displayName }
            )
        }

    override suspend fun leaveGroup(groupId: String): Result<Unit> {
        delay(latencyMillis)
        if (!isMember(groupId)) {
            return Result.failure(DataError.NotFound())
        }
        summaries.update { list -> list.filterNot { it.group.id == groupId } }
        members.update { byGroup ->
            byGroup + (groupId to byGroup[groupId].orEmpty().filterNot { it.userId == currentUserId })
        }
        return Result.success(Unit)
    }

    /** True when the signed-in user belongs to [groupId]. */
    fun isMember(groupId: String): Boolean = summaries.value.any { it.group.id == groupId }

    /** Finds a group by id, including sample groups the user has not joined yet. */
    fun findGroup(groupId: String): Group? =
        summaries.value.firstOrNull { it.group.id == groupId }?.group ?: otherGroups[groupId]

    /** Adds the signed-in user to [group] as a member; does nothing if they already belong to it. */
    fun addCurrentUser(group: Group) {
        if (isMember(group.id)) return
        val now = clock()
        members.update { byGroup ->
            byGroup + (group.id to byGroup[group.id].orEmpty() + currentMember(GroupRole.Member, now))
        }
        summaries.update { list ->
            list + GroupSummary(
                group = group,
                myRole = GroupRole.Member,
                memberCount = members.value[group.id].orEmpty().size,
                unreadCount = 0,
                latestMessagePreview = null,
                latestActivityAt = now,
                latestFileName = null
            )
        }
    }

    private fun currentMember(role: GroupRole, joinedAt: Instant) = GroupMember(
        userId = currentUserId,
        displayName = currentUserName,
        avatarUrl = null,
        role = role,
        joinedAt = joinedAt
    )
}
