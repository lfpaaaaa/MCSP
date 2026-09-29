package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot

/**
 * Extra minutes to leave before a class because of the weather: rain slows the walk and makes
 * people wait for a gap; heat does the same. The thresholds are a first guess for the context
 * engine to tune.
 */
object WeatherBuffer {
    const val RAIN_MINUTES = 5
    const val HEAVY_RAIN_MINUTES = 10
    const val HEAT_MINUTES = 5

    /** Chance of rain in the current hour from which people take an umbrella and a slower route. */
    const val RAIN_PROBABILITY_PERCENT = 50

    /** Rain in the 15-minute reporting interval that counts as heavy (about 10 mm per hour). */
    const val HEAVY_RAIN_MILLIMETRES = 2.5

    const val HEAT_CELSIUS = 32.0

    fun extraMinutes(weather: WeatherSnapshot?): Int {
        if (weather == null) {
            return 0
        }
        val code = weather.weatherCode ?: NO_CODE
        val rain = when {
            code in HEAVY_RAIN_CODES || weather.precipitationMillimetres >= HEAVY_RAIN_MILLIMETRES -> HEAVY_RAIN_MINUTES
            code in RAIN_CODES || weather.precipitationMillimetres > 0.0 ||
                (weather.precipitationProbabilityPercent ?: 0) >= RAIN_PROBABILITY_PERCENT -> RAIN_MINUTES
            else -> 0
        }
        val heat = if (weather.temperatureCelsius >= HEAT_CELSIUS) HEAT_MINUTES else 0
        return rain + heat
    }

    private const val NO_CODE = -1

    /** WMO codes for drizzle, rain, showers and thunderstorms. */
    private val RAIN_CODES = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 97, 99)

    /** WMO codes for heavy rain, violent showers and thunderstorms. */
    private val HEAVY_RAIN_CODES = setOf(65, 67, 82, 95, 96, 97, 99)
}
