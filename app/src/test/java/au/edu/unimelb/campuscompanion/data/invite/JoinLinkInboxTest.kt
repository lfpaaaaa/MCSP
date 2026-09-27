package au.edu.unimelb.campuscompanion.data.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinLinkInboxTest {
    private val inbox = JoinLinkInbox()

    @Test
    fun joinLinksLeaveAPendingToken() {
        assertTrue(inbox.offer("campuscompanion://join?token=abc123"))

        assertEquals("abc123", inbox.pendingToken.value)
    }

    @Test
    fun otherLinksAreIgnored() {
        assertFalse(inbox.offer("campuscompanion://login-callback?code=xyz"))
        assertFalse(inbox.offer("https://example.com/join?token=abc123"))
        assertFalse(inbox.offer(null))

        assertNull(inbox.pendingToken.value)
    }

    @Test
    fun aNewerLinkReplacesTheOlderOne() {
        inbox.offer("campuscompanion://join?token=first")
        inbox.offer("campuscompanion://join?token=second")

        assertEquals("second", inbox.pendingToken.value)
    }

    @Test
    fun clearingForgetsThePendingToken() {
        inbox.offer("campuscompanion://join?token=abc123")

        inbox.clear()

        assertNull(inbox.pendingToken.value)
    }
}
