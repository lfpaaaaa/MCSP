package au.edu.unimelb.campuscompanion.sensing.orientation

/** Heading is magnetic north, measured from the current display's top edge. */
data class CompassSample(
    val headingDegrees: Float,
    val timestampNanos: Long,
    val accuracy: Int = 3,
    val tiltDegrees: Float = 0f,
    val displayRotation: Int = 0
)
