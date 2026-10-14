package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** The two settings have separate jobs: arrival buffer sets departure, preparation sets warning. */
class ContextPreparationTimingTest {
    private val lecture = CourseSession(
        id = "lecture", code = "COMP90018", title = "Mobile Computing", activity = "lecture",
        location = "Parkville", room = "PAR-160", start = at(15, 0).atZone(ZONE),
        end = at(16, 0).atZone(ZONE)
    )

    @Test
    fun arrivalAndPreparationSettingsMatchTheWorkedExamples() {
        data class Example(val arrival: Int, val preparation: Int, val leave: Instant, val warning: Instant)
        listOf(
            Example(5, 5, at(14, 30), at(14, 25)),
            Example(5, 10, at(14, 30), at(14, 20)),
            Example(5, 20, at(14, 30), at(14, 10)),
            Example(10, 10, at(14, 25), at(14, 15)),
            Example(5, 30, at(14, 30), at(14, 0))
        ).forEach { example ->
            val inputs = inputs(example.warning, example.arrival, example.preparation)
            val before = ContextEngine.decide(inputs.copy(now = example.warning.minusSeconds(1)))
            val due = ContextEngine.decide(inputs)
            assertEquals(example.leave, before.leaveBy)
            assertEquals(example.leave, due.leaveBy)
            assertEquals(ContextState.UPCOMING, before.state)
            assertEquals(ContextState.LEAVE_SOON, due.state)
        }
    }

    @Test
    fun zeroPreparationWarnsAtLeaveByWithoutIntroducingAnotherState() {
        val departure = at(14, 30)
        val inputs = inputs(departure, arrival = 5, preparation = 0)
        assertEquals(ContextState.UPCOMING, ContextEngine.decide(inputs.copy(now = departure.minusSeconds(1))).state)
        assertEquals(ContextState.LEAVE_SOON, ContextEngine.decide(inputs).state)
        assertEquals(ContextState.LEAVE_SOON, ContextEngine.decide(inputs.copy(now = departure.plusSeconds(1))).state)
    }

    @Test
    fun preparationDoesNotChangeDepartureOrTheActualLatenessBoundary() {
        listOf(0, 5, 10, 20, 30).forEach { preparation ->
            val inputs = inputs(at(14, 35), arrival = 5, preparation = preparation)
            val lastOnTime = ContextEngine.decide(inputs)
            val late = ContextEngine.decide(inputs.copy(now = inputs.now.plusSeconds(1)))
            assertEquals(at(14, 30), lastOnTime.leaveBy)
            assertEquals(lastOnTime.leaveBy, late.leaveBy)
            assertEquals(ContextState.LEAVE_SOON, lastOnTime.state)
            assertEquals(ContextState.RUNNING_LATE, late.state)
        }
    }

    @Test
    fun walkingAndArrivalSuppressEverySupportedWarningWindow() {
        listOf(0, 5, 10, 20, 30).forEach { preparation ->
            val warning = at(14, 30).minusSeconds(preparation * 60L)
            val inputs = inputs(warning, arrival = 5, preparation = preparation)
            assertEquals(
                ContextState.EN_ROUTE,
                ContextEngine.decide(inputs.copy(isMoving = true, enRouteSignal = true)).state
            )
            assertEquals(
                ContextState.ARRIVED,
                ContextEngine.decide(inputs.copy(distanceMeters = 30.0)).state
            )
        }
    }

    @Test
    fun theThirtyMinuteMaximumDoesNotExpandTheExistingDepartureWindow() {
        val beforeDepartureWindow = inputs(at(13, 45).minusSeconds(1), arrival = 5, preparation = 30)
        assertEquals(
            ContextState.UPCOMING,
            ContextEngine.decide(beforeDepartureWindow.copy(isMoving = true, enRouteSignal = true)).state
        )
        assertEquals(
            ContextState.UPCOMING,
            ContextEngine.decide(beforeDepartureWindow.copy(distanceMeters = 30.0)).state
        )
        val windowOpens = beforeDepartureWindow.copy(now = at(13, 45))
        assertEquals(
            ContextState.EN_ROUTE,
            ContextEngine.decide(windowOpens.copy(isMoving = true, enRouteSignal = true)).state
        )
        assertEquals(ContextState.ARRIVED, ContextEngine.decide(windowOpens.copy(distanceMeters = 30.0)).state)
    }

    @Test
    fun preparationIsBoundedAndOverridesOnlyTheFallbackWarningConfiguration() {
        val config = ContextConfig(leaveSoonWindowMinutes = 2)
        val high = inputs(at(14, 0), arrival = 5, preparation = 90)
        assertEquals(ContextState.UPCOMING, ContextEngine.decide(high.copy(now = high.now.minusSeconds(1)), config = config).state)
        assertEquals(ContextState.LEAVE_SOON, ContextEngine.decide(high, config = config).state)
        val low = inputs(at(14, 30), arrival = 5, preparation = -1)
        assertEquals(ContextState.UPCOMING, ContextEngine.decide(low.copy(now = low.now.minusSeconds(1)), config = config).state)
        assertEquals(ContextState.LEAVE_SOON, ContextEngine.decide(low, config = config).state)
        val fallback = low.copy(now = at(14, 28), preparationMinutes = null)
        assertEquals(ContextState.LEAVE_SOON, ContextEngine.decide(fallback, config = config).state)
    }

    @Test
    fun preparationCannotCreateAWarningWithoutTravelTime() {
        val decision = ContextEngine.decide(inputs(at(14, 45), arrival = 5, preparation = 30).copy(travelMinutes = null))
        assertEquals(ContextState.UPCOMING, decision.state)
        assertNull(decision.leaveBy)
    }

    private fun inputs(now: Instant, arrival: Int, preparation: Int) = ContextInputs(
        now = now, sessions = listOf(lecture), travelMinutes = 25, bufferMinutes = arrival,
        distanceMeters = 2_000.0, isMoving = false, buildingCode = "PAR-160",
        preparationMinutes = preparation
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Australia/Melbourne")
        fun at(hour: Int, minute: Int): Instant =
            ZonedDateTime.of(2026, 10, 5, hour, minute, 0, 0, ZONE).toInstant()
    }
}
