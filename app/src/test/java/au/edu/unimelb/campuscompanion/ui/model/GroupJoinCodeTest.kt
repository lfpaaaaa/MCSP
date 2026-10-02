package au.edu.unimelb.campuscompanion.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(isValidGroupJoinCode("ABC你好1"))
    }
}
