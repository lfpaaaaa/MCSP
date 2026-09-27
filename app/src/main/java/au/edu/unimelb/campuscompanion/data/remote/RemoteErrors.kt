package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.DataError
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Converts a failure from supabase-kt or the network stack into a [DataError], so that callers
 * never have to handle library exceptions.
 */
fun Throwable.toDataError(): DataError = when (this) {
    is DataError -> this
    is RestException -> dataErrorFor(this.statusCode, this.error, this)
    is HttpRequestException, is IOException -> DataError.Offline(this)
    else -> DataError.Unexpected(this)
}

/**
 * Maps a PostgREST error response to a [DataError]. [message] is the Postgres error message. The
 * group functions raise fixed messages such as `invite_expired`, which take precedence over the
 * HTTP status code.
 */
fun dataErrorFor(statusCode: Int, message: String?, cause: Throwable? = null): DataError =
    when (message) {
        "invite_not_found" -> DataError.InvalidInvite(DataError.InvalidInvite.Reason.Unknown, cause)
        "invite_expired" -> DataError.InvalidInvite(DataError.InvalidInvite.Reason.Expired, cause)
        "invite_used_up" -> DataError.InvalidInvite(DataError.InvalidInvite.Reason.UsedUp, cause)
        "not_authenticated" -> DataError.Unauthenticated(cause)
        "not_a_member" -> DataError.Forbidden(cause)
        else -> when (statusCode) {
            401 -> DataError.Unauthenticated(cause)
            403 -> DataError.Forbidden(cause)
            404 -> DataError.NotFound(cause)
            429 -> DataError.RateLimited(cause)
            else -> DataError.Unexpected(cause)
        }
    }

/** Runs [block] and rethrows any failure as a [DataError]. Cancellation is passed through. */
internal inline fun <T> remoteCall(block: () -> T): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        throw error.toDataError()
    }

/** Runs [block] and returns its outcome as a [Result] whose failures are always [DataError]s. */
internal inline fun <T> dataResult(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error.toDataError())
    }
