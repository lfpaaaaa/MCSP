package au.edu.unimelb.campuscompanion.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.departurePromptKey
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.ui.CourseReminderPreferencesStore
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Recreates the background SDK user-lookup gap with an isolated confirmed account. The real
 * Application's engine and reminder collectors run throughout: no direct consider/show calls.
 * Authentication and one timestamped GPS observation are fixtures, not live login/GPS acceptance.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundDepartureWiringTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun confirmedAccountKeepsPreferencesAndAutomaticallyNotifiesWhenSdkUserIsUnavailable() {
        runBlocking {
            withTimeout(30_000) {
                SupabaseProvider.client?.auth?.sessionStatus?.first { it != SessionStatus.Initializing }
                AppRepositories.sensingAccountReady.first { it }
            }
        }
        // Do not replace any real signed-in account when running this fixture.
        assumeTrue("Run with a signed-out test account", AppRepositories.sensingAccounts.currentAccountId == null)
        assumeTrue("The SDK lookup must be unavailable", SupabaseProvider.client?.auth?.currentUserOrNull() == null)
        assertTrue("Enable app notifications on the emulator", Notifications.canNotify(context))
        val account = "background-reminder-${UUID.randomUUID()}"
        val now = ZonedDateTime.now(ZoneId.of("Australia/Melbourne"))
        val session = CourseSession(
            id = "background-occurrence-${UUID.randomUUID()}", code = "QA-CONTEXT",
            title = "Background reminder fixture", location = "PAR", room = "PAR-160",
            start = now.minusMinutes(20), end = now.plusMinutes(40), activity = account
        )
        val preferences = CourseReminderPreferencesStore(context, account)
        val choice = CourseReminderPreference(enabled = true, leadMinutes = 7, preparationMinutes = 23)
        val key = session.departurePromptKey()
        val tag = DepartureNotifier.tag(key)
        val historyStore = context.getSharedPreferences("context_departure_reminder_history", Context.MODE_PRIVATE)
        val originalHistory = historyStore.all.toMap()
        val historyPrefix = "context-v1:${Uri.encode(account)}:"
        val oldSessions = AppRepositories.sensingAccounts.currentSessions
        try {
            preferences.save(session.reminderSeriesKey(), choice)
            instrumentation.runOnMainSync {
                AppRepositories.sensingAccounts.replaceAccount(account, listOf(session))
                AppRepositories.travel.startSensing()
            }
            runBlocking { AppRepositories.travel.refresh() }
            val beforePosition = AppRepositories.travel.snapshot.value
            assertEquals(session.id, beforePosition.session?.id)
            assertEquals(7, beforePosition.leadMinutes)
            assertEquals(23, beforePosition.preparationMinutes)
            assertEquals(ContextState.CLASS_IN_PROGRESS, beforePosition.state)
            assertTrue(manager.activeNotifications.none { it.tag == tag })

            val building = requireNotNull(AppRepositories.travel.sensingTarget.value.building)
            val target = AppRepositories.travel.sensingTarget.value
            val observed = SystemClock.elapsedRealtimeNanos()
            val evidence = SensingEvidence(target.generation, LocationSample(
                building.location.latitude + 0.004, building.location.longitude,
                5f, System.currentTimeMillis(), observed
            ))
            assertTrue(AppRepositories.travel.updateEvidence(evidence))
            assertEquals(ContextState.RUNNING_LATE, AppRepositories.travel.snapshot.value.state)
            val history = DepartureReminderHistory(context)
            await {
                manager.activeNotifications.any { it.tag == tag } &&
                    history.wasSent(account, session, ContextState.RUNNING_LATE)
            }
            val notification = manager.activeNotifications.single { it.tag == tag }.notification
            assertEquals("Running late for QA-CONTEXT", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(DepartureNotifier.CHANNEL_ID, notification.channelId)
            assertNull("The production notifier must not need the SDK's transient user lookup",
                SupabaseProvider.client?.auth?.currentUserOrNull())
            assertEquals(account, AppRepositories.sensingAccounts.currentAccountId)
            assertEquals(1, manager.activeNotifications.count { it.tag == tag })
        } finally {
            instrumentation.runOnMainSync {
                AppRepositories.sensingAccounts.replaceAccount(null, oldSessions)
            }
            DepartureNotifier.cancelCourse(context, session.reminderSeriesKey())
            preferences.clear()
            historyStore.edit().apply {
                historyStore.all.keys.filter { it.startsWith(historyPrefix) }.forEach(::remove)
            }.commit()
            assertEquals("Preserve every non-fixture delivery record", originalHistory,
                historyStore.all.filterKeys { !it.startsWith(historyPrefix) })
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Timed out waiting for the Application's automatic departure notification", condition())
    }
}
