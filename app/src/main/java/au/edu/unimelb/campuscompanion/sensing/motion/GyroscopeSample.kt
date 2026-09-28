package au.edu.unimelb.campuscompanion.sensing.motion

data class GyroscopeSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val rotationMagnitude: Double,
    val timestampNanos: Long
)