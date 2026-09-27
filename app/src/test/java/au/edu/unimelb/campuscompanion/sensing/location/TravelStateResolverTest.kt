package au.edu.unimelb.campuscompanion.sensing.location

import org.junit.Assert.assertEquals
import org.junit.Test

class TravelStateResolverTest {

    @Test
    fun enRouteOutsideArrivalRadius_staysEnRoute() {
        val result = TravelStateResolver.resolve(
            currentState = TravelState.EN_ROUTE,
            distanceMeters = 120.0
        )

        assertEquals(TravelState.EN_ROUTE, result)
    }

    @Test
    fun enRouteInsideArrivalRadius_becomesArrived() {
        val result = TravelStateResolver.resolve(
            currentState = TravelState.EN_ROUTE,
            distanceMeters = 60.0
        )

        assertEquals(TravelState.ARRIVED, result)
    }

    @Test
    fun exactly75m_becomesArrived() {
        val result = TravelStateResolver.resolve(
            currentState = TravelState.EN_ROUTE,
            distanceMeters = 75.0
        )

        assertEquals(TravelState.ARRIVED, result)
    }

    @Test
    fun upcomingClassBeforeLeaveTime_staysUpcomingClass() {
        val result = TravelStateResolver.resolve(
            currentState = TravelState.UPCOMING_CLASS,
            distanceMeters = 500.0,
            minutesUntilClass = 40,
            estimatedTravelMinutes = 24
        )

        assertEquals(
            TravelState.UPCOMING_CLASS,
            result
        )
    }

    @Test
    fun upcomingClassAtLeaveTime_becomesShouldLeaveSoon() {
        val result = TravelStateResolver.resolve(
            currentState = TravelState.UPCOMING_CLASS,
            distanceMeters = 500.0,
            minutesUntilClass = 29,
            estimatedTravelMinutes = 24
        )

        assertEquals(
            TravelState.SHOULD_LEAVE_SOON,
            result
        )
    }

    @Test
    fun shouldLeaveSoonNotMoving_staysShouldLeaveSoon() {
        val result = TravelStateResolver.resolve(
            currentState = TravelState.SHOULD_LEAVE_SOON,
            distanceMeters = 500.0,
            isMoving = false
        )

        assertEquals(
            TravelState.SHOULD_LEAVE_SOON,
            result
        )
    }

    @Test
    fun shouldLeaveSoonWhenMoving_becomesEnRoute() {
        val result = TravelStateResolver.resolve(
            currentState = TravelState.SHOULD_LEAVE_SOON,
            distanceMeters = 500.0,
            isMoving = true
        )

        assertEquals(
            TravelState.EN_ROUTE,
            result
        )
    }
}

