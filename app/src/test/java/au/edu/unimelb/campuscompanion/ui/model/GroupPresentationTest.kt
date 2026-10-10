package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.timetableGroupSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GroupPresentationTest {
    private val zone = ZoneId.of("Australia/Melbourne")
    private val now = ZonedDateTime.of(2026, 10, 2, 10, 0, 0, 0, zone)

    @Test
    fun timetableGroupWithFutureClassStaysActive() {
        val session = session(
            start = now.plusDays(1),
            end = now.plusDays(1).plusHours(1)
        )

        assertFalse(timetableGroup().isAutomaticallyFolded(listOf(session), now))
    }

    @Test
    fun timetableGroupWithoutRemainingClassesIsAutomaticallyFolded() {
        assertTrue(timetableGroup().isAutomaticallyFolded(emptyList(), now))
    }

    @Test
    fun userCreatedGroupIsNeverAutomaticallyFolded() {
        val group = timetableGroup().copy(origin = GroupOrigin.CreatedByUser)

        assertFalse(group.isAutomaticallyFolded(emptyList(), now))
    }

    @Test
    fun userCanRestoreAutomaticallyFoldedGroup() {
        val preferences = GroupChatPreferences(foldedOverride = false)

        assertFalse(timetableGroup().isFolded(preferences, emptyList(), now))
    }

    @Test
    fun userCanFoldCreatedGroup() {
        val group = timetableGroup().copy(origin = GroupOrigin.CreatedByUser)
        val preferences = GroupChatPreferences(foldedOverride = true)

        assertTrue(group.isFolded(preferences, emptyList(), now))
    }

    @Test
    fun savingOtherSettingsDoesNotDisableFutureAutomaticFolding() {
        val override = foldedOverrideAfterEdit(
            existingOverride = null,
            initialFolded = false,
            selectedFolded = false
        )

        assertTrue(override == null)
    }


    @Test fun tutorialFoldsAfterItsOwnLastClassEvenIfAnotherRoomHasClasses() {
        val tutorial = session(now.plusDays(1), now.plusDays(1).plusHours(1)).copy(activity = "tutorial")
        val group = timetableGroup().copy(timetableKey = tutorial.timetableGroupSpec()!!.key)
        assertFalse(group.isAutomaticallyFolded(listOf(tutorial), now))
        assertTrue(group.isAutomaticallyFolded(listOf(tutorial.copy(room = "999")), now))
    }

    private fun timetableGroup() = CourseGroup(
        id = "comp90018-group",
        courseCode = "COMP90018",
        name = "Mobile Computing Systems Programming",
        members = 0,
        unreadCount = 0,
        latestMessage = "",
        latestFileName = null,
        privateContentEnabled = false
    )

    private fun session(
        start: ZonedDateTime,
        end: ZonedDateTime
    ) = CourseSession(
        id = "session",
        code = "COMP90018",
        title = "Lecture",
        location = "PAR",
        room = "160",
        start = start,
        end = end
    )
}
