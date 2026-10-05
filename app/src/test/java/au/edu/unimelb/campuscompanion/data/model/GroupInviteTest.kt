package au.edu.unimelb.campuscompanion.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GroupInviteTest {
    private val expiresAt = Instant.parse("2026-09-25T04:10:00Z")
    private val invite = GroupInvite(groupId = "group-1", token = "a1B2c3", expiresAt = expiresAt)

    @Test
    fun joinUriRoundTripsTheToken() {
        assertEquals("campuscompanion://join?token=a1B2c3", invite.joinUri)
        assertEquals("a1B2c3", GroupInvite.tokenFromUri(invite.joinUri))
    }

    @Test
    fun ignoresUrisThatAreNotInvites() {
        assertNull(GroupInvite.tokenFromUri("https://example.com/join?token=a1B2c3"))
        assertNull(GroupInvite.tokenFromUri("campuscompanion://join?token="))
        assertNull(GroupInvite.tokenFromUri("campuscompanion://join?token=a1 B2"))
    }

    @Test
    fun typedGroupCodesAreTidiedUp() {
        assertEquals("ABC234", GroupInvite.joinCodeFromText(" abc 234 "))
        assertEquals("ABC234", GroupInvite.joinCodeFromText("abc-234"))
        assertNull(GroupInvite.joinCodeFromText("abc23"))
        assertNull(GroupInvite.joinCodeFromText("abc2345"))
        assertNull(GroupInvite.joinCodeFromText("a".repeat(64)))
    }

    @Test
    fun expiresAtTheDeadline() {
        assertFalse(invite.isExpired(expiresAt.minusSeconds(1)))
        assertTrue(invite.isExpired(expiresAt))
    }
}
