package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.model.GroupSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GroupMappingsTest {
    private val created = Instant.parse("2026-10-14T01:00:00Z")
    private val group = Group("group", "Project group", "COMP90018", "owner", created, "A1B2C3")

    @Test fun creationAndSummaryUseTheSameOriginalExpiry() {
        val fresh = group.toCourseGroup("owner", 1)
        val summary = summary().toCourseGroup("owner")
        assertEquals(created.plusSeconds(300), fresh.joinCodeExpiresAt)
        assertEquals(fresh.joinCodeAccess(), summary.joinCodeAccess())
        assertEquals("A1B2C3", summary.joinCode)
        assertNull(summary.joinCodeAccess().activeJoinCode(created.plusSeconds(300)))
    }

    @Test fun summaryRoleRemainsAuthoritativeAfterOwnershipTransfer() {
        assertEquals(GroupOrigin.Joined, summary(GroupRole.Member).toCourseGroup("owner").origin)
        assertEquals(GroupOrigin.CreatedByUser, summary(GroupRole.Owner).toCourseGroup("other").origin)
    }

    @Test fun timetableOriginAndKeysSurviveBothMappings() {
        val timetable = group.copy(name = "COMP90018 tutorial,,,", timetableKey = "COMP90018|tutorial", timetableSlot = "Tuesday")
        val fresh = timetable.toCourseGroup("owner", 12)
        val listed = summary().copy(group = timetable).toCourseGroup("owner")
        listOf(fresh, listed).forEach {
            assertEquals(GroupOrigin.Timetable, it.origin)
            assertEquals(timetable.timetableKey, it.timetableKey)
            assertEquals(timetable.timetableSlot, it.timetableSlot)
        }
        assertEquals("COMP90018 tutorial", listed.name)
    }

    @Test fun expiryStringRoundTripNeverRestartsItsLifetimeAndToleratesOlderState() {
        val expiry = group.toCourseGroup("owner", 1).joinCodeExpiresAt!!
        assertEquals(expiry, restoreJoinCodeExpiresAt(expiry.toString()))
        assertNull(restoreJoinCodeExpiresAt(null))
        assertNull(restoreJoinCodeExpiresAt("invalid"))
        val restored = group.toCourseGroup("owner", 1).copy(joinCodeExpiresAt = restoreJoinCodeExpiresAt(expiry.toString()))
        assertNull(restored.joinCodeAccess().activeJoinCode(expiry))
    }

    private fun summary(role: GroupRole = GroupRole.Owner) = GroupSummary(
        group, role, 1, 0, null, created, null
    )
}
