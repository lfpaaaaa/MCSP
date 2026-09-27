package au.edu.unimelb.campuscompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class ErrorMessagesTest {
    private val inviteErrors = DataError.InvalidInvite.Reason.entries.map { DataError.InvalidInvite(it) }

    @Test
    fun everyErrorIsExplainedInPlainWords() {
        val errors = listOf(
            DataError.Offline(IllegalStateException("socket closed")),
            DataError.Unauthenticated(),
            DataError.Forbidden(),
            DataError.NotFound(),
            DataError.RateLimited(),
            DataError.Validation("Messages need 1 to 2000 characters."),
            DataError.Unexpected(IllegalStateException("boom"))
        ) + inviteErrors

        errors.forEach { error ->
            val message = error.toUserMessage()
            assertTrue(message.title.isNotBlank())
            assertTrue(message.body.isNotBlank())
            assertFalse(message.body.contains("socket closed") || message.body.contains("boom"))
        }
    }

    @Test
    fun failuresThatMayPassOfferARetry() {
        assertEquals(RecoveryAction.Retry, DataError.Offline().toUserMessage().action)
        assertEquals(RecoveryAction.Retry, DataError.RateLimited().toUserMessage().action)
        assertEquals(RecoveryAction.Retry, DataError.Unexpected().toUserMessage().action)
    }

    @Test
    fun anEndedSessionAsksTheUserToSignIn() {
        assertEquals(RecoveryAction.SignIn, DataError.Unauthenticated().toUserMessage().action)
    }

    @Test
    fun eachInviteProblemHasItsOwnExplanation() {
        val messages = inviteErrors.map { it.toUserMessage() }

        assertEquals(3, messages.map { it.body }.toSet().size)
        assertTrue(messages.all { it.action == RecoveryAction.ScanNewInvite })
    }

    @Test
    fun validationMessagesAreShownAsWritten() {
        val message = DataError.Validation("Messages need 1 to 2000 characters.").toUserMessage()

        assertEquals("Messages need 1 to 2000 characters.", message.body)
    }

    @Test
    fun libraryExceptionsAreExplainedToo() {
        assertEquals(DataError.Offline().toUserMessage(), UnknownHostException("example.supabase.co").toUserMessage())
    }
}
