package au.edu.unimelb.campuscompanion.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GroupJoinCodeTest {
    @Test
    fun inputIsUppercasedFilteredAndLimitedToSixCharacters() {
        assertEquals("A1B2C3", normalizeGroupJoinCode("a1-b2 c3d4"))
    }

    @Test
    fun onlySixAsciiLettersAndDigitsAreValid() {
        assertTrue(isValidGroupJoinCode("A1B2C3"))
        assertFalse(isValidGroupJoinCode("A1B2C"))
        assertFalse(isValidGroupJoinCode("A1B2-3"))
        assertFalse(isValidGroupJoinCode("ABC你好1"))
    }

    @Test
    fun codeIsValidJustBeforeTheOriginalFiveMinuteExpiry() {
        val createdAt = Instant.parse("2026-10-14T04:00:00Z")
        val expiry = groupJoinCodeExpiresAt(createdAt)
        val access = StartedGroupAccess("Revision", "ABC234", expiry)

        assertEquals(createdAt.plusSeconds(300), expiry)
        assertEquals("ABC234", access.activeJoinCode(expiry.minusNanos(1)))
        assertNull(access.activeJoinCode(expiry))
        assertNull(access.activeJoinCode(expiry.plusNanos(1)))
    }

    @Test
    fun missingInvalidOrUnknownExpiryCodesAreUnavailable() {
        val now = Instant.parse("2026-10-14T04:00:00Z")
        listOf(null, "", "ABC23", "ABC2345", "abc234", "ABC-34", "ABC你好1").forEach { code ->
            assertNull(StartedGroupAccess("Revision", code, now.plusSeconds(300)).activeJoinCode(now))
        }
        assertNull(StartedGroupAccess("Revision", "ABC234", null).activeJoinCode(now))
    }

    @Test
    fun reopeningWithTheSavedGroupDoesNotExtendItsExpiry() {
        val createdAt = Instant.parse("2026-10-14T04:00:00Z")
        val group = CourseGroup(
            id = "group-1", courseCode = "COMP90018", name = "Revision", members = 2,
            unreadCount = 0, latestMessage = "", latestFileName = null,
            origin = GroupOrigin.Joined, joinCode = "ABC234", joinCodeExpiresAt = groupJoinCodeExpiresAt(createdAt)
        )
        val firstOpening = group.joinCodeAccess()
        val reopened = group.copy().joinCodeAccess()

        assertEquals(firstOpening, reopened)
        assertEquals(createdAt.plusSeconds(300), reopened.expiresAt)
        assertEquals("ABC234", reopened.activeJoinCode(createdAt.plusSeconds(299)))
        assertNull(reopened.activeJoinCode(createdAt.plusSeconds(300)))
    }
}
