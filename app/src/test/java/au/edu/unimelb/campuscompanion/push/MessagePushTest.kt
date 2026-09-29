package au.edu.unimelb.campuscompanion.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagePushTest {

    @Test
    fun aGroupMessageBecomesANotification() {
        val push = MessagePush.from(
            mapOf(
                "type" to "group_message",
                "message_id" to "m1",
                "group_id" to "g1",
                "group_name" to "Mobile team",
                "sender_name" to "Alice",
                "preview" to "Meeting moved to 3 pm "
            )
        )

        assertEquals("Mobile team", push?.title)
        assertEquals("Alice: Meeting moved to 3 pm", push?.text)
        assertEquals("g1".hashCode(), push?.notificationId)
    }

    @Test
    fun missingDetailsGetPlainDefaults() {
        val push = MessagePush.from(mapOf("type" to "group_message", "group_id" to "g1"))

        assertEquals("Group", push?.title)
        assertEquals("A member sent a message", push?.text)
    }

    @Test
    fun otherPushesAreIgnored() {
        assertNull(MessagePush.from(mapOf("type" to "marketing", "group_id" to "g1")))
        assertNull(MessagePush.from(mapOf("type" to "group_message")))
        assertNull(MessagePush.from(emptyMap()))
    }
}
