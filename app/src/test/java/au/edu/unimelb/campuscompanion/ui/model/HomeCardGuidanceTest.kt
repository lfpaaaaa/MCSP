package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.english
import au.edu.unimelb.campuscompanion.context.ContextState
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

class HomeCardGuidanceTest {
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
        mode = TravelMode.Walking,
        durationSeconds = 23 * 60L,
        distanceMeters = 2_400,
        source = EstimateSource.Routing,
        computedAt = now.toInstant()
    )

    @Test
    fun leaveSoonCountdownRoundsUpAndChangesToLeaveNowAtTheEngineDeadline() {
        val snapshot = snapshot().copy(leaveBy = now.plusMinutes(5))

        assertEquals("Leave in 5 minutes", guidance(snapshot).headline.english())
        assertEquals("Leave in 1 minute", guidance(snapshot, now.plusMinutes(4).plusSeconds(30)).headline.english())
        assertEquals("Leave in 1 minute", guidance(snapshot, now.plusMinutes(5).minusNanos(1)).headline.english())
        assertEquals("Leave now", guidance(snapshot, now.plusMinutes(5)).headline.english())
        assertEquals("Leave now", guidance(snapshot, now.plusMinutes(5).plusSeconds(1)).headline.english())
    }

    @Test
    fun departureLineUsesANondefaultEngineDeadlineWithoutRecalculatingIt() {
        val snapshot = snapshot().copy(
            state = ContextState.UPCOMING,
            leaveBy = now.withMinute(42),
            leadMinutes = 3,
            weatherBufferMinutes = 4,
            entryAllowanceMinutes = 6
        )

        val guidance = guidance(snapshot)

        assertEquals("Leave by 2:42 PM", guidance.departureLine?.english())
    }

    @Test
    fun availableTravelWithoutADeadlineDoesNotFabricateDepartureGuidance() {
        val guidance = guidance(snapshot().copy(leaveBy = null))

        assertFalse(guidance.headline.english().startsWith("Leave in "))
        assertFalse(guidance.headline.english() == "Leave now")
        assertNull(guidance.departureLine?.english())
        assertNull(guidance.bufferExplanation?.english())
        assertFalse(guidance.showWeatherCredit)
        assertTrue(guidance.showTravelDetails)
    }

    @Test
    fun missingDestinationLocationAndRouteNeverInventADepartureCountdown() {
        val unknownTravel = snapshot(ContextState.UPCOMING).copy(estimate = null, leaveBy = null)
        val cases = listOf(
            unknownTravel.copy(building = null, distanceMeters = null),
            unknownTravel.copy(distanceMeters = null),
            unknownTravel
        )
        val messages = cases.map { snapshot ->
            val guidance = guidance(snapshot)
            assertEquals("Class starts in 35 minutes", guidance.headline.english())
            assertNull(guidance.departureLine?.english())
            assertNull(guidance.bufferExplanation?.english())
            assertTrue(guidance.showTravelDetails)
            requireNotNull(guidance.travelUnavailableMessage?.english())
        }

        assertTrue(messages.all(String::isNotBlank))
        assertEquals(3, messages.distinct().size)
    }

    @Test
    fun bufferExplanationUsesActualEngineComponentsWithoutTheOldFixedTenMinutes() {
        val guidance = guidance(snapshot().copy(
            leadMinutes = 3,
            weatherBufferMinutes = 4,
            entryAllowanceMinutes = 6
        ))
        val explanation = requireNotNull(guidance.bufferExplanation?.english())

        assertTrue(explanation.contains("3 min arrival buffer"))
        assertTrue(explanation.contains("4 min for weather"))
        assertTrue(explanation.contains("6 min to reach the room"))
        assertFalse(explanation.contains("10 min"))
        assertTrue(guidance.showWeatherCredit)
    }

    @Test
    fun weatherCreditAppearsOnlyWhenTheWeatherBufferIsExplained() {
        val withoutWeather = guidance(snapshot().copy(weatherBufferMinutes = 0))
        val inClass = guidance(snapshot(ContextState.IN_CLASS), nextClass.start.plusMinutes(5))

        assertFalse(withoutWeather.showWeatherCredit)
        assertNull(inClass.bufferExplanation?.english())
        assertFalse(inClass.showWeatherCredit)
        assertFalse(inClass.showTravelDetails)
    }

    @Test
    fun enRouteGuidanceDoesNotAskTheStudentToDepartAgain() {
        val guidance = guidance(snapshot(ContextState.EN_ROUTE))

        assertEquals("On your way", guidance.headline.english())
        assertNull(guidance.departureLine?.english())
        assertNull(guidance.bufferExplanation?.english())
        assertTrue(guidance.showTravelDetails)
    }

    @Test
    fun approximateRemainingTravelIsDescribedAsAboutRatherThanAnExactArrivalPromise() {
        val guidance = guidance(snapshot(ContextState.EN_ROUTE).copy(
            estimate = estimate.copy(source = EstimateSource.StraightLine)
        ))

        assertEquals("On your way", guidance.headline.english())
        assertEquals("About 23 min remaining · Class starts at 3:00 PM", guidance.detail?.english())
    }

    @Test
    fun arrivedGuidanceCountsDownToClassStartInsteadOfDeparture() {
        val guidance = guidance(snapshot(ContextState.ARRIVED), nextClass.start.minusMinutes(6))

        assertEquals("Class starts in 6 minutes", guidance.headline.english())
        assertNull(guidance.departureLine?.english())
        assertFalse(guidance.showTravelDetails)
    }

    @Test
    fun inClassGuidanceShowsTheClassEndInsteadOfAnExpiredDeadline() {
        val guidance = guidance(snapshot(ContextState.IN_CLASS), nextClass.start.plusMinutes(6))

        assertEquals("In class until 4:00 PM", guidance.headline.english())
        assertNull(guidance.departureLine?.english())
        assertNull(guidance.bufferExplanation?.english())
    }

    @Test
    fun calendarProgressDoesNotClaimTheStudentHasArrivedOrAskThemToLeave() {
        val snapshot = snapshot(ContextState.CLASS_IN_PROGRESS).copy(
            estimate = null, distanceMeters = null, leaveBy = null
        )
        val guidance = guidance(snapshot, nextClass.start.plusMinutes(6))
        val headline = guidance.headline.english()
        assertTrue(headline.startsWith("Class "))
        assertTrue(headline.contains("4:00 PM"))
        assertFalse(headline.startsWith("In class"))
        assertEquals("CURRENT CLASS", guidance.cardLabel.english())
        assertNull(guidance.departureLine?.english())
        assertNull(guidance.bufferExplanation?.english())
        assertFalse(guidance.showTravelDetails)
        assertFalse(guidance.showWeatherCredit)
    }

    @Test
    fun anInClassSnapshotBeforeTheStartShowsUpdatingGuidance() {
        val guidance = guidance(snapshot(ContextState.IN_CLASS), nextClass.start.minusNanos(1))
        assertEquals("Checking your next class", guidance.headline.english())
        assertFalse(guidance.headline.english().startsWith("In class"))
        assertNull(guidance.departureLine?.english())
        assertFalse(guidance.showTravelDetails)
    }

    @Test
    fun runningLateBeforeClassPromptsDepartureAndExplainsTheTravelShortfall() {
        val guidance = guidance(snapshot(ContextState.RUNNING_LATE), nextClass.start.minusMinutes(15))

        assertEquals("Leave now", guidance.headline.english())
        assertEquals("You may arrive about 8 minutes after class starts.", guidance.detail?.english())
        assertTrue(guidance.showTravelDetails)
    }

    @Test
    fun runningLateAfterClassStartShowsElapsedTimeAndRemainingTravel() {
        val guidance = guidance(snapshot(ContextState.RUNNING_LATE), nextClass.start.plusMinutes(5))

        assertEquals("Class started 5 minutes ago", guidance.headline.english())
        assertEquals("About 23 min remaining", guidance.detail?.english())
        assertTrue(guidance.showTravelDetails)
    }

    @Test
    fun elapsedClassTimeDoesNotRoundUpBeforeAMinuteHasActuallyPassed() {
        val snapshot = snapshot(ContextState.RUNNING_LATE)

        assertEquals("Class starts now", guidance(snapshot, nextClass.start).headline.english())
        assertEquals("Class started less than a minute ago", guidance(snapshot, nextClass.start.plusNanos(1)).headline.english())
        assertEquals("Class started less than a minute ago", guidance(snapshot, nextClass.start.plusSeconds(59)).headline.english())
        assertEquals("Class started 1 minute ago", guidance(snapshot, nextClass.start.plusMinutes(1)).headline.english())
        assertEquals("Class started 1 minute ago", guidance(snapshot, nextClass.start.plusSeconds(119)).headline.english())
    }

    @Test
    fun longBreakUsesTheEngineDeadlineForTheFreeTime() {
        val later = nextClass.copy(start = nextClass.start.plusHours(2), end = nextClass.end.plusHours(2))
        val snapshot = snapshot(ContextState.LONG_BREAK).copy(
            session = later,
            leaveBy = now.plusMinutes(90)
        )

        val guidance = guidance(snapshot, sessions = listOf(later))

        assertEquals("Free for 1 hour 30 minutes", guidance.headline.english())
        assertEquals("Leave by 3:55 PM", guidance.departureLine?.english())
        assertTrue(guidance.showTravelDetails)
    }

    @Test
    fun longBreakWithoutTravelDoesNotClaimTheWholeTimeUntilClassIsFree() {
        val guidance = guidance(snapshot(ContextState.LONG_BREAK).copy(estimate = null, leaveBy = null))

        assertEquals("A break before your next class", guidance.headline.english())
        assertFalse(guidance.headline.english().contains("Free"))
        assertTrue(requireNotNull(guidance.detail?.english()).contains("Next class starts at 3:00 PM"))
        assertNull(guidance.departureLine?.english())
        assertNull(guidance.bufferExplanation?.english())
        assertTrue(guidance.showTravelDetails)
        assertTrue(requireNotNull(guidance.travelUnavailableMessage?.english()).isNotBlank())
    }

    @Test
    fun postClassRetainsTheFinishedSessionAndKeepsTheNextClassGuidanceCalm() {
        val finished = finishedClass()
        val snapshot = snapshot(ContextState.POST_CLASS).copy(
            finishedSession = finished,
            leaveBy = now.plusMinutes(20)
        )
        val card = buildHomeCardState(listOf(finished, nextClass), snapshot, now)

        val guidance = buildHomeCardGuidance(card, now)

        assertEquals(ContextState.POST_CLASS, card.state)
        assertEquals(finished, card.finishedSession)
        assertEquals(snapshot.leaveBy, card.leaveBy)
        assertEquals("Next class starts at 3:00 PM", guidance.headline.english())
        assertFalse(guidance.headline.english().startsWith("Leave"))
        assertNull(guidance.departureLine?.english())
    }

    @Test
    fun postClassWithoutAnotherClassShowsTheFinishedCourse() {
        val finished = finishedClass()
        val guidance = guidance(
            TravelSnapshot(state = ContextState.POST_CLASS, finishedSession = finished),
            sessions = listOf(finished)
        )

        assertEquals("COMP90018 just finished", guidance.headline.english())
        assertNull(guidance.departureLine?.english())
        assertFalse(guidance.showTravelDetails)
    }

    @Test
    fun noMoreClassesTodayShowsTheNextTeachingDayWithoutAPromptToLeave() {
        val tomorrow = nextClass.copy(start = nextClass.start.plusDays(1), end = nextClass.end.plusDays(1))
        val guidance = guidance(
            TravelSnapshot(session = tomorrow, state = ContextState.NO_UPCOMING_CLASS),
            sessions = listOf(tomorrow)
        )

        assertEquals("No more classes today", guidance.headline.english())
        assertEquals("Next class starts on Tue, 13 Oct at 3:00 PM", guidance.detail?.english())
        assertNull(guidance.departureLine?.english())
        assertFalse(guidance.showTravelDetails)
    }

    @Test
    fun noUpcomingSessionExplainsTheEmptyTimetableWithoutTravelDetails() {
        val guidance = guidance(TravelSnapshot(), sessions = emptyList())

        assertEquals("No more classes today", guidance.headline.english())
        assertEquals("Your timetable has no upcoming classes.", guidance.detail?.english())
        assertNull(guidance.departureLine?.english())
        assertNull(guidance.bufferExplanation?.english())
        assertFalse(guidance.showTravelDetails)
    }

    @Test
    fun startupAndStaleSnapshotsCannotSurfaceAnotherClasssDepartureDetails() {
        val startup = guidance(TravelSnapshot())
        val stale = guidance(snapshot().copy(session = nextClass.copy(id = "stale-class")))

        for (guidance in listOf(startup, stale)) {
            assertFalse(guidance.headline.english().startsWith("Leave in "))
            assertFalse(guidance.headline.english() == "Leave now")
            assertNull(guidance.departureLine?.english())
            assertNull(guidance.bufferExplanation?.english())
            assertFalse(guidance.showTravelDetails)
            assertFalse(guidance.showWeatherCredit)
        }
    }

    @Test
    fun anUpdatingClassAlreadyInProgressDoesNotClaimItIsTheNextClass() {
        val guidance = guidance(TravelSnapshot(), nextClass.start.plusMinutes(1))

        assertEquals("CURRENT CLASS", guidance.cardLabel.english())
        assertEquals("Updating class guidance", guidance.headline.english())
        assertNull(guidance.departureLine?.english())
        assertFalse(guidance.showTravelDetails)
    }

    @Test
    fun displayedTimesUseTheClassZoneEvenWhenTheClockAndDeadlineUseOtherZones() {
        val snapshot = snapshot().copy(
            leaveBy = now.plusMinutes(5).withZoneSameInstant(ZoneId.of("UTC"))
        )
        val overseasClock = now.withZoneSameInstant(ZoneId.of("America/New_York"))

        val overseasGuidance = guidance(snapshot, overseasClock)

        assertEquals("Leave in 5 minutes", overseasGuidance.headline.english())
        assertEquals("Leave by 2:30 PM", overseasGuidance.departureLine?.english())
        val inClass = guidance(snapshot.copy(state = ContextState.IN_CLASS), nextClass.start.withZoneSameInstant(ZoneId.of("UTC")))
        assertEquals("In class until 4:00 PM", inClass.headline.english())
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

    private fun guidance(
        snapshot: TravelSnapshot,
        at: ZonedDateTime = now,
        sessions: List<CourseSession> = listOf(nextClass)
    ): HomeCardGuidance = buildHomeCardGuidance(buildHomeCardState(sessions, snapshot, at), at)
}
