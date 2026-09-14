package au.edu.unimelb.campuscompanion.data

import android.content.Context

enum class TravelMode(val displayName: String) {
    Walking("Walking"),
    PublicTransport("Public transport"),
    Driving("Driving")
}

data class TravelPreferences(
    val walkingThresholdMeters: Int = DEFAULT_WALKING_THRESHOLD_METERS,
    val longerDistanceMode: TravelMode = TravelMode.PublicTransport
) {
    fun preferredMode(distanceMeters: Int): TravelMode {
        require(distanceMeters >= 0) { "Distance cannot be negative" }
        return if (distanceMeters <= walkingThresholdMeters) {
            TravelMode.Walking
        } else {
            longerDistanceMode
        }
    }

    companion object {
        const val DEFAULT_WALKING_THRESHOLD_METERS = 1_000
    }
}

class TravelPreferencesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): TravelPreferences {
        val savedMode = preferences.getString(KEY_LONGER_DISTANCE_MODE, null)
        val longerDistanceMode = savedMode
            ?.let { name -> runCatching { TravelMode.valueOf(name) }.getOrNull() }
            ?: TravelMode.PublicTransport

        return TravelPreferences(
            walkingThresholdMeters = preferences.getInt(
                KEY_WALKING_THRESHOLD_METERS,
                TravelPreferences.DEFAULT_WALKING_THRESHOLD_METERS
            ),
            longerDistanceMode = longerDistanceMode
        )
    }

    fun save(preferencesValue: TravelPreferences) {
        preferences.edit()
            .putInt(KEY_WALKING_THRESHOLD_METERS, preferencesValue.walkingThresholdMeters)
            .putString(KEY_LONGER_DISTANCE_MODE, preferencesValue.longerDistanceMode.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "travel_preferences"
        const val KEY_WALKING_THRESHOLD_METERS = "walking_threshold_meters"
        const val KEY_LONGER_DISTANCE_MODE = "longer_distance_mode"
    }
}
