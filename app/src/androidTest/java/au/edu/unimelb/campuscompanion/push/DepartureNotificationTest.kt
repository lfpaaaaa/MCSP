package au.edu.unimelb.campuscompanion.push

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.DepartureReminders
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.util.UUID

/** Run with adb am instrument; do not use a runner task that uninstalls the user's app afterwards. */
@RunWith(AndroidJUnit4::class)
class DepartureNotificationTest {
    @Test fun leaveSoonReminderAppearsInSystemNotificationsAndDoesNotRepeat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = ZonedDateTime.now()
        val session = CourseSession(UUID.randomUUID().toString(), "QA-DEPARTURE", "Departure notification check",
            "Test", "Test", now.plusMinutes(30), now.plusMinutes(90), activity = "test-${UUID.randomUUID()}")
        val snapshot = TravelSnapshot(session = session, state = ContextState.LEAVE_SOON,
            estimate = TravelEstimate(TravelMode.Walking, 1200, 1500, EstimateSource.StraightLine, now.toInstant()))
        val manager = context.getSystemService(NotificationManager::class.java)
        val reminders = DepartureReminders(flowOf(), { CourseReminderPreference() }, { DepartureNotifier.show(context, it) })
        try {
            assertNotNull("Enable app notifications on the test device", reminders.consider(snapshot))
            val tag = "departure:${session.reminderSeriesKey()}"
            var notification: Notification? = null
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (notification == null && SystemClock.elapsedRealtime() < deadline) {
                notification = manager.activeNotifications.firstOrNull { it.tag == tag }?.notification
                if (notification == null) SystemClock.sleep(50)
            }
            assertNotNull(notification)
            assertEquals("Time to leave for QA-DEPARTURE", notification!!.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("20 min on foot"))
            assertNull(reminders.consider(snapshot))
            assertEquals(1, manager.activeNotifications.count { it.tag == tag })
        } finally { DepartureNotifier.cancelCourse(context, session.reminderSeriesKey()) }
    }
}
