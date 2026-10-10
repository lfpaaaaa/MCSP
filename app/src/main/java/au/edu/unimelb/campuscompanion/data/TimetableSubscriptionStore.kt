package au.edu.unimelb.campuscompanion.data

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant

class TimetableSubscriptionStore internal constructor(
    private val read: (String) -> String?,
    private val write: (Map<String, String?>) -> Unit
) : TimetablePersistence {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(
        "timetable_subscription", Context.MODE_PRIVATE
    ))

    private constructor(preferences: SharedPreferences) : this(
        read = { key -> preferences.getString(key, null) },
        write = { entries ->
            val editor = preferences.edit()
            entries.forEach { (key, value) -> editor.putString(key, value) }
            editor.apply()
        }
    )

    override fun load(userId: String): SavedTimetable {
        // Retain the existing key so users with only a URL saved upgrade without reconnecting.
        val url = read(urlKey(userId)).orEmpty()
        return TimetableCache.decode(read(cacheKey(userId)), url)
            ?: SavedTimetable(url)
    }

    override fun save(userId: String, url: String, timetable: TimetableImport, savedAt: Instant) {
        val encoded = TimetableCache.encode(url, timetable, savedAt)
        write(mapOf(urlKey(userId) to url, cacheKey(userId) to encoded))
    }

    override fun clear(userId: String) {
        write(mapOf(urlKey(userId) to null, cacheKey(userId) to null))
    }

    private fun urlKey(userId: String) = "subscription_url_$userId"
    private fun cacheKey(userId: String) = "snapshot_$userId"
}
