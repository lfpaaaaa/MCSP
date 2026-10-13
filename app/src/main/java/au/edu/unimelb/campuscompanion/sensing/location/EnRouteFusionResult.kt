package au.edu.unimelb.campuscompanion.sensing.location

data class EnRouteFusionResult(
    /** null means warm-up or unavailable, rather than an authoritative negative. */
    val verdict: Boolean?,
    val rawVerdict: Boolean?,
    val isMoving: Boolean?,
    val isRotating: Boolean?,
    val distanceTrend: DistanceTrend,
    val headingDifferenceDegrees: Double?,
    val closingMeters: Double
) {
    // Retain the existing log/read names; evidence consumers must use the nullable verdict.
    val rawEnRoute: Boolean get() = rawVerdict == true
    val stableEnRoute: Boolean get() = verdict == true
}
