package au.edu.unimelb.campuscompanion.context

/**
 * Where the student is in their day, as far as the next class is concerned. [ContextEngine]
 * decides the state; the Home card (and later the notifications) are driven by it.
 */
enum class ContextState {
    /** No class left today. The snapshot may still carry the next class on a later day. */
    NO_UPCOMING_CLASS,

    /** An hour or more before it is time to leave, after an earlier class today. */
    LONG_BREAK,

    /** A class later today; not time to leave yet. */
    UPCOMING,

    /** The leave-by time is a few minutes away, or has passed but the student can still be on time. */
    LEAVE_SOON,

    /** Heading towards the class's building. Departure reminders are suppressed. */
    EN_ROUTE,

    /** Leaving now would arrive after the start, or the class has started and the student is elsewhere. */
    RUNNING_LATE,

    /** At the class's building before it starts. */
    ARRIVED,

    /** The class is in progress. */
    IN_CLASS,

    /** A class finished a few minutes ago and nothing about the next class needs attention yet. */
    POST_CLASS
}
