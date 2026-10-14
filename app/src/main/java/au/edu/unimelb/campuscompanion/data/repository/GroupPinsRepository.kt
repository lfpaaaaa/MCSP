package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.*
import au.edu.unimelb.campuscompanion.data.remote.dataResult
import au.edu.unimelb.campuscompanion.data.remote.toDataError
import au.edu.unimelb.campuscompanion.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

interface GroupPinsDataSource {
    suspend fun fetch(groupId: String): GroupPinsSnapshot
    suspend fun pin(groupId: String, target: PinTarget, forEveryone: Boolean)
    suspend fun unpin(groupId: String, pinId: String)
    fun changes(groupId: String): Flow<Unit>
}

/** Server is authoritative. Failed mutations never become successful local pins. */
class GroupPinsRepository(private val remote: GroupPinsDataSource?, private val refreshMillis: Long = 30_000) {
    // Replay the last invalidation: a mutation can finish during the initial fetch, before live collectors start.
    private val invalidations = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 16)

    fun observe(groupId: String): Flow<GroupPinsState> = if (remote == null) flowOf(GroupPinsState(loading = false)) else flow {
        var state = GroupPinsState()
        emit(state)
        merge(
            remote.changes(groupId).retryWhen { error, _ ->
                if (error is CancellationException) throw error
                delay(2_000); true
            },
            invalidations.filter { it == groupId }.map { Unit },
            flow { while (true) { delay(refreshMillis); emit(Unit) } }
        ).onStart { emit(Unit) }.conflate().collect {
            state = dataResult { remote.fetch(groupId) }.fold(
                onSuccess = { GroupPinsState(items = it.items, canPinForEveryone = it.canPinForEveryone, loading = false) },
                onFailure = { error ->
                    val failure = error.toDataError()
                    GroupPinsState(items = if (failure is DataError.Forbidden || failure is DataError.Unauthenticated) emptyList() else state.items,
                        canPinForEveryone = state.canPinForEveryone && failure !is DataError.Forbidden && failure !is DataError.Unauthenticated,
                        loading = false, error = failure)
                }
            )
            emit(state)
        }
    }

    fun refresh(groupId: String) { invalidations.tryEmit(groupId) }

    suspend fun pin(groupId: String, target: PinTarget, forEveryone: Boolean): Result<Unit> = dataResult {
        source().pin(groupId, target, forEveryone)
        refresh(groupId)
    }

    suspend fun unpin(groupId: String, pinId: String): Result<Unit> = dataResult {
        source().unpin(groupId, pinId)
        refresh(groupId)
    }

    private fun source(): GroupPinsDataSource = remote
        ?: throw DataError.Validation(UiText(R.string.pins_backend_required))
}
