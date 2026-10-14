package au.edu.unimelb.campuscompanion.sensing.location

import java.util.ArrayDeque
import kotlin.math.abs

/** Distance policy is independent of Android; callers supply valid monotonic GPS observation times. */
class DistanceTrendDetector {
    companion object {
        private const val WINDOW_SIZE = 4
        private const val MIN_DISTANCE_CHANGE_METERS = 8.0
        private const val MIN_SEGMENT_CHANGE_METERS = 2.0
        private const val APPROACH_CONFIRMATION_COUNT = 3
        private const val MAX_SAMPLE_GAP_NANOS = 30_000_000_000L
        private const val NO_PROGRESS_MIN_SPAN_NANOS = 30_000_000_000L
    }

    private data class Observation(val distance: Double, val timeNanos: Long, val accuracy: Float)
    private val distanceHistory = ArrayDeque<Observation>()
    private var lastObservedAtNanos: Long? = null
    private var currentTrend = DistanceTrend.UNKNOWN
    private var approachObservationCount = 0
    private var sustainedClosingMeters = 0.0
    private var noProgressSinceNanos: Long? = null

    fun update(distanceMeters: Double, observedAtNanos: Long, accuracyMeters: Float = 0f): DistanceTrend {
        if (!distanceMeters.isFinite() || distanceMeters < 0.0 || observedAtNanos < 0L ||
            !accuracyMeters.isFinite() || accuracyMeters !in 0f..50f) {
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
        distanceHistory.addLast(Observation(distanceMeters, observedAtNanos, accuracyMeters))
        if (distanceHistory.size > WINDOW_SIZE) distanceHistory.removeFirst()
        if (distanceHistory.size < WINDOW_SIZE) return DistanceTrend.UNKNOWN

        val observations = distanceHistory.toList()
        val distances = observations.map { it.distance }
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
        // Keep the feature branch's fusion verdict. Urgency needs stronger evidence: its STABLE
        // fallback can include a jump or a rebound, which must not be described as standing still.
        val compactStop = currentTrend == DistanceTrend.STABLE &&
            distances.max() - distances.min() <= 20.0 &&
            !(abs(netClosing) > 5.0 && (closingChanges.all { it > 0 } || closingChanges.all { it < 0 }))
        val clearRetreat = currentTrend == DistanceTrend.MOVING_AWAY &&
            -netClosing >= maxOf(20.0, (observations.first().accuracy + observations.last().accuracy).toDouble())
        noProgressSinceNanos = if (compactStop || clearRetreat) {
            noProgressSinceNanos ?: observations.first().timeNanos
        } else null
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

    /** A fresh trend may prompt only after continuous no-progress evidence spans 30 seconds. */
    fun noProgressConfirmed(): Boolean = noProgressSinceNanos?.let { since ->
        (lastObservedAtNanos ?: since) - since >= NO_PROGRESS_MIN_SPAN_NANOS
    } == true

    fun reset() {
        distanceHistory.clear()
        lastObservedAtNanos = null
        currentTrend = DistanceTrend.UNKNOWN
        approachObservationCount = 0
        sustainedClosingMeters = 0.0
        noProgressSinceNanos = null
    }
}
