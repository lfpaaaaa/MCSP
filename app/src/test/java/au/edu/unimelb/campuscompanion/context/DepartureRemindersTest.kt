package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.english

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.sensing.location.DistanceTrend
import au.edu.unimelb.campuscompanion.sensing.location.TravelState
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.DEFAULT_COURSE_REMINDER_LEAD_MINUTES
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DepartureRemindersTest {
    private var preferences = CourseReminderPreference()
    private val sent = mutableListOf<DepartureReminder>()
    private val reminders = DepartureReminders(
        snapshots = flowOf(),
        preferences = { preferences },
        notify = sent::add
    )

    private val lecture = session("lecture", "COMP90018", "Mobile Computing Systems Programming")
    private val tutorial = session("tutorial", "SWEN90006", "Software Testing")

    @Test fun defaultReminderSaysToLeaveWithinTenMinutes() {
        assertNull(reminders.consider(snapshot(lecture, 26)))
        val reminder = reminders.consider(snapshot(lecture, 25))!!
        assertEquals("Leave within 10 min · COMP90018", reminder.title.english())
        assertEquals("Mobile Computing Systems Programming starts at 11:00 AM. Travel time: 15 min on foot. Leave by 10:45 AM (within 10 min).", reminder.text.english())
        assertNull(reminders.consider(snapshot(lecture, 24)))
        assertEquals(1, sent.size)
    }
    @Test fun elevenAmClassAndTwentyMinuteTripNotifiesAtTenThirty() {
        val before = snapshot(lecture, 30).copy(estimate = estimate(20))
        assertEquals("Leave within 10 min · COMP90018", reminders.consider(before)?.title?.english())
        assertTrue(sent.single().text.english().contains("Leave by 10:40 AM"))
    }
    @Test fun exactBoundaryDoesNotFireOneSecondEarly() {
        val boundary = lecture.start.toInstant().minusSeconds(25 * 60)
        assertNull(reminders.consider(snapshot(lecture, 25).copy(evaluatedAt = boundary.minusSeconds(1))))
        assertNotNull(reminders.consider(snapshot(lecture, 25).copy(evaluatedAt = boundary)))
    }
    @Test fun theDeadlineUpdatesToLeaveNowOnlyOnce() {
        reminders.consider(snapshot(lecture, 25))
        val now = reminders.consider(snapshot(lecture, 15))!!
        assertEquals("Leave now for COMP90018", now.title.english())
        assertEquals(sent[0].notificationId, now.notificationId)
        assertNull(reminders.consider(snapshot(lecture, 10)))
    }
    @Test fun nextClassGetsItsOwnReminder() {
        reminders.consider(snapshot(lecture, 25))
        reminders.consider(snapshot(tutorial, 25))
        assertEquals(2, sent.size)
        assertEquals(sent[0].notificationId, sent[1].notificationId)
    }

    @Test
    fun theNextClassGetsItsOwnReminder() {
        reminders.consider(snapshot(lecture, ContextState.LEAVE_SOON))
        reminders.consider(snapshot(lecture, ContextState.ARRIVED))

        val next = reminders.consider(snapshot(tutorial, ContextState.LEAVE_SOON))

        assertEquals("Time to leave for SWEN90006", next?.title)
        assertEquals(2, sent.size)
    }

    @Test
    fun nothingIsSentWhileRemindersAreOff() {
        preferences = CourseReminderPreference(enabled = false)

        assertNull(reminders.consider(snapshot(lecture, ContextState.LEAVE_SOON)))
        assertTrue(sent.isEmpty())

        // Switching reminders back on for the same class still gives the reminder.
        preferences = CourseReminderPreference(enabled = true)
        val reminder = reminders.consider(snapshot(lecture, ContextState.LEAVE_SOON))
        assertEquals("Time to leave for COMP90018", reminder?.title)
    }
    @Test fun motionUiStateDoesNotCausePrematureOrStaleReminders() {
        assertNull(reminders.consider(snapshot(lecture, 40).copy(state = TravelState.SHOULD_LEAVE_SOON)))
        assertNotNull(reminders.consider(snapshot(lecture, 25).copy(state = TravelState.UPCOMING_CLASS)))
    }
    @Test fun arrivedOrAlreadyTravellingDoesNotReceiveDepartureAlert() {
        assertNull(reminders.consider(snapshot(lecture, 25).copy(state = TravelState.ARRIVED)))
        assertNull(reminders.consider(snapshot(lecture, 25).copy(state = TravelState.EN_ROUTE)))
    }
    @Test fun customLeadAndZeroLeadAreRespected() {
        preferences = CourseReminderPreference(leadMinutes = 5)
        assertNull(reminders.consider(snapshot(lecture, 25)))
        assertEquals("Leave within 5 min · COMP90018", reminders.consider(snapshot(lecture, 20))?.title?.english())
        preferences = CourseReminderPreference(leadMinutes = 0)
        assertNull(reminders.consider(snapshot(tutorial, 16)))
        assertEquals("Leave now for SWEN90006", reminders.consider(snapshot(tutorial, 15))?.title?.english())
    }
    @Test fun weatherMovesTheLeaveDeadlineEarlier() {
        val result = reminders.consider(snapshot(lecture, 30).copy(weatherBufferMinutes = 5))!!
        assertTrue(result.text.english().contains("Leave by 10:40 AM (within 10 min)"))
        assertTrue(result.text.english().contains("Includes 5 min extra for weather"))
    }
    @Test fun changedEtaCanBringReminderForward() {
        assertNull(reminders.consider(snapshot(lecture, 35)))
        val result = reminders.consider(snapshot(lecture, 35).copy(estimate = estimate(25)))!!
        assertEquals("Leave within 10 min · COMP90018", result.title.english())
        assertTrue(result.text.english().contains("Travel time: 25 min"))
    }
    @Test fun deniedNotificationsCanBeRetriedWithoutConsumingTheReminder() {
        var allowed = false
        val notifier = DepartureReminders(flowOf(), { preferences }, { if (allowed) sent.add(it) else false })
        assertNull(notifier.consider(snapshot(lecture, 25)))
        allowed = true
        assertNotNull(notifier.consider(snapshot(lecture, 24)))
        assertEquals(1, sent.size)
    }
    @Test fun persistedHistoryPreventsRepeatsAfterRestart() {
        val history = mutableSetOf<Pair<String, DepartureReminderStage>>()
        fun notifier() = DepartureReminders(flowOf(), { preferences }, sent::add,
            wasSent = { session, state -> (session.id to state) in history },
            rememberSent = { session, state -> history.add(session.id to state); Unit })
        notifier().consider(snapshot(lecture, 25))
        assertNull(notifier().consider(snapshot(lecture, 24)))
        assertEquals(1, sent.size)
    }

    @Test
    fun mutingTutorialsKeepsLecturesAndOtherCoursesEnabled() {
        val sameCourseLecture = lecture.copy(activity = "lecture")
        val sameCourseTutorial = lecture.copy(id = "comp-tutorial", activity = "tutorial")
        val mutedKey = sameCourseTutorial.reminderSeriesKey()
        val perActivity = DepartureReminders(
            snapshots = flowOf(),
            preferences = { CourseReminderPreference(enabled = it.reminderSeriesKey() != mutedKey) },
            notify = sent::add
        )

        assertNull(perActivity.consider(snapshot(sameCourseTutorial, ContextState.LEAVE_SOON)))
        assertNull(perActivity.consider(snapshot(sameCourseTutorial, ContextState.RUNNING_LATE)))
        perActivity.consider(snapshot(sameCourseLecture, ContextState.LEAVE_SOON))
        perActivity.consider(snapshot(tutorial, ContextState.LEAVE_SOON))

        assertEquals(listOf(sameCourseLecture.id, tutorial.id), sent.map { it.sessionId })
    }

    @Test
    fun preferenceChangesWithdrawRemindersAndRecheckTheCurrentClass() = runBlocking<Unit> {
        val current = lecture.copy(activity = "lecture")
        val snapshots = MutableStateFlow(snapshot(current, ContextState.LEAVE_SOON))
        val changes = MutableStateFlow(0)
        val shade = FakeDepartureShade()
        shade.showOther("earlier-class")
        preferences = CourseReminderPreference(enabled = false)
        val reactive = DepartureReminders(
            snapshots = snapshots,
            preferences = { preferences },
            notify = shade::post,
            preferenceChanges = changes.map { Unit },
            shade = shade
        )
        val job = launch { reactive.run() }
        try {
            // Turned off: no reminder for this course, and nothing else is left in the shade.
            withTimeout(2_000) { while (shade.withdrawn.isEmpty()) delay(1) }
            assertTrue(shade.alerts.isEmpty())
            preferences = CourseReminderPreference(enabled = true)
            changes.value++
            withTimeout(2_000) { while (shade.alerts.isEmpty()) delay(1) }
            assertEquals(current.reminderSeriesKey(), shade.alerts.single().courseKey)

            shade.withdrawn.clear()
            preferences = CourseReminderPreference(enabled = false)
            changes.value++
            withTimeout(2_000) { while (shade.withdrawn.isEmpty()) delay(1) }
            assertEquals(listOf(current.departurePromptKey()), shade.withdrawn)
            assertTrue(shade.shown.isEmpty())
            assertNull(reactive.consider(snapshot(current, ContextState.RUNNING_LATE)))
            assertEquals(1, shade.alerts.size)
        } finally {
            job.cancel()
            job.join()
        }
    }
    @Test fun stationaryUserGetsUrgentReminderFiveMinutesBeforeDeparture() {
        reminders.consider(snapshot(lecture, 25))
        val warning = reminders.consider(snapshot(lecture, 20).copy(destinationProgress = DistanceTrend.STABLE))!!
        assertEquals(DepartureReminderStage.LeaveNow, warning.stage)
        assertEquals("Leave now for COMP90018", warning.title.english())
        assertTrue(warning.text.english().contains("Please leave immediately"))
        assertEquals(sent.first().notificationId, warning.notificationId)
        assertEquals(2, sent.size)
        assertNull(reminders.consider(snapshot(lecture, 19).copy(destinationProgress = DistanceTrend.STABLE)))
        assertNull(reminders.consider(snapshot(lecture, 15))) // No third alert at the original deadline.
    }
    @Test fun urgentBoundaryDoesNotFireOneSecondEarly() {
        val atFive = snapshot(lecture, 20).copy(destinationProgress = DistanceTrend.STABLE,
            evaluatedAt = lecture.start.toInstant().minusSeconds(20 * 60))
        val earlier = reminders.consider(atFive.copy(evaluatedAt = atFive.evaluatedAt!!.minusSeconds(1)))!!
        assertEquals(DepartureReminderStage.Prepare, earlier.stage)
        assertEquals(DepartureReminderStage.LeaveNow, reminders.consider(atFive)?.stage)
    }
    @Test fun travellingAwayCanAlertEvenIfOldUiStateSaysEnRoute() {
        val warning = reminders.consider(snapshot(lecture, 20).copy(
            state = TravelState.EN_ROUTE, destinationProgress = DistanceTrend.MOVING_AWAY))!!
        assertEquals(DepartureReminderStage.LeaveNow, warning.stage)
    }
    @Test fun approachingOrArrivedDoesNotGetUrgentReminder() {
        assertNull(reminders.consider(snapshot(lecture, 20).copy(destinationProgress = DistanceTrend.APPROACHING)))
        assertNull(reminders.consider(snapshot(lecture, 20).copy(
            state = TravelState.ARRIVED, destinationProgress = DistanceTrend.STABLE)))
        assertTrue(sent.isEmpty())
    }
    @Test fun unknownDirectionDoesNotClaimTheUserHasNotDeparted() {
        val warning = reminders.consider(snapshot(lecture, 20))!!
        assertEquals(DepartureReminderStage.Prepare, warning.stage)
        assertFalse(warning.text.english().contains("Please leave immediately"))
    }
    @Test fun changingTravelTimeReevaluatesTheUrgentBoundary() {
        val position = snapshot(lecture, 30).copy(destinationProgress = DistanceTrend.STABLE)
        assertNull(reminders.consider(position))
        assertEquals(DepartureReminderStage.LeaveNow, reminders.consider(position.copy(estimate = estimate(25)))?.stage)
        assertNull(reminders.consider(position.copy(estimate = estimate(20)))) // Never downgrade the alert.
    }
    @Test fun fiveMinuteUrgentCheckIsIndependentOfThePreparationSlider() {
        preferences = CourseReminderPreference(leadMinutes = 0)
        assertEquals(DepartureReminderStage.LeaveNow, reminders.consider(
            snapshot(lecture, 20).copy(destinationProgress = DistanceTrend.STABLE))?.stage)
    }
    @Test fun disabledCourseNoEtaAndEndedClassCannotTriggerUrgentReminder() {
        val position = snapshot(lecture, 20).copy(destinationProgress = DistanceTrend.STABLE)
        preferences = CourseReminderPreference(enabled = false)
        assertNull(reminders.consider(position))
        preferences = CourseReminderPreference()
        assertNull(reminders.consider(position.copy(estimate = null)))
        assertNull(reminders.consider(position.copy(evaluatedAt = lecture.end.toInstant())))
        assertTrue(sent.isEmpty())
    }
    @Test fun urgentWarningSurvivesRestartAndRetriesIfNotificationPermissionIsDenied() {
        val history = mutableSetOf<Pair<String, DepartureReminderStage>>()
        var allowed = true
        fun notifier() = DepartureReminders(flowOf(), { preferences }, { allowed && sent.add(it) },
            wasSent = { session, stage -> (session.id to stage) in history },
            rememberSent = { session, stage -> history.add(session.id to stage); Unit })
        notifier().consider(snapshot(lecture, 25))
        val stopped = snapshot(lecture, 20).copy(destinationProgress = DistanceTrend.STABLE)
        allowed = false
        assertNull(notifier().consider(stopped))
        allowed = true
        assertEquals(DepartureReminderStage.LeaveNow, notifier().consider(stopped)?.stage)
        assertNull(notifier().consider(stopped))
        assertEquals(2, sent.size)
    }
    @Test fun weatherBufferStillMovesTheUrgentCheckEarlier() {
        val warning = reminders.consider(snapshot(lecture, 25).copy(
            destinationProgress = DistanceTrend.STABLE, weatherBufferMinutes = 5))!!
        assertEquals(DepartureReminderStage.LeaveNow, warning.stage)
        assertTrue(warning.text.english().contains("Includes 5 min extra for weather"))
    }
    private fun estimate(minutes: Int = 15) = TravelEstimate(TravelMode.Walking, minutes * 60L, 1200, EstimateSource.Routing, Instant.parse("2026-10-12T00:00:00Z"))
    private fun snapshot(session: CourseSession, minutesUntil: Long) = TravelSnapshot(session = session,
        building = FakeBuildingLookup.PETER_HALL, estimate = estimate(), distanceMeters = 1200.0, minutesUntilClass = minutesUntil)
    private fun session(id: String, code: String, title: String): CourseSession {
        val start = ZonedDateTime.of(2026, 10, 12, 11, 0, 0, 0, ZoneId.of("Australia/Melbourne"))
        return CourseSession(
            id = id,
            code = code,
            title = title,
            location = "Parkville Campus",
            room = "PAR-160",
            start = start,
            end = start.plusHours(1)
        )
    }
}
