package au.edu.unimelb.campuscompanion.push

import android.content.Context
import android.net.Uri
import au.edu.unimelb.campuscompanion.context.DepartureReminderStage
import au.edu.unimelb.campuscompanion.ui.model.CourseSession

/** Prevents a service/activity restart from delivering the same class warning again. No GPS is stored. */
class DepartureReminderHistory(context: Context) {
    private val storage = context.getSharedPreferences("departure_reminder_history", Context.MODE_PRIVATE)
    fun wasSent(userId: String, session: CourseSession, stage: DepartureReminderStage): Boolean =
        storage.contains(key(userId, session, stage))
    fun record(userId: String, session: CourseSession, stage: DepartureReminderStage) {
        val cutoff = System.currentTimeMillis()
        storage.edit().apply {
            storage.all.forEach { (key, expiry) -> if (expiry is Long && expiry < cutoff) remove(key) }
            putLong(key(userId, session, stage), session.end.toInstant().toEpochMilli())
        }.apply()
    }
    // Keep existing on-device history valid when upgrading from travel-state based reminders.
    private val DepartureReminderStage.storageKey: String
        get() = when (this) {
            DepartureReminderStage.Prepare -> "SHOULD_LEAVE_SOON"
            DepartureReminderStage.LeaveNow -> "RUNNING_LATE"
        }

    private fun key(userId: String, session: CourseSession, stage: DepartureReminderStage) =
        "${Uri.encode(userId)}:${Uri.encode(session.id)}:${stage.storageKey}"
}
