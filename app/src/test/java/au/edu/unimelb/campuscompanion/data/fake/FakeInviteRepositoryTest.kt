package au.edu.unimelb.campuscompanion.data.fake

import au.edu.unimelb.campuscompanion.data.DataError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FakeInviteRepositoryTest {
    private var now = Instant.parse("2026-09-25T04:00:00Z")
    private val groups = FakeGroupRepository(latencyMillis = 0, clock = { now })
    private val invites = FakeInviteRepository(groups, latencyMillis = 0, clock = { now })

    @Test
    fun demoTokenJoinsTheSampleGroup() = runBlocking<Unit> {
        val group = invites.joinWithToken(FakeData.DEMO_INVITE_TOKEN).getOrThrow()

        assertEquals(FakeData.JOINABLE_GROUP_ID, group.id)
        assertTrue(groups.observeMyGroups().first().any { it.group.id == group.id })
    }

    @Test
    fun joiningTwiceKeepsOneMembership() = runBlocking<Unit> {
        invites.joinWithToken(FakeData.DEMO_INVITE_TOKEN).getOrThrow()
        invites.joinWithToken(FakeData.DEMO_INVITE_TOKEN).getOrThrow()

        val memberships = groups.observeMyGroups().first().count { it.group.id == FakeData.JOINABLE_GROUP_ID }
        assertEquals(1, memberships)
    }

    @Test
    fun unknownTokenIsRejected() = runBlocking<Unit> {
        val error = invites.joinWithToken("not-a-real-token").exceptionOrNull()

        assertTrue(error is DataError.InvalidInvite)
        assertEquals(DataError.InvalidInvite.Reason.Unknown, (error as DataError.InvalidInvite).reason)
    }

    @Test
    fun inviteExpiresAfterItsLifetime() = runBlocking<Unit> {
        val invite = invites.createInvite(FakeData.TEAM_GROUP_ID).getOrThrow()
        now = now.plus(FakeInviteRepository.DEFAULT_INVITE_LIFETIME).plus(Duration.ofSeconds(1))

        val error = invites.joinWithToken(invite.token).exceptionOrNull()

        assertTrue(error is DataError.InvalidInvite)
        assertEquals(DataError.InvalidInvite.Reason.Expired, (error as DataError.InvalidInvite).reason)
    }

    @Test
    fun onlyMembersCanCreateInvites() = runBlocking<Unit> {
        val error = invites.createInvite(FakeData.JOINABLE_GROUP_ID).exceptionOrNull()

        assertTrue(error is DataError.Forbidden)
    }
}
