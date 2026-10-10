package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow

/**
 * Keeps the last group list of each user on the device, so the Groups screen opens without a
 * connection and the chats cached in Room can still be reached. The rows are stored as the
 * server returned them and replaced after every successful refresh.
 */
interface GroupListCache {
    /** The saved list of [userId], or null when there is none or it cannot be read. */
    fun load(userId: String): List<GroupSummaryRow>?

    fun save(userId: String, rows: List<GroupSummaryRow>)
}
