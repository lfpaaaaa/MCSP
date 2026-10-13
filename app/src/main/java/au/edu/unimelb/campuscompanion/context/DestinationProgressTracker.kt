package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrend
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** Short-lived, on-device GPS history. Phone orientation alone cannot establish trip direction. */
internal class DestinationProgressTracker {
    private data class Sample(val distance: Double, val accuracy: Float, val time: Instant)
    private val samples = mutableListOf<Sample>()

    fun reset() { samples.clear() }

    fun observe(distanceMeters: Double, accuracyMeters: Float, recordedAt: Instant, now: Instant): DistanceTrend {
        val age = Duration.between(recordedAt, now)
        if (!distanceMeters.isFinite() || distanceMeters < 0 ||
            !accuracyMeters.isFinite() || accuracyMeters !in 0f..50f ||
            age.isNegative || age > MAX_AGE) {
            reset()
            return DistanceTrend.UNKNOWN
        }
        samples.removeAll { Duration.between(it.time, now) > WINDOW }
        if (samples.lastOrNull()?.time?.let { Duration.between(it, recordedAt) > MAX_AGE } == true) reset()
        // Timer ticks and repeated/out-of-order fixes are not evidence of movement or a stop.
        if (samples.lastOrNull()?.time?.let { recordedAt.isAfter(it) } != false) {
            samples.add(Sample(distanceMeters, accuracyMeters, recordedAt))
        }
        if (samples.size < 4 || Duration.between(samples.first().time, samples.last().time) < MIN_SPAN) {
            return DistanceTrend.UNKNOWN
        }
        // Use the shortest recent window containing four fixes and at least 30 seconds.
        // This works with both 10-second pre-class and 60-second battery-saving updates.
        while (samples.size > 4 && Duration.between(samples[1].time, samples.last().time) >= MIN_SPAN) {
            samples.removeAt(0)
        }
        val first = samples.take(2)
        val last = samples.takeLast(2)
        val baseline = first.map { it.distance }.average()
        val change = last.map { it.distance }.average() - baseline
        // Require progress beyond both GPS error radii, with a floor for stationary GPS jitter.
        val threshold = maxOf(20.0, (first.maxOf { it.accuracy } + last.maxOf { it.accuracy }).toDouble())
        return when {
            last.all { it.distance - baseline <= -threshold } -> DistanceTrend.APPROACHING
            last.all { it.distance - baseline >= threshold } -> DistanceTrend.MOVING_AWAY
            abs(change) <= 20.0 -> DistanceTrend.STABLE
            else -> DistanceTrend.UNKNOWN // Possible progress is hidden inside the GPS error radius.
        }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofMinutes(3)
        val MIN_SPAN: Duration = Duration.ofSeconds(30)
        val MAX_AGE: Duration = Duration.ofSeconds(90)
    }
}
