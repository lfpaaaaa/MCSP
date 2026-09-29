package au.edu.unimelb.campuscompanion.data.fake

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeChatRepositoryTest {
    private val chat = FakeChatRepository(latencyMillis = 0)

    @Test
    fun sentMessageIsStoredOnceAsSent() = runBlocking<Unit> {
        val sent = chat.sendMessage(FakeData.TEAM_GROUP_ID, "  Meeting moved to 3 pm  ").getOrThrow()

        assertEquals("Meeting moved to 3 pm", sent.body)
        assertEquals(MessageStatus.Sent, sent.status)
        val messages = chat.observeMessages(FakeData.TEAM_GROUP_ID).first()
        assertEquals(sent, messages.last())
        assertEquals(1, messages.count { it.clientId == sent.clientId })
    }

    @Test
    fun failedMessageCanBeRetried() = runBlocking<Unit> {
        chat.failSends = true
        val failure = chat.sendMessage(FakeData.TEAM_GROUP_ID, "Hello").exceptionOrNull()
        assertTrue(failure is DataError.Offline)
        val failed = chat.observeMessages(FakeData.TEAM_GROUP_ID).first().last()
        assertEquals(MessageStatus.Failed, failed.status)

        chat.failSends = false
        val retried = chat.retryMessage(FakeData.TEAM_GROUP_ID, failed.clientId).getOrThrow()

        assertEquals(MessageStatus.Sent, retried.status)
        val messages = chat.observeMessages(FakeData.TEAM_GROUP_ID).first()
        assertEquals(1, messages.count { it.clientId == failed.clientId })
    }

    @Test
    fun blankMessageIsRejected() = runBlocking<Unit> {
        val error = chat.sendMessage(FakeData.TEAM_GROUP_ID, "   ").exceptionOrNull()

        assertTrue(error is DataError.Validation)
    }
}
