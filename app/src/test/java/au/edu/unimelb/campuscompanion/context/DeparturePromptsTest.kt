package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.english

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.building.BuildingLocation
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.TravelEstimate
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the student can see in the notification shade as the engine's results change. */
class DeparturePromptsTest {
    @Test
    fun aPromptIsWithdrawnOnceTheStudentSetsOffArrivesOrIsInClass() {
        listOf(ContextState.EN_ROUTE, ContextState.ARRIVED, ContextState.IN_CLASS).forEach { state ->
            val f = Fixture()
            assertNotNull(f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON)))

            assertNull(f.reminders.consider(f.snapshot(state = state)))

            assertEquals(listOf(f.key), f.shade.withdrawn)
            assertTrue(f.shade.shown.isEmpty())
        }
    }

    @Test
    fun aPromptIsWithdrawnWhenItsClassIsRemovedOrAnotherClassIsNext() {
        val removed = Fixture()
        removed.reminders.consider(removed.snapshot(state = ContextState.LEAVE_SOON))
        // The engine's result for a timetable without an upcoming class.
        assertNull(removed.reminders.consider(TravelSnapshot()))
        assertTrue(removed.shade.shown.isEmpty())

        val replaced = Fixture()
        replaced.reminders.consider(replaced.snapshot(state = ContextState.LEAVE_SOON))
        val tutorial = replaced.lecture.copy(
            id = "tutorial", start = replaced.lecture.start.plusHours(2), end = replaced.lecture.end.plusHours(2)
        )
        assertNull(replaced.reminders.consider(replaced.snapshot(tutorial, state = ContextState.UPCOMING)))
        assertTrue(replaced.shade.shown.isEmpty())
    }

    @Test
    fun aPendingResultLeavesTheShadeAsItIs() {
        val f = Fixture()
        f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON))

        assertNull(f.reminders.consider(TravelSnapshot.PENDING))

        assertEquals(setOf(f.key), f.shade.shownKeys())
        assertTrue(f.shade.withdrawn.isEmpty())
        assertTrue(f.shade.updates.isEmpty())
    }

    @Test
    fun aVenueEditUpdatesTheShownPromptSilentlyWithoutASecondAlert() {
        val f = Fixture()
        f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON))
        assertTrue(f.shade.textOf(f.key)!!.contains("Peter Hall"))
        val moved = f.lecture.copy(room = "PAR-104-G01")

        val alanGilbert = f.snapshot(moved, FakeBuildingLookup.ALAN_GILBERT, minutes = 7, state = ContextState.LEAVE_SOON)
        assertNull(f.reminders.consider(alanGilbert))

        assertEquals(1, f.shade.alerts.size)
        assertEquals(1, f.shade.updates.size)
        assertEquals(
            "Mobile Computing starts at 11:00 AM in Alan Gilbert Building 104. Travel time: 7 min on foot. Leave by 10:53 AM (within 10 min).",
            f.shade.textOf(f.key)
        )
        // The same details again change nothing.
        f.reminders.consider(alanGilbert)
        assertEquals(1, f.shade.updates.size)
    }

    @Test
    fun aDismissedPromptIsNotBroughtBack() {
        val f = Fixture()
        f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON))
        f.shade.dismiss(f.key)
        val moved = f.lecture.copy(room = "PAR-104-G01")

        f.reminders.consider(f.snapshot(moved, FakeBuildingLookup.ALAN_GILBERT, minutes = 7, state = ContextState.LEAVE_SOON))
        f.reminders.consider(f.snapshot(moved, FakeBuildingLookup.ALAN_GILBERT, minutes = 7, state = ContextState.UPCOMING))

        assertTrue(f.shade.shown.isEmpty())
        assertEquals(1, f.shade.alerts.size)
        assertTrue(f.shade.updates.isEmpty())
    }

    @Test
    fun aPromptStaysWhileTheClassIsStillAheadButNoLongerDue() {
        // A faster route or a smaller buffer moved the deadline back after the warning.
        val f = Fixture()
        f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON))

        assertNull(f.reminders.consider(f.snapshot(minutes = 9, state = ContextState.UPCOMING)))
        assertEquals(setOf(f.key), f.shade.shownKeys())
        assertEquals("Leave within 40 min · COMP90018", f.shade.shown[f.key]?.first)
        assertTrue(f.shade.textOf(f.key)!!.contains("Travel time: 9 min on foot"))

        // Due again later: still only the one alert.
        assertNull(f.reminders.consider(f.snapshot(minutes = 9, state = ContextState.LEAVE_SOON)))
        assertEquals(1, f.shade.alerts.size)
    }

    @Test
    fun aPromptIsWithdrawnWhenItsVenueIsNoLongerKnown() {
        val f = Fixture()
        f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON))
        val online = f.lecture.copy(location = "Online", room = "Online")

        assertNull(f.reminders.consider(f.snapshot(online, building = null, minutes = null, state = ContextState.UPCOMING)))

        assertTrue(f.shade.shown.isEmpty())
    }

    @Test
    fun aClassMovedToANewStartGetsItsOwnPromptAndUndoingItDoesNotAlertAgain() {
        val f = Fixture()
        f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON))
        val later = f.lecture.copy(start = f.lecture.start.plusMinutes(10), end = f.lecture.end.plusMinutes(10))

        // Not yet due at the new time: the prompt for the old time goes, and nothing replaces it yet.
        assertNull(f.reminders.consider(f.snapshot(later, state = ContextState.UPCOMING)))
        assertTrue(f.shade.shown.isEmpty())

        // Due at the new time: one alert for the new start.
        val reminder = f.reminders.consider(f.snapshot(later, state = ContextState.LEAVE_SOON))
        assertEquals(later.departurePromptKey(), reminder?.key)
        assertTrue(reminder!!.text.english().startsWith("Mobile Computing starts at 11:10 AM"))

        // Moved back: the original start was already announced, so it is not announced again.
        assertNull(f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON)))
        assertEquals(2, f.shade.alerts.size)
        assertTrue(f.shade.shown.isEmpty())
    }

    @Test
    fun aLateReminderStaysLateWhenTheEtaImproves() {
        val f = Fixture()
        f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON))
        f.reminders.consider(f.snapshot(state = ContextState.RUNNING_LATE))

        assertNull(f.reminders.consider(f.snapshot(minutes = 12, state = ContextState.LEAVE_SOON)))

        assertEquals(2, f.shade.alerts.size)
        assertEquals("Leave now for COMP90018", f.shade.shown[f.key]?.first)
        assertTrue(f.shade.textOf(f.key)!!.contains("Travel time: 12 min on foot"))
    }

    @Test
    fun aResultWorkedOutWithAnEarlierBufferChangesNothing() {
        val f = Fixture()
        f.preference = CourseReminderPreference(leadMinutes = 0)
        f.shade.showOther("earlier-class")

        // Still the 10-minute buffer: the engine is already working out the result with 0.
        assertNull(f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON, lead = 10)))
        assertTrue(f.shade.alerts.isEmpty())
        assertEquals(setOf("earlier-class"), f.shade.shownKeys())

        assertNull(f.reminders.consider(f.snapshot(state = ContextState.UPCOMING, lead = 0)))
        assertTrue(f.shade.shown.isEmpty())
        assertNotNull(f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON, lead = 0)))
    }

    @Test
    fun promptsFromEarlierClassesOrAppVersionsAreWithdrawnByTheFirstResult() {
        val f = Fixture()
        // Earlier versions tagged a prompt with its course rather than its class occurrence.
        f.shade.showOther("activity:comp90018:lecture")

        assertNotNull(f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON)))

        assertEquals(setOf(f.key), f.shade.shownKeys())
    }

    @Test
    fun signingOutLeavesTheShadeToTheSignOutHandler() {
        val f = Fixture()
        f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON))
        f.userId = null

        assertNull(f.reminders.consider(f.snapshot(state = ContextState.ARRIVED)))
        assertNull(f.reminders.consider(TravelSnapshot()))

        assertEquals(setOf(f.key), f.shade.shownKeys())
        assertTrue(f.shade.withdrawn.isEmpty())
    }

    @Test
    fun afterARestartTheSurvivingPromptIsUpdatedSilentlyOnce() {
        val f = Fixture()
        f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON))

        val restarted = f.newReminders()
        assertNull(restarted.consider(f.snapshot(minutes = 14, state = ContextState.LEAVE_SOON)))
        assertNull(restarted.consider(f.snapshot(minutes = 14, state = ContextState.LEAVE_SOON)))

        assertEquals(1, f.shade.alerts.size)
        assertEquals(1, f.shade.updates.size)
        assertTrue(f.shade.textOf(f.key)!!.contains("Travel time: 14 min on foot"))
    }

    @Test
    fun anUnknownEtaKeepsOnlyTheCurrentClassPromptAndNeverInventsAWarning() {
        val f = Fixture()
        f.reminders.consider(f.snapshot(state = ContextState.LEAVE_SOON))
        assertNull(f.reminders.consider(f.snapshot(minutes = null, state = ContextState.LEAVE_SOON)))
        assertEquals(setOf(f.key), f.shade.shownKeys())

        val next = f.lecture.copy(id = "next", start = f.lecture.start.plusHours(2), end = f.lecture.end.plusHours(2))
        assertNull(f.reminders.consider(f.snapshot(next, minutes = null, state = ContextState.UPCOMING)))
        assertTrue(f.shade.shown.isEmpty())
        assertEquals(1, f.shade.alerts.size)
    }

    private class Fixture {
        val shade = FakeDepartureShade()
        var preference = CourseReminderPreference()
        var userId: String? = "student-a"
        private val history = mutableSetOf<Triple<String, String, DepartureReminderStage>>()
        val lecture = CourseSession(
            id = "lecture",
            code = "COMP90018",
            title = "Mobile Computing",
            location = "Parkville Campus",
            room = "PAR-160-B117",
            start = START,
            end = START.plusHours(1),
            activity = "lecture"
        )
        val key = lecture.departurePromptKey()
        val reminders = newReminders()

        /** A fresh instance sharing the shade and the saved history, as after a process restart. */
        fun newReminders() = DepartureReminders(
            snapshots = flowOf(),
            preferences = { preference },
            notify = shade::post,
            shade = shade,
            currentUserId = { userId },
            wasSent = { user, session, state -> Triple(user, session.departurePromptKey(), state) in history },
            rememberSent = { user, session, state -> history += Triple(user, session.departurePromptKey(), state) }
        )

        fun snapshot(
            session: CourseSession = lecture,
            building: BuildingLocation? = FakeBuildingLookup.PETER_HALL,
            minutes: Int? = 15,
            state: ContextState,
            lead: Int = preference.leadMinutes
        ) = TravelSnapshot(
            session = session,
            building = building,
            state = state,
            estimate = minutes?.let {
                TravelEstimate(TravelMode.Walking, it * 60L, 1_200, EstimateSource.Routing, Instant.EPOCH)
            },
            leadMinutes = lead,
            evaluatedAt = session.start.toInstant().minusSeconds(when (state) {
                ContextState.RUNNING_LATE -> ((minutes ?: 15) - 1) * 60L
                ContextState.LEAVE_SOON -> ((minutes ?: 15) + lead) * 60L
                else -> ((minutes ?: 15) + lead + 30) * 60L
            })
        )
    }

    private companion object {
        val START: ZonedDateTime = ZonedDateTime.of(2026, 10, 12, 11, 0, 0, 0, ZoneId.of("Australia/Melbourne"))
    }
}
