package au.edu.unimelb.campuscompanion.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import au.edu.unimelb.campuscompanion.MainActivity
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.context.DepartureReminder

/** Posts departure reminders; tapping one opens the Schedule screen. */
object DepartureNotifier {
    const val CHANNEL_ID = "departure"

    @SuppressLint("MissingPermission")
    fun show(context: Context, reminder: DepartureReminder): Boolean {
        if (!Notifications.canNotify(context)) return false
        createChannel(context)
        if (context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE) return false

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationTaps.EXTRA_SCREEN, NotificationTaps.SCREEN_SCHEDULE)
        }
        val tap = PendingIntent.getActivity(
            context,
            reminder.notificationId,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(courseTag(reminder.courseKey), reminder.notificationId, notification)
            true
        } catch (_: SecurityException) { false }
    }

    /** Clears this course's visible reminders without affecting chats or other courses. */
    fun cancelCourse(context: Context, courseKey: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == courseTag(courseKey) }.forEach {
            manager.cancel(it.tag, it.id)
        }
    }

    private fun courseTag(key: String) = "departure:$key"

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Departure reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "When it is time to leave for your next class"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
