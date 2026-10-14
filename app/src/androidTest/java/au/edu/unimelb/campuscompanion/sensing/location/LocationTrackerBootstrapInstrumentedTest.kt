package au.edu.unimelb.campuscompanion.sensing.location

import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises the real Android main Handler, Location objects and Play services Tasks with a
 * controlled FLP interface. These tests never request device location, change grants/accounts,
 * launch Maps, or alter the running app's sensing owner. Real-provider acquisition is a separate
 * emulator/device smoke test; the fake makes cancellation and late delivery deterministic here.
 */
@RunWith(AndroidJUnit4::class)
class LocationTrackerBootstrapInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun normalColdStartGetsOneFreshBoundedHighAccuracyFixAlongsideBalancedUpdates() = withTracker { tracker, fused ->
        onMain { tracker.startTracking(LocationTrackingMode.NORMAL) }
        val regular = fused.registrations.single()
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, regular.request.priority)
        assertEquals(60_000L, regular.request.intervalMillis)
        assertEquals(30_000L, regular.request.minUpdateIntervalMillis)
        assertSame(Looper.getMainLooper(), regular.looper)
        val bootstrap = fused.bootstraps.single()
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, bootstrap.request.priority)
        assertEquals(0L, bootstrap.request.maxUpdateAgeMillis)
        assertEquals(20_000L, bootstrap.request.durationMillis)

        val fix = fix()
        bootstrap.answer.setResult(fix)
        settle()

        assertEquals(fix.elapsedRealtimeNanos, tracker.location.value?.elapsedRealtimeNanos)
        assertEquals(fix.latitude, requireNotNull(tracker.location.value).latitude, 0.0)
        assertTrue("The balanced subscription remains registered after bootstrap", fused.removed.isEmpty())
        onMain { repeat(3) { tracker.startTracking(LocationTrackingMode.NORMAL) } }
        assertEquals(1, fused.registrations.size)
        assertEquals(1, fused.bootstraps.size)
    }

    @Test
    fun aNullBootstrapResultDoesNotRetryOrStopBalancedUpdates() = withTracker { tracker, fused ->
        onMain { tracker.startTracking() }
        fused.bootstraps.single().answer.setResult(null)
        settle()
        onMain { repeat(3) { tracker.startTracking() } }
        assertEquals(1, fused.bootstraps.size)
        assertEquals(1, fused.registrations.size)
        assertNull(tracker.location.value)
        assertTrue(fused.removed.isEmpty())

        onMain { fused.registrations.single().emit(fix()) }
        assertNotNull(tracker.location.value)
    }

    @Test
    fun aFailedBootstrapDoesNotRetryOrStopBalancedUpdates() = withTracker { tracker, fused ->
        onMain { tracker.startTracking() }
        fused.bootstraps.single().answer.setException(IllegalStateException("Test provider unavailable"))
        settle()
        onMain { repeat(3) { tracker.startTracking() } }
        assertEquals(1, fused.bootstraps.size)
        assertEquals(1, fused.registrations.size)
        assertNull(tracker.location.value)
        assertTrue(fused.removed.isEmpty())

        onMain { fused.registrations.single().emit(fix()) }
        assertNotNull(tracker.location.value)
    }

    @Test
    fun aProviderThatNeverCompletesIsCancelledAfterTwentySecondsWithoutRetry() = withTracker { tracker, fused ->
        val startedAt = SystemClock.elapsedRealtime()
        onMain { tracker.startTracking() }
        val bootstrap = fused.bootstraps.single()
        val cancelled = CountDownLatch(1)
        bootstrap.cancellation.onCanceledRequested { cancelled.countDown() }

        assertTrue("The local deadline must cancel a provider that never completes",
            cancelled.await(30, TimeUnit.SECONDS))
        assertTrue("The cold-start budget is twenty seconds", SystemClock.elapsedRealtime() - startedAt >= 20_000L)
        assertFalse("The fake provider never completed its task", bootstrap.answer.task.isComplete)
        assertTrue(bootstrap.cancellation.isCancellationRequested)
        assertNull(tracker.location.value)
        assertTrue("Timeout must leave balanced updates running", fused.removed.isEmpty())

        bootstrap.answer.setResult(fix())
        settle()
        assertNull("Completion after timeout cannot publish a position", tracker.location.value)
        onMain {
            tracker.startTracking()
            fused.registrations.single().emit(fix())
        }
        assertEquals(1, fused.bootstraps.size)
        assertNotNull(tracker.location.value)
    }

    @Test
    fun acceptedRegularFixCancelsBootstrapAndLateCompletionCannotOverrideIt() = withTracker { tracker, fused ->
        onMain { tracker.startTracking() }
        val bootstrap = fused.bootstraps.single()
        val regularFix = fix()
        onMain { fused.registrations.single().emit(regularFix) }
        assertTrue(bootstrap.cancellation.isCancellationRequested)
        val accepted = tracker.location.value
        assertNotNull(accepted)

        // Even a measured-later answer belongs to a bootstrap that was already cancelled.
        bootstrap.answer.setResult(fix(latitude = -37.7900))
        settle()

        assertEquals(accepted, tracker.location.value)
        assertTrue(fused.removed.isEmpty())
    }

    @Test
    fun stoppingBeforeAQueuedBootstrapListenerRunsCannotRestoreLocation() = withTracker { tracker, fused ->
        onMain { tracker.startTracking() }
        val registration = fused.registrations.single()
        val bootstrap = fused.bootstraps.single()
        onMain {
            bootstrap.answer.setResult(fix())
            tracker.stopTracking()
        }
        settle()
        assertTrue(bootstrap.cancellation.isCancellationRequested)
        assertNull(tracker.location.value)
        assertEquals(listOf(registration.callback), fused.removed)

        onMain { registration.emit(fix()) }
        assertNull("A queued regular callback also belongs to the stopped run", tracker.location.value)
    }

    @Test
    fun rapidRestartIgnoresBothCallbacksFromThePreviousRun() = withTracker { tracker, fused ->
        onMain { tracker.startTracking() }
        val oldBootstrap = fused.bootstraps.single()
        val oldRegistration = fused.registrations.single()
        onMain {
            tracker.stopTracking()
            tracker.startTracking()
        }
        assertTrue(oldBootstrap.cancellation.isCancellationRequested)
        assertEquals(2, fused.bootstraps.size)
        assertEquals(2, fused.registrations.size)

        oldBootstrap.answer.setResult(fix(latitude = -37.7900))
        onMain { oldRegistration.emit(fix(latitude = -37.7900)) }
        settle()
        assertNull(tracker.location.value)

        val current = fix()
        fused.bootstraps.last().answer.setResult(current)
        settle()
        assertEquals(current.elapsedRealtimeNanos, tracker.location.value?.elapsedRealtimeNanos)
        assertEquals(current.latitude, requireNotNull(tracker.location.value).latitude, 0.0)
    }

    @Test
    fun preClassKeepsItsExistingHighRateRequestAndNeedsNoBootstrap() = withTracker { tracker, fused ->
        onMain { tracker.startTracking(LocationTrackingMode.PRE_CLASS) }
        val request = fused.registrations.single().request
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, request.priority)
        assertEquals(10_000L, request.intervalMillis)
        assertEquals(5_000L, request.minUpdateIntervalMillis)
        assertTrue(fused.bootstraps.isEmpty())
        onMain { repeat(3) { tracker.startTracking(LocationTrackingMode.PRE_CLASS) } }
        assertEquals(1, fused.registrations.size)
        assertTrue(fused.bootstraps.isEmpty())
    }

    @Test
    fun switchingFromPreClassToNormalWithAFreshFixDoesNotBoostAgain() = withTracker { tracker, fused ->
        onMain {
            tracker.startTracking(LocationTrackingMode.PRE_CLASS)
            fused.registrations.single().emit(fix())
        }
        assertNotNull(tracker.location.value)

        onMain { tracker.startTracking(LocationTrackingMode.NORMAL) }

        assertEquals(2, fused.registrations.size)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, fused.registrations.last().request.priority)
        assertTrue("A fresh existing position does not need a cold-start bootstrap", fused.bootstraps.isEmpty())
    }

    @Test
    fun switchingFromPreClassToNormalWithoutAFixCanBootstrap() = withTracker { tracker, fused ->
        onMain {
            tracker.startTracking(LocationTrackingMode.PRE_CLASS)
            tracker.startTracking(LocationTrackingMode.NORMAL)
        }
        assertEquals(2, fused.registrations.size)
        assertEquals(1, fused.bootstraps.size)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, fused.registrations.last().request.priority)
    }

    @Test
    fun switchingToPreClassCancelsTheNormalBootstrapAndIgnoresItsAnswer() = withTracker { tracker, fused ->
        onMain { tracker.startTracking(LocationTrackingMode.NORMAL) }
        val bootstrap = fused.bootstraps.single()
        onMain { tracker.startTracking(LocationTrackingMode.PRE_CLASS) }
        assertTrue(bootstrap.cancellation.isCancellationRequested)
        assertEquals(1, fused.bootstraps.size)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, fused.registrations.last().request.priority)

        bootstrap.answer.setResult(fix())
        settle()
        assertNull(tracker.location.value)
        onMain { fused.registrations.last().emit(fix()) }
        assertNotNull(tracker.location.value)
    }

    @Test
    fun bootstrapAndRegularCallbacksShareOneMonotonicOrderingWatermark() = withTracker { tracker, fused ->
        onMain { tracker.startTracking() }
        val older = fix(latitude = -37.7900)
        val current = fix()
        fused.bootstraps.single().answer.setResult(current)
        settle()
        val accepted = tracker.location.value
        assertNotNull(accepted)

        onMain {
            fused.registrations.single().emit(older)
            fused.registrations.single().emit(Location(current).apply { latitude = -37.7900 })
        }
        assertEquals("Older and equal-time fixes cannot replace the accepted bootstrap", accepted, tracker.location.value)
        onMain { fused.registrations.single().emit(fix(latitude = -37.8050)) }
        assertEquals(-37.8050, requireNotNull(tracker.location.value).latitude, 0.0)
    }

    @Test
    fun unusableRegularFixesCannotCancelTheInitialAcquisition() = withTracker { tracker, fused ->
        val beforeActivation = fix(observedAt = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L)
        onMain { tracker.startTracking() }
        val registration = fused.registrations.single()
        val bootstrap = fused.bootstraps.single()
        val unusable = listOf(
            beforeActivation,
            fix(observedAt = 0),
            fix(observedAt = SystemClock.elapsedRealtimeNanos() - 31_000_000_000L),
            fix(observedAt = SystemClock.elapsedRealtimeNanos() + 60_000_000_000L),
            fix(accuracy = 80f),
            fix().apply { removeAccuracy() },
            fix(latitude = Double.NaN)
        )
        unusable.forEach { invalid ->
            onMain { registration.emit(invalid) }
            assertNull("Unusable evidence must not become the initial position", tracker.location.value)
            assertFalse("Unusable evidence must not cancel acquisition", bootstrap.cancellation.isCancellationRequested)
        }

        bootstrap.answer.setResult(fix())
        settle()
        assertNotNull(tracker.location.value)
    }

    @Test
    fun bootstrapUsesTheSameFreshnessAndAccuracyRulesAsRegularFixes() {
        val invalidFixes: List<() -> Location> = listOf(
            { fix(observedAt = 0) },
            { fix(observedAt = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L) },
            { fix(observedAt = SystemClock.elapsedRealtimeNanos() - 31_000_000_000L) },
            { fix(observedAt = SystemClock.elapsedRealtimeNanos() + 60_000_000_000L) },
            { fix(accuracy = 80f) },
            { fix().apply { removeAccuracy() } },
            { fix(latitude = Double.NaN) }
        )
        invalidFixes.forEach { invalid ->
            withTracker { tracker, fused ->
                onMain { tracker.startTracking() }
                fused.bootstraps.single().answer.setResult(invalid())
                settle()
                assertNull(tracker.location.value)
                onMain { tracker.startTracking() }
                assertEquals("A rejected answer must not start an acquisition loop", 1, fused.bootstraps.size)
                onMain { fused.registrations.single().emit(fix()) }
                assertNotNull(tracker.location.value)
            }
        }
    }

    @Test
    fun activeRegistrationFailureReachesTheCallerOnMainAndAllowsRetry() = withTracker { tracker, fused ->
        fused.completeRegistrationsImmediately = false
        val failures = mutableListOf<Exception>()
        val callbackLoopers = mutableListOf<Looper?>()
        onMain {
            tracker.startTracking(LocationTrackingMode.PRE_CLASS) {
                failures += it
                callbackLoopers += Looper.myLooper()
            }
        }
        val registration = fused.registrations.single()
        val failure = SecurityException("Test location permission changed")
        registration.answer.setException(failure)
        settle()

        assertEquals(1, failures.size)
        assertSame(failure, failures.single())
        assertEquals(listOf(Looper.getMainLooper()), callbackLoopers)
        assertEquals(listOf(registration.callback), fused.removed)
        assertNull(tracker.location.value)

        onMain { tracker.startTracking(LocationTrackingMode.PRE_CLASS) { failures += it } }
        val retried = fused.registrations.last()
        retried.answer.setResult(null)
        onMain { retried.emit(fix()) }
        assertEquals(2, fused.registrations.size)
        assertNotNull("A failed registration must not prevent the caller from retrying", tracker.location.value)
        assertEquals(1, failures.size)
    }

    @Test
    fun replacedRegistrationFailureCannotFailOrStopTheCurrentCaller() = withTracker { tracker, fused ->
        fused.completeRegistrationsImmediately = false
        val oldFailures = mutableListOf<Exception>()
        val currentFailures = mutableListOf<Exception>()
        onMain { tracker.startTracking(LocationTrackingMode.NORMAL) { oldFailures += it } }
        val old = fused.registrations.single()
        val oldBootstrap = fused.bootstraps.single()
        onMain { tracker.startTracking(LocationTrackingMode.PRE_CLASS) { currentFailures += it } }
        val current = fused.registrations.last()
        current.answer.setResult(null)
        onMain { current.emit(fix()) }
        val accepted = tracker.location.value
        assertNotNull(accepted)

        old.answer.setException(IllegalStateException("Test superseded request failed late"))
        settle()

        assertTrue("A replaced caller must not receive a stale failure", oldFailures.isEmpty())
        assertTrue("A stale failure must not mark current compass tracking failed", currentFailures.isEmpty())
        assertEquals(accepted, tracker.location.value)
        assertTrue(oldBootstrap.cancellation.isCancellationRequested)
        assertEquals(listOf(old.callback), fused.removed)
        onMain { current.emit(fix(latitude = -37.8050)) }
        assertEquals(-37.8050, requireNotNull(tracker.location.value).latitude, 0.0)
    }

    private fun withTracker(block: (LocationTracker, FakeFusedClient) -> Unit) {
        val fused = FakeFusedClient()
        val tracker = LocationTracker(fused.client)
        try {
            block(tracker, fused)
        } finally {
            onMain { tracker.stopTracking() }
            settle()
        }
    }

    private fun onMain(action: () -> Unit) {
        instrumentation.runOnMainSync { action() }
        settle()
    }

    private fun settle() = instrumentation.waitForIdleSync()

    private fun fix(
        latitude: Double = -37.8100,
        accuracy: Float = 10f,
        observedAt: Long = SystemClock.elapsedRealtimeNanos()
    ) = Location("controlled-flp").apply {
        this.latitude = latitude
        longitude = 144.9630
        this.accuracy = accuracy
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = observedAt
    }

    private class FakeFusedClient {
        data class Registration(
            val request: LocationRequest,
            val callback: LocationCallback,
            val looper: Looper,
            val answer: TaskCompletionSource<Void> = TaskCompletionSource()
        ) {
            fun emit(location: Location) = callback.onLocationResult(LocationResult.create(listOf(location)))
        }
        data class Bootstrap(
            val request: CurrentLocationRequest,
            val cancellation: CancellationToken,
            val answer: TaskCompletionSource<Location> = TaskCompletionSource()
        )

        var completeRegistrationsImmediately = true
        val registrations = mutableListOf<Registration>()
        val bootstraps = mutableListOf<Bootstrap>()
        val removed = mutableListOf<LocationCallback>()
        val client: FusedLocationProviderClient = Proxy.newProxyInstance(
            FusedLocationProviderClient::class.java.classLoader,
            arrayOf(FusedLocationProviderClient::class.java)
        ) { proxy, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "requestLocationUpdates" -> {
                    val registration = Registration(args[0] as LocationRequest, args[1] as LocationCallback, args[2] as Looper)
                    registrations += registration
                    if (completeRegistrationsImmediately) registration.answer.setResult(null)
                    registration.answer.task
                }
                "getCurrentLocation" -> {
                    val bootstrap = Bootstrap(args[0] as CurrentLocationRequest, args[1] as CancellationToken)
                    bootstraps += bootstrap
                    bootstrap.answer.task
                }
                "removeLocationUpdates" -> {
                    removed += args[0] as LocationCallback
                    Tasks.forResult<Void>(null)
                }
                "toString" -> "ControlledFusedLocationProviderClient"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                else -> error("Unexpected FLP method: ${method.name}")
            }
        } as FusedLocationProviderClient
    }
}
