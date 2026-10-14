package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.DataError
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.request.HttpRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class RemoteErrorsTest {

    @Test
    fun inviteErrorsKeepTheirReason() {
        assertEquals(DataError.InvalidInvite.Reason.Unknown, inviteReason(dataErrorFor(400, "invite_not_found")))
        assertEquals(DataError.InvalidInvite.Reason.Expired, inviteReason(dataErrorFor(400, "invite_expired")))
        assertEquals(DataError.InvalidInvite.Reason.UsedUp, inviteReason(dataErrorFor(400, "invite_used_up")))
    }

    @Test
    fun serverMessagesTakePrecedenceOverStatusCodes() {
        assertTrue(dataErrorFor(403, "not_authenticated") is DataError.Unauthenticated)
        assertTrue(dataErrorFor(403, "not_a_member") is DataError.Forbidden)
    }

    @Test
    fun statusCodesMapToDataErrors() {
        assertTrue(dataErrorFor(401, "JWT expired") is DataError.Unauthenticated)
        assertTrue(dataErrorFor(403, "permission denied for function create_group") is DataError.Forbidden)
        assertTrue(dataErrorFor(404, "Could not find the function") is DataError.NotFound)
        assertTrue(dataErrorFor(429, null) is DataError.RateLimited)
        assertTrue(dataErrorFor(500, "internal error") is DataError.Unexpected)
    }

    @Test
    fun restExceptionsAreMappedByStatusAndMessage() {
        val error = RestException("invite_used_up", null, 400, "invite_used_up").toDataError()

        assertEquals(DataError.InvalidInvite.Reason.UsedUp, inviteReason(error))
    }

    @Test
    fun networkFailuresMeanOffline() {
        val requestFailure = HttpRequestException("Unable to resolve host", HttpRequestBuilder())

        assertTrue(requestFailure.toDataError() is DataError.Offline)
        assertTrue(UnknownHostException("example.supabase.co").toDataError() is DataError.Offline)
    }

    @Test
    fun dataErrorsPassThroughUnchanged() {
        val original = DataError.NotFound()

        assertSame(original, original.toDataError())
    }

    @Test
    fun otherFailuresAreUnexpected() {
        assertTrue(IllegalStateException("boom").toDataError() is DataError.Unexpected)
    }

    private fun inviteReason(error: DataError): DataError.InvalidInvite.Reason =
        (error as DataError.InvalidInvite).reason
}
