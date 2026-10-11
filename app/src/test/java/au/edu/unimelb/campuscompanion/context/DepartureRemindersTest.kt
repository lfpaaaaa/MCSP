package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
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

    @Test
    fun aReminderIsSentOnceWhenItIsTimeToLeave() {
        assertNull(reminders.consider(snapshot(lecture, ContextState.UPCOMING)))

        val reminder = reminders.consider(snapshot(lecture, ContextState.LEAVE_SOON))

        assertEquals("Time to leave for COMP90018", reminder?.title)
        assertEquals(
            "Mobile Computing Systems Programming starts at 11:00 AM in Peter Hall Building 160. " +
                "About 15 min on foot. Allow 5 min more for the weather.",
            reminder?.text
        )
        assertEquals(listOf(reminder), sent)

        assertNull(reminders.consider(snapshot(lecture, ContextState.LEAVE_SOON)))
        assertNull(reminders.consider(snapshot(lecture, ContextState.EN_ROUTE)))
        assertEquals(1, sent.size)
    }

    @Test
    fun runningLateIsASecondReminderForTheSameClass() {
        reminders.consider(snapshot(lecture, ContextState.LEAVE_SOON))

        val late = reminders.consider(snapshot(lecture, ContextState.RUNNING_LATE))

        assertEquals("Running late for COMP90018", late?.title)
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

    @Test
    fun withoutATravelTimeTheReminderStillNamesTheClass() {
        val reminder = reminders.consider(
            TravelSnapshot(session = lecture, building = null, state = ContextState.LEAVE_SOON)
        )

        assertEquals("Mobile Computing Systems Programming starts at 11:00 AM.", reminder?.text)
    }

    @Test
    fun theFlowIsCollectedUntilItEnds() = runBlocking<Unit> {
        val collected = DepartureReminders(
            snapshots = flowOf(
                snapshot(lecture, ContextState.UPCOMING),
                snapshot(lecture, ContextState.LEAVE_SOON),
                snapshot(lecture, ContextState.LEAVE_SOON)
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
            assertNull(reactive.consider(snapshot(current, ContextState.RUNNING_LATE)))
            assertEquals(1, sent.size)
        } finally {
            job.cancel()
            job.join()
        }
    }

    @Test
    fun failedDeliveryRetriesAndOnlySuccessfulDeliveryIsRemembered() {
        val history = ReminderHistory()
        val attempts = mutableListOf<DepartureReminder>()
        var deliverySucceeds = false
        val reliable = reliableReminders(history, notify = {
            attempts.add(it)
            if (deliverySucceeds) sent.add(it) else false
        })
        val warning = snapshot(lecture, ContextState.LEAVE_SOON)

        assertNull(reliable.consider(warning))
        assertEquals(1, attempts.size)
        assertTrue(sent.isEmpty())
        assertTrue(history.writes.isEmpty())

        deliverySucceeds = true
        assertNotNull(reliable.consider(warning))
        assertEquals(2, attempts.size)
        assertEquals(listOf(ContextState.LEAVE_SOON), history.writes.map { it.state })
        assertNull(reliable.consider(warning))
        assertEquals(2, attempts.size)
        assertEquals(1, sent.size)
    }

    @Test
    fun laterSnapshotRetriesAFailedDelivery() = runBlocking<Unit> {
        val history = ReminderHistory()
        var attempts = 0
        val warning = snapshot(lecture, ContextState.LEAVE_SOON)
        val snapshots = Channel<TravelSnapshot>(Channel.UNLIMITED)
        val deliveryAttempts = Channel<Unit>(Channel.UNLIMITED)
        val reliable = DepartureReminders(
            snapshots = snapshots.receiveAsFlow(),
            preferences = { preferences },
            notify = {
                attempts++
                deliveryAttempts.trySend(Unit)
                if (attempts == 1) false else sent.add(it)
            },
            currentUserId = { "student-a" },
            wasSent = history::wasSent,
            rememberSent = history::rememberSent
        )

        val job = launch { reliable.run() }
        try {
            snapshots.send(warning)
            withTimeout(2_000) { deliveryAttempts.receive() }
            snapshots.send(warning)
            withTimeout(2_000) { deliveryAttempts.receive() }
            snapshots.send(warning)
            snapshots.close()
            withTimeout(2_000) { job.join() }

            assertEquals(2, attempts)
            assertEquals(1, sent.size)
            assertEquals(1, history.writes.size)
        } finally {
            job.cancel()
            job.join()
            snapshots.close()
            deliveryAttempts.close()
        }
    }

    @Test
    fun preferenceReevaluationRetriesAFailedDelivery() = runBlocking<Unit> {
        val history = ReminderHistory()
        val warning = snapshot(lecture, ContextState.LEAVE_SOON)
        val changes = MutableStateFlow(0)
        val attempts = Channel<DepartureReminder>(Channel.UNLIMITED)
        var deliverySucceeds = false
        val reliable = DepartureReminders(
            snapshots = MutableStateFlow(warning),
            preferences = { preferences },
            notify = {
                attempts.trySend(it)
                if (deliverySucceeds) sent.add(it) else false
            },
            preferenceChanges = changes.map { Unit },
            currentUserId = { "student-a" },
            wasSent = history::wasSent,
            rememberSent = history::rememberSent
        )
        val job = launch { reliable.run() }
        try {
            withTimeout(2_000) { attempts.receive() }
            assertTrue(sent.isEmpty())
            assertTrue(history.writes.isEmpty())

            deliverySucceeds = true
            changes.value++
            withTimeout(2_000) { attempts.receive() }
            assertEquals(1, sent.size)
            assertEquals(1, history.writes.size)
            assertNull(reliable.consider(warning))
            assertTrue(attempts.tryReceive().isFailure)
        } finally {
            job.cancel()
            job.join()
            attempts.close()
        }
    }

    @Test
    fun recreatingTheNotifierUsesHistoryAndStillAllowsLateEscalation() {
        val history = ReminderHistory()
        val warning = snapshot(lecture, ContextState.LEAVE_SOON)
        val late = snapshot(lecture, ContextState.RUNNING_LATE)
        assertNotNull(reliableReminders(history).consider(warning))

        val recreated = reliableReminders(history)
        assertNull(recreated.consider(warning))
        assertNotNull(recreated.consider(late))
        assertNull(recreated.consider(late))
        assertEquals(listOf(ContextState.LEAVE_SOON, ContextState.RUNNING_LATE), sent.map { it.state })
        assertEquals(2, history.writes.size)
        assertEquals(sent[0].notificationId, sent[1].notificationId)
    }

    @Test
    fun aDeliveredLateReminderBlocksAnEarlierWarningInMemory() {
        val reliable = DepartureReminders(
            snapshots = flowOf(), preferences = { preferences }, notify = sent::add
        )

        assertNotNull(reliable.consider(snapshot(lecture, ContextState.RUNNING_LATE)))
        assertNull(reliable.consider(snapshot(lecture, ContextState.LEAVE_SOON)))
        assertEquals(listOf(ContextState.RUNNING_LATE), sent.map { it.state })
    }

    @Test
    fun aDeliveredLateReminderBlocksAnEarlierWarningAfterRecreation() {
        val history = ReminderHistory()
        assertNotNull(reliableReminders(history).consider(snapshot(lecture, ContextState.RUNNING_LATE)))

        val recreated = reliableReminders(history)
        assertNull(recreated.consider(snapshot(lecture, ContextState.LEAVE_SOON)))
        assertNull(recreated.consider(snapshot(lecture, ContextState.RUNNING_LATE)))
        assertEquals(listOf(ContextState.RUNNING_LATE), sent.map { it.state })
        assertEquals(1, history.writes.size)
    }

    @Test
    fun aFailedLateReminderDoesNotBlockADeliverableWarning() {
        val history = ReminderHistory()
        val attemptedStates = mutableListOf<ContextState>()
        val reliable = reliableReminders(history, notify = {
            attemptedStates.add(it.state)
            if (it.state == ContextState.RUNNING_LATE) false else sent.add(it)
        })

        assertNull(reliable.consider(snapshot(lecture, ContextState.RUNNING_LATE)))
        assertTrue(history.writes.isEmpty())
        assertNotNull(reliable.consider(snapshot(lecture, ContextState.LEAVE_SOON)))
        assertEquals(listOf(ContextState.RUNNING_LATE, ContextState.LEAVE_SOON), attemptedStates)
        assertEquals(listOf(ContextState.LEAVE_SOON), history.writes.map { it.state })
    }

    @Test
    fun switchingAccountsKeepsTheirDeliveryHistoriesSeparate() {
        val history = ReminderHistory()
        var userId = "student-a"
        val reliable = reliableReminders(history, currentUserId = { userId })
        val warning = snapshot(lecture, ContextState.LEAVE_SOON)
        assertNotNull(reliable.consider(warning))

        userId = "student-b"
        assertNotNull(reliable.consider(warning))
        userId = "student-a"
        assertNull(reliable.consider(warning))
        assertEquals(2, sent.size)
        assertEquals(listOf("student-a", "student-b"), history.writes.map { it.userId })
    }

    @Test
    fun aNewOccurrenceWithTheSameIdGetsItsOwnDeliveryHistory() {
        val history = ReminderHistory()
        val reliable = reliableReminders(history)
        val nextWeek = lecture.copy(start = lecture.start.plusWeeks(1), end = lecture.end.plusWeeks(1))
        assertNotNull(reliable.consider(snapshot(lecture, ContextState.LEAVE_SOON)))
        assertNotNull(reliable.consider(snapshot(nextWeek, ContextState.LEAVE_SOON)))

        val recreated = reliableReminders(history)
        assertNull(recreated.consider(snapshot(lecture, ContextState.LEAVE_SOON)))
        assertNull(recreated.consider(snapshot(nextWeek, ContextState.LEAVE_SOON)))
        assertEquals(2, sent.size)
        assertEquals(listOf(lecture.start.toInstant(), nextWeek.start.toInstant()), history.writes.map { it.start })
        assertEquals(listOf(lecture.id, lecture.id), history.writes.map { it.sessionId })
    }

    @Test
    fun signingOutDoesNotNotifyOrAccessHistory() {
        val history = ReminderHistory()
        var userId: String? = null
        val reliable = reliableReminders(history, currentUserId = { userId })
        val warning = snapshot(lecture, ContextState.LEAVE_SOON)
        assertNull(reliable.consider(warning))
        assertTrue(sent.isEmpty())
        assertTrue(history.reads.isEmpty())
        assertTrue(history.writes.isEmpty())

        userId = "student-a"
        assertNotNull(reliable.consider(warning))
        val readsBeforeSignOut = history.reads.size
        userId = null
        assertNull(reliable.consider(snapshot(lecture, ContextState.RUNNING_LATE)))
        assertEquals(readsBeforeSignOut, history.reads.size)
        assertEquals(1, sent.size)
        assertEquals(1, history.writes.size)

        userId = "student-a"
        assertNull(reliable.consider(warning))
        assertEquals(1, sent.size)
    }

    @Test
    fun suppressedStatesDisabledPreferencesAndMissingSessionsDoNotWriteHistory() {
        val history = ReminderHistory()
        val reliable = reliableReminders(history)
        ContextState.entries.filter {
            it != ContextState.LEAVE_SOON && it != ContextState.RUNNING_LATE
        }.forEach { state -> assertNull(reliable.consider(snapshot(lecture, state))) }
        preferences = CourseReminderPreference(enabled = false)
        assertNull(reliable.consider(snapshot(lecture, ContextState.LEAVE_SOON)))
        assertNull(reliable.consider(snapshot(lecture, ContextState.RUNNING_LATE)))
        assertNull(reliable.consider(TravelSnapshot()))

        assertTrue(sent.isEmpty())
        assertTrue(history.writes.isEmpty())
    }

    @Test
    fun endingTheTrackedSessionClearsOnlyInMemoryDeduplication() {
        val reliable = DepartureReminders(
            snapshots = flowOf(), preferences = { preferences }, notify = sent::add
        )
        val warning = snapshot(lecture, ContextState.LEAVE_SOON)
        assertNotNull(reliable.consider(warning))
        assertNull(reliable.consider(TravelSnapshot()))
        assertNotNull(reliable.consider(warning))
        assertEquals(2, sent.size)

        val history = ReminderHistory()
        val persistent = reliableReminders(history)
        assertNotNull(persistent.consider(warning))
        assertNull(persistent.consider(TravelSnapshot()))
        assertNull(persistent.consider(warning))
        assertEquals(1, history.writes.size)
        assertEquals(3, sent.size)
    }

    private fun reliableReminders(
        history: ReminderHistory,
        currentUserId: () -> String? = { "student-a" },
        notify: (DepartureReminder) -> Boolean = sent::add
    ) = DepartureReminders(
        snapshots = flowOf(),
        preferences = { preferences },
        notify = notify,
        currentUserId = currentUserId,
        wasSent = history::wasSent,
        rememberSent = history::rememberSent
    )

    /** Simulates saved history across notifier instances; this fixture does not exercise disk I/O. */
    private class ReminderHistory {
        data class Key(val userId: String, val sessionId: String, val start: Instant, val state: ContextState)

        private val delivered = mutableSetOf<Key>()
        val reads = mutableListOf<Key>()
        val writes = mutableListOf<Key>()

        fun wasSent(userId: String, session: CourseSession, state: ContextState): Boolean {
            val key = Key(userId, session.id, session.start.toInstant(), state)
            reads.add(key)
            return key in delivered
        }

        fun rememberSent(userId: String, session: CourseSession, state: ContextState) {
            val key = Key(userId, session.id, session.start.toInstant(), state)
            writes.add(key)
            delivered.add(key)
        }
    }

    private fun snapshot(session: CourseSession, state: ContextState) = TravelSnapshot(
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
