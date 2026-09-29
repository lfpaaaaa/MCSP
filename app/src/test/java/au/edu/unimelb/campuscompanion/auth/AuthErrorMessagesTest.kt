package au.edu.unimelb.campuscompanion.auth

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.toUserMessage
import io.github.jan.supabase.auth.exception.AuthRestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class AuthErrorMessagesTest {

    @Test
    fun aWrongOrExpiredCodePointsToTheNewestEmail() {
        val message = authErrorMessage(AuthRestException("otp_expired", "Token has expired or is invalid", 403))

        assertTrue(message.contains("newest email"))
        assertFalse(message.contains("Token has expired"))
    }

    @Test
    fun rateLimitsAskTheUserToWait() {
        val message = authErrorMessage(AuthRestException("over_email_send_rate_limit", "Email rate limit exceeded", 429))

        assertTrue(message.contains("Wait a minute"))
    }

    @Test
    fun networkFailuresExplainTheConnectionProblem() {
        assertEquals(
            DataError.Offline().toUserMessage().body,
            authErrorMessage(UnknownHostException("example.supabase.co"))
        )
    }

    @Test
    fun unknownServerErrorsUseTheGeneralMessage() {
        assertEquals(
            DataError.Unexpected().toUserMessage().body,
            authErrorMessage(AuthRestException("some_new_code", "Something new happened", 500))
        )
    }
}
