package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.RouteEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ContextEngineTest {
    private val zone = ZoneId.of("Australia/Melbourne")

    /** A Monday. */
    private val day = LocalDate.of(2026, 10, 5)

    /** COMP90018, 3:00-4:00 PM in PAR-160: the class from the proposal's demo. */
    private val comp = session("comp90018", "COMP90018", 15, 0, 16, 0)

    @Test
    fun theDemoScenarioRunsFromLeaveSoonToPostClass() {
        val trip = Trip()

        // 2:25 PM in the library, a 25-minute walk away, with a 5-minute buffer.
        val inLibrary = trip.update(inputs(at(14, 25), distanceMeters = 2_000.0))
        assertEquals(ContextState.LEAVE_SOON, inLibrary.state)
        assertEquals(at(14, 30), inLibrary.leaveBy)

        // Walking, and the distance to PAR-160 is falling: the reminder is suppressed.
        val walking = trip.update(inputs(at(14, 27), distanceMeters = 1_940.0, isMoving = true))
        assertEquals(ContextState.EN_ROUTE, walking.state)

        // Waiting at a crossing does not cancel the trip.
        val waiting = trip.update(inputs(at(14, 40), travelMinutes = 12, distanceMeters = 900.0))
        assertEquals(ContextState.EN_ROUTE, waiting.state)

        // Inside the arrival radius six minutes before the start.
        val arrived = trip.update(inputs(at(14, 54), travelMinutes = 1, distanceMeters = 40.0))
        assertEquals(ContextState.ARRIVED, arrived.state)

        val inClass = trip.update(inputs(at(15, 0), travelMinutes = 1, distanceMeters = 30.0))
        assertEquals(ContextState.IN_CLASS, inClass.state)

        val afterClass = trip.update(inputs(at(16, 5), travelMinutes = null, distanceMeters = null))
        assertEquals(ContextState.POST_CLASS, afterClass.state)
        assertEquals("comp90018", afterClass.finishedSession?.id)

        val later = trip.update(inputs(at(16, 20), travelMinutes = null, distanceMeters = null))
        assertEquals(ContextState.NO_UPCOMING_CLASS, later.state)
    }

    @Test
    fun leaveByIsStartMinusTravelBufferAndEntryAllowance() {
        val start = at(15, 0)

        assertEquals(at(14, 30), ContextEngine.leaveBy(start, travelMinutes = 25, bufferMinutes = 5, entryAllowanceMinutes = 0))
        // A 5-minute weather buffer on top of the student's 5 minutes.
        assertEquals(at(14, 25), ContextEngine.leaveBy(start, travelMinutes = 25, bufferMinutes = 10, entryAllowanceMinutes = 0))
        assertEquals(at(14, 27), ContextEngine.leaveBy(start, travelMinutes = 25, bufferMinutes = 5, entryAllowanceMinutes = 3))
        assertNull(ContextEngine.leaveBy(start, travelMinutes = null, bufferMinutes = 5, entryAllowanceMinutes = 0))
    }

    @Test
    fun theEntryAllowanceMakesTheDepartureEarlier() {
        val decision = ContextEngine.decide(
            inputs(at(14, 0)),
            config = ContextConfig(entryAllowanceMinutes = 3)
        )

        assertEquals(at(14, 27), decision.leaveBy)
    }

    @Test
    fun leaveSoonStartsExactlyTenMinutesBeforeTheBufferedDeparture() {
        val trip = Trip()
        val leaveBy = at(14, 30)
        val windowOpens = at(14, 20)

        val beforeWindow = trip.update(inputs(windowOpens.minusSeconds(1)))
        assertEquals(leaveBy, beforeWindow.leaveBy)
        assertEquals(ContextState.UPCOMING, beforeWindow.state)

        assertEquals(ContextState.LEAVE_SOON, trip.update(inputs(windowOpens)).state)
        // Reaching the buffered deadline still leaves time to arrive before class starts.
        assertEquals(ContextState.LEAVE_SOON, trip.update(inputs(leaveBy)).state)
    }

    @Test
    fun settingOffEarlySkipsTheLeaveSoonReminder() {
        val trip = Trip()

        assertEquals(ContextState.UPCOMING, trip.update(inputs(at(14, 0), distanceMeters = 2_000.0)).state)
        assertEquals(
            ContextState.EN_ROUTE,
            trip.update(inputs(at(14, 2), distanceMeters = 1_930.0, isMoving = true)).state
        )
        // At the time the reminder would have fired, the student is already on the way.
        assertEquals(
            ContextState.EN_ROUTE,
            trip.update(inputs(at(14, 26), travelMinutes = 8, distanceMeters = 600.0)).state
        )
    }

    @Test
    fun walkingAwayFromTheClassIsNotEnRoute() {
        val trip = Trip()

        trip.update(inputs(at(14, 25), distanceMeters = 2_000.0))
        val first = trip.update(inputs(at(14, 26), distanceMeters = 2_100.0, isMoving = true))
        val second = trip.update(inputs(at(14, 27), travelMinutes = 26, distanceMeters = 2_200.0, isMoving = true))

        assertEquals(ContextState.LEAVE_SOON, first.state)
        assertEquals(ContextState.LEAVE_SOON, second.state)
    }

    @Test
    fun walkingAroundInThePlaceIsNotEnRoute() {
        val trip = Trip()

        trip.update(inputs(at(14, 25), distanceMeters = 2_000.0))
        listOf(1_985.0, 2_010.0, 1_975.0, 2_000.0).forEachIndexed { index, distance ->
            val decision = trip.update(inputs(at(14, 26).plusSeconds(20L * index), distanceMeters = distance, isMoving = true))
            assertEquals(ContextState.LEAVE_SOON, decision.state)
        }
    }

    @Test
    fun turningBackEndsTheTrip() {
        val trip = Trip()

        trip.update(inputs(at(14, 25), distanceMeters = 2_000.0))
        trip.update(inputs(at(14, 27), distanceMeters = 1_940.0, isMoving = true))
        assertEquals(
            ContextState.EN_ROUTE,
            trip.update(inputs(at(14, 32), travelMinutes = 19, distanceMeters = 1_500.0, isMoving = true)).state
        )

        // 200 m back from the closest point: no longer on the way, and the leave-by time has passed.
        val turnedBack = trip.update(inputs(at(14, 36), travelMinutes = 22, distanceMeters = 1_700.0, isMoving = true))
        assertEquals(ContextState.LEAVE_SOON, turnedBack.state)
    }

    @Test
    fun travelBeforeTheDepartureWindowIsNotTheTripToClass() {
        val trip = Trip()

        // Coming to campus by tram in the morning.
        trip.update(inputs(at(10, 0), distanceMeters = 10_000.0, isMoving = true))
        assertEquals(
            ContextState.UPCOMING,
            trip.update(inputs(at(10, 30), distanceMeters = 2_000.0, isMoving = true)).state
        )

        // The window opens at 1:45 PM (45 minutes before leaving at 2:30); the morning trip does not count.
        assertEquals(
            ContextState.UPCOMING,
            trip.update(inputs(at(13, 46), distanceMeters = 2_000.0, isMoving = true)).state
        )
        assertEquals(
            ContextState.EN_ROUTE,
            trip.update(inputs(at(13, 48), distanceMeters = 1_940.0, isMoving = true)).state
        )
    }

    @Test
    fun theSensingServicesCombinedSignalCountsAsSettingOff() {
        val decision = ContextEngine.decide(inputs(at(14, 20), distanceMeters = 2_000.0, enRouteSignal = true))

        assertEquals(ContextState.EN_ROUTE, decision.state)
    }

    @Test
    fun leavingNowWouldArriveAfterTheStart() {
        val decision = ContextEngine.decide(inputs(at(14, 40), distanceMeters = 2_000.0))

        assertEquals(ContextState.RUNNING_LATE, decision.state)
    }

    @Test
    fun arrivingExactlyAtTheClassStartIsNotRunningLate() {
        val trip = Trip()
        val lastOnTimeDeparture = at(14, 35)

        // With a 25-minute trip, leaving now would arrive exactly at the 3:00 PM start.
        assertEquals(ContextState.LEAVE_SOON, trip.update(inputs(lastOnTimeDeparture)).state)
        assertEquals(
            ContextState.RUNNING_LATE,
            trip.update(inputs(lastOnTimeDeparture.plusSeconds(1))).state
        )
    }

    @Test
    fun aStudentKnownToBeAwayRemainsRunningLateUntilTheClassEnds() {
        val trip = Trip()
        val start = at(15, 0)
        val graceEnds = at(15, 15)

        assertEquals(ContextState.RUNNING_LATE, trip.update(inputs(start)).state)
        assertEquals(ContextState.RUNNING_LATE, trip.update(inputs(graceEnds.minusSeconds(1))).state)
        assertEquals(ContextState.RUNNING_LATE, trip.update(inputs(graceEnds)).state)
        assertEquals(ContextState.RUNNING_LATE, trip.update(inputs(at(16, 0).minusNanos(1))).state)
        assertEquals(ContextState.POST_CLASS, trip.update(inputs(at(16, 0))).state)
    }

    @Test
    fun anActiveClassDoesNotImplyArrivalOutsideTheEntryRadius() {
        for (distance in listOf(75.0001, 150.0, 200.0001, 2_000.0)) {
            val decision = ContextEngine.decide(inputs(at(15, 30), distanceMeters = distance))
            assertEquals(ContextState.RUNNING_LATE, decision.state)
            assertFalse(decision.memory.arrived)
        }
    }

    @Test
    fun confirmedArrivalUsesTheExistingEntryAndExitHysteresisDuringClass() {
        val trip = Trip()
        assertEquals(ContextState.ARRIVED, trip.update(inputs(at(14, 59), distanceMeters = 75.0)).state)
        assertEquals(ContextState.IN_CLASS, trip.update(inputs(at(15, 1), distanceMeters = 200.0)).state)
        assertEquals(ContextState.RUNNING_LATE, trip.update(inputs(at(15, 2), distanceMeters = 200.0001)).state)
    }

    @Test
    fun confirmedArrivalCannotClaimInClassBeforeStartOrAfterEnd() {
        val trip = Trip()
        assertEquals(ContextState.ARRIVED,
            trip.update(inputs(at(15, 0).minusNanos(1), distanceMeters = 40.0)).state)
        assertEquals(ContextState.IN_CLASS, trip.update(inputs(at(15, 0), distanceMeters = 40.0)).state)
        assertEquals(ContextState.POST_CLASS, trip.update(inputs(at(16, 0), distanceMeters = 40.0)).state)
    }

    @Test
    fun anEstablishedTripDoesNotBecomeInClassAtTheOldGraceBoundary() {
        val trip = Trip()
        trip.update(inputs(at(14, 57), distanceMeters = 2_000.0))
        assertEquals(ContextState.EN_ROUTE,
            trip.update(inputs(at(14, 59), distanceMeters = 1_940.0, isMoving = true)).state)
        assertEquals(ContextState.EN_ROUTE, trip.update(inputs(at(15, 15), distanceMeters = 900.0)).state)
        assertEquals(ContextState.EN_ROUTE, trip.update(inputs(at(15, 30), distanceMeters = 900.0)).state)
        assertEquals(ContextState.POST_CLASS, trip.update(inputs(at(16, 0), distanceMeters = 900.0)).state)
    }

    @Test
    fun withoutAPositionTheClockStillDrivesTheDay() {
        val trip = Trip()

        val before = trip.update(inputs(at(14, 25), travelMinutes = null, distanceMeters = null))
        assertEquals(ContextState.UPCOMING, before.state)
        assertNull(before.leaveBy)

        assertEquals(
            ContextState.CLASS_IN_PROGRESS,
            trip.update(inputs(at(15, 5), travelMinutes = null, distanceMeters = null)).state
        )
        assertEquals(
            ContextState.POST_CLASS,
            trip.update(inputs(at(16, 5), travelMinutes = null, distanceMeters = null)).state
        )
    }

    @Test
    fun anUnknownTravelTimeNeverInventsADepartureTime() {
        val decision = ContextEngine.decide(inputs(at(14, 58), travelMinutes = null, distanceMeters = 2_000.0))

        assertEquals(ContextState.UPCOMING, decision.state)
        assertNull(decision.leaveBy)
    }

    @Test
    fun beingAtTheBuildingHoursEarlyOnlyCountsOnceTheTripMatters() {
        val trip = Trip()

        assertEquals(
            ContextState.UPCOMING,
            trip.update(inputs(at(11, 0), travelMinutes = 1, distanceMeters = 30.0)).state
        )
        assertEquals(
            ContextState.ARRIVED,
            trip.update(inputs(at(14, 15), travelMinutes = 1, distanceMeters = 30.0)).state
        )
    }

    @Test
    fun anArrivalSurvivesGpsJitterButNotLeavingTheBuilding() {
        val trip = Trip()

        assertEquals(ContextState.ARRIVED, trip.update(inputs(at(14, 50), travelMinutes = 1, distanceMeters = 40.0)).state)
        assertEquals(ContextState.ARRIVED, trip.update(inputs(at(14, 51), travelMinutes = 2, distanceMeters = 120.0)).state)

        val left = trip.update(inputs(at(14, 52), travelMinutes = 4, distanceMeters = 260.0))
        assertEquals(ContextState.LEAVE_SOON, left.state)
    }

    @Test
    fun enteringTheArrivalGeofenceCountsAsArrived() {
        val decision = ContextEngine.decide(
            inputs(at(14, 52), travelMinutes = 2, distanceMeters = 90.0, insideGeofence = true)
        )

        assertEquals(ContextState.ARRIVED, decision.state)
    }

    @Test
    fun aGapAfterAnEarlierClassIsALongBreak() {
        val morning = session("swen90014", "SWEN90014", 9, 0, 10, 0)
        val sessions = listOf(morning, comp)
        val trip = Trip()

        val justFinished = trip.update(inputs(at(10, 5), sessions = sessions))
        assertEquals(ContextState.POST_CLASS, justFinished.state)
        assertEquals("swen90014", justFinished.finishedSession?.id)
        assertEquals("comp90018", justFinished.session?.id)

        assertEquals(ContextState.LONG_BREAK, trip.update(inputs(at(11, 0), sessions = sessions)).state)
        assertEquals(ContextState.UPCOMING, trip.update(inputs(at(13, 45), sessions = sessions)).state)
        assertEquals(ContextState.LEAVE_SOON, trip.update(inputs(at(14, 26), sessions = sessions)).state)
    }

    @Test
    fun anUrgentNextClassComesBeforeTheFinishedOne() {
        val next = session("swen90014", "SWEN90014", 16, 15, 17, 15)

        // COMP90018 ended two minutes ago, but it is time to leave for SWEN90014.
        val decision = ContextEngine.decide(
            inputs(at(16, 2), sessions = listOf(comp, next), travelMinutes = 10, distanceMeters = 700.0)
        )

        assertEquals(ContextState.LEAVE_SOON, decision.state)
        assertEquals("swen90014", decision.session?.id)
        assertEquals("comp90018", decision.finishedSession?.id)
    }

    @Test
    fun noClassLeftTodayIsNoUpcomingClass() {
        val tomorrow = session("tomorrow", "COMP90018", 9, 0, 10, 0, dayOffset = 1)

        val withTomorrow = ContextEngine.decide(inputs(at(18, 0), sessions = listOf(tomorrow)))
        assertEquals(ContextState.NO_UPCOMING_CLASS, withTomorrow.state)
        assertEquals("tomorrow", withTomorrow.session?.id)

        val empty = ContextEngine.decide(inputs(at(18, 0), sessions = emptyList()))
        assertEquals(ContextState.NO_UPCOMING_CLASS, empty.state)
        assertNull(empty.session)
    }

    @Test
    fun theMemoryStartsAgainForTheNextClass() {
        val next = session("swen90014", "SWEN90014", 17, 0, 18, 0)
        val sessions = listOf(comp, next)
        val trip = Trip()

        trip.update(inputs(at(14, 55), sessions = sessions, travelMinutes = 1, distanceMeters = 30.0))
        val afterwards = trip.update(inputs(at(16, 1), sessions = sessions, travelMinutes = 6, distanceMeters = 400.0))

        assertEquals("swen90014", afterwards.memory.sessionId)
        assertFalse(afterwards.memory.arrived)
        assertEquals(ContextState.POST_CLASS, afterwards.state)
    }

    @Test
    fun aSameIdMoveToAnotherOrUnknownBuildingDoesNotKeepArrivalWithoutAPosition() {
        listOf(OTHER_BUILDING, null).forEach { newBuilding ->
            val arrived = ContextEngine.decide(
                inputs(at(14, 50), travelMinutes = 1, distanceMeters = 30.0, buildingCode = PAR_160)
            )
            val moved = comp.copy(location = "The Spot", room = "PAR-110-101")

            val decision = ContextEngine.decide(
                inputs(
                    at(14, 51), sessions = listOf(moved), travelMinutes = null, distanceMeters = null,
                    buildingCode = newBuilding
                ),
                previous = arrived
            )

            assertEquals(ContextState.UPCOMING, decision.state)
            assertEquals(ContextMemory(comp.id, buildingCode = newBuilding), decision.memory)
            assertNull(decision.leaveBy)
        }
    }

    @Test
    fun aSameIdMoveToAnotherBuildingDoesNotKeepAnEnRouteTripWithoutAPosition() {
        val trip = Trip()
        trip.update(inputs(at(14, 20), distanceMeters = 2_000.0, buildingCode = PAR_160))
        val enRoute = trip.update(
            inputs(at(14, 21), distanceMeters = 1_940.0, isMoving = true, buildingCode = PAR_160)
        )
        assertTrue(enRoute.memory.enRoute)
        val moved = comp.copy(location = "The Spot", room = "PAR-110-101")

        val decision = trip.update(
            inputs(
                at(14, 22), sessions = listOf(moved), travelMinutes = null, distanceMeters = null,
                buildingCode = OTHER_BUILDING
            )
        )

        assertEquals(ContextState.UPCOMING, decision.state)
        assertEquals(ContextMemory(comp.id, buildingCode = OTHER_BUILDING), decision.memory)
    }

    @Test
    fun aSameIdMoveToAnotherBuildingStartsAFreshDistanceTrend() {
        val trip = Trip()
        trip.update(inputs(at(14, 20), distanceMeters = 2_000.0, buildingCode = PAR_160))
        val moved = comp.copy(location = "The Spot", room = "PAR-110-101")

        // The new building is closer, but this is the first measurement for that destination.
        val first = trip.update(
            inputs(
                at(14, 21), sessions = listOf(moved), distanceMeters = 1_000.0, isMoving = true,
                buildingCode = OTHER_BUILDING
            )
        )
        assertEquals(ContextState.LEAVE_SOON, first.state)
        assertFalse(first.memory.enRoute)
        assertEquals(1_000.0, first.memory.farthestMeters)

        val approaching = trip.update(
            inputs(
                at(14, 22), sessions = listOf(moved), distanceMeters = 940.0, isMoving = true,
                buildingCode = OTHER_BUILDING
            )
        )
        assertEquals(ContextState.EN_ROUTE, approaching.state)
    }

    @Test
    fun aRoomOnlyChangeInTheSameBuildingKeepsArrivalMemory() {
        val arrived = ContextEngine.decide(
            inputs(at(14, 50), travelMinutes = 1, distanceMeters = 30.0, buildingCode = PAR_160)
        )
        val newRoom = comp.copy(room = "PAR-160-201")

        val decision = ContextEngine.decide(
            inputs(
                at(14, 51), sessions = listOf(newRoom), travelMinutes = null, distanceMeters = null,
                buildingCode = PAR_160
            ),
            previous = arrived
        )

        assertEquals(ContextState.ARRIVED, decision.state)
        assertEquals(arrived.memory, decision.memory)
    }

    @Test
    fun changingEitherOccurrenceTimeInTheSameBuildingKeepsTripMemory() {
        val arrived = ContextEngine.decide(
            inputs(at(14, 50), travelMinutes = 1, distanceMeters = 30.0, buildingCode = PAR_160)
        )
        val retimed = listOf(
            comp.copy(start = comp.start.plusMinutes(30)),
            comp.copy(end = comp.end.plusMinutes(30))
        )

        retimed.forEach { changed ->
            val decision = ContextEngine.decide(
                inputs(
                    at(14, 51), sessions = listOf(changed), travelMinutes = null, distanceMeters = null,
                    buildingCode = PAR_160
                ),
                previous = arrived
            )
            assertEquals(arrived.memory, decision.memory)
            assertEquals(ContextState.ARRIVED, decision.state)
        }
    }

    @Test
    fun movingTheClassOutOfItsDepartureWindowEndsATripInProgress() {
        val trip = Trip()
        trip.update(inputs(at(14, 20), distanceMeters = 2_000.0, buildingCode = PAR_160))
        val enRoute = trip.update(
            inputs(at(14, 21), distanceMeters = 1_940.0, isMoving = true, buildingCode = PAR_160)
        )
        assertEquals(ContextState.EN_ROUTE, enRoute.state)
        val tomorrow = comp.copy(start = comp.start.plusDays(1), end = comp.end.plusDays(1))

        val decision = trip.update(
            inputs(
                at(14, 22), sessions = listOf(tomorrow), distanceMeters = 1_900.0, isMoving = true,
                buildingCode = PAR_160
            )
        )

        assertEquals(ContextState.NO_UPCOMING_CLASS, decision.state)
        assertFalse(decision.memory.enRoute)
    }

    @Test
    fun aSmallRetimeKeepsATripInProgress() {
        val trip = Trip()
        trip.update(inputs(at(14, 20), distanceMeters = 2_000.0, buildingCode = PAR_160))
        trip.update(inputs(at(14, 21), distanceMeters = 1_940.0, isMoving = true, buildingCode = PAR_160))
        val later = comp.copy(start = comp.start.plusMinutes(10), end = comp.end.plusMinutes(10))

        val decision = trip.update(
            inputs(
                at(14, 22), sessions = listOf(later), distanceMeters = 1_900.0, isMoving = true,
                buildingCode = PAR_160
            )
        )

        assertEquals(ContextState.EN_ROUTE, decision.state)
        assertTrue(decision.memory.enRoute)
    }

    @Test
    fun aClassMovedLaterKeepsArrivalForItsNewDepartureWindow() {
        val arrived = ContextEngine.decide(
            inputs(at(14, 50), travelMinutes = 1, distanceMeters = 30.0, buildingCode = PAR_160)
        )
        val evening = comp.copy(start = comp.start.plusHours(2), end = comp.end.plusHours(2))

        val waiting = ContextEngine.decide(
            inputs(
                at(14, 51), sessions = listOf(evening), travelMinutes = null, distanceMeters = null,
                buildingCode = PAR_160
            ),
            previous = arrived
        )
        assertTrue(waiting.memory.arrived)
        assertEquals(ContextState.UPCOMING, waiting.state)

        val windowOpen = ContextEngine.decide(
            inputs(
                at(16, 20), sessions = listOf(evening), travelMinutes = null, distanceMeters = null,
                buildingCode = PAR_160
            ),
            previous = waiting
        )
        assertEquals(ContextState.ARRIVED, windowOpen.state)
    }

    @Test
    fun withoutABuildingCodeAChangedVenueStillStartsAFreshTrip() {
        val arrived = ContextEngine.decide(inputs(at(14, 50), travelMinutes = 1, distanceMeters = 30.0))
        val moved = comp.copy(location = "The Spot", room = "PAR-110-101")

        val decision = ContextEngine.decide(
            inputs(at(14, 51), sessions = listOf(moved), travelMinutes = null, distanceMeters = null),
            previous = arrived
        )

        assertEquals(ContextState.UPCOMING, decision.state)
        assertEquals(ContextMemory(comp.id), decision.memory)
    }

    @Test
    fun keptArrivalStillEndsWhenTheStudentLeavesTheBuilding() {
        val arrived = ContextEngine.decide(
            inputs(at(14, 50), travelMinutes = 1, distanceMeters = 30.0, buildingCode = PAR_160)
        )
        val newRoom = comp.copy(room = "PAR-160-201")

        val decision = ContextEngine.decide(
            inputs(
                at(14, 51), sessions = listOf(newRoom), travelMinutes = 4, distanceMeters = 260.0,
                buildingCode = PAR_160
            ),
            previous = arrived
        )

        assertFalse(decision.memory.arrived)
        assertEquals(ContextState.LEAVE_SOON, decision.state)
    }

    @Test
    fun unchangedOccurrenceKeepsArrivalThroughEnrichmentAndEquivalentTimeZones() {
        val arrived = ContextEngine.decide(inputs(at(14, 50), travelMinutes = 1, distanceMeters = 30.0))
        val enriched = comp.copy(
            title = "Updated display title",
            etaMinutes = 1,
            routeEstimate = RouteEstimate(distanceMeters = 30, walkingMinutes = 1),
            start = comp.start.withZoneSameInstant(ZoneId.of("UTC")),
            end = comp.end.withZoneSameInstant(ZoneId.of("UTC"))
        )

        val decision = ContextEngine.decide(
            inputs(at(14, 51), sessions = listOf(enriched), travelMinutes = 1, distanceMeters = null),
            previous = arrived
        )

        assertEquals(ContextState.ARRIVED, decision.state)
        assertEquals(arrived.memory, decision.memory)
    }

    @Test
    fun unchangedOccurrenceKeepsEnRouteMemoryThroughEnrichment() {
        val enRoute = ContextEngine.decide(
            inputs(at(14, 20), distanceMeters = 2_000.0, enRouteSignal = true)
        )
        val enriched = comp.copy(
            etaMinutes = 20,
            routeEstimate = RouteEstimate(distanceMeters = 2_000, walkingMinutes = 20)
        )

        val decision = ContextEngine.decide(
            inputs(at(14, 21), sessions = listOf(enriched), travelMinutes = 20, distanceMeters = null),
            previous = enRoute
        )

        assertEquals(ContextState.EN_ROUTE, decision.state)
        assertEquals(enRoute.memory, decision.memory)
    }

    @Test
    fun memoryWithADifferentSessionIdIsNotReusedEvenForAMatchingDecision() {
        val arrived = ContextEngine.decide(inputs(at(14, 50), travelMinutes = 1, distanceMeters = 30.0))
        val mismatchedMemory = arrived.copy(memory = arrived.memory.copy(sessionId = "another-class"))

        val decision = ContextEngine.decide(
            inputs(at(14, 51), travelMinutes = null, distanceMeters = null),
            previous = mismatchedMemory
        )

        assertEquals(ContextMemory(comp.id), decision.memory)
        assertEquals(ContextState.UPCOMING, decision.state)
    }

    /** Feeds updates through the engine the way TravelEngine does, keeping the previous decision. */
    private class Trip(private val config: ContextConfig = ContextConfig()) {
        private var last: ContextDecision? = null

        fun update(inputs: ContextInputs): ContextDecision =
            ContextEngine.decide(inputs, last, config).also { last = it }
    }

    private fun inputs(
        now: Instant,
        sessions: List<CourseSession> = listOf(comp),
        travelMinutes: Int? = 25,
        bufferMinutes: Int = 5,
        distanceMeters: Double? = 2_000.0,
        isMoving: Boolean = false,
        enRouteSignal: Boolean? = null,
        insideGeofence: Boolean = false,
        buildingCode: String? = null
    ) = ContextInputs(
        now = now,
        sessions = sessions,
        travelMinutes = travelMinutes,
        bufferMinutes = bufferMinutes,
        distanceMeters = distanceMeters,
        isMoving = isMoving,
        enRouteSignal = enRouteSignal,
        insideGeofence = insideGeofence,
        buildingCode = buildingCode
    )

    private fun at(hour: Int, minute: Int): Instant = day.atTime(hour, minute).atZone(zone).toInstant()

    private fun session(
        id: String,
        code: String,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        dayOffset: Long = 0
    ): CourseSession {
        val date = day.plusDays(dayOffset)
        return CourseSession(
            id = id,
            code = code,
            title = "Class $id",
            location = "Parkville Campus",
            room = "PAR-160",
            start = date.atTime(startHour, startMinute).atZone(zone),
            end = date.atTime(endHour, endMinute).atZone(zone)
        )
    }

    private companion object {
        /** Building location codes in the format TravelEngine passes (the building's `locCode`). */
        const val PAR_160 = "PAR;160"
        const val OTHER_BUILDING = "PAR;110"
    }
}
