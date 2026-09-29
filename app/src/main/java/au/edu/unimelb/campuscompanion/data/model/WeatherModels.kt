package au.edu.unimelb.campuscompanion.data.model

import java.time.Instant

/** Current weather at a location, used to add a buffer to departure reminders. */
data class WeatherSnapshot(
    val temperatureCelsius: Double,
    /** Rain in the current reporting interval (about 15 minutes), in millimetres. */
    val precipitationMillimetres: Double,
    val precipitationProbabilityPercent: Int?,
    val observedAt: Instant,
    /** WMO weather code (0 clear, 61-67 rain, 80-82 showers, 95-99 thunderstorm), when reported. */
    val weatherCode: Int? = null
)
