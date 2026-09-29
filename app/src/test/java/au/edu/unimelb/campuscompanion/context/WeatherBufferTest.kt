package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class WeatherBufferTest {
    @Test
    fun dryMildWeatherAddsNothing() {
        assertEquals(0, WeatherBuffer.extraMinutes(weather(temperature = 18.0)))
        assertEquals(0, WeatherBuffer.extraMinutes(null))
    }

    @Test
    fun rainOrALikelyShowerAddsFiveMinutes() {
        assertEquals(5, WeatherBuffer.extraMinutes(weather(precipitation = 0.2)))
        assertEquals(5, WeatherBuffer.extraMinutes(weather(code = 61)))
        assertEquals(5, WeatherBuffer.extraMinutes(weather(probability = 50)))
        assertEquals(0, WeatherBuffer.extraMinutes(weather(probability = 49)))
    }

    @Test
    fun heavyRainOrAStormAddsTenMinutes() {
        assertEquals(10, WeatherBuffer.extraMinutes(weather(precipitation = 2.5)))
        assertEquals(10, WeatherBuffer.extraMinutes(weather(code = 95)))
    }

    @Test
    fun heatAddsFiveMinutesOnTopOfRain() {
        assertEquals(5, WeatherBuffer.extraMinutes(weather(temperature = 32.0)))
        assertEquals(15, WeatherBuffer.extraMinutes(weather(temperature = 35.0, code = 82)))
    }

    private fun weather(
        temperature: Double = 18.0,
        precipitation: Double = 0.0,
        probability: Int? = 10,
        code: Int? = 1
    ) = WeatherSnapshot(
        temperatureCelsius = temperature,
        precipitationMillimetres = precipitation,
        precipitationProbabilityPercent = probability,
        observedAt = Instant.parse("2026-09-29T08:00:00Z"),
        weatherCode = code
    )
}
