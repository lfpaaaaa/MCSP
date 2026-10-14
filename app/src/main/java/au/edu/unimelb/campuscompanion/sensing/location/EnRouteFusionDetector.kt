package au.edu.unimelb.campuscompanion.sensing.location

import au.edu.unimelb.campuscompanion.sensing.activity.UserActivityState

/** Activity/movement fusion. Unavailable observations are distinct from evidence against starting travel. */
class EnRouteFusionDetector {
    companion object {
        private const val MAX_HEADING_DIFFERENCE_DEGREES = 60.0
        private const val CONSERVATIVE_CLOSING_METERS = 50.0
        private const val ENTER_CONFIRMATION_COUNT = 3
        private const val EXIT_CONFIRMATION_COUNT = 3
        private const val MAX_SAMPLE_GAP_NANOS = 30_000_000_000L
    }

    private var positiveCount = 0
    private var negativeCount = 0
    private var lastObservedAtNanos: Long? = null
    private var currentVerdict: Boolean? = null

    fun update(
        isMoving: Boolean?,
        isRotating: Boolean?,
        distanceTrend: DistanceTrend,
        headingDifferenceDegrees: Double?,
        closingMeters: Double,
        observedAtNanos: Long,
        activity: UserActivityState? = null
    ): EnRouteFusionResult {
        val previousObservation = lastObservedAtNanos
        if (observedAtNanos < 0L ||
            (previousObservation != null && observedAtNanos <= previousObservation)
        ) {
            // A replay is not another confirmation and carries no new fusion authority.
            return result(null, null, isMoving, isRotating, distanceTrend, headingDifferenceDegrees, closingMeters, activity)
        }
        if (previousObservation != null && observedAtNanos - previousObservation > MAX_SAMPLE_GAP_NANOS) {
            reset()
        }
        lastObservedAtNanos = observedAtNanos

        val headingMatches = headingDifferenceDegrees != null &&
            headingDifferenceDegrees.isFinite() &&
            headingDifferenceDegrees in 0.0..MAX_HEADING_DIFFERENCE_DEGREES &&
            isRotating == false

        val movement = activity?.isMoving ?: isMoving

        val rawVerdict = when {
            distanceTrend == DistanceTrend.UNKNOWN -> null
            distanceTrend == DistanceTrend.MOVING_AWAY -> false
            distanceTrend == DistanceTrend.STABLE && movement != null -> false
            distanceTrend == DistanceTrend.APPROACHING && movement == true &&
                closingMeters.isFinite() && closingMeters >= 0.0 &&
                (activity == UserActivityState.IN_VEHICLE || headingMatches ||
                    closingMeters >= CONSERVATIVE_CLOSING_METERS) -> true
            // Missing sensors, phone rotation/orientation, or conflicting stationary/progress
            // evidence do not veto the distance-and-movement fallback.
            else -> null
        }

        currentVerdict = when (rawVerdict) {
            true -> {
                positiveCount = (positiveCount + 1).coerceAtMost(ENTER_CONFIRMATION_COUNT)
                negativeCount = 0
                if (positiveCount >= ENTER_CONFIRMATION_COUNT) true else null
            }
            false -> {
                negativeCount = (negativeCount + 1).coerceAtMost(EXIT_CONFIRMATION_COUNT)
                positiveCount = 0
                if (negativeCount >= EXIT_CONFIRMATION_COUNT) false else null
            }
            null -> {
                positiveCount = 0
                negativeCount = 0
                null
            }
        }
        return result(currentVerdict, rawVerdict, isMoving, isRotating, distanceTrend, headingDifferenceDegrees, closingMeters, activity)
    }

    fun isEnRoute(): Boolean = currentVerdict == true

    fun reset() {
        positiveCount = 0
        negativeCount = 0
        lastObservedAtNanos = null
        currentVerdict = null
    }

    private fun result(
        verdict: Boolean?,
        rawVerdict: Boolean?,
        isMoving: Boolean?,
        isRotating: Boolean?,
        distanceTrend: DistanceTrend,
        headingDifferenceDegrees: Double?,
        closingMeters: Double,
        activity: UserActivityState?
    ) = EnRouteFusionResult(
        verdict = verdict,
        rawVerdict = rawVerdict,
        isMoving = isMoving,
        isRotating = isRotating,
        distanceTrend = distanceTrend,
        headingDifferenceDegrees = headingDifferenceDegrees,
        closingMeters = closingMeters,
        activity = activity
    )
}
