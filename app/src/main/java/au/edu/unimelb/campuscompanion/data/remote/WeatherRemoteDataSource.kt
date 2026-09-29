package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Current weather from the weather service. Implementations throw
 * [au.edu.unimelb.campuscompanion.data.DataError] on failure.
 */
interface WeatherRemoteDataSource {
    suspend fun currentWeather(location: GeoPoint): WeatherSnapshot
}

/** The parts of an Open-Meteo forecast answer that the app reads (https://open-meteo.com/en/docs). */
@Serializable
internal data class OpenMeteoForecast(
    val current: OpenMeteoCurrent,
    val hourly: OpenMeteoHourly? = null
)

@Serializable
internal data class OpenMeteoCurrent(
    /** Local time such as "2026-09-29T21:45". */
    val time: String,
    @SerialName("temperature_2m") val temperatureCelsius: Double,
    val precipitation: Double = 0.0,
    @SerialName("weather_code") val weatherCode: Int? = null
)

@Serializable
internal data class OpenMeteoHourly(
    val time: List<String> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?> = emptyList()
)

/** The forecast request for a place: current conditions and the hour-by-hour chance of rain today. */
internal fun openMeteoUrl(location: GeoPoint): String =
    "https://api.open-meteo.com/v1/forecast" +
        "?latitude=${location.latitude}&longitude=${location.longitude}" +
        "&current=temperature_2m,precipitation,weather_code" +
        "&hourly=precipitation_probability&forecast_days=1&timezone=auto"

/** The current conditions, with the chance of rain for the current hour. */
internal fun OpenMeteoForecast.toSnapshot(observedAt: Instant): WeatherSnapshot {
    // Times are local and share one format, so the row for the current hour is the last one that
    // does not come after the current time.
    val probability = hourly?.let { rows ->
        rows.time.indices
            .lastOrNull { index -> rows.time[index] <= current.time }
            ?.let { index -> rows.precipitationProbability.getOrNull(index) }
    }
    return WeatherSnapshot(
        temperatureCelsius = current.temperatureCelsius,
        precipitationMillimetres = current.precipitation.coerceAtLeast(0.0),
        precipitationProbabilityPercent = probability,
        observedAt = observedAt,
        weatherCode = current.weatherCode
    )
}
