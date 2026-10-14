package au.edu.unimelb.campuscompanion.sensing.service

import au.edu.unimelb.campuscompanion.context.TravelEngine
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small account gate shared by the visible Activity and an already-running/sticky service. */
class SensingAccountLifecycle(private val travel: TravelEngine) {
    private val lock = Any()
    private val _eligible = MutableStateFlow(false)
    val eligible = _eligible.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    @Volatile private var accountId: String? = null
    private var sessions: List<CourseSession> = emptyList()
    // Engine preference callbacks may hold the engine lock; avoid taking locks in reverse order.
    internal val currentAccountId: String? get() = accountId
    internal val currentSessions: List<CourseSession> get() = synchronized(lock) { sessions }

    /** Local reminder delivery finishes before account replacement/sign-out can clear its scope. */
    internal fun <T> withCurrentAccount(userId: String, action: () -> T): T? = synchronized(lock) {
        if (accountId == userId) action() else null
    }

    fun replaceAccount(
        userId: String?,
        timetable: List<CourseSession>,
        onAccountChanged: () -> Unit = {}
    ) = synchronized(lock) {
        val changed = !_ready.value || accountId != userId
        // Target observers may run immediately on Main; expose the new owner before its generation.
        accountId = userId
        if (userId == null) {
            // Invalidate first: receiver/network callbacks cannot outlive sign-out.
            travel.stopSensing()
            travel.updateSessions(emptyList(), reset = changed)
            sessions = emptyList()
        } else {
            // A replacement keeps an authorized running service, but advances its evidence scope
            // before publishing the new sessions. It never launches a service from the background.
            travel.updateSessions(timetable, reset = changed)
            sessions = timetable.toList()
        }
        // Old prompts are cleared before any guarded delivery can run for the new owner.
        if (changed) onAccountChanged()
        _eligible.value = userId != null
        _ready.value = true
    }

    /** A disposed account's Compose import/refresh cannot overwrite a replacement account. */
    fun updateTimetable(userId: String, sessions: List<CourseSession>): Boolean = synchronized(lock) {
        if (!_ready.value || accountId != userId) return@synchronized false
        travel.updateSessions(sessions)
        this.sessions = sessions.toList()
        true
    }
}
