package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WeatherParsingTest {
    private val receivedAt = Instant.parse("2026-09-29T11:46:00Z")

    @Test
    fun theCurrentConditionsAndTheChanceOfRainForThisHourAreRead() {
        val forecast = remoteJson.decodeFromString(OpenMeteoForecast.serializer(), SAMPLE)

        val snapshot = forecast.toSnapshot(receivedAt)

        assertEquals(14.2, snapshot.temperatureCelsius, 0.0)
        assertEquals(0.3, snapshot.precipitationMillimetres, 0.0)
        assertEquals(61, snapshot.weatherCode)
        assertEquals(70, snapshot.precipitationProbabilityPercent)
        assertEquals(receivedAt, snapshot.observedAt)
    }

    @Test
    fun withoutHourlyRowsTheChanceOfRainIsUnknown() {
        val forecast = remoteJson.decodeFromString(
            OpenMeteoForecast.serializer(),
            """{"current":{"time":"2026-09-29T21:45","interval":900,"temperature_2m":18.0,"precipitation":0.0,"weather_code":1}}"""
        )

        val snapshot = forecast.toSnapshot(receivedAt)

        assertNull(snapshot.precipitationProbabilityPercent)
        assertEquals(0.0, snapshot.precipitationMillimetres, 0.0)
    }

    @Test
    fun theRequestAsksForTheCurrentConditionsAtThePlace() {
        val url = openMeteoUrl(GeoPoint(-37.8, 144.96))

        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=-37.8&longitude=144.96&"))
        assertTrue(url.contains("current=temperature_2m,precipitation,weather_code"))
        assertTrue(url.contains("hourly=precipitation_probability"))
        assertTrue(url.contains("timezone=auto"))
    }

    private companion object {
        val SAMPLE = """
            {
              "latitude": -37.8, "longitude": 144.96, "utc_offset_seconds": 36000,
              "timezone": "Australia/Melbourne", "timezone_abbreviation": "AEST",
              "current_units": {"time": "iso8601", "temperature_2m": "°C", "precipitation": "mm", "weather_code": "wmo code"},
              "current": {"time": "2026-09-29T21:45", "interval": 900, "temperature_2m": 14.2, "precipitation": 0.3, "weather_code": 61},
              "hourly_units": {"time": "iso8601", "precipitation_probability": "%"},
              "hourly": {
                "time": ["2026-09-29T20:00", "2026-09-29T21:00", "2026-09-29T22:00"],
                "precipitation_probability": [20, 70, 90]
              }
            }
        """.trimIndent()
    }
}
