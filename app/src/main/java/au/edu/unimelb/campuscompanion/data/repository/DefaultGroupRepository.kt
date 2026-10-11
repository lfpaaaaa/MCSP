package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupMember
import au.edu.unimelb.campuscompanion.data.model.GroupSummary
import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.data.remote.GroupMemberRow
import au.edu.unimelb.campuscompanion.data.remote.GroupRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.GroupRow
import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow
import au.edu.unimelb.campuscompanion.data.remote.dataResult
import au.edu.unimelb.campuscompanion.data.remote.remoteCall
import au.edu.unimelb.campuscompanion.data.remote.toModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Server-backed groups, with a separate offline list for each signed-in account. */
class DefaultGroupRepository(
    private val remote: GroupRemoteDataSource,
    private val cache: GroupListCache? = null,
    private val currentUserId: () -> String?
) : GroupRepository {
    // Identity distinguishes two logins of the same account as well as different accounts.
    private class Session(val userId: String)
    private data class GroupState(val session: Session? = null, val rows: List<GroupSummaryRow>? = null)
    private val state = MutableStateFlow(GroupState())
    // A refresh begun before a successful create/leave must not overwrite that newer change.
    private val listMutex = Mutex()

    override fun clearSession() {
        state.value = GroupState(rows = emptyList())
    }

    override fun observeMyGroups(): Flow<List<GroupSummary>> = flow {
        val userId = currentUserId()
        if (userId == null) {
            emit(emptyList())
            return@flow
        }
        // A cached list can be displayed while an already-running refresh waits for the network.
        if (state.value.session?.userId != userId || state.value.rows == null) {
            withSession { Unit }
        }
        emitAll(state.mapNotNull { value ->
            if (value.session?.userId != userId || currentUserId() != userId) emptyList()
            else value.rows?.map(GroupSummaryRow::toModel)?.sortedByActivity()
        })
    }

    override suspend fun refresh(): Result<Unit> = withSession { refresh(it) }

    override suspend fun syncTimetableGroups(specs: List<TimetableGroupSpec>): Result<Unit> = withSession { session ->
        val joined = remote.syncTimetableGroups(specs)
        requireCurrent(session)
        val existing = state.value.rows.orEmpty().associateBy { it.id }
        val rows = joined.map { group ->
            group.summary(existing[group.id]?.myRole ?: "member").let { updated ->
                existing[group.id]?.copy(name = updated.name, timetableKey = updated.timetableKey,
                    timetableSlot = updated.timetableSlot) ?: updated
            }
        } + existing.values.filterNot { old -> joined.any { it.id == old.id } }
        publish(session, rows)
        refresh(session)
    }

    override suspend fun createGroup(name: String, courseCode: String?): Result<Group> {
        val input = NewGroupInput.parse(name, courseCode).getOrElse { return Result.failure(it) }
        return withSession { session ->
            val group = remote.createGroup(input.name, input.courseCode)
            requireCurrent(session)
            // The server has already saved the group and its owner membership. Save the returned
            // record locally too, even if the subsequent list request loses its connection.
            publish(session, listOf(group.summary("owner")) + state.value.rows.orEmpty().filterNot { it.id == group.id })
            dataResult { refresh(session) }
            group.toModel()
        }
    }

    override suspend fun setMyNickname(groupId: String, nickname: String): Result<String> {
        if (currentUserId() == null) return Result.failure(DataError.Unauthenticated())
        val name = nickname.trim()
        if (name.isEmpty() || name.length > 40) {
            return Result.failure(DataError.Validation("Group nicknames need 1 to 40 characters."))
        }
        return dataResult { remote.setMyNickname(groupId, name) }
    }

    /** Re-read names on subscription/reconnection and nickname changes. */
    override fun observeMembers(groupId: String): Flow<List<GroupMember>> = flow {
        emit(remoteCall { remote.fetchMembers(groupId).map(GroupMemberRow::toModel) })
        remote.memberChanges(groupId).collect {
            emit(remoteCall { remote.fetchMembers(groupId).map(GroupMemberRow::toModel) })
        }
    }

    override suspend fun transferAndLeave(groupId: String, newOwnerId: String): Result<Unit> = withSession { session ->
        remote.transferAndLeave(groupId, newOwnerId)
        removeGroup(session, groupId)
    }

    override suspend fun dissolveGroup(groupId: String): Result<Unit> = withSession { session ->
        remote.dissolveGroup(groupId)
        removeGroup(session, groupId)
    }

    override suspend fun leaveGroup(groupId: String): Result<Unit> = withSession { session ->
        if (remote.deleteMembership(groupId, session.userId) == 0) throw DataError.NotFound()
        removeGroup(session, groupId)
    }

    private suspend fun <T> withSession(block: suspend (Session) -> T): Result<T> = dataResult {
        val userId = currentUserId() ?: throw DataError.Unauthenticated()
        val session = state.updateAndGet {
            if (it.session?.userId == userId) it else GroupState(Session(userId))
        }.session!!
        listMutex.withLock {
            requireCurrent(session)
            loadCache(session)
            block(session)
        }
    }

    private fun requireCurrent(session: Session) {
        if (state.value.session !== session || currentUserId() != session.userId) {
            throw DataError.Unauthenticated()
        }
    }

    private suspend fun loadCache(session: Session) {
        if (state.value.rows != null) return
        val saved = withContext(Dispatchers.IO) { runCatching { cache?.load(session.userId) }.getOrNull() }
        requireCurrent(session)
        if (saved != null) state.update { if (it.session === session) it.copy(rows = saved) else it }
    }

    private suspend fun refresh(session: Session) {
        requireCurrent(session)
        val rows = remote.fetchMyGroups()
        publish(session, rows)
    }

    private suspend fun removeGroup(session: Session, groupId: String) {
        requireCurrent(session)
        publish(session, state.value.rows.orEmpty().filterNot { it.id == groupId })
    }

    private suspend fun publish(session: Session, rows: List<GroupSummaryRow>) {
        requireCurrent(session)
        state.update { if (it.session === session) it.copy(rows = rows) else it }
        withContext(Dispatchers.IO) {
            // Cache failure must not turn a successful server operation into a failed create
            // that the user retries. Always write under the account that made the request.
            runCatching { cache?.save(session.userId, rows) }
        }
    }

    private fun List<GroupSummary>.sortedByActivity(): List<GroupSummary> =
        sortedByDescending { it.latestActivityAt ?: it.group.createdAt }

    private fun GroupRow.summary(role: String) = GroupSummaryRow(
        id = id, name = name, courseCode = courseCode,
        createdBy = createdBy, createdAt = createdAt,
        myRole = role, memberCount = 1, latestActivityAt = createdAt, joinCode = joinCode,
        timetableKey = timetableKey, timetableSlot = timetableSlot
    )
}
