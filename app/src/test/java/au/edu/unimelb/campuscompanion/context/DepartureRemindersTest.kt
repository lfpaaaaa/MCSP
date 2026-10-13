package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.english

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.sensing.location.TravelState
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DepartureRemindersTest {
    private var preferences = CourseReminderPreference()
    private val sent = mutableListOf<DepartureReminder>()
    private val reminders = DepartureReminders(flowOf(), { preferences }, sent::add)
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
        assertNull(reminders.consider(snapshot(lecture, 25)))
    }
    @Test fun disabledCourseDoesNotNotifyAndCanBeReenabled() {
        preferences = CourseReminderPreference(enabled = false)
        assertNull(reminders.consider(snapshot(lecture, 25)))
        preferences = CourseReminderPreference()
        assertNotNull(reminders.consider(snapshot(lecture, 25)))
    }
    @Test fun noEtaMeansNoGuessedDepartureNotification() {
        assertNull(reminders.consider(snapshot(lecture, 25).copy(estimate = null)))
        assertTrue(sent.isEmpty())
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
        val history = mutableSetOf<Pair<String, TravelState>>()
        fun notifier() = DepartureReminders(flowOf(), { preferences }, sent::add,
            wasSent = { session, state -> (session.id to state) in history },
            rememberSent = { session, state -> history.add(session.id to state); Unit })
        notifier().consider(snapshot(lecture, 25))
        assertNull(notifier().consider(snapshot(lecture, 24)))
        assertEquals(1, sent.size)
    }
    @Test fun mutingTutorialKeepsLectureAndWorkshopEnabled() {
        val tut = lecture.copy(id = "tut", activity = "tutorial")
        val notifier = DepartureReminders(flowOf(), { CourseReminderPreference(enabled = it.reminderSeriesKey() != tut.reminderSeriesKey()) }, sent::add)
        assertNull(notifier.consider(snapshot(tut, 25)))
        notifier.consider(snapshot(lecture.copy(activity = "lecture"), 25))
        notifier.consider(snapshot(lecture.copy(id = "work", activity = "workshop"), 25))
        assertEquals(2, sent.size)
    }
    @Test fun preferenceChangesCancelAndReevaluateInBackground() = runBlocking<Unit> {
        val changes = MutableStateFlow(0)
        val cancelled = mutableListOf<String>()
        preferences = CourseReminderPreference(enabled = false)
        val notifier = DepartureReminders(MutableStateFlow(snapshot(lecture, 25)), { preferences }, sent::add,
            preferenceChanges = changes.map { Unit }, cancelCourse = cancelled::add)
        val job = launch { notifier.run() }
        try {
            withTimeout(2000) { while (cancelled.isEmpty()) delay(1) }
            preferences = CourseReminderPreference()
            changes.value++
            withTimeout(2000) { while (sent.isEmpty()) delay(1) }
            assertEquals(lecture.reminderSeriesKey(), sent.single().courseKey)
        } finally { job.cancel(); job.join() }
    }
    private fun estimate(minutes: Int = 15) = TravelEstimate(TravelMode.Walking, minutes * 60L, 1200, EstimateSource.Routing, Instant.parse("2026-10-12T00:00:00Z"))
    private fun snapshot(session: CourseSession, minutesUntil: Long) = TravelSnapshot(session = session,
        building = FakeBuildingLookup.PETER_HALL, estimate = estimate(), distanceMeters = 1200.0, minutesUntilClass = minutesUntil)
    private fun session(id: String, code: String, title: String): CourseSession {
        val start = ZonedDateTime.of(2026, 10, 12, 11, 0, 0, 0, ZoneId.of("Australia/Melbourne"))
        return CourseSession(id, code, title, "Parkville Campus", "PAR-160", start, start.plusHours(1))
    }
}
