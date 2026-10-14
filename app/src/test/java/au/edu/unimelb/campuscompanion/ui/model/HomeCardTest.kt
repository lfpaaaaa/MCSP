package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.english
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.ContextEngine
import au.edu.unimelb.campuscompanion.context.ContextInputs
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeCardTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")
    private val now = ZonedDateTime.of(2026, 10, 12, 14, 25, 0, 0, melbourne)
    private val nextClass = CourseSession(
        id = "comp90018",
        code = "COMP90018",
        title = "Mobile Computing Systems Programming",
        location = "Parkville Campus",
        room = "PAR-160",
        activity = "tutorial",
        start = now.withHour(15).withMinute(0),
        end = now.withHour(16).withMinute(0)
    )
    private val building = BuildingLocation(
        locCode = "PAR;160",
        buildingNumber = "160",
        campusCode = "PAR",
        name = "Peter Hall Building",
        address = "230 Grattan Street, Parkville",
        location = GeoPoint(-37.7994, 144.9608)
    )
    private val estimate = TravelEstimate(
        mode = TravelMode.PublicTransport,
        durationSeconds = 23 * 60L,
        distanceMeters = 2_400,
        source = EstimateSource.Routing,
        computedAt = now.toInstant()
    )

    @Test fun classRowUsesTheEnginePreparationChoiceAndNeverInventsAMissingEta() {
        for ((eta, preparation, expected) in listOf(
            Triple(null, 10, ContextState.UPCOMING),
            Triple(10, 0, ContextState.UPCOMING),
            Triple(10, 25, ContextState.LEAVE_SOON)
        )) {
            val clock = if (eta == null) nextClass.start.minusMinutes(5) else now
            val decision = ContextEngine.decide(ContextInputs(clock.toInstant(), listOf(nextClass), eta,
                bufferMinutes = 0, distanceMeters = null, isMoving = false, preparationMinutes = preparation))
            val card = buildHomeCardState(listOf(nextClass), TravelSnapshot(session = nextClass,
                state = decision.state, preparationMinutes = preparation,
                leaveBy = decision.leaveBy?.atZone(melbourne)), clock)
            val row = requireNotNull(card.forSession(nextClass))
            assertEquals(expected, row.state)
            assertEquals(card.statusLabel, row.statusLabel)
        }
    }

    @Test fun anotherOccurrenceCannotInheritTheTrackedRowsTravelState() {
        val card = buildHomeCardState(listOf(nextClass), snapshot(ContextState.EN_ROUTE), now)
        assertEquals(ContextState.EN_ROUTE, card.forSession(nextClass)?.state)
        assertNull(card.forSession(nextClass.copy(id = "next-week", start = nextClass.start.plusWeeks(1))))
        assertNull(card.forSession(nextClass.copy(room = "PAR-104")))
    }

    @Test
    fun aRoomEmbeddedInCalendarLocationIsPreservedAlongsideTheResolvedBuilding() {
        val imported = nextClass.copy(
            location = "PAR-379-B1-B116-Digital Learning Space (PC) (32)",
            room = ""
        )
        val destination = building.copy(name = "207-221 Bouverie St, Carlton South ")

        val card = buildHomeCardState(listOf(imported), snapshot().copy(session = imported, building = destination), now)

        assertTrue(card.destinationText.english().contains(imported.location))
        assertTrue(card.destinationText.english().contains(destination.name.trim()))
    }

    @Test
    fun matchingSnapshotPreservesEngineStateInsteadOfDerivingTimetableStatus() {
        for (state in listOf(ContextState.EN_ROUTE, ContextState.ARRIVED, ContextState.LONG_BREAK)) {
            val card = buildHomeCardState(listOf(nextClass), snapshot(state), now)

            assertEquals(state, card.state)
            assertFalse(card.isUpdating)
        }
    }

    @Test
    fun departureDeadlineTravelAndAllBuffersComeDirectlyFromTheSnapshot() {
        val deadline = now.plusMinutes(7)
        val snapshot = snapshot().copy(
            leaveBy = deadline,
            leadMinutes = 3,
            weatherBufferMinutes = 4,
            entryAllowanceMinutes = 6
        )

        val card = buildHomeCardState(listOf(nextClass), snapshot, now)

        assertEquals(nextClass, card.session)
        assertEquals(snapshot.estimate, card.estimate)
        assertEquals(building, card.building)
        assertEquals(snapshot.distanceMeters, card.distanceMeters)
        assertEquals(deadline, card.leaveBy)
        assertEquals(7L, card.departureCountdownMinutes)
        assertEquals(3, card.leadMinutes)
        assertEquals(4, card.weatherBufferMinutes)
        assertEquals(6, card.entryAllowanceMinutes)
    }

    @Test
    fun departureCountdownRoundsPositiveFractionalMinutesUp() {
        val snapshot = snapshot().copy(leaveBy = now.plusMinutes(5))

        assertEquals(5L, buildHomeCardState(listOf(nextClass), snapshot, now.plusSeconds(30)).departureCountdownMinutes)
        assertEquals(1L, buildHomeCardState(listOf(nextClass), snapshot, now.plusMinutes(4).plusSeconds(30)).departureCountdownMinutes)
        assertEquals(1L, buildHomeCardState(listOf(nextClass), snapshot, now.plusMinutes(5).minusNanos(1)).departureCountdownMinutes)
    }

    @Test
    fun departureCountdownIsZeroAtAndAfterTheDeadline() {
        val deadline = now.plusMinutes(5)
        val snapshot = snapshot().copy(leaveBy = deadline)

        assertEquals(0L, buildHomeCardState(listOf(nextClass), snapshot, deadline).departureCountdownMinutes)
        assertEquals(0L, buildHomeCardState(listOf(nextClass), snapshot, deadline.plusSeconds(1)).departureCountdownMinutes)
    }

    @Test
    fun unknownDeadlineIsNotRecomputedFromAnAvailableEstimate() {
        val snapshot = snapshot().copy(leaveBy = null)

        val card = buildHomeCardState(listOf(nextClass), snapshot, now)

        assertEquals(estimate, card.estimate)
        assertNull(card.leaveBy)
        assertNull(card.departureCountdownMinutes)
    }

    @Test
    fun missingEngineEstimateDoesNotUseEnrichedTimetableEtaAsADeadline() {
        val enriched = nextClass.copy(etaMinutes = 20)
        val snapshot = TravelSnapshot(session = nextClass, state = ContextState.UPCOMING, leadMinutes = 5)

        val card = buildHomeCardState(listOf(enriched), snapshot, now)

        assertEquals(ContextState.UPCOMING, card.state)
        assertEquals(enriched, card.session)
        assertNull(card.estimate)
        assertNull(card.leaveBy)
        assertNull(card.departureCountdownMinutes)
    }

    @Test
    fun defaultStartupSnapshotKeepsTheLoadedClassWhileWaitingForTheEngine() {
        val card = buildHomeCardState(listOf(nextClass), TravelSnapshot(), now)

        assertUpdatingWithoutSnapshotData(card, nextClass)
    }

    @Test
    fun aSnapshotForAnotherClassCannotLeakItsGuidanceOrTravel() {
        val stale = snapshot().copy(session = nextClass.copy(id = "another-class"))

        val card = buildHomeCardState(listOf(nextClass), stale, now)

        assertUpdatingWithoutSnapshotData(card, nextClass)
    }

    @Test
    fun aSameIdSnapshotWithChangedSessionDetailsIsRejected() {
        val updatedSessions = listOf(
            nextClass.copy(code = "SWEN90014"),
            nextClass.copy(activity = "workshop"),
            nextClass.copy(location = "Southbank Campus"),
            nextClass.copy(room = "PAR-200"),
            nextClass.copy(start = nextClass.start.plusMinutes(10)),
            nextClass.copy(end = nextClass.end.plusMinutes(10))
        )

        for (updated in updatedSessions) {
            val card = buildHomeCardState(listOf(updated), snapshot(), now)

            assertUpdatingWithoutSnapshotData(card, updated)
        }
    }

    @Test
    fun routeEnrichmentDoesNotMakeAMatchingEngineSnapshotStale() {
        val enriched = nextClass.copy(
            etaMinutes = estimate.durationMinutes,
            routeEstimate = RouteEstimate(distanceMeters = estimate.distanceMeters, publicTransportMinutes = estimate.durationMinutes)
        )

        val card = buildHomeCardState(listOf(enriched), snapshot(ContextState.EN_ROUTE), now)

        assertEquals(enriched, card.session)
        assertEquals(ContextState.EN_ROUTE, card.state)
        assertEquals(estimate, card.estimate)
        assertFalse(card.isUpdating)
    }

    @Test
    fun atTheExactClassEndTheNextClassIsSelectedAndTheOldSnapshotIsRejected() {
        val later = nextClass.copy(
            id = "later-class",
            start = nextClass.end.plusHours(1),
            end = nextClass.end.plusHours(2)
        )

        val card = buildHomeCardState(listOf(later, nextClass), snapshot(ContextState.IN_CLASS), nextClass.end)

        assertUpdatingWithoutSnapshotData(card, later)
    }

    @Test
    fun aClassPeriodSnapshotCannotClaimAnActiveClassBeforeItsStart() {
        for (state in listOf(ContextState.IN_CLASS, ContextState.CLASS_IN_PROGRESS)) {
            val card = buildHomeCardState(listOf(nextClass), snapshot(state), nextClass.start.minusNanos(1))
            assertUpdatingWithoutSnapshotData(card, nextClass)
        }
    }

    @Test
    fun aClassPeriodSnapshotIsAcceptedAtItsExactStartAcrossDisplayZones() {
        for (state in listOf(ContextState.IN_CLASS, ContextState.CLASS_IN_PROGRESS)) {
            val clock = nextClass.start.withZoneSameInstant(ZoneId.of("UTC"))
            val card = buildHomeCardState(listOf(nextClass), snapshot(state), clock)
            assertEquals(state, card.state)
            assertFalse(card.isUpdating)
        }
    }

    @Test
    fun sessionMatchingAndCountdownUseInstantsAcrossTimeZones() {
        val utc = ZoneId.of("UTC")
        val utcSession = nextClass.copy(
            start = nextClass.start.withZoneSameInstant(utc),
            end = nextClass.end.withZoneSameInstant(utc)
        )
        val snapshot = snapshot().copy(
            session = utcSession,
            leaveBy = now.plusMinutes(5).withZoneSameInstant(utc)
        )

        val card = buildHomeCardState(listOf(nextClass), snapshot, now.withZoneSameInstant(ZoneId.of("America/New_York")))

        assertEquals(nextClass, card.session)
        assertEquals(ContextState.LEAVE_SOON, card.state)
        assertEquals(5L, card.departureCountdownMinutes)
        assertFalse(card.isUpdating)
    }

    @Test
    fun approximateTravelAndTheEngineSelectedModeRemainIntact() {
        val approximate = estimate.copy(mode = TravelMode.Driving, source = EstimateSource.StraightLine)

        val card = buildHomeCardState(listOf(nextClass), snapshot().copy(estimate = approximate), now)

        assertEquals(approximate, card.estimate)
        assertEquals(TravelMode.Driving, card.estimate?.mode)
        assertTrue(card.estimate!!.isApproximate)
    }

    @Test
    fun postClassWithAFutureTrackedClassRetainsTheEngineStateAndFinishedSession() {
        val finished = finishedClass()
        val snapshot = snapshot(ContextState.POST_CLASS).copy(finishedSession = finished)

        val card = buildHomeCardState(listOf(finished, nextClass), snapshot, now)

        assertEquals(nextClass, card.session)
        assertEquals(ContextState.POST_CLASS, card.state)
        assertEquals(finished, card.finishedSession)
        assertEquals(snapshot.leaveBy, card.leaveBy)
        assertFalse(card.isUpdating)
    }

    @Test
    fun urgentNextClassDoesNotHideTheValidatedFinishedOccurrence() {
        val finished = finishedClass()
        for (state in listOf(ContextState.LEAVE_SOON, ContextState.RUNNING_LATE, ContextState.EN_ROUTE)) {
            val snapshot = snapshot(state).copy(finishedSession = finished)

            val card = buildHomeCardState(listOf(finished, nextClass), snapshot, now)

            assertEquals(state, card.state)
            assertEquals(nextClass, card.session)
            assertEquals(finished, card.finishedSession)
            assertEquals(snapshot.leaveBy, card.leaveBy)
        }
    }

    @Test
    fun anUpdatingCardCannotExposeTheRawSnapshotsFinishedOccurrence() {
        val finished = finishedClass()
        val stale = snapshot().copy(
            session = nextClass.copy(room = "PAR-200"),
            finishedSession = finished
        )

        val card = buildHomeCardState(listOf(finished, nextClass), stale, now)

        assertUpdatingWithoutSnapshotData(card, nextClass)
        assertNull(card.finishedSession)
    }

    @Test
    fun anAbsentFinishedOccurrenceDoesNotInvalidateAMatchedUpcomingSnapshot() {
        val snapshot = snapshot(ContextState.POST_CLASS).copy(finishedSession = finishedClass())

        val card = buildHomeCardState(listOf(nextClass), snapshot, now)

        assertEquals(nextClass, card.session)
        assertEquals(ContextState.POST_CLASS, card.state)
        assertEquals(snapshot.leaveBy, card.leaveBy)
        assertEquals(5L, card.departureCountdownMinutes)
        assertEquals(estimate, card.estimate)
        assertNull(card.finishedSession)
        assertFalse(card.isUpdating)
    }

    @Test
    fun editedFinishedOccurrencesAreClearedWithoutLosingMatchedUpcomingGuidance() {
        val finished = finishedClass()
        val staleFinishedOccurrences = listOf(
            finished.copy(end = finished.end.minusMinutes(1)),
            finished.copy(room = "PAR-200")
        )

        for (staleFinished in staleFinishedOccurrences) {
            val snapshot = snapshot(ContextState.POST_CLASS).copy(finishedSession = staleFinished)

            val card = buildHomeCardState(listOf(finished, nextClass), snapshot, now)

            assertEquals(nextClass, card.session)
            assertEquals(ContextState.POST_CLASS, card.state)
            assertEquals(snapshot.leaveBy, card.leaveBy)
            assertEquals(5L, card.departureCountdownMinutes)
            assertEquals(estimate, card.estimate)
            assertNull(card.finishedSession)
            assertFalse(card.isUpdating)
        }
    }

    @Test
    fun postClassWithNoUpcomingClassRetainsTheFinishedSession() {
        val finished = finishedClass()
        val snapshot = TravelSnapshot(state = ContextState.POST_CLASS, finishedSession = finished)

        val card = buildHomeCardState(listOf(finished), snapshot, now)

        assertNull(card.session)
        assertEquals(ContextState.POST_CLASS, card.state)
        assertEquals(finished, card.finishedSession)
        assertNull(card.departureCountdownMinutes)
        assertFalse(card.isUpdating)
    }

    @Test
    fun postClassSnapshotCannotSurfaceAFinishedSessionAbsentFromTheCurrentTimetable() {
        val snapshot = TravelSnapshot(state = ContextState.POST_CLASS, finishedSession = finishedClass())

        val card = buildHomeCardState(emptyList(), snapshot, now)

        assertUpdatingWithoutSnapshotData(card, null)
    }

    @Test
    fun aFinishedSessionWithAnEndStillInTheFutureIsRejected() {
        val finished = finishedClass()
        val snapshot = TravelSnapshot(
            state = ContextState.POST_CLASS,
            finishedSession = finished.copy(end = now.plusMinutes(1))
        )

        val card = buildHomeCardState(listOf(finished), snapshot, now)

        assertUpdatingWithoutSnapshotData(card, null)
    }

    @Test
    fun anEndedTrackedSessionCannotRemainOnHomeWithoutAnUpcomingClass() {
        val card = buildHomeCardState(listOf(nextClass), snapshot(ContextState.IN_CLASS), nextClass.end)

        assertUpdatingWithoutSnapshotData(card, null)
    }

    @Test
    fun noUpcomingClassAcceptsAnIdleEngineSnapshot() {
        val card = buildHomeCardState(emptyList(), TravelSnapshot(), now)

        assertNull(card.session)
        assertEquals(ContextState.NO_UPCOMING_CLASS, card.state)
        assertNull(card.finishedSession)
        assertNull(card.departureCountdownMinutes)
        assertFalse(card.isUpdating)
    }

    @Test
    fun aLaterTeachingDayRemainsAvailableWithTheEnginesNoUpcomingState() {
        val tomorrow = nextClass.copy(start = nextClass.start.plusDays(1), end = nextClass.end.plusDays(1))
        val snapshot = TravelSnapshot(session = tomorrow, state = ContextState.NO_UPCOMING_CLASS)

        val card = buildHomeCardState(listOf(tomorrow), snapshot, now)

        assertEquals(tomorrow, card.session)
        assertEquals(ContextState.NO_UPCOMING_CLASS, card.state)
        assertFalse(card.isUpdating)
    }

    private fun snapshot(state: ContextState = ContextState.LEAVE_SOON) = TravelSnapshot(
        session = nextClass,
        state = state,
        estimate = estimate,
        building = building,
        distanceMeters = 2_100.0,
        leaveBy = now.plusMinutes(5),
        leadMinutes = 5,
        weatherBufferMinutes = 2,
        entryAllowanceMinutes = 1
    )

    private fun finishedClass() = nextClass.copy(
        id = "finished-class",
        start = now.minusHours(1),
        end = now.minusMinutes(5)
    )

    private fun assertUpdatingWithoutSnapshotData(card: HomeCardUiState, expectedSession: CourseSession?) {
        assertEquals(expectedSession, card.session)
        assertTrue(card.isUpdating)
        assertNull(card.state)
        assertNull(card.estimate)
        assertNull(card.building)
        assertNull(card.distanceMeters)
        assertNull(card.leaveBy)
        assertNull(card.departureCountdownMinutes)
        assertNull(card.finishedSession)
        assertEquals(0, card.leadMinutes)
        assertEquals(0, card.weatherBufferMinutes)
        assertEquals(0, card.entryAllowanceMinutes)
    }
}
