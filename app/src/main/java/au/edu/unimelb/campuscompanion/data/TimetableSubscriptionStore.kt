package au.edu.unimelb.campuscompanion.data

import android.content.Context

class TimetableSubscriptionStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "timetable_subscription",
        Context.MODE_PRIVATE
    )

    fun loadUrl(userId: String): String = preferences.getString(keyFor(userId), "").orEmpty()

    fun saveUrl(userId: String, url: String) {
        preferences.edit().putString(keyFor(userId), url).apply()
    }

    fun clear(userId: String) {
        preferences.edit().remove(keyFor(userId)).apply()
    }

    private fun keyFor(userId: String) = "subscription_url_$userId"
}
