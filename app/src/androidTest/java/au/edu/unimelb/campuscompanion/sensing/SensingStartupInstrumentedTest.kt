package au.edu.unimelb.campuscompanion.sensing

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import au.edu.unimelb.campuscompanion.MainActivity
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.push.Notifications
import au.edu.unimelb.campuscompanion.sensing.location.locationPermissionState
import au.edu.unimelb.campuscompanion.sensing.service.SensingForegroundService
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Run this class with adb am instrument after configuring the desired permission state externally.
 * Changing our own package's grants during instrumentation kills the runner, so these probes never
 * grant/revoke permissions or clear app data. Run once precise, once coarse-only and once denied.
 * Remaining alive beyond Android's foreground-promotion deadline is part of the regression check.
 */
@RunWith(AndroidJUnit4::class)
class SensingStartupInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val serviceIntent = Intent(context, SensingForegroundService::class.java)
        .setAction(SensingForegroundService.ACTION_PRE_CLASS_MODE)

    @Test
    fun approximateOnlyActivityAndAlreadyQueuedServiceLaunchDoNotCrash() {
        val permission = locationPermissionState(context)
        assumeTrue("Configure coarse-only access before this probe", !permission.fine && permission.coarse)
        withVisibleActivity {
            assertFalse("Approximate-only activity startup must not start sensing", serviceRunning())
            // Models a fine -> coarse change after the Activity's launch check but before delivery.
            instrumentation.runOnMainSync { ContextCompat.startForegroundService(context, serviceIntent) }
            observePastForegroundDeadline()
            assertFalse("The coarse-permitted foreground promotion must stop precise sensing", serviceRunning())
        }
    }

    @Test
    fun deniedLocationActivityAndAlreadyQueuedServiceLaunchDoNotCrash() {
        val permission = locationPermissionState(context)
        assumeTrue("Configure denied location before this probe", !permission.foregroundGranted)
        withVisibleActivity {
            assertFalse("Denied-location activity startup must not start sensing", serviceRunning())
            // Models total permission loss after a valid foreground launch was already queued.
            instrumentation.runOnMainSync { ContextCompat.startForegroundService(context, serviceIntent) }
            observePastForegroundDeadline()
            assertFalse("The cleanup-only foreground promotion must stop sensing", serviceRunning())
        }
    }

    @Test
    fun preciseForegroundLocationStartsARealForegroundSensingService() {
        assumeTrue("Configure precise access before this probe", locationPermissionState(context).fine)
        withVisibleActivity {
            withFixtureAccount {
                await("Precise access should permit foreground sensing") { serviceRunning(foregroundOnly = true) }
            }
        }
    }

    @Test
    fun deniedNotificationsDoNotBlockPreciseForegroundSensing() {
        assumeTrue("Requires precise access", locationPermissionState(context).fine)
        assumeTrue("Configure denied notifications before this probe", !notificationsEnabled())
        // Model a declined first-use request without putting another system dialog over the test.
        val prompts = context.getSharedPreferences("permission_prompts", android.content.Context.MODE_PRIVATE)
        val key = "notification_permission_requested"
        val existed = prompts.contains(key)
        val original = prompts.getBoolean(key, false)
        prompts.edit().putBoolean(key, true).commit()
        try {
            withVisibleActivity {
                withFixtureAccount {
                    await("Notification denial must still allow the location foreground service") {
                        serviceRunning(foregroundOnly = true)
                    }
                    assertFalse("Reminder delivery must remain unavailable", Notifications.canNotify(context))
                }
            }
        } finally {
            prompts.edit().apply { if (existed) putBoolean(key, original) else remove(key) }.commit()
        }
    }

    @Test
    fun repeatedActivityReconciliationKeepsOneSensingGeneration() {
        assumeTrue("Requires precise access", locationPermissionState(context).fine)
        withVisibleActivity {
            withFixtureAccount {
                await("Visible account startup should start sensing") { serviceRunning(foregroundOnly = true) }
                val generation = AppRepositories.travel.sensingTarget.value.generation
                repeat(3) {
                    command(SensingForegroundService.ACTION_RECONCILE)
                    command(SensingForegroundService.ACTION_START)
                }
                assertEquals("Repeated starts must not reset the engine activation period", generation,
                    AppRepositories.travel.sensingTarget.value.generation)
                assertTrue(serviceRunning(foregroundOnly = true))
            }
        }
    }

    @Test
    fun navigationCommandsChangeModeWithoutRestartingTheSensingRun() {
        assumeTrue("Requires precise access", locationPermissionState(context).fine)
        assumeTrue("Mode probe reads the foreground notification", notificationsEnabled())
        withVisibleActivity {
            withFixtureAccount {
                awaitNotification("Background sensing — battery saving mode")
                val generation = AppRepositories.travel.sensingTarget.value.generation
                command(SensingForegroundService.ACTION_PRE_CLASS_MODE)
                awaitNotification("Monitoring your trip to class")
                repeat(3) { command(SensingForegroundService.ACTION_PRE_CLASS_MODE) }
                assertEquals(generation, AppRepositories.travel.sensingTarget.value.generation)
                command(SensingForegroundService.ACTION_NORMAL_MODE)
                awaitNotification("Background sensing — battery saving mode")
                assertEquals(generation, AppRepositories.travel.sensingTarget.value.generation)
            }
        }
    }

    @Test
    fun boundedNavigationRequestExpiresOnTheServiceTick() {
        assumeTrue("Requires precise access", locationPermissionState(context).fine)
        assumeTrue("Mode probe reads the foreground notification", notificationsEnabled())
        withVisibleActivity {
            withFixtureAccount {
                command(SensingForegroundService.ACTION_PRE_CLASS_MODE)
                awaitNotification("Monitoring your trip to class")
                val generation = AppRepositories.travel.sensingTarget.value.generation
                // Exercise the real two-minute deadline and tick, with no hidden test override.
                await("A legacy command must not keep high-rate sensors running forever", 130_000L) {
                    notificationText() == "Background sensing — battery saving mode"
                }
                assertEquals(generation, AppRepositories.travel.sensingTarget.value.generation)
                assertTrue(serviceRunning(foregroundOnly = true))
            }
        }
    }

    @Test
    fun accountReplacementResetsTheSameClassAndBuildingThenSignoutStopsTheService() {
        assumeTrue("Requires precise access", locationPermissionState(context).fine)
        val session = futureClass()
        withVisibleActivity {
            withFixtureAccount(listOf(session)) {
                await("Fixture account should activate sensing") { AppRepositories.travel.sensingTarget.value.active }
                val first = AppRepositories.travel.sensingTarget.value
                instrumentation.runOnMainSync {
                    AppRepositories.sensingAccounts.replaceAccount("sensing-fixture-b", listOf(session))
                }
                await("Replacement must keep the already-authorized service") { serviceRunning(foregroundOnly = true) }
                val second = AppRepositories.travel.sensingTarget.value
                assertTrue(second.generation > first.generation)
                assertEquals(first.sessionId, second.sessionId)
                assertEquals(first.building, second.building)
                instrumentation.runOnMainSync {
                    AppRepositories.sensingAccounts.replaceAccount("sensing-fixture-a", listOf(session))
                }
                assertTrue(AppRepositories.travel.sensingTarget.value.generation > second.generation)
                assertTrue(AppRepositories.travel.sensingTarget.value.active)
                instrumentation.runOnMainSync { AppRepositories.sensingAccounts.replaceAccount(null, emptyList()) }
                await("Signout must stop foreground sensing") { !serviceRunning() }
                assertFalse(AppRepositories.travel.sensingTarget.value.active)
            }
        }
    }

    @Test
    fun stopAndFreshLaunchChoosePolicyWithoutTheOldNavigationOverride() {
        assumeTrue("Requires precise access", locationPermissionState(context).fine)
        assumeTrue("Mode probe reads the foreground notification", notificationsEnabled())
        withVisibleActivity {
            withFixtureAccount {
                repeat(3) { cycle ->
                    command(SensingForegroundService.ACTION_PRE_CLASS_MODE)
                    awaitNotification("Monitoring your trip to class")
                    val generation = AppRepositories.travel.sensingTarget.value.generation
                    assertTrue("Cycle ${cycle + 1}: override requires active sensing",
                        AppRepositories.travel.sensingTarget.value.active)
                    assertTrue("Cycle ${cycle + 1}: override requires a foreground service",
                        serviceRunning(foregroundOnly = true))
                    command(SensingForegroundService.ACTION_STOP)
                    awaitStopped()
                    assertFalse(AppRepositories.travel.sensingTarget.value.active)
                    // An empty action is also the policy path used by a START_STICKY recreation.
                    command(null)
                    await("Cycle ${cycle + 1}: fresh launch must activate a new sensing generation") {
                        val target = AppRepositories.travel.sensingTarget.value
                        target.active && target.generation > generation
                    }
                    val newGeneration = AppRepositories.travel.sensingTarget.value.generation
                    await("Cycle ${cycle + 1}: fresh launch must become a foreground service") {
                        serviceRunning(foregroundOnly = true)
                    }
                    awaitNotification("Background sensing — battery saving mode")
                    assertEquals("Cycle ${cycle + 1}: NORMAL must belong to the fresh sensing run",
                        newGeneration, AppRepositories.travel.sensingTarget.value.generation)
                    assertTrue(AppRepositories.travel.sensingTarget.value.active)
                    assertTrue(serviceRunning(foregroundOnly = true))
                }
            }
        }
    }

    @Test
    fun stoppingDuringRapidModeChangeDoesNotRepostForegroundNotification() {
        assumeTrue("Requires precise access", locationPermissionState(context).fine)
        assumeTrue("Mode probe reads the foreground notification", notificationsEnabled())
        withVisibleActivity {
            withFixtureAccount {
                awaitNotification("Background sensing — battery saving mode")
                // Queue both commands in one main-thread batch, leaving a mode publication
                // pending when STOP is delivered rather than waiting for the PRE_CLASS shade.
                instrumentation.runOnMainSync {
                    ContextCompat.startForegroundService(context,
                        Intent(context, SensingForegroundService::class.java)
                            .setAction(SensingForegroundService.ACTION_PRE_CLASS_MODE))
                    ContextCompat.startForegroundService(context,
                        Intent(context, SensingForegroundService::class.java)
                            .setAction(SensingForegroundService.ACTION_STOP))
                }
                instrumentation.waitForIdleSync()
                awaitStopped()
                fun assertRemainsStopped() {
                    assertFalse("Delayed mode publication must not restart the foreground service", serviceRunning())
                    assertNull("Delayed mode publication must not repost the foreground notification", notificationText())
                    assertFalse("Delayed mode publication must not reactivate the sensing target",
                        AppRepositories.travel.sensingTarget.value.active)
                }
                // Observe twice the production 500 ms publication interval, checking throughout
                // the pending-callback window instead of accepting only an immediate removal.
                val deadline = SystemClock.elapsedRealtime() + 1_000L
                while (SystemClock.elapsedRealtime() < deadline) {
                    assertRemainsStopped()
                    SystemClock.sleep(50L)
                }
                assertRemainsStopped()
            }
        }
    }

    @Test
    fun screenOffKeepsTheAuthorizedForegroundRun() {
        assumeTrue("Requires precise access", locationPermissionState(context).fine)
        withVisibleActivity {
            withFixtureAccount {
                command(SensingForegroundService.ACTION_PRE_CLASS_MODE)
                await("Trip request should have a foreground service") { serviceRunning(foregroundOnly = true) }
                val generation = AppRepositories.travel.sensingTarget.value.generation
                try {
                    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
                    SystemClock.sleep(6_000L) // Include one complete policy tick while the screen is off.
                    assertTrue(serviceRunning(foregroundOnly = true))
                    assertEquals(generation, AppRepositories.travel.sensingTarget.value.generation)
                } finally {
                    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
                    instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
                }
            }
        }
    }

    @Test
    fun signedOutPreciseAccessDoesNotAuthorizeSensing() {
        assumeTrue("Requires precise access", locationPermissionState(context).fine)
        withVisibleActivity {
            withFixtureAccount(accountId = null) {
                assertFalse(serviceRunning())
                command(SensingForegroundService.ACTION_PRE_CLASS_MODE)
                observePastForegroundDeadline()
                assertFalse("A navigation request cannot bypass the account gate", serviceRunning())
                assertFalse(AppRepositories.travel.sensingTarget.value.active)
            }
        }
    }

    private fun withFixtureAccount(
        sessions: List<CourseSession> = emptyList(),
        accountId: String? = "sensing-fixture-a",
        check: () -> Unit
    ) {
        // Supabase restores its session when MainActivity returns to the foreground. Installing
        // a fixture before that event lets the real cached account replace it mid-assertion.
        runBlocking {
            withTimeout(30_000L) {
                SupabaseProvider.client?.auth?.sessionStatus?.first { it != SessionStatus.Initializing }
                AppRepositories.sensingAccountReady.first { it }
            }
        }
        await("Visible-activity authentication restoration must finish", 30_000L) {
            AppRepositories.sensingAccounts.currentAccountId ==
                SupabaseProvider.client?.auth?.currentUserOrNull()?.id
        }
        stopAndAwaitDestroyed()
        val oldAccount = AppRepositories.sensingAccounts.currentAccountId
        val oldSessions = AppRepositories.sensingAccounts.currentSessions
        instrumentation.runOnMainSync {
            AppRepositories.sensingAccounts.replaceAccount(accountId, sessions)
        }
        instrumentation.waitForIdleSync()
        try {
            if (accountId != null) command(SensingForegroundService.ACTION_RECONCILE)
            check()
        } finally {
            // Waking the screen resumes MainActivity and restores cached auth. Finish this
            // test's Activity before stopping so its visible-start observer cannot requeue a
            // foreground service during teardown. The outer Activity cleanup is idempotent.
            finishTestActivities()
            try {
                stopAndAwaitDestroyed()
            } finally {
                instrumentation.runOnMainSync {
                    AppRepositories.sensingAccounts.replaceAccount(oldAccount, oldSessions)
                }
                instrumentation.waitForIdleSync()
            }
        }
    }

    private fun futureClass(): CourseSession {
        val start = ZonedDateTime.now(ZoneId.of("Australia/Melbourne")).plusHours(4)
        return CourseSession("shared-occurrence", "SENSING", "Sensing fixture", "PAR", "PAR-160",
            start, start.plusHours(1))
    }

    private fun command(action: String?) {
        instrumentation.runOnMainSync {
            ContextCompat.startForegroundService(context,
                Intent(context, SensingForegroundService::class.java).setAction(action))
        }
        instrumentation.waitForIdleSync()
    }

    private fun notificationsEnabled(): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun notificationText(): String? = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == 1001 }?.notification?.extras
        ?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun awaitNotification(text: String) {
        try {
            await("Foreground service should report: $text", 15_000L) { notificationText() == text }
        } catch (failure: AssertionError) {
            val target = AppRepositories.travel.sensingTarget.value
            throw AssertionError("${failure.message}; actualText=${notificationText()} " +
                "serviceActive=${serviceRunning()} foreground=${serviceRunning(foregroundOnly = true)} " +
                "targetActive=${target.active} generation=${target.generation}", failure)
        }
    }

    private fun stopAndAwaitDestroyed() {
        instrumentation.runOnMainSync { context.stopService(serviceIntent) }
        awaitStopped()
    }

    private fun finishTestActivities() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }
                .flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitStopped() = await("Foreground service and its sensing period must finish cleanup", 15_000L) {
        !serviceRunning() && !AppRepositories.travel.sensingTarget.value.active && notificationText() == null
    }

    private fun await(message: String, timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100L)
        instrumentation.waitForIdleSync()
        assertTrue(message, condition())
    }

    private fun withVisibleActivity(check: () -> Unit) {
        val launchIntent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var existing: Activity? = null
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            existing = Stage.values().filter { it != Stage.DESTROYED }
                .flatMap { monitor.getActivitiesInStage(it) }.firstOrNull { it is MainActivity }
        }
        // MainActivity is singleTask, so startActivitySync would wait forever if launch reused it.
        val activity = existing?.also {
            instrumentation.runOnMainSync { context.startActivity(launchIntent) }
        } ?: instrumentation.startActivitySync(launchIntent)
        try {
            instrumentation.waitForIdleSync()
            check()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            stopAndAwaitDestroyed()
        }
    }

    private fun observePastForegroundDeadline() {
        // AOSP Android 15 defaults fgs_start_foreground_timeout to 10 seconds.
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100L)
        instrumentation.waitForIdleSync()
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(foregroundOnly: Boolean = false): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).any {
            it.service.className == SensingForegroundService::class.java.name &&
                (!foregroundOnly || it.foreground)
        }
}
