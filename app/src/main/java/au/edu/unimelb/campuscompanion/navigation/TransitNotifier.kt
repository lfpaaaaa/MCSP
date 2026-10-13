package au.edu.unimelb.campuscompanion.navigation

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import au.edu.unimelb.campuscompanion.MainActivity
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.push.NotificationTaps
import au.edu.unimelb.campuscompanion.push.Notifications

object TransitNotifier {
    private const val ALERT_CHANNEL = "transit_arrival"
    private const val STATUS_CHANNEL = "transit_tracking"
    private const val ALERT_ID = 2201
    private const val STATUS_ID = 2202

    @SuppressLint("MissingPermission")
    fun show(context: Context, leg: TransitLeg) {
        if (!Notifications.canNotify(context)) return
        channels(context)
        val text = context.getString(R.string.notification_approaching_get_ready_to_get_off, leg.line, leg.arrivalName)
        context.getSystemService(NotificationManager::class.java).notify(ALERT_ID,
            base(context, ALERT_CHANNEL).setContentTitle(context.getString(R.string.notification_your_stop_is_approaching))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
    }
    @SuppressLint("MissingPermission")
    fun tracking(context: Context, active: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!active) { manager.cancel(STATUS_ID); return }
        if (!Notifications.canNotify(context)) return
        channels(context)
        val stop = PendingIntent.getBroadcast(context, STATUS_ID, Intent(context, StopTransitReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(STATUS_ID, base(context, STATUS_CHANNEL)
            .setContentTitle(context.getString(R.string.notification_get_off_reminder_is_on))
            .setContentText(context.getString(R.string.notification_tracking_your_selected_route_tap_to_see_your))
            .setOngoing(true).addAction(0, context.getString(R.string.class_navigation_end_trip), stop).build())
    }
    fun cancelAlert(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID) }
    private fun base(context: Context, channel: String): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).putExtra(NotificationTaps.EXTRA_SCREEN, "navigation")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, STATUS_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channel).setSmallIcon(R.mipmap.ic_launcher).setContentIntent(tap)
    }
    private fun channels(context: Context) {
        context.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(ALERT_CHANNEL, context.getString(R.string.notification_get_off_reminders), NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
            createNotificationChannel(NotificationChannel(STATUS_CHANNEL, context.getString(R.string.notification_transit_tracking), NotificationManager.IMPORTANCE_LOW))
        }
    }
}

class StopTransitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AppRepositories.navigation.stop() }
}
