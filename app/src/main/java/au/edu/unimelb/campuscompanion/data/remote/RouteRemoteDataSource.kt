package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The answer of the routing service for one trip. */
sealed interface RouteOutcome {
    data class Found(val durationSeconds: Long, val distanceMeters: Int) : RouteOutcome

    /** A fair-use limit is reached; routing can resume after [retryAfterSeconds]. */
    data class QuotaExceeded(val retryAfterSeconds: Long) : RouteOutcome
}

/**
 * Travel times from the route-eta Edge Function. Implementations throw
 * [au.edu.unimelb.campuscompanion.data.DataError] for failures other than a reached limit.
 */
interface RouteRemoteDataSource {
    suspend fun route(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): RouteOutcome
}

/** A position in a request to the route-eta function, already rounded on the device. */
@Serializable
data class RoutePoint(val lat: Double, val lng: Double)

/** The JSON body sent to the route-eta function; [mode] is the travel mode's wire name. */
@Serializable
data class RouteRequestBody(val origin: RoutePoint, val destination: RoutePoint, val mode: String)

/** The JSON body the route-eta function returns; [cached] is true when it answered from its cache. */
@Serializable
data class RouteResponseBody(
    @SerialName("duration_seconds") val durationSeconds: Long,
    @SerialName("distance_meters") val distanceMeters: Int,
    val cached: Boolean = false
)

@Serializable
private data class RouteErrorBody(
    val error: String? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long? = null
)

/** The name of a travel mode in route-eta requests. */
internal val TravelMode.wireName: String
    get() = when (this) {
        TravelMode.Walking -> "WALK"
        TravelMode.PublicTransport -> "TRANSIT"
        TravelMode.Driving -> "DRIVE"
    }

internal fun GeoPoint.toRoutePoint() = RoutePoint(lat = latitude, lng = longitude)

/**
 * The wait in seconds from a 429 answer of the route-eta function, or null when the answer is not
 * about the routing limits.
 */
internal fun quotaRetryAfterSeconds(body: String): Long? {
    val answer = runCatching { remoteJson.decodeFromString(RouteErrorBody.serializer(), body) }.getOrNull()
        ?: return null
    if (answer.error != "quota_exceeded") return null
    return (answer.retryAfterSeconds ?: DEFAULT_QUOTA_PAUSE_SECONDS).coerceAtLeast(1)
}

private const val DEFAULT_QUOTA_PAUSE_SECONDS = 3_600L
