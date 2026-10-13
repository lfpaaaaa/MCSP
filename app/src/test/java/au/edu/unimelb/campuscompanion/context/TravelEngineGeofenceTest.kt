package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceEntryEvent
import au.edu.unimelb.campuscompanion.sensing.location.GeofenceRejectionReason
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
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry()).accepted)
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
        assertTrue(trip.engine.reportGeofenceEntered(entry).accepted)

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
        assertTrue(trip.engine.reportGeofenceEntered(first).accepted)
        assertFalse(trip.engine.reportGeofenceEntered(first).accepted)
        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)

        trip.advance(40)
        assertFalse(trip.engine.reportGeofenceEntered(first.copy(receivedAtNanos = trip.elapsed)).accepted)
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(requestId = first.requestId)).accepted)
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
        assertFalse(trip.engine.reportGeofenceEntered(earlierEntry.copy(receivedAtNanos = trip.elapsed)).accepted)
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
    }

    @Test
    fun aTriggerNoNewerThanAnAcceptedFarFixIsRejected() {
        val trip = Trip()
        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        assertFalse(trip.engine.reportGeofenceEntered(trip.entry()).accepted)
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
        assertTrue(trip.engine.reportGeofenceEntered(entry.copy(receivedAtNanos = trip.elapsed)).accepted)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun nullTriggerCanEstablishArrivalWithoutGpsButCannotBeReplayedAfterExit() {
        val trip = Trip()
        val entry = trip.entry(trigger = null)
        assertTrue(trip.engine.reportGeofenceEntered(entry).accepted)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        trip.advance(10)
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.advance(40)
        assertFalse(trip.engine.reportGeofenceEntered(entry.copy(receivedAtNanos = trip.elapsed)).accepted)
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
    }

    @Test
    fun nullTriggerRejectsKnownAwayPositionEvenWhenThatPositionHasExpired() = runBlocking {
        val trip = Trip()
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.advance(40)
        trip.engine.refresh()
        assertFalse(trip.engine.reportGeofenceEntered(trip.entry(trigger = null)).accepted)
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
    }

    @Test
    fun aTriggerExactlyThreeMinutesOldIsStillAccepted() {
        val trip = Trip()
        val trigger = trip.sample()
        trip.advance(180)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(trigger = trigger)).accepted)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun triggerAfterReceiptIsRejectedEvenWhenItIsNoLongerFutureAtProcessingTime() {
        val trip = Trip()
        val receivedAt = trip.elapsed
        trip.advance(1)
        val impossibleEntry = trip.entry().copy(receivedAtNanos = receivedAt)
        trip.advance(1)
        assertFalse(trip.engine.reportGeofenceEntered(impossibleEntry).accepted)
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)

        // Rejection must not consume the registration or advance its physical-observation watermark.
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(trigger = trip.sample(observed = receivedAt))).accepted)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun geofenceTriggerAccuracyDoesNotRelaxTheSeparatePreciseGpsGate() {
        val trip = Trip()
        val trigger = trip.sample(accuracy = 120f)
        assertFalse(trip.engine.updateEvidence(SensingEvidence(trip.engine.sensingTarget.value.generation, trigger)))
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(trigger = trigger)).accepted)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun oldFutureAndInvalidTriggerMeasurementsCannotEstablishArrival() {
        val mutations: List<(Trip, LocationSample) -> LocationSample> = listOf(
            { trip, sample -> sample.copy(elapsedRealtimeNanos = trip.elapsed - 180_000_000_001L) },
            { trip, sample -> sample.copy(elapsedRealtimeNanos = trip.elapsed + 1) },
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
            assertFalse("Invalid trigger case $index", trip.engine.reportGeofenceEntered(trip.entry(trigger = mutate(trip, trip.sample()))).accepted)
            assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
        }
    }

    @Test
    fun alreadyInsideInitialEntryAcceptsAFreshPreActivationFixWithoutRelaxingGpsAdmission() = runBlocking {
        val trip = Trip()
        val trigger = trip.sample(observed = trip.engine.sensingTarget.value.activatedAtNanos - 2_000_000_000L)
        trip.advance(1)
        assertFalse(trip.engine.updateEvidence(SensingEvidence(trip.engine.sensingTarget.value.generation, trigger)))
        val result = trip.engine.reportGeofenceEntered(trip.entry(trigger = trigger))
        assertTrue(result.accepted)
        assertNull(result.rejectionReason)
        assertEquals(3_000L, result.triggerAgeMillis)
        assertEquals(3_000L, result.triggerAgeAtReceiptMillis)
        assertEquals(10f, result.triggerAccuracyMeters)
        assertTrue(result.triggerBeforeActivation)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        trip.engine.refresh()
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        assertEquals(GeofenceRejectionReason.DUPLICATE,
            trip.engine.reportGeofenceEntered(trip.entry(trigger = trigger)).rejectionReason)
    }

    @Test
    fun sameBuildingClassHandoverAcceptsCurrentInitialEntryAndRejectsTheOldGeneration() = runBlocking {
        val trip = Trip()
        val nextClass = trip.lecture.copy(
            id = "next-class", title = "Next class",
            start = trip.lecture.end.plusMinutes(1), end = trip.lecture.end.plusMinutes(61)
        )
        trip.engine.updateSessions(listOf(trip.lecture, nextClass))
        val oldEntry = trip.entry()
        assertTrue(trip.engine.reportGeofenceEntered(oldEntry).accepted)
        trip.advance(85 * 60L)
        trip.engine.refresh()
        val current = trip.engine.sensingTarget.value
        assertEquals(nextClass.id, current.sessionId)
        assertNotEquals(oldEntry.generation, current.generation)
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
        val trigger = trip.sample(observed = current.activatedAtNanos - 2_000_000_000L)
        trip.advance(1)
        assertEquals(GeofenceRejectionReason.WRONG_GENERATION,
            trip.engine.reportGeofenceEntered(oldEntry.copy(receivedAtNanos = trip.elapsed, triggerLocation = trigger)).rejectionReason)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(requestId = "next-registration", trigger = trigger)).accepted)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
        assertEquals(nextClass.id, trip.engine.snapshot.value.session?.id)
    }

    @Test
    fun preActivationTriggerUsesTheSameThreeMinuteBoundaryAsAnyOtherTrigger() {
        val trip = Trip()
        trip.advance(181)
        trip.engine.stopSensing()
        val target = trip.engine.startSensing()
        val atBoundary = trip.sample(observed = target.activatedAtNanos - TravelEngine.GEOFENCE_TRIGGER_MAX_AGE_NANOS)
        val expired = atBoundary.copy(elapsedRealtimeNanos = atBoundary.elapsedRealtimeNanos - 1)
        assertEquals(GeofenceRejectionReason.TRIGGER_EXPIRED,
            trip.engine.reportGeofenceEntered(trip.entry(trigger = expired)).rejectionReason)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(trigger = atBoundary)).accepted)
    }

    @Test
    fun preActivationInitialEntryCannotOverrideANewerAwayGpsFixEvenAfterGpsExpires() = runBlocking {
        val trip = Trip()
        val trigger = trip.sample(observed = trip.engine.sensingTarget.value.activatedAtNanos - 2_000_000_000L)
        trip.advance(1)
        assertTrue(trip.engine.updateEvidence(trip.evidence()))
        trip.advance(40)
        trip.engine.refresh()
        assertNull(trip.engine.snapshot.value.distanceMeters)
        val result = trip.engine.reportGeofenceEntered(trip.entry(trigger = trigger))
        assertEquals(GeofenceRejectionReason.NEWER_AWAY_FIX, result.rejectionReason)
        assertFalse(result.accepted)
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
    }

    @Test
    fun laterPreActivationMeasurementCannotBeUsedAsAReentryOnTheSameRegistration() {
        val trip = Trip()
        val activated = trip.engine.sensingTarget.value.activatedAtNanos
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(trigger = trip.sample(observed = activated - 2_000_000_000L))).accepted)
        val result = trip.engine.reportGeofenceEntered(trip.entry(trigger = trip.sample(observed = activated - 1_000_000_000L)))
        assertEquals(GeofenceRejectionReason.PRE_ACTIVATION_REENTRY, result.rejectionReason)
    }

    @Test
    fun rejectionDiagnosticsDistinguishInvalidExpiredDuplicateAndContradictedEntries() {
        val trip = Trip()
        assertEquals(GeofenceRejectionReason.INVALID_POSITION,
            trip.engine.reportGeofenceEntered(trip.entry(trigger = trip.sample().copy(latitude = Double.NaN))).rejectionReason)
        assertEquals(GeofenceRejectionReason.INVALID_ACCURACY,
            trip.engine.reportGeofenceEntered(trip.entry(trigger = trip.sample(accuracy = Float.NaN))).rejectionReason)
        val receivedAt = trip.elapsed
        trip.advance(2)
        val entry = trip.entry(trigger = trip.sample(observed = receivedAt)).copy(receivedAtNanos = receivedAt + 1_000_000_000L)
        val accepted = trip.engine.reportGeofenceEntered(entry)
        assertTrue(accepted.accepted)
        assertEquals(2_000L, accepted.triggerAgeMillis)
        assertEquals(1_000L, accepted.triggerAgeAtReceiptMillis)
        assertEquals(GeofenceRejectionReason.DUPLICATE, trip.engine.reportGeofenceEntered(entry).rejectionReason)
        trip.advance(31)
        assertEquals(GeofenceRejectionReason.RECEIPT_EXPIRED, trip.engine.reportGeofenceEntered(entry).rejectionReason)
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
            assertFalse("Invalid scope/receipt case $index", trip.engine.reportGeofenceEntered(mutate(trip, trip.entry())).accepted)
            assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
        }
    }

    @Test
    fun accountReplacementAndAToBToARejectTheOldRegistrationScope() {
        val trip = Trip()
        val old = trip.entry()
        trip.engine.updateSessions(listOf(trip.lecture.copy(room = "PAR-104")))
        trip.engine.updateSessions(listOf(trip.lecture))
        assertFalse(trip.engine.reportGeofenceEntered(old).accepted)
        val beforeAccountChange = trip.entry()
        trip.engine.updateSessions(listOf(trip.lecture), reset = true)
        assertFalse(trip.engine.reportGeofenceEntered(beforeAccountChange).accepted)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(requestId = "new-account-registration")).accepted)
        assertEquals(ContextState.ARRIVED, trip.engine.snapshot.value.state)
    }

    @Test
    fun explicitStopAndRestartRejectPreviouslyDeliveredAndPendingEntries() = runBlocking {
        val trip = Trip()
        val old = trip.entry()
        assertTrue(trip.engine.reportGeofenceEntered(old).accepted)
        trip.engine.stopSensing()
        assertFalse(trip.engine.reportGeofenceEntered(old).accepted)
        trip.advance(1)
        trip.engine.startSensing()
        assertFalse(trip.engine.reportGeofenceEntered(old.copy(receivedAtNanos = trip.elapsed)).accepted)
        trip.engine.refresh()
        assertFalse(trip.engine.snapshot.value.state == ContextState.ARRIVED)
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry(requestId = "restarted-registration")).accepted)
    }

    @Test
    fun sameBuildingRoomCorrectionPreservesTheRegistrationScope() {
        val trip = Trip()
        val entry = trip.entry()
        val target = trip.engine.sensingTarget.value
        trip.engine.updateSessions(listOf(trip.lecture.copy(room = "PAR-160-201")))
        assertEquals(target, trip.engine.sensingTarget.value)
        assertTrue(trip.engine.reportGeofenceEntered(entry).accepted)
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
        assertTrue(trip.engine.reportGeofenceEntered(trip.entry()).accepted)
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
