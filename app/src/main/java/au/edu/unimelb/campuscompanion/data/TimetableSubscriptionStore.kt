package au.edu.unimelb.campuscompanion.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TimetableSubscriptionStore internal constructor(
    private val read: (String) -> String?,
    private val confirmedCleanup: (Set<String>) -> Boolean
) : TimetableLegacyStorage {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(
        "timetable_subscription", Context.MODE_PRIVATE
    ))

    internal constructor(preferences: SharedPreferences) : this(
        read = { key -> preferences.getString(key, null) },
        confirmedCleanup = { keys ->
            val editor = preferences.edit()
            keys.forEach(editor::remove)
            editor.commit()
        }
    )

    override fun readLegacy(ownerId: String): SavedTimetable {
        // Retain the existing key so users with only a URL saved upgrade without reconnecting.
        val url = read(urlKey(ownerId)).orEmpty()
        return TimetableCache.decode(read(cacheKey(ownerId)), url)
            ?: SavedTimetable(url)
    }

    override suspend fun clearLegacy(ownerId: String): Boolean = withContext(Dispatchers.IO) {
        val keys = setOf(urlKey(ownerId), cacheKey(ownerId))
        confirmedCleanup(keys)
    }

    private fun urlKey(userId: String) = "subscription_url_$userId"
    private fun cacheKey(userId: String) = "snapshot_$userId"
}
