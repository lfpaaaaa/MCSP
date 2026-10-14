package au.edu.unimelb.campuscompanion.sensing.location

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.service.SensingForegroundService
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual Play services registration/removal, without claiming simulated entry delivery. */
@RunWith(AndroidJUnit4::class)
class GeofenceManagerInstrumentedTest {
    @Test
    fun permittedPlayServicesRegistrationAndRemovalCompleteAsynchronously() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Requires precise and background location", locationPermissionState(context).canMonitorBackgroundArrival)
        assumeTrue("Requires Google Play services", GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS)
        instrumentation.runOnMainSync { context.stopService(Intent(context, SensingForegroundService::class.java)) }
        val manager = GeofenceManager(context)
        try {
            instrumentation.runOnMainSync { manager.setTarget(target(1)) }
            val registered = awaitRegistration(manager)
            assertEquals("Actual add task must succeed: ${registered.detail}",
                GeofenceRegistrationStatus.REGISTERED, registered.status)
            assertNotNull(registered.requestId)
            instrumentation.runOnMainSync { manager.stop() }
            val removed = awaitRemoval(manager)
            assertEquals("Actual removal task must succeed: ${removed.detail}",
                GeofenceRegistrationStatus.REMOVED, removed.status)
            assertNull(removed.requestId)
        } finally {
            instrumentation.runOnMainSync { manager.stop() }
        }
    }

    @Test
    fun stoppedOwnersCleanupCannotReclaimAndStopReplacementOwner() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Requires precise and background location", locationPermissionState(context).canMonitorBackgroundArrival)
        assumeTrue("Requires Google Play services", GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS)
        instrumentation.runOnMainSync { context.stopService(Intent(context, SensingForegroundService::class.java)) }
        val old = GeofenceManager(context)
        val replacement = GeofenceManager(context)
        try {
            instrumentation.runOnMainSync { old.setTarget(target(2)) }
            assertEquals(GeofenceRegistrationStatus.REGISTERED, awaitRegistration(old).status)
            instrumentation.runOnMainSync {
                old.stop()
                replacement.setTarget(target(3))
            }
            val registered = awaitRegistration(replacement)
            assertEquals("Old cleanup must not stop the new owner: ${registered.detail}",
                GeofenceRegistrationStatus.REGISTERED, registered.status)
            assertEquals(3L, registered.generation)
            instrumentation.runOnMainSync { replacement.stop() }
            assertEquals(GeofenceRegistrationStatus.REMOVED, awaitRemoval(replacement).status)
        } finally {
            instrumentation.runOnMainSync {
                old.stop()
                replacement.stop()
            }
        }
    }

    private suspend fun awaitRegistration(manager: GeofenceManager): GeofenceRegistrationState = withTimeout(45_000L) {
        manager.registration.first {
            it.status in setOf(GeofenceRegistrationStatus.REGISTERED, GeofenceRegistrationStatus.DENIED,
                GeofenceRegistrationStatus.UNAVAILABLE, GeofenceRegistrationStatus.FAILED)
        }
    }

    private suspend fun awaitRemoval(manager: GeofenceManager): GeofenceRegistrationState = withTimeout(45_000L) {
        manager.registration.first {
            it.status in setOf(GeofenceRegistrationStatus.REMOVED, GeofenceRegistrationStatus.DENIED,
                GeofenceRegistrationStatus.UNAVAILABLE, GeofenceRegistrationStatus.FAILED)
        }
    }

    private fun target(generation: Long) = SensingTarget(
        generation, SystemClock.elapsedRealtimeNanos(), true, "instrumented-$generation",
        BuildingLocation("TEST-$generation", "1", "P", "Test arrival", "", GeoPoint(-37.798, 144.960))
    )
}
