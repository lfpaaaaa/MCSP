package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import kotlin.math.cos
import kotlin.math.sqrt

/** Only the currently selected trip is tracked; activity recognition cannot identify a vehicle. */
data class TransitLeg(
    val line: String,
    val headsign: String,
    val departureName: String,
    val departure: GeoPoint,
    val arrivalName: String,
    val arrival: GeoPoint,
    val path: List<GeoPoint>
)

enum class BoardingState { Waiting, Riding, Alight }

data class StopProgress(
    val state: BoardingState = BoardingState.Waiting,
    val remainingMeters: Int? = null,
    val offRoute: Boolean = false,
    val alert: Boolean = false
)

/**
 * Arms after seeing the boarding stop followed by sustained vehicle-like movement along the leg.
 * A user who starts tracking after boarding can explicitly confirm the line. Never infers a bus
 * number from GPS alone. Uses distance along the route, not a circle around a stop on a loop.
 */
class TransitJourney(private val leg: TransitLeg) {
    private var previous: LocationSample? = null
    private var previousRemaining: Double? = null
    private var sawBoardingStop = false
    private var movingSamples = 0
    private var approachingSamples = 0
    private var alerted = false
    private var boarding = BoardingState.Waiting

    fun confirmBoarding() { boarding = BoardingState.Riding }

    fun update(sample: LocationSample, nowMillis: Long, inVehicle: Boolean): StopProgress {
        if (!sample.accuracyMeters.isFinite() || sample.accuracyMeters !in 0f..50f ||
            nowMillis - sample.timestampMillis !in 0L..30_000L ||
            previous?.let { sample.timestampMillis <= it.timestampMillis } == true) {
            movingSamples = 0
            approachingSamples = 0
            previousRemaining = null
            return StopProgress(boarding)
        }
        val point = GeoPoint(sample.latitude, sample.longitude)
        val last = previous
        val seconds = last?.let { (sample.timestampMillis - it.timestampMillis) / 1000.0 }
        val speed = if (last != null && seconds != null && seconds in 1.0..30.0) {
            GeoMath.distanceMeters(GeoPoint(last.latitude, last.longitude), point) / seconds
        } else 0.0
        previous = sample
        val match = matchPath(point, leg.path)
        if (match == null || match.distanceFromPath > 100 || speed > 70) {
            movingSamples = 0
            approachingSamples = 0
            previousRemaining = null
            return StopProgress(boarding, offRoute = true)
        }
        val departureDistance = GeoMath.distanceMeters(point, leg.departure)
        if (departureDistance <= 150) sawBoardingStop = true
        if (sawBoardingStop && speed >= 3 && (inVehicle || speed >= 5)) movingSamples++
        else movingSamples = 0
        if (boarding == BoardingState.Waiting && movingSamples >= 3 && departureDistance >= 120) {
            boarding = BoardingState.Riding
        }
        val oldRemaining = previousRemaining
        // At crossings/loops the nearest segment can jump ahead; require coherent progress.
        if (oldRemaining != null && kotlin.math.abs(oldRemaining - match.remaining) >
            speed * (seconds ?: 0.0) + 150) {
            approachingSamples = 0
            previousRemaining = null
            return StopProgress(boarding, offRoute = true)
        }
        approachingSamples = if (oldRemaining != null && match.remaining < oldRemaining - 5) approachingSamples + 1 else 0
        previousRemaining = match.remaining
        val threshold = (speed * 75).coerceIn(250.0, 700.0)
        val shouldAlert = boarding == BoardingState.Riding && !alerted && approachingSamples >= 2 &&
            match.remaining <= threshold
        if (shouldAlert) {
            alerted = true
            boarding = BoardingState.Alight
        }
        return StopProgress(boarding, match.remaining.toInt(), alert = shouldAlert)
    }
}

internal data class PathMatch(val distanceFromPath: Double, val remaining: Double)

/** Nearest segment plus its untravelled distance. Paths use WGS84; trips are local campus commutes. */
internal fun matchPath(point: GeoPoint, path: List<GeoPoint>): PathMatch? {
    if (path.size < 2) return null
    var bestDistance = Double.POSITIVE_INFINITY
    var bestRemaining = 0.0
    var remaining = 0.0
    for (index in path.lastIndex downTo 1) {
        val a = path[index - 1]
        val b = path[index]
        val scaleX = 111_320 * cos(Math.toRadians(point.latitude))
        val ax = (a.longitude - point.longitude) * scaleX
        val ay = (a.latitude - point.latitude) * 111_320
        val bx = (b.longitude - point.longitude) * scaleX
        val by = (b.latitude - point.latitude) * 111_320
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val fraction = if (lengthSquared > 0) (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
        val distance = sqrt((ax + dx * fraction) * (ax + dx * fraction) + (ay + dy * fraction) * (ay + dy * fraction))
        val length = GeoMath.distanceMeters(a, b)
        if (distance < bestDistance) {
            bestDistance = distance
            bestRemaining = remaining + length * (1 - fraction)
        }
        remaining += length
    }
    return PathMatch(bestDistance, bestRemaining)
}

/** Google encoded polylines use five decimal places by default. Invalid/truncated data is rejected. */
fun decodeRoutePolyline(encoded: String): List<GeoPoint> {
    var index = 0
    var lat = 0
    var lng = 0
    fun coordinate(): Int {
        var value = 0
        var shift = 0
        var chunk: Int
        do {
            require(index < encoded.length && shift <= 30) { "Invalid route geometry" }
            chunk = encoded[index++].code - 63
            require(chunk in 0..63) { "Invalid route geometry" }
            value = value or ((chunk and 31) shl shift)
            shift += 5
        } while (chunk >= 32)
        return if (value and 1 != 0) (value shr 1).inv() else value shr 1
    }
    return buildList {
        while (index < encoded.length) {
            lat += coordinate()
            lng += coordinate()
            add(GeoPoint(lat / 100000.0, lng / 100000.0))
        }
    }
}
