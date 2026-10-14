package au.edu.unimelb.campuscompanion.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertFalse(isValidGroupJoinCode("ABC😀1"))
    }

    @Test
    fun reopeningKeepsTheOriginalCodeAndCreationDeadline() {
        val createdAt = Instant.parse("2026-10-14T02:00:00Z")
        val expiry = createdAt.plusSeconds(GROUP_JOIN_CODE_LIFETIME_SECONDS)
        val firstDialog = StartedGroupAccess("Study group", "A1B2C3", expiry)
        assertEquals("A1B2C3", firstDialog.activeJoinCode(createdAt))

        val reopened = StartedGroupAccess("Study group", firstDialog.joinCode, expiry)
        assertEquals("A1B2C3", reopened.activeJoinCode(createdAt.plusSeconds(299)))
        assertNull(reopened.activeJoinCode(createdAt.plusSeconds(300)))
        assertNull(reopened.activeJoinCode(createdAt.plusSeconds(301)))
    }

    @Test
    fun missingExpiryOrInvalidCodeCannotBeShared() {
        val now = Instant.parse("2026-10-14T02:00:00Z")
        assertNull(StartedGroupAccess("Study group", "A1B2C3", null).activeJoinCode(now))
        assertNull(StartedGroupAccess("Study group", null, now.plusSeconds(300)).activeJoinCode(now))
        assertNull(StartedGroupAccess("Study group", "bad", now.plusSeconds(300)).activeJoinCode(now))
    }

}
