package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.ui.text.UiText
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRemoteErrorsTest {
    @Test fun missingAttachmentDeploymentHasAnActionableMessage() {
        listOf(
            "column shared_files.allow_downloads does not exist",
            "Could not find the 'allow_downloads' column of 'shared_files' in the schema cache",
            "Could not find the function public.group_file_access in the schema cache",
            "Could not find the function public.group_files in the schema cache",
            "Bucket not found"
        ).forEach { message ->
            val mapped = RestException(message, null, 400, message).toFileDataError()
            assertEquals(UiText(R.string.attachment_service_unavailable), (mapped as DataError.Validation).userMessage)
        }
    }

    @Test fun ordinaryAccessAndNetworkErrorsKeepTheirMeaning() {
        assertTrue(RestException("not_a_member", null, 403, "denied").toFileDataError() is DataError.Forbidden)
        assertTrue(RestException("Object not found", null, 404, "missing").toFileDataError() is DataError.NotFound)
        assertTrue(java.io.IOException().toFileDataError() is DataError.Offline)
        assertTrue(RestException("invalid allow_downloads value", null, 400, "invalid").toFileDataError() is DataError.Unexpected)
    }

    @Test fun cancellationIsNeverShownAsAnUploadFailure() {
        val cancellation = CancellationException("cancelled")
        try {
            fileRemoteCall<Unit> { throw cancellation }
            throw AssertionError("Expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }
}
