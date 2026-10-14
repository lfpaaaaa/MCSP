package au.edu.unimelb.campuscompanion.sensing.motion

/** The measurement time of the latest complete accelerometer classification window. */
data class MotionClassification(
    val isMoving: Boolean,
    val timestampNanos: Long
)
