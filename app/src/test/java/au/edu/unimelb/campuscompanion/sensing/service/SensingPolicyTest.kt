package au.edu.unimelb.campuscompanion.sensing.service

import au.edu.unimelb.campuscompanion.context.ContextConfig
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.FakeBuildingLookup
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.sensing.location.SensingTarget
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SensingPolicyTest {
    private val start = Instant.parse("2026-10-13T01:00:00Z")
    private val session = CourseSession(
        id = "lecture", code = "COMP90018", title = "Lecture", location = "Parkville",
        room = "PAR-160", start = start.atZone(ZoneId.of("Australia/Melbourne")),
        end = start.plusSeconds(60 * 60).atZone(ZoneId.of("Australia/Melbourne"))
    )
    private val target = SensingTarget(1L, 100L, true, session.id, FakeBuildingLookup.PETER_HALL)
    private val guidance = TravelSnapshot(
        session = session, building = target.building, state = ContextState.LEAVE_SOON,
        leaveBy = session.start.minusMinutes(25)
    )

    private fun mode(
        at: Instant,
        snapshot: TravelSnapshot = guidance,
        tracked: CourseSession? = session,
        sensingTarget: SensingTarget = target,
        eligible: Boolean = true,
        precise: Boolean = true,
        highRateOverride: Boolean = false,
        config: ContextConfig = ContextConfig()
    ): SensingMode = SensingPolicy.mode(eligible, precise, tracked, sensingTarget, snapshot, at, highRateOverride, config)

    @Test
    fun matchingLeaveByOpensSamplingAtExactlyFortyFiveMinutesBeforeDeparture() {
        val opens = guidance.leaveBy!!.toInstant().minusSeconds(45 * 60)
        assertEquals(SensingMode.NORMAL, mode(opens.minusNanos(1)))
        assertEquals(SensingMode.PRE_CLASS, mode(opens))
        assertEquals(SensingMode.PRE_CLASS, mode(start.minusNanos(1)))
    }

    @Test
    fun absentEstimateBootstrapsAtExactlyFortyFiveMinutesBeforeClassStart() {
        val opens = start.minusSeconds(45 * 60)
        for (snapshot in listOf(TravelSnapshot.PENDING, TravelSnapshot(), guidance.copy(leaveBy = null))) {
            assertEquals(SensingMode.NORMAL, mode(opens.minusNanos(1), snapshot))
            assertEquals(SensingMode.PRE_CLASS, mode(opens, snapshot))
        }
    }

    @Test
    fun pendingOrStaleGuidanceCannotOpenAnotherOccurrencesDepartureWindow() {
        val beforeBootstrap = start.minusSeconds(55 * 60)
        assertEquals(SensingMode.PRE_CLASS, mode(beforeBootstrap))
        val unusable = listOf(
            guidance.copy(pending = true),
            guidance.copy(session = null),
            guidance.copy(session = session.copy(id = "previous-lecture")),
            guidance.copy(session = session.copy(code = "COMP90024")),
            guidance.copy(session = session.copy(start = session.start.minusDays(1))),
            guidance.copy(session = session.copy(end = session.end.plusMinutes(1))),
            guidance.copy(building = FakeBuildingLookup.ALAN_GILBERT),
            guidance.copy(building = null)
        )
        unusable.forEach { snapshot ->
            assertEquals("Unmatched guidance must use class-start bootstrap", SensingMode.NORMAL,
                mode(beforeBootstrap, snapshot))
            assertEquals(SensingMode.PRE_CLASS, mode(start.minusSeconds(45 * 60), snapshot))
        }
    }

    @Test
    fun sameBuildingRoomAndTitleCorrectionsKeepUsableDepartureTiming() {
        val edited = session.copy(room = "PAR-160-201", title = "Updated lecture")
        assertEquals(SensingMode.PRE_CLASS, mode(start.minusSeconds(55 * 60), tracked = edited))
    }

    @Test
    fun confirmedTravelAndArrivalBeforeClassKeepHighRateOutsideDepartureWindow() {
        val wellBeforeWindow = start.minusSeconds(3 * 60 * 60)
        for (state in listOf(ContextState.EN_ROUTE, ContextState.ARRIVED)) {
            assertEquals(SensingMode.PRE_CLASS, mode(wellBeforeWindow, guidance.copy(state = state)))
            assertEquals(SensingMode.NORMAL, mode(wellBeforeWindow,
                guidance.copy(state = state, session = session.copy(id = "old"))))
        }
        assertEquals(SensingMode.NORMAL, mode(wellBeforeWindow, guidance.copy(state = ContextState.IN_CLASS)))
    }

    @Test
    fun lateGraceIsActiveFromClassStartThroughItsExclusiveEnd() {
        for (snapshot in listOf(TravelSnapshot.PENDING, guidance,
            guidance.copy(state = ContextState.CLASS_IN_PROGRESS), guidance.copy(state = ContextState.ARRIVED))) {
            assertEquals(SensingMode.PRE_CLASS, mode(start, snapshot))
            assertEquals(SensingMode.PRE_CLASS, mode(start.plusSeconds(15 * 60).minusNanos(1), snapshot))
            assertEquals(SensingMode.NORMAL, mode(start.plusSeconds(15 * 60), snapshot))
            assertEquals(SensingMode.NORMAL, mode(start.plusSeconds(20 * 60), snapshot))
        }
    }

    @Test
    fun unfinishedCurrentTripsKeepHighRateAfterFifteenMinutesUntilClassEnd() {
        for (state in listOf(ContextState.EN_ROUTE, ContextState.RUNNING_LATE)) {
            val current = guidance.copy(state = state)
            assertEquals(SensingMode.PRE_CLASS, mode(start.plusSeconds(15 * 60), current))
            assertEquals(SensingMode.PRE_CLASS, mode(session.end.toInstant().minusNanos(1), current))
            assertEquals(SensingMode.NORMAL, mode(session.end.toInstant(), current))
            assertEquals(SensingMode.NORMAL, mode(start.plusSeconds(20 * 60),
                current.copy(session = session.copy(id = "another-occurrence"))))
            assertEquals(SensingMode.NORMAL, mode(start.plusSeconds(20 * 60),
                current.copy(building = FakeBuildingLookup.ALAN_GILBERT)))
        }
        assertEquals(SensingMode.NORMAL, mode(start.plusSeconds(20 * 60),
            guidance.copy(state = ContextState.IN_CLASS)))
    }

    @Test
    fun classEndStopsHighRateEvenWhenItIsBeforeLateGraceExpires() {
        val shortClass = session.copy(end = session.start.plusMinutes(5))
        assertEquals(SensingMode.PRE_CLASS, mode(shortClass.end.toInstant().minusNanos(1), tracked = shortClass))
        assertEquals(SensingMode.NORMAL, mode(shortClass.end.toInstant(), tracked = shortClass))
        assertEquals(SensingMode.NORMAL, mode(session.end.toInstant()))
    }

    @Test
    fun missingOrDifferentTargetCannotUseClassTimingOrGuidance() {
        assertEquals(SensingMode.NORMAL, mode(start.minusSeconds(60), tracked = null))
        assertEquals(SensingMode.NORMAL, mode(start.minusSeconds(60), sensingTarget = target.copy(sessionId = "other")))
        assertEquals(SensingMode.NORMAL, mode(start.minusSeconds(60), sensingTarget = target.copy(sessionId = null)))
    }

    @Test
    fun accountPermissionAndInactiveRunStopEvenAnExplicitHighRateRequest() {
        for (highRateOverride in listOf(false, true)) {
            assertEquals(SensingMode.STOP, mode(start, eligible = false, highRateOverride = highRateOverride))
            assertEquals(SensingMode.STOP, mode(start, precise = false, highRateOverride = highRateOverride))
            assertEquals(SensingMode.STOP, mode(start, sensingTarget = target.copy(active = false), highRateOverride = highRateOverride))
        }
    }

    @Test
    fun authorizedNavigationOverrideSamplesOutsideClassWindow() {
        assertEquals(SensingMode.PRE_CLASS, mode(start.minusSeconds(4 * 60 * 60), highRateOverride = true))
        assertEquals(SensingMode.PRE_CLASS, mode(session.end.toInstant(), tracked = null, highRateOverride = true))
    }

    @Test
    fun configuredWindowsApplyToDepartureBootstrapAndLateGrace() {
        val config = ContextConfig(earlyDepartureWindowMinutes = 10, lateGraceMinutes = 3)
        val departureOpens = guidance.leaveBy!!.toInstant().minusSeconds(10 * 60)
        assertEquals(SensingMode.NORMAL, mode(departureOpens.minusNanos(1), config = config))
        assertEquals(SensingMode.PRE_CLASS, mode(departureOpens, config = config))
        val bootstrapOpens = start.minusSeconds(10 * 60)
        assertEquals(SensingMode.NORMAL, mode(bootstrapOpens.minusNanos(1), TravelSnapshot.PENDING, config = config))
        assertEquals(SensingMode.PRE_CLASS, mode(bootstrapOpens, TravelSnapshot.PENDING, config = config))
        assertEquals(SensingMode.PRE_CLASS, mode(start.plusSeconds(3 * 60).minusNanos(1), config = config))
        assertEquals(SensingMode.NORMAL, mode(start.plusSeconds(3 * 60), config = config))
    }
}
