package au.edu.unimelb.campuscompanion.data.fake

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.Group
import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import au.edu.unimelb.campuscompanion.data.repository.InviteRepository
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [InviteRepository]. [FakeData.DEMO_INVITE_TOKEN] always joins the sample group that
 * the user has not joined yet.
 */
class FakeInviteRepository(
    private val groups: FakeGroupRepository,
    private val latencyMillis: Long = FakeData.DEFAULT_LATENCY_MILLIS,
    private val inviteLifetime: Duration = DEFAULT_INVITE_LIFETIME,
    private val clock: () -> Instant = Instant::now
) : InviteRepository {

    private val invites = ConcurrentHashMap<String, GroupInvite>().apply {
        put(
            FakeData.DEMO_INVITE_TOKEN,
            GroupInvite(FakeData.JOINABLE_GROUP_ID, FakeData.DEMO_INVITE_TOKEN, Instant.MAX)
        )
    }

    override suspend fun createInvite(groupId: String): Result<GroupInvite> {
        delay(latencyMillis)
        if (!groups.isMember(groupId)) {
            return Result.failure(DataError.Forbidden())
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        val invite = GroupInvite(groupId, token, clock().plus(inviteLifetime))
        invites[token] = invite
        return Result.success(invite)
    }

    override suspend fun joinWithToken(token: String): Result<Group> {
        delay(latencyMillis)
        val invite = invites[token.trim()]
            ?: return Result.failure(DataError.InvalidInvite(DataError.InvalidInvite.Reason.Unknown))
        if (invite.isExpired(clock())) {
            return Result.failure(DataError.InvalidInvite(DataError.InvalidInvite.Reason.Expired))
        }
        val group = groups.findGroup(invite.groupId)
            ?: return Result.failure(DataError.NotFound())
        groups.addCurrentUser(group)
        return Result.success(group)
    }

    companion object {
        val DEFAULT_INVITE_LIFETIME: Duration = Duration.ofMinutes(10)
    }
}
