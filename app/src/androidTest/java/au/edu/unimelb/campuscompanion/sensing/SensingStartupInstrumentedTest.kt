package au.edu.unimelb.campuscompanion.sensing

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import au.edu.unimelb.campuscompanion.MainActivity
import au.edu.unimelb.campuscompanion.sensing.location.locationPermissionState
import au.edu.unimelb.campuscompanion.sensing.service.SensingForegroundService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

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
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (!serviceRunning(foregroundOnly = true) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100L)
            }
            assertTrue("Precise access should permit foreground sensing", serviceRunning(foregroundOnly = true))
        }
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
            context.stopService(serviceIntent)
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
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
