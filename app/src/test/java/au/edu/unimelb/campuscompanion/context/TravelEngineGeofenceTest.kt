package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceEntryEvent
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.location.SensingEvidence
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Events admitted by the registration guard must still satisfy the engine's physical contract. */
class TravelEngineGeofenceTest {
    @Test
    fun aCurrentEntryEstablishesArrivalAndWithdrawsTheDeparturePromptImmediately() = runBlocking {
        val trip = Trip()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.engine.refresh()
        assertNotNull(trip.reminders.consider(trip.engine.snapshot.value))
        val key = trip.shade.shownKeys().single()

        trip.advance(10)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry()))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        assertNull(trip.engine.snapshot.value.distanceMeters)
        assertEquals(trip.home, trip.engine.snapshot.value.estimateOrigin)
        assertNull(trip.reminders.consider(trip.engine.snapshot.value))
        assertEquals(listOf(key), trip.shade.withdrawn)
        assertTrue(trip.shade.shownKeys().isEmpty())

        trip.engine.refresh()
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        trip.reminders.consider(trip.engine.snapshot.value)
        assertEquals(1, trip.shade.alerts.size)
        assertTrue(trip.shade.shownKeys().isEmpty())
    }

    @Test
    fun newerEntrySupersedesOlderGpsButOnlyLaterFreshGpsCanExitArrival() = runBlocking {
        val trip = Trip()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.engine.refresh()
        trip.advance(10)
        val entry = trip.entry()
        val entryObserved = entry.triggerLocation!!.elapsedRealtimeNanos
        assertTrue(trip.engine.reportGeofenceEntered(entry))

        // The earlier far-away fix is still younger than 30 seconds. A tick must not reuse it.
        trip.advance(5)
        trip.engine.refresh()
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        assertNull(trip.engine.snapshot.value.distanceMeters)
        assertFalse(trip.engine.updateEvidence(trip.evidence(observed = entryObserved)))
        assertFalse(trip.engine.updateEvidence(trip.evidence(observed = entryObserved - 1)))

        val doorwayJitter = trip.building.location.copy(latitude = trip.building.location.latitude + 0.0012)
        assertTrue(trip.engine.updateEvidence(trip.evidence(doorwayJitter)))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        trip.advance(1)
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
    }

    @Test
    fun duplicatesCannotUndoExitButANewerRealEntryAtTheSameRegistrationCanArriveAgain() {
        val trip = Trip()
        val first = trip.entry()
        assertTrue(trip.engine.reportGeofenceEntered(first))
        assertFalse(trip.engine.reportGeofenceEntered(first))
        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)

        trip.advance(40)
        assertFalse(trip.engine.reportGeofenceEntered(first.copy(receivedAtNanos = trip.elapsed)))
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(requestId = first.requestId)))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun delayedEntryCannotOverrideANewerFarFixEvenAfterThatFixExpires() = runBlocking {
        val trip = Trip()
        val earlierEntry = trip.entry()
        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.advance(40)
        trip.engine.refresh()
        assertNull(trip.engine.snapshot.value.distanceMeters)
        assertFalse(trip.engine.reportGeofenceEntered(earlierEntry.copy(receivedAtNanos = trip.elapsed)))
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
    }

    @Test
    fun aTriggerNoNewerThanAnAcceptedFarFixIsRejected() {
        val trip = Trip()
        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        assertFalse(trip.engine.reportGeofenceEntered(trip.entry()))
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
    }

    @Test
    fun delayedEntryWithNewerConsistentGpsRemainsUsable() {
        val trip = Trip()
        val entry = trip.entry()
        trip.advance(10)
        val insideExitRadius = trip.building.location.copy(latitude = trip.building.location.latitude + 0.0012)
        assertTrue(trip.engine.updateEvidence(trip.evidence(insideExitRadius)))
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
        assertTrue(trip.engine.reportGeofenceEntered(entry.copy(receivedAtNanos = trip.elapsed)))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun nullTriggerCanEstablishArrivalWithoutGpsButCannotBeReplayedAfterExit() {
        val trip = Trip()
        val entry = trip.entry(trigger = null)
        assertTrue(trip.engine.reportGeofenceEntered(entry))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.advance(40)
        assertFalse(trip.engine.reportGeofenceEntered(entry.copy(receivedAtNanos = trip.elapsed)))
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
    }

    @Test
    fun nullTriggerRejectsKnownAwayPositionEvenWhenThatPositionHasExpired() = runBlocking {
        val trip = Trip()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.advance(40)
        trip.engine.refresh()
        assertFalse(trip.engine.reportGeofenceEntered(trip.entry(trigger = null)))
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
    }

    @Test
    fun aTriggerExactlyThreeMinutesOldIsStillAccepted() {
        val trip = Trip()
        val trigger = trip.sample()
        trip.advance(180)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(trigger = trigger)))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun triggerAfterReceiptIsRejectedEvenWhenItIsNoLongerFutureAtProcessingTime() {
        val trip = Trip()
        val receivedAt = trip.elapsed
        trip.advance(1)
        val impossibleEntry = trip.entry().copy(receivedAtNanos = receivedAt)
        trip.advance(1)
        assertFalse(trip.engine.reportGeofenceEntered(impossibleEntry))
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)

        // Rejection must not consume the registration or advance its physical-observation watermark.
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(trigger = trip.sample(observed = receivedAt))))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun geofenceTriggerAccuracyDoesNotRelaxTheSeparatePreciseGpsGate() {
        val trip = Trip()
        val trigger = trip.sample(accuracy = 120f)
        assertFalse(trip.engine.updateEvidence(SensingEvidence(trip.engine.sensingTarget.value.generation, trigger)))
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(trigger = trigger)))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun oldFuturePreActivationAndInvalidTriggerMeasurementsCannotEstablishArrival() {
        val mutations: List<(Trip, LocationSample) -> LocationSample> = listOf(
            { trip, sample -> sample.copy(elapsedRealtimeNanos = trip.elapsed - 180_000_000_001L) },
            { trip, sample -> sample.copy(elapsedRealtimeNanos = trip.elapsed + 1) },
            { trip, sample -> sample.copy(elapsedRealtimeNanos = trip.engine.sensingTarget.value.activatedAtNanos - 1) },
            { _, sample -> sample.copy(latitude = Double.NaN) },
            { _, sample -> sample.copy(latitude = 91.0) },
            { _, sample -> sample.copy(longitude = Double.POSITIVE_INFINITY) },
            { _, sample -> sample.copy(longitude = -181.0) },
            { _, sample -> sample.copy(accuracyMeters = Float.NaN) },
            { _, sample -> sample.copy(accuracyMeters = -1f) },
            { trip, sample -> sample.copy(latitude = trip.home.latitude, longitude = trip.home.longitude) }
        )
        mutations.forEachIndexed { index, mutate ->
            val trip = Trip()
            trip.advance(181)
            assertFalse("Invalid trigger case $index", trip.engine.reportGeofenceEntered(trip.entry(trigger = mutate(trip, trip.sample()))))
            assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
        }
    }

    @Test
    fun receiptAndTargetScopeAreValidatedBeforeTheEventCanAffectTheDecision() {
        val mutations: List<(Trip, GeofenceEntryEvent) -> GeofenceEntryEvent> = listOf(
            { _, event -> event.copy(requestId = " ") },
            { _, event -> event.copy(generation = event.generation - 1) },
            { _, event -> event.copy(buildingCode = FakeBuildingLookup.ALAN_GILBERT.locCode) },
            { trip, event -> event.copy(receivedAtNanos = trip.elapsed + 1) },
            { trip, event -> event.copy(receivedAtNanos = trip.elapsed - 31_000_000_000L) },
            { trip, event -> event.copy(receivedAtNanos = trip.engine.sensingTarget.value.activatedAtNanos - 1) }
        )
        mutations.forEachIndexed { index, mutate ->
            val trip = Trip()
            trip.advance(40)
            assertFalse("Invalid scope/receipt case $index", trip.engine.reportGeofenceEntered(mutate(trip, trip.entry())))
            assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
        }
    }

    @Test
    fun accountReplacementAndAToBToARejectTheOldRegistrationScope() {
        val trip = Trip()
        val old = trip.entry()
        trip.engine.updateSessions(listOf(trip.lecture.copy(room = "PAR-104")))
        trip.engine.updateSessions(listOf(trip.lecture))
        assertFalse(trip.engine.reportGeofenceEntered(old))
        val beforeAccountChange = trip.entry()
        trip.engine.updateSessions(listOf(trip.lecture), reset = true)
        assertFalse(trip.engine.reportGeofenceEntered(beforeAccountChange))
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(requestId = "new-account-registration")))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun explicitStopAndRestartRejectPreviouslyDeliveredAndPendingEntries() = runBlocking {
        val trip = Trip()
        val old = trip.entry()
        assertTrue(trip.engine.reportGeofenceEntered(old))
        trip.engine.stopSensing()
        assertFalse(trip.engine.reportGeofenceEntered(old))
        trip.advance(1)
        trip.engine.startSensing()
        assertFalse(trip.engine.reportGeofenceEntered(old.copy(receivedAtNanos = trip.elapsed)))
        trip.engine.refresh()
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(requestId = "restarted-registration")))
    }

    @Test
    fun sameBuildingRoomCorrectionPreservesTheRegistrationScope() {
        val trip = Trip()
        val entry = trip.entry()
        val target = trip.engine.sensingTarget.value
        trip.engine.updateSessions(listOf(trip.lecture.copy(room = "PAR-160-201")))
        assertEquals(target, trip.engine.sensingTarget.value)
        assertTrue(trip.engine.reportGeofenceEntered(entry))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun aGeofenceEntryDuringDelayedRoutingArrivesImmediatelyAndSurvivesTheOldResult() = runBlocking {
        val enteredRouting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val trip = Trip(beforeRouteResult = { enteredRouting.complete(Unit); release.await() })
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { trip.engine.refresh() }
        enteredRouting.await()
        trip.advance(10)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry()))
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        release.complete(Unit)
        refresh.await()
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        assertNull(trip.engine.snapshot.value.distanceMeters)
        assertEquals(trip.home, trip.engine.snapshot.value.estimateOrigin)
    }

    private class Trip(private val beforeRouteResult: suspend () -> Unit = {}) {
        var now = Instant.parse("2026-10-13T00:00:00Z")
        var elapsed = 100_000_000_000L
        val building = FakeBuildingLookup.PETER_HALL
        val home = GeoPoint(-37.8100, 144.9630)
        val lecture = CourseSession(
            id = "lecture", code = "COMP90018", title = "Lecture", location = "Parkville", room = "PAR-160",
            start = now.plusSeconds(25 * 60).atZone(ZoneId.of("Australia/Melbourne")),
            end = now.plusSeconds(85 * 60).atZone(ZoneId.of("Australia/Melbourne"))
        )
        val shade = FakeDepartureShade()
        val reminders = DepartureReminders(emptyFlow(), { CourseReminderPreference() }, shade::post, shade = shade)
        private val routes = object : EtaRepository {
            override suspend fun estimate(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): Result<TravelEstimate> {
                beforeRouteResult()
                return Result.success(TravelEstimate(mode, 15 * 60L, 1500, EstimateSource.Routing, now))
            }
        }
        val engine = TravelEngine(routes, FakeBuildingLookup(), { TravelPreferences() }, clock = { now }, elapsedNanos = { elapsed })
            .also { it.updateSessions(listOf(lecture)); it.startSensing() }

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
            elapsed += seconds * 1_000_000_000L
        }

        fun sample(point: GeoPoint = building.location, observed: Long = elapsed, accuracy: Float = 10f) =
            LocationSample(point.latitude, point.longitude, accuracy, now.toEpochMilli(), observed)

        fun evidence(point: GeoPoint = home, observed: Long = elapsed) =
            SensingEvidence(engine.sensingTarget.value.generation, sample(point, observed))

        fun entry(requestId: String = "current-registration", trigger: LocationSample? = sample()) =
            GeofenceEntryEvent(requestId, engine.sensingTarget.value.generation, building.locCode, elapsed, trigger)
    }
}
