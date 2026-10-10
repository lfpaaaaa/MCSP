package au.edu.unimelb.campuscompanion.ui.screens

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.RouteEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeScreenTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")
    private val now = ZonedDateTime.of(2026, 9, 27, 9, 0, 0, 0, melbourne)

    @Test
    fun homeAgendaOnlyContainsRemainingClassesTodayAndPromotesTheNearest() {
        val finishedToday = session("finished", 7, 8)
        val nearestToday = session("nearest", 10, 11)
        val laterToday = session("later", 15, 16)
        val tomorrow = session("tomorrow", 10, 11, dayOffset = 1)

        val agenda = buildHomeAgenda(
            sessions = listOf(tomorrow, laterToday, finishedToday, nearestToday),
            now = now
        )

        assertEquals("nearest", agenda.nextClass?.id)
        assertEquals(listOf("later"), agenda.todayClasses.map { it.id })
    }

    @Test
    fun nextClassMovesToTheFirstClassOnTheNextTeachingDay() {
        val finishedMonday = session("monday-finished", 7, 8)
        val firstWednesday = session("wednesday-first", 10, 11, dayOffset = 2)
        val secondWednesday = session("wednesday-second", 14, 15, dayOffset = 2)

        val agenda = buildHomeAgenda(
            sessions = listOf(secondWednesday, finishedMonday, firstWednesday),
            now = now
        )

        assertEquals("wednesday-first", agenda.nextClass?.id)
        assertEquals(emptyList<CourseSession>(), agenda.todayClasses)
    }

    @Test
    fun routeWithinWalkingThresholdUsesWalkingEstimate() {
        val session = session("nearby", 10, 11).copy(
            routeEstimate = RouteEstimate(
                distanceMeters = 800,
                walkingMinutes = 10,
                publicTransportMinutes = 7,
                drivingMinutes = 4
            )
        )

        val travel = selectTravelSummary(
            session = session,
            preferences = TravelPreferences(
                walkingThresholdMeters = 1_000,
                longerDistanceMode = TravelMode.Driving
            )
        )

        assertEquals(TravelMode.Walking, travel.mode)
        assertEquals(800, travel.distanceMeters)
        assertEquals(10, travel.durationMinutes)
    }

    @Test
    fun routeBeyondThresholdUsesSelectedLongerDistanceMode() {
        val session = session("far", 10, 11).copy(
            routeEstimate = RouteEstimate(
                distanceMeters = 2_400,
                walkingMinutes = 30,
                publicTransportMinutes = 14,
                drivingMinutes = 9
            )
        )

        val travel = selectTravelSummary(
            session = session,
            preferences = TravelPreferences(
                walkingThresholdMeters = 1_000,
                longerDistanceMode = TravelMode.PublicTransport
            )
        )

        assertEquals(TravelMode.PublicTransport, travel.mode)
        assertEquals(14, travel.durationMinutes)
    }

    @Test
    fun theModeWithATimeWinsOverAPreferredModeWithoutOne() {
        // The travel engine times one mode; the card shows that one even if the threshold would pick another.
        val session = session("edge", 10, 11).copy(
            routeEstimate = RouteEstimate(distanceMeters = 1_100, walkingMinutes = 14)
        )

        val travel = selectTravelSummary(
            session = session,
            preferences = TravelPreferences(walkingThresholdMeters = 1_000, longerDistanceMode = TravelMode.Driving)
        )

        assertEquals(TravelMode.Walking, travel.mode)
        assertEquals(14, travel.durationMinutes)
        assertEquals(1_100, travel.distanceMeters)
    }

    @Test
    fun aStraightLineEstimateIsMarkedApproximate() {
        val routed = session("routed", 10, 11).copy(
            routeEstimate = RouteEstimate(distanceMeters = 900, walkingMinutes = 12)
        )
        val estimated = session("estimated", 10, 11).copy(
            routeEstimate = RouteEstimate(distanceMeters = 900, walkingMinutes = 12, isApproximate = true)
        )

        assertEquals(false, selectTravelSummary(routed, TravelPreferences()).isApproximate)
        assertEquals(true, selectTravelSummary(estimated, TravelPreferences()).isApproximate)
    }

    @Test
    fun missingRouteDataDoesNotInventDistanceOrMode() {
        val travel = selectTravelSummary(
            session = session("pending", 10, 11),
            preferences = TravelPreferences()
        )

        assertNull(travel.distanceMeters)
        assertNull(travel.mode)
        assertNull(travel.durationMinutes)
    }

    private fun session(
        id: String,
        startHour: Int,
        endHour: Int,
        dayOffset: Long = 0
    ): CourseSession {
        val day = now.toLocalDate().plusDays(dayOffset)
        return CourseSession(
            id = id,
            code = "COMP90018",
            title = "Mobile Computing Systems Programming",
            location = "Parkville Campus",
            room = "PAR-160",
            start = day.atTime(startHour, 0).atZone(melbourne),
            end = day.atTime(endHour, 0).atZone(melbourne)
        )
    }
}
