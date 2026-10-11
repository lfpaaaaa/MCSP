package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.context.ContextEngine
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class CourseReminderTimingTest {
    private val start = ZonedDateTime.of(2026, 10, 12, 11, 0, 0, 0, ZoneId.of("Australia/Melbourne"))
    private val lecture = CourseSession(
        id = "comp90018-lecture",
        code = "COMP90018",
        title = "Mobile Computing Systems Programming",
        location = "Parkville",
        room = "PAR-160",
        start = start,
        end = start.plusHours(1),
        etaMinutes = 99
    )
    private val travel = TravelEstimate(
        mode = TravelMode.Walking,
        durationSeconds = 20 * 60L,
        distanceMeters = 1_400,
        source = EstimateSource.Routing,
        computedAt = start.minusHours(1).toInstant()
    )

    @Test
    fun liveEngineDeadlineIncludesWeatherAndEntryWithoutUsingTimetableEta() {
        val deadline = ContextEngine.leaveBy(
            start = start.toInstant(),
            travelMinutes = travel.durationMinutes,
            bufferMinutes = 10 + 5,
            entryAllowanceMinutes = 3
        )!!.atZone(start.zone)
        val snapshot = liveSnapshot().copy(
            weatherBufferMinutes = 5,
            entryAllowanceMinutes = 3,
            leaveBy = deadline
        )

        assertEquals(start.withHour(10).withMinute(22), deadline)
        assertEquals(
            CourseReminderTiming.Ready(
                leaveBy = deadline,
                warningAt = start.withHour(10).withMinute(17),
                travelMinutes = 20
            ),
            lecture.reminderTiming(snapshot, arrivalBufferMinutes = 10)
        )
    }

    @Test
    fun zeroArrivalBufferStillWarnsFiveMinutesBeforeDeparture() {
        val snapshot = liveSnapshot(arrivalBufferMinutes = 0)

        assertEquals(
            CourseReminderTiming.Ready(
                leaveBy = start.minusMinutes(20),
                warningAt = start.minusMinutes(25),
                travelMinutes = 20
            ),
            lecture.reminderTiming(snapshot, arrivalBufferMinutes = 0)
        )
    }

    @Test
    fun changingTheBufferHidesTheOldTimeUntilTheEngineHasAppliedTheNewChoice() {
        val oldSnapshot = liveSnapshot(arrivalBufferMinutes = 10)
        assertEquals(
            CourseReminderTiming.Updating,
            lecture.reminderTiming(oldSnapshot, arrivalBufferMinutes = 20)
        )

        val updatedSnapshot = liveSnapshot(arrivalBufferMinutes = 20)
        assertEquals(
            CourseReminderTiming.Ready(
                leaveBy = start.minusMinutes(40),
                warningAt = start.minusMinutes(45),
                travelMinutes = 20
            ),
            lecture.reminderTiming(updatedSnapshot, arrivalBufferMinutes = 20)
        )

        // A quick second change must not display the previous choice's deadline either.
        assertEquals(
            CourseReminderTiming.Updating,
            lecture.reminderTiming(updatedSnapshot, arrivalBufferMinutes = 10)
        )
    }

    @Test
    fun anotherClassOrAnotherOccurrenceCannotUseTheTrackedRoutesTiming() {
        val snapshot = liveSnapshot()
        val nextWeek = lecture.copy(start = start.plusWeeks(1), end = lecture.end.plusWeeks(1))
        val otherClass = lecture.copy(id = "comp90018-tutorial")

        assertEquals(CourseReminderTiming.NotTracked, nextWeek.reminderTiming(snapshot, 10))
        assertEquals(CourseReminderTiming.NotTracked, otherClass.reminderTiming(snapshot, 10))
        assertEquals(
            CourseReminderTiming.NotTracked,
            lecture.reminderTiming(snapshot.copy(session = null), 10)
        )
    }

    @Test
    fun theSameOccurrenceInAnotherTimeZoneStillUsesTheLiveDeadline() {
        val sameOccurrence = lecture.copy(
            start = start.withZoneSameInstant(ZoneId.of("UTC")),
            end = lecture.end.withZoneSameInstant(ZoneId.of("UTC"))
        )
        val snapshot = liveSnapshot()

        assertEquals(
            lecture.reminderTiming(snapshot, 10),
            sameOccurrence.reminderTiming(snapshot, 10)
        )
    }

    @Test
    fun aTimetableEtaCannotReplaceMissingLiveTravelInformation() {
        val snapshot = liveSnapshot()

        assertEquals(
            CourseReminderTiming.MissingTravelTime,
            lecture.reminderTiming(snapshot.copy(estimate = null), 10)
        )
        assertEquals(
            CourseReminderTiming.MissingTravelTime,
            lecture.reminderTiming(snapshot.copy(leaveBy = null), 10)
        )
        assertEquals(
            CourseReminderTiming.MissingTravelTime,
            lecture.reminderTiming(snapshot.copy(estimate = null, leaveBy = null), 10)
        )
    }

    private fun liveSnapshot(arrivalBufferMinutes: Int = 10) = TravelSnapshot(
        session = lecture,
        estimate = travel,
        leadMinutes = arrivalBufferMinutes,
        leaveBy = ContextEngine.leaveBy(
            start = start.toInstant(),
            travelMinutes = travel.durationMinutes,
            bufferMinutes = arrivalBufferMinutes,
            entryAllowanceMinutes = 0
        )!!.atZone(start.zone)
    )
}
