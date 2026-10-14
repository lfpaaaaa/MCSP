package au.edu.unimelb.campuscompanion.sensing.location

import java.util.ArrayDeque

/** Distance policy is independent of Android; callers supply valid monotonic GPS observation times. */
class DistanceTrendDetector {
    companion object {
        private const val WINDOW_SIZE = 4
        private const val MIN_DISTANCE_CHANGE_METERS = 8.0
        private const val MIN_SEGMENT_CHANGE_METERS = 2.0
        private const val APPROACH_CONFIRMATION_COUNT = 3
        private const val MAX_SAMPLE_GAP_NANOS = 30_000_000_000L
    }

    private val distanceHistory = ArrayDeque<Double>()
    private var lastObservedAtNanos: Long? = null
    private var currentTrend = DistanceTrend.UNKNOWN
    private var approachObservationCount = 0
    private var sustainedClosingMeters = 0.0

    fun update(distanceMeters: Double, observedAtNanos: Long): DistanceTrend {
        if (!distanceMeters.isFinite() || distanceMeters < 0.0 || observedAtNanos < 0L) {
            return DistanceTrend.UNKNOWN
        }
        val previousObservation = lastObservedAtNanos
        if (previousObservation != null && observedAtNanos <= previousObservation) {
            return currentTrend
        }
        if (previousObservation != null && observedAtNanos - previousObservation > MAX_SAMPLE_GAP_NANOS) {
            reset()
        }
        lastObservedAtNanos = observedAtNanos
        distanceHistory.addLast(distanceMeters)
        if (distanceHistory.size > WINDOW_SIZE) distanceHistory.removeFirst()
        if (distanceHistory.size < WINDOW_SIZE) return DistanceTrend.UNKNOWN

        val distances = distanceHistory.toList()
        val closingChanges = distances.zipWithNext { before, after -> before - after }
        val netClosing = distances.first() - distances.last()
        val previousTrend = currentTrend

        // Require agreement in at least two segments. A single GPS jump cannot establish progress,
        // and a substantial rebound breaks a previously continuous approach span.
        currentTrend = when {
            netClosing >= MIN_DISTANCE_CHANGE_METERS &&
                closingChanges.count { it >= MIN_SEGMENT_CHANGE_METERS } >= 2 &&
                closingChanges.none { it <= -MIN_DISTANCE_CHANGE_METERS } -> DistanceTrend.APPROACHING
            netClosing <= -MIN_DISTANCE_CHANGE_METERS &&
                closingChanges.count { it <= -MIN_SEGMENT_CHANGE_METERS } >= 2 &&
                closingChanges.none { it >= MIN_DISTANCE_CHANGE_METERS } -> DistanceTrend.MOVING_AWAY
            else -> DistanceTrend.STABLE
        }
        approachObservationCount = if (currentTrend == DistanceTrend.APPROACHING) {
            (approachObservationCount + 1).coerceAtMost(APPROACH_CONFIRMATION_COUNT)
        } else {
            0
        }

        sustainedClosingMeters = if (currentTrend == DistanceTrend.APPROACHING) {
            val medianClosing = closingChanges.sorted()[1]
            if (previousTrend == DistanceTrend.APPROACHING) {
                // Count each new segment once, capping an isolated jump by the surrounding pace.
                (sustainedClosingMeters + closingChanges.last().coerceAtMost(medianClosing))
                    .coerceAtLeast(0.0)
            } else {
                // Do not credit the largest initial jump toward the conservative 50 m fallback.
                minOf(netClosing, closingChanges.filter { it > 0.0 }.sorted().take(2).sum())
            }
        } else {
            0.0
        }
        return currentTrend
    }

    fun getCurrentTrend(): DistanceTrend = currentTrend

    /** Three distinct approach windows, for the conservative movement-and-progress fallback. */
    fun sustainedApproach(): Boolean = currentTrend == DistanceTrend.APPROACHING &&
        approachObservationCount >= APPROACH_CONFIRMATION_COUNT

    /** Conservative progress accumulated only while the four-sample approach remains sustained. */
    fun closingMeters(): Double = sustainedClosingMeters

    fun reset() {
        distanceHistory.clear()
        lastObservedAtNanos = null
        currentTrend = DistanceTrend.UNKNOWN
        approachObservationCount = 0
        sustainedClosingMeters = 0.0
    }
}
