package au.edu.unimelb.campuscompanion.data

import android.content.Context

/** How the user travels to class; [displayName] is the label on screen. */
enum class TravelMode(val displayName: String) {
    Walking("Walking"),
    PublicTransport("Public transport"),
    Driving("Driving")
}

/**
 * The user's travel settings: up to which distance they walk, which mode they use for longer
 * trips, and whether and how early the departure reminders are posted.
 */
data class TravelPreferences(
    val walkingThresholdMeters: Int = DEFAULT_WALKING_THRESHOLD_METERS,
    val longerDistanceMode: TravelMode = TravelMode.PublicTransport,
    /** Whether a notification is posted when it is time to leave for the next class. */
    val remindersEnabled: Boolean = true,
    /** Minutes kept in hand on top of the travel time; the Schedule screen's slider. */
    val reminderLeadMinutes: Int = DEFAULT_REMINDER_LEAD_MINUTES
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
        const val DEFAULT_REMINDER_LEAD_MINUTES = 10
        val REMINDER_LEAD_RANGE: IntRange = 0..60
    }
}

/** Keeps [TravelPreferences] in SharedPreferences; missing values fall back to the defaults. */
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
            longerDistanceMode = longerDistanceMode,
            remindersEnabled = preferences.getBoolean(KEY_REMINDERS_ENABLED, true),
            reminderLeadMinutes = preferences.getInt(
                KEY_REMINDER_LEAD_MINUTES,
                TravelPreferences.DEFAULT_REMINDER_LEAD_MINUTES
            ).coerceIn(TravelPreferences.REMINDER_LEAD_RANGE)
        )
    }

    fun save(preferencesValue: TravelPreferences) {
        preferences.edit()
            .putInt(KEY_WALKING_THRESHOLD_METERS, preferencesValue.walkingThresholdMeters)
            .putString(KEY_LONGER_DISTANCE_MODE, preferencesValue.longerDistanceMode.name)
            .putBoolean(KEY_REMINDERS_ENABLED, preferencesValue.remindersEnabled)
            .putInt(KEY_REMINDER_LEAD_MINUTES, preferencesValue.reminderLeadMinutes)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "travel_preferences"
        const val KEY_WALKING_THRESHOLD_METERS = "walking_threshold_meters"
        const val KEY_LONGER_DISTANCE_MODE = "longer_distance_mode"
        const val KEY_REMINDERS_ENABLED = "reminders_enabled"
        const val KEY_REMINDER_LEAD_MINUTES = "reminder_lead_minutes"
    }
}
