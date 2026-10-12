package au.edu.unimelb.campuscompanion.push

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.DepartureReminder
import au.edu.unimelb.campuscompanion.context.DepartureReminders
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.DEFAULT_COURSE_REMINDER_LEAD_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Run with adb am instrument; do not use a runner task that uninstalls the user's app afterwards.
 * These tests only touch their own QA reminders, never the student's.
 */
@RunWith(AndroidJUnit4::class)
class DepartureNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun leaveSoonReminderAppearsInSystemNotificationsAndDoesNotRepeat() {
        val now = ZonedDateTime.now()
        val session = CourseSession(UUID.randomUUID().toString(), "QA-DEPARTURE", "Departure notification check",
            "Test", "Test", now.plusMinutes(30), now.plusMinutes(90), activity = "test-${UUID.randomUUID()}")
        val snapshot = TravelSnapshot(session = session, state = ContextState.LEAVE_SOON,
            estimate = TravelEstimate(TravelMode.Walking, 1200, 1500, EstimateSource.StraightLine, now.toInstant()),
            leadMinutes = DEFAULT_COURSE_REMINDER_LEAD_MINUTES)
        // Without the shade adapter, so the student's own reminders are never withdrawn by this test.
        val reminders = DepartureReminders(flowOf(), { CourseReminderPreference() }, { DepartureNotifier.show(context, it) })
        try {
            val reminder = reminders.consider(snapshot)
            assertNotNull("Enable app notifications on the test device", reminder)
            val tag = DepartureNotifier.tag(reminder!!.key)
            waitFor { manager.activeNotifications.any { it.tag == tag } }
            val notification = manager.activeNotifications.first { it.tag == tag }.notification
            assertEquals("Time to leave for QA-DEPARTURE", notification.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("20 min on foot"))
            assertNull(reminders.consider(snapshot))
            assertEquals(1, manager.activeNotifications.count { it.tag == tag })
        } finally { DepartureNotifier.cancelCourse(context, session.reminderSeriesKey()) }
    }

    @Test fun updatesReplaceAShownReminderButNeverRestoreAWithdrawnOne() {
        val courseKey = "activity:qa-${UUID.randomUUID()}:lecture"
        val key = "qa-${UUID.randomUUID()}@2026-10-13T01:00:00Z"
        val tag = DepartureNotifier.tag(key)
        fun reminder(text: String) =
            DepartureReminder("qa", courseKey, ContextState.LEAVE_SOON, "Time to leave for QA", text, key)
        fun shownText(): String? = manager.activeNotifications.firstOrNull { it.tag == tag }
            ?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        try {
            assertTrue("Enable app notifications on the test device", DepartureNotifier.show(context, reminder("In Peter Hall")))
            waitFor { shownText() == "In Peter Hall" }
            assertTrue(key in DepartureNotifier.shade(context).shownKeys())

            // Listen for sound or vibration when checking by hand: this replacement must be silent.
            assertTrue(DepartureNotifier.update(context, reminder("In Alan Gilbert")))
            waitFor { shownText() == "In Alan Gilbert" }
            assertEquals(1, manager.activeNotifications.count { it.tag == tag })
            val replacement = manager.activeNotifications.first { it.tag == tag }.notification
            assertTrue(replacement.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertNull(replacement.sound)
            assertNull(replacement.vibrate)
            assertEquals(0, replacement.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))

            DepartureNotifier.shade(context).withdraw(key)
            waitFor { shownText() == null }
            assertFalse(DepartureNotifier.update(context, reminder("In FBE")))
            SystemClock.sleep(300)
            assertNull(shownText())
        } finally { DepartureNotifier.cancelCourse(context, courseKey) }
    }

    @Test fun turningACourseOffClearsOnlyThatCoursesReminders() {
        val muted = "activity:qa-muted-${UUID.randomUUID()}:tutorial"
        val other = "activity:qa-other-${UUID.randomUUID()}:lecture"
        val mutedKey = "qa-muted-${UUID.randomUUID()}@2026-10-13T01:00:00Z"
        val otherKey = "qa-other-${UUID.randomUUID()}@2026-10-13T02:00:00Z"
        try {
            assertTrue("Enable app notifications on the test device", DepartureNotifier.show(context,
                DepartureReminder("qa", muted, ContextState.LEAVE_SOON, "Time to leave for QA", "Muted", mutedKey)))
            assertTrue(DepartureNotifier.show(context,
                DepartureReminder("qa", other, ContextState.LEAVE_SOON, "Time to leave for QA", "Other", otherKey)))
            waitFor { DepartureNotifier.shade(context).shownKeys().containsAll(listOf(mutedKey, otherKey)) }

            DepartureNotifier.cancelCourse(context, muted)
            waitFor { mutedKey !in DepartureNotifier.shade(context).shownKeys() }
            assertTrue(otherKey in DepartureNotifier.shade(context).shownKeys())
        } finally {
            DepartureNotifier.cancelCourse(context, muted)
            DepartureNotifier.cancelCourse(context, other)
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for the notification shade", condition())
    }
}
