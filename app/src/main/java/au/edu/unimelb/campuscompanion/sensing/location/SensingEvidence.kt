package au.edu.unimelb.campuscompanion.sensing.location

import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.sensing.activity.ActivityObservation

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
    val progressConfirmed: Boolean = false,
    /** Accepted activity state from the uninterrupted subscription in this sensing period. */
    val activity: ActivityObservation? = null
)

/** Closed diagnostic vocabulary: geofence rejection logs never contain positions. */
enum class GeofenceRejectionReason(val logValue: String) {
    NO_ACTIVE_TARGET("no_active_target"),
    WRONG_GENERATION("wrong_generation"),
    WRONG_BUILDING("wrong_building"),
    INVALID_REQUEST_ID("invalid_request_id"),
    RECEIPT_BEFORE_ACTIVATION("receipt_before_activation"),
    INVALID_RECEIPT_TIME("invalid_receipt_time"),
    RECEIPT_EXPIRED("receipt_expired"),
    DUPLICATE("duplicate"),
    PRE_ACTIVATION_REENTRY("pre_activation_reentry"),
    MISSING_TRIGGER_TIME("missing_trigger_time"),
    TRIGGER_AFTER_RECEIPT("trigger_after_receipt"),
    TRIGGER_EXPIRED("trigger_expired"),
    INVALID_POSITION("invalid_position"),
    INVALID_ACCURACY("invalid_accuracy"),
    OUTSIDE_EXIT_RADIUS("outside_exit_radius"),
    NEWER_AWAY_FIX("newer_away_fix"),
    NO_TRACKED_SESSION("no_tracked_session")
}

/** Ages use elapsed realtime so an already-inside delivery can be calibrated on a phone. */
data class GeofenceEntryResult(
    val rejectionReason: GeofenceRejectionReason?,
    val triggerAgeMillis: Long?,
    val triggerAgeAtReceiptMillis: Long?,
    val triggerAccuracyMeters: Float?,
    val triggerBeforeActivation: Boolean
) {
    val accepted: Boolean get() = rejectionReason == null
}

/** Shared Android adapter / engine checks, measured against elapsed realtime, not receipt time. */
object EvidenceFreshness {
    const val LOCATION_MAX_AGE_NANOS = 30_000_000_000L
    const val SENSOR_MAX_AGE_NANOS = 5_000_000_000L

    fun isFresh(observedAt: Long, now: Long, maxAge: Long): Boolean =
        observedAt > 0 && observedAt <= now && now - observedAt <= maxAge

    fun accepts(location: LocationSample, target: SensingTarget, now: Long, previous: Long = 0): Boolean =
        target.active && rejectionReason(location, now, previous, target.activatedAtNanos) == null

    /** Bounded diagnostics shared by the Android adapter and engine, with no raw coordinates. */
    fun rejectionReason(location: LocationSample, now: Long, previous: Long = 0, activatedAt: Long = 0): String? = when {
        location.elapsedRealtimeNanos <= 0 -> "missing_time"
        location.elapsedRealtimeNanos > now -> "future"
        location.elapsedRealtimeNanos < activatedAt -> "before_activation"
        location.elapsedRealtimeNanos <= previous -> "out_of_order"
        now - location.elapsedRealtimeNanos > LOCATION_MAX_AGE_NANOS -> "expired"
        !location.accuracyMeters.isFinite() || location.accuracyMeters < 0f -> "invalid_accuracy"
        location.accuracyMeters > 50f -> "inaccurate"
        !location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0 -> "invalid_position"
        else -> null
    }
}
