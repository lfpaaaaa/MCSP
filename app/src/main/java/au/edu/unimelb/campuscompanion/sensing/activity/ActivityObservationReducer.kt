package au.edu.unimelb.campuscompanion.sensing.activity

enum class ActivityTransitionKind { EXIT, ENTER }

data class MeasuredActivityTransition(
    val state: UserActivityState,
    val transition: ActivityTransitionKind,
    val observedAtNanos: Long
)

/** Pure observed-time reduction; callback receipt must never refresh activity evidence. */
class ActivityObservationReducer(
    private val maximumEventAgeNanos: Long = 60_000_000_000L,
    private val minimumSnapshotConfidence: Int = 50
) {
    init {
        require(maximumEventAgeNanos >= 0L)
        require(minimumSnapshotConfidence in 0..100)
    }

    var observation: ActivityObservation? = null
        private set
    private var activationNanos: Long? = null
    private var lastEventNanos = 0L
    private val eventsAtLastTime = mutableSetOf<Pair<UserActivityState, ActivityTransitionKind>>()
    private var hasAcceptedTransition = false

    /** Repeated starts preserve state; a new activation boundary resets it. */
    fun start(activatedAtNanos: Long) {
        require(activatedAtNanos >= 0L)
        if (activationNanos == activatedAtNanos) return
        stop()
        activationNanos = activatedAtNanos
    }

    fun stop() {
        activationNanos = null
        lastEventNanos = 0L
        eventsAtLastTime.clear()
        hasAcceptedTransition = false
        observation = null
    }

    /** Equal-time EXITs precede ENTERs, so a handover ends in the newly entered activity. */
    fun acceptBatch(
        events: List<MeasuredActivityTransition>,
        nowNanos: Long
    ): List<MeasuredActivityTransition> = events
        .sortedWith(compareBy<MeasuredActivityTransition> { it.observedAtNanos }
            .thenBy { it.transition.ordinal }
            .thenBy { it.state.ordinal })
        .filter { acceptTransition(it, nowNanos) }

    fun acceptTransition(event: MeasuredActivityTransition, nowNanos: Long): Boolean {
        if (event.state == UserActivityState.UNKNOWN ||
            !hasValidTime(event.observedAtNanos, nowNanos) ||
            event.observedAtNanos < lastEventNanos
        ) return false

        if (event.observedAtNanos != lastEventNanos) eventsAtLastTime.clear()
        if (!eventsAtLastTime.add(event.state to event.transition)) return false
        lastEventNanos = event.observedAtNanos
        when (event.transition) {
            ActivityTransitionKind.ENTER -> {
                hasAcceptedTransition = true
                observation = ActivityObservation(event.state, event.observedAtNanos)
            }
            ActivityTransitionKind.EXIT -> if (matchesCurrentState(event.state)) {
                hasAcceptedTransition = true
                observation = ActivityObservation(UserActivityState.UNKNOWN, event.observedAtNanos)
            }
        }
        return true
    }

    /** Sampling only seeds initial state; it cannot supersede the authoritative transition stream. */
    fun acceptSnapshot(
        state: UserActivityState,
        confidence: Int,
        observedAtNanos: Long,
        nowNanos: Long
    ): Boolean {
        if (hasAcceptedTransition || state == UserActivityState.UNKNOWN ||
            confidence !in minimumSnapshotConfidence..100 ||
            observedAtNanos <= lastEventNanos || !hasValidTime(observedAtNanos, nowNanos)
        ) return false

        lastEventNanos = observedAtNanos
        eventsAtLastTime.clear()
        observation = ActivityObservation(state, observedAtNanos)
        return true
    }

    private fun matchesCurrentState(exited: UserActivityState): Boolean {
        val current = observation?.state ?: return false
        return current == exited ||
            (current == UserActivityState.ON_FOOT &&
                exited in setOf(UserActivityState.WALKING, UserActivityState.RUNNING))
    }

    private fun hasValidTime(observedAtNanos: Long, nowNanos: Long): Boolean {
        val activation = activationNanos ?: return false
        return observedAtNanos > activation && observedAtNanos > 0L &&
            observedAtNanos <= nowNanos && nowNanos - observedAtNanos <= maximumEventAgeNanos
    }
}
