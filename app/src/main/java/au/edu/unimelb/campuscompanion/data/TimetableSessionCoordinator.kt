package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Serializes account replacement and the single feed shared by reminders and directions. */
class TimetableSessionCoordinator(
    private val scope: CoroutineScope,
    private val createSession: (String) -> TimetableSession,
    private val onReset: () -> Unit,
    private val onFeed: (List<CourseSession>, Boolean) -> Unit,
    private val lifecycleDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private val mutableCurrent = MutableStateFlow<TimetableSession?>(null)
    val current = mutableCurrent.asStateFlow()

    init {
        scope.launch(lifecycleDispatcher) {
            current.collectLatest { session ->
                if (session == null) return@collectLatest
                var first = true
                session.state.map { it.isLoaded to it.sessions }.distinctUntilChanged().collect { (loaded, sessions) ->
                    // Owner equality alone cannot reject an old A emission after A → B → A.
                    if (loaded && current.value === session && session.isActive) {
                        onFeed(sessions, first)
                        first = false
                    }
                }
            }
        }
    }

    suspend fun replaceOwner(ownerId: String?) = withContext(lifecycleDispatcher) {
        val previous = current.value
        if (previous?.ownerId == ownerId && previous?.isActive == true) return@withContext
        mutableCurrent.value = null
        previous?.close()
        // Unknown cold-start emptiness must not withdraw a prompt before Room has loaded.
        if (previous != null) onReset()
        if (ownerId != null) {
            val session = createSession(ownerId)
            mutableCurrent.value = session
            scope.launch(lifecycleDispatcher) {
                if (session.initialize().isSuccess && current.value === session && session.isActive) {
                    session.refresh()
                }
            }
        }
    }
}
