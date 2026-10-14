package au.edu.unimelb.campuscompanion.sensing.service

import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.DepartureReminder
import au.edu.unimelb.campuscompanion.context.DepartureReminders
import au.edu.unimelb.campuscompanion.context.FakeBuildingLookup
import au.edu.unimelb.campuscompanion.context.TravelEngine
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.geo.GeoMath
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrendDetector
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Account retention through SDK loading transitions must cover both timing and delivery. */
class BackgroundDepartureAccountTest {
    @Test
    fun backgroundSdkLoadingRetainsCustomTimingAndBothReminderStagesExactlyOnce() = runBlocking {
        val fixture = Fixture()
        fixture.authenticate("A")
        val generation = fixture.engine.sensingTarget.value.generation

        // Auth 3.0.1 exposes no current user while its background lifecycle is Initializing.
        // This is not NotAuthenticated: the established account must not be replaced.
        fixture.sdkCurrentUserId = null
        assertNull(fixture.sdkCurrentUserId)
        assertEquals("A", fixture.accounts.currentAccountId)
        assertTrue(fixture.accounts.eligible.value)
        fixture.locateAndRefresh()
        val preparation = fixture.engine.snapshot.value
        assertEquals(7, preparation.leadMinutes)
        assertEquals(13, preparation.preparationMinutes)
        assertEquals(fixture.session.start.minusMinutes(27), preparation.leaveBy)
        assertEquals(ContextState.LEAVE_SOON, preparation.state)
        assertEquals(ContextState.LEAVE_SOON, fixture.reminders.consider(preparation)?.state)

        fixture.stationaryAtUrgentBoundary()
        val urgent = fixture.engine.snapshot.value
        assertEquals(ContextState.LEAVE_NOW, urgent.state)
        assertEquals(ContextState.LEAVE_NOW, fixture.reminders.consider(urgent)?.state)
        repeat(3) { assertNull(fixture.reminders.consider(urgent)) }
        fixture.reminders = fixture.coordinator()
        assertNull(fixture.reminders.consider(urgent))

        fixture.sdkCurrentUserId = "A"
        assertEquals(generation, fixture.engine.sensingTarget.value.generation)
        assertNull(fixture.reminders.consider(urgent))
        assertEquals(listOf(ContextState.LEAVE_SOON, ContextState.LEAVE_NOW),
            fixture.deliveries.map { it.reminder.state })
        assertTrue(fixture.deliveries.all { it.accountId == "A" })
        assertEquals(2, fixture.history.size)
        assertTrue(fixture.preferenceReads.all { it == "A" })
    }

    @Test
    fun unresolvedAndInitiallySignedOutAccountsCannotDeliverAnotherAccountsDueSnapshot() = runBlocking {
        val previous = Fixture()
        previous.authenticate("A")
        previous.locateAndRefresh()
        val oldDueSnapshot = previous.engine.snapshot.value
        assertEquals(ContextState.LEAVE_SOON, oldDueSnapshot.state)

        val fresh = Fixture()
        // An SDK user appearing before account/cache reconciliation is not authorization yet.
        fresh.sdkCurrentUserId = "A"
        assertFalse(fresh.accounts.ready.value)
        assertNull(fresh.accounts.currentAccountId)
        assertNull(fresh.reminders.consider(oldDueSnapshot))
        fresh.sdkCurrentUserId = null
        fresh.accounts.replaceAccount(null, listOf(fresh.session))
        assertTrue(fresh.accounts.ready.value)
        assertFalse(fresh.accounts.eligible.value)
        assertNull(fresh.reminders.consider(oldDueSnapshot))
        assertTrue(fresh.deliveries.isEmpty())
        assertTrue(fresh.history.isEmpty())
        assertTrue(fresh.preferenceReads.isEmpty())
    }

    @Test
    fun actualSignOutImmediatelyBlocksAlreadyComputedDueSnapshot() = runBlocking {
        val fixture = Fixture()
        fixture.authenticate("A")
        fixture.locateAndRefresh()
        val dueSnapshot = fixture.engine.snapshot.value
        assertEquals(ContextState.LEAVE_SOON, dueSnapshot.state)
        val readsBeforeSignOut = fixture.preferenceReads.size

        fixture.sdkCurrentUserId = null
        fixture.accounts.replaceAccount(null, emptyList())
        assertNull(fixture.accounts.currentAccountId)
        assertFalse(fixture.engine.sensingTarget.value.active)
        assertNull(fixture.reminders.consider(dueSnapshot))
        assertEquals(readsBeforeSignOut, fixture.preferenceReads.size)
        assertTrue(fixture.deliveries.isEmpty())
        assertTrue(fixture.history.isEmpty())
        fixture.engine.refresh()
        assertEquals(ContextState.NO_UPCOMING_CLASS, fixture.engine.snapshot.value.state)
    }

    @Test
    fun replacementAccountHonorsItsMuteAndHasIndependentOccurrenceHistory() = runBlocking {
        val fixture = Fixture()
        fixture.authenticate("A")
        fixture.locateAndRefresh()
        assertNotNull(fixture.reminders.consider(fixture.engine.snapshot.value))
        val accountAGeneration = fixture.engine.sensingTarget.value.generation

        fixture.authenticate("B")
        fixture.sdkCurrentUserId = null
        fixture.locateAndRefresh()
        val mutedSnapshot = fixture.engine.snapshot.value
        assertTrue(fixture.engine.sensingTarget.value.generation > accountAGeneration)
        assertEquals(ContextState.LEAVE_SOON, mutedSnapshot.state)
        assertEquals(0, mutedSnapshot.leadMinutes)
        assertEquals(30, mutedSnapshot.preparationMinutes)
        assertNull(fixture.reminders.consider(mutedSnapshot))
        assertEquals(1, fixture.deliveries.size)
        assertTrue(fixture.history.all { it.accountId == "A" })

        fixture.preferences["B"] = fixture.preferences.getValue("A")
        fixture.engine.refresh()
        assertNotNull(fixture.reminders.consider(fixture.engine.snapshot.value))
        fixture.reminders = fixture.coordinator()
        assertNull(fixture.reminders.consider(fixture.engine.snapshot.value))
        assertEquals(listOf("A", "B"), fixture.deliveries.map { it.accountId })

        fixture.authenticate("A")
        fixture.locateAndRefresh()
        assertNull(fixture.reminders.consider(fixture.engine.snapshot.value))
        assertEquals(2, fixture.deliveries.size)
        assertEquals(setOf("A", "B"), fixture.history.map { it.accountId }.toSet())
    }

    @Test
    fun queuedSnapshotCannotBorrowReplacementAccountsMatchingSettings() = runBlocking {
        val fixture = Fixture()
        fixture.authenticate("A")
        fixture.locateAndRefresh()
        val queuedAccountASnapshot = fixture.engine.snapshot.value
        assertEquals(ContextState.LEAVE_SOON, queuedAccountASnapshot.state)

        val accountBCourse = fixture.session.copy(id = "account-b-only", code = "COMP90015", title = "B lecture")
        fixture.preferences["B"] = fixture.preferences.getValue("A")
        fixture.authenticate("B", listOf(accountBCourse))
        fixture.sdkCurrentUserId = null
        fixture.locateAndRefresh()
        val currentAccountBSnapshot = fixture.engine.snapshot.value
        assertEquals(ContextState.LEAVE_SOON, currentAccountBSnapshot.state)
        assertEquals(queuedAccountASnapshot.leadMinutes, currentAccountBSnapshot.leadMinutes)
        assertEquals(queuedAccountASnapshot.preparationMinutes, currentAccountBSnapshot.preparationMinutes)
        val readsBeforeQueuedResult = fixture.preferenceReads.size

        assertNull(fixture.reminders.consider(queuedAccountASnapshot))
        assertEquals(readsBeforeQueuedResult, fixture.preferenceReads.size)
        assertTrue(fixture.deliveries.isEmpty())
        assertTrue(fixture.history.isEmpty())
        assertNotNull(fixture.reminders.consider(currentAccountBSnapshot))
        assertEquals("B", fixture.deliveries.single().accountId)
        assertEquals(accountBCourse.id, fixture.deliveries.single().reminder.sessionId)
        assertEquals(accountBCourse.id, fixture.history.single().sessionId)
    }

    @Test
    fun replacementBetweenOwnerLookupAndGuardRejectsTheCapturedOwner() = runBlocking {
        val fixture = Fixture()
        fixture.authenticate("A")
        fixture.locateAndRefresh()
        val dueSnapshot = fixture.engine.snapshot.value
        var snapshotChecked = false
        val reminders = DepartureReminders(
            snapshots = fixture.engine.snapshot,
            preferences = { throw AssertionError("A superseded account must not read reminder preferences") },
            notify = { throw AssertionError("A superseded account must not deliver a reminder") },
            currentUserId = {
                val capturedOwner = fixture.accounts.currentAccountId
                fixture.authenticate("B")
                capturedOwner
            },
            isCurrentSnapshot = {
                snapshotChecked = true
                it === fixture.engine.snapshot.value
            },
            withCurrentAccount = { accountId, action -> fixture.accounts.withCurrentAccount(accountId, action) }
        )

        assertNull(reminders.consider(dueSnapshot))
        assertEquals("B", fixture.accounts.currentAccountId)
        assertFalse(snapshotChecked)
        assertTrue(fixture.deliveries.isEmpty())
        assertTrue(fixture.history.isEmpty())
    }

    @Test
    fun replacementCleanupFinishesBeforeNewOwnersGuardedDeliveryCanRun() {
        val fixture = Fixture()
        fixture.authenticate("A")
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val deliveryAttempted = CountDownLatch(1)
        val deliveryRan = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()
        val workers = Executors.newFixedThreadPool(2) { action ->
            Thread(action, "background-departure-account-test").apply { isDaemon = true }
        }
        try {
            val replacement = workers.submit<Boolean> {
                fixture.accounts.replaceAccount("B", listOf(fixture.session)) {
                    cleanupStarted.countDown()
                    assertTrue("Replacement cleanup was never released", releaseCleanup.await(5, TimeUnit.SECONDS))
                    order += "cancel-old"
                }
                true
            }
            assertTrue("Account replacement never reached cleanup", cleanupStarted.await(2, TimeUnit.SECONDS))
            assertEquals("B", fixture.accounts.currentAccountId)
            val delivery = workers.submit<Boolean> {
                deliveryAttempted.countDown()
                fixture.accounts.withCurrentAccount("B") {
                    order += "deliver-B"
                    deliveryRan.countDown()
                    true
                } ?: false
            }
            assertTrue("New account delivery never attempted its guard", deliveryAttempted.await(2, TimeUnit.SECONDS))
            assertFalse("New reminder ran before old reminder cleanup completed", deliveryRan.await(100, TimeUnit.MILLISECONDS))
            assertTrue(order.isEmpty())

            releaseCleanup.countDown()
            assertTrue(replacement.get(2, TimeUnit.SECONDS))
            assertTrue(delivery.get(2, TimeUnit.SECONDS))
            assertEquals(listOf("cancel-old", "deliver-B"), order.toList())
        } finally {
            releaseCleanup.countDown()
            workers.shutdownNow()
            assertTrue("Account regression test leaked worker threads", workers.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private class Fixture {
        val initialNow = Instant.parse("2026-10-14T00:00:00Z")
        private var now = initialNow
        private var elapsed = 100_000_000_000L
        var sdkCurrentUserId: String? = null
        val session = CourseSession(
            id = "shared-occurrence", code = "COMP90018", title = "Lecture",
            location = "Parkville", room = "PAR-160",
            start = initialNow.plusSeconds(40 * 60).atZone(ZoneId.of("Australia/Melbourne")),
            end = initialNow.plusSeconds(100 * 60).atZone(ZoneId.of("Australia/Melbourne"))
        )
        val preferences = mutableMapOf(
            "A" to CourseReminderPreference(enabled = true, leadMinutes = 7, preparationMinutes = 13),
            "B" to CourseReminderPreference(enabled = false, leadMinutes = 4, preparationMinutes = 30)
        )
        val preferenceReads = mutableListOf<String>()
        val deliveries = mutableListOf<Delivery>()
        val history = mutableSetOf<HistoryKey>()
        private val trend = DistanceTrendDetector()
        private var trendGeneration = -1L
        private val point = FakeBuildingLookup.PETER_HALL.location.let {
            GeoPoint(it.latitude - 1200.0 / 111_000.0, it.longitude)
        }
        val engine = TravelEngine(
            eta = object : EtaRepository {
                override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode) =
                    Result.success(TravelEstimate(mode, 20 * 60L, 1500, EstimateSource.Routing, now))
            },
            buildings = FakeBuildingLookup(), preferences = { TravelPreferences() },
            reminderPreference = { reminderPreference(it) }, clock = { now }, elapsedNanos = { elapsed }
        )
        val accounts = SensingAccountLifecycle(engine)
        var reminders = coordinator()

        fun coordinator() = DepartureReminders(
            snapshots = engine.snapshot,
            preferences = ::reminderPreference,
            currentUserId = { accounts.currentAccountId },
            notify = { reminder ->
                val accountId = accounts.currentAccountId
                if (accountId == null) false else {
                    deliveries += Delivery(accountId, reminder)
                    true
                }
            },
            wasSent = { accountId, course, state ->
                HistoryKey(accountId, course.id, course.start.toInstant(), state) in history
            },
            rememberSent = { accountId, course, state ->
                history += HistoryKey(accountId, course.id, course.start.toInstant(), state)
            },
            isCurrentSnapshot = { it === engine.snapshot.value },
            withCurrentAccount = { accountId, action -> accounts.withCurrentAccount(accountId, action) }
        )

        private fun reminderPreference(@Suppress("UNUSED_PARAMETER") course: CourseSession): CourseReminderPreference {
            val accountId = accounts.currentAccountId ?: return CourseReminderPreference(enabled = false)
            preferenceReads += accountId
            return preferences.getValue(accountId)
        }

        fun authenticate(accountId: String, courses: List<CourseSession> = listOf(session)) {
            sdkCurrentUserId = accountId
            accounts.replaceAccount(accountId, courses)
            engine.startSensing()
        }

        suspend fun locateAndRefresh() {
            advance(1)
            fix()
            engine.refresh()
        }

        suspend fun stationaryAtUrgentBoundary() {
            // Four fresh observations span 30 seconds and end five minutes before leave-by.
            advance(450 - Duration.between(initialNow, now).seconds)
            repeat(4) { index ->
                if (index > 0) advance(10)
                fix()
            }
            engine.refresh()
        }

        private fun fix() {
            val target = engine.sensingTarget.value
            if (target.generation != trendGeneration) {
                trend.reset()
                trendGeneration = target.generation
            }
            val observedTrend = trend.update(
                GeoMath.distanceMeters(point, requireNotNull(target.building).location), elapsed, 10f
            )
            assertTrue(engine.updateEvidence(SensingEvidence(
                generation = target.generation,
                location = LocationSample(point.latitude, point.longitude, 10f, now.toEpochMilli(), elapsed),
                distanceTrend = observedTrend,
                noProgressConfirmed = trend.noProgressConfirmed()
            )))
        }

        private fun advance(seconds: Long) {
            require(seconds >= 0)
            now = now.plusSeconds(seconds)
            elapsed += seconds * 1_000_000_000L
        }
    }

    private data class Delivery(val accountId: String, val reminder: DepartureReminder)
    private data class HistoryKey(val accountId: String, val sessionId: String, val start: Instant, val state: ContextState)
}
