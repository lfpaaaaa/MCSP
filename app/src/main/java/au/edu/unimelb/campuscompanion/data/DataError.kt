package au.edu.unimelb.campuscompanion.data

/**
 * Failures reported by the repositories. Screens map each case to a short explanation and a
 * recovery action instead of showing raw exception text.
 */
sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The device has no usable network connection. */
    class Offline(cause: Throwable? = null) : DataError("No network connection", cause)

    /** The session is missing or has expired, so the user has to sign in again. */
    class Unauthenticated(cause: Throwable? = null) : DataError("Not signed in", cause)

    /** Row-level security or a server-side check refused the request. */
    class Forbidden(cause: Throwable? = null) : DataError("Not allowed", cause)

    /** The requested record does not exist or is not visible to the user. */
    class NotFound(cause: Throwable? = null) : DataError("Not found", cause)

    /** The invite token is unknown, has expired or has no uses left. */
    class InvalidInvite(
        val reason: Reason,
        cause: Throwable? = null
    ) : DataError("Invite is not valid: $reason", cause) {
        enum class Reason { Unknown, Expired, UsedUp }
    }

    /** The server asked the client to slow down. */
    class RateLimited(cause: Throwable? = null) : DataError("Too many requests", cause)

    /** The input was rejected before it was sent, for example an empty message. */
    class Validation(message: String) : DataError(message)

    /** Any other failure. */
    class Unexpected(cause: Throwable? = null) : DataError("Unexpected error", cause)
}
