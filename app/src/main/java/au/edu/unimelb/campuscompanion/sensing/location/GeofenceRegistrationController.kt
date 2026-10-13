package au.edu.unimelb.campuscompanion.sensing.location

import java.util.UUID

/** A delivered entry is consumed by TravelEngine, rather than retained as an inside flag. */
data class GeofenceEntryEvent(
    val requestId: String,
    val generation: Long,
    val buildingCode: String,
    val receivedAtNanos: Long,
    val triggerLocation: LocationSample? = null
)

data class ArrivalGeofenceRegistration(
    val requestId: String,
    val generation: Long,
    val activatedAtNanos: Long,
    val buildingCode: String,
    val latitude: Double,
    val longitude: Double
)

sealed interface GeofenceRegistrationOperation {
    data object RemoveOrphans : GeofenceRegistrationOperation
    data class Add(val registration: ArrivalGeofenceRegistration) : GeofenceRegistrationOperation
    data class Remove(val registration: ArrivalGeofenceRegistration) : GeofenceRegistrationOperation
}

/**
 * One operation at a time: a superseded add must finish and be removed before a replacement add.
 * This reducer contains no Android callbacks, so late-completion and A → B → A races are testable.
 */
class GeofenceRegistrationController(
    private val createRequestId: () -> String = { "arrival.${UUID.randomUUID()}" }
) {
    var desired: ArrivalGeofenceRegistration? = null
        private set
    var registered: ArrivalGeofenceRegistration? = null
        private set
    var inFlight: GeofenceRegistrationOperation? = null
        private set

    private var target: SensingTarget? = null
    private var needsOrphanRemoval = true
    private var orphanRemovalFailed = false
    private val obsolete = linkedMapOf<String, ArrivalGeofenceRegistration>()
    private val failedRemovals = mutableSetOf<String>()
    private val failedAdds = mutableSetOf<String>()

    val hasPendingCleanup: Boolean
        get() = needsOrphanRemoval || obsolete.isNotEmpty() ||
            inFlight is GeofenceRegistrationOperation.Remove ||
            inFlight == GeofenceRegistrationOperation.RemoveOrphans

    val hasFailedCleanup: Boolean
        get() = orphanRemovalFailed || failedRemovals.isNotEmpty()

    fun setTarget(next: SensingTarget?) {
        val usable = next?.takeIf { it.active && it.building != null }
        if (target == usable) return
        target = usable
        registered?.let { obsolete[it.requestId] = it }
        registered = null
        desired = usable?.let {
            val building = requireNotNull(it.building)
            ArrivalGeofenceRegistration(
                createRequestId(), it.generation, it.activatedAtNanos,
                building.locCode, building.location.latitude, building.location.longitude
            )
        }
    }

    /** Retry failed cleanup on a later lifecycle/permission check, never in a tight callback loop. */
    fun retryCleanup() {
        orphanRemovalFailed = false
        failedRemovals.clear()
    }

    fun nextOperation(): GeofenceRegistrationOperation? {
        if (inFlight != null) return null
        val next = when {
            needsOrphanRemoval && !orphanRemovalFailed -> GeofenceRegistrationOperation.RemoveOrphans
            needsOrphanRemoval -> null
            obsolete.values.any { it.requestId !in failedRemovals } ->
                GeofenceRegistrationOperation.Remove(obsolete.values.first { it.requestId !in failedRemovals })
            obsolete.isNotEmpty() -> null
            desired != null && registered != desired && desired!!.requestId !in failedAdds ->
                GeofenceRegistrationOperation.Add(requireNotNull(desired))
            else -> null
        }
        inFlight = next
        return next
    }

    fun complete(operation: GeofenceRegistrationOperation, success: Boolean) {
        check(inFlight == operation) { "Registration completions must match the in-flight operation" }
        inFlight = null
        when (operation) {
            GeofenceRegistrationOperation.RemoveOrphans -> {
                needsOrphanRemoval = !success
                orphanRemovalFailed = !success
            }
            is GeofenceRegistrationOperation.Add -> {
                val registration = operation.registration
                if (success && desired == registration) {
                    registered = registration
                } else if (success) {
                    obsolete[registration.requestId] = registration
                } else {
                    failedAdds += registration.requestId
                }
            }
            is GeofenceRegistrationOperation.Remove -> {
                val id = operation.registration.requestId
                if (success) {
                    obsolete.remove(id)
                    failedRemovals.remove(id)
                } else {
                    failedRemovals += id
                }
            }
        }
    }

    /** Pending, failed, obsolete and pre-process-recreation request IDs cannot forward entry. */
    fun currentRegistration(requestId: String): ArrivalGeofenceRegistration? =
        registered?.takeIf { it == desired && it.requestId == requestId }
}
