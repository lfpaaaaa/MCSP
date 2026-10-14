package au.edu.unimelb.campuscompanion.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses disposable QA channels, without changing the user's departure reminder settings. */
@RunWith(AndroidJUnit4::class)
class DepartureNotificationChannelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun newChannelsDefaultToHeadsUpAndNotificationSound() = withTestChannel { id ->
        DepartureNotifier.createChannel(context, id)
        val channel = manager.getNotificationChannel(id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes.usage)
        assertTrue(channel.shouldVibrate())
        assertFalse(channel.canBypassDnd())
    }

    @Test fun registeringAgainPreservesSilentLowImportanceSettings() = withTestChannel { id ->
        manager.createNotificationChannel(NotificationChannel(id, "QA silent reminders", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        })
        DepartureNotifier.createChannel(context, id)
        DepartureNotifier.createChannel(context, id)
        val channel = manager.getNotificationChannel(id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test fun registeringAgainDoesNotReenableBlockedReminders() = withTestChannel { id ->
        manager.createNotificationChannel(NotificationChannel(id, "QA blocked reminders", NotificationManager.IMPORTANCE_NONE))
        DepartureNotifier.createChannel(context, id)
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(id).importance)
    }

    @Test fun settingsShortcutTargetsOnlyDepartureReminders() {
        val intent = DepartureNotifier.notificationSettingsIntent(context)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(DepartureNotifier.CHANNEL_ID, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    private fun withTestChannel(test: (String) -> Unit) {
        val id = "qa-departure-${UUID.randomUUID()}"
        try { test(id) } finally { manager.deleteNotificationChannel(id) }
    }
}
