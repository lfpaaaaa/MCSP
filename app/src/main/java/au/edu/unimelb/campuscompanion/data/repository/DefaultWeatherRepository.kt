package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import au.edu.unimelb.campuscompanion.data.remote.WeatherRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.dataResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * [WeatherRepository] that asks the weather service sparingly: positions are rounded to about
 * 1 km, which is all the weather needs and keeps the exact position on the device; each place is
 * asked at most every [freshFor]; and when the service cannot be reached, the last answer is
 * reused while it is younger than [usableFor].
 */
class DefaultWeatherRepository(
    private val remote: WeatherRemoteDataSource,
    private val clock: () -> Instant = Instant::now,
    private val freshFor: Duration = FRESH_FOR,
    private val usableFor: Duration = USABLE_FOR
) : WeatherRepository {

    private val cache = ConcurrentHashMap<GeoPoint, WeatherSnapshot>()
    private val lock = Mutex()

    override suspend fun currentWeather(location: GeoPoint): Result<WeatherSnapshot> {
        val place = location.coarsened(decimals = 2)
        val now = clock()
        fresh(place, now)?.let { return Result.success(it) }

        return lock.withLock {
            // Another caller may have refreshed this place while we waited.
            fresh(place, now)?.let { return Result.success(it) }
            dataResult { remote.currentWeather(place) }
                .onSuccess { cache[place] = it }
                .recoverCatching { error ->
                    cache[place]?.takeIf { it.observedAt.plus(usableFor).isAfter(now) } ?: throw error
                }
        }
    }

    private fun fresh(place: GeoPoint, now: Instant): WeatherSnapshot? =
        cache[place]?.takeIf { it.observedAt.plus(freshFor).isAfter(now) }

    companion object {
        val FRESH_FOR: Duration = Duration.ofMinutes(30)
        val USABLE_FOR: Duration = Duration.ofHours(3)
    }
}
