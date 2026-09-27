package au.edu.unimelb.campuscompanion.sensing.motion

data class MotionSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val magnitude: Double,
    val timestampNanos: Long
)