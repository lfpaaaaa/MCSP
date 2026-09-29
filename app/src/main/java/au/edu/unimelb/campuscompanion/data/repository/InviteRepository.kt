package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupInvite

/** Short-lived invitations shared by QR code and NFC. */
interface InviteRepository {
    /** Issues a new invite for [groupId]. Only members of the group can create invites. */
    suspend fun createInvite(groupId: String): Result<GroupInvite>

    /**
     * Joins the group behind [token]. Joining a group the user already belongs to succeeds
     * without using up the invite.
     */
    suspend fun joinWithToken(token: String): Result<Group>
}
