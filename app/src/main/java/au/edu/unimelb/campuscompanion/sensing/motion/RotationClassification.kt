package au.edu.unimelb.campuscompanion.sensing.motion

/** The measurement time of the latest complete gyroscope classification window. */
data class RotationClassification(
    val isRotating: Boolean,
    val timestampNanos: Long
)
