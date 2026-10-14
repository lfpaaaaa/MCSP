package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import au.edu.unimelb.campuscompanion.data.remote.GroupRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.dataResult
import au.edu.unimelb.campuscompanion.data.remote.toModel

/**
 * [InviteRepository] backed by [remote]. Joins run through [groups] so that the returned group
 * is published and cached even when the subsequent summary refresh fails.
 */
class DefaultInviteRepository(
    private val remote: GroupRemoteDataSource,
    private val groups: GroupRepository
) : InviteRepository {

    override suspend fun createInvite(groupId: String): Result<GroupInvite> =
        dataResult { remote.createInvite(groupId).toModel(groupId) }

    /** Accepts either a bare token or a full join link, such as the text of a scanned QR code. */
    override suspend fun joinWithToken(token: String): Result<Group> {
        val normalized = GroupInvite.tokenFromUri(token) ?: GroupInvite.joinCodeFromText(token) ?: token.trim()
        if (normalized.isEmpty() || normalized.any(Char::isWhitespace)) {
            return Result.failure(DataError.InvalidInvite(DataError.InvalidInvite.Reason.Unknown))
        }
        return groups.joinWithToken(normalized)
    }
}
