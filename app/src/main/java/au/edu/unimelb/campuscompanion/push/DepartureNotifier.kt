package au.edu.unimelb.campuscompanion.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import au.edu.unimelb.campuscompanion.MainActivity
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.context.DepartureReminder
import au.edu.unimelb.campuscompanion.context.DepartureShade

/**
 * Posts departure reminders, one notification per class occurrence (tagged with its
 * [DepartureReminder.key]); tapping one opens the Schedule screen. [shade] lets the reminder logic
 * update and withdraw them.
 */
object DepartureNotifier {
    const val CHANNEL_ID = "departure"
    private const val TAG_PREFIX = "departure:"
    private const val EXTRA_COURSE_KEY = "au.edu.unimelb.campuscompanion.extra.DEPARTURE_COURSE_KEY"

    /** Posts the reminder with sound and vibration; false when notifications are off or denied. */
    fun show(context: Context, reminder: DepartureReminder): Boolean = post(context, reminder, silently = false)

    /**
     * Replaces a reminder that is still shown, without sound or vibration. One the student
     * dismissed is not posted again; returns false then.
     */
    fun update(context: Context, reminder: DepartureReminder): Boolean {
        if (prompts(context).none { it.tag == tag(reminder.key) }) return false
        return post(context, reminder, silently = true)
    }

    /** The departure reminders in the notification shade, for the reminder logic. */
    fun shade(context: Context): DepartureShade {
        val appContext = context.applicationContext
        return object : DepartureShade {
            override fun shownKeys(): Set<String> =
                prompts(appContext).mapNotNull { it.tag?.removePrefix(TAG_PREFIX) }.toSet()

            override fun update(reminder: DepartureReminder) {
                DepartureNotifier.update(appContext, reminder)
            }

            override fun withdraw(key: String) = cancel(appContext) { it.tag == tag(key) }
        }
    }

    /** Clears this course's visible reminders without affecting chats or other courses. */
    fun cancelCourse(context: Context, courseKey: String) = cancel(context) {
        // Reminders posted before occurrence tags were tagged with their course.
        it.notification.extras.getString(EXTRA_COURSE_KEY) == courseKey || it.tag == tag(courseKey)
    }

    /** Clears every departure reminder, for example when the account changes. */
    fun cancelAll(context: Context) = cancel(context) { true }

    /** The notification tag of the reminder with this key. */
    fun tag(key: String): String = TAG_PREFIX + key

    @SuppressLint("MissingPermission")
    private fun post(context: Context, reminder: DepartureReminder, silently: Boolean): Boolean {
        if (!Notifications.canNotify(context)) return false
        createChannel(context)
        if (manager(context).getNotificationChannel(CHANNEL_ID)?.importance ==
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
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(reminder.title.resolve(context.resources))
            .setContentText(reminder.text.resolve(context.resources))
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text.resolve(context.resources)))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addExtras(Bundle().apply { putString(EXTRA_COURSE_KEY, reminder.courseKey) })
        if (silently) {
            // A correction to a reminder the student has already heard: no second sound.
            builder.setSilent(true).setOnlyAlertOnce(true)
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }
        return try {
            NotificationManagerCompat.from(context).notify(tag(reminder.key), reminder.notificationId, builder.build())
            true
        } catch (_: SecurityException) { false }
    }

    /** This app's departure reminders in the shade, including ones posted by earlier versions. */
    private fun prompts(context: Context): List<StatusBarNotification> =
        manager(context).activeNotifications.filter { it.notification.channelId == CHANNEL_ID }

    private fun cancel(context: Context, which: (StatusBarNotification) -> Boolean) {
        val manager = manager(context)
        prompts(context).filter(which).forEach { manager.cancel(it.tag, it.id) }
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.campus_companion_app_departure_reminders),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_when_it_is_time_to_leave_for_your)
            enableVibration(true)
        }
        manager(context).createNotificationChannel(channel)
    }
}
