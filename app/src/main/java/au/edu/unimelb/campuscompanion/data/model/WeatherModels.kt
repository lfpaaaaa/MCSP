package au.edu.unimelb.campuscompanion.data.model

import java.time.Instant

/** Current weather at a location, used to add a buffer to departure reminders. */
data class WeatherSnapshot(
    val temperatureCelsius: Double,
    val precipitationMillimetres: Double,
    val precipitationProbabilityPercent: Int?,
    val observedAt: Instant
)
