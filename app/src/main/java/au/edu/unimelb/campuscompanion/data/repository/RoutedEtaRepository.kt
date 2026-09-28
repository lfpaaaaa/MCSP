package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.geo.StraightLineEstimator
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.remote.RouteOutcome
import au.edu.unimelb.campuscompanion.data.remote.RouteRemoteDataSource
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * [EtaRepository] backed by the routing service, built to keep the load on the free, volunteer-run
 * routing services small:
 *
 * - the origin is rounded to about 110 m on the device, so the exact position never leaves it;
 * - answers are cached, walking and driving times for a day and public transport for 10 minutes;
 * - each destination is requested at most once every three minutes, even while the user moves;
 * - when the server reports that a limit is reached, routing in that travel mode pauses until it
 *   resets.
 *
 * Whenever no routed time is available, the offline straight-line estimate is returned instead.
 */
class RoutedEtaRepository(
    private val remote: RouteRemoteDataSource,
    private val clock: () -> Instant = Instant::now,
    private val minRequestInterval: Duration = MIN_REQUEST_INTERVAL
) : EtaRepository {

    private val cache = ConcurrentHashMap<RouteKey, CachedEstimate>()
    private val latestByDestination = ConcurrentHashMap<DestinationKey, TravelEstimate>()
    private val lastRequestAt = ConcurrentHashMap<DestinationKey, Instant>()
    private val pausedUntil = ConcurrentHashMap<TravelMode, Instant>()

    override suspend fun estimate(
        origin: GeoPoint,
        destination: GeoPoint,
        mode: TravelMode
    ): Result<TravelEstimate> {
        val now = clock()
        val from = origin.coarsened()
        val to = destination.coarsened()
        val routeKey = RouteKey(from, to, mode)
        val destinationKey = DestinationKey(to, mode)

        cache[routeKey]?.takeIf { now.isBefore(it.expiresAt) }?.let { return Result.success(it.estimate) }

        val lastRequest = lastRequestAt[destinationKey]
        val throttled = lastRequest != null && now.isBefore(lastRequest.plus(minRequestInterval))
        val paused = pausedUntil[mode]?.let { now.isBefore(it) } == true
        if (throttled || paused) {
            return Result.success(fallback(destinationKey, origin, destination, now))
        }

        lastRequestAt[destinationKey] = now
        val outcome = try {
            remote.route(from, to, mode)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return Result.success(fallback(destinationKey, origin, destination, now))
        }
        return when (outcome) {
            is RouteOutcome.Found -> {
                val estimate = TravelEstimate(
                    mode = mode,
                    durationSeconds = outcome.durationSeconds.coerceAtLeast(0),
                    distanceMeters = outcome.distanceMeters.coerceAtLeast(0),
                    source = EstimateSource.Routing,
                    computedAt = now
                )
                cache[routeKey] = CachedEstimate(estimate, now.plus(cacheLifetime(mode)))
                latestByDestination[destinationKey] = estimate
                Result.success(estimate)
            }
            is RouteOutcome.QuotaExceeded -> {
                pausedUntil[mode] = now.plusSeconds(outcome.retryAfterSeconds)
                Result.success(fallback(destinationKey, origin, destination, now))
            }
        }
    }

    /** A recent routed time for the destination, or else the offline estimate from the exact origin. */
    private fun fallback(key: DestinationKey, origin: GeoPoint, destination: GeoPoint, now: Instant): TravelEstimate =
        latestByDestination[key]?.takeIf { now.isBefore(it.computedAt.plus(RECENT_ESTIMATE_AGE)) }
            ?: StraightLineEstimator.estimate(origin, destination, now)

    private fun cacheLifetime(mode: TravelMode): Duration = when (mode) {
        TravelMode.PublicTransport -> TRANSIT_CACHE_LIFETIME
        TravelMode.Walking, TravelMode.Driving -> FIXED_ROUTE_CACHE_LIFETIME
    }

    private data class RouteKey(val from: GeoPoint, val to: GeoPoint, val mode: TravelMode)

    private data class DestinationKey(val to: GeoPoint, val mode: TravelMode)

    private data class CachedEstimate(val estimate: TravelEstimate, val expiresAt: Instant)

    companion object {
        val MIN_REQUEST_INTERVAL: Duration = Duration.ofMinutes(3)
        private val RECENT_ESTIMATE_AGE: Duration = Duration.ofMinutes(10)
        private val TRANSIT_CACHE_LIFETIME: Duration = Duration.ofMinutes(10)
        private val FIXED_ROUTE_CACHE_LIFETIME: Duration = Duration.ofDays(1)
    }
}

/** Rounds to three decimal places, about 110 m, before a position is sent anywhere. */
internal fun GeoPoint.coarsened(): GeoPoint =
    GeoPoint(roundToThreeDecimals(latitude), roundToThreeDecimals(longitude))

private fun roundToThreeDecimals(value: Double): Double = (value * 1_000).roundToLong() / 1_000.0
