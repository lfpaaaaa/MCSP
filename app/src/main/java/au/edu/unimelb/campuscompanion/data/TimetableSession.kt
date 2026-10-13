package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** An app-owned account session. Screen cancellation does not cancel its persistence work. */
class TimetableSession(
    val ownerId: String,
    private val repository: TimetableRepository,
    parentScope: CoroutineScope,
    private val download: suspend (String) -> Result<ByteArray>,
    private val parse: suspend (ByteArray, List<RetainedOccurrence>) -> Result<TimetableImport>,
    private val lifecycleDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: () -> Instant = Instant::now,
    private val displayZone: () -> ZoneId = ZoneId::systemDefault
) : AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + lifecycleDispatcher)
    private val gate = Mutex()
    private val mutableState = MutableStateFlow(TimetableState(isLoaded = false, isLoading = true))
    val state = mutableState.asStateFlow()
    val isActive: Boolean get() = job.isActive

    private var initialization: Deferred<Result<Unit>>? = null
    private var snapshot: TimetableSnapshot? = null
    private var requestId = 0L

    /** Loads once; a failed initial read remains unresolved until [retryLoad]. */
    suspend fun initialize(): Result<Unit> = ownedAction { awaitInitialization(retry = false) }

    suspend fun retryLoad(): Result<Unit> = ownedAction { awaitInitialization(retry = true) }

    suspend fun refresh(): Result<Unit> = ownedAction {
        val loaded = awaitInitialization(retry = false)
        if (loaded.isFailure) loaded else importOwned(requestedUrl = null)
    }

    suspend fun connect(url: String): Result<Unit> = ownedAction {
        val loaded = awaitInitialization(retry = false)
        if (loaded.isFailure) loaded else importOwned(url.trim())
    }

    /** Confirms legacy cleanup and commits removal before publishing manual-only state. */
    suspend fun clear(): Result<Unit> = ownedAction {
        val loaded = awaitInitialization(retry = false)
        if (loaded.isFailure) return@ownedAction loaded
        gate.withLock {
            checkActive()
            requestId++
            try {
                val committed = repository.disconnect(ownerId)
                checkActive()
                publish(committed, cached = false)
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                if (isActive) mutableState.value = state.value.copy(isLoading = false)
                throw cancelled
            } catch (error: Exception) {
                fail(error, "The timetable could not be removed.", markCached = false)
            }
        }
    }

    override fun close() {
        scope.cancel(CancellationException("Timetable session closed"))
    }

    private suspend fun <T> ownedAction(action: suspend () -> T): T {
        // Entry and execution both occur on the account lifecycle dispatcher. Awaiting from a
        // screen does not make that screen the parent of the accepted operation.
        return withContext(lifecycleDispatcher) {
            checkActive()
            scope.async {
                checkActive()
                action()
            }.await()
        }
    }

    private suspend fun awaitInitialization(retry: Boolean): Result<Unit> {
        val pending = gate.withLock {
            checkActive()
            if (state.value.isLoaded) return Result.success(Unit)
            val existing = initialization
            if (existing == null || (retry && existing.isCompleted)) {
                scope.async(start = CoroutineStart.LAZY) { loadInitial() }.also { initialization = it }
            } else existing
        }
        return pending.await()
    }

    private suspend fun loadInitial(): Result<Unit> = gate.withLock {
        checkActive()
        mutableState.value = state.value.copy(isLoading = true, errorMessage = null)
        try {
            val loaded = repository.load(ownerId)
            checkActive()
            publish(loaded, cached = loaded.source?.lastSyncedAt != null)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            if (isActive) mutableState.value = state.value.copy(isLoading = false)
            throw cancelled
        } catch (error: Exception) {
            checkActive()
            currentCoroutineContext().ensureActive()
            mutableState.value = state.value.copy(isLoaded = false, isLoading = false,
                errorMessage = error.message ?: "The saved timetable could not be loaded.")
            Result.failure(error)
        }
    }

    private suspend fun importOwned(requestedUrl: String?): Result<Unit> {
        val (request, url) = gate.withLock {
            checkActive()
            val url = requestedUrl ?: snapshot?.source?.url.orEmpty()
            if (url.isBlank()) return Result.success(Unit)
            requestId++
            mutableState.value = state.value.copy(isLoading = true, errorMessage = null)
            requestId to url
        }
        try {
            // Download without holding the gate so replacement and disconnect can invalidate it.
            val bytes = download(url).getOrThrow()
            return gate.withLock {
                checkRequest(request)
                // Read after the network wait: edits saved during it supply the retention targets.
                // This is a pre-commit read; the mutation itself returns its committed aggregate.
                val latest = repository.load(ownerId)
                checkRequest(request)
                val retained = if (latest.source?.url == url) latest.retainedOccurrences(clock()) else emptyList()
                val imported = parse(bytes, retained).getOrThrow()
                checkRequest(request)
                val committed = repository.replaceImport(ownerId, url, imported, clock())
                checkRequest(request)
                publish(committed, cached = false)
                Result.success(Unit)
            }
        } catch (cancelled: CancellationException) {
            // A downloader may cancel independently of session disposal. Stop only its spinner;
            // a superseded request must not change the newer request's state.
            if (isActive) withContext(NonCancellable + lifecycleDispatcher) {
                gate.withLock {
                    if (isActive && request == requestId) {
                        mutableState.value = state.value.copy(isLoading = false)
                    }
                }
            }
            throw cancelled
        } catch (error: Exception) {
            return gate.withLock {
                checkRequest(request)
                fail(error, "The timetable could not be refreshed.")
            }
        }
    }

    private suspend fun publish(committed: TimetableSnapshot, cached: Boolean) {
        currentCoroutineContext().ensureActive()
        checkActive()
        val visible = committed.forDisplay(clock(), displayZone())
        snapshot = committed
        val source = committed.source
        mutableState.value = TimetableState(
            url = source?.url.orEmpty(), sessions = visible.sessions, groups = visible.groups,
            detectedEventCount = source?.sourceEventCount ?: 0, isLoaded = true,
            isConnected = source?.lastSyncedAt != null, isCached = cached,
            lastSyncedAt = source?.lastSyncedAt
        )
    }

    private fun fail(error: Throwable, fallback: String, markCached: Boolean = true): Result<Unit> {
        checkActive()
        mutableState.value = state.value.copy(isLoading = false,
            isCached = if (markCached) state.value.isConnected else state.value.isCached,
            errorMessage = error.message ?: fallback)
        return Result.failure(error)
    }

    private suspend fun checkRequest(request: Long) {
        currentCoroutineContext().ensureActive()
        checkActive()
        if (request != requestId) throw CancellationException("Timetable request superseded")
    }

    private fun checkActive() {
        job.ensureActive()
    }
}
