package au.edu.unimelb.campuscompanion.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import au.edu.unimelb.campuscompanion.BuildConfig
import au.edu.unimelb.campuscompanion.MainActivity
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.context.ContextState
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
    private const val DEBUG_TAG = "DeparturePrompt"
    private const val REJECTION_LOG_INTERVAL_MILLIS = 5_000L
    private val diagnosticLock = Any()
    private var lastRejectionLogElapsedMillis: Long? = null

    /** Posts the reminder with sound and vibration; false when notifications are off or denied. */
    fun show(context: Context, reminder: DepartureReminder): Boolean = post(context, reminder, silently = false)

    /**
     * Replaces a reminder that is still shown, without sound or vibration. One the student
     * dismissed is not posted again; returns false then.
     */
    fun update(context: Context, reminder: DepartureReminder): Boolean {
        if (prompts(context).none { it.tag == tag(reminder.key) }) {
            logDiagnostic("update", reminder.notificationId, "not_shown", reminder.state, limited = true)
            return false
        }
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
        val operation = if (silently) "update" else "post"
        if (!Notifications.canNotify(context)) {
            logDiagnostic(operation, reminder.notificationId, "notifications_unavailable", reminder.state, limited = true)
            return false
        }
        createChannel(context)
        if (manager(context).getNotificationChannel(CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE) {
            logDiagnostic(operation, reminder.notificationId, "channel_disabled", reminder.state, limited = true)
            return false
        }

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
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
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
            logDiagnostic(operation, reminder.notificationId, "submitted", reminder.state)
            true
        } catch (_: SecurityException) {
            logDiagnostic(operation, reminder.notificationId, "permission_changed", reminder.state, limited = true)
            false
        }
    }

    /** This app's departure reminders in the shade, including ones posted by earlier versions. */
    private fun prompts(context: Context): List<StatusBarNotification> =
        manager(context).activeNotifications.filter { it.notification.channelId == CHANNEL_ID }

    private fun cancel(context: Context, which: (StatusBarNotification) -> Boolean) {
        val manager = manager(context)
        prompts(context).filter(which).forEach {
            manager.cancel(it.tag, it.id)
            logDiagnostic("withdraw", it.id, "submitted")
        }
    }

    /** One line per notification operation; bound repeated delivery failures without storing content. */
    private fun logDiagnostic(
        operation: String,
        notificationId: Int,
        result: String,
        state: ContextState? = null,
        limited: Boolean = false
    ) {
        if (!BuildConfig.DEBUG) return
        val elapsedMillis = SystemClock.elapsedRealtime()
        if (limited) {
            synchronized(diagnosticLock) {
                val previous = lastRejectionLogElapsedMillis
                if (previous != null && elapsedMillis - previous < REJECTION_LOG_INTERVAL_MILLIS) return
                lastRejectionLogElapsedMillis = elapsedMillis
            }
        }
        val stateField = state?.let { ", state=$it" }.orEmpty()
        // Submission is an API call result, not a claim that Android has displayed/removed it yet.
        Log.d(DEBUG_TAG, "operation=$operation, result=$result, notificationId=$notificationId" +
            "$stateField, elapsedRealtimeMs=$elapsedMillis")
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Departure reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "When it is time to leave for your next class"
            enableVibration(true)
        }
        manager(context).createNotificationChannel(channel)
    }
}
