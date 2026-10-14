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
                warningAt = start.withHour(10).withMinute(12),
                travelMinutes = 20
            ),
            lecture.reminderTiming(snapshot, arrivalBufferMinutes = 10)
        )
    }

    @Test
    fun zeroArrivalBufferStillWarnsTenMinutesBeforeDeparture() {
        val snapshot = liveSnapshot(arrivalBufferMinutes = 0)

        assertEquals(
            CourseReminderTiming.Ready(
                leaveBy = start.minusMinutes(20),
                warningAt = start.minusMinutes(30),
                travelMinutes = 20
            ),
            lecture.reminderTiming(snapshot, arrivalBufferMinutes = 0)
        )
    }

    @Test
    fun zeroPreparationWarnsAtLeaveByWithoutChangingTheDeadline() {
        val snapshot = liveSnapshot(arrivalBufferMinutes = 0, preparationMinutes = 0)

        assertEquals(
            CourseReminderTiming.Ready(
                leaveBy = start.minusMinutes(20),
                warningAt = start.minusMinutes(20),
                travelMinutes = 20
            ),
            lecture.reminderTiming(snapshot, arrivalBufferMinutes = 0, preparationMinutes = 0)
        )
    }

    @Test
    fun maximumPreparationMovesOnlyTheWarningThirtyMinutesEarlier() {
        val snapshot = liveSnapshot(arrivalBufferMinutes = 30, preparationMinutes = 30)

        assertEquals(
            CourseReminderTiming.Ready(
                leaveBy = start.minusMinutes(50),
                warningAt = start.minusMinutes(80),
                travelMinutes = 20
            ),
            lecture.reminderTiming(snapshot, arrivalBufferMinutes = 30, preparationMinutes = 30)
        )
    }

    @Test
    fun changingPreparationHidesTheOldWarningUntilTheEngineAppliesIt() {
        val snapshot = liveSnapshot(arrivalBufferMinutes = 5, preparationMinutes = 5)
        assertEquals(
            CourseReminderTiming.Updating,
            lecture.reminderTiming(snapshot, arrivalBufferMinutes = 5, preparationMinutes = 20)
        )

        val updated = snapshot.copy(preparationMinutes = 20)
        assertEquals(
            CourseReminderTiming.Ready(
                leaveBy = start.minusMinutes(25),
                warningAt = start.minusMinutes(45),
                travelMinutes = 20
            ),
            lecture.reminderTiming(updated, arrivalBufferMinutes = 5, preparationMinutes = 20)
        )
        assertEquals(
            CourseReminderTiming.Updating,
            lecture.reminderTiming(updated, arrivalBufferMinutes = 5, preparationMinutes = 0)
        )
    }

    @Test
    fun threePmClassWithTwentyFiveMinuteTripMatchesTheTwoSliderExamples() {
        val session = lecture.copy(start = start.withHour(15), end = start.withHour(16))
        val estimate = travel.copy(durationSeconds = 25 * 60L)
        // Arrival buffer, preparation, leave-by minute, warning minute (all in the 14:00 hour).
        val examples = listOf(
            listOf(5, 5, 30, 25),
            listOf(5, 10, 30, 20),
            listOf(5, 20, 30, 10),
            listOf(10, 10, 25, 15)
        )

        for ((arrivalBuffer, preparation, leaveMinute, warningMinute) in examples) {
            val snapshot = TravelSnapshot(
                session = session,
                estimate = estimate,
                leadMinutes = arrivalBuffer,
                preparationMinutes = preparation,
                leaveBy = ContextEngine.leaveBy(
                    session.start.toInstant(),
                    estimate.durationMinutes,
                    arrivalBuffer,
                    entryAllowanceMinutes = 0
                )!!.atZone(start.zone)
            )
            assertEquals(
                "Arrival buffer $arrivalBuffer, preparation $preparation",
                CourseReminderTiming.Ready(
                    leaveBy = start.withHour(14).withMinute(leaveMinute),
                    warningAt = start.withHour(14).withMinute(warningMinute),
                    travelMinutes = 25
                ),
                session.reminderTiming(snapshot, arrivalBuffer, preparation)
            )
        }
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
                warningAt = start.minusMinutes(50),
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
    fun sameIdVenueTimeCourseAndActivityEditsCannotPreviewAnOldDeadline() {
        val snapshot = liveSnapshot()
        val changed = listOf(
            lecture.copy(code = "SWEN90014"),
            lecture.copy(activity = "workshop"),
            lecture.copy(location = "Southbank"),
            lecture.copy(room = "PAR-200"),
            lecture.copy(start = start.plusMinutes(5)),
            lecture.copy(end = lecture.end.plusMinutes(5))
        )

        for (updated in changed) {
            assertEquals(
                "An edited class must wait for a matching snapshot",
                CourseReminderTiming.NotTracked,
                updated.reminderTiming(snapshot, 10)
            )
        }
    }

    @Test
    fun displayTitleAndRouteEnrichmentStillAllowTheMatchingLiveDeadline() {
        val snapshot = liveSnapshot()
        val enriched = lecture.copy(
            title = "Updated course display name",
            etaMinutes = 45,
            routeEstimate = RouteEstimate(distanceMeters = 9_999, walkingMinutes = 45)
        )

        assertEquals(lecture.reminderTiming(snapshot, 10), enriched.reminderTiming(snapshot, 10))
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

    private fun liveSnapshot(
        arrivalBufferMinutes: Int = 10,
        preparationMinutes: Int = DEFAULT_COURSE_REMINDER_PREPARATION_MINUTES
    ) = TravelSnapshot(
        session = lecture,
        estimate = travel,
        leadMinutes = arrivalBufferMinutes,
        preparationMinutes = preparationMinutes,
        leaveBy = ContextEngine.leaveBy(
            start = start.toInstant(),
            travelMinutes = travel.durationMinutes,
            bufferMinutes = arrivalBufferMinutes,
            entryAllowanceMinutes = 0
        )!!.atZone(start.zone)
    )
}
