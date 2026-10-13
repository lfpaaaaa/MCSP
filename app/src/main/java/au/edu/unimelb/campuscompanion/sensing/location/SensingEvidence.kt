package au.edu.unimelb.campuscompanion.sensing.location

import au.edu.unimelb.campuscompanion.data.building.BuildingLocation

/** An in-memory sensing period. Generation changes even for A → B → A. */
data class SensingTarget(
    val generation: Long,
    val activatedAtNanos: Long,
    val active: Boolean,
    val sessionId: String?,
    val building: BuildingLocation?
)

data class MovementObservation(val isMoving: Boolean, val observedAtNanos: Long)

/** A verdict belongs to this exact location observation and target, never a separate update. */
data class SensingEvidence(
    val generation: Long,
    val location: LocationSample?,
    val movement: MovementObservation? = null,
    val enRoute: Boolean? = null,
    val progressConfirmed: Boolean = false
)

/** Shared Android adapter / engine checks, measured against elapsed realtime, not receipt time. */
object EvidenceFreshness {
    const val LOCATION_MAX_AGE_NANOS = 30_000_000_000L
    const val SENSOR_MAX_AGE_NANOS = 5_000_000_000L

    fun isFresh(observedAt: Long, now: Long, maxAge: Long): Boolean =
        observedAt > 0 && observedAt <= now && now - observedAt <= maxAge

    fun accepts(location: LocationSample, target: SensingTarget, now: Long, previous: Long = 0): Boolean =
        target.active && location.elapsedRealtimeNanos >= target.activatedAtNanos &&
            location.elapsedRealtimeNanos > previous &&
            isFresh(location.elapsedRealtimeNanos, now, LOCATION_MAX_AGE_NANOS) &&
            location.accuracyMeters.isFinite() && location.accuracyMeters in 0f..50f &&
            location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0
}
