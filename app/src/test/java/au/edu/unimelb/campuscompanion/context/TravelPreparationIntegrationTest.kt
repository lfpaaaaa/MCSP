package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Saved settings, asynchronous estimates, physical callbacks and notifications share the same timing. */
class TravelPreparationIntegrationTest {
    @Test
    fun newPreferencesAndTheEngineFallbackBothUseZeroArrivalAndFivePreparation() = runBlocking<Unit> {
        listOf(true, false).forEach { useSavedPreference ->
            val trip = Trip(useSavedPreference)
            trip.now = at(14, 30).minusSeconds(1)
            trip.engine.refresh()
            assertEquals(0, trip.engine.snapshot.value.leadMinutes)
            assertEquals(5, trip.engine.snapshot.value.preparationMinutes)
            assertEquals(at(14, 35), trip.engine.snapshot.value.leaveBy?.toInstant())
            assertEquals(ContextState.UPCOMING, trip.engine.snapshot.value.state)
            trip.now = at(14, 30)
            trip.engine.refresh()
            assertEquals(ContextState.LEAVE_SOON, trip.engine.snapshot.value.state)
        }
    }

    @Test
    fun savedPreparationOverridesTheLegacyConfigAndIsClampedWithArrival() = runBlocking<Unit> {
        val trip = Trip(config = ContextConfig(leaveSoonWindowMinutes = 2))
        trip.preference = CourseReminderPreference(leadMinutes = 90, preparationMinutes = 90)
        trip.now = at(13, 35)
        trip.engine.refresh()
        assertEquals(30, trip.engine.snapshot.value.leadMinutes)
        assertEquals(30, trip.engine.snapshot.value.preparationMinutes)
        assertEquals(at(14, 5), trip.engine.snapshot.value.leaveBy?.toInstant())
        assertEquals(ContextState.LEAVE_SOON, trip.engine.snapshot.value.state)

        trip.preference = CourseReminderPreference(leadMinutes = -1, preparationMinutes = -1)
        trip.now = at(14, 35).minusSeconds(1)
        trip.engine.refresh()
        assertEquals(0, trip.engine.snapshot.value.leadMinutes)
        assertEquals(0, trip.engine.snapshot.value.preparationMinutes)
        assertEquals(ContextState.UPCOMING, trip.engine.snapshot.value.state)
        trip.now = at(14, 35)
        trip.engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, trip.engine.snapshot.value.state)
    }

    @Test
    fun reducingPreparationDuringEtaCannotDeliverTheOldEarlierWarning() = runBlocking<Unit> {
        val trip = Trip()
        trip.preference = CourseReminderPreference(leadMinutes = 5, preparationMinutes = 20)
        trip.now = at(14, 10)
        trip.gateEta = true
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { trip.engine.refresh() }
        val answer = withTimeout(TIMEOUT) { trip.etaRequests.receive() }
        trip.preference = trip.preference.copy(preparationMinutes = 5)
        answer.complete(Unit)
        withTimeout(TIMEOUT) { refresh.await() }

        // The snapshot identifies its captured settings; the notifier compares both against saved values.
        assertEquals(20, trip.engine.snapshot.value.preparationMinutes)
        assertEquals(ContextState.LEAVE_SOON, trip.engine.snapshot.value.state)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        assertTrue(trip.sent.isEmpty())
        trip.gateEta = false
        trip.engine.refresh()
        assertEquals(5, trip.engine.snapshot.value.preparationMinutes)
        assertEquals(at(14, 30), trip.engine.snapshot.value.leaveBy?.toInstant())
        assertEquals(ContextState.UPCOMING, trip.engine.snapshot.value.state)

        trip.now = at(14, 25)
        trip.engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, trip.reminders.consider(trip.engine.snapshot.value)?.state)
        assertEquals(1, trip.sent.size)
    }

    @Test
    fun increasingPreparationUpdatesTheRunningPipelineWithoutAnotherFix() = runBlocking<Unit> {
        val trip = Trip()
        trip.preference = CourseReminderPreference(leadMinutes = 5, preparationMinutes = 5)
        trip.now = at(14, 10)
        val notifications = Channel<DepartureReminder>(Channel.UNLIMITED)
        val reminders = DepartureReminders(
            snapshots = trip.engine.snapshot, preferences = { trip.preference },
            preferenceChanges = trip.preferenceChanges.map { Unit },
            notify = { notifications.trySend(it).isSuccess }
        )
        val engineJob = launch { trip.engine.run() }
        val reminderJob = launch { reminders.run() }
        try {
            val initial = withTimeout(TIMEOUT) { trip.engine.snapshot.first { !it.pending } }
            assertEquals(ContextState.UPCOMING, initial.state)
            assertEquals(5, initial.preparationMinutes)
            assertTrue(notifications.tryReceive().isFailure)

            trip.preference = trip.preference.copy(preparationMinutes = 20)
            trip.preferenceChanges.value++
            val notification = withTimeout(TIMEOUT) { notifications.receive() }
            assertEquals(ContextState.LEAVE_SOON, notification.state)
            assertEquals(20, trip.engine.snapshot.value.preparationMinutes)
            assertEquals(initial.leaveBy, trip.engine.snapshot.value.leaveBy)
        } finally {
            engineJob.cancelAndJoin()
            reminderJob.cancelAndJoin()
            notifications.close()
        }
    }

    @Test
    fun sensorAndEvidenceClearingCallbacksKeepThePreparationUsedByTheirGuidance() = runBlocking<Unit> {
        val trip = Trip()
        trip.preference = CourseReminderPreference(leadMinutes = 5, preparationMinutes = 20)
        trip.now = at(14, 10)
        trip.engine.startSensing()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.engine.refresh()
        assertEquals(ContextState.LEAVE_SOON, trip.engine.snapshot.value.state)

        trip.gateEta = true
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { trip.engine.refresh() }
        val answer = withTimeout(TIMEOUT) { trip.etaRequests.receive() }
        trip.preference = trip.preference.copy(preparationMinutes = 0)
        trip.elapsed += 1_000_000_000L
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        assertEquals(20, trip.engine.snapshot.value.preparationMinutes)
        trip.engine.clearSensingEvidence()
        assertEquals(20, trip.engine.snapshot.value.preparationMinutes)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        answer.complete(Unit)
        withTimeout(TIMEOUT) { refresh.await() }
        assertEquals(20, trip.engine.snapshot.value.preparationMinutes)

        trip.gateEta = false
        trip.engine.refresh()
        assertEquals(0, trip.engine.snapshot.value.preparationMinutes)
        assertEquals(ContextState.UPCOMING, trip.engine.snapshot.value.state)
        assertEquals(at(14, 30), trip.engine.snapshot.value.leaveBy?.toInstant())
    }

    @Test
    fun zeroPreparationStillAllowsMovementAndArrivalToSuppressTheWarning() = runBlocking<Unit> {
        val trip = Trip()
        trip.preference = CourseReminderPreference(leadMinutes = 5, preparationMinutes = 0)
        trip.now = at(14, 30)
        trip.engine.updateEnRouteSignal(true)
        trip.engine.refresh()
        assertEquals(ContextState.EN_ROUTE, trip.engine.snapshot.value.state)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        trip.engine.updateOrigin(FakeBuildingLookup.PETER_HALL.location)
        trip.engine.refresh()
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        assertTrue(trip.sent.isEmpty())
    }

    private class Trip(useSavedPreference: Boolean = true, config: ContextConfig = ContextConfig()) {
        var now: Instant = at(14, 0)
        var elapsed = 100_000_000_000L
        var preference = CourseReminderPreference()
        var gateEta = false
        val preferenceChanges = MutableStateFlow(0)
        val etaRequests = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
        val sent = mutableListOf<DepartureReminder>()
        val lecture = CourseSession(
            id = "lecture", code = "COMP90018", title = "Mobile Computing", activity = "lecture",
            location = "Parkville", room = "PAR-160", start = at(15, 0).atZone(ZONE), end = at(16, 0).atZone(ZONE)
        )
        val engine = TravelEngine(
            eta = object : EtaRepository {
                override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): Result<TravelEstimate> {
                    if (gateEta) {
                        val answer = CompletableDeferred<Unit>()
                        etaRequests.send(answer)
                        answer.await()
                    }
                    return Result.success(TravelEstimate(mode, 25 * 60L, 1_500, EstimateSource.Routing, now))
                }
            },
            buildings = FakeBuildingLookup(), preferences = { TravelPreferences() }, clock = { now },
            elapsedNanos = { elapsed }, tickInterval = Duration.ofHours(1),
            reminderPreference = if (useSavedPreference) ({ preference }) else null,
            reminderChanges = preferenceChanges.map { Unit }, config = config
        )
        val reminders = DepartureReminders(engine.snapshot, { preference }, sent::add)

        init {
            engine.updateSessions(listOf(lecture))
            engine.updateOrigin(AWAY)
        }

        fun evidence() = SensingEvidence(
            generation = engine.sensingTarget.value.generation,
            location = LocationSample(AWAY.latitude, AWAY.longitude, 10f, now.toEpochMilli(), elapsed)
        )
    }

    private companion object {
        const val TIMEOUT = 5_000L
        val ZONE: ZoneId = ZoneId.of("Australia/Melbourne")
        val AWAY = GeoPoint(-37.8100, 144.9630)
        fun at(hour: Int, minute: Int): Instant =
            ZonedDateTime.of(2026, 10, 5, hour, minute, 0, 0, ZONE).toInstant()
    }
}
