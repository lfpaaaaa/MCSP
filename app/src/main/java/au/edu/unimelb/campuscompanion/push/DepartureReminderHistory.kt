package au.edu.unimelb.campuscompanion.push

import android.content.Context
import android.net.Uri
import android.util.Log
import au.edu.unimelb.campuscompanion.context.DepartureReminderStage
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.Instant

/** Prevents a service/activity restart from delivering the same class warning again. No GPS is stored. */
class DepartureReminderHistory(
    context: Context,
    private val now: () -> Instant = Instant::now,
    preferenceName: String = "context_departure_reminder_history"
) {
    private val storage = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    fun wasSent(userId: String, session: CourseSession, stage: DepartureReminderStage): Boolean =
        storage.getLong(key(userId, session, stage), 0L) > now().toEpochMilli()

    /** Call off the main thread: delivery is recorded on disk before this method returns. */
    fun record(userId: String, session: CourseSession, stage: DepartureReminderStage): Boolean {
        val cutoff = now().toEpochMilli()
        val expiry = session.end.toInstant().toEpochMilli()
        val saved = storage.edit().apply {
            storage.all.forEach { (key, expiry) -> if (expiry is Long && expiry <= cutoff) remove(key) }
            if (expiry > cutoff) putLong(key(userId, session, stage), expiry)
        }.commit()
        if (!saved) Log.w("DepartureReminderHistory", "Could not persist departure reminder delivery")
        return saved
    }

    // Keep the main branch's occurrence/account history while adding the earlier urgent warning.
    private val DepartureReminderStage.storageKey: String
        get() = when (this) {
            DepartureReminderStage.Prepare -> "LEAVE_SOON"
            DepartureReminderStage.LeaveNow -> "RUNNING_LATE"
        }
    private fun key(userId: String, session: CourseSession, stage: DepartureReminderStage) =
        "context-v1:${Uri.encode(userId)}:${Uri.encode(session.id)}:${session.start.toInstant()}:${stage.storageKey}"
}
