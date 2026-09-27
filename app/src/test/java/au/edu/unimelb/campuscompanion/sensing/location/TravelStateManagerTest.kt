package au.edu.unimelb.campuscompanion.sensing.location

import org.junit.Assert.assertEquals
import org.junit.Test

class TravelStateManagerTest {

    @Test
    fun initialState_isUpcomingClass() {
        val manager = TravelStateManager()

        assertEquals(
            TravelState.UPCOMING_CLASS,
            manager.state.value
        )
    }

    @Test
    fun beforeLeaveThreshold_staysUpcomingClass() {
        val manager = TravelStateManager()

        manager.update(
            distanceMeters = 500.0,
            minutesUntilClass = 40,
            estimatedTravelMinutes = 24
        )

        assertEquals(
            TravelState.UPCOMING_CLASS,
            manager.state.value
        )
    }

    @Test
    fun atLeaveThreshold_becomesShouldLeaveSoon() {
        val manager = TravelStateManager()

        manager.update(
            distanceMeters = 500.0,
            minutesUntilClass = 29,
            estimatedTravelMinutes = 24
        )

        assertEquals(
            TravelState.SHOULD_LEAVE_SOON,
            manager.state.value
        )
    }

    @Test
    fun insideArrivalRadius_becomesArrived() {
        val manager = TravelStateManager()

        manager.update(
            distanceMeters = 60.0,
            minutesUntilClass = 20,
            estimatedTravelMinutes = 24
        )

        assertEquals(
            TravelState.ARRIVED,
            manager.state.value
        )
    }

    @Test
    fun shouldLeaveSoonNotMoving_staysShouldLeaveSoon() {
        val manager = TravelStateManager(
            initialState = TravelState.SHOULD_LEAVE_SOON
        )

        manager.update(
            distanceMeters = 500.0,
            isMoving = false
        )

        assertEquals(
            TravelState.SHOULD_LEAVE_SOON,
            manager.state.value
        )
    }

    @Test
    fun shouldLeaveSoonWhenMoving_becomesEnRoute() {
        val manager = TravelStateManager(
            initialState = TravelState.SHOULD_LEAVE_SOON
        )

        manager.update(
            distanceMeters = 500.0,
            isMoving = true
        )

        assertEquals(
            TravelState.EN_ROUTE,
            manager.state.value
        )
    }
}