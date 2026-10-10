package au.edu.unimelb.campuscompanion.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    fun load(seriesKey: String, legacyKey: String? = null): CourseReminderPreference {
        // Keep existing choices until this activity gets its own setting.
        val savedKey = if (preferences.contains(key(seriesKey, "enabled"))) seriesKey
            else legacyKey ?: seriesKey
        return CourseReminderPreference(
            enabled = preferences.getBoolean(key(savedKey, "enabled"), true),
            leadMinutes = preferences.getInt(
                key(savedKey, "lead_minutes"),
                DEFAULT_COURSE_REMINDER_LEAD_MINUTES
            ).coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES)
        )
    }

    fun save(seriesKey: String, value: CourseReminderPreference) {
        preferences.edit()
            .putBoolean(key(seriesKey, "enabled"), value.enabled)
            .putInt(
                key(seriesKey, "lead_minutes"),
                value.leadMinutes.coerceIn(0, MAX_COURSE_REMINDER_LEAD_MINUTES)
            )
            .apply()
        revision.update { it + 1 }
    }

    fun clear() {
        val userPrefix = "${Uri.encode(userId)}:"
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(userPrefix) }
            .forEach(editor::remove)
        editor.apply()
        revision.update { it + 1 }
    }

    private fun key(seriesKey: String, field: String): String =
        "${Uri.encode(userId)}:${Uri.encode(seriesKey)}:$field"

    companion object {
        private val revision = MutableStateFlow(0L)
        val changes = revision.asStateFlow()

        private const val PREFERENCES_NAME = "course_reminder_preferences"
    }
}
