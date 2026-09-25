package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import au.edu.unimelb.campuscompanion.data.remote.GroupRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.dataResult
import au.edu.unimelb.campuscompanion.data.remote.toModel

/**
 * [InviteRepository] backed by [remote]. After a successful join, [groups] is refreshed so that
 * the new group appears in the group list straight away.
 */
class DefaultInviteRepository(
    private val remote: GroupRemoteDataSource,
    private val groups: GroupRepository
) : InviteRepository {

    override suspend fun createInvite(groupId: String): Result<GroupInvite> =
        dataResult { remote.createInvite(groupId).toModel(groupId) }

    /** Accepts either a bare token or a full join link, such as the text of a scanned QR code. */
    override suspend fun joinWithToken(token: String): Result<Group> {
        val normalized = GroupInvite.tokenFromUri(token) ?: token.trim()
        if (normalized.isEmpty() || normalized.any(Char::isWhitespace)) {
            return Result.failure(DataError.InvalidInvite(DataError.InvalidInvite.Reason.Unknown))
        }
        return dataResult { remote.joinWithToken(normalized).toModel() }
            .onSuccess { groups.refresh() }
    }
}
