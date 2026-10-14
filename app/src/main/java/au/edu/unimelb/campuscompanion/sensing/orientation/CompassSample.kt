package au.edu.unimelb.campuscompanion.sensing.orientation

/** Heading is magnetic north, measured from the top edge of the display the sample was mapped to. */
data class CompassSample(
    val headingDegrees: Float,
    val timestampNanos: Long,
    /** Magnetometer accuracy, from SensorManager.SENSOR_STATUS_UNRELIABLE (0) to HIGH (3). */
    val accuracy: Int = 3,
    /** How far the screen is tilted from flat; large tilts make the heading unreliable. */
    val tiltDegrees: Float = 0f,
    /** Surface.ROTATION_* the heading was remapped to. */
    val displayRotation: Int = 0
)
