package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId

/** Owns a signed-in account's timetable. Call actions from the UI thread. */
class TimetableSession(
    private val userId: String,
    private val storage: TimetablePersistence,
    private val importTimetable: suspend (String) -> Result<TimetableImport>,
    private val clock: () -> Instant = Instant::now,
    private val displayZone: () -> ZoneId = ZoneId::systemDefault
) {
    private val saved = storage.load(userId)
    private val cached = saved.timetable?.forDisplay(clock(), displayZone())
    private val mutableState = MutableStateFlow(TimetableState(
        url = saved.url, sessions = cached?.sessions.orEmpty(), groups = cached?.groups.orEmpty(),
        detectedEventCount = cached?.sourceEventCount ?: 0, isConnected = cached != null,
        isLoading = saved.url.isNotBlank(), isCached = cached != null, lastSyncedAt = saved.savedAt
    ))
    val state = mutableState.asStateFlow()
    private var requestId = 0

    suspend fun refresh(): Result<Unit> {
        val url = state.value.url
        if (url.isBlank()) return Result.success(Unit)
        return connect(url)
    }

    suspend fun connect(url: String): Result<Unit> {
        val normalized = url.trim()
        val request = ++requestId
        mutableState.value = state.value.copy(isLoading = true, errorMessage = null)
        try {
            val result = importTimetable(normalized)
            // A removed/replaced timetable must not be resurrected by an older response.
            if (request != requestId) return Result.failure(CancellationException("Timetable request superseded"))
            return result.fold(
                onSuccess = { imported ->
                    val savedAt = clock()
                    storage.save(userId, normalized, imported, savedAt)
                    val visible = imported.forDisplay(savedAt, displayZone())
                    mutableState.value = TimetableState(url = normalized, sessions = visible.sessions,
                        groups = visible.groups, detectedEventCount = visible.sourceEventCount,
                        isConnected = true, lastSyncedAt = savedAt)
                    Result.success(Unit)
                },
                onFailure = { error -> fail(error) }
            )
        } catch (cancelled: CancellationException) {
            if (request == requestId) mutableState.value = state.value.copy(isLoading = false)
            throw cancelled
        } catch (error: Exception) {
            return if (request == requestId) fail(error) else Result.failure(error)
        }
    }

    fun clear() {
        requestId++
        storage.clear(userId)
        mutableState.value = TimetableState()
    }

    private fun fail(error: Throwable): Result<Unit> {
        mutableState.value = state.value.copy(isLoading = false, isCached = state.value.isConnected,
            errorMessage = error.message ?: "The timetable could not be refreshed.")
        return Result.failure(error)
    }
}
