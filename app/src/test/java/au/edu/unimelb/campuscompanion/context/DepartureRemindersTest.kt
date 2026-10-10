package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.sensing.location.TravelState
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test
    fun aReminderIsSentOnceWhenItIsTimeToLeave() {
        assertNull(reminders.consider(snapshot(lecture, TravelState.UPCOMING_CLASS)))

        val reminder = reminders.consider(snapshot(lecture, TravelState.SHOULD_LEAVE_SOON))

        assertEquals("Time to leave for COMP90018", reminder?.title)
        assertEquals(
            "Mobile Computing Systems Programming starts at 11:00 AM in Peter Hall Building 160. " +
                "About 15 min on foot. Allow 5 min more for the weather.",
            reminder?.text
        )
        assertEquals(listOf(reminder), sent)

        assertNull(reminders.consider(snapshot(lecture, TravelState.SHOULD_LEAVE_SOON)))
        assertNull(reminders.consider(snapshot(lecture, TravelState.EN_ROUTE)))
        assertEquals(1, sent.size)
    }

    @Test
    fun runningLateIsASecondReminderForTheSameClass() {
        reminders.consider(snapshot(lecture, TravelState.SHOULD_LEAVE_SOON))

        val late = reminders.consider(snapshot(lecture, TravelState.RUNNING_LATE))

        assertEquals("Running late for COMP90018", late?.title)
        assertEquals(2, sent.size)
        assertEquals(sent[0].notificationId, sent[1].notificationId)
    }

    @Test
    fun theNextClassGetsItsOwnReminder() {
        reminders.consider(snapshot(lecture, TravelState.SHOULD_LEAVE_SOON))
        reminders.consider(snapshot(lecture, TravelState.ARRIVED))

        val next = reminders.consider(snapshot(tutorial, TravelState.SHOULD_LEAVE_SOON))

        assertEquals("Time to leave for SWEN90006", next?.title)
        assertEquals(2, sent.size)
    }

    @Test
    fun nothingIsSentWhileRemindersAreOff() {
        preferences = CourseReminderPreference(enabled = false)

        assertNull(reminders.consider(snapshot(lecture, TravelState.SHOULD_LEAVE_SOON)))
        assertTrue(sent.isEmpty())

        // Switching reminders back on for the same class still gives the reminder.
        preferences = CourseReminderPreference(enabled = true)
        val reminder = reminders.consider(snapshot(lecture, TravelState.SHOULD_LEAVE_SOON))
        assertEquals("Time to leave for COMP90018", reminder?.title)
    }

    @Test
    fun withoutATravelTimeTheReminderStillNamesTheClass() {
        val reminder = reminders.consider(
            TravelSnapshot(session = lecture, building = null, state = TravelState.SHOULD_LEAVE_SOON)
        )

        assertEquals("Mobile Computing Systems Programming starts at 11:00 AM.", reminder?.text)
    }

    @Test
    fun theFlowIsCollectedUntilItEnds() = runBlocking<Unit> {
        val collected = DepartureReminders(
            snapshots = flowOf(
                snapshot(lecture, TravelState.UPCOMING_CLASS),
                snapshot(lecture, TravelState.SHOULD_LEAVE_SOON),
                snapshot(lecture, TravelState.SHOULD_LEAVE_SOON)
            ),
            preferences = { preferences },
            notify = sent::add
        )

        collected.run()

        assertEquals(listOf("Time to leave for COMP90018"), sent.map { it.title })
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

        assertNull(perActivity.consider(snapshot(sameCourseTutorial, TravelState.SHOULD_LEAVE_SOON)))
        assertNull(perActivity.consider(snapshot(sameCourseTutorial, TravelState.RUNNING_LATE)))
        perActivity.consider(snapshot(sameCourseLecture, TravelState.SHOULD_LEAVE_SOON))
        perActivity.consider(snapshot(tutorial, TravelState.SHOULD_LEAVE_SOON))

        assertEquals(listOf(sameCourseLecture.id, tutorial.id), sent.map { it.sessionId })
    }

    @Test
    fun preferenceChangesWithdrawRemindersAndRecheckTheCurrentClass() = runBlocking<Unit> {
        val current = lecture.copy(activity = "lecture")
        val snapshots = MutableStateFlow(snapshot(current, TravelState.SHOULD_LEAVE_SOON))
        val changes = MutableStateFlow(0)
        val cancelled = mutableListOf<String>()
        preferences = CourseReminderPreference(enabled = false)
        val reactive = DepartureReminders(
            snapshots = snapshots,
            preferences = { preferences },
            notify = sent::add,
            preferenceChanges = changes.map { Unit },
            cancelCourse = cancelled::add
        )
        val job = launch { reactive.run() }
        try {
            withTimeout(2_000) { while (cancelled.isEmpty()) delay(1) }
            assertTrue(sent.isEmpty())
            preferences = CourseReminderPreference(enabled = true)
            changes.value++
            withTimeout(2_000) { while (sent.isEmpty()) delay(1) }
            assertEquals(current.reminderSeriesKey(), sent.single().courseKey)

            cancelled.clear()
            preferences = CourseReminderPreference(enabled = false)
            changes.value++
            withTimeout(2_000) { while (cancelled.isEmpty()) delay(1) }
            assertEquals(listOf(current.reminderSeriesKey()), cancelled)
            assertNull(reactive.consider(snapshot(current, TravelState.RUNNING_LATE)))
            assertEquals(1, sent.size)
        } finally {
            job.cancel()
            job.join()
        }
    }

    private fun snapshot(session: CourseSession, state: TravelState) = TravelSnapshot(
        session = session,
        building = FakeBuildingLookup.PETER_HALL,
        state = state,
        estimate = TravelEstimate(
            mode = TravelMode.Walking,
            durationSeconds = 15 * 60,
            distanceMeters = 1_200,
            source = EstimateSource.Routing,
            computedAt = Instant.parse("2026-10-12T00:00:00Z")
        ),
        distanceMeters = 1_200.0,
        minutesUntilClass = 25,
        weatherBufferMinutes = 5
    )

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
