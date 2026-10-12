package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.timetableGroupSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeGroupUpdatesTest {
    private val start = ZonedDateTime.of(2026, 10, 12, 15, 0, 0, 0, ZoneId.of("Australia/Melbourne"))
    private val finished = CourseSession(
        id = "finished", code = "COMP90018", title = "Mobile Computing Systems Programming",
        location = "PAR", room = "160", activity = "tutorial", start = start, end = start.plusHours(1)
    )

    @Test
    fun canonicalGroupWinsAndKeepsTheRepositoryUpdateSummary() {
        val canonical = group("course", key = "COMP90018|course").copy(
            unreadCount = 3, latestMessage = "Please review the proposal", latestFileName = "proposal.pdf"
        )
        val updates = buildHomeGroupUpdates(finished, listOf(group("tagged"), exactSlot(), canonical))

        assertEquals(canonical, updates.featuredGroup)
        assertEquals(listOf(canonical, group("tagged")), updates.rows)
        assertEquals("After COMP90018", updates.contextLabel)
    }

    @Test
    fun exactRemoteSlotWinsOverACourseTaggedGroupWithoutALocalSpec() {
        val exact = exactSlot()

        assertNull(exact.timetableSpec)
        assertEquals(exact, buildHomeGroupUpdates(finished, listOf(group("tagged"), exact)).featuredGroup)
    }

    @Test
    fun anotherTutorialSlotCannotMatchByCourseCodeAlone() {
        val otherSlot = group("other-slot", key = finished.copy(room = "999").timetableGroupSpec()!!.key)
        val updates = buildHomeGroupUpdates(finished, listOf(otherSlot))

        assertNull(updates.featuredGroup)
        assertNull(updates.contextLabel)
        assertEquals(listOf(otherSlot), updates.rows)
    }

    @Test
    fun matchingAutomaticallyFoldedMembershipCanAppearOnlyOnHome() {
        val folded = group("course", key = "COMP90018|course")
        val active = group("active").copy(courseCode = "SWEN90014")
        val candidates = homePostClassCandidates(listOf(folded, active), emptyMap())
        val updates = buildHomeGroupUpdates(finished, listOf(active), candidates)

        assertTrue(folded.isFolded(GroupChatPreferences(), listOf(finished), finished.end))
        assertEquals(folded, updates.featuredGroup)
        assertEquals(listOf(folded, active), updates.rows)
        assertEquals(listOf(active), buildHomeGroupUpdates(null, listOf(active), candidates).rows)
    }

    @Test
    fun explicitlyFoldedMembershipIsExcludedEvenWhenItMatches() {
        val folded = group("course", key = "COMP90018|course")
        val candidates = homePostClassCandidates(
            listOf(folded), mapOf(folded.id to GroupChatPreferences(foldedOverride = true))
        )
        val updates = buildHomeGroupUpdates(finished, emptyList(), candidates)

        assertTrue(candidates.isEmpty())
        assertNull(updates.featuredGroup)
        assertTrue(updates.rows.isEmpty())
    }

    private fun exactSlot() = group("exact", key = finished.timetableGroupSpec()!!.key)

    private fun group(id: String, key: String? = null) = CourseGroup(
        id = id, courseCode = "COMP90018", name = id, members = 8,
        unreadCount = 0, latestMessage = "No messages yet.", latestFileName = null,
        origin = if (key == null) GroupOrigin.Joined else GroupOrigin.Timetable,
        timetableKey = key
    )
}
