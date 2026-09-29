package au.edu.unimelb.campuscompanion.sensing.location

data class EnRouteFusionResult(
    val rawEnRoute: Boolean,
    val stableEnRoute: Boolean,
    val isMoving: Boolean,
    val isRotating: Boolean,
    val distanceTrend: DistanceTrend,
    val headingDifferenceDegrees: Double?
)