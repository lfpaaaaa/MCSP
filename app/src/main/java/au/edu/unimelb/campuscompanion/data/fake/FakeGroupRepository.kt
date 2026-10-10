package au.edu.unimelb.campuscompanion.data.fake

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec

import au.edu.unimelb.campuscompanion.data.slotLabel
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
    private val otherGroups = listOf(FakeData.joinableGroup(clock())).associateBy { it.id }.toMutableMap()

    override fun observeMyGroups(): Flow<List<GroupSummary>> =
        summaries.map { list ->
            list.sortedByDescending { it.latestActivityAt ?: it.group.createdAt }
        }

    override suspend fun syncTimetableGroups(specs: List<TimetableGroupSpec>): Result<Unit> {
        val now = clock()
        specs.forEach { spec ->
            val id = UUID.nameUUIDFromBytes(spec.key.toByteArray()).toString()
            if (summaries.value.none { it.group.id == id }) {
                val group = Group(id, spec.name, spec.courseCode, false, null, now,
                    timetableKey = spec.key, timetableSlot = spec.slotLabel())
                members.update { it + (id to listOf(currentMember(GroupRole.Member, now))) }
                summaries.update { it + GroupSummary(group, GroupRole.Member, 1, 0, null, now, null) }
            }
        }
        return Result.success(Unit)
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
            createdAt = now,
            joinCode = FakeData.randomJoinCode()
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

    override suspend fun resetJoinCode(groupId: String): Result<String> {
        delay(latencyMillis)
        val summary = summaries.value.firstOrNull { it.group.id == groupId }
            ?: return Result.failure(DataError.NotFound())
        if (summary.myRole != GroupRole.Owner) return Result.failure(DataError.Forbidden())
        val code = FakeData.randomJoinCode()
        summaries.update { list ->
            list.map { if (it.group.id == groupId) it.copy(group = it.group.copy(joinCode = code)) else it }
        }
        return Result.success(code)
    }

    override fun observeMembers(groupId: String): Flow<List<GroupMember>> =
        members.map { byGroup ->
            byGroup[groupId].orEmpty().sortedWith(
                compareBy<GroupMember> { it.role != GroupRole.Owner }.thenBy { it.displayName }
            )
        }

    override suspend fun transferAndLeave(groupId: String, newOwnerId: String): Result<Unit> {
        val summary = summaries.value.firstOrNull { it.group.id == groupId }
        if (summary?.myRole != GroupRole.Owner) return Result.failure(DataError.Forbidden())
        if (newOwnerId == currentUserId || members.value[groupId].orEmpty().none { it.userId == newOwnerId })
            return Result.failure(DataError.Forbidden())
        members.update { map -> map + (groupId to map[groupId].orEmpty().map {
            if (it.userId == newOwnerId) it.copy(role = GroupRole.Owner) else it
        }) }
        return leaveGroup(groupId)
    }

    override suspend fun dissolveGroup(groupId: String): Result<Unit> {
        val summary = summaries.value.firstOrNull { it.group.id == groupId }
            ?: return Result.failure(DataError.NotFound())
        if (summary.myRole != GroupRole.Owner) return Result.failure(DataError.Forbidden())
        otherGroups.remove(groupId)
        return leaveGroup(groupId)
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
    /** A group, joined or not, whose join code matches. */
    fun findGroupByJoinCode(code: String): Group? =
        (summaries.value.map { it.group } + otherGroups.values).firstOrNull { it.joinCode == code }

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
