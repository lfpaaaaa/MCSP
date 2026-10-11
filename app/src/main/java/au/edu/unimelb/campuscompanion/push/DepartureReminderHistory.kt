package au.edu.unimelb.campuscompanion.push

import android.content.Context
import android.net.Uri
import au.edu.unimelb.campuscompanion.sensing.location.TravelState
import au.edu.unimelb.campuscompanion.ui.model.CourseSession

/** Prevents a service/activity restart from delivering the same class warning again. No GPS is stored. */
class DepartureReminderHistory(context: Context) {
    private val storage = context.getSharedPreferences("departure_reminder_history", Context.MODE_PRIVATE)
    fun wasSent(userId: String, session: CourseSession, state: TravelState): Boolean =
        storage.contains(key(userId, session, state))
    fun record(userId: String, session: CourseSession, state: TravelState) {
        val cutoff = System.currentTimeMillis()
        storage.edit().apply {
            storage.all.forEach { (key, expiry) -> if (expiry is Long && expiry < cutoff) remove(key) }
            putLong(key(userId, session, state), session.end.toInstant().toEpochMilli())
        }.apply()
    }
    private fun key(userId: String, session: CourseSession, state: TravelState) =
        "${Uri.encode(userId)}:${Uri.encode(session.id)}:${state.name}"
}
