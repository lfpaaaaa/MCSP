package au.edu.unimelb.campuscompanion.sensing.location

import android.app.ActivityManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import au.edu.unimelb.campuscompanion.MainActivity
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.sensing.service.SensingForegroundService
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.GeofenceStatusCodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Real Play services add/remove plus the manager -> shared TravelEngine adapter path used by the
 * receiver. Trigger samples are supplied by this test: this does not prove INITIAL_TRIGGER_ENTER
 * broadcast delivery, platform trigger-location age, or physical arrival reliability.
 * Configure precise/background access before instrumentation; this test changes no app grants.
 */
@RunWith(AndroidJUnit4::class)
class GeofenceAdmissionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun currentSuccessfulRegistrationAdmitsPreActivationFixAtStartupAndSameBuildingHandover() = runBlocking {
        withRegisteredFixture { manager, fixture, initialTarget, initialRegistration ->
            val initialId = requireNotNull(initialRegistration.requestId)
            val trigger = preActivationSample(initialTarget)
            lateinit var initialEntry: GeofenceEntryEvent
            lateinit var result: GeofenceEntryResult
            instrumentation.runOnMainSync {
                initialEntry = requireNotNull(manager.entry(initialId, SystemClock.elapsedRealtimeNanos(), trigger))
                assertEquals(initialTarget.generation, initialEntry.generation)
                result = AppRepositories.travel.reportGeofenceEntered(initialEntry)
            }
            assertTrue("Fresh already-inside fix should be admitted: ${result.rejectionReason}", result.accepted)
            assertNull(result.rejectionReason)
            assertTrue(result.triggerBeforeActivation)
            assertTrue(requireNotNull(result.triggerAgeAtReceiptMillis) >= 2_000L)
            assertTrue(requireNotNull(result.triggerAgeMillis) >= requireNotNull(result.triggerAgeAtReceiptMillis))
            assertEquals(10f, result.triggerAccuracyMeters)
            assertEquals(ContextState.ARRIVED, AppRepositories.travel.snapshot.value.state)
            instrumentation.runOnMainSync {
                assertEquals(GeofenceRejectionReason.DUPLICATE,
                    AppRepositories.travel.reportGeofenceEntered(initialEntry).rejectionReason)
            }

            // A distinct class occurrence in the same building must create a new scoped ID.
            val next = fixture.copy(id = "geofence-next-class", title = "Next fixture class")
            lateinit var nextTarget: SensingTarget
            instrumentation.runOnMainSync {
                assertTrue(AppRepositories.sensingAccounts.updateTimetable(FIXTURE_ACCOUNT, listOf(next)))
                nextTarget = AppRepositories.travel.sensingTarget.value
                assertNotEquals(initialTarget.generation, nextTarget.generation)
                assertEquals(initialTarget.building, nextTarget.building)
                assertFalse(AppRepositories.travel.snapshot.value.state == ContextState.ARRIVED)
                manager.setTarget(nextTarget)
                assertNull(manager.entry(initialId, SystemClock.elapsedRealtimeNanos(), trigger))
                assertEquals(GeofenceRejectionReason.WRONG_GENERATION,
                    AppRepositories.travel.reportGeofenceEntered(initialEntry.copy(
                        receivedAtNanos = SystemClock.elapsedRealtimeNanos()
                    )).rejectionReason)
            }
            val nextRegistration = awaitRegistered(manager, nextTarget.generation)
            val nextId = requireNotNull(nextRegistration.requestId)
            assertNotEquals(initialId, nextId)
            instrumentation.runOnMainSync {
                assertNull(manager.entry(initialId, SystemClock.elapsedRealtimeNanos(), trigger))
                val nextEntry = requireNotNull(manager.entry(nextId, SystemClock.elapsedRealtimeNanos(), preActivationSample(nextTarget)))
                val nextResult = AppRepositories.travel.reportGeofenceEntered(nextEntry)
                assertTrue("Same-building initial entry should arrive: ${nextResult.rejectionReason}", nextResult.accepted)
                assertTrue(nextResult.triggerBeforeActivation)
                assertEquals(ContextState.ARRIVED, AppRepositories.travel.snapshot.value.state)
                assertEquals(next.id, AppRepositories.travel.snapshot.value.session?.id)
            }
        }
    }

    @Test
    fun unavailableDeliveryInvalidatesCurrentIdAndRetriesOnlyAfterTheRealCooldown() = runBlocking {
        withRegisteredFixture { manager, _, target, registration ->
            val oldId = requireNotNull(registration.requestId)
            val trigger = preActivationSample(target)
            // Synthetic adapter error, not a reproduced Play services/provider outage.
            instrumentation.runOnMainSync {
                manager.deliveryFailed(GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE)
                assertEquals(GeofenceRegistrationStatus.UNAVAILABLE, manager.registration.value.status)
                assertNull(manager.entry(oldId, SystemClock.elapsedRealtimeNanos(), trigger))
                repeat(3) {
                    manager.setTarget(target)
                    assertEquals("Repeated lifecycle checks must respect the retry cooldown",
                        GeofenceRegistrationStatus.UNAVAILABLE, manager.registration.value.status)
                    assertNull(manager.entry(oldId, SystemClock.elapsedRealtimeNanos(), trigger))
                }
            }
            // Wait on the instrumentation coroutine, leaving Android's main thread free for
            // actual remove completion. No clock injection or production timing hooks.
            delay(60_100L)
            instrumentation.runOnMainSync { manager.setTarget(target) }
            val recovered = awaitRegistered(manager, target.generation)
            val newId = requireNotNull(recovered.requestId)
            assertNotEquals("Recovery must not reuse the invalidated registration ID", oldId, newId)
            instrumentation.runOnMainSync {
                assertNull(manager.entry(oldId, SystemClock.elapsedRealtimeNanos(), trigger))
                assertNotNull(manager.entry(newId, SystemClock.elapsedRealtimeNanos(), trigger))
            }
        }
    }

    private suspend fun withRegisteredFixture(
        check: suspend (GeofenceManager, CourseSession, SensingTarget, GeofenceRegistrationState) -> Unit
    ) {
        assumeTrue("Requires precise and background location", locationPermissionState(context).canMonitorBackgroundArrival)
        assumeTrue("Requires Google Play services", GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS)
        withTimeout(30_000L) { AppRepositories.sensingAccountReady.first { it } }
        finishActivitiesAndStopService()
        val oldAccount = AppRepositories.sensingAccounts.currentAccountId
        val oldSessions = AppRepositories.sensingAccounts.currentSessions
        val manager = GeofenceManager(context)
        try {
            val fixture = futureClass()
            lateinit var target: SensingTarget
            instrumentation.runOnMainSync {
                AppRepositories.sensingAccounts.replaceAccount(FIXTURE_ACCOUNT, listOf(fixture))
                target = AppRepositories.travel.startSensing()
                manager.setTarget(target)
            }
            check(manager, fixture, target, awaitRegistered(manager, target.generation))
        } finally {
            try {
                instrumentation.runOnMainSync {
                    manager.stop()
                    AppRepositories.travel.stopSensing()
                }
                withTimeout(45_000L) {
                    val removed = manager.registration.first {
                        it.status in setOf(GeofenceRegistrationStatus.REMOVED, GeofenceRegistrationStatus.FAILED,
                            GeofenceRegistrationStatus.DENIED, GeofenceRegistrationStatus.UNAVAILABLE)
                    }
                    assertEquals("Fixture geofence cleanup must succeed: ${removed.detail}",
                        GeofenceRegistrationStatus.REMOVED, removed.status)
                }
            } finally {
                instrumentation.runOnMainSync { AppRepositories.sensingAccounts.replaceAccount(oldAccount, oldSessions) }
                instrumentation.waitForIdleSync()
            }
        }
    }

    private suspend fun awaitRegistered(manager: GeofenceManager, generation: Long): GeofenceRegistrationState =
        withTimeout(45_000L) {
            val result = manager.registration.first {
                it.generation == generation && it.status in setOf(GeofenceRegistrationStatus.REGISTERED,
                    GeofenceRegistrationStatus.DENIED, GeofenceRegistrationStatus.UNAVAILABLE, GeofenceRegistrationStatus.FAILED)
            }
            assertEquals("Actual Play services registration must succeed: ${result.detail}",
                GeofenceRegistrationStatus.REGISTERED, result.status)
            result
        }

    private fun preActivationSample(target: SensingTarget): LocationSample {
        val building = requireNotNull(target.building)
        val observed = target.activatedAtNanos - 2_000_000_000L
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - observed) / 1_000_000L
        return LocationSample(building.location.latitude, building.location.longitude, 10f,
            System.currentTimeMillis() - ageMillis, observed)
    }

    private fun futureClass(): CourseSession {
        val start = ZonedDateTime.now(ZoneId.of("Australia/Melbourne")).plusMinutes(30)
        return CourseSession("geofence-initial-class", "GEOFENCE", "Arrival fixture", "PAR", "PAR-160",
            start, start.plusHours(1))
    }

    private fun finishActivitiesAndStopService() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }
                .flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<MainActivity>()
                .distinct().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { context.stopService(Intent(context, SensingForegroundService::class.java)) }
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        val serviceName = SensingForegroundService::class.java.name
        fun stopped(): Boolean {
            @Suppress("DEPRECATION")
            val running = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
                .any { it.service.className == serviceName }
            return !running && !AppRepositories.travel.sensingTarget.value.active
        }
        while (!stopped() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100L)
        assertTrue("Previous activity/service must finish before installing fixture account", stopped())
    }

    companion object {
        private const val FIXTURE_ACCOUNT = "geofence-admission-fixture"
    }
}
