package au.edu.unimelb.campuscompanion.sensing.activity

enum class UserActivityState {
    STILL,
    WALKING,
    RUNNING,
    ON_FOOT,
    IN_VEHICLE,
    UNKNOWN;

    val isMoving: Boolean?
        get() = when (this) {
            WALKING, RUNNING, ON_FOOT, IN_VEHICLE -> true
            STILL -> false
            UNKNOWN -> null
        }
}

/** A measured state, retained until a relevant transition or the sensing period ends. */
data class ActivityObservation(
    val state: UserActivityState,
    val observedAtNanos: Long
)
