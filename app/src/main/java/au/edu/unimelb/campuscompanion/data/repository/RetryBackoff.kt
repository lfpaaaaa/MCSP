package au.edu.unimelb.campuscompanion.data.repository

/** Shared capped exponential backoff for chat, file and membership subscriptions. */
internal fun retryDelayMillis(attempt: Int, firstDelayMillis: Long, maxDelayMillis: Long): Long {
    val factor = 1L shl (attempt - 1).coerceIn(0, 6)
    return (firstDelayMillis * factor).coerceAtMost(maxDelayMillis)
}
