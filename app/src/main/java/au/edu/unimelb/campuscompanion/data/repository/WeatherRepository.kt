package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot

/** Current weather, used to add a buffer to departure reminders when it rains. */
interface WeatherRepository {
    suspend fun currentWeather(location: GeoPoint): Result<WeatherSnapshot>
}
