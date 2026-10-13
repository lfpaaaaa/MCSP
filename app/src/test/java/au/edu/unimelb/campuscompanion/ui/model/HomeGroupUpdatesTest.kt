package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.english

import au.edu.unimelb.campuscompanion.context.ContextEngine
import au.edu.unimelb.campuscompanion.context.ContextInputs
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.timetableGroupSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("After COMP90018", updates.contextLabel?.english())
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
        assertNull(updates.contextLabel?.english())
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

    @Test
    fun matchingGroupWithoutMessagesOrFilesStillProvidesPostClassContext() {
        val emptyGroup = group("empty", key = "COMP90018|course")
        val updates = buildHomeGroupUpdates(finished, listOf(emptyGroup))

        assertEquals(emptyGroup, updates.featuredGroup)
        assertEquals("After COMP90018", updates.contextLabel?.english())
        assertEquals(0, updates.rows.single().unreadCount)
        assertEquals("No messages yet.", updates.rows.single().latestMessage)
        assertNull(updates.rows.single().latestFileName)
    }

    @Test
    fun exactWorkshopMatchDoesNotSelectAnotherWorkshopSlot() {
        val workshop = finished.copy(activity = "workshop")
        val otherSlot = group("other-workshop", key = workshop.copy(start = start.plusHours(1),
            end = start.plusHours(2)).timetableGroupSpec()!!.key)
        val exact = group("workshop", key = workshop.timetableGroupSpec()!!.key)
        val updates = buildHomeGroupUpdates(workshop, listOf(otherSlot, group("tagged"), exact))

        assertNull(exact.timetableSpec)
        assertEquals(exact, updates.featuredGroup)
        assertEquals(listOf(exact, otherSlot), updates.rows)
    }

    @Test
    fun courseTaggedCreatedGroupCanMatchALectureWithoutASlot() {
        val created = group("created").copy(
            courseCode = "comp90018", origin = GroupOrigin.CreatedByUser
        )
        val lecture = finished.copy(activity = "lecture")

        assertNull(lecture.timetableGroupSpec())
        assertEquals(created, buildHomeGroupUpdates(lecture, listOf(created)).featuredGroup)
    }

    @Test
    fun joinedGroupWithAnotherTimetableKeyCannotUseItsCourseTagAsAFallback() {
        val otherSlot = group("joined-other-slot", key = finished.copy(room = "999")
            .timetableGroupSpec()!!.key).copy(origin = GroupOrigin.Joined)
        val tagged = group("joined-tagged")
        val updates = buildHomeGroupUpdates(finished, listOf(otherSlot, tagged))

        assertEquals(tagged, updates.featuredGroup)
    }

    @Test
    fun aTimetableGroupWithoutAMatchingKeyDoesNotActAsACourseTaggedMembership() {
        val unkeyedTimetable = group("unkeyed").copy(origin = GroupOrigin.Timetable)
        val updates = buildHomeGroupUpdates(finished, listOf(unkeyedTimetable))

        assertNull(updates.featuredGroup)
        assertNull(updates.contextLabel?.english())
        assertEquals(listOf(unkeyedTimetable), updates.rows)
    }

    @Test
    fun equallySuitableJoinedGroupsKeepTheirExistingOrder() {
        val first = group("first")
        val second = group("second")

        assertEquals(first, buildHomeGroupUpdates(finished, listOf(first, second)).featuredGroup)
        assertEquals(second, buildHomeGroupUpdates(finished, listOf(second, first)).featuredGroup)
    }

    @Test
    fun noFinishedClassKeepsOrdinaryHomeOrderDeduplicatesAndLimitsRows() {
        val first = group("first")
        val second = group("second")
        val duplicate = first.copy(name = "Duplicate summary")
        val updates = buildHomeGroupUpdates(null, listOf(first, duplicate, second, group("third")))

        assertNull(updates.featuredGroup)
        assertNull(updates.contextLabel?.english())
        assertEquals(listOf(first, second), updates.rows)
    }

    @Test
    fun featuringAGroupBeyondTheOrdinaryLimitMovesItToTheFirstRowWithoutDuplicates() {
        val first = group("first").copy(courseCode = "SWEN90014")
        val second = group("second").copy(courseCode = "ELEN90095")
        val canonical = group("course", key = "COMP90018|course")
        val updates = buildHomeGroupUpdates(finished, listOf(first, second, canonical, canonical))

        assertEquals(listOf(canonical, first), updates.rows)
        assertEquals(2, updates.rows.map(CourseGroup::id).distinct().size)
    }

    @Test
    fun unrelatedFoldedCandidatesDoNotEnterTheOrdinaryRows() {
        val first = group("first").copy(courseCode = "SWEN90014")
        val second = group("second").copy(courseCode = "ELEN90095")
        val foldedOtherCourse = group("folded", key = "SWEN90014|course")
            .copy(courseCode = "SWEN90014")
        val updates = buildHomeGroupUpdates(
            finished, listOf(first, second), listOf(foldedOtherCourse)
        )

        assertNull(updates.featuredGroup)
        assertNull(updates.contextLabel?.english())
        assertEquals(listOf(first, second), updates.rows)
    }

    @Test
    fun candidateRepositorySummaryIsUsedOnceWhenItsIdAlsoOccursInActiveRows() {
        val stale = group("course", key = "COMP90018|course")
        val current = stale.copy(
            unreadCount = 3, latestMessage = "Proposal ready", latestFileName = "proposal.pdf"
        )
        val other = group("other").copy(courseCode = "SWEN90014")
        val updates = buildHomeGroupUpdates(finished, listOf(stale, other), listOf(current))

        assertEquals(current, updates.featuredGroup)
        assertEquals(listOf(current, other), updates.rows)
    }

    @Test
    fun automaticAndRestoredMembershipCandidatesKeepTheirOrderAndIgnoreMuteSettings() {
        val automatic = group("automatic", key = "COMP90018|course")
        val restored = group("restored", key = "COMP90018|course")
        val default = group("default")
        val explicitFold = group("explicit-fold")
        val preferences = mapOf(
            automatic.id to GroupChatPreferences(muted = true),
            restored.id to GroupChatPreferences(foldedOverride = false),
            explicitFold.id to GroupChatPreferences(foldedOverride = true)
        )

        val candidates = homePostClassCandidates(
            listOf(automatic, restored, default, explicitFold), preferences
        )

        assertEquals(listOf(automatic, restored, default), candidates)
        assertTrue(automatic.isFolded(preferences.getValue(automatic.id), listOf(finished), finished.end))
        assertFalse(restored.isFolded(preferences.getValue(restored.id), listOf(finished), finished.end))
    }

    @Test
    fun engineFinishedClassCanFeatureAFoldedGroupWithNoNextClass() {
        val now = finished.end.plusMinutes(5)
        val canonical = group("course", key = "COMP90018|course")
        val sessions = listOf(finished)
        val card = buildHomeCardState(sessions, engineSnapshot(sessions, now), now)
        val candidates = homePostClassCandidates(listOf(canonical), emptyMap())
        val updates = buildHomeGroupUpdates(card.finishedSession, emptyList(), candidates)

        assertEquals(ContextState.POST_CLASS, card.state)
        assertNull(card.session)
        assertTrue(canonical.isFolded(GroupChatPreferences(), sessions, now))
        assertEquals(canonical, updates.featuredGroup)
        assertEquals(listOf(canonical), updates.rows)
    }

    @Test
    fun urgentNextClassRetainsItsGuidanceWhileTheFinishedCourseGroupIsFeatured() {
        val now = finished.end.plusMinutes(2)
        val next = nextClass().copy(start = finished.end.plusMinutes(15))
        val sessions = listOf(finished, next)
        val canonical = group("course", key = "COMP90018|course")
        val nextGroup = group("next-group").copy(courseCode = next.code)
        val snapshot = engineSnapshot(sessions, now, travelMinutes = 10)
        val card = buildHomeCardState(sessions, snapshot, now)
        val updates = buildHomeGroupUpdates(card.finishedSession, listOf(nextGroup, canonical))

        assertEquals(ContextState.LEAVE_SOON, card.state)
        assertEquals(next, card.session)
        assertEquals(snapshot.leaveBy, card.leaveBy)
        assertEquals(3L, card.departureCountdownMinutes)
        assertEquals(canonical, updates.featuredGroup)
        assertEquals(listOf(canonical, nextGroup), updates.rows)
    }

    @Test
    fun runningLateNextClassDoesNotHideTheValidatedFinishedCourse() {
        val now = finished.end.plusMinutes(2)
        val next = nextClass().copy(start = finished.end.plusMinutes(15))
        val sessions = listOf(finished, next)
        val canonical = group("course", key = "COMP90018|course")
        val card = buildHomeCardState(sessions, engineSnapshot(sessions, now, travelMinutes = 15), now)
        val updates = buildHomeGroupUpdates(card.finishedSession, listOf(canonical))

        assertEquals(ContextState.RUNNING_LATE, card.state)
        assertEquals(next, card.session)
        assertEquals(finished, card.finishedSession)
        assertEquals(canonical, updates.featuredGroup)
    }

    @Test
    fun engineWindowExpiryRemovesTheFoldedFeaturedGroupAtTheFifteenMinuteBoundary() {
        val sessions = listOf(finished)
        val canonical = group("course", key = "COMP90018|course")
        val ordinary = group("ordinary").copy(courseCode = "SWEN90014")
        val candidates = homePostClassCandidates(listOf(canonical, ordinary), emptyMap())
        val justInside = finished.end.plusMinutes(15).minusSeconds(1)
        val atExpiry = finished.end.plusMinutes(15)
        val before = buildHomeCardState(sessions, engineSnapshot(sessions, justInside), justInside)
        val after = buildHomeCardState(sessions, engineSnapshot(sessions, atExpiry), atExpiry)

        assertEquals(canonical, buildHomeGroupUpdates(before.finishedSession, listOf(ordinary), candidates).featuredGroup)
        assertEquals(ContextState.NO_UPCOMING_CLASS, after.state)
        assertNull(after.finishedSession)
        val expiredUpdates = buildHomeGroupUpdates(after.finishedSession, listOf(ordinary), candidates)
        assertNull(expiredUpdates.featuredGroup)
        assertNull(expiredUpdates.contextLabel)
        assertEquals(listOf(ordinary), expiredUpdates.rows)
        assertTrue(canonical.isFolded(GroupChatPreferences(), sessions, atExpiry))
    }

    @Test
    fun removedFinishedOccurrenceClearsFeaturedContextWhileKeepingTheNextClass() {
        val now = finished.end.plusMinutes(5)
        val next = nextClass()
        val oldSnapshot = engineSnapshot(listOf(finished, next), now)
        val card = buildHomeCardState(listOf(next), oldSnapshot, now)
        val canonical = group("course", key = "COMP90018|course")
        val ordinary = group("ordinary").copy(courseCode = next.code)
        val updates = buildHomeGroupUpdates(card.finishedSession, listOf(ordinary), listOf(canonical))

        assertEquals(next, card.session)
        assertEquals(oldSnapshot.state, card.state)
        assertNull(card.finishedSession)
        assertNull(updates.featuredGroup)
        assertNull(updates.contextLabel?.english())
        assertEquals(listOf(ordinary), updates.rows)
    }

    @Test
    fun editedFinishedOccurrenceDoesNotFeatureItsGroupFromAStaleEngineSnapshot() {
        val now = finished.end.plusMinutes(5)
        val next = nextClass()
        val oldSnapshot = engineSnapshot(listOf(finished, next), now)
        val edited = finished.copy(room = "999")
        val card = buildHomeCardState(listOf(edited, next), oldSnapshot, now)
        val canonical = group("course", key = "COMP90018|course")
        val updates = buildHomeGroupUpdates(card.finishedSession, emptyList(), listOf(canonical))

        assertEquals(next, card.session)
        assertEquals(oldSnapshot.state, card.state)
        assertNull(card.finishedSession)
        assertNull(updates.featuredGroup)
        assertTrue(updates.rows.isEmpty())
    }

    private fun nextClass() = finished.copy(
        id = "next", code = "SWEN90014", title = "Software Engineering",
        start = finished.end.plusHours(1), end = finished.end.plusHours(2)
    )

    private fun engineSnapshot(
        sessions: List<CourseSession>,
        now: ZonedDateTime,
        travelMinutes: Int? = null
    ): TravelSnapshot {
        val decision = ContextEngine.decide(
            ContextInputs(
                now = now.toInstant(), sessions = sessions, travelMinutes = travelMinutes,
                bufferMinutes = 0, distanceMeters = 700.0, isMoving = false
            )
        )
        return TravelSnapshot(
            session = decision.session, state = decision.state,
            finishedSession = decision.finishedSession,
            leaveBy = decision.leaveBy?.atZone(now.zone)
        )
    }

    private fun exactSlot() = group("exact", key = finished.timetableGroupSpec()!!.key)

    private fun group(id: String, key: String? = null) = CourseGroup(
        id = id, courseCode = "COMP90018", name = id, members = 8,
        unreadCount = 0, latestMessage = "No messages yet.", latestFileName = null,
        origin = if (key == null) GroupOrigin.Joined else GroupOrigin.Timetable,
        timetableKey = key
    )
}
