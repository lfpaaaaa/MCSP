package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.remote.SharedFileRow

/**
 * Keeps the last file list of each group on the device, so an open chat still shows what was
 * shared when there is no connection. The rows are stored as the server returned them and
 * replaced after every successful refresh, upload or deletion; they are kept per user so that
 * someone else signing in on the same device never sees them.
 */
interface SharedFileListCache {
    /** The saved files of [groupId] for [userId], or null when there are none or they cannot be read. */
    fun load(userId: String, groupId: String): List<SharedFileRow>?

    fun save(userId: String, groupId: String, rows: List<SharedFileRow>)
}
