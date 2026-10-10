package au.edu.unimelb.campuscompanion.ui

import android.content.Context
import android.net.Uri
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.DEFAULT_COURSE_REMINDER_LEAD_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.MAX_COURSE_REMINDER_LEAD_MINUTES

class CourseReminderPreferencesStore(
    context: Context,
    private val userId: String
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(seriesKey: String): CourseReminderPreference = CourseReminderPreference(
        enabled = preferences.getBoolean(key(seriesKey, "enabled"), true),
        leadMinutes = preferences.getInt(
            key(seriesKey, "lead_minutes"),
            DEFAULT_COURSE_REMINDER_LEAD_MINUTES
        ).coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES)
    )

    fun save(seriesKey: String, value: CourseReminderPreference) {
        preferences.edit()
            .putBoolean(key(seriesKey, "enabled"), value.enabled)
            .putInt(
                key(seriesKey, "lead_minutes"),
                value.leadMinutes.coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES)
            )
            .apply()
    }

    fun clear() {
        val userPrefix = "${Uri.encode(userId)}:"
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(userPrefix) }
            .forEach(editor::remove)
        editor.apply()
    }

    private fun key(seriesKey: String, field: String): String =
        "${Uri.encode(userId)}:${Uri.encode(seriesKey)}:$field"

    private companion object {
        const val PREFERENCES_NAME = "course_reminder_preferences"
    }
}
