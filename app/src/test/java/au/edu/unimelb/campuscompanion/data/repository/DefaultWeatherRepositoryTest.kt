package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import au.edu.unimelb.campuscompanion.data.remote.WeatherRemoteDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DefaultWeatherRepositoryTest {
    private var now = Instant.parse("2026-09-29T08:00:00Z")
    private val remote = FakeWeatherRemoteDataSource()
    private val repository = DefaultWeatherRepository(remote, clock = { now })

    @Test
    fun theExactPositionNeverLeavesTheDevice() = runBlocking<Unit> {
        repository.currentWeather(GeoPoint(-37.79812, 144.96078))

        assertEquals(listOf(GeoPoint(-37.8, 144.96)), remote.requests)
    }

    @Test
    fun nearbyPlacesShareOneAnswerForHalfAnHour() = runBlocking<Unit> {
        val first = repository.currentWeather(GeoPoint(-37.7981, 144.9608)).getOrThrow()
        now = now.plus(Duration.ofMinutes(29))
        val second = repository.currentWeather(GeoPoint(-37.7975, 144.9612)).getOrThrow()
        assertEquals(first, second)
        assertEquals(1, remote.requests.size)

        now = now.plus(Duration.ofMinutes(2))
        repository.currentWeather(GeoPoint(-37.7981, 144.9608))
        assertEquals(2, remote.requests.size)
    }

    @Test
    fun theLastAnswerIsReusedWhileTheServiceIsUnreachable() = runBlocking<Unit> {
        val first = repository.currentWeather(HOME).getOrThrow()
        remote.failure = DataError.Offline()

        now = now.plus(Duration.ofHours(2))
        assertEquals(first, repository.currentWeather(HOME).getOrThrow())

        now = now.plus(Duration.ofHours(2))
        assertTrue(repository.currentWeather(HOME).exceptionOrNull() is DataError.Offline)
    }

    private inner class FakeWeatherRemoteDataSource : WeatherRemoteDataSource {
        val requests = mutableListOf<GeoPoint>()
        var failure: DataError? = null

        override suspend fun currentWeather(location: GeoPoint): WeatherSnapshot {
            requests += location
            failure?.let { throw it }
            return WeatherSnapshot(
                temperatureCelsius = 16.0,
                precipitationMillimetres = 0.0,
                precipitationProbabilityPercent = 10,
                observedAt = now
            )
        }
    }

    private companion object {
        val HOME = GeoPoint(-37.8100, 144.9630)
    }
}
