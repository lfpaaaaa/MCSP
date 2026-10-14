package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.ui.text.UiText
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException

/** A missing attachment deployment cannot be repaired by restarting the phone. */
internal fun Throwable.toFileDataError(): DataError {
    if (this is RestException && statusCode in setOf(400, 404)) {
        val missingSchema = error.contains("does not exist", ignoreCase = true) ||
            error.contains("Could not find", ignoreCase = true)
        val attachmentSchema = error.contains("allow_downloads") ||
            error.contains("group_files") || error.contains("group_file_access")
        if ((missingSchema && attachmentSchema) || error.equals("Bucket not found", ignoreCase = true)) {
            return DataError.Validation(UiText(R.string.attachment_service_unavailable))
        }
    }
    return toDataError()
}

internal inline fun <T> fileRemoteCall(block: () -> T): T = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    throw error.toFileDataError()
}
