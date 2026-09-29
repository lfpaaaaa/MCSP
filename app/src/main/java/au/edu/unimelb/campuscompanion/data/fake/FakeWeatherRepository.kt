package au.edu.unimelb.campuscompanion.data.fake

import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import au.edu.unimelb.campuscompanion.data.repository.WeatherRepository
import kotlinx.coroutines.delay
import java.time.Instant

/** [WeatherRepository] with adjustable rainfall, for previewing weather-aware reminders. */
class FakeWeatherRepository(
    private val latencyMillis: Long = FakeData.DEFAULT_LATENCY_MILLIS,
    private val clock: () -> Instant = Instant::now
) : WeatherRepository {

    /** Rainfall reported by the next request, in millimetres per hour. */
    @Volatile
    var precipitationMillimetres: Double = 0.0

    override suspend fun currentWeather(location: GeoPoint): Result<WeatherSnapshot> {
        delay(latencyMillis)
        val raining = precipitationMillimetres > 0.0
        return Result.success(
            WeatherSnapshot(
                temperatureCelsius = 16.0,
                precipitationMillimetres = precipitationMillimetres,
                precipitationProbabilityPercent = if (raining) 80 else 10,
                observedAt = clock()
            )
        )
    }
}
